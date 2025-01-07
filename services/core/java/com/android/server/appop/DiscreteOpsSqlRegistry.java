/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.appop;

import static android.app.AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR;
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_RECEIVER;
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_TRUSTED;
import static android.app.AppOpsManager.flagsToString;
import static android.app.AppOpsManager.getUidStateName;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.LongSparseArray;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.ServiceThread;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This class handles sqlite persistence layer for discrete ops.
 */
public class DiscreteOpsSqlRegistry extends DiscreteOpsRegistry {
    private static final String TAG = "DiscreteOpsSqlRegistry";

    private final Context mContext;
    private final DiscreteOpsDbHelper mDiscreteOpsDbHelper;
    private final SqliteWriteHandler mSqliteWriteHandler;
    private final DiscreteOpCache mDiscreteOpCache = new DiscreteOpCache(512);
    private static final long THREE_HOURS = Duration.ofHours(3).toMillis();
    private static final int WRITE_CACHE_EVICTED_OP_EVENTS = 1;
    private static final int DELETE_OLD_OP_EVENTS = 2;
    // Attribution chain id is used to identify an attribution source chain, This is
    // set for startOp only. PermissionManagerService resets this ID on device restart, so
    // we use previously persisted chain id as offset, and add it to chain id received from
    // permission manager service.
    private long mChainIdOffset;
    private final File mDatabaseFile;

    DiscreteOpsSqlRegistry(Context context) {
        this(context, DiscreteOpsDbHelper.getDatabaseFile());
    }

