package org.telegram.messenger;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The favorite feed list in the "feed" section of ForkSettingsStore, keyed by user id, not by account slot:
 * it survives "clear cache", database resets and logout/login into the same account.
 * A removed channel stays as a deletion mark.
 */
public class ForkFavoriteChannelsStore implements FavoriteChannelsStore {

    @Override
    public LinkedHashMap<Long, Integer> load(long userId) {
        final LinkedHashMap<Long, Integer> result = new LinkedHashMap<>();
        if (userId == 0) {
            return result;
        }
        final JSONObject feed = ForkSettingsStore.section(ForkSettingsStore.get(userId), ForkSettingsStore.FEED);
        final ArrayList<long[]> list = new ArrayList<>();
        for (Iterator<String> it = feed.keys(); it.hasNext(); ) {
            final String did = it.next();
            final JSONObject o = feed.optJSONObject(did);
            if (o == null || o.optBoolean("deleted")) {
                continue;
            }
            try {
                list.add(new long[]{Long.parseLong(did), o.optInt("added")});
            } catch (NumberFormatException e) {
                FileLog.e(e);
            }
        }
        // in the order they were added
        Collections.sort(list, (a, b) -> Long.compare(a[1], b[1]));
        for (long[] item : list) {
            result.put(item[0], (int) item[1]);
        }
        return result;
    }

    @Override
    public void save(long userId, LinkedHashMap<Long, Integer> channels) {
        if (userId == 0) {
            return;
        }
        final JSONObject feed = ForkSettingsStore.section(ForkSettingsStore.get(userId), ForkSettingsStore.FEED);
        final long t = ForkSettingsStore.now();
        try {
            for (Iterator<String> it = feed.keys(); it.hasNext(); ) {
                final String did = it.next();
                final JSONObject o = feed.optJSONObject(did);
                if (o != null && !o.optBoolean("deleted") && !channels.containsKey(Long.parseLong(did))) {
                    o.put("deleted", true);
                    o.put("t", t);
                }
            }
            for (Map.Entry<Long, Integer> entry : channels.entrySet()) {
                final String did = String.valueOf(entry.getKey());
                final JSONObject o = feed.optJSONObject(did);
                if (o == null || o.optBoolean("deleted") || o.optInt("added") != entry.getValue()) {
                    feed.put(did, ForkSettingsStore.record("added", entry.getValue(), "t", t));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        ForkSettingsStore.save(userId);
    }
}
