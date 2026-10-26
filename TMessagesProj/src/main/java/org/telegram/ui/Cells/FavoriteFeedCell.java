package org.telegram.ui.Cells;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.FavoriteChannelsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.FavoriteFeedUi;

/**
 * "Favorite feed" row in the chat list. Not backed by a real dialog: drawn as a custom dialog
 * with the newest post of the favorite channels as preview and their unread counters summed up.
 */
public class FavoriteFeedCell extends DialogCell {

    private final int account;
    private final CustomDialog feedDialog = new CustomDialog();
    private final AvatarDrawable feedAvatar;

    public FavoriteFeedCell(DialogsActivity fragment, Context context, int account) {
        super(fragment, context, false, false, account, null);
        this.account = account;
        feedAvatar = FavoriteFeedUi.createFeedAvatar(context);
        fillDialog();
        setDialog(feedDialog);
    }

    private void fillDialog() {
        final FavoriteChannelsController controller = FavoriteChannelsController.getInstance(account);
        final MessageObject last = controller.getLastMessage();
        feedDialog.name = getString(R.string.FavoriteFeed);
        feedDialog.pinned = true;
        feedDialog.unread_count = controller.getUnreadCount();
        if (last != null) {
            final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-last.getDialogId());
            // media posts: the caption, not the media type label
            final CharSequence source = !TextUtils.isEmpty(last.caption) ? last.caption : last.messageText;
            final String text = source != null ? source.toString().replace('\n', ' ') : "";
            feedDialog.message = (chat != null ? chat.title + ": " : "") + text;
            feedDialog.date = last.messageOwner.date;
        } else {
            feedDialog.message = getString(R.string.FavoriteFeedNoPosts);
            feedDialog.date = 0;
        }
    }

    @Override
    public boolean update(int mask, boolean animated) {
        if (feedAvatar == null) {
            // called from the super constructor
            return super.update(mask, animated);
        }
        fillDialog();
        final boolean result = super.update(mask, animated);
        avatarImage.setImage(null, "50_50", feedAvatar, null, 0);
        return result;
    }
}
