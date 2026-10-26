package org.telegram.messenger;

import java.util.LinkedHashMap;

/**
 * Storage for the favorite feed channel list of one account.
 * Kept behind an interface so a synced implementation can replace the local one later.
 */
public interface FavoriteChannelsStore {

    /** @return dialog id -> date added (unix seconds), in the order they were added */
    LinkedHashMap<Long, Integer> load(long userId);

    void save(long userId, LinkedHashMap<Long, Integer> channels);
}
