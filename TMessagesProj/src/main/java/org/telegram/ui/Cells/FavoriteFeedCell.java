package org.telegram.ui.Cells;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.FavoriteChannelsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.DialogsActivity;

/**
 * "Favorite feed" row in the chat list. Not backed by a real dialog: drawn as a custom dialog
 * with the newest post of the favorite channels as preview and their unread counters summed up.
 */
public class FavoriteFeedCell extends DialogCell {

    private final int account;
    private final CustomDialog feedDialog = new CustomDialog();
    private final AvatarDrawable feedAvatar = new AvatarDrawable();

    public FavoriteFeedCell(DialogsActivity fragment, Context context, int account) {
        super(fragment, context, false, false, account, null);
        this.account = account;
        feedAvatar.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
        final Drawable icon = context.getResources().getDrawable(R.drawable.msg_fave).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        feedAvatar.setCustomIcon(icon);
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
            final String text = last.messageText != null ? last.messageText.toString() : "";
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