    DiscreteOpsSqlRegistry(Context context, File databaseFile) {
        ServiceThread thread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND, true);
        thread.start();
        mContext = context;
        mDatabaseFile = databaseFile;
        mSqliteWriteHandler = new SqliteWriteHandler(thread.getLooper());
        mDiscreteOpsDbHelper = new DiscreteOpsDbHelper(context, databaseFile);
        mChainIdOffset = mDiscreteOpsDbHelper.getLargestAttributionChainId();
    }

    @Override
    void recordDiscreteAccess(int uid, String packageName,
            @NonNull String deviceId, int op,
            @Nullable String attributionTag, int flags, int uidState,
            long accessTime, long accessDuration, int attributionFlags, int attributionChainId,
            int accessType) {
        if (shouldLogAccess(op)) {
            FrameworkStatsLog.write(FrameworkStatsLog.APP_OP_ACCESS_TRACKED, uid, op, accessType,
                    uidState, flags, attributionFlags,
                    getAttributionTag(attributionTag, packageName),
                    attributionChainId);
        }

        if (!isDiscreteOp(op, flags)) {
            return;
        }

        long offsetChainId = attributionChainId;
        if (attributionChainId != ATTRIBUTION_CHAIN_ID_NONE) {
            offsetChainId = attributionChainId + mChainIdOffset;
            // PermissionManagerService chain id reached the max value,
            // reset offset, it's going to be very rare.
            if (attributionChainId == Integer.MAX_VALUE) {
                mChainIdOffset = offsetChainId;
            }
        }
        DiscreteOp discreteOpEvent = new DiscreteOp(uid, packageName, attributionTag, deviceId, op,
                flags, attributionFlags, uidState, offsetChainId, accessTime, accessDuration);
        mDiscreteOpCache.add(discreteOpEvent);
    }

    @Override
    void writeAndClearOldAccessHistory() {
        // Let the sql impl also follow the same disk write frequencies as xml,
        // controlled by AppOpsService.
        mDiscreteOpsDbHelper.insertDiscreteOps(mDiscreteOpCache.getAllEventsAndClear());
        if (!mSqliteWriteHandler.hasMessages(DELETE_OLD_OP_EVENTS)) {
            if (mSqliteWriteHandler.sendEmptyMessageDelayed(DELETE_OLD_OP_EVENTS, THREE_HOURS)) {
                Slog.w(TAG, "DELETE_OLD_OP_EVENTS is not queued");
            }
        }
    }

    @Override
    void clearHistory() {
        mDiscreteOpCache.clear();
        mDiscreteOpsDbHelper.execSQL(DiscreteOpsTable.DELETE_TABLE_DATA);
    }

    @Override
    void clearHistory(int uid, String packageName) {
        mDiscreteOpCache.clear(uid, packageName);
        mDiscreteOpsDbHelper.execSQL(DiscreteOpsTable.DELETE_DATA_FOR_UID_PACKAGE,
                new Object[]{uid, packageName});
    }

    @Override
    void offsetHistory(long offset) {
        mDiscreteOpCache.offsetTimestamp(offset);
        mDiscreteOpsDbHelper.execSQL(DiscreteOpsTable.OFFSET_ACCESS_TIME,
                new Object[]{offset});
    }

    private IntArray getAppOpCodes(@AppOpsManager.HistoricalOpsRequestFilter int filter,
            @Nullable String[] opNamesFilter) {
        if ((filter & AppOpsManager.FILTER_BY_OP_NAMES) != 0) {
            IntArray opCodes = new IntArray(opNamesFilter.length);
            for (int i = 0; i < opNamesFilter.length; i++) {
                int op;
                try {
                    op = AppOpsManager.strOpToOp(opNamesFilter[i]);
                } catch (IllegalArgumentException ex) {
                    Slog.w(TAG, "Appop `" + opNamesFilter[i] + "` is not recognized.");
                    continue;
                }
                opCodes.add(op);
            }
            return opCodes;
        }
        return null;
    }

    @Override
    void addFilteredDiscreteOpsToHistoricalOps(AppOpsManager.HistoricalOps result,
            long beginTimeMillis, long endTimeMillis, int filter, int uidFilter,
            @Nullable String packageNameFilter,
            @Nullable String[] opNamesFilter,
            @Nullable String attributionTagFilter, int opFlagsFilter,
            Set<String> attributionExemptPkgs) {
        // flush the cache into database before read.
        writeAndClearOldAccessHistory();
        boolean assembleChains = attributionExemptPkgs != null;
        IntArray opCodes = getAppOpCodes(filter, opNamesFilter);
        List<DiscreteOp> discreteOps = mDiscreteOpsDbHelper.getDiscreteOps(filter, uidFilter,
                packageNameFilter, attributionTagFilter, opCodes, opFlagsFilter, beginTimeMillis,
                endTimeMillis, -1, null);

        LongSparseArray<AttributionChain> attributionChains = null;
        if (assembleChains) {
            attributionChains = createAttributionChains(discreteOps, attributionExemptPkgs);
        }

        int nEvents = discreteOps.size();
        for (int j = 0; j < nEvents; j++) {
            DiscreteOp event = discreteOps.get(j);
            AppOpsManager.OpEventProxyInfo proxy = null;
            if (assembleChains && event.mChainId != ATTRIBUTION_CHAIN_ID_NONE) {
                AttributionChain chain = attributionChains.get(event.mChainId);
                if (chain != null && chain.isComplete()
                        && chain.isStart(event)
                        && chain.mLastVisibleEvent != null) {
                    DiscreteOp proxyEvent = chain.mLastVisibleEvent;
                    proxy = new AppOpsManager.OpEventProxyInfo(proxyEvent.mUid,
                            proxyEvent.mPackageName, proxyEvent.mAttributionTag);
                }
            }
            result.addDiscreteAccess(event.mOpCode, event.mUid, event.mPackageName,
                    event.mAttributionTag, event.mUidState, event.mOpFlags,
                    event.mDiscretizedAccessTime, event.mDiscretizedDuration, proxy);
        }
    }

    @Override
    void dump(@NonNull PrintWriter pw, int uidFilter, @Nullable String packageNameFilter,
            @Nullable String attributionTagFilter,
            @AppOpsManager.HistoricalOpsRequestFilter int filter, int dumpOp,
            @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix,
            int nDiscreteOps) {
        writeAndClearOldAccessHistory();
        IntArray opCodes = new IntArray();
        if (dumpOp != AppOpsManager.OP_NONE) {
            opCodes.add(dumpOp);
        }
        List<DiscreteOp> discreteOps = mDiscreteOpsDbHelper.getDiscreteOps(filter, uidFilter,
                packageNameFilter, attributionTagFilter, opCodes, 0, -1,
                -1, nDiscreteOps, DiscreteOpsTable.Columns.ACCESS_TIME);

        pw.print(prefix);
        pw.print("Largest chain id: ");
        pw.print(mDiscreteOpsDbHelper.getLargestAttributionChainId());
        pw.println();
        pw.println("UID|PACKAGE_NAME|DEVICE_ID|OP_NAME|ATTRIBUTION_TAG|UID_STATE|OP_FLAGS|"
                + "ATTR_FLAGS|CHAIN_ID|ACCESS_TIME|DURATION");
        int discreteOpsCount = discreteOps.size();
        for (int i = 0; i < discreteOpsCount; i++) {
            DiscreteOp event = discreteOps.get(i);
            date.setTime(event.mAccessTime);
            pw.println(event.mUid + "|" + event.mPackageName + "|" + event.mDeviceId + "|"
                    + AppOpsManager.opToName(event.mOpCode) + "|" + event.mAttributionTag + "|"
                    + getUidStateName(event.mUidState) + "|"
                    + flagsToString(event.mOpFlags) + "|" + event.mAttributionFlags + "|"
                    + event.mChainId + "|"
                    + sdf.format(date) + "|" + event.mDuration);
        }
        pw.println();
    }

    void migrateXmlData(List<DiscreteOp> opEvents, int chainIdOffset) {
        mChainIdOffset = chainIdOffset;
        mDiscreteOpsDbHelper.insertDiscreteOps(opEvents);
    }

    LongSparseArray<AttributionChain> createAttributionChains(
            List<DiscreteOp> discreteOps, Set<String> attributionExemptPkgs) {
        LongSparseArray<AttributionChain> chains = new LongSparseArray<>();
        final int count = discreteOps.size();

        for (int i = 0; i < count; i++) {
            DiscreteOp opEvent = discreteOps.get(i);
            if (opEvent.mChainId == ATTRIBUTION_CHAIN_ID_NONE
                    || (opEvent.mAttributionFlags & ATTRIBUTION_FLAG_TRUSTED) == 0) {
                continue;
            }
            AttributionChain chain = chains.get(opEvent.mChainId);
            if (chain == null) {
                chain = new AttributionChain(attributionExemptPkgs);
                chains.put(opEvent.mChainId, chain);
            }
            chain.addEvent(opEvent);
        }
        return chains;
    }

    static class AttributionChain {
        List<DiscreteOp> mChain = new ArrayList<>();
        Set<String> mExemptPkgs;
        DiscreteOp mStartEvent = null;
        DiscreteOp mLastVisibleEvent = null;

        AttributionChain(Set<String> exemptPkgs) {
            mExemptPkgs = exemptPkgs;
        }

        boolean isComplete() {
            return !mChain.isEmpty() && getStart() != null && isEnd(mChain.get(mChain.size() - 1));
        }

        DiscreteOp getStart() {
            return mChain.isEmpty() || !isStart(mChain.get(0)) ? null : mChain.get(0);
        }

        private boolean isEnd(DiscreteOp event) {
            return event != null
                    && (event.mAttributionFlags & ATTRIBUTION_FLAG_ACCESSOR) != 0;
        }

        private boolean isStart(DiscreteOp event) {
            return event != null
                    && (event.mAttributionFlags & ATTRIBUTION_FLAG_RECEIVER) != 0;
        }

        DiscreteOp getLastVisible() {
            // Search all nodes but the first one, which is the start node
            for (int i = mChain.size() - 1; i > 0; i--) {
                DiscreteOp event = mChain.get(i);
                if (!mExemptPkgs.contains(event.mPackageName)) {
                    return event;
                }
            }
            return null;
        }

        void addEvent(DiscreteOp opEvent) {
            // check if we have a matching event except duration.
            DiscreteOp matchingItem = null;
            for (int i = 0; i < mChain.size(); i++) {
                DiscreteOp item = mChain.get(i);
                if (item.equalsExceptDuration(opEvent)) {
                    matchingItem = item;
                    break;
                }
            }

            if (matchingItem != null) {
                // exact match or existing event has longer duration
                if (matchingItem.mDuration == opEvent.mDuration
                        || matchingItem.mDuration > opEvent.mDuration) {
                    return;
                }
                mChain.remove(matchingItem);
            }

            if (mChain.isEmpty() || isEnd(opEvent)) {
                mChain.add(opEvent);
            } else if (isStart(opEvent)) {
                mChain.add(0, opEvent);
            } else {
                for (int i = 0; i < mChain.size(); i++) {
                    DiscreteOp currEvent = mChain.get(i);
                    if ((!isStart(currEvent)
                            && currEvent.mAccessTime > opEvent.mAccessTime)
                            || (i == mChain.size() - 1 && isEnd(currEvent))) {
                        mChain.add(i, opEvent);
                        break;
                    } else if (i == mChain.size() - 1) {
                        mChain.add(opEvent);
                        break;
                    }
                }
            }
            mStartEvent = isComplete() ? getStart() : null;
            mLastVisibleEvent = isComplete() ? getLastVisible() : null;
        }
    }

    /**
     * Handler to write asynchronously to sqlite database.
     */
    class SqliteWriteHandler extends Handler {
        SqliteWriteHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_CACHE_EVICTED_OP_EVENTS:
                    List<DiscreteOp> opEvents = (List<DiscreteOp>) msg.obj;
                    mDiscreteOpsDbHelper.insertDiscreteOps(opEvents);
                    break;
                case DELETE_OLD_OP_EVENTS:
                    long cutOffTimeStamp = System.currentTimeMillis() - sDiscreteHistoryCutoff;
                    mDiscreteOpsDbHelper.execSQL(
                            DiscreteOpsTable.DELETE_TABLE_DATA_BEFORE_ACCESS_TIME,
                            new Object[]{cutOffTimeStamp});
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + msg.what);
            }
        }
    }

    /**
     * A write cache for discrete ops. The noteOp, start/finishOp discrete op events are written to
     * the cache first.
     * <p>
     * These events are persisted into sqlite database
     * 1) Periodic interval, controlled by {@link AppOpsService}
     * 2) When total events in the cache exceeds cache limit.
     * 3) During read call we flush the whole cache to sqlite.
     * 4) During shutdown.
     */
    class DiscreteOpCache {
        private final int mCapacity;
        private final ArraySet<DiscreteOp> mCache;

        DiscreteOpCache(int capacity) {
            mCapacity = capacity;
            mCache = new ArraySet<>();
        }

        public void add(DiscreteOp opEvent) {
            synchronized (this) {
                if (mCache.contains(opEvent)) {
                    return;
                }
                mCache.add(opEvent);
                if (mCache.size() >= mCapacity) {
                    if (DEBUG_LOG) {
                        Slog.i(TAG, "Current discrete ops cache size: " + mCache.size());
                    }
                    List<DiscreteOp> evictedEvents = evict();
                    if (DEBUG_LOG) {
                        Slog.i(TAG, "Evicted discrete ops size: " + evictedEvents.size());
                    }
                    // if nothing to evict, just write the whole cache to disk
                    if (evictedEvents.isEmpty()) {
                        Slog.w(TAG, "No discrete ops event is evicted, write cache to db.");
                        evictedEvents.addAll(mCache);
                        mCache.clear();
                    }
                    mSqliteWriteHandler.obtainMessage(WRITE_CACHE_EVICTED_OP_EVENTS, evictedEvents);
                }
            }
        }

        /**
         * Evict entries older than {@link DiscreteOpsRegistry#sDiscreteHistoryQuantization}.
         */
        private List<DiscreteOp> evict() {
            synchronized (this) {
                List<DiscreteOp> evictedEvents = new ArrayList<>();
                Set<DiscreteOp> snapshot = new ArraySet<>(mCache);
                long evictionTimestamp = System.currentTimeMillis() - sDiscreteHistoryQuantization;
                evictionTimestamp = discretizeTimeStamp(evictionTimestamp);
                for (DiscreteOp opEvent : snapshot) {
                    if (opEvent.mDiscretizedAccessTime <= evictionTimestamp) {
                        evictedEvents.add(opEvent);
                        mCache.remove(opEvent);
                    }
                }
                return evictedEvents;
            }
        }

        /**
         * Remove all the entries from cache.
         *
         * @return return all removed entries.
         */
        public List<DiscreteOp> getAllEventsAndClear() {
            synchronized (this) {
                List<DiscreteOp> cachedOps = new ArrayList<>(mCache.size());
                if (mCache.isEmpty()) {
                    return cachedOps;
                }
                cachedOps.addAll(mCache);
                mCache.clear();
                return cachedOps;
            }
        }

        /**
         * Remove all entries from the cache.
         */
        public void clear() {
            synchronized (this) {
                mCache.clear();
            }
        }

        /**
         * Offset access time by given offset milliseconds.
         */
        public void offsetTimestamp(long offsetMillis) {
            synchronized (this) {
                List<DiscreteOp> cachedOps = new ArrayList<>(mCache);
                mCache.clear();
                for (DiscreteOp discreteOp : cachedOps) {
                    add(new DiscreteOp(discreteOp.getUid(), discreteOp.mPackageName,
                            discreteOp.getAttributionTag(), discreteOp.getDeviceId(),
                            discreteOp.mOpCode, discreteOp.mOpFlags,
                            discreteOp.getAttributionFlags(), discreteOp.getUidState(),
                            discreteOp.getChainId(), discreteOp.mAccessTime - offsetMillis,
                            discreteOp.getDuration())
                    );
                }
            }
        }

        /** Remove cached events for given UID and package. */
        public void clear(int uid, String packageName) {
            synchronized (this) {
                Set<DiscreteOp> snapshot = new ArraySet<>(mCache);
                for (DiscreteOp currentEvent : snapshot) {
                    if (Objects.equals(packageName, currentEvent.mPackageName)
                            && uid == currentEvent.getUid()) {
                        mCache.remove(currentEvent);
                    }
                }
            }
        }
    }

    /** Immutable discrete op object. */
    static class DiscreteOp {
        private final int mUid;
        private final String mPackageName;
        private final String mAttributionTag;
        private final String mDeviceId;
        private final int mOpCode;
        private final int mOpFlags;
        private final int mAttributionFlags;
        private final int mUidState;
        private final long mChainId;
        private final long mAccessTime;
        private final long mDuration;
        // store discretized timestamp to avoid repeated calculations.
        private final long mDiscretizedAccessTime;
        private final long mDiscretizedDuration;

        DiscreteOp(int uid, String packageName, String attributionTag, String deviceId,
                int opCode,
                int mOpFlags, int mAttributionFlags, int uidState, long chainId, long accessTime,
                long duration) {
            this.mUid = uid;
            this.mPackageName = packageName.intern();
            this.mAttributionTag = attributionTag;
            this.mDeviceId = deviceId;
            this.mOpCode = opCode;
            this.mOpFlags = mOpFlags;
            this.mAttributionFlags = mAttributionFlags;
            this.mUidState = uidState;
            this.mChainId = chainId;
            this.mAccessTime = accessTime;
            this.mDiscretizedAccessTime = discretizeTimeStamp(accessTime);
            this.mDuration = duration;
            this.mDiscretizedDuration = discretizeDuration(duration);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DiscreteOp that)) return false;

            if (mUid != that.mUid) return false;
            if (mOpCode != that.mOpCode) return false;
            if (mOpFlags != that.mOpFlags) return false;
            if (mAttributionFlags != that.mAttributionFlags) return false;
            if (mUidState != that.mUidState) return false;
            if (mChainId != that.mChainId) return false;
            if (!Objects.equals(mPackageName, that.mPackageName)) {
                return false;
            }
            if (!Objects.equals(mAttributionTag, that.mAttributionTag)) {
                return false;
            }
            if (!Objects.equals(mDeviceId, that.mDeviceId)) {
                return false;
            }
            if (mDiscretizedAccessTime != that.mDiscretizedAccessTime) {
                return false;
            }
            return mDiscretizedDuration == that.mDiscretizedDuration;
        }

        @Override
        public int hashCode() {
            int result = mUid;
            result = 31 * result + (mPackageName != null ? mPackageName.hashCode() : 0);
            result = 31 * result + (mAttributionTag != null ? mAttributionTag.hashCode() : 0);
            result = 31 * result + (mDeviceId != null ? mDeviceId.hashCode() : 0);
            result = 31 * result + mOpCode;
            result = 31 * result + mOpFlags;
            result = 31 * result + mAttributionFlags;
            result = 31 * result + mUidState;
            result = 31 * result + Objects.hash(mChainId);
            result = 31 * result + Objects.hash(mDiscretizedAccessTime);
            result = 31 * result + Objects.hash(mDiscretizedDuration);
            return result;
        }

        public boolean equalsExceptDuration(DiscreteOp that) {
            if (mUid != that.mUid) return false;
            if (mOpCode != that.mOpCode) return false;
            if (mOpFlags != that.mOpFlags) return false;
            if (mAttributionFlags != that.mAttributionFlags) return false;
            if (mUidState != that.mUidState) return false;
            if (mChainId != that.mChainId) return false;
            if (!Objects.equals(mPackageName, that.mPackageName)) {
                return false;
            }
            if (!Objects.equals(mAttributionTag, that.mAttributionTag)) {
                return false;
            }
            if (!Objects.equals(mDeviceId, that.mDeviceId)) {
                return false;
            }
            return mAccessTime == that.mAccessTime;
        }

        @Override
        public String toString() {
            return "DiscreteOp{"
                    + "uid=" + mUid
                    + ", packageName='" + mPackageName + '\''
                    + ", attributionTag='" + mAttributionTag + '\''
                    + ", deviceId='" + mDeviceId + '\''
                    + ", opCode=" + AppOpsManager.opToName(mOpCode)
                    + ", opFlag=" + flagsToString(mOpFlags)
                    + ", attributionFlag=" + mAttributionFlags
                    + ", uidState=" + getUidStateName(mUidState)
                    + ", chainId=" + mChainId
                    + ", accessTime=" + mAccessTime
                    + ", duration=" + mDuration + '}';
        }

        public int getUid() {
            return mUid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        public String getDeviceId() {
            return mDeviceId;
        }

        public int getOpCode() {
            return mOpCode;
        }

        @AppOpsManager.OpFlags
        public int getOpFlags() {
            return mOpFlags;
        }


        @AppOpsManager.AttributionFlags
        public int getAttributionFlags() {
            return mAttributionFlags;
        }

        @AppOpsManager.UidState
        public int getUidState() {
            return mUidState;
        }

        public long getChainId() {
            return mChainId;
        }

        public long getAccessTime() {
            return mAccessTime;
        }

        public long getDuration() {
            return mDuration;
        }
    }

    // API for tests only, can be removed or changed.
    void recordDiscreteAccess(DiscreteOp discreteOpEvent) {
        mDiscreteOpCache.add(discreteOpEvent);
    }

    // API for tests only, can be removed or changed.
    List<DiscreteOp> getCachedDiscreteOps() {
        return new ArrayList<>(mDiscreteOpCache.mCache);
    }

    // API for tests only, can be removed or changed.
    List<DiscreteOp> getAllDiscreteOps() {
        List<DiscreteOp> ops = new ArrayList<>(mDiscreteOpCache.mCache);
        ops.addAll(mDiscreteOpsDbHelper.getAllDiscreteOps(DiscreteOpsTable.SELECT_TABLE_DATA));
        return ops;
    }

    // API for testing and migration
    long getLargestAttributionChainId() {
        return mDiscreteOpsDbHelper.getLargestAttributionChainId();
    }

    // API for testing and migration
    void deleteDatabase() {
        mDiscreteOpsDbHelper.close();
        mContext.deleteDatabase(mDatabaseFile.getName());
    }
}
