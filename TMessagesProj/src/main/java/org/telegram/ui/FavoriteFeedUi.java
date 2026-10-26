package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FavoriteChannelsController;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;

/**
 * Glue between the favorite feed and upstream screens, so upstream files only get one-line hooks.
 */
public class FavoriteFeedUi {

    public static CharSequence getToggleText(boolean favorite) {
        return getString(favorite ? R.string.FavoriteFeedRemove : R.string.FavoriteFeedAdd);
    }

    public static int getToggleIcon(boolean favorite) {
        return favorite ? R.drawable.msg_unfave : R.drawable.msg_fave;
    }

    public static boolean canBeFavorite(int account, long did) {
        if (!DialogObject.isChatDialog(did)) {
            return false;
        }
        return FavoriteChannelsController.canBeFavorite(MessagesController.getInstance(account).getChat(-did));
    }

    /** Header menu item of a chat screen. */
    public static void updateItem(ActionBarMenuItem.Item item, int account, long did) {
        if (item == null) {
            return;
        }
        final boolean favorite = FavoriteChannelsController.getInstance(account).isFavorite(did);
        item.setText(getToggleText(favorite));
        item.setIcon(getToggleIcon(favorite));
    }

    /** Menu item of a profile screen. */
    public static void updateItem(ActionBarMenuSubItem item, int account, long did) {
        if (item == null) {
            return;
        }
        final boolean favorite = FavoriteChannelsController.getInstance(account).isFavorite(did);
        item.setTextAndIcon(getToggleText(favorite), getToggleIcon(favorite));
    }

    public static void toggle(BaseFragment fragment, long did) {
        final int account = fragment.getCurrentAccount();
        final boolean favorite = FavoriteChannelsController.getInstance(account).toggle(did);
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-did);
        final String title = chat != null ? chat.title : "";
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.star_fill,
            AndroidUtilities.replaceTags(formatString(favorite ? R.string.FavoriteFeedAdded : R.string.FavoriteFeedRemoved, title))).show();
    }

    private static boolean allFavorite(int account, ArrayList<Long> dialogs) {
        final FavoriteChannelsController controller = FavoriteChannelsController.getInstance(account);
        for (int i = 0; i < dialogs.size(); i++) {
            if (!controller.isFavorite(dialogs.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Item in the selection menu of the chat list: shown only when every selected chat is a channel. */
    public static void updateSelectionItem(ActionBarMenuSubItem item, int account, ArrayList<Long> selected) {
        if (item == null) {
            return;
        }
        boolean allChannels = !selected.isEmpty();
        for (int i = 0; i < selected.size(); i++) {
            if (!canBeFavorite(account, selected.get(i))) {
                allChannels = false;
                break;
            }
        }
        item.setVisibility(allChannels ? View.VISIBLE : View.GONE);
        if (allChannels) {
            final boolean favorite = allFavorite(account, selected);
            item.setTextAndIcon(getToggleText(favorite), getToggleIcon(favorite));
        }
    }

    /** If every selected channel is favorite, removes them all, otherwise adds them all. */
    public static void toggleSelected(BaseFragment fragment, ArrayList<Long> selected) {
        if (selected.isEmpty()) {
            return;
        }
        if (selected.size() == 1) {
            toggle(fragment, selected.get(0));
            return;
        }
        final int account = fragment.getCurrentAccount();
        final FavoriteChannelsController controller = FavoriteChannelsController.getInstance(account);
        final boolean remove = allFavorite(account, selected);
        for (int i = 0; i < selected.size(); i++) {
            if (remove) {
                controller.remove(selected.get(i));
            } else {
                controller.add(selected.get(i));
            }
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.star_fill,
            AndroidUtilities.replaceTags(formatPluralString(remove ? "FavoriteFeedRemovedChannels" : "FavoriteFeedAddedChannels", selected.size()))).show();
    }

    /** Long press on the feed row in the chat list. */
    public static boolean showPlaceholderMenu(BaseFragment fragment, View view) {
        final int account = fragment.getCurrentAccount();
        ItemOptions.makeOptions(fragment, view)
            .add(R.drawable.msg_cancel, getString(R.string.FavoriteFeedHide), () -> {
                FavoriteChannelsController.getInstance(account).setPlaceholderHidden(true);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_hide, getString(R.string.FavoriteFeedHidden), getString(R.string.Undo),
                    () -> FavoriteChannelsController.getInstance(account).setPlaceholderHidden(false)).show();
            })
            .show();
        return true;
    }

    private static Drawable badgeBg, badgeFg;
    private static int badgeBgColor, badgeFgColor;
    private static final Rect badgeRect = new Rect();

    /** Star in the top right corner of a favorite channel avatar in the chat list. */
    public static void drawAvatarBadge(Canvas canvas, ImageReceiver avatar, float alpha, Theme.ResourcesProvider resourcesProvider) {
        if (alpha <= 0) {
            return;
        }
        if (badgeBg == null) {
            badgeBg = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.star_small_outline).mutate();
            badgeFg = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.star_small_inner).mutate();
        }
        final int bg = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        if (badgeBgColor != bg) {
            badgeBg.setColorFilter(new PorterDuffColorFilter(badgeBgColor = bg, PorterDuff.Mode.SRC_IN));
        }
        final int fg = Theme.getColor(Theme.key_chats_unreadCounter, resourcesProvider);
        if (badgeFgColor != fg) {
            badgeFg.setColorFilter(new PorterDuffColorFilter(badgeFgColor = fg, PorterDuff.Mode.SRC_IN));
        }
        final int sz = dp(17);
        final int right = (int) avatar.getImageX2() + dp(1.66f);
        final int top = (int) avatar.getImageY() - dp(1);
        badgeRect.set(right - sz, top, right, top + sz);
        badgeRect.inset(-dp(1), -dp(1));
        badgeBg.setBounds(badgeRect);
        badgeBg.setAlpha((int) (0xFF * alpha));
        badgeBg.draw(canvas);
        badgeRect.set(right - sz, top, right, top + sz);
        badgeFg.setBounds(badgeRect);
        badgeFg.setAlpha((int) (0xFF * alpha));
        badgeFg.draw(canvas);
    }
}
