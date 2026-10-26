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

    private final FavoriteChannelsStore store = new PrefsFavoriteChannelsStore();
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
        // adding a channel brings a hidden feed row back
        getSettings().edit().remove("hidden_" + loadedForUserId).apply();
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

    /** Newest top message among the favorite channels, from the loaded chat list. */
    public MessageObject getLastMessage() {
        ensureLoaded();
        MessageObject last = null;
        for (long did : channels.keySet()) {
            final ArrayList<MessageObject> messages = getMessagesController().dialogMessage.get(did);
            if (messages == null || messages.isEmpty()) {
                continue;
            }
            final MessageObject message = messages.get(0);
            if (last == null || message.messageOwner.date > last.messageOwner.date) {
                last = message;
            }
        }
        return last;
    }

    private SharedPreferences getSettings() {
        return ApplicationLoader.applicationContext.getSharedPreferences("favorite_feed_settings", Activity.MODE_PRIVATE);
    }

    /** The feed row is shown in the chat list while there is at least one channel, unless hidden. */
    public boolean isPlaceholderVisible() {
        return getCount() > 0 && !isPlaceholderHidden();
    }

    public boolean isPlaceholderHidden() {
        ensureLoaded();
        return getSettings().getBoolean("hidden_" + loadedForUserId, false);
    }

    /** Posts shown in the feed mark their channel read. On by default. */
    public boolean isMarkReadEnabled() {
        return getSettings().getBoolean("mark_read", true);
    }

    public void setMarkReadEnabled(boolean enabled) {
        getSettings().edit().putBoolean("mark_read", enabled).apply();
    }

    public void setPlaceholderHidden(boolean hidden) {
        ensureLoaded();
        getSettings().edit().putBoolean("hidden_" + loadedForUserId, hidden).apply();
        getNotificationCenter().postNotificationName(NotificationCenter.favoriteChannelsUpdated);
    }

    private void onChanged() {
        store.save(loadedForUserId, channels);
        getNotificationCenter().postNotificationName(NotificationCenter.favoriteChannelsUpdated);
    }
}
