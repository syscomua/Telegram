package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManagerFixed;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FavoriteChannelsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * Posts of all favorite channels in one list, newest first.
 * Reads the regular local message table; nothing is copied anywhere.
 * Its own screen instead of ChatActivity: ChatActivity is built around a single dialog.
 */
public class FavoriteFeedActivity extends BaseFragment {

    private static final int PAGE_SIZE = 50;

    private static final int ROW_HEADER = 0;
    private static final int ROW_MESSAGE = 1;
    private static final int ROW_LOADING = 2;

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private GridLayoutManagerFixed layoutManager;
    private ListAdapter adapter;
    private TextView emptyView;

    /** Newest first, as stored rows are read: ORDER BY date DESC, mid DESC. */
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final HashSet<String> messageKeys = new HashSet<>();
    private final LongSparseArray<MessageObject.GroupedMessages> groups = new LongSparseArray<>();
    private final ArrayList<Row> rows = new ArrayList<>();

    private ArrayList<Long> channels = new ArrayList<>();
    private boolean loading;
    private boolean endReached;
    private int cursorDate;
    private int cursorMid;
    private int loadGeneration;

    private static class Row {
        final int type;
        final MessageObject message;

        Row(int type, MessageObject message) {
            this.type = type;
            this.message = message;
        }
    }

    private static String key(long dialogId, int mid) {
        return dialogId + "_" + mid;
    }

