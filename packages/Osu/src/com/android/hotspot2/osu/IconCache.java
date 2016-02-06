package com.android.hotspot2.osu;

import android.util.Log;

import com.android.anqp.HSIconFileElement;
import com.android.anqp.IconInfo;
import com.android.hotspot2.Utils;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.android.anqp.Constants.ANQPElementType.HSIconFile;

public class IconCache extends Thread {
    private static final int CacheSize = 64;
    private static final int RetryCount = 3;

    private final OSUManager mOSUManager;
    private final Map<Long, LinkedList<QuerySet>> mBssQueues = new HashMap<>();

    private final Map<IconKey, HSIconFileElement> mCache =
            new LinkedHashMap<IconKey, HSIconFileElement>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > CacheSize;
                }
            };

    private static class IconKey {
        private final long mBSSID;
        private final long mHESSID;
        private final String mSSID;
        private final int mAnqpDomID;
        private final String mFileName;

        private IconKey(OSUInfo osuInfo, String fileName) {
            mBSSID = osuInfo.getBSSID();
            mHESSID = osuInfo.getHESSID();
            mSSID = osuInfo.getAdvertisingSSID();
            mAnqpDomID = osuInfo.getAnqpDomID();
            mFileName = fileName;
        }

        public String getFileName() {
            return mFileName;
        }

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (thatObject == null || getClass() != thatObject.getClass()) {
                return false;
            }

            IconKey that = (IconKey) thatObject;

            return mFileName.equals(that.mFileName) && ((mBSSID == that.mBSSID) ||
                    ((mAnqpDomID == that.mAnqpDomID) && (mAnqpDomID != 0) &&
                            (mHESSID == that.mHESSID) && ((mHESSID != 0)
                            || mSSID.equals(that.mSSID))));
        }

        @Override
        public int hashCode() {
            int result = (int) (mBSSID ^ (mBSSID >>> 32));
            result = 31 * result + (int) (mHESSID ^ (mHESSID >>> 32));
            result = 31 * result + mSSID.hashCode();
            result = 31 * result + mAnqpDomID;
            result = 31 * result + mFileName.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return String.format("%012x:%012x '%s' [%d] + '%s'",
                    mBSSID, mHESSID, mSSID, mAnqpDomID, mFileName);
        }
    }

    private static class QueryEntry {
        private final IconKey mKey;
        private int mRetry;
        private long mLastSent;

        private QueryEntry(IconKey key) {
            mKey = key;
            mLastSent = System.currentTimeMillis();
        }

        private IconKey getKey() {
            return mKey;
        }

        private int bumpRetry() {
            mLastSent = System.currentTimeMillis();
            return mRetry++;
        }

        private long age(long now) {
            return now - mLastSent;
        }

        @Override
        public String toString() {
            return String.format("Entry %s, retry %d", mKey, mRetry);
        }
    }

    private static class QuerySet {
        private final OSUInfo mOsuInfo;
        private final LinkedList<QueryEntry> mEntries;

        private QuerySet(OSUInfo osuInfo, List<IconInfo> icons) {
            mOsuInfo = osuInfo;
            mEntries = new LinkedList<>();
            for (IconInfo iconInfo : icons) {
                mEntries.addLast(new QueryEntry(new IconKey(osuInfo, iconInfo.getFileName())));
            }
        }

        private QueryEntry peek() {
            return mEntries.getFirst();
        }

        private QueryEntry pop() {
            mEntries.removeFirst();
            return mEntries.isEmpty() ? null : mEntries.getFirst();
        }

        private boolean isEmpty() {
            return mEntries.isEmpty();
        }

        private List<QueryEntry> getAllEntries() {
            return Collections.unmodifiableList(mEntries);
        }

        private long getBssid() {
            return mOsuInfo.getBSSID();
        }

        private OSUInfo getOsuInfo() {
            return mOsuInfo;
        }

        private IconKey updateIcon(String fileName, HSIconFileElement iconFileElement) {
            IconKey key = null;
            for (QueryEntry queryEntry : mEntries) {
                if (queryEntry.getKey().getFileName().equals(fileName)) {
                    key = queryEntry.getKey();
                }
            }
            if (key == null) {
                return null;
            }

            if (iconFileElement != null) {
                mOsuInfo.setIconFileElement(iconFileElement, fileName);
            } else {
                mOsuInfo.setIconStatus(OSUInfo.IconStatus.NotAvailable);
            }
            return key;
        }

        private boolean updateIcon(IconKey key, HSIconFileElement iconFileElement) {
            boolean match = false;
            for (QueryEntry queryEntry : mEntries) {
                if (queryEntry.getKey().equals(key)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return false;
            }

            if (iconFileElement != null) {
                mOsuInfo.setIconFileElement(iconFileElement, key.getFileName());
            } else {
                mOsuInfo.setIconStatus(OSUInfo.IconStatus.NotAvailable);
            }
            return true;
        }

        @Override
        public String toString() {
            return "OSU " + mOsuInfo + ": " + mEntries;
        }
    }

    public IconCache(OSUManager osuManager) {
        mOSUManager = osuManager;
    }

    public void clear() {
        mBssQueues.clear();
        mCache.clear();
    }

    private boolean enqueue(QuerySet querySet) {
        boolean newEntry = false;
        LinkedList<QuerySet> queries = mBssQueues.get(querySet.getBssid());
        if (queries == null) {
            queries = new LinkedList<>();
            mBssQueues.put(querySet.getBssid(), queries);
            newEntry = true;
        }
        queries.addLast(querySet);
        return newEntry;
    }

    public void startIconQuery(OSUInfo osuInfo, List<IconInfo> icons) {
        Log.d("ZXZ", String.format("Icon query on %012x for %s", osuInfo.getBSSID(), icons));
        if (icons == null || icons.isEmpty()) {
            return;
        }

        QuerySet querySet = new QuerySet(osuInfo, icons);
        for (QueryEntry entry : querySet.getAllEntries()) {
            HSIconFileElement iconElement = mCache.get(entry.getKey());
            if (iconElement != null) {
                osuInfo.setIconFileElement(iconElement, entry.getKey().getFileName());
                mOSUManager.iconResults(Arrays.asList(osuInfo));
                return;
            }
        }
        if (enqueue(querySet)) {
            initiateQuery(querySet.getBssid());
        }
    }

    private void initiateQuery(long bssid) {
        LinkedList<QuerySet> queryEntries = mBssQueues.get(bssid);
        if (queryEntries == null) {
            return;
        } else if (queryEntries.isEmpty()) {
            mBssQueues.remove(bssid);
            return;
        }

        QuerySet querySet = queryEntries.getFirst();
        QueryEntry queryEntry = querySet.peek();
        if (queryEntry.bumpRetry() >= RetryCount) {
            QueryEntry newEntry = querySet.pop();
            if (newEntry == null) {
                // No more entries in this QuerySet, advance to the next set.
                querySet.getOsuInfo().setIconStatus(OSUInfo.IconStatus.NotAvailable);
                queryEntries.removeFirst();
                if (queryEntries.isEmpty()) {
                    // No further QuerySet on this BSSID, drop the bucket and bail.
                    mBssQueues.remove(bssid);
                    return;
                } else {
                    querySet = queryEntries.getFirst();
                    queryEntry = querySet.peek();
                    queryEntry.bumpRetry();
                }
            }
        }
        mOSUManager.doIconQuery(bssid, queryEntry.getKey().getFileName());
    }

    public void notifyIconReceived(long bssid, String fileName, byte[] iconData) {
        Log.d("ZXZ", String.format("Icon '%s':%d received from %012x",
                fileName, iconData != null ? iconData.length : -1, bssid));
        IconKey key;
        HSIconFileElement iconFileElement = null;
        List<OSUInfo> updates = new ArrayList<>();

        LinkedList<QuerySet> querySets = mBssQueues.get(bssid);
        if (querySets == null || querySets.isEmpty()) {
            Log.d(OSUManager.TAG,
                    String.format("Spurious icon response from %012x for '%s' (%d) bytes",
                            bssid, fileName, iconData != null ? iconData.length : -1));
            Log.d("ZXZ", "query set: " + querySets
                    + ", BSS queues: " + Utils.bssidsToString(mBssQueues.keySet()));
            return;
        } else {
            QuerySet querySet = querySets.removeFirst();
            if (iconData != null) {
                try {
                    iconFileElement = new HSIconFileElement(HSIconFile,
                            ByteBuffer.wrap(iconData).order(ByteOrder.LITTLE_ENDIAN));
                } catch (ProtocolException | BufferUnderflowException e) {
                    Log.e(OSUManager.TAG, "Failed to parse ANQP icon file: " + e);
                }
            }
            key = querySet.updateIcon(fileName, iconFileElement);
            if (key == null) {
                Log.d(OSUManager.TAG,
                        String.format("Spurious icon response from %012x for '%s' (%d) bytes",
                                bssid, fileName, iconData != null ? iconData.length : -1));
                Log.d("ZXZ", "query set: " + querySets + ", BSS queues: "
                        + Utils.bssidsToString(mBssQueues.keySet()));
                querySets.addFirst(querySet);
                return;
            }

            if (iconFileElement != null) {
                mCache.put(key, iconFileElement);
            }

            if (querySet.isEmpty()) {
                mBssQueues.remove(bssid);
            }
            updates.add(querySet.getOsuInfo());
        }

        // Update any other pending entries that matches the ESS of the currently resolved icon
        Iterator<Map.Entry<Long, LinkedList<QuerySet>>> bssIterator =
                mBssQueues.entrySet().iterator();
        while (bssIterator.hasNext()) {
            Map.Entry<Long, LinkedList<QuerySet>> bssEntries = bssIterator.next();
            Iterator<QuerySet> querySetIterator = bssEntries.getValue().iterator();
            while (querySetIterator.hasNext()) {
                QuerySet querySet = querySetIterator.next();
                if (querySet.updateIcon(key, iconFileElement)) {
                    querySetIterator.remove();
                    updates.add(querySet.getOsuInfo());
                }
            }
            if (bssEntries.getValue().isEmpty()) {
                bssIterator.remove();
            }
        }

        initiateQuery(bssid);

        mOSUManager.iconResults(updates);
    }

    private static final long RequeryTimeLow = 6000L;
    private static final long RequeryTimeHigh = 15000L;

    public void tickle(boolean wifiOff) {
        synchronized (mCache) {
            if (wifiOff) {
                mBssQueues.clear();
            } else {
                long now = System.currentTimeMillis();

                Iterator<Map.Entry<Long, LinkedList<QuerySet>>> bssIterator =
                        mBssQueues.entrySet().iterator();
                while (bssIterator.hasNext()) {
                    // Get the list of entries for this BSSID
                    Map.Entry<Long, LinkedList<QuerySet>> bssEntries = bssIterator.next();
                    Iterator<QuerySet> querySetIterator = bssEntries.getValue().iterator();
                    while (querySetIterator.hasNext()) {
                        QuerySet querySet = querySetIterator.next();
                        QueryEntry queryEntry = querySet.peek();
                        long age = queryEntry.age(now);
                        if (age > RequeryTimeHigh) {
                            // Timed out entry, move on to the next.
                            queryEntry = querySet.pop();
                            if (queryEntry == null) {
                                // Empty query set, update status and remove it.
                                querySet.getOsuInfo()
                                        .setIconStatus(OSUInfo.IconStatus.NotAvailable);
                                querySetIterator.remove();
                            } else {
                                // Start a query on the next entry and bail out of the set iteration
                                initiateQuery(querySet.getBssid());
                                break;
                            }
                        } else if (age > RequeryTimeLow) {
                            // Re-issue queries for qualified entries and bail out of set iteration
                            initiateQuery(querySet.getBssid());
                            break;
                        }
                    }
                    if (bssEntries.getValue().isEmpty()) {
                        // Kill the whole bucket if the set list is empty
                        bssIterator.remove();
                    }
                }
            }
        }
    }
}
