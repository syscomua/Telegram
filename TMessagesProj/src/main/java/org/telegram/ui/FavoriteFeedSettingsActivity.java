package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FavoriteChannelsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Favorite channels list, feed options, export and import of the list. */
public class FavoriteFeedSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int REQUEST_IMPORT = 41;
    private static final String EXPORT_HEADER = "# Telegram favorite feed v1";

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_CHANNEL = 2;
    private static final int TYPE_BUTTON = 3;
    private static final int TYPE_INFO = 4;
    private static final int TYPE_SHADOW = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<Long> channels = new ArrayList<>();

    private int rowCount;
    private int optionsHeaderRow;
    private int autoScrollRow;
    private int optionsShadowRow;
    private int channelsHeaderRow;
    private int channelsStartRow;
    private int channelsEndRow;
    private int channelsInfoRow;
    private int exportRow;
    private int importRow;
    private int backupInfoRow;

    private FavoriteChannelsController getController() {
        return FavoriteChannelsController.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.favoriteChannelsUpdated);
        updateRows();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.favoriteChannelsUpdated);
    }

    private void updateRows() {
        channels = getController().getAll();
        rowCount = 0;
        optionsHeaderRow = rowCount++;
        autoScrollRow = rowCount++;
        optionsShadowRow = rowCount++;
        channelsHeaderRow = rowCount++;
        channelsStartRow = rowCount;
        rowCount += channels.size();
        channelsEndRow = rowCount;
        channelsInfoRow = rowCount++;
        exportRow = rowCount++;
        importRow = rowCount++;
        backupInfoRow = rowCount++;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.FavoriteFeedSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == autoScrollRow) {
                final boolean enabled = !getController().isAutoScrollEnabled();
                getController().setAutoScrollEnabled(enabled);
                ((TextCheckCell) view).setChecked(enabled);
            } else if (position >= channelsStartRow && position < channelsEndRow) {
                final Bundle args = new Bundle();
                args.putLong("chat_id", -channels.get(position - channelsStartRow));
                presentFragment(new ChatActivity(args));
            } else if (position == exportRow) {
                exportList();
            } else if (position == importRow) {
                importList();
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;
        return fragmentView;
    }

    /** Left, banned, deleted or never loaded: stays in the list but is not shown in the feed. */
    public static boolean isAvailable(TLRPC.Chat chat) {
        return chat != null && !(chat instanceof TLRPC.TL_channelForbidden) && !ChatObject.isNotInChat(chat);
    }

    private void exportList() {
        try {
            final StringBuilder sb = new StringBuilder(EXPORT_HEADER).append('\n');
            for (int a = 0; a < channels.size(); a++) {
                final long dialogId = channels.get(a);
                final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                final String username = chat != null ? ChatObject.getPublicUsername(chat) : null;
                final String title = chat != null && chat.title != null ? chat.title.replace('\n', ' ') : "";
                sb.append(dialogId).append(' ').append(TextUtils.isEmpty(username) ? "-" : username).append(' ').append(title).append('\n');
            }
            final File file = new File(AndroidUtilities.getCacheDir(), "favorite_feed.txt");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            final Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, getString(R.string.FavoriteFeedExport)));
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.UnknownError)).show();
        }
    }

    private void importList() {
        try {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/plain");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        int added = 0;
        boolean valid = false;
        try (InputStream stream = getParentActivity().getContentResolver().openInputStream(data.getData());
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equals(EXPORT_HEADER)) {
                    valid = true;
                    continue;
                }
                if (!valid || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final int space = line.indexOf(' ');
                try {
                    final long dialogId = Long.parseLong(space < 0 ? line : line.substring(0, space));
                    if (dialogId < 0 && !getController().isFavorite(dialogId)) {
                        getController().add(dialogId);
                        added++;
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!valid) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.FavoriteFeedImportInvalid)).show();
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, AndroidUtilities.replaceTags(formatPluralString("FavoriteFeedImported", added))).show();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.favoriteChannelsUpdated) {
            updateRows();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_CHANNEL || type == TYPE_BUTTON;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == optionsHeaderRow || position == channelsHeaderRow) {
                return TYPE_HEADER;
            } else if (position == autoScrollRow) {
                return TYPE_CHECK;
            } else if (position >= channelsStartRow && position < channelsEndRow) {
                return TYPE_CHANNEL;
            } else if (position == exportRow || position == importRow) {
                return TYPE_BUTTON;
            } else if (position == optionsShadowRow) {
                return TYPE_SHADOW;
            }
            return TYPE_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHANNEL: {
                    final ManageChatUserCell cell = new ManageChatUserCell(context, 7, 6, true);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    cell.setDelegate((userCell, click) -> {
                        if (click) {
                            final Object tag = userCell.getTag();
                            if (tag instanceof Long) {
                                getController().remove((Long) tag);
                            }
                        }
                        return true;
                    });
                    view = cell;
                    break;
                }
                case TYPE_BUTTON:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                default:
                    view = new TextInfoPrivacyCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(getString(position == optionsHeaderRow ? R.string.FavoriteFeedOptions : R.string.FavoriteFeedChannelsHeader));
                    break;
                case TYPE_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.FavoriteFeedAutoScroll), getController().isAutoScrollEnabled(), false);
                    break;
                case TYPE_CHANNEL: {
                    final ManageChatUserCell cell = (ManageChatUserCell) holder.itemView;
                    final long dialogId = channels.get(position - channelsStartRow);
                    final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                    cell.setTag(dialogId);
                    final CharSequence status;
                    if (!isAvailable(chat)) {
                        status = AndroidUtilities.replaceTags("**" + getString(R.string.FavoriteFeedUnavailable) + "**");
                    } else if (chat.participants_count > 0) {
                        status = formatPluralString("Subscribers", chat.participants_count);
                    } else {
                        status = getString(R.string.ChannelPublic);
                    }
                    if (chat != null) {
                        cell.setData(chat, null, status, position != channelsEndRow - 1);
                    } else {
                        final TLRPC.Chat stub = new TLRPC.TL_channelForbidden();
                        stub.id = -dialogId;
                        stub.title = String.valueOf(-dialogId);
                        cell.setData(stub, null, status, position != channelsEndRow - 1);
                    }
                    break;
                }
                case TYPE_BUTTON: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4));
                    cell.setText(getString(position == exportRow ? R.string.FavoriteFeedExport : R.string.FavoriteFeedImport), position == exportRow);
                    break;
                }
                case TYPE_INFO: {
                    final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == channelsInfoRow) {
                        cell.setText(getString(channels.isEmpty() ? R.string.FavoriteFeedEmptyHint : R.string.FavoriteFeedChannelsInfo));
                        cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else {
                        cell.setText(getString(R.string.FavoriteFeedBackupInfo));
                        cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
            }
        }
    }
}
