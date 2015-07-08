/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import android.content.Context;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import dalvik.system.CloseGuard;

/**
 * Class providing enumeration over buckets of network usage statistics. {@link NetworkStats} objects
 * are returned as results to various queries in {@link NetworkStatsManager}.
 */
public final class NetworkStats implements AutoCloseable {
    private final static String TAG = "NetworkStats";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /**
     * Start timestamp of stats collected
     */
    private final long mStartTimeStamp;

    /**
     * End timestamp of stats collected
     */
    private final long mEndTimeStamp;


    /**
     * Non-null array indicates the query enumerates over uids.
     */
    private int[] mUids;

    /**
     * Index of the current uid in mUids when doing uid enumeration or a single uid value,
     * depending on query type.
     */
    private int mUidOrUidIndex;

    /**
     * The session while the query requires it, null if all the stats have been collected or close()
     * has been called.
     */
    private INetworkStatsSession mSession;
    private NetworkTemplate mTemplate;

    /**
     * Results of a summary query.
     */
    private android.net.NetworkStats mSummary = null;

    /**
     * Results of detail queries.
     */
    private NetworkStatsHistory mHistory = null;

    /**
     * Where we are in enumerating over the current result.
     */
    private int mEnumerationIndex = 0;

    /**
     * Recycling entry objects to prevent heap fragmentation.
     */
    private android.net.NetworkStats.Entry mRecycledSummaryEntry = null;
    private NetworkStatsHistory.Entry mRecycledHistoryEntry = null;

