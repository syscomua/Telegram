package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

/**
 * Settings of the fork features, one JSON document per Telegram user id: local chat groups and
 * the favorite feed channels. Every record keeps the time of its last change and a deletion stays
 * as a mark, so two copies can later be merged record by record. Format: docs/FORK_SETTINGS.md.
 * Must be used from the UI thread.
 */
public class ForkSettingsStore {

    public static final int VERSION = 1;

    public static final String GROUPS = "groups";
    public static final String MEMBERS = "members";
    public static final String FEED = "feed";

    private static final String PREFS_NAME = "fork_settings";
    private static final HashMap<Long, JSONObject> cache = new HashMap<>();
    private static final Random random = new Random();

    private static SharedPreferences getPrefs(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Activity.MODE_PRIVATE);
    }

    private static String key(long userId) {
        return "account_" + userId;
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    /** The document of the account, loaded once; changes to it are written by save(). */
    public static JSONObject get(long userId) {
        JSONObject root = cache.get(userId);
        if (root != null) {
            return root;
        }
        final String json = getPrefs(PREFS_NAME).getString(key(userId), null);
        if (json != null) {
            try {
                root = new JSONObject(json);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (root == null) {
            root = migrate(userId);
            cache.put(userId, root);
            save(userId);
        } else {
            cache.put(userId, root);
        }
        return root;
    }

    public static void save(long userId) {
        final JSONObject root = cache.get(userId);
        if (root == null || userId == 0) {
            return;
        }
        try {
            root.put("v", VERSION);
            root.put("t", now());
        } catch (Exception e) {
            FileLog.e(e);
        }
        getPrefs(PREFS_NAME).edit().putString(key(userId), root.toString()).apply();
    }

    /** A section of the document, created when missing. */
    public static JSONObject section(JSONObject root, String name) {
        JSONObject section = root.optJSONObject(name);
        if (section == null) {
            section = new JSONObject();
            try {
                root.put(name, section);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return section;
    }

    /** A random group id: groups made on two devices must not clash once merged. */
    public static int newGroupId(JSONObject groups) {
        int id;
        do {
            id = 1 + random.nextInt(Integer.MAX_VALUE - LocalGroupsController.FOLDER_BASE - 1);
        } while (groups.has(String.valueOf(id)));
        return id;
    }

    public static JSONObject record(Object... pairs) {
        final JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put((String) pairs[i], pairs[i + 1]);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return o;
    }

    /** Builds the first document from the files of the earlier builds, which are left as they are. */
    private static JSONObject migrate(long userId) {
        final JSONObject root = new JSONObject();
        final JSONObject groups = section(root, GROUPS);
        final JSONObject members = section(root, MEMBERS);
        final JSONObject feed = section(root, FEED);
        final long t = now();
        try {
            final String oldGroups = getPrefs("local_groups").getString("groups_" + userId, null);
            if (oldGroups != null) {
                final JSONObject old = new JSONObject(oldGroups);
                final HashMap<Integer, Integer> ids = new HashMap<>();
                final JSONArray list = old.getJSONArray("groups");
                for (int i = 0; i < list.length(); i++) {
                    final JSONObject g = list.getJSONObject(i);
                    final int id = newGroupId(groups);
                    ids.put(g.getInt("id"), id);
                    groups.put(String.valueOf(id), record("title", g.getString("title"), "order", i + 1, "t", t));
                }
                final JSONObject map = old.getJSONObject("members");
                for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                    final String did = it.next();
                    final Integer id = ids.get(map.getInt(did));
                    members.put(did, record("g", id != null ? id : 0, "t", t));
                }
            }
            final String oldFeed = getPrefs("favorite_feed").getString("channels_" + userId, null);
            if (!TextUtils.isEmpty(oldFeed)) {
                for (String item : oldFeed.split(",")) {
                    final int colon = item.indexOf(':');
                    final String did = colon < 0 ? item : item.substring(0, colon);
                    final int added = colon < 0 ? 0 : Integer.parseInt(item.substring(colon + 1));
                    feed.put(String.valueOf(Long.parseLong(did)), record("added", added, "t", t));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return root;
    }
}
