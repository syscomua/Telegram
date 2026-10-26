package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the list in its own preferences file, keyed by user id, not by account slot:
 * it survives "clear cache", database resets and logout/login into the same account.
 * Format: "did:date,did:date".
 */
public class PrefsFavoriteChannelsStore implements FavoriteChannelsStore {

    private static final String PREFS_NAME = "favorite_feed";

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
    }

    private static String key(long userId) {
        return "channels_" + userId;
    }

    @Override
    public LinkedHashMap<Long, Integer> load(long userId) {
        final LinkedHashMap<Long, Integer> result = new LinkedHashMap<>();
        if (userId == 0) {
            return result;
        }
        final String value = getPrefs().getString(key(userId), null);
        if (TextUtils.isEmpty(value)) {
            return result;
        }
        for (String item : value.split(",")) {
            final int colon = item.indexOf(':');
            try {
                if (colon < 0) {
                    result.put(Long.parseLong(item), 0);
                } else {
                    result.put(Long.parseLong(item.substring(0, colon)), Integer.parseInt(item.substring(colon + 1)));
                }
            } catch (NumberFormatException e) {
                FileLog.e(e);
            }
        }
        return result;
    }

    @Override
    public void save(long userId, LinkedHashMap<Long, Integer> channels) {
        if (userId == 0) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : channels.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        final SharedPreferences.Editor editor = getPrefs().edit();
        if (sb.length() == 0) {
            editor.remove(key(userId));
        } else {
            editor.putString(key(userId), sb.toString());
        }
        editor.apply();
    }
}
