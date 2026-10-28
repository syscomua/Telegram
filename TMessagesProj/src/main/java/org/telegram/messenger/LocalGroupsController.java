package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Chat groups kept on this device: each one collapses its chats into a single row of the chat list,
 * the way the archive does. A group is shown as a folder with id FOLDER_BASE + group id,
 * the server never sees these folders. One instance per account, must be used from the UI thread.
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
        /** every bot without a group of its own lands here */
        public boolean bots;
    }

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
        groups = new ArrayList<>();
        members = new HashMap<>();
        rows.clear();
        final String json = getPrefs().getString("groups_" + userId, null);
        if (json == null) {
            addDefault(LocaleController.getString(R.string.LocalGroupFamily), false);
            addDefault(LocaleController.getString(R.string.LocalGroupFriends), false);
            addDefault(LocaleController.getString(R.string.LocalGroupWork), false);
            addDefault(LocaleController.getString(R.string.LocalGroupBots), true);
            return;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final JSONArray list = root.getJSONArray("groups");
            for (int i = 0; i < list.length(); i++) {
                final JSONObject o = list.getJSONObject(i);
                final Group group = new Group();
                group.id = o.getInt("id");
                group.title = o.getString("title");
                group.bots = o.optBoolean("bots");
                groups.add(group);
            }
            final JSONObject map = root.getJSONObject("members");
            for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                final String did = it.next();
                members.put(Long.parseLong(did), map.getInt(did));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void addDefault(String title, boolean bots) {
        final Group group = new Group();
        group.id = groups.size() + 1;
        group.title = title;
        group.bots = bots;
        groups.add(group);
    }

    private void save() {
        try {
            final JSONArray list = new JSONArray();
            for (Group group : groups) {
                final JSONObject o = new JSONObject();
                o.put("id", group.id);
                o.put("title", group.title);
                if (group.bots) {
                    o.put("bots", true);
                }
                list.put(o);
            }
            final JSONObject map = new JSONObject();
            for (HashMap.Entry<Long, Integer> e : members.entrySet()) {
                map.put(String.valueOf(e.getKey()), e.getValue());
            }
            final JSONObject root = new JSONObject();
            root.put("groups", list);
            root.put("members", map);
            getPrefs().edit().putString("groups_" + loadedForUserId, root.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void onChanged() {
        save();
        getMessagesController().sortDialogs(null);
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("local_groups", Activity.MODE_PRIVATE);
    }

    public ArrayList<Group> getGroups() {
        ensureLoaded();
        return new ArrayList<>(groups);
    }

    public Group getGroup(int groupId) {
        ensureLoaded();
        for (Group group : groups) {
            if (group.id == groupId) {
                return group;
            }
        }
        return null;
    }

    public String getTitle(int folderId) {
        final Group group = getGroup(folderId - FOLDER_BASE);
        return group != null ? group.title : "";
    }

    /** @return the group the chat is in, 0 if none */
    public int getGroupOf(long did) {
        ensureLoaded();
        final Integer groupId = members.get(did);
        if (groupId != null) {
            return groupId;
        }
        if (did > 0) {
            final TLRPC.User user = getMessagesController().getUser(did);
            if (user != null && user.bot) {
                for (Group group : groups) {
                    if (group.bots) {
                        return group.id;
                    }
                }
            }
        }
        return 0;
    }

    /** groupId 0 takes the chats out of their groups, a bot then stays out of the bots group too */
    public void setGroup(ArrayList<Long> dids, int groupId) {
        ensureLoaded();
        for (long did : dids) {
            members.put(did, groupId);
        }
        onChanged();
    }

    public int createGroup(String title) {
        ensureLoaded();
        int maxId = 0;
        for (Group group : groups) {
            maxId = Math.max(maxId, group.id);
        }
        final Group group = new Group();
        group.id = maxId + 1;
        group.title = title;
        groups.add(group);
        onChanged();
        return group.id;
    }

    public void renameGroup(int groupId, String title) {
        final Group group = getGroup(groupId);
        if (group != null) {
            group.title = title;
            onChanged();
        }
    }

    /** The chats of a deleted group go back to the chat list. */
    public void deleteGroup(int groupId) {
        final Group group = getGroup(groupId);
        if (group == null) {
            return;
        }
        groups.remove(group);
        for (Iterator<Integer> it = members.values().iterator(); it.hasNext(); ) {
            if (it.next() == groupId) {
                it.remove();
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
            if (groupId == 0 || getGroup(groupId) == null) {
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
