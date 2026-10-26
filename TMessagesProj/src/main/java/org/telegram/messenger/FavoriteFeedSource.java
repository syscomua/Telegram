package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Source of the favorite feed for ChatActivity in MODE_SEARCH, the same way
 * HashtagSearchController feeds public posts search: pages of messages go out as
 * messagesDidLoad + hashtagSearchUpdated, ids are replaced with generated ones because
 * message ids of different channels collide (the original one stays in realId).
 *
 * Posts are read from the regular local table, newest first, with a (date, mid) cursor.
 * Missing history is requested through MessagesController.loadMessages, which stores it
 * the usual way, holes included, so the channels themselves are not affected.
 */
public class FavoriteFeedSource implements NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_SIZE = 50;
    private static final int FETCH_SIZE = 30;
    private static final int MAX_PARALLEL_FETCHES = 3;

    private static volatile FavoriteFeedSource[] Instance = new FavoriteFeedSource[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static FavoriteFeedSource getInstance(int num) {
        FavoriteFeedSource localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new FavoriteFeedSource(num);
                }
            }
        }
        return localInstance;
    }

    private final int currentAccount;
    private final HashtagSearchController.SearchResult result;
    private final int fetchGuid = ConnectionsManager.generateClassGuid();

    /** guid of the ChatActivity being fed */
    private int guid;
    private int generation;
    private ArrayList<Long> channels = new ArrayList<>();
    private boolean loading;
    private int loadIndex;
    private boolean refreshed;
    private int cursorDate;
    private int cursorMid;
    /** Next id for older pages (goes down) and for posts arriving live (goes up). */
    private int nextId;
    private int nextNewId;
    /** "dialogId_mid" of every post given to ChatActivity -> its generated id there */
    private final HashMap<String, Integer> idsByKey = new HashMap<>();

    /** Oldest locally known post per channel: {date, mid}. */
    private final HashMap<Long, int[]> oldestLocal = new HashMap<>();
    private boolean oldestLocalLoaded;
    private final HashSet<Long> exhausted = new HashSet<>();

    private final ArrayDeque<long[]> fetchQueue = new ArrayDeque<>();
    private final HashMap<Integer, long[]> fetchesByLoadIndex = new HashMap<>();
    private int activeFetches;
    private int lastFetchIndex = 1;
    private boolean floodPaused;
    private Runnable afterFetches;

    private FavoriteFeedSource(int account) {
        currentAccount = account;
        result = new HashtagSearchController.SearchResult(account);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDidLoad);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.loadingMessagesFailed);
    }

    public HashtagSearchController.SearchResult getSearchResult() {
        return result;
    }

    private static String key(long dialogId, int mid) {
        return dialogId + "_" + mid;
    }

    /** Left or inaccessible channels stay in the list but are not part of the feed. */
    private ArrayList<Long> getAvailableChannels() {
        final ArrayList<Long> all = FavoriteChannelsController.getInstance(currentAccount).getAll();
        final ArrayList<Long> available = new ArrayList<>();
        for (int a = 0; a < all.size(); a++) {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-all.get(a));
            if (chat != null && !(chat instanceof TLRPC.TL_channelForbidden) && !ChatObject.isNotInChat(chat)) {
                available.add(all.get(a));
            }
        }
        return available;
    }

    public void clear() {
        generation++;
        result.clear();
        result.lastGeneratedId = 0;
        channels = getAvailableChannels();
        loading = false;
        refreshed = false;
        cursorDate = 0;
        cursorMid = 0;
        // newest first, so ids go down; room above is left for posts arriving later
        nextId = Integer.MAX_VALUE / 2;
        nextNewId = nextId + 1;
        idsByKey.clear();
        oldestLocal.clear();
        oldestLocalLoaded = false;
        exhausted.clear();
        fetchQueue.clear();
        fetchesByLoadIndex.clear();
        activeFetches = 0;
        afterFetches = null;
    }

    /** Next page for ChatActivity, called from HashtagSearchController.searchHashtag. */
    public void load(int guid, int loadIndex) {
        if (guid != this.guid) {
            this.guid = guid;
            clear();
        }
        if (loading || result.endReached) {
            return;
        }
        loading = true;
        result.loading = true;
        this.loadIndex = loadIndex;
        if (channels.isEmpty()) {
            deliver(new ArrayList<>(), true, 0, 0);
            return;
        }
        if (!refreshed) {
            refreshed = true;
            for (int a = 0; a < channels.size(); a++) {
                enqueueFetch(channels.get(a), 0);
            }
            runAfterFetches(this::readPage);
        } else {
            readPage();
        }
    }

    private void runAfterFetches(Runnable runnable) {
        if (activeFetches == 0 && fetchQueue.isEmpty()) {
            runnable.run();
        } else {
            afterFetches = runnable;
        }
    }

    private void readPage() {
        final int gen = generation;
        final String ids = TextUtils.join(",", channels);
        final int fromDate = cursorDate;
        final int fromMid = cursorMid;
        final boolean needOldest = !oldestLocalLoaded;
        final long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            final ArrayList<TLRPC.Message> page = new ArrayList<>();
            final ArrayList<Long> usersToLoad = new ArrayList<>();
            final ArrayList<Long> chatsToLoad = new ArrayList<>();
            final HashMap<Long, int[]> oldest = new HashMap<>();
            int rowsRead = 0;
            int lastDate = fromDate;
            int lastMid = fromMid;
            SQLiteCursor cursor = null;
            try {
                if (needOldest) {
                    cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT uid, MIN(date), MIN(mid) FROM messages_v2 WHERE uid IN (%s) GROUP BY uid", ids));
                    while (cursor.next()) {
                        oldest.put(cursor.longValue(0), new int[]{cursor.intValue(1), cursor.intValue(2)});
                    }
                    cursor.dispose();
                    cursor = null;
                }
                final String after = fromDate == 0 ? "" : String.format(Locale.US, " AND (date < %d OR (date = %d AND mid < %d))", fromDate, fromDate, fromMid);
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT data, mid, uid, date, custom_params FROM messages_v2 WHERE uid IN (%s)%s ORDER BY date DESC, mid DESC LIMIT %d", ids, after, PAGE_SIZE));
                while (cursor.next()) {
                    rowsRead++;
                    lastMid = cursor.intValue(1);
                    lastDate = cursor.intValue(3);
                    final NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    message.readAttachPath(data, clientUserId);
                    data.reuse();
                    if (message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty) {
                        continue;
                    }
                    message.id = lastMid;
                    message.dialog_id = cursor.longValue(2);
                    message.date = lastDate;
                    final NativeByteBuffer customParams = cursor.byteBufferValue(4);
                    if (customParams != null) {
                        MessageCustomParamsHelper.readLocalParams(message, customParams);
                        customParams.reuse();
                    }
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    page.add(message);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            final ArrayList<TLRPC.User> users = usersToLoad.isEmpty() ? new ArrayList<>() : storage.getUsers(usersToLoad);
            final ArrayList<TLRPC.Chat> chats = chatsToLoad.isEmpty() ? new ArrayList<>() : storage.getChats(chatsToLoad);
            final boolean dbEnd = rowsRead < PAGE_SIZE;
            final int pageDate = lastDate;
            final int pageMid = lastMid;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                MessagesController.getInstance(currentAccount).putUsers(users, true);
                MessagesController.getInstance(currentAccount).putChats(chats, true);
                if (needOldest) {
                    oldestLocalLoaded = true;
                    for (HashMap.Entry<Long, int[]> entry : oldest.entrySet()) {
                        updateOldestLocal(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
                    }
                }
                // a channel whose local history ends inside this page may have posts here
                // that were never downloaded: get them first and read the page again
                boolean fetchingOlder = false;
                for (int a = 0; a < channels.size(); a++) {
                    final long dialogId = channels.get(a);
                    if (exhausted.contains(dialogId)) {
                        continue;
                    }
                    final int[] old = oldestLocal.get(dialogId);
                    if (old == null || dbEnd || old[0] > pageDate || old[0] == pageDate && old[1] >= pageMid) {
                        enqueueFetch(dialogId, old != null ? old[1] : 0);
                        fetchingOlder = true;
                    }
                }
                if (fetchingOlder) {
                    runAfterFetches(this::readPage);
                    return;
                }
                deliver(page, dbEnd, pageDate, pageMid);
            });
        });
    }

    private void deliver(ArrayList<TLRPC.Message> page, boolean end, int pageDate, int pageMid) {
        cursorDate = pageDate;
        cursorMid = pageMid;
        final ArrayList<MessageObject> objects = new ArrayList<>();
        for (int a = 0; a < page.size(); a++) {
            final TLRPC.Message message = page.get(a);
            if (idsByKey.containsKey(key(message.dialog_id, message.id))) {
                continue;
            }
            objects.add(toFeedObject(message, nextId--));
        }
        result.messages.addAll(objects);
        result.loading = false;
        result.endReached = end;
        result.count = result.messages.size();
        loading = false;
        final NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.postNotificationName(NotificationCenter.messagesDidLoad, 0L, objects.size(), objects, false, 0, 0, 0, 0, 2, true, guid, loadIndex, 0, 0, ChatActivity.MODE_SEARCH);
        center.postNotificationName(NotificationCenter.hashtagSearchUpdated, guid, result.count, result.endReached, 0, 0, 0);
    }

    /** Message of the feed: generated id, the original one in realId, channel avatar shown. */
    private MessageObject toFeedObject(TLRPC.Message message, int feedId) {
        idsByKey.put(key(message.dialog_id, message.id), feedId);
        message.realId = message.id;
        message.id = feedId;
        final MessageObject object = new MessageObject(currentAccount, message, true, true);
        // channel avatar next to each post, as Saved Messages shows the source
        object.forceAvatar = true;
        return object;
    }

    /** The same message may be open in its channel at the same time: never change its id there. */
    private static TLRPC.Message copyMessage(MessageObject object) {
        final TLRPC.Message message = object.messageOwner;
        final SerializedData out = new SerializedData(message.getObjectSize());
        message.serializeToStream(out);
        final SerializedData in = new SerializedData(out.toByteArray());
        final TLRPC.Message copy = TLRPC.Message.TLdeserialize(in, in.readInt32(false), false);
        out.cleanup();
        in.cleanup();
        if (copy != null) {
            copy.dialog_id = object.getDialogId();
            copy.attachPath = message.attachPath;
        }
        return copy;
    }

    private boolean isFeedChannel(long dialogId) {
        return guid != 0 && channels.contains(dialogId);
    }

    /** New posts of favorite channels for an open feed (didReceiveNewMessages). */
    public ArrayList<MessageObject> wrapNewMessages(long dialogId, ArrayList<MessageObject> objects) {
        final ArrayList<MessageObject> result = new ArrayList<>();
        if (!isFeedChannel(dialogId)) {
            return result;
        }
        for (int a = 0; a < objects.size(); a++) {
            final MessageObject object = objects.get(a);
            if (object.getId() <= 0 || object.messageOwner instanceof TLRPC.TL_messageService || object.scheduled
                || idsByKey.containsKey(key(dialogId, object.getId()))) {
                continue;
            }
            final TLRPC.Message copy = copyMessage(object);
            if (copy != null) {
                result.add(toFeedObject(copy, nextNewId++));
            }
        }
        this.result.messages.addAll(result);
        this.result.count = this.result.messages.size();
        return result;
    }

    /** Edited posts already in the feed (replaceMessagesObjects). */
    public ArrayList<MessageObject> wrapEditedMessages(long dialogId, ArrayList<MessageObject> objects) {
        final ArrayList<MessageObject> result = new ArrayList<>();
        if (!isFeedChannel(dialogId)) {
            return result;
        }
        for (int a = 0; a < objects.size(); a++) {
            final MessageObject object = objects.get(a);
            if (object == null) {
                continue;
            }
            final Integer feedId = idsByKey.get(key(dialogId, object.getId()));
            if (feedId == null) {
                continue;
            }
            final TLRPC.Message copy = copyMessage(object);
            if (copy != null) {
                result.add(toFeedObject(copy, feedId));
            }
        }
        return result;
    }

    /** Feed ids of deleted posts (messagesDeleted, channelId as posted). */
    public ArrayList<Integer> mapDeletedIds(long channelId, ArrayList<Integer> ids) {
        final ArrayList<Integer> result = new ArrayList<>();
        if (channelId == 0 || !isFeedChannel(-channelId)) {
            return result;
        }
        for (int a = 0; a < ids.size(); a++) {
            final Integer feedId = idsByKey.remove(key(-channelId, ids.get(a)));
            if (feedId != null) {
                result.add(feedId);
            }
        }
        return result;
    }

    private void updateOldestLocal(long dialogId, int date, int mid) {
        final int[] current = oldestLocal.get(dialogId);
        if (current == null || date < current[0] || date == current[0] && mid < current[1]) {
            oldestLocal.put(dialogId, new int[]{date, mid});
        }
    }

    // region server

    /** fetch = {dialogId, maxId}: history older than maxId, or the newest when 0. */
    private void enqueueFetch(long dialogId, int maxId) {
        for (long[] fetch : fetchQueue) {
            if (fetch[0] == dialogId) {
                return;
            }
        }
        for (long[] fetch : fetchesByLoadIndex.values()) {
            if (fetch[0] == dialogId) {
                return;
            }
        }
        fetchQueue.add(new long[]{dialogId, maxId});
        startFetches();
    }

    private void startFetches() {
        while (!floodPaused && activeFetches < MAX_PARALLEL_FETCHES && !fetchQueue.isEmpty()) {
            final long[] fetch = fetchQueue.poll();
            activeFetches++;
            final int index = lastFetchIndex++;
            fetchesByLoadIndex.put(index, fetch);
            MessagesController.getInstance(currentAccount).loadMessages(fetch[0], 0, false, FETCH_SIZE, (int) fetch[1], 0, false, 0, fetchGuid, 0, 0, 0, 0, 0, index, false);
        }
    }

    private void onFetchFinished() {
        activeFetches--;
        startFetches();
        if (activeFetches == 0 && fetchQueue.isEmpty() && afterFetches != null) {
            final Runnable runnable = afterFetches;
            afterFetches = null;
            runnable.run();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            if ((Integer) args[10] != fetchGuid) {
                return;
            }
            final long[] fetch = fetchesByLoadIndex.remove((Integer) args[11]);
            if (fetch == null) {
                return;
            }
            final ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[2];
            final int[] before = oldestLocal.get(fetch[0]);
            for (int a = 0; a < objects.size(); a++) {
                updateOldestLocal(fetch[0], objects.get(a).messageOwner.date, objects.get(a).getId());
            }
            final int[] after = oldestLocal.get(fetch[0]);
            if (objects.isEmpty() || fetch[1] != 0 && objects.size() < FETCH_SIZE || before != null && after == before) {
                // empty channel, start of history, or nothing older came back
                exhausted.add(fetch[0]);
            }
            onFetchFinished();
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            if ((Integer) args[0] != fetchGuid || !(args[1] instanceof TLRPC.TL_messages_getHistory)) {
                return;
            }
            final TLRPC.InputPeer peer = ((TLRPC.TL_messages_getHistory) args[1]).peer;
            if (peer == null) {
                return;
            }
            Integer index = null;
            for (HashMap.Entry<Integer, long[]> entry : fetchesByLoadIndex.entrySet()) {
                if (entry.getValue()[0] == -peer.channel_id) {
                    index = entry.getKey();
                    break;
                }
            }
            if (index == null) {
                return;
            }
            final long[] fetch = fetchesByLoadIndex.remove(index);
            final TLRPC.TL_error error = args.length > 2 ? (TLRPC.TL_error) args[2] : null;
            if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                activeFetches--;
                fetchQueue.addFirst(fetch);
                if (!floodPaused) {
                    floodPaused = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        floodPaused = false;
                        startFetches();
                    }, Math.max(1, Utilities.parseInt(error.text)) * 1000L);
                }
                return;
            }
            // left, banned or private now: skip it in this session
            exhausted.add(fetch[0]);
            onFetchFinished();
        }
    }

    // endregion
}
