package org.telegram.messenger;

import android.util.SparseArray;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Chat groups kept on this device: each one collapses its chats into a single row of the chat list,
 * the way the archive does. A group is shown as a folder with id FOLDER_BASE + group id,
 * the server never sees these folders. Stored in the "groups" and "members" sections of ForkSettingsStore.
 * One instance per account, must be used from the UI thread.
 */
public class LocalGroupsController extends BaseController {

    public static final int FOLDER_BASE = 1000;

    private static volatile LocalGroupsController[] Instance = new LocalGroupsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static LocalGroupsController getInstance(int num) {
        LocalGroupsController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new LocalGroupsController(num);
                }
            }
        }
        return localInstance;
    }

    public static class Group {
        public int id;
        public String title;
        public int order;
    }

    // the stored records, deletion marks included
    private JSONObject groupsJson = new JSONObject();
    private JSONObject membersJson = new JSONObject();
    // what is in effect: live groups by order, chats of live groups
    private ArrayList<Group> groups = new ArrayList<>();
    private HashMap<Long, Integer> members = new HashMap<>();
    private final SparseArray<TLRPC.TL_dialogFolder> rows = new SparseArray<>();
    private long loadedForUserId;

    private LocalGroupsController(int num) {
        super(num);
    }

    public static boolean isGroupFolder(int folderId) {
        return folderId >= FOLDER_BASE;
    }

    private void ensureLoaded() {
        final long userId = getUserConfig().getClientUserId();
        if (userId == loadedForUserId) {
            return;
        }
        loadedForUserId = userId;
        rows.clear();
        final JSONObject root = ForkSettingsStore.get(userId);
        groupsJson = ForkSettingsStore.section(root, ForkSettingsStore.GROUPS);
        membersJson = ForkSettingsStore.section(root, ForkSettingsStore.MEMBERS);
        if (groupsJson.length() == 0 && userId != 0) {
            putGroup(LocaleController.getString(R.string.LocalGroupFamily));
            putGroup(LocaleController.getString(R.string.LocalGroupFriends));
            putGroup(LocaleController.getString(R.string.LocalGroupWork));
            putGroup(LocaleController.getString(R.string.LocalGroupBots));
            ForkSettingsStore.save(userId);
        }
        rebuild();
    }

    /** Fills groups and members from the stored records. */
    private void rebuild() {
        groups = new ArrayList<>();
        for (Iterator<String> it = groupsJson.keys(); it.hasNext(); ) {
            final String id = it.next();
            final JSONObject o = groupsJson.optJSONObject(id);
            if (o == null || o.optBoolean("deleted")) {
                continue;
            }
            final Group group = new Group();
            group.id = Integer.parseInt(id);
            group.title = o.optString("title");
            group.order = o.optInt("order");
            groups.add(group);
        }
        Collections.sort(groups, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : Integer.compare(a.id, b.id));
        members = new HashMap<>();
        for (Iterator<String> it = membersJson.keys(); it.hasNext(); ) {
            final String did = it.next();
            final JSONObject o = membersJson.optJSONObject(did);
            final int groupId = o != null ? o.optInt("g") : 0;
            if (groupId != 0 && findGroup(groupId) != null) {
                members.put(Long.parseLong(did), groupId);
            }
        }
    }

    private int putGroup(String title) {
        int order = 0;
        for (Iterator<String> it = groupsJson.keys(); it.hasNext(); ) {
            final JSONObject o = groupsJson.optJSONObject(it.next());
            if (o != null) {
                order = Math.max(order, o.optInt("order"));
            }
        }
        final int id = ForkSettingsStore.newGroupId(groupsJson);
        putRecord(groupsJson, String.valueOf(id), ForkSettingsStore.record("title", title, "order", order + 1, "t", ForkSettingsStore.now()));
        return id;
    }

    private void putMember(long did, int groupId, long t) {
        putRecord(membersJson, String.valueOf(did), ForkSettingsStore.record("g", groupId, "t", t));
    }

    private static void putRecord(JSONObject section, String key, JSONObject record) {
        try {
            section.put(key, record);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void onChanged() {
        ForkSettingsStore.save(loadedForUserId);
        rebuild();
        getMessagesController().sortDialogs(null);
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    public ArrayList<Group> getGroups() {
        ensureLoaded();
        return new ArrayList<>(groups);
    }

    private Group findGroup(int groupId) {
        for (Group group : groups) {
            if (group.id == groupId) {
                return group;
            }
        }
        return null;
    }

    public Group getGroup(int groupId) {
        ensureLoaded();
        return findGroup(groupId);
    }

    public String getTitle(int folderId) {
        final Group group = getGroup(folderId - FOLDER_BASE);
        return group != null ? group.title : "";
    }

    /** @return the group the chat is in, 0 if none */
    public int getGroupOf(long did) {
        ensureLoaded();
        final Integer groupId = members.get(did);
        return groupId != null ? groupId : 0;
    }

    /** groupId 0 takes the chats out of their groups, leaving a mark that they were taken out */
    public void setGroup(ArrayList<Long> dids, int groupId) {
        ensureLoaded();
        final long t = ForkSettingsStore.now();
        for (long did : dids) {
            putMember(did, groupId, t);
        }
        onChanged();
    }

    /**
     * One-off: moves into the group every bot of the main list that is in no group yet.
     * @return how many bots were moved
     */
    public int addAllBots(int groupId) {
        ensureLoaded();
        final long t = ForkSettingsStore.now();
        int count = 0;
        for (TLRPC.Dialog d : getMessagesController().getAllDialogs()) {
            if (!(d instanceof TLRPC.TL_dialog) || d.id <= 0 || d.folder_id != 0 || members.containsKey(d.id)) {
                continue;
            }
            final TLRPC.User user = getMessagesController().getUser(d.id);
            if (user != null && user.bot) {
                putMember(d.id, groupId, t);
                count++;
            }
        }
        if (count > 0) {
            onChanged();
        }
        return count;
    }

    public int createGroup(String title) {
        ensureLoaded();
        final int id = putGroup(title);
        onChanged();
        return id;
    }

    public void renameGroup(int groupId, String title) {
        ensureLoaded();
        final JSONObject o = groupsJson.optJSONObject(String.valueOf(groupId));
        if (o == null) {
            return;
        }
        try {
            o.put("title", title);
            o.put("t", ForkSettingsStore.now());
        } catch (Exception e) {
            FileLog.e(e);
        }
        onChanged();
    }

    /** The chats of a deleted group go back to the chat list; the group stays as a deletion mark. */
    public void deleteGroup(int groupId) {
        ensureLoaded();
        final JSONObject o = groupsJson.optJSONObject(String.valueOf(groupId));
        if (o == null) {
            return;
        }
        final long t = ForkSettingsStore.now();
        try {
            o.put("deleted", true);
            o.put("t", t);
        } catch (Exception e) {
            FileLog.e(e);
        }
        for (HashMap.Entry<Long, Integer> e : members.entrySet()) {
            if (e.getValue() == groupId) {
                putMember(e.getKey(), 0, t);
            }
        }
        rows.remove(groupId);
        onChanged();
    }

    /**
     * Called by sortDialogs once the main list is built: moves grouped chats out of it into the lists
     * of their folders and puts a folder row per non-empty group where its newest chat would be.
     */
    public void collapseGroups(SparseArray<ArrayList<TLRPC.Dialog>> dialogsByFolder) {
        ensureLoaded();
        final ArrayList<TLRPC.Dialog> main = dialogsByFolder.get(0);
        if (main == null || groups.isEmpty()) {
            return;
        }
        for (int a = 0; a < main.size(); a++) {
            final TLRPC.Dialog d = main.get(a);
            if (!(d instanceof TLRPC.TL_dialog)) {
                continue;
            }
            final int groupId = getGroupOf(d.id);
            if (groupId == 0) {
                continue;
            }
            main.remove(a--);
            ArrayList<TLRPC.Dialog> list = dialogsByFolder.get(FOLDER_BASE + groupId);
            if (list == null) {
                list = new ArrayList<>();
                dialogsByFolder.put(FOLDER_BASE + groupId, list);
            }
            list.add(d);
        }
        for (Group group : groups) {
            final ArrayList<TLRPC.Dialog> list = dialogsByFolder.get(FOLDER_BASE + group.id);
            if (list == null || list.isEmpty()) {
                continue;
            }
            final TLRPC.TL_dialogFolder row = getRow(group);
            row.last_message_date = 0;
            row.unread_count = 0;
            row.unread_mark = false;
            for (TLRPC.Dialog d : list) {
                row.last_message_date = Math.max(row.last_message_date, d.last_message_date);
                if (!getMessagesController().isDialogMuted(d.id, 0)) {
                    row.unread_count += getMessagesController().getDialogUnreadCount(d);
                }
                row.unread_mark |= d.unread_mark;
            }
            int index = 0;
            while (index < main.size()) {
                final TLRPC.Dialog d = main.get(index);
                // the archive row keeps its place on top, a hidden archive is pulled from there
                final boolean archive = d instanceof TLRPC.TL_dialogFolder && !isGroupFolder(((TLRPC.TL_dialogFolder) d).folder.id);
                if (!d.pinned && !archive && d.last_message_date <= row.last_message_date) {
                    break;
                }
                index++;
            }
            main.add(index, row);
        }
    }

    /** The chat list row of a group by its dialog id, null for any other id. */
    public TLRPC.TL_dialogFolder getRow(long dialogId) {
        if (!DialogObject.isFolderDialogId(dialogId)) {
            return null;
        }
        final int folderId = (int) (dialogId & ~0x2000000000000000L);
        return isGroupFolder(folderId) ? rows.get(folderId - FOLDER_BASE) : null;
    }

    private TLRPC.TL_dialogFolder getRow(Group group) {
        TLRPC.TL_dialogFolder row = rows.get(group.id);
        if (row == null) {
            row = new TLRPC.TL_dialogFolder();
            row.peer = new TLRPC.TL_peerUser();
            row.folder = new TLRPC.TL_folder();
            row.folder.id = FOLDER_BASE + group.id;
            row.notify_settings = new TLRPC.TL_peerNotifySettings();
            DialogObject.initDialog(row);
            rows.put(group.id, row);
        }
        row.folder.title = group.title;
        return row;
    }
}
