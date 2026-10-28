package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocalGroupsController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/**
 * Menus of the local chat groups, so upstream screens only get one-line hooks.
 */
public class LocalGroupsUi {

    /** "To group" for the chats selected in the chat list: a group, a new one, or out of any group. */
    public static void showMoveMenu(BaseFragment fragment, ArrayList<Long> dialogs) {
        if (dialogs.isEmpty() || fragment.getParentActivity() == null) {
            return;
        }
        final LocalGroupsController controller = LocalGroupsController.getInstance(fragment.getCurrentAccount());
        final ArrayList<LocalGroupsController.Group> groups = controller.getGroups();
        boolean inGroup = false;
        for (long did : dialogs) {
            inGroup |= controller.getGroupOf(did) != 0;
        }
        final int n = groups.size();
        final CharSequence[] items = new CharSequence[n + 2];
        final int[] icons = new int[n + 2];
        for (int i = 0; i < n; i++) {
            items[i] = groups.get(i).title;
            icons[i] = R.drawable.msg_folders;
        }
        items[n] = getString(R.string.LocalGroupNew);
        icons[n] = R.drawable.msg_addfolder;
        items[n + 1] = inGroup ? getString(R.string.LocalGroupRemoveFrom) : null;
        icons[n + 1] = inGroup ? R.drawable.msg_removefolder : 0;

        final BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity());
        builder.setTitle(getString(R.string.LocalGroupMoveTo));
        builder.setItems(items, icons, (d, which) -> {
            if (which < n) {
                moveTo(fragment, dialogs, groups.get(which).id, groups.get(which).title);
            } else if (which == n) {
                askTitle(fragment, getString(R.string.LocalGroupNew), null, title -> {
                    moveTo(fragment, dialogs, controller.createGroup(title), title);
                });
            } else {
                controller.setGroup(dialogs, 0);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, getString(R.string.LocalGroupRemoved)).show();
            }
        });
        fragment.showDialog(builder.create());
    }

    private static void moveTo(BaseFragment fragment, ArrayList<Long> dialogs, int groupId, String title) {
        LocalGroupsController.getInstance(fragment.getCurrentAccount()).setGroup(dialogs, groupId);
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
            AndroidUtilities.replaceTags(formatString(R.string.LocalGroupMoved, title))).show();
    }

    /** Long press on a group row in the chat list. */
    public static void showGroupMenu(BaseFragment fragment, View view, int folderId) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        try {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignored) {}
        final LocalGroupsController controller = LocalGroupsController.getInstance(fragment.getCurrentAccount());
        final int groupId = folderId - LocalGroupsController.FOLDER_BASE;
        final String title = controller.getTitle(folderId);
        final BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity());
        builder.setTitle(title);
        builder.setItems(new CharSequence[]{
            getString(R.string.LocalGroupRename),
            getString(R.string.LocalGroupDelete)
        }, new int[]{
            R.drawable.msg_edit,
            R.drawable.msg_delete
        }, (d, which) -> {
            if (which == 0) {
                askTitle(fragment, getString(R.string.LocalGroupRename), title, newTitle -> controller.renameGroup(groupId, newTitle));
            } else {
                controller.deleteGroup(groupId);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete,
                    AndroidUtilities.replaceTags(formatString(R.string.LocalGroupDeleted, title))).show();
            }
        });
        fragment.showDialog(builder.create());
    }

    private interface TitleCallback {
        void run(String title);
    }

    private static void askTitle(BaseFragment fragment, String header, String current, TitleCallback callback) {
        AlertsCreator.createSimpleTextInputAlert(fragment.getParentActivity(), fragment, header, null,
            getString(R.string.LocalGroupTitleHint), current, 32, getString(R.string.OK), fragment.getResourceProvider(), text -> {
                final String title = text == null ? "" : text.trim();
                if (!title.isEmpty()) {
                    callback.run(title);
                }
            });
    }
}
