package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
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
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Posts of all favorite channels in one list, newest first.
 * Reads the regular local message table; nothing is copied anywhere. History missing
 * locally is requested through MessagesController.loadMessages, which stores it the usual
 * way (with holes), so the channels themselves are not affected.
 * Its own screen instead of ChatActivity: ChatActivity is built around a single dialog.
 */
public class FavoriteFeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_SIZE = 50;
    private static final int FETCH_SIZE = 30;
    private static final int MAX_PARALLEL_FETCHES = 3;

    private static final int MENU_SETTINGS = 1;

    private static final int ROW_HEADER = 0;
    private static final int ROW_MESSAGE = 1;
    private static final int ROW_LOADING = 2;

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private GridLayoutManagerFixed layoutManager;
    private ListAdapter adapter;
    private TextView emptyView;
    private TextView newPostsView;

    /** Sorted newest first: date DESC, mid DESC, as stored rows are read. */
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final HashSet<String> messageKeys = new HashSet<>();
    private final LongSparseArray<MessageObject.GroupedMessages> groups = new LongSparseArray<>();
    private final ArrayList<Row> rows = new ArrayList<>();

    private ArrayList<Long> channels = new ArrayList<>();
    private boolean loading;
    private boolean dbEndReached;
    private int cursorDate;
    private int cursorMid;
    private int loadGeneration;

    /** Oldest locally known post per channel, to see which channel runs out first. */
    private final HashMap<Long, int[]> oldestLocal = new HashMap<>();
    private boolean oldestLocalLoaded;
    private final HashSet<Long> exhausted = new HashSet<>();
    private final HashSet<Long> fetching = new HashSet<>();
    private final ArrayDeque<long[]> fetchQueue = new ArrayDeque<>();
    private final HashMap<Integer, long[]> fetchesByLoadIndex = new HashMap<>();
    private int activeFetches;
    private int lastLoadIndex = 1;
    private boolean floodPaused;

    private int newPostsCount;
    private final HashMap<Long, Integer> readUpTo = new HashMap<>();
    private final Runnable markReadRunnable = this::markVisibleAsRead;

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

    private boolean isFavorite(long dialogId) {
        return channels.contains(dialogId);
    }

    /** Left or inaccessible channels stay in the list but are not part of the feed. */
    private ArrayList<Long> getAvailableChannels() {
        final ArrayList<Long> all = FavoriteChannelsController.getInstance(currentAccount).getAll();
        final ArrayList<Long> result = new ArrayList<>();
        for (int a = 0; a < all.size(); a++) {
            if (FavoriteFeedSettingsActivity.isAvailable(getMessagesController().getChat(-all.get(a)))) {
                result.add(all.get(a));
            }
        }
        return result;
    }

    @Override
    public boolean onFragmentCreate() {
        channels = getAvailableChannels();
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.loadingMessagesFailed);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().addObserver(this, NotificationCenter.favoriteChannelsUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.loadingMessagesFailed);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().removeObserver(this, NotificationCenter.favoriteChannelsUpdated);
        AndroidUtilities.cancelRunOnUIThread(markReadRunnable);
    }

    @SuppressLint("ClickableViewAccessibility")
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
                } else if (id == MENU_SETTINGS) {
                    presentFragment(new FavoriteFeedSettingsActivity());
                }
            }
        });
        actionBar.createMenu().addItem(MENU_SETTINGS, R.drawable.msg_settings_old).setContentDescription(getString(R.string.FavoriteFeedSettings));
        updateSubtitle();

        contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                // the content starts below the action bar, unlike in ChatActivity
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
        };
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context) {
            private float pullStartY = -1;

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                // pull down at the top of the list to refresh
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    pullStartY = canScrollVertically(-1) ? -1 : e.getY();
                } else if (e.getAction() == MotionEvent.ACTION_MOVE && pullStartY >= 0 && !canScrollVertically(-1)) {
                    if (e.getY() - pullStartY > dp(110)) {
                        pullStartY = -1;
                        refreshFromServer();
                    }
                } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    pullStartY = -1;
                }
                return super.onTouchEvent(e);
            }
        };
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
                if (layoutManager.findFirstVisibleItemPosition() == 0) {
                    setNewPostsCount(0);
                }
                scheduleMarkRead();
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = createPill(context, 14);
        emptyView.setPadding(dp(16), dp(10), dp(16), dp(10));
        emptyView.setText(getString(channels.isEmpty() ? R.string.FavoriteFeedEmptyHint : R.string.FavoriteFeedNoPosts));
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        newPostsView = createPill(context, 14);
        newPostsView.setPadding(dp(14), dp(7), dp(14), dp(7));
        newPostsView.setTypeface(AndroidUtilities.bold());
        newPostsView.setVisibility(View.GONE);
        newPostsView.setOnClickListener(v -> {
            listView.scrollToPosition(0);
            setNewPostsCount(0);
        });
        contentView.addView(newPostsView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

        fragmentView = contentView;
        loadMore();
        refreshFromServer();
        return fragmentView;
    }

    private TextView createPill(Context context, int textSize) {
        final TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize);
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(getThemedColor(Theme.key_chat_serviceText));
        textView.setBackground(Theme.createRoundRectDrawable(dp(14), getThemedColor(Theme.key_chat_serviceBackground)));
        return textView;
    }

    private void updateSubtitle() {
        if (actionBar != null) {
            actionBar.setSubtitle(formatPluralString("FavoriteFeedChannels", channels.size()));
        }
    }

    private void setNewPostsCount(int count) {
        newPostsCount = count;
        if (newPostsView == null) {
            return;
        }
        if (count > 0) {
            newPostsView.setText(formatPluralString("FavoriteFeedNewPosts", count));
            newPostsView.setVisibility(View.VISIBLE);
        } else {
            newPostsView.setVisibility(View.GONE);
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

    // region local database

    private void checkLoadMore() {
        if (loading || dbEndReached || layoutManager == null) {
            return;
        }
        final int last = layoutManager.findLastVisibleItemPosition();
        if (last >= rows.size() - 10) {
            loadMore();
        }
    }

    private void loadMore() {
        if (loading || dbEndReached) {
            return;
        }
        if (channels.isEmpty()) {
            dbEndReached = true;
            updateRows();
            return;
        }
        loading = true;
        updateRows();
        final int generation = loadGeneration;
        final String ids = TextUtils.join(",", channels);
        final int fromDate = cursorDate;
        final int fromMid = cursorMid;
        final boolean needOldest = !oldestLocalLoaded;
        final long clientUserId = getUserConfig().getClientUserId();
        final MessagesStorage storage = getMessagesStorage();
        storage.getStorageQueue().postRunnable(() -> {
            final ArrayList<TLRPC.Message> result = new ArrayList<>();
            final ArrayList<Long> usersToLoad = new ArrayList<>();
            final ArrayList<Long> chatsToLoad = new ArrayList<>();
            final HashMap<Long, int[]> oldest = new HashMap<>();
            int rowsRead = 0;
            int lastDate = fromDate;
            int lastMid = fromMid;
            SQLiteCursor cursor = null;
            try {
                if (needOldest) {
                    cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT uid, MIN(date), MIN(mid) FROM messages_v2 WHERE uid IN (%s) GROUP BY uid", ids));
                    while (cursor.next()) {
                        oldest.put(cursor.longValue(0), new int[]{cursor.intValue(1), cursor.intValue(2)});
                    }
                    cursor.dispose();
                    cursor = null;
                }
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
                if (needOldest) {
                    oldestLocalLoaded = true;
                    for (HashMap.Entry<Long, int[]> entry : oldest.entrySet()) {
                        updateOldestLocal(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
                    }
                }
                cursorDate = newDate;
                cursorMid = newMid;
                dbEndReached = end;
                loading = false;
                final ArrayList<MessageObject> objects = new ArrayList<>();
                for (int a = 0; a < result.size(); a++) {
                    objects.add(new MessageObject(currentAccount, result.get(a), true, true));
                }
                addMessages(objects);
                fetchChannelsRunningOut();
                // a page of service messages only: keep going
                if (!dbEndReached && result.isEmpty()) {
                    loadMore();
                } else {
                    AndroidUtilities.runOnUIThread(this::checkLoadMore);
                }
            });
        });
    }

    private void updateOldestLocal(long dialogId, int date, int mid) {
        final int[] current = oldestLocal.get(dialogId);
        if (current == null || date < current[0] || date == current[0] && mid < current[1]) {
            oldestLocal.put(dialogId, new int[]{date, mid});
        }
    }

    /** True if (date, mid) is above the database cursor, i.e. inside the part already read. */
    private boolean isAboveCursor(int date, int mid) {
        return cursorDate == 0 || date > cursorDate || date == cursorDate && mid >= cursorMid;
    }

    // endregion

    // region server

    /** Fresh history of every favorite channel. */
    private void refreshFromServer() {
        for (int a = 0; a < channels.size(); a++) {
            enqueueFetch(channels.get(a), 0);
        }
        updateRows();
    }

    /**
     * Channels whose local history ends above the current database cursor may have posts there
     * that were never downloaded, so their older history is requested.
     */
    private void fetchChannelsRunningOut() {
        if (!oldestLocalLoaded) {
            return;
        }
        for (int a = 0; a < channels.size(); a++) {
            final long dialogId = channels.get(a);
            if (exhausted.contains(dialogId) || fetching.contains(dialogId)) {
                continue;
            }
            final int[] oldest = oldestLocal.get(dialogId);
            if (oldest == null) {
                enqueueFetch(dialogId, 0);
            } else if (dbEndReached || isAboveCursor(oldest[0], oldest[1])) {
                enqueueFetch(dialogId, oldest[1]);
            }
        }
    }

    private void enqueueFetch(long dialogId, int maxId) {
        if (fetching.contains(dialogId)) {
            return;
        }
        fetching.add(dialogId);
        fetchQueue.add(new long[]{dialogId, maxId});
        startFetches();
    }

    private void startFetches() {
        while (!floodPaused && activeFetches < MAX_PARALLEL_FETCHES && !fetchQueue.isEmpty()) {
            final long[] fetch = fetchQueue.poll();
            activeFetches++;
            final int loadIndex = lastLoadIndex++;
            fetchesByLoadIndex.put(loadIndex, fetch);
            getMessagesController().loadMessages(fetch[0], 0, false, FETCH_SIZE, (int) fetch[1], 0, false, 0, classGuid, 0, 0, 0, 0, 0, loadIndex, false);
        }
    }

    private void onFetchFinished(long[] fetch) {
        activeFetches--;
        fetching.remove(fetch[0]);
        startFetches();
        updateRows();
    }

    private void onFetchLoaded(int loadIndex, ArrayList<MessageObject> objects) {
        final long[] fetch = fetchesByLoadIndex.remove(loadIndex);
        if (fetch == null) {
            return;
        }
        final long dialogId = fetch[0];
        if (fetch[1] != 0 && objects.size() < FETCH_SIZE) {
            exhausted.add(dialogId);
        }
        final ArrayList<MessageObject> fresh = new ArrayList<>();
        int added = 0;
        for (int a = 0; a < objects.size(); a++) {
            final MessageObject message = objects.get(a);
            updateOldestLocal(dialogId, message.messageOwner.date, message.getId());
            // rows below the cursor are now in the database and will come with the next pages
            if (isAboveCursor(message.messageOwner.date, message.getId()) || dbEndReached) {
                fresh.add(message);
            }
        }
        final boolean scrolledDown = layoutManager != null && layoutManager.findFirstVisibleItemPosition() > 0;
        final int topBefore = messages.isEmpty() ? 0 : messages.get(0).messageOwner.date;
        added = addMessages(fresh);
        if (scrolledDown && added > 0) {
            int above = 0;
            for (int a = 0; a < fresh.size(); a++) {
                if (fresh.get(a).messageOwner.date > topBefore) {
                    above++;
                }
            }
            if (above > 0) {
                setNewPostsCount(newPostsCount + above);
            }
        }
        if (dbEndReached && !objects.isEmpty()) {
            // more rows were saved below the cursor
            dbEndReached = false;
        }
        onFetchFinished(fetch);
        checkLoadMore();
    }

    private void onFetchFailed(TLObject request, TLRPC.TL_error error) {
        if (!(request instanceof TLRPC.TL_messages_getHistory)) {
            return;
        }
        final TLRPC.InputPeer peer = ((TLRPC.TL_messages_getHistory) request).peer;
        if (peer == null || peer.channel_id == 0) {
            return;
        }
        final long dialogId = -peer.channel_id;
        Integer loadIndex = null;
        for (HashMap.Entry<Integer, long[]> entry : fetchesByLoadIndex.entrySet()) {
            if (entry.getValue()[0] == dialogId) {
                loadIndex = entry.getKey();
                break;
            }
        }
        if (loadIndex == null) {
            return;
        }
        final long[] fetch = fetchesByLoadIndex.remove(loadIndex);
        if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            final int seconds = Utilities.parseInt(error.text);
            activeFetches--;
            fetchQueue.addFirst(fetch);
            if (!floodPaused) {
                floodPaused = true;
                AndroidUtilities.runOnUIThread(() -> {
                    floodPaused = false;
                    startFetches();
                }, Math.max(1, seconds) * 1000L);
            }
            return;
        }
        // channel left, banned or private now: skip it in this session
        exhausted.add(dialogId);
        onFetchFinished(fetch);
    }

    // endregion

    // region list

    /** Merges messages into the sorted list. @return how many were new */
    private int addMessages(ArrayList<MessageObject> objects) {
        int added = 0;
        for (int a = 0; a < objects.size(); a++) {
            final MessageObject message = objects.get(a);
            if (message.messageOwner instanceof TLRPC.TL_messageService || message.messageOwner instanceof TLRPC.TL_messageEmpty || message.scheduled) {
                continue;
            }
            if (messageKeys.add(key(message.getDialogId(), message.getId()))) {
                messages.add(message);
                added++;
            }
        }
        if (added > 0) {
            Collections.sort(messages, (m1, m2) -> {
                if (m1.messageOwner.date != m2.messageOwner.date) {
                    return m2.messageOwner.date - m1.messageOwner.date;
                }
                if (m1.getId() != m2.getId()) {
                    return m2.getId() - m1.getId();
                }
                return Long.compare(m2.getDialogId(), m1.getDialogId());
            });
            rebuildGroups();
        }
        updateRows();
        return added;
    }

    private void removeMessages(long dialogId, ArrayList<Integer> ids) {
        boolean changed = false;
        for (int a = messages.size() - 1; a >= 0; a--) {
            final MessageObject message = messages.get(a);
            if (message.getDialogId() == dialogId && ids.contains(message.getId())) {
                messages.remove(a);
                messageKeys.remove(key(dialogId, message.getId()));
                changed = true;
            }
        }
        if (changed) {
            rebuildGroups();
            updateRows();
        }
    }

    private void replaceMessages(long dialogId, ArrayList<MessageObject> objects) {
        boolean changed = false;
        for (int b = 0; b < objects.size(); b++) {
            final MessageObject object = objects.get(b);
            if (object == null) {
                continue;
            }
            for (int a = 0; a < messages.size(); a++) {
                final MessageObject message = messages.get(a);
                if (message.getDialogId() == dialogId && message.getId() == object.getId()) {
                    messages.set(a, object);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            rebuildGroups();
            updateRows();
        }
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
        final boolean busy = loading || activeFetches > 0 || !fetchQueue.isEmpty();
        if (busy) {
            rows.add(new Row(ROW_LOADING, null));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(messages.isEmpty() && !busy ? View.VISIBLE : View.GONE);
        }
    }

    // endregion

    // region read state

    private void scheduleMarkRead() {
        AndroidUtilities.cancelRunOnUIThread(markReadRunnable);
        AndroidUtilities.runOnUIThread(markReadRunnable, 500);
    }

    /** Shown posts mark their channel read up to them, as scrolling a channel does. */
    private void markVisibleAsRead() {
        if (listView == null || isPaused || !FavoriteChannelsController.getInstance(currentAccount).isMarkReadEnabled()) {
            return;
        }
        final HashMap<Long, MessageObject> maxVisible = new HashMap<>();
        for (int a = 0; a < listView.getChildCount(); a++) {
            final View child = listView.getChildAt(a);
            if (!(child instanceof ChatMessageCell) || child.getBottom() > listView.getHeight() + dp(4)) {
                continue;
            }
            final MessageObject message = ((ChatMessageCell) child).getMessageObject();
            if (message == null || message.getId() <= 0) {
                continue;
            }
            final MessageObject current = maxVisible.get(message.getDialogId());
            if (current == null || message.getId() > current.getId()) {
                maxVisible.put(message.getDialogId(), message);
            }
        }
        for (HashMap.Entry<Long, MessageObject> entry : maxVisible.entrySet()) {
            final long dialogId = entry.getKey();
            final MessageObject message = entry.getValue();
            final TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(dialogId);
            if (dialog == null || dialog.unread_count == 0 || message.getId() <= dialog.read_inbox_max_id) {
                continue;
            }
            final Integer done = readUpTo.get(dialogId);
            if (done != null && done >= message.getId()) {
                continue;
            }
            readUpTo.put(dialogId, message.getId());
            int countDiff = 0;
            for (int a = 0; a < messages.size(); a++) {
                final MessageObject m = messages.get(a);
                if (m.getDialogId() == dialogId && m.getId() > dialog.read_inbox_max_id && m.getId() <= message.getId()) {
                    countDiff++;
                }
            }
            getMessagesController().markDialogAsRead(dialogId, message.getId(), message.getId(), message.messageOwner.date, false, 0, Math.max(1, countDiff), true, 0);
        }
    }

    // endregion

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        scheduleMarkRead();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            if ((Integer) args[10] != classGuid) {
                return;
            }
            onFetchLoaded((Integer) args[11], (ArrayList<MessageObject>) args[2]);
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            if ((Integer) args[0] != classGuid) {
                return;
            }
            onFetchFailed((TLObject) args[1], args.length > 2 ? (TLRPC.TL_error) args[2] : null);
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            final long dialogId = (Long) args[0];
            final boolean scheduled = (Boolean) args[2];
            if (scheduled || !isFavorite(dialogId)) {
                return;
            }
            final ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[1];
            final boolean scrolledDown = layoutManager != null && layoutManager.findFirstVisibleItemPosition() > 0;
            final int added = addMessages(objects);
            if (scrolledDown && added > 0) {
                setNewPostsCount(newPostsCount + added);
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            final boolean scheduled = (Boolean) args[2];
            final long channelId = (Long) args[1];
            if (scheduled || channelId == 0 || !isFavorite(-channelId)) {
                return;
            }
            removeMessages(-channelId, (ArrayList<Integer>) args[0]);
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            final long dialogId = (Long) args[0];
            if (isFavorite(dialogId)) {
                replaceMessages(dialogId, (ArrayList<MessageObject>) args[1]);
            }
        } else if (id == NotificationCenter.favoriteChannelsUpdated) {
            final ArrayList<Long> newChannels = getAvailableChannels();
            if (!newChannels.equals(channels)) {
                reload(newChannels);
            }
        }
    }

    private void reload(ArrayList<Long> newChannels) {
        channels = newChannels;
        loadGeneration++;
        messages.clear();
        messageKeys.clear();
        groups.clear();
        oldestLocal.clear();
        oldestLocalLoaded = false;
        exhausted.clear();
        fetchQueue.clear();
        fetching.clear();
        fetchesByLoadIndex.clear();
        activeFetches = 0;
        loading = false;
        dbEndReached = false;
        cursorDate = 0;
        cursorMid = 0;
        updateSubtitle();
        if (emptyView != null) {
            emptyView.setText(getString(channels.isEmpty() ? R.string.FavoriteFeedEmptyHint : R.string.FavoriteFeedNoPosts));
        }
        loadMore();
        refreshFromServer();
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
                final TextView textView = createPill(context, 13);
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
