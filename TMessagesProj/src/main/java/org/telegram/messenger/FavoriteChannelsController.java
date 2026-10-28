package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Channels the user marked for the "Favorite feed" virtual chat. One instance per account.
 * Only dialog ids are stored here, messages stay in the regular local database.
 * Must be used from the UI thread.
 */
public class FavoriteChannelsController extends BaseController {

    private static volatile FavoriteChannelsController[] Instance = new FavoriteChannelsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static FavoriteChannelsController getInstance(int num) {
        FavoriteChannelsController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new FavoriteChannelsController(num);
                }
            }
        }
        return localInstance;
    }

    private final FavoriteChannelsStore store = new ForkFavoriteChannelsStore();
    private LinkedHashMap<Long, Integer> channels = new LinkedHashMap<>();
    private long loadedForUserId;

    private FavoriteChannelsController(int num) {
        super(num);
    }

    public static boolean canBeFavorite(TLRPC.Chat chat) {
        return ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isMonoForum(chat);
    }

    private void ensureLoaded() {
        final long userId = getUserConfig().getClientUserId();
        if (userId != loadedForUserId) {
            loadedForUserId = userId;
            channels = store.load(userId);
        }
    }

    public boolean isFavorite(long did) {
        ensureLoaded();
        return channels.containsKey(did);
    }

    public void add(long did) {
        ensureLoaded();
        if (did >= 0 || channels.containsKey(did)) {
            return;
        }
        channels.put(did, ConnectionsManager.getInstance(currentAccount).getCurrentTime());
        onChanged();
    }

    public void remove(long did) {
        ensureLoaded();
        if (channels.remove(did) != null) {
            onChanged();
        }
    }

    /** @return true if the channel is favorite after the call */
    public boolean toggle(long did) {
        if (isFavorite(did)) {
            remove(did);
            return false;
        }
        add(did);
        return true;
    }

    /** Dialog ids (-channelId) in the order they were added. */
    public ArrayList<Long> getAll() {
        ensureLoaded();
        return new ArrayList<>(channels.keySet());
    }

    public int getCount() {
        ensureLoaded();
        return channels.size();
    }

    /** Sum of unread counters of the favorite channels, from the loaded chat list. */
    public int getUnreadCount() {
        ensureLoaded();
        int count = 0;
        for (long did : channels.keySet()) {
            final TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(did);
            if (dialog != null) {
                count += dialog.unread_count;
            }
        }
        return count;
    }

    private SharedPreferences getSettings() {
        return ApplicationLoader.applicationContext.getSharedPreferences("favorite_feed_settings", Activity.MODE_PRIVATE);
    }

    /** Scroll an open feed to a new post when its bottom is on screen, as chats do. Off by default. */
    public boolean isAutoScrollEnabled() {
        return getSettings().getBoolean("auto_scroll", false);
    }

    public void setAutoScrollEnabled(boolean enabled) {
        getSettings().edit().putBoolean("auto_scroll", enabled).apply();
    }

    private void onChanged() {
        store.save(loadedForUserId, channels);
        getNotificationCenter().postNotificationName(NotificationCenter.favoriteChannelsUpdated);
    }
}
