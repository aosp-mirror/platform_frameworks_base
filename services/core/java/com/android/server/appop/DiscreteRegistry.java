/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static android.app.AppOpsManager.FILTER_BY_ATTRIBUTION_TAG;
import static android.app.AppOpsManager.FILTER_BY_OP_NAMES;
import static android.app.AppOpsManager.FILTER_BY_PACKAGE_NAME;
import static android.app.AppOpsManager.FILTER_BY_UID;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXY;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_PHONE_CALL_CAMERA;
import static android.app.AppOpsManager.OP_PHONE_CALL_MICROPHONE;
import static android.app.AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.flagsToString;
import static android.app.AppOpsManager.getUidStateName;

import static java.lang.Long.min;
import static java.lang.Math.max;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This class manages information about recent accesses to ops for permission usage timeline.
 *
 * The discrete history is kept for limited time (initial default is 24 hours, set in
 * {@link DiscreteRegistry#sDiscreteHistoryCutoff) and discarded after that.
 *
 * Discrete history is quantized to reduce resources footprint. By default quantization is set to
 * one minute in {@link DiscreteRegistry#sDiscreteHistoryQuantization}. All access times are aligned
 * to the closest quantized time. All durations (except -1, meaning no duration) are rounded up to
 * the closest quantized interval.
 *
 * When data is queried through API, events are deduplicated and for every time quant there can
 * be only one {@link AppOpsManager.AttributedOpEntry}. Each entry contains information about
 * different accesses which happened in specified time quant - across dimensions of
 * {@link AppOpsManager.UidState} and {@link AppOpsManager.OpFlags}. For each dimension
 * it is only possible to know if at least one access happened in the time quant.
 *
 * Every time state is saved (default is 30 minutes), memory state is dumped to a
 * new file and memory state is cleared. Files older than time limit are deleted
 * during the process.
 *
 * When request comes in, files are read and requested information is collected
 * and delivered. Information is cached in memory until the next state save (up to 30 minutes), to
 * avoid reading disk if more API calls come in a quick succession.
 *
 * THREADING AND LOCKING:
 * For in-memory transactions this class relies on {@link DiscreteRegistry#mInMemoryLock}. It is
 * assumed that the same lock is used for in-memory transactions in {@link AppOpsService},
 * {@link HistoricalRegistry}, and {@link DiscreteRegistry}.
 * {@link DiscreteRegistry#recordDiscreteAccess(int, String, int, String, int, int, long, long)}
 * must only be called while holding this lock.
 * {@link DiscreteRegistry#mOnDiskLock} is used when disk transactions are performed.
 * It is very important to release {@link DiscreteRegistry#mInMemoryLock} as soon as possible, as
 * no AppOps related transactions across the system can be performed while it is held.
 *
 * INITIALIZATION: We can initialize persistence only after the system is ready
 * as we need to check the optional configuration override from the settings
 * database which is not initialized at the time the app ops service is created. This class
 * relies on {@link HistoricalRegistry} for controlling that no calls are allowed until then. All
 * outside calls are going through {@link HistoricalRegistry}, where
 * {@link HistoricalRegistry#isPersistenceInitializedMLocked()} check is done.
 *
 */

final class DiscreteRegistry {
    static final String DISCRETE_HISTORY_FILE_SUFFIX = "tl";
    private static final String TAG = DiscreteRegistry.class.getSimpleName();

    private static final String PROPERTY_DISCRETE_HISTORY_CUTOFF = "discrete_history_cutoff_millis";
    private static final String PROPERTY_DISCRETE_HISTORY_QUANTIZATION =
            "discrete_history_quantization_millis";
    private static final String PROPERTY_DISCRETE_FLAGS = "discrete_history_op_flags";
    private static final String PROPERTY_DISCRETE_OPS_LIST = "discrete_history_ops_cslist";
    private static final String DEFAULT_DISCRETE_OPS = OP_FINE_LOCATION + "," + OP_COARSE_LOCATION
            + "," + OP_CAMERA + "," + OP_RECORD_AUDIO + "," + OP_PHONE_CALL_MICROPHONE + ","
            + OP_PHONE_CALL_CAMERA + "," + OP_RECEIVE_AMBIENT_TRIGGER_AUDIO;
    private static final long DEFAULT_DISCRETE_HISTORY_CUTOFF = Duration.ofDays(7).toMillis();
    private static final long MAXIMUM_DISCRETE_HISTORY_CUTOFF = Duration.ofDays(30).toMillis();
    private static final long DEFAULT_DISCRETE_HISTORY_QUANTIZATION =
            Duration.ofMinutes(1).toMillis();

    private static long sDiscreteHistoryCutoff;
    private static long sDiscreteHistoryQuantization;
    private static int[] sDiscreteOps;
    private static int sDiscreteFlags;

    private static final String TAG_HISTORY = "h";
    private static final String ATTR_VERSION = "v";
    private static final String ATTR_LARGEST_CHAIN_ID = "lc";
    private static final int CURRENT_VERSION = 1;

    private static final String TAG_UID = "u";
    private static final String ATTR_UID = "ui";

    private static final String TAG_PACKAGE = "p";
    private static final String ATTR_PACKAGE_NAME = "pn";

    private static final String TAG_OP = "o";
    private static final String ATTR_OP_ID = "op";

    private static final String TAG_TAG = "a";
    private static final String ATTR_TAG = "at";

    private static final String TAG_ENTRY = "e";
    private static final String ATTR_NOTE_TIME = "nt";
    private static final String ATTR_NOTE_DURATION = "nd";
    private static final String ATTR_UID_STATE = "us";
    private static final String ATTR_FLAGS = "f";
    private static final String ATTR_ATTRIBUTION_FLAGS = "af";
    private static final String ATTR_CHAIN_ID = "ci";

    private static final int OP_FLAGS_DISCRETE = OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED
            | OP_FLAG_TRUSTED_PROXY;

    // Lock for read/write access to on disk state
    private final Object mOnDiskLock = new Object();

    //Lock for read/write access to in memory state
    private final @NonNull Object mInMemoryLock;

    @GuardedBy("mOnDiskLock")
    private File mDiscreteAccessDir;

    @GuardedBy("mInMemoryLock")
    private DiscreteOps mDiscreteOps;

    @GuardedBy("mOnDiskLock")
    private DiscreteOps mCachedOps = null;

    private boolean mDebugMode = false;

    DiscreteRegistry(Object inMemoryLock) {
        mInMemoryLock = inMemoryLock;
        synchronized (mOnDiskLock) {
            mDiscreteAccessDir = new File(
                    new File(Environment.getDataSystemDirectory(), "appops"),
                    "discrete");
            createDiscreteAccessDirLocked();
            int largestChainId = readLargestChainIdFromDiskLocked();
            synchronized (mInMemoryLock) {
                mDiscreteOps = new DiscreteOps(largestChainId);
            }
        }
    }

    void systemReady() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                AsyncTask.THREAD_POOL_EXECUTOR, (DeviceConfig.Properties p) -> {
                    setDiscreteHistoryParameters(p);
                });
        setDiscreteHistoryParameters(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_PRIVACY));
    }

    private void setDiscreteHistoryParameters(DeviceConfig.Properties p) {
        if (p.getKeyset().contains(PROPERTY_DISCRETE_HISTORY_CUTOFF)) {
            sDiscreteHistoryCutoff = p.getLong(PROPERTY_DISCRETE_HISTORY_CUTOFF,
                    DEFAULT_DISCRETE_HISTORY_CUTOFF);
            if (!Build.IS_DEBUGGABLE && !mDebugMode) {
                sDiscreteHistoryCutoff = min(MAXIMUM_DISCRETE_HISTORY_CUTOFF,
                        sDiscreteHistoryCutoff);
            }
        } else {
            sDiscreteHistoryCutoff = DEFAULT_DISCRETE_HISTORY_CUTOFF;
        }
        if (p.getKeyset().contains(PROPERTY_DISCRETE_HISTORY_QUANTIZATION)) {
            sDiscreteHistoryQuantization = p.getLong(PROPERTY_DISCRETE_HISTORY_QUANTIZATION,
                    DEFAULT_DISCRETE_HISTORY_QUANTIZATION);
            if (!Build.IS_DEBUGGABLE && !mDebugMode) {
                sDiscreteHistoryQuantization = max(DEFAULT_DISCRETE_HISTORY_QUANTIZATION,
                        sDiscreteHistoryQuantization);
            }
        } else {
            sDiscreteHistoryQuantization = DEFAULT_DISCRETE_HISTORY_QUANTIZATION;
        }
        sDiscreteFlags = p.getKeyset().contains(PROPERTY_DISCRETE_FLAGS) ? sDiscreteFlags =
                p.getInt(PROPERTY_DISCRETE_FLAGS, OP_FLAGS_DISCRETE) : OP_FLAGS_DISCRETE;
        sDiscreteOps = p.getKeyset().contains(PROPERTY_DISCRETE_OPS_LIST) ? parseOpsList(
                p.getString(PROPERTY_DISCRETE_OPS_LIST, DEFAULT_DISCRETE_OPS)) : parseOpsList(
                DEFAULT_DISCRETE_OPS);
    }

    void recordDiscreteAccess(int uid, String packageName, int op, @Nullable String attributionTag,
            @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState, long accessTime,
            long accessDuration, @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId) {
        if (!isDiscreteOp(op, flags)) {
            return;
        }
        synchronized (mInMemoryLock) {
            mDiscreteOps.addDiscreteAccess(op, uid, packageName, attributionTag, flags, uidState,
                    accessTime, accessDuration, attributionFlags, attributionChainId);
        }
    }

    void writeAndClearAccessHistory() {
        synchronized (mOnDiskLock) {
            if (mDiscreteAccessDir == null) {
                Slog.d(TAG, "State not saved - persistence not initialized.");
                return;
            }
            DiscreteOps discreteOps;
            synchronized (mInMemoryLock) {
                discreteOps = mDiscreteOps;
                mDiscreteOps = new DiscreteOps(discreteOps.mChainIdOffset);
                mCachedOps = null;
            }
            deleteOldDiscreteHistoryFilesLocked();
            if (!discreteOps.isEmpty()) {
                persistDiscreteOpsLocked(discreteOps);
            }
        }
    }

    void addFilteredDiscreteOpsToHistoricalOps(AppOpsManager.HistoricalOps result,
            long beginTimeMillis, long endTimeMillis,
            @AppOpsManager.HistoricalOpsRequestFilter int filter, int uidFilter,
            @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
            @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter,
            Set<String> attributionExemptPkgs) {
        boolean assembleChains = attributionExemptPkgs != null;
        DiscreteOps discreteOps = getAllDiscreteOps();
        ArrayMap<Integer, AttributionChain> attributionChains = new ArrayMap<>();
        if (assembleChains) {
            attributionChains = createAttributionChains(discreteOps, attributionExemptPkgs);
        }
        beginTimeMillis = max(beginTimeMillis, Instant.now().minus(sDiscreteHistoryCutoff,
                ChronoUnit.MILLIS).toEpochMilli());
        discreteOps.filter(beginTimeMillis, endTimeMillis, filter, uidFilter, packageNameFilter,
                opNamesFilter, attributionTagFilter, flagsFilter, attributionChains);
        discreteOps.applyToHistoricalOps(result, attributionChains);
        return;
    }

    private int readLargestChainIdFromDiskLocked() {
        final File[] files = mDiscreteAccessDir.listFiles();
        if (files != null && files.length > 0) {
            File latestFile = null;
            long latestFileTimestamp = 0;
            for (File f : files) {
                final String fileName = f.getName();
                if (!fileName.endsWith(DISCRETE_HISTORY_FILE_SUFFIX)) {
                    continue;
                }
                long timestamp = Long.valueOf(fileName.substring(0,
                        fileName.length() - DISCRETE_HISTORY_FILE_SUFFIX.length()));
                if (latestFileTimestamp < timestamp) {
                    latestFile = f;
                    latestFileTimestamp = timestamp;
                }
            }
            if (latestFile == null) {
                return 0;
            }
            FileInputStream stream;
            try {
                stream = new FileInputStream(latestFile);
            } catch (FileNotFoundException e) {
                return 0;
            }
            try {
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                XmlUtils.beginDocument(parser, TAG_HISTORY);

                final int largestChainId = parser.getAttributeInt(null, ATTR_LARGEST_CHAIN_ID, 0);
                return largestChainId;
            } catch (Throwable t) {
                return 0;
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        } else {
            return 0;
        }
    }

    private ArrayMap<Integer, AttributionChain> createAttributionChains(
            DiscreteOps discreteOps, Set<String> attributionExemptPkgs) {
        ArrayMap<Integer, AttributionChain> chains = new ArrayMap<>();
        int nUids = discreteOps.mUids.size();
        for (int uidNum = 0; uidNum < nUids; uidNum++) {
            ArrayMap<String, DiscretePackageOps> pkgs = discreteOps.mUids.valueAt(uidNum).mPackages;
            int uid = discreteOps.mUids.keyAt(uidNum);
            int nPackages = pkgs.size();
            for (int pkgNum = 0; pkgNum < nPackages; pkgNum++) {
                ArrayMap<Integer, DiscreteOp> ops = pkgs.valueAt(pkgNum).mPackageOps;
                String pkg = pkgs.keyAt(pkgNum);
                int nOps = ops.size();
                for (int opNum = 0; opNum < nOps; opNum++) {
                    ArrayMap<String, List<DiscreteOpEvent>> attrOps =
                            ops.valueAt(opNum).mAttributedOps;
                    int op = ops.keyAt(opNum);
                    int nAttrOps = attrOps.size();
                    for (int attrOpNum = 0; attrOpNum < nAttrOps; attrOpNum++) {
                        List<DiscreteOpEvent> opEvents = attrOps.valueAt(attrOpNum);
                        String attributionTag = attrOps.keyAt(attrOpNum);
                        int nOpEvents = opEvents.size();
                        for (int opEventNum = 0; opEventNum < nOpEvents; opEventNum++) {
                            DiscreteOpEvent event = opEvents.get(opEventNum);
                            if (event == null
                                    || event.mAttributionChainId == ATTRIBUTION_CHAIN_ID_NONE
                                    || (event.mAttributionFlags & ATTRIBUTION_FLAG_TRUSTED) == 0) {
                                continue;
                            }

                            if (!chains.containsKey(event.mAttributionChainId)) {
                                chains.put(event.mAttributionChainId,
                                        new AttributionChain(attributionExemptPkgs));
                            }
                            chains.get(event.mAttributionChainId)
                                    .addEvent(pkg, uid, attributionTag, op, event);
                        }
                    }
                }
            }
        }
        return chains;
    }

    private void readDiscreteOpsFromDisk(DiscreteOps discreteOps) {
        synchronized (mOnDiskLock) {
            long beginTimeMillis = Instant.now().minus(sDiscreteHistoryCutoff,
                    ChronoUnit.MILLIS).toEpochMilli();

            final File[] files = mDiscreteAccessDir.listFiles();
            if (files != null && files.length > 0) {
                for (File f : files) {
                    final String fileName = f.getName();
                    if (!fileName.endsWith(DISCRETE_HISTORY_FILE_SUFFIX)) {
                        continue;
                    }
                    long timestamp = Long.valueOf(fileName.substring(0,
                            fileName.length() - DISCRETE_HISTORY_FILE_SUFFIX.length()));
                    if (timestamp < beginTimeMillis) {
                        continue;
                    }
                    discreteOps.readFromFile(f, beginTimeMillis);
                }
            }
        }
    }

    void clearHistory() {
        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                mDiscreteOps = new DiscreteOps(0);
            }
            clearOnDiskHistoryLocked();
        }
    }

    void clearHistory(int uid, String packageName) {
        synchronized (mOnDiskLock) {
            DiscreteOps discreteOps;
            synchronized (mInMemoryLock) {
                discreteOps = getAllDiscreteOps();
                clearHistory();
            }
            discreteOps.clearHistory(uid, packageName);
            persistDiscreteOpsLocked(discreteOps);
        }
    }

    void offsetHistory(long offset) {
        synchronized (mOnDiskLock) {
            DiscreteOps discreteOps;
            synchronized (mInMemoryLock) {
                discreteOps = getAllDiscreteOps();
                clearHistory();
            }
            discreteOps.offsetHistory(offset);
            persistDiscreteOpsLocked(discreteOps);
        }
    }

    void dump(@NonNull PrintWriter pw, int uidFilter, @Nullable String packageNameFilter,
            @Nullable String attributionTagFilter,
            @AppOpsManager.HistoricalOpsRequestFilter int filter, int dumpOp,
            @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix,
            int nDiscreteOps) {
        DiscreteOps discreteOps = getAllDiscreteOps();
        String[] opNamesFilter = dumpOp == OP_NONE ? null
                : new String[]{AppOpsManager.opToPublicName(dumpOp)};
        discreteOps.filter(0, Instant.now().toEpochMilli(), filter, uidFilter, packageNameFilter,
                opNamesFilter, attributionTagFilter, OP_FLAGS_ALL, new ArrayMap<>());
        pw.print(prefix);
        pw.print("Largest chain id: ");
        pw.print(mDiscreteOps.mLargestChainId);
        pw.println();
        discreteOps.dump(pw, sdf, date, prefix, nDiscreteOps);
    }

    private void clearOnDiskHistoryLocked() {
        mCachedOps = null;
        FileUtils.deleteContentsAndDir(mDiscreteAccessDir);
        createDiscreteAccessDir();
    }

    private DiscreteOps getAllDiscreteOps() {
        DiscreteOps discreteOps = new DiscreteOps(0);

        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                discreteOps.merge(mDiscreteOps);
            }
            if (mCachedOps == null) {
                mCachedOps = new DiscreteOps(0);
                readDiscreteOpsFromDisk(mCachedOps);
            }
            discreteOps.merge(mCachedOps);
            return discreteOps;
        }
    }

    /**
     * Represents a chain of usages, each attributing its usage to the one before it
     */
    private static final class AttributionChain {
        private static final class OpEvent {
            String mPkgName;
            int mUid;
            String mAttributionTag;
            int mOpCode;
            DiscreteOpEvent mOpEvent;

            OpEvent(String pkgName, int uid, String attributionTag, int opCode,
                    DiscreteOpEvent event) {
                mPkgName = pkgName;
                mUid = uid;
                mAttributionTag = attributionTag;
                mOpCode = opCode;
                mOpEvent = event;
            }

            public boolean matches(String pkgName, int uid, String attributionTag, int opCode,
                    DiscreteOpEvent event) {
                return Objects.equals(pkgName, mPkgName) && mUid == uid
                        && Objects.equals(attributionTag, mAttributionTag) && mOpCode == opCode
                        && mOpEvent.mAttributionChainId == event.mAttributionChainId
                        && mOpEvent.mAttributionFlags == event.mAttributionFlags
                        && mOpEvent.mNoteTime == event.mNoteTime;
            }

            public boolean packageOpEquals(OpEvent other) {
                return Objects.equals(other.mPkgName, mPkgName) && other.mUid == mUid
                        && Objects.equals(other.mAttributionTag, mAttributionTag)
                        && mOpCode == other.mOpCode;
            }

            public boolean equalsExceptDuration(OpEvent other) {
                if (other.mOpEvent.mNoteDuration == mOpEvent.mNoteDuration) {
                    return false;
                }
                return packageOpEquals(other) && mOpEvent.equalsExceptDuration(other.mOpEvent);
            }
        }

        ArrayList<OpEvent> mChain = new ArrayList<>();
        Set<String> mExemptPkgs;
        OpEvent mStartEvent = null;
        OpEvent mLastVisibleEvent = null;

        AttributionChain(Set<String> exemptPkgs) {
            mExemptPkgs = exemptPkgs;
        }

        boolean isComplete() {
            return !mChain.isEmpty() && getStart() != null && isEnd(mChain.get(mChain.size() - 1));
        }

        boolean isStart(String pkgName, int uid, String attributionTag, int op,
                DiscreteOpEvent opEvent) {
            if (mStartEvent == null || opEvent == null) {
                return false;
            }
            return mStartEvent.matches(pkgName, uid, attributionTag, op, opEvent);
        }

        private OpEvent getStart() {
            return mChain.isEmpty() || !isStart(mChain.get(0)) ? null : mChain.get(0);
        }

        private OpEvent getLastVisible() {
            // Search all nodes but the first one, which is the start node
            for (int i = mChain.size() - 1; i > 0; i--) {
                OpEvent event = mChain.get(i);
                if (!mExemptPkgs.contains(event.mPkgName)) {
                    return event;
                }
            }
            return null;
        }

        void addEvent(String pkgName, int uid, String attributionTag, int op,
                DiscreteOpEvent opEvent) {
            OpEvent event = new OpEvent(pkgName, uid, attributionTag, op, opEvent);

            // check if we have a matching event, without duration, replacing duration otherwise
            for (int i = 0; i < mChain.size(); i++) {
                OpEvent item = mChain.get(i);
                if (item.equalsExceptDuration(event)) {
                    if (event.mOpEvent.mNoteDuration != -1) {
                        item.mOpEvent = event.mOpEvent;
                    }
                    return;
                }
            }

            if (mChain.isEmpty() || isEnd(event)) {
                mChain.add(event);
            } else if (isStart(event)) {
                mChain.add(0, event);

            } else {
                for (int i = 0; i < mChain.size(); i++) {
                    OpEvent currEvent = mChain.get(i);
                    if ((!isStart(currEvent)
                            && currEvent.mOpEvent.mNoteTime > event.mOpEvent.mNoteTime)
                            || i == mChain.size() - 1 && isEnd(currEvent)) {
                        mChain.add(i, event);
                        break;
                    } else if (i == mChain.size() - 1) {
                        mChain.add(event);
                        break;
                    }
                }
            }
            mStartEvent = isComplete() ? getStart() : null;
            mLastVisibleEvent = isComplete() ? getLastVisible() : null;
        }

        private boolean isEnd(OpEvent event) {
            return event != null
                    && (event.mOpEvent.mAttributionFlags & ATTRIBUTION_FLAG_ACCESSOR) != 0;
        }

        private boolean isStart(OpEvent event) {
            return event != null
                    && (event.mOpEvent.mAttributionFlags & ATTRIBUTION_FLAG_RECEIVER) != 0;
        }
    }

    private final class DiscreteOps {
        ArrayMap<Integer, DiscreteUidOps> mUids;
        int mChainIdOffset;
        int mLargestChainId;

        DiscreteOps(int chainIdOffset) {
            mUids = new ArrayMap<>();
            mChainIdOffset = chainIdOffset;
            mLargestChainId = chainIdOffset;
        }

        boolean isEmpty() {
            return mUids.isEmpty();
        }

        void merge(DiscreteOps other) {
            mLargestChainId = max(mLargestChainId, other.mLargestChainId);
            int nUids = other.mUids.size();
            for (int i = 0; i < nUids; i++) {
                int uid = other.mUids.keyAt(i);
                DiscreteUidOps uidOps = other.mUids.valueAt(i);
                getOrCreateDiscreteUidOps(uid).merge(uidOps);
            }
        }

        void addDiscreteAccess(int op, int uid, @NonNull String packageName,
                @Nullable String attributionTag, @AppOpsManager.OpFlags int flags,
                @AppOpsManager.UidState int uidState, long accessTime, long accessDuration,
                @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId) {
            int offsetChainId = attributionChainId;
            if (attributionChainId != ATTRIBUTION_CHAIN_ID_NONE) {
                offsetChainId = attributionChainId + mChainIdOffset;
                if (offsetChainId > mLargestChainId) {
                    mLargestChainId = offsetChainId;
                } else if (offsetChainId < 0) {
                    // handle overflow
                    offsetChainId = 0;
                    mLargestChainId = 0;
                    mChainIdOffset = -1 * attributionChainId;
                }
            }
            getOrCreateDiscreteUidOps(uid).addDiscreteAccess(op, packageName, attributionTag, flags,
                    uidState, accessTime, accessDuration, attributionFlags, offsetChainId);
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter, int uidFilter,
                @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
                @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter,
                ArrayMap<Integer, AttributionChain> attributionChains) {
            if ((filter & FILTER_BY_UID) != 0) {
                ArrayMap<Integer, DiscreteUidOps> uids = new ArrayMap<>();
                uids.put(uidFilter, getOrCreateDiscreteUidOps(uidFilter));
                mUids = uids;
            }
            int nUids = mUids.size();
            for (int i = nUids - 1; i >= 0; i--) {
                mUids.valueAt(i).filter(beginTimeMillis, endTimeMillis, filter, packageNameFilter,
                        opNamesFilter, attributionTagFilter, flagsFilter, mUids.keyAt(i),
                        attributionChains);
                if (mUids.valueAt(i).isEmpty()) {
                    mUids.removeAt(i);
                }
            }
        }

        private void offsetHistory(long offset) {
            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                mUids.valueAt(i).offsetHistory(offset);
            }
        }

        private void clearHistory(int uid, String packageName) {
            if (mUids.containsKey(uid)) {
                mUids.get(uid).clearPackage(packageName);
                if (mUids.get(uid).isEmpty()) {
                    mUids.remove(uid);
                }
            }
        }

        private void applyToHistoricalOps(AppOpsManager.HistoricalOps result,
                ArrayMap<Integer, AttributionChain> attributionChains) {
            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                mUids.valueAt(i).applyToHistory(result, mUids.keyAt(i), attributionChains);
            }
        }

        private void writeToStream(FileOutputStream stream) throws Exception {
            TypedXmlSerializer out = Xml.resolveSerializer(stream);

            out.startDocument(null, true);
            out.startTag(null, TAG_HISTORY);
            out.attributeInt(null, ATTR_VERSION, CURRENT_VERSION);
            out.attributeInt(null, ATTR_LARGEST_CHAIN_ID, mLargestChainId);

            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                out.startTag(null, TAG_UID);
                out.attributeInt(null, ATTR_UID, mUids.keyAt(i));
                mUids.valueAt(i).serialize(out);
                out.endTag(null, TAG_UID);
            }
            out.endTag(null, TAG_HISTORY);
            out.endDocument();
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                pw.print(prefix);
                pw.print("Uid: ");
                pw.print(mUids.keyAt(i));
                pw.println();
                mUids.valueAt(i).dump(pw, sdf, date, prefix + "  ", nDiscreteOps);
            }
        }

        private DiscreteUidOps getOrCreateDiscreteUidOps(int uid) {
            DiscreteUidOps result = mUids.get(uid);
            if (result == null) {
                result = new DiscreteUidOps();
                mUids.put(uid, result);
            }
            return result;
        }

        private void readFromFile(File f, long beginTimeMillis) {
            FileInputStream stream;
            try {
                stream = new FileInputStream(f);
            } catch (FileNotFoundException e) {
                return;
            }
            try {
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                XmlUtils.beginDocument(parser, TAG_HISTORY);

                // We haven't released version 1 and have more detailed
                // accounting - just nuke the current state
                final int version = parser.getAttributeInt(null, ATTR_VERSION);
                if (version != CURRENT_VERSION) {
                    throw new IllegalStateException("Dropping unsupported discrete history " + f);
                }
                int depth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    if (TAG_UID.equals(parser.getName())) {
                        int uid = parser.getAttributeInt(null, ATTR_UID, -1);
                        getOrCreateDiscreteUidOps(uid).deserialize(parser, beginTimeMillis);
                    }
                }
            } catch (Throwable t) {
                Slog.e(TAG, "Failed to read file " + f.getName() + " " + t.getMessage() + " "
                        + Arrays.toString(t.getStackTrace()));
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void createDiscreteAccessDir() {
        if (!mDiscreteAccessDir.exists()) {
            if (!mDiscreteAccessDir.mkdirs()) {
                Slog.e(TAG, "Failed to create DiscreteRegistry directory");
            }
            FileUtils.setPermissions(mDiscreteAccessDir.getPath(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
        }
    }

    private void persistDiscreteOpsLocked(DiscreteOps discreteOps) {
        long currentTimeStamp = Instant.now().toEpochMilli();
        final AtomicFile file = new AtomicFile(new File(mDiscreteAccessDir,
                currentTimeStamp + DISCRETE_HISTORY_FILE_SUFFIX));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            discreteOps.writeToStream(stream);
            file.finishWrite(stream);
        } catch (Throwable t) {
            Slog.e(TAG,
                    "Error writing timeline state: " + t.getMessage() + " "
                            + Arrays.toString(t.getStackTrace()));
            if (stream != null) {
                file.failWrite(stream);
            }
        }
    }

    private void deleteOldDiscreteHistoryFilesLocked() {
        final File[] files = mDiscreteAccessDir.listFiles();
        if (files != null && files.length > 0) {
            for (File f : files) {
                final String fileName = f.getName();
                if (!fileName.endsWith(DISCRETE_HISTORY_FILE_SUFFIX)) {
                    continue;
                }
                try {
                    long timestamp = Long.valueOf(fileName.substring(0,
                            fileName.length() - DISCRETE_HISTORY_FILE_SUFFIX.length()));
                    if (Instant.now().minus(sDiscreteHistoryCutoff,
                            ChronoUnit.MILLIS).toEpochMilli() > timestamp) {
                        f.delete();
                        Slog.e(TAG, "Deleting file " + fileName);

                    }
                } catch (Throwable t) {
                    Slog.e(TAG, "Error while cleaning timeline files: " + t.getMessage() + " "
                            + t.getStackTrace());
                }
            }
        }
    }

    private void createDiscreteAccessDirLocked() {
        if (!mDiscreteAccessDir.exists()) {
            if (!mDiscreteAccessDir.mkdirs()) {
                Slog.e(TAG, "Failed to create DiscreteRegistry directory");
            }
            FileUtils.setPermissions(mDiscreteAccessDir.getPath(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
        }
    }

    private final class DiscreteUidOps {
        ArrayMap<String, DiscretePackageOps> mPackages;

        DiscreteUidOps() {
            mPackages = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mPackages.isEmpty();
        }

        void merge(DiscreteUidOps other) {
            int nPackages = other.mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                String packageName = other.mPackages.keyAt(i);
                DiscretePackageOps p = other.mPackages.valueAt(i);
                getOrCreateDiscretePackageOps(packageName).merge(p);
            }
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter,
                @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
                @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter,
                int currentUid, ArrayMap<Integer, AttributionChain> attributionChains) {
            if ((filter & FILTER_BY_PACKAGE_NAME) != 0) {
                ArrayMap<String, DiscretePackageOps> packages = new ArrayMap<>();
                packages.put(packageNameFilter, getOrCreateDiscretePackageOps(packageNameFilter));
                mPackages = packages;
            }
            int nPackages = mPackages.size();
            for (int i = nPackages - 1; i >= 0; i--) {
                mPackages.valueAt(i).filter(beginTimeMillis, endTimeMillis, filter, opNamesFilter,
                        attributionTagFilter, flagsFilter, currentUid, mPackages.keyAt(i),
                        attributionChains);
                if (mPackages.valueAt(i).isEmpty()) {
                    mPackages.removeAt(i);
                }
            }
        }

        private void offsetHistory(long offset) {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                mPackages.valueAt(i).offsetHistory(offset);
            }
        }

        private void clearPackage(String packageName) {
            mPackages.remove(packageName);
        }

        void addDiscreteAccess(int op, @NonNull String packageName, @Nullable String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState,
                long accessTime, long accessDuration,
                @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId) {
            getOrCreateDiscretePackageOps(packageName).addDiscreteAccess(op, attributionTag, flags,
                    uidState, accessTime, accessDuration, attributionFlags, attributionChainId);
        }

        private DiscretePackageOps getOrCreateDiscretePackageOps(String packageName) {
            DiscretePackageOps result = mPackages.get(packageName);
            if (result == null) {
                result = new DiscretePackageOps();
                mPackages.put(packageName, result);
            }
            return result;
        }

        private void applyToHistory(AppOpsManager.HistoricalOps result, int uid,
                @NonNull ArrayMap<Integer, AttributionChain> attributionChains) {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                mPackages.valueAt(i).applyToHistory(result, uid, mPackages.keyAt(i),
                        attributionChains);
            }
        }

        void serialize(TypedXmlSerializer out) throws Exception {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATTR_PACKAGE_NAME, mPackages.keyAt(i));
                mPackages.valueAt(i).serialize(out);
                out.endTag(null, TAG_PACKAGE);
            }
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                pw.print(prefix);
                pw.print("Package: ");
                pw.print(mPackages.keyAt(i));
                pw.println();
                mPackages.valueAt(i).dump(pw, sdf, date, prefix + "  ", nDiscreteOps);
            }
        }

        void deserialize(TypedXmlPullParser parser, long beginTimeMillis) throws Exception {
            int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_PACKAGE.equals(parser.getName())) {
                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    getOrCreateDiscretePackageOps(packageName).deserialize(parser, beginTimeMillis);
                }
            }
        }
    }

    private final class DiscretePackageOps {
        ArrayMap<Integer, DiscreteOp> mPackageOps;

        DiscretePackageOps() {
            mPackageOps = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mPackageOps.isEmpty();
        }

        void addDiscreteAccess(int op, @Nullable String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState,
                long accessTime, long accessDuration,
                @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId) {
            getOrCreateDiscreteOp(op).addDiscreteAccess(attributionTag, flags, uidState, accessTime,
                    accessDuration, attributionFlags, attributionChainId);
        }

        void merge(DiscretePackageOps other) {
            int nOps = other.mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                int opId = other.mPackageOps.keyAt(i);
                DiscreteOp op = other.mPackageOps.valueAt(i);
                getOrCreateDiscreteOp(opId).merge(op);
            }
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter,
                @Nullable String[] opNamesFilter, @Nullable String attributionTagFilter,
                @AppOpsManager.OpFlags int flagsFilter, int currentUid, String currentPkgName,
                ArrayMap<Integer, AttributionChain> attributionChains) {
            int nOps = mPackageOps.size();
            for (int i = nOps - 1; i >= 0; i--) {
                int opId = mPackageOps.keyAt(i);
                if ((filter & FILTER_BY_OP_NAMES) != 0 && !ArrayUtils.contains(opNamesFilter,
                        AppOpsManager.opToPublicName(opId))) {
                    mPackageOps.removeAt(i);
                    continue;
                }
                mPackageOps.valueAt(i).filter(beginTimeMillis, endTimeMillis, filter,
                        attributionTagFilter, flagsFilter, currentUid, currentPkgName,
                        mPackageOps.keyAt(i), attributionChains);
                if (mPackageOps.valueAt(i).isEmpty()) {
                    mPackageOps.removeAt(i);
                }
            }
        }

        private void offsetHistory(long offset) {
            int nOps = mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                mPackageOps.valueAt(i).offsetHistory(offset);
            }
        }

        private DiscreteOp getOrCreateDiscreteOp(int op) {
            DiscreteOp result = mPackageOps.get(op);
            if (result == null) {
                result = new DiscreteOp();
                mPackageOps.put(op, result);
            }
            return result;
        }

        private void applyToHistory(AppOpsManager.HistoricalOps result, int uid,
                @NonNull String packageName,
                @NonNull ArrayMap<Integer, AttributionChain> attributionChains) {
            int nPackageOps = mPackageOps.size();
            for (int i = 0; i < nPackageOps; i++) {
                mPackageOps.valueAt(i).applyToHistory(result, uid, packageName,
                        mPackageOps.keyAt(i), attributionChains);
            }
        }

        void serialize(TypedXmlSerializer out) throws Exception {
            int nOps = mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                out.startTag(null, TAG_OP);
                out.attributeInt(null, ATTR_OP_ID, mPackageOps.keyAt(i));
                mPackageOps.valueAt(i).serialize(out);
                out.endTag(null, TAG_OP);
            }
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nOps = mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                pw.print(prefix);
                pw.print(AppOpsManager.opToName(mPackageOps.keyAt(i)));
                pw.println();
                mPackageOps.valueAt(i).dump(pw, sdf, date, prefix + "  ", nDiscreteOps);
            }
        }

        void deserialize(TypedXmlPullParser parser, long beginTimeMillis) throws Exception {
            int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_OP.equals(parser.getName())) {
                    int op = parser.getAttributeInt(null, ATTR_OP_ID);
                    getOrCreateDiscreteOp(op).deserialize(parser, beginTimeMillis);
                }
            }
        }
    }

    private final class DiscreteOp {
        ArrayMap<String, List<DiscreteOpEvent>> mAttributedOps;

        DiscreteOp() {
            mAttributedOps = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mAttributedOps.isEmpty();
        }

        void merge(DiscreteOp other) {
            int nTags = other.mAttributedOps.size();
            for (int i = 0; i < nTags; i++) {
                String tag = other.mAttributedOps.keyAt(i);
                List<DiscreteOpEvent> otherEvents = other.mAttributedOps.valueAt(i);
                List<DiscreteOpEvent> events = getOrCreateDiscreteOpEventsList(tag);
                mAttributedOps.put(tag, stableListMerge(events, otherEvents));
            }
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter,
                @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter,
                int currentUid, String currentPkgName, int currentOp,
                ArrayMap<Integer, AttributionChain> attributionChains) {
            if ((filter & FILTER_BY_ATTRIBUTION_TAG) != 0) {
                ArrayMap<String, List<DiscreteOpEvent>> attributedOps = new ArrayMap<>();
                attributedOps.put(attributionTagFilter,
                        getOrCreateDiscreteOpEventsList(attributionTagFilter));
                mAttributedOps = attributedOps;
            }

            int nTags = mAttributedOps.size();
            for (int i = nTags - 1; i >= 0; i--) {
                String tag = mAttributedOps.keyAt(i);
                List<DiscreteOpEvent> list = mAttributedOps.valueAt(i);
                list = filterEventsList(list, beginTimeMillis, endTimeMillis, flagsFilter,
                        currentUid, currentPkgName, currentOp, mAttributedOps.keyAt(i),
                        attributionChains);
                mAttributedOps.put(tag, list);
                if (list.size() == 0) {
                    mAttributedOps.removeAt(i);
                }
            }
        }

        private void offsetHistory(long offset) {
            int nTags = mAttributedOps.size();
            for (int i = 0; i < nTags; i++) {
                List<DiscreteOpEvent> list = mAttributedOps.valueAt(i);

                int n = list.size();
                for (int j = 0; j < n; j++) {
                    DiscreteOpEvent event = list.get(j);
                    list.set(j, new DiscreteOpEvent(event.mNoteTime - offset, event.mNoteDuration,
                            event.mUidState, event.mOpFlag, event.mAttributionFlags,
                            event.mAttributionChainId));
                }
            }
        }

        void addDiscreteAccess(@Nullable String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState,
                long accessTime, long accessDuration,
                @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId) {
            List<DiscreteOpEvent> attributedOps = getOrCreateDiscreteOpEventsList(
                    attributionTag);

            int nAttributedOps = attributedOps.size();
            int i = nAttributedOps;
            for (; i > 0; i--) {
                DiscreteOpEvent previousOp = attributedOps.get(i - 1);
                if (discretizeTimeStamp(previousOp.mNoteTime) < discretizeTimeStamp(accessTime)) {
                    break;
                }
                if (previousOp.mOpFlag == flags && previousOp.mUidState == uidState
                        && previousOp.mAttributionFlags == attributionFlags
                        && previousOp.mAttributionChainId == attributionChainId) {
                    if (discretizeDuration(accessDuration) != discretizeDuration(
                            previousOp.mNoteDuration)) {
                        break;
                    } else {
                        return;
                    }
                }
            }
            attributedOps.add(i, new DiscreteOpEvent(accessTime, accessDuration, uidState, flags,
                    attributionFlags, attributionChainId));
        }

        private List<DiscreteOpEvent> getOrCreateDiscreteOpEventsList(String attributionTag) {
            List<DiscreteOpEvent> result = mAttributedOps.get(attributionTag);
            if (result == null) {
                result = new ArrayList<>();
                mAttributedOps.put(attributionTag, result);
            }
            return result;
        }

        private void applyToHistory(AppOpsManager.HistoricalOps result, int uid,
                @NonNull String packageName, int op,
                @NonNull ArrayMap<Integer, AttributionChain> attributionChains) {
            int nOps = mAttributedOps.size();
            for (int i = 0; i < nOps; i++) {
                String tag = mAttributedOps.keyAt(i);
                List<DiscreteOpEvent> events = mAttributedOps.valueAt(i);
                int nEvents = events.size();
                for (int j = 0; j < nEvents; j++) {
                    DiscreteOpEvent event = events.get(j);
                    AppOpsManager.OpEventProxyInfo proxy = null;
                    if (event.mAttributionChainId != ATTRIBUTION_CHAIN_ID_NONE
                            && attributionChains != null) {
                        AttributionChain chain = attributionChains.get(event.mAttributionChainId);
                        if (chain != null && chain.isComplete()
                                && chain.isStart(packageName, uid, tag, op, event)
                                && chain.mLastVisibleEvent != null) {
                            AttributionChain.OpEvent proxyEvent = chain.mLastVisibleEvent;
                            proxy = new AppOpsManager.OpEventProxyInfo(proxyEvent.mUid,
                                    proxyEvent.mPkgName, proxyEvent.mAttributionTag);
                        }
                    }
                    result.addDiscreteAccess(op, uid, packageName, tag, event.mUidState,
                            event.mOpFlag, discretizeTimeStamp(event.mNoteTime),
                            discretizeDuration(event.mNoteDuration), proxy);
                }
            }
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nAttributions = mAttributedOps.size();
            for (int i = 0; i < nAttributions; i++) {
                pw.print(prefix);
                pw.print("Attribution: ");
                pw.print(mAttributedOps.keyAt(i));
                pw.println();
                List<DiscreteOpEvent> ops = mAttributedOps.valueAt(i);
                int nOps = ops.size();
                int first = nDiscreteOps < 1 ? 0 : max(0, nOps - nDiscreteOps);
                for (int j = first; j < nOps; j++) {
                    ops.get(j).dump(pw, sdf, date, prefix + "  ");

                }
            }
        }

        void serialize(TypedXmlSerializer out) throws Exception {
            int nAttributions = mAttributedOps.size();
            for (int i = 0; i < nAttributions; i++) {
                out.startTag(null, TAG_TAG);
                String tag = mAttributedOps.keyAt(i);
                if (tag != null) {
                    out.attribute(null, ATTR_TAG, mAttributedOps.keyAt(i));
                }
                List<DiscreteOpEvent> ops = mAttributedOps.valueAt(i);
                int nOps = ops.size();
                for (int j = 0; j < nOps; j++) {
                    out.startTag(null, TAG_ENTRY);
                    ops.get(j).serialize(out);
                    out.endTag(null, TAG_ENTRY);
                }
                out.endTag(null, TAG_TAG);
            }
        }

        void deserialize(TypedXmlPullParser parser, long beginTimeMillis) throws Exception {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (TAG_TAG.equals(parser.getName())) {
                    String attributionTag = parser.getAttributeValue(null, ATTR_TAG);
                    List<DiscreteOpEvent> events = getOrCreateDiscreteOpEventsList(
                            attributionTag);
                    int innerDepth = parser.getDepth();
                    while (XmlUtils.nextElementWithin(parser, innerDepth)) {
                        if (TAG_ENTRY.equals(parser.getName())) {
                            long noteTime = parser.getAttributeLong(null, ATTR_NOTE_TIME);
                            long noteDuration = parser.getAttributeLong(null, ATTR_NOTE_DURATION,
                                    -1);
                            int uidState = parser.getAttributeInt(null, ATTR_UID_STATE);
                            int opFlags = parser.getAttributeInt(null, ATTR_FLAGS);
                            int attributionFlags = parser.getAttributeInt(null,
                                    ATTR_ATTRIBUTION_FLAGS, AppOpsManager.ATTRIBUTION_FLAGS_NONE);
                            int attributionChainId = parser.getAttributeInt(null, ATTR_CHAIN_ID,
                                    AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
                            if (noteTime + noteDuration < beginTimeMillis) {
                                continue;
                            }
                            DiscreteOpEvent event = new DiscreteOpEvent(noteTime, noteDuration,
                                    uidState, opFlags, attributionFlags, attributionChainId);
                            events.add(event);
                        }
                    }
                    Collections.sort(events, (a, b) -> a.mNoteTime < b.mNoteTime ? -1
                            : (a.mNoteTime == b.mNoteTime ? 0 : 1));
                }
            }
        }
    }

    private final class DiscreteOpEvent {
        final long mNoteTime;
        final long mNoteDuration;
        final @AppOpsManager.UidState int mUidState;
        final @AppOpsManager.OpFlags int mOpFlag;
        final @AppOpsManager.AttributionFlags int mAttributionFlags;
        final int mAttributionChainId;

        DiscreteOpEvent(long noteTime, long noteDuration, @AppOpsManager.UidState int uidState,
                @AppOpsManager.OpFlags int opFlag,
                @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId) {
            mNoteTime = noteTime;
            mNoteDuration = noteDuration;
            mUidState = uidState;
            mOpFlag = opFlag;
            mAttributionFlags = attributionFlags;
            mAttributionChainId = attributionChainId;
        }

        public boolean equalsExceptDuration(DiscreteOpEvent o) {
            return mNoteTime == o.mNoteTime && mUidState == o.mUidState && mOpFlag == o.mOpFlag
                    && mAttributionFlags == o.mAttributionFlags
                    && mAttributionChainId == o.mAttributionChainId;

        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix) {
            pw.print(prefix);
            pw.print("Access [");
            pw.print(getUidStateName(mUidState));
            pw.print("-");
            pw.print(flagsToString(mOpFlag));
            pw.print("] at ");
            date.setTime(discretizeTimeStamp(mNoteTime));
            pw.print(sdf.format(date));
            if (mNoteDuration != -1) {
                pw.print(" for ");
                pw.print(discretizeDuration(mNoteDuration));
                pw.print(" milliseconds ");
            }
            if (mAttributionFlags != AppOpsManager.ATTRIBUTION_FLAGS_NONE) {
                pw.print(" attribution flags=");
                pw.print(mAttributionFlags);
                pw.print(" with chainId=");
                pw.print(mAttributionChainId);
            }
            pw.println();
        }

        private void serialize(TypedXmlSerializer out) throws Exception {
            out.attributeLong(null, ATTR_NOTE_TIME, mNoteTime);
            if (mNoteDuration != -1) {
                out.attributeLong(null, ATTR_NOTE_DURATION, mNoteDuration);
            }
            if (mAttributionFlags != AppOpsManager.ATTRIBUTION_FLAGS_NONE) {
                out.attributeInt(null, ATTR_ATTRIBUTION_FLAGS, mAttributionFlags);
            }
            if (mAttributionChainId != AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE) {
                out.attributeInt(null, ATTR_CHAIN_ID, mAttributionChainId);
            }
            out.attributeInt(null, ATTR_UID_STATE, mUidState);
            out.attributeInt(null, ATTR_FLAGS, mOpFlag);
        }
    }

    private static int[] parseOpsList(String opsList) {
        String[] strArr;
        if (opsList.isEmpty()) {
            strArr = new String[0];
        } else {
            strArr = opsList.split(",");
        }
        int nOps = strArr.length;
        int[] result = new int[nOps];
        try {
            for (int i = 0; i < nOps; i++) {
                result[i] = Integer.parseInt(strArr[i]);
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse Discrete ops list: " + e.getMessage());
            return parseOpsList(DEFAULT_DISCRETE_OPS);
        }
        return result;
    }

    private static List<DiscreteOpEvent> stableListMerge(List<DiscreteOpEvent> a,
            List<DiscreteOpEvent> b) {
        int nA = a.size();
        int nB = b.size();
        int i = 0;
        int k = 0;
        List<DiscreteOpEvent> result = new ArrayList<>(nA + nB);
        while (i < nA || k < nB) {
            if (i == nA) {
                result.add(b.get(k++));
            } else if (k == nB) {
                result.add(a.get(i++));
            } else if (a.get(i).mNoteTime < b.get(k).mNoteTime) {
                result.add(a.get(i++));
            } else {
                result.add(b.get(k++));
            }
        }
        return result;
    }

    private static List<DiscreteOpEvent> filterEventsList(List<DiscreteOpEvent> list,
            long beginTimeMillis, long endTimeMillis, @AppOpsManager.OpFlags int flagsFilter,
            int currentUid, String currentPackageName, int currentOp, String currentAttrTag,
            ArrayMap<Integer, AttributionChain> attributionChains) {
        int n = list.size();
        List<DiscreteOpEvent> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            DiscreteOpEvent event = list.get(i);
            AttributionChain chain = attributionChains.get(event.mAttributionChainId);
            // If we have an attribution chain, and this event isn't the beginning node, remove it
            if (chain != null && !chain.isStart(currentPackageName, currentUid, currentAttrTag,
                    currentOp, event) && chain.isComplete()
                    && event.mAttributionChainId != ATTRIBUTION_CHAIN_ID_NONE) {
                continue;
            }
            if ((event.mOpFlag & flagsFilter) != 0
                    && event.mNoteTime + event.mNoteDuration > beginTimeMillis
                    && event.mNoteTime < endTimeMillis) {
                result.add(event);
            }
        }
        return result;
    }

    private static boolean isDiscreteOp(int op, @AppOpsManager.OpFlags int flags) {
        if (!ArrayUtils.contains(sDiscreteOps, op)) {
            return false;
        }
        if ((flags & (sDiscreteFlags)) == 0) {
            return false;
        }
        return true;
    }

    private static long discretizeTimeStamp(long timeStamp) {
        return timeStamp / sDiscreteHistoryQuantization * sDiscreteHistoryQuantization;

    }

    private static long discretizeDuration(long duration) {
        return duration == -1 ? -1 : (duration + sDiscreteHistoryQuantization - 1)
                        / sDiscreteHistoryQuantization * sDiscreteHistoryQuantization;
    }

    void setDebugMode(boolean debugMode) {
        this.mDebugMode = debugMode;
    }
}