    /** @hide */
    NetworkStats(Context context, NetworkTemplate template, long startTimestamp,
            long endTimestamp) throws RemoteException, SecurityException {
        final INetworkStatsService statsService = INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        // Open network stats session
        mSession = statsService.openSessionForUsageStats(context.getOpPackageName());
        mCloseGuard.open("close");
        mTemplate = template;
        mStartTimeStamp = startTimestamp;
        mEndTimeStamp = endTimestamp;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        } finally {
            super.finalize();
        }
    }

    // -------------------------BEGINNING OF PUBLIC API-----------------------------------

    /**
     * Buckets are the smallest elements of a query result. As some dimensions of a result may be
     * aggregated (e.g. time or state) some values may be equal across all buckets.
     */
    public static class Bucket {
        /**
         * Combined usage across all other states.
         */
        public static final int STATE_ALL = -1;

        /**
         * Usage not accounted in any other states.
         */
        public static final int STATE_DEFAULT = 0x1;

        /**
         * Foreground usage.
         */
        public static final int STATE_FOREGROUND = 0x2;

        /**
         * Special UID value for aggregate/unspecified.
         */
        public static final int UID_ALL = android.net.NetworkStats.UID_ALL;

        /**
         * Special UID value for removed apps.
         */
        public static final int UID_REMOVED = TrafficStats.UID_REMOVED;

        /**
         * Special UID value for data usage by tethering.
         */
        public static final int UID_TETHERING = TrafficStats.UID_TETHERING;

        private int mUid;
        private int mState;
        private long mBeginTimeStamp;
        private long mEndTimeStamp;
        private long mRxBytes;
        private long mRxPackets;
        private long mTxBytes;
        private long mTxPackets;

        private static int convertState(int networkStatsSet) {
            switch (networkStatsSet) {
                case android.net.NetworkStats.SET_ALL : return STATE_ALL;
                case android.net.NetworkStats.SET_DEFAULT : return STATE_DEFAULT;
                case android.net.NetworkStats.SET_FOREGROUND : return STATE_FOREGROUND;
            }
            return 0;
        }

        private static int convertUid(int uid) {
            switch (uid) {
                case TrafficStats.UID_REMOVED: return UID_REMOVED;
                case TrafficStats.UID_TETHERING: return UID_TETHERING;
            }
            return uid;
        }

        public Bucket() {
        }

        /**
         * Key of the bucket. Usually an app uid or one of the following special values:<p />
         * <ul>
         * <li>{@link #UID_REMOVED}</li>
         * <li>{@link #UID_TETHERING}</li>
         * <li>{@link android.os.Process#SYSTEM_UID}</li>
         * </ul>
         * @return Bucket key.
         */
        public int getUid() {
            return mUid;
        }

        /**
         * Usage state. One of the following values:<p/>
         * <ul>
         * <li>{@link #STATE_ALL}</li>
         * <li>{@link #STATE_DEFAULT}</li>
         * <li>{@link #STATE_FOREGROUND}</li>
         * </ul>
         * @return Usage state.
         */
        public int getState() {
            return mState;
        }

        /**
         * Start timestamp of the bucket's time interval. Defined in terms of "Unix time", see
         * {@link java.lang.System#currentTimeMillis}.
         * @return Start of interval.
         */
        public long getStartTimeStamp() {
            return mBeginTimeStamp;
        }

        /**
         * End timestamp of the bucket's time interval. Defined in terms of "Unix time", see
         * {@link java.lang.System#currentTimeMillis}.
         * @return End of interval.
         */
        public long getEndTimeStamp() {
            return mEndTimeStamp;
        }

        /**
         * Number of bytes received during the bucket's time interval. Statistics are measured at
         * the network layer, so they include both TCP and UDP usage.
         * @return Number of bytes.
         */
        public long getRxBytes() {
            return mRxBytes;
        }

        /**
         * Number of bytes transmitted during the bucket's time interval. Statistics are measured at
         * the network layer, so they include both TCP and UDP usage.
         * @return Number of bytes.
         */
        public long getTxBytes() {
            return mTxBytes;
        }

        /**
         * Number of packets received during the bucket's time interval. Statistics are measured at
         * the network layer, so they include both TCP and UDP usage.
         * @return Number of packets.
         */
        public long getRxPackets() {
            return mRxPackets;
        }

        /**
         * Number of packets transmitted during the bucket's time interval. Statistics are measured
         * at the network layer, so they include both TCP and UDP usage.
         * @return Number of packets.
         */
        public long getTxPackets() {
            return mTxPackets;
        }
    }

    /**
     * Fills the recycled bucket with data of the next bin in the enumeration.
     * @param bucketOut Bucket to be filled with data.
     * @return true if successfully filled the bucket, false otherwise.
     */
    public boolean getNextBucket(Bucket bucketOut) {
        if (mSummary != null) {
            return getNextSummaryBucket(bucketOut);
        } else {
            return getNextHistoryBucket(bucketOut);
        }
    }

    /**
     * Check if it is possible to ask for a next bucket in the enumeration.
     * @return true if there is at least one more bucket.
     */
    public boolean hasNextBucket() {
        if (mSummary != null) {
            return mEnumerationIndex < mSummary.size();
        } else if (mHistory != null) {
            return mEnumerationIndex < mHistory.size()
                    || hasNextUid();
        }
        return false;
    }

    /**
     * Closes the enumeration. Call this method before this object gets out of scope.
     */
    @Override
    public void close() {
        if (mSession != null) {
            try {
                mSession.close();
            } catch (RemoteException e) {
                Log.w(TAG, e);
                // Otherwise, meh
            }
        }
        mSession = null;
        if (mCloseGuard != null) {
            mCloseGuard.close();
        }
    }

    // -------------------------END OF PUBLIC API-----------------------------------

    /**
     * Collects device summary results into a Bucket.
     * @throws RemoteException
     */
    Bucket getDeviceSummaryForNetwork() throws RemoteException {
        mSummary = mSession.getDeviceSummaryForNetwork(mTemplate, mStartTimeStamp, mEndTimeStamp);

        // Setting enumeration index beyond end to avoid accidental enumeration over data that does
        // not belong to the calling user.
        mEnumerationIndex = mSummary.size();

        return getSummaryAggregate();
    }

    /**
     * Collects summary results and sets summary enumeration mode.
     * @throws RemoteException
     */
    void startSummaryEnumeration() throws RemoteException {
        mSummary = mSession.getSummaryForAllUid(mTemplate, mStartTimeStamp, mEndTimeStamp, false);

        mEnumerationIndex = 0;
    }

    /**
     * Collects history results for uid and resets history enumeration index.
     */
    void startHistoryEnumeration(int uid) {
        mHistory = null;
        try {
            mHistory = mSession.getHistoryIntervalForUid(mTemplate, uid,
                    android.net.NetworkStats.SET_ALL, android.net.NetworkStats.TAG_NONE,
                    NetworkStatsHistory.FIELD_ALL, mStartTimeStamp, mEndTimeStamp);
            setSingleUid(uid);
        } catch (RemoteException e) {
            Log.w(TAG, e);
            // Leaving mHistory null
        }
        mEnumerationIndex = 0;
    }

    /**
     * Starts uid enumeration for current user.
     * @throws RemoteException
     */
    void startUserUidEnumeration() throws RemoteException {
        setUidEnumeration(mSession.getRelevantUids());
        stepHistory();
    }

    /**
     * Steps to next uid in enumeration and collects history for that.
     */
    private void stepHistory(){
        if (hasNextUid()) {
            stepUid();
            mHistory = null;
            try {
                mHistory = mSession.getHistoryIntervalForUid(mTemplate, getUid(),
                        android.net.NetworkStats.SET_ALL, android.net.NetworkStats.TAG_NONE,
                        NetworkStatsHistory.FIELD_ALL, mStartTimeStamp, mEndTimeStamp);
            } catch (RemoteException e) {
                Log.w(TAG, e);
                // Leaving mHistory null
            }
            mEnumerationIndex = 0;
        }
    }

    private void fillBucketFromSummaryEntry(Bucket bucketOut) {
        bucketOut.mUid = Bucket.convertUid(mRecycledSummaryEntry.uid);
        bucketOut.mState = Bucket.convertState(mRecycledSummaryEntry.set);
        bucketOut.mBeginTimeStamp = mStartTimeStamp;
        bucketOut.mEndTimeStamp = mEndTimeStamp;
        bucketOut.mRxBytes = mRecycledSummaryEntry.rxBytes;
        bucketOut.mRxPackets = mRecycledSummaryEntry.rxPackets;
        bucketOut.mTxBytes = mRecycledSummaryEntry.txBytes;
        bucketOut.mTxPackets = mRecycledSummaryEntry.txPackets;
    }

    /**
     * Getting the next item in summary enumeration.
     * @param bucketOut Next item will be set here.
     * @return true if a next item could be set.
     */
    private boolean getNextSummaryBucket(Bucket bucketOut) {
        if (bucketOut != null && mEnumerationIndex < mSummary.size()) {
            mRecycledSummaryEntry = mSummary.getValues(mEnumerationIndex++, mRecycledSummaryEntry);
            fillBucketFromSummaryEntry(bucketOut);
            return true;
        }
        return false;
    }

    Bucket getSummaryAggregate() {
        if (mSummary == null) {
            return null;
        }
        Bucket bucket = new Bucket();
        if (mRecycledSummaryEntry == null) {
            mRecycledSummaryEntry = new android.net.NetworkStats.Entry();
        }
        mSummary.getTotal(mRecycledSummaryEntry);
        fillBucketFromSummaryEntry(bucket);
        return bucket;
    }
    /**
     * Getting the next item in a history enumeration.
     * @param bucketOut Next item will be set here.
     * @return true if a next item could be set.
     */
    private boolean getNextHistoryBucket(Bucket bucketOut) {
        if (bucketOut != null && mHistory != null) {
            if (mEnumerationIndex < mHistory.size()) {
                mRecycledHistoryEntry = mHistory.getValues(mEnumerationIndex++,
                        mRecycledHistoryEntry);
                bucketOut.mUid = Bucket.convertUid(getUid());
                bucketOut.mState = Bucket.STATE_ALL;
                bucketOut.mBeginTimeStamp = mRecycledHistoryEntry.bucketStart;
                bucketOut.mEndTimeStamp = mRecycledHistoryEntry.bucketStart +
                        mRecycledHistoryEntry.bucketDuration;
                bucketOut.mRxBytes = mRecycledHistoryEntry.rxBytes;
                bucketOut.mRxPackets = mRecycledHistoryEntry.rxPackets;
                bucketOut.mTxBytes = mRecycledHistoryEntry.txBytes;
                bucketOut.mTxPackets = mRecycledHistoryEntry.txPackets;
                return true;
            } else if (hasNextUid()) {
                stepHistory();
                return getNextHistoryBucket(bucketOut);
            }
        }
        return false;
    }

    // ------------------ UID LOGIC------------------------

    private boolean isUidEnumeration() {
        return mUids != null;
    }

    private boolean hasNextUid() {
        return isUidEnumeration() && (mUidOrUidIndex + 1) < mUids.length;
    }

    private int getUid() {
        // Check if uid enumeration.
        if (isUidEnumeration()) {
            if (mUidOrUidIndex < 0 || mUidOrUidIndex >= mUids.length) {
                throw new IndexOutOfBoundsException(
                        "Index=" + mUidOrUidIndex + " mUids.length=" + mUids.length);
            }
            return mUids[mUidOrUidIndex];
        }
        // Single uid mode.
        return mUidOrUidIndex;
    }

    private void setSingleUid(int uid) {
        mUidOrUidIndex = uid;
    }

    private void setUidEnumeration(int[] uids) {
        mUids = uids;
        mUidOrUidIndex = -1;
    }

    private void stepUid() {
        if (mUids != null) {
            ++mUidOrUidIndex;
        }
    }
}