    @Override
    public boolean onFragmentCreate() {
        channels = FavoriteChannelsController.getInstance(currentAccount).getAll();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.FavoriteFeed));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        updateSubtitle();

        contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(4));
        listView.setItemAnimator(null);
        layoutManager = new GridLayoutManagerFixed(context, 1000, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean shouldLayoutChildFromOpositeSide(View child) {
                return false;
            }

            @Override
            protected boolean hasSiblingChild(int position) {
                final MessageObject message = getRowMessage(position);
                final MessageObject.GroupedMessages group = getValidGroup(message);
                if (group == null) {
                    return false;
                }
                final MessageObject.GroupedMessagePosition pos = group.getPosition(message);
                if (pos.minX == pos.maxX || pos.minY != pos.maxY || pos.minY == 0) {
                    return false;
                }
                for (int a = 0; a < group.posArray.size(); a++) {
                    final MessageObject.GroupedMessagePosition p = group.posArray.get(a);
                    if (p != pos && p.minY <= pos.minY && p.maxY >= pos.minY) {
                        return true;
                    }
                }
                return false;
            }
        };
        layoutManager.setSpanSizeLookup(new GridLayoutManagerFixed.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final MessageObject message = getRowMessage(position);
                final MessageObject.GroupedMessages group = getValidGroup(message);
                if (group != null) {
                    return group.getPosition(message).spanSize;
                }
                return 1000;
            }
        });
        listView.setLayoutManager(layoutManager);
        // same as in ChatActivity: pulls album parts that share a row up to their siblings
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                outRect.bottom = 0;
                if (!(view instanceof ChatMessageCell)) {
                    return;
                }
                final ChatMessageCell cell = (ChatMessageCell) view;
                final MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                final MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                if (group == null || position == null || position.siblingHeights == null) {
                    return;
                }
                final float maxHeight = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.5f;
                int h = cell.getExtraInsetHeight();
                for (int a = 0; a < position.siblingHeights.length; a++) {
                    h += (int) Math.ceil(maxHeight * position.siblingHeights[a]);
                }
                h += (position.maxY - position.minY) * Math.round(7 * AndroidUtilities.density);
                for (int a = 0; a < group.posArray.size(); a++) {
                    final MessageObject.GroupedMessagePosition pos = group.posArray.get(a);
                    if (pos.minY != position.minY || pos.minX == position.minX && pos.maxX == position.maxX && pos.minY == position.minY && pos.maxY == position.maxY) {
                        continue;
                    }
                    if (pos.minY == position.minY) {
                        h -= (int) Math.ceil(maxHeight * pos.ph) - dp(4);
                        break;
                    }
                }
                outRect.bottom = -h;
            }
        });
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            final Row row = position >= 0 && position < rows.size() ? rows.get(position) : null;
            if (row == null || row.message == null) {
                return;
            }
            if (row.type == ROW_HEADER) {
                openChannel(row.message.getDialogId(), 0);
            } else {
                openChannel(row.message.getDialogId(), row.message.getId());
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                checkLoadMore();
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(getThemedColor(Theme.key_chat_serviceText));
        emptyView.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_chat_serviceBackground)));
        emptyView.setPadding(dp(16), dp(10), dp(16), dp(10));
        emptyView.setText(getString(channels.isEmpty() ? R.string.FavoriteFeedEmptyHint : R.string.FavoriteFeedNoPosts));
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        fragmentView = contentView;
        loadMore();
        return fragmentView;
    }

    private void updateSubtitle() {
        if (actionBar != null) {
            actionBar.setSubtitle(formatPluralString("FavoriteFeedChannels", channels.size()));
        }
    }

    private void openChannel(long dialogId, int messageId) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", -dialogId);
        if (messageId != 0) {
            args.putInt("message_id", messageId);
        }
        presentFragment(new ChatActivity(args));
    }

    private MessageObject getRowMessage(int position) {
        if (position < 0 || position >= rows.size()) {
            return null;
        }
        final Row row = rows.get(position);
        return row.type == ROW_MESSAGE ? row.message : null;
    }

    private MessageObject.GroupedMessages getValidGroup(MessageObject message) {
        if (message == null || message.getGroupId() == 0) {
            return null;
        }
        final MessageObject.GroupedMessages group = groups.get(message.getGroupId());
        if (group == null || group.messages.size() <= 1 || group.getPosition(message) == null) {
            return null;
        }
        return group;
    }

    private void checkLoadMore() {
        if (loading || endReached || layoutManager == null) {
            return;
        }
        final int last = layoutManager.findLastVisibleItemPosition();
        if (last >= rows.size() - 10) {
            loadMore();
        }
    }

    private void loadMore() {
        if (loading || endReached) {
            return;
        }
        if (channels.isEmpty()) {
            endReached = true;
            updateRows();
            return;
        }
        loading = true;
        updateRows();
        final int generation = loadGeneration;
        final String ids = TextUtils.join(",", channels);
        final int fromDate = cursorDate;
        final int fromMid = cursorMid;
        final long clientUserId = getUserConfig().getClientUserId();
        final MessagesStorage storage = getMessagesStorage();
        storage.getStorageQueue().postRunnable(() -> {
            final ArrayList<TLRPC.Message> result = new ArrayList<>();
            final ArrayList<Long> usersToLoad = new ArrayList<>();
            final ArrayList<Long> chatsToLoad = new ArrayList<>();
            int rowsRead = 0;
            int lastDate = fromDate;
            int lastMid = fromMid;
            SQLiteCursor cursor = null;
            try {
                final String after = fromDate == 0 ? "" : String.format(Locale.US, " AND (date < %d OR (date = %d AND mid < %d))", fromDate, fromDate, fromMid);
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT data, mid, uid, date, custom_params FROM messages_v2 WHERE uid IN (%s)%s ORDER BY date DESC, mid DESC LIMIT %d", ids, after, PAGE_SIZE));
                while (cursor.next()) {
                    rowsRead++;
                    lastMid = cursor.intValue(1);
                    lastDate = cursor.intValue(3);
                    final NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    message.readAttachPath(data, clientUserId);
                    data.reuse();
                    if (message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty) {
                        continue;
                    }
                    message.id = lastMid;
                    message.dialog_id = cursor.longValue(2);
                    message.date = lastDate;
                    final NativeByteBuffer customParams = cursor.byteBufferValue(4);
                    if (customParams != null) {
                        MessageCustomParamsHelper.readLocalParams(message, customParams);
                        customParams.reuse();
                    }
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    result.add(message);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            final ArrayList<TLRPC.User> users = usersToLoad.isEmpty() ? new ArrayList<>() : storage.getUsers(usersToLoad);
            final ArrayList<TLRPC.Chat> chats = chatsToLoad.isEmpty() ? new ArrayList<>() : storage.getChats(chatsToLoad);
            final boolean end = rowsRead < PAGE_SIZE;
            final int newDate = lastDate;
            final int newMid = lastMid;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != loadGeneration) {
                    return;
                }
                getMessagesController().putUsers(users, true);
                getMessagesController().putChats(chats, true);
                cursorDate = newDate;
                cursorMid = newMid;
                endReached = end;
                loading = false;
                for (int a = 0; a < result.size(); a++) {
                    final TLRPC.Message message = result.get(a);
                    if (messageKeys.add(key(message.dialog_id, message.id))) {
                        messages.add(new MessageObject(currentAccount, message, true, true));
                    }
                }
                rebuildGroups();
                updateRows();
                // a page of service messages only: keep going
                if (!endReached && result.isEmpty()) {
                    loadMore();
                } else {
                    AndroidUtilities.runOnUIThread(this::checkLoadMore);
                }
            });
        });
    }

    private void rebuildGroups() {
        groups.clear();
        for (int a = 0; a < messages.size(); a++) {
            final MessageObject message = messages.get(a);
            if (!message.hasValidGroupId()) {
                continue;
            }
            MessageObject.GroupedMessages group = groups.get(message.getGroupId());
            if (group == null) {
                group = new MessageObject.GroupedMessages();
                group.reversed = true;
                group.groupId = message.getGroupId();
                groups.put(group.groupId, group);
            }
            group.messages.add(message);
        }
        for (int a = 0; a < groups.size(); a++) {
            groups.valueAt(a).calculate();
        }
    }

    private void updateRows() {
        rows.clear();
        long previousGroup = 0;
        for (int a = 0; a < messages.size(); a++) {
            final MessageObject message = messages.get(a);
            final long groupId = getValidGroup(message) != null ? message.getGroupId() : 0;
            if (groupId == 0 || groupId != previousGroup) {
                rows.add(new Row(ROW_HEADER, message));
            }
            previousGroup = groupId;
            rows.add(new Row(ROW_MESSAGE, message));
        }
        if (loading) {
            rows.add(new Row(ROW_LOADING, null));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(messages.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != ROW_LOADING;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == ROW_HEADER) {
                view = new ChannelHeaderCell(context);
            } else if (viewType == ROW_MESSAGE) {
                final ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                    @Override
                    public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) {
                        openPost(cell);
                    }

                    @Override
                    public void didPressOther(ChatMessageCell cell, float otherX, float otherY) {
                        openPost(cell);
                    }

                    @Override
                    public void didPressChannelAvatar(ChatMessageCell cell, TLRPC.Chat chat, int postId, float touchX, float touchY, boolean asForward) {
                        if (chat != null) {
                            openChannel(-chat.id, postId);
                        }
                    }

                    @Override
                    public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
                        if (url instanceof URLSpan) {
                            Browser.openUrl(getParentActivity(), ((URLSpan) url).getURL());
                        }
                    }

                    @Override
                    public boolean canPerformActions() {
                        return true;
                    }
                });
                view = cell;
            } else {
                final FrameLayout frameLayout = new FrameLayout(context);
                final TextView textView = new TextView(context);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                textView.setTextColor(getThemedColor(Theme.key_chat_serviceText));
                textView.setBackground(Theme.createRoundRectDrawable(dp(11), getThemedColor(Theme.key_chat_serviceBackground)));
                textView.setPadding(dp(10), dp(3), dp(10), dp(3));
                textView.setText(getString(R.string.Loading));
                frameLayout.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 8, 0, 8));
                view = frameLayout;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final Row row = rows.get(position);
            if (row.type == ROW_HEADER) {
                ((ChannelHeaderCell) holder.itemView).setChannel(getMessagesController().getChat(-row.message.getDialogId()));
            } else if (row.type == ROW_MESSAGE) {
                final ChatMessageCell cell = (ChatMessageCell) holder.itemView;
                final MessageObject.GroupedMessages group = getValidGroup(row.message);
                final boolean pinnedTop = group != null && getRowMessage(position - 1) != null && getValidGroup(getRowMessage(position - 1)) == group;
                final boolean pinnedBottom = group != null && getRowMessage(position + 1) != null && getValidGroup(getRowMessage(position + 1)) == group;
                cell.isChat = false;
                cell.setMessageObject(row.message, group, pinnedBottom, pinnedTop, false);
                cell.setHighlighted(false);
            }
        }
    }

    private void openPost(ChatMessageCell cell) {
        final MessageObject message = cell.getMessageObject();
        if (message != null) {
            openChannel(message.getDialogId(), message.getId());
        }
    }

    /** Channel avatar and title above each post. */
    private class ChannelHeaderCell extends FrameLayout {

        private final BackupImageView avatarView;
        private final TextView titleView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();

        ChannelHeaderCell(Context context) {
            super(context);
            final LinearLayout pill = new LinearLayout(context);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackground(Theme.createRoundRectDrawable(dp(13), getThemedColor(Theme.key_chat_serviceBackground)));
            pill.setPadding(dp(3), dp(3), dp(10), dp(3));

            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(10));
            pill.addView(avatarView, LayoutHelper.createLinear(20, 20));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(getThemedColor(Theme.key_chat_serviceText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            pill.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));

            addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 10, 10, 2));
        }

        void setChannel(TLRPC.Chat chat) {
            if (chat == null) {
                titleView.setText("");
                avatarView.setImageDrawable(null);
                return;
            }
            titleView.setText(chat.title);
            avatarDrawable.setInfo(currentAccount, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        }
    }
}
