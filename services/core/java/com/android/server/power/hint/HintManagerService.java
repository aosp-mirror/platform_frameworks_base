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

package com.android.server.power.hint;

import static android.os.Flags.adpfUseFmqChannel;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.power.hint.Flags.adpfSessionTag;
import static com.android.server.power.hint.Flags.powerhintThreadCleanup;
import static com.android.server.power.hint.Flags.resetOnForkEnabled;

import android.adpf.ISessionManager;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.StatsManager;
import android.app.UidObserver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.power.ChannelConfig;
import android.hardware.power.CpuHeadroomParams;
import android.hardware.power.CpuHeadroomResult;
import android.hardware.power.GpuHeadroomParams;
import android.hardware.power.GpuHeadroomResult;
import android.hardware.power.IPower;
import android.hardware.power.SessionConfig;
import android.hardware.power.SessionTag;
import android.hardware.power.SupportInfo;
import android.hardware.power.WorkDuration;
import android.os.Binder;
import android.os.CpuHeadroomParamsInternal;
import android.os.GpuHeadroomParamsInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHintManager;
import android.os.IHintSession;
import android.os.Looper;
import android.os.Message;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SessionCreationConfig;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.power.hint.HintManagerService.AppHintSession.SessionModes;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** An hint service implementation that runs in System Server process. */
public final class HintManagerService extends SystemService {
    private static final String TAG = "HintManagerService";
    private static final boolean DEBUG = false;

    private static final int EVENT_CLEAN_UP_UID = 3;
    @VisibleForTesting  static final int CLEAN_UP_UID_DELAY_MILLIS = 1000;
    // The minimum interval between the headroom calls as rate limiting.
    private static final int DEFAULT_GPU_HEADROOM_INTERVAL_MILLIS = 1000;
    private static final int DEFAULT_CPU_HEADROOM_INTERVAL_MILLIS = 1000;


    @VisibleForTesting final long mHintSessionPreferredRate;

    @VisibleForTesting static final int MAX_GRAPHICS_PIPELINE_THREADS_COUNT = 5;

    // Multi-level map storing all active AppHintSessions.
    // First level is keyed by the UID of the client process creating the session.
    // Second level is keyed by an IBinder passed from client process. This is used to observe
    // when the process exits. The client generally uses the same IBinder object across multiple
    // sessions, so the value is a set of AppHintSessions.
    @GuardedBy("mLock")
    private final ArrayMap<Integer, ArrayMap<IBinder, ArraySet<AppHintSession>>> mActiveSessions;

    // Multi-level map storing all the channel binder token death listeners.
    // First level is keyed by the UID of the client process owning the channel.
    // Second level is the tgid of the process, which will often just be size one.
    // Each channel is unique per (tgid, uid) pair, so this map associates each pair with an
    // object that listens for the death notification of the binder token that was provided by
    // that client when it created the channel, so we can detect when the client process dies.
    @GuardedBy("mChannelMapLock")
    private ArrayMap<Integer, TreeMap<Integer, ChannelItem>> mChannelMap;

    /*
     * Multi-level map storing the session statistics since last pull from StatsD.
     * The first level is keyed by the UID of the process owning the session.
     * The second level is keyed by the tag of the session. The point of separating different
     * tags is that since different categories (e.g. HWUI vs APP) of the sessions may have different
     * behaviors.
     */
    @GuardedBy("mSessionSnapshotMapLock")
    private ArrayMap<Integer, ArrayMap<Integer, AppHintSessionSnapshot>> mSessionSnapshotMap;

    /*
     * App UID to Thread mapping.
     * Thread is a sub class bookkeeping TID, thread mode (especially graphics pipeline mode)
     * This is to bookkeep and track the thread usage.
     */
    @GuardedBy("mThreadsUsageObject")
    private ArrayMap<Integer, ArraySet<ThreadUsageTracker>> mThreadsUsageMap;

    /** Lock to protect mActiveSessions and the UidObserver. */
    private final Object mLock = new Object();

    /** Lock to protect mChannelMap. */
    private final Object mChannelMapLock = new Object();

    /*
     * Lock to protect mSessionSnapshotMap.
     * Nested acquisition of mSessionSnapshotMapLock and mLock should be avoided.
     * We should grab these separately.
     * When we need to have nested acquisitions, we should always follow the order of acquiring
     * mSessionSnapshotMapLock first then mLock.
     */
    private final Object mSessionSnapshotMapLock = new Object();

    /** Lock to protect mThreadsUsageMap. */
    private final Object mThreadsUsageObject = new Object();

    @GuardedBy("mNonIsolatedTidsLock")
    private final Map<Integer, Set<Long>> mNonIsolatedTids;

    private final Object mNonIsolatedTidsLock = new Object();

    @VisibleForTesting final MyUidObserver mUidObserver;

    private final NativeWrapper mNativeWrapper;
    private final CleanUpHandler mCleanUpHandler;

    private final ActivityManagerInternal mAmInternal;

    private final Context mContext;

    private AtomicBoolean mConfigCreationSupport = new AtomicBoolean(true);

    private final IPower mPowerHal;
    private int mPowerHalVersion;
    private SupportInfo mSupportInfo = null;
    private final PackageManager mPackageManager;

    private boolean mUsesFmq;

    private static final String PROPERTY_SF_ENABLE_CPU_HINT = "debug.sf.enable_adpf_cpu_hint";
    private static final String PROPERTY_HWUI_ENABLE_HINT_MANAGER = "debug.hwui.use_hint_manager";
    private static final String PROPERTY_USE_HAL_HEADROOMS = "persist.hms.use_hal_headrooms";
    private static final String PROPERTY_CHECK_HEADROOM_TID = "persist.hms.check_headroom_tid";

    private Boolean mFMQUsesIntegratedEventFlag = false;

    private final Object mCpuHeadroomLock = new Object();

    private ISessionManager mSessionManager;

    // this cache tracks the expiration time of the items and performs cleanup on lookup
    private static class HeadroomCache<K, V> {
        final List<HeadroomCacheItem> mItemList;
        final Map<K, HeadroomCacheItem> mKeyItemMap;
        final long mItemExpDurationMillis;

        class HeadroomCacheItem {
            long mExpTime;
            K mKey;
            V mValue;

            HeadroomCacheItem(K k, V v) {
                mExpTime = System.currentTimeMillis() + mItemExpDurationMillis;
                mKey = k;
                mValue = v;
            }

            boolean isExpired() {
                return mExpTime <= System.currentTimeMillis();
            }
        }

        void add(K key, V value) {
            if (mKeyItemMap.containsKey(key)) {
                final HeadroomCacheItem item = mKeyItemMap.get(key);
                mItemList.remove(item);
            }
            final HeadroomCacheItem item = new HeadroomCacheItem(key, value);
            mItemList.add(item);
            mKeyItemMap.put(key, item);
        }

        V get(K key) {
            while (!mItemList.isEmpty() && mItemList.getFirst().isExpired()) {
                mKeyItemMap.remove(mItemList.removeFirst().mKey);
            }
            final HeadroomCacheItem item = mKeyItemMap.get(key);
            if (item == null) {
                return null;
            }
            return item.mValue;
        }

        HeadroomCache(int size, long expDurationMillis) {
            mItemList = new LinkedList<>();
            mKeyItemMap = new ArrayMap<>(size);
            mItemExpDurationMillis = expDurationMillis;
        }
    }

    @GuardedBy("mCpuHeadroomLock")
    private final HeadroomCache<CpuHeadroomParams, CpuHeadroomResult> mCpuHeadroomCache;

    private final Object mGpuHeadroomLock = new Object();

    @GuardedBy("mGpuHeadroomLock")
    private final HeadroomCache<GpuHeadroomParams, GpuHeadroomResult> mGpuHeadroomCache;

    // these are set to default values in CpuHeadroomParamsInternal and GpuHeadroomParamsInternal
    private final int mDefaultCpuHeadroomCalculationWindowMillis;
    private final int mDefaultGpuHeadroomCalculationWindowMillis;

    @VisibleForTesting
    final IHintManager.Stub mService = new BinderService();

    public HintManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    HintManagerService(Context context, Injector injector) {
        super(context);
        mContext = context;
        if (powerhintThreadCleanup()) {
            mCleanUpHandler = new CleanUpHandler(createCleanUpThread().getLooper());
            mNonIsolatedTids = new HashMap<>();
        } else {
            mCleanUpHandler = null;
            mNonIsolatedTids = null;
        }
        if (adpfSessionTag()) {
            mPackageManager = mContext.getPackageManager();
        } else {
            mPackageManager = null;
        }
        mActiveSessions = new ArrayMap<>();
        mChannelMap = new ArrayMap<>();
        mSessionSnapshotMap = new ArrayMap<>();
        mThreadsUsageMap = new ArrayMap<>();
        mNativeWrapper = injector.createNativeWrapper();
        mNativeWrapper.halInit();
        mHintSessionPreferredRate = mNativeWrapper.halGetHintSessionPreferredRate();
        mUidObserver = new MyUidObserver();
        mAmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mPowerHal = injector.createIPower();
        mPowerHalVersion = 0;
        mUsesFmq = false;
        if (mPowerHal != null) {
            mSupportInfo = getSupportInfo();
        }
        mDefaultCpuHeadroomCalculationWindowMillis =
                new CpuHeadroomParamsInternal().calculationWindowMillis;
        mDefaultGpuHeadroomCalculationWindowMillis =
                new GpuHeadroomParamsInternal().calculationWindowMillis;
        if (mSupportInfo.headroom.isCpuSupported) {
            mCpuHeadroomCache = new HeadroomCache<>(2, mSupportInfo.headroom.cpuMinIntervalMillis);
        } else {
            mCpuHeadroomCache = null;
        }
        if (mSupportInfo.headroom.isGpuSupported) {
            mGpuHeadroomCache = new HeadroomCache<>(2, mSupportInfo.headroom.gpuMinIntervalMillis);
        } else {
            mGpuHeadroomCache = null;
        }
    }

    SupportInfo getSupportInfo() {
        try {
            mPowerHalVersion = mPowerHal.getInterfaceVersion();
            if (mPowerHalVersion >= 6) {
                return mPowerHal.getSupportInfo();
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not contact PowerHAL!", e);
        }

        SupportInfo supportInfo = new SupportInfo();
        supportInfo.headroom = new SupportInfo.HeadroomSupportInfo();
        supportInfo.headroom.isCpuSupported = false;
        supportInfo.headroom.isGpuSupported = false;
        return supportInfo;
    }

    private ServiceThread createCleanUpThread() {
        final ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_LOWEST, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }

    @VisibleForTesting
    static class Injector {
        NativeWrapper createNativeWrapper() {
            return new NativeWrapper();
        }
        IPower createIPower() {
            return IPower.Stub.asInterface(
                ServiceManager.waitForDeclaredService(IPower.DESCRIPTOR + "/default"));
        }
    }

    private static class ThreadUsageTracker {
        /*
         * Thread object for tracking thread usage per UID
         */
        int mTid;
        boolean mIsGraphicsPipeline;

        ThreadUsageTracker(int tid) {
            mTid = tid;
            mIsGraphicsPipeline = false;
        }

        ThreadUsageTracker(int tid, boolean isGraphicsPipeline) {
            mTid = tid;
            mIsGraphicsPipeline = isGraphicsPipeline;
        }

        public int getTid() {
            return mTid;
        }

        public boolean isGraphicsPipeline() {
            return mIsGraphicsPipeline;
        }

        public void setGraphicsPipeline(boolean isGraphicsPipeline) {
            mIsGraphicsPipeline = isGraphicsPipeline;
        }
    }

    private class AppHintSessionSnapshot {
        /*
         * Per-Uid and Per-SessionTag snapshot that tracks metrics including
         * number of created sessions, number of power efficienct sessions, and
         * maximum number of threads in a session.
         * Given that it's Per-SessionTag, each uid can have multiple snapshots.
         */
        int mCurrentSessionCount;
        int mMaxConcurrentSession;
        int mMaxThreadCount;
        int mPowerEfficientSessionCount;
        int mGraphicsPipelineSessionCount;

        final int mTargetDurationNsCountPQSize = 100;
        PriorityQueue<TargetDurationRecord> mTargetDurationNsCountPQ;

        class TargetDurationRecord implements Comparable<TargetDurationRecord> {
            long mTargetDurationNs;
            long mTimestamp;
            int mCount;
            TargetDurationRecord(long targetDurationNs) {
                mTargetDurationNs = targetDurationNs;
                mTimestamp = System.currentTimeMillis();
                mCount = 1;
            }

            @Override
            public int compareTo(TargetDurationRecord t) {
                int tCount = t.getCount();
                int thisCount = this.getCount();
                // Here we sort in the order of number of count in ascending order.
                // i.e. the lowest count of target duration is at the head of the queue.
                // Upon same count, the tiebreaker is the timestamp, the older item will be at the
                // front of the queue.
                if (tCount == thisCount) {
                    return (t.getTimestamp() < this.getTimestamp()) ? 1 : -1;
                }
                return (tCount < thisCount) ? 1 : -1;
            }
            long getTargetDurationNs() {
                return mTargetDurationNs;
            }

            int getCount() {
                return mCount;
            }

            long getTimestamp() {
                return mTimestamp;
            }

            void setCount(int count) {
                mCount = count;
            }

            void setTimestamp() {
                mTimestamp = System.currentTimeMillis();
            }

            void setTargetDurationNs(long targetDurationNs) {
                mTargetDurationNs = targetDurationNs;
            }
        }

        AppHintSessionSnapshot() {
            mCurrentSessionCount = 0;
            mMaxConcurrentSession = 0;
            mMaxThreadCount = 0;
            mPowerEfficientSessionCount = 0;
            mGraphicsPipelineSessionCount = 0;
            mTargetDurationNsCountPQ = new PriorityQueue<>(1);
        }

        void updateUponSessionCreation(int threadCount, long targetDuration) {
            mCurrentSessionCount += 1;
            mMaxConcurrentSession = Math.max(mMaxConcurrentSession, mCurrentSessionCount);
            mMaxThreadCount = Math.max(mMaxThreadCount, threadCount);
            updateTargetDurationNs(targetDuration);
        }

        void updateUponSessionClose() {
            mCurrentSessionCount -= 1;
        }

        void logPowerEfficientSession() {
            mPowerEfficientSessionCount += 1;
        }

        void logGraphicsPipelineSession() {
            mGraphicsPipelineSessionCount += 1;
        }

        void updateThreadCount(int threadCount) {
            mMaxThreadCount = Math.max(mMaxThreadCount, threadCount);
        }

        void updateTargetDurationNs(long targetDurationNs) {
            for (TargetDurationRecord t : mTargetDurationNsCountPQ) {
                if (t.getTargetDurationNs() == targetDurationNs) {
                    t.setCount(t.getCount() + 1);
                    t.setTimestamp();
                    return;
                }
            }
            if (mTargetDurationNsCountPQ.size() == mTargetDurationNsCountPQSize) {
                mTargetDurationNsCountPQ.poll();
            }
            mTargetDurationNsCountPQ.add(new TargetDurationRecord(targetDurationNs));
        }

        int getMaxConcurrentSession() {
            return mMaxConcurrentSession;
        }

        int getMaxThreadCount() {
            return mMaxThreadCount;
        }

        int getPowerEfficientSessionCount() {
            return mPowerEfficientSessionCount;
        }

        int getGraphicsPipelineSessionCount() {
            return mGraphicsPipelineSessionCount;
        }

        long[] targetDurationNsList() {
            final int listSize = 5;
            long[] targetDurations = new long[listSize];
            while (mTargetDurationNsCountPQ.size() > listSize) {
                mTargetDurationNsCountPQ.poll();
            }
            for (int i = 0; i < listSize && !mTargetDurationNsCountPQ.isEmpty(); ++i) {
                targetDurations[i] = mTargetDurationNsCountPQ.poll().getTargetDurationNs();
            }
            return targetDurations;
        }
    }
    private boolean isHintSessionSupported() {
        return mHintSessionPreferredRate != -1;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.PERFORMANCE_HINT_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            systemReady();
        }
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            registerStatsCallbacks();
        }
    }

    private void systemReady() {
        Slogf.v(TAG, "Initializing HintManager service...");
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

    }

    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.ADPF_SYSTEM_COMPONENT_INFO,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.ADPF_SESSION_SNAPSHOT,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
    }

    private int onPullAtom(int atomTag, @NonNull List<StatsEvent> data) {
        if (atomTag == FrameworkStatsLog.ADPF_SYSTEM_COMPONENT_INFO) {
            final boolean isSurfaceFlingerUsingCpuHint =
                    SystemProperties.getBoolean(PROPERTY_SF_ENABLE_CPU_HINT, false);
            final boolean isHwuiHintManagerEnabled =
                    SystemProperties.getBoolean(PROPERTY_HWUI_ENABLE_HINT_MANAGER, false);

            data.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.ADPF_SYSTEM_COMPONENT_INFO,
                    isSurfaceFlingerUsingCpuHint,
                    isHwuiHintManagerEnabled,
                    getFmqUsage()));
        }
        if (atomTag == FrameworkStatsLog.ADPF_SESSION_SNAPSHOT) {
            synchronized (mSessionSnapshotMapLock) {
                for (int i = 0; i < mSessionSnapshotMap.size(); ++i) {
                    final int uid = mSessionSnapshotMap.keyAt(i);
                    final ArrayMap<Integer, AppHintSessionSnapshot> sessionSnapshots =
                            mSessionSnapshotMap.valueAt(i);
                    for (int j = 0; j < sessionSnapshots.size(); ++j) {
                        final int sessionTag = sessionSnapshots.keyAt(j);
                        final AppHintSessionSnapshot sessionSnapshot = sessionSnapshots.valueAt(j);
                        data.add(FrameworkStatsLog.buildStatsEvent(
                                FrameworkStatsLog.ADPF_SESSION_SNAPSHOT,
                                uid,
                                sessionTag,
                                sessionSnapshot.getMaxConcurrentSession(),
                                sessionSnapshot.getMaxThreadCount(),
                                sessionSnapshot.getPowerEfficientSessionCount(),
                                sessionSnapshot.targetDurationNsList()
                        ));
                    }
                }
            }
            restoreSessionSnapshot();
        }
        return android.app.StatsManager.PULL_SUCCESS;
    }

    private int getFmqUsage() {
        if (mUsesFmq) {
            return FrameworkStatsLog.ADPFSYSTEM_COMPONENT_INFO__FMQ_SUPPORTED__SUPPORTED;
        } else if (mPowerHalVersion < 5) {
            return FrameworkStatsLog.ADPFSYSTEM_COMPONENT_INFO__FMQ_SUPPORTED__HAL_VERSION_NOT_MET;
        } else {
            return FrameworkStatsLog.ADPFSYSTEM_COMPONENT_INFO__FMQ_SUPPORTED__UNSUPPORTED;
        }
    }

    private void restoreSessionSnapshot() {
        // clean up snapshot map and rebuild with current active sessions
        synchronized (mSessionSnapshotMapLock) {
            mSessionSnapshotMap.clear();
            synchronized (mLock) {
                for (int i = 0; i < mActiveSessions.size(); i++) {
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap =
                            mActiveSessions.valueAt(i);
                    for (int j = 0; j < tokenMap.size(); j++) {
                        ArraySet<AppHintSession> sessionSet = tokenMap.valueAt(j);
                        for (int k = 0; k < sessionSet.size(); ++k) {
                            AppHintSession appHintSession = sessionSet.valueAt(k);
                            final int tag = appHintSession.getTag();
                            final int uid = appHintSession.getUid();
                            final long targetDuationNs =
                                    appHintSession.getTargetDurationNs();
                            final int threadCount = appHintSession.getThreadIds().length;
                            ArrayMap<Integer, AppHintSessionSnapshot> snapshots =
                                    mSessionSnapshotMap.get(uid);
                            if (snapshots == null) {
                                snapshots = new ArrayMap<>();
                                mSessionSnapshotMap.put(uid, snapshots);
                            }
                            AppHintSessionSnapshot snapshot = snapshots.get(tag);
                            if (snapshot == null) {
                                snapshot = new AppHintSessionSnapshot();
                                snapshots.put(tag, snapshot);
                            }
                            snapshot.updateUponSessionCreation(threadCount,
                                    targetDuationNs);
                        }
                    }
                }
            }
        }
    }

    /**
     * Wrapper around the static-native methods from native.
     *
     * This class exists to allow us to mock static native methods in our tests. If mocking static
     * methods becomes easier than this in the future, we can delete this class.
     */
    @VisibleForTesting
    public static class NativeWrapper {
        private native void nativeInit();

        private static native long nativeGetHintSessionPreferredRate();

        private static native long nativeCreateHintSession(int tgid, int uid, int[] tids,
                long durationNanos);

        private static native long nativeCreateHintSessionWithConfig(int tgid, int uid, int[] tids,
                long durationNanos, int tag, SessionConfig config);

        private static native void nativePauseHintSession(long halPtr);

        private static native void nativeResumeHintSession(long halPtr);

        private static native void nativeCloseHintSession(long halPtr);

        private static native void nativeUpdateTargetWorkDuration(
                long halPtr, long targetDurationNanos);

        private static native void nativeReportActualWorkDuration(
                long halPtr, long[] actualDurationNanos, long[] timeStampNanos);

        private static native void nativeSendHint(long halPtr, int hint);

        private static native void nativeSetThreads(long halPtr, int[] tids);

        private static native void nativeSetMode(long halPtr, int mode, boolean enabled);

        private static native void nativeReportActualWorkDuration(
                long halPtr, WorkDuration[] workDurations);

        /** Wrapper for HintManager.nativeInit */
        public void halInit() {
            nativeInit();
        }

        /** Wrapper for HintManager.nativeGetHintSessionPreferredRate */
        public long halGetHintSessionPreferredRate() {
            return nativeGetHintSessionPreferredRate();
        }

        /** Wrapper for HintManager.nativeCreateHintSession */
        public long halCreateHintSession(int tgid, int uid, int[] tids, long durationNanos) {
            return nativeCreateHintSession(tgid, uid, tids, durationNanos);
        }

        /** Wrapper for HintManager.nativeCreateHintSessionWithConfig */
        public long halCreateHintSessionWithConfig(
                int tgid, int uid, int[] tids, long durationNanos, int tag, SessionConfig config) {
            return nativeCreateHintSessionWithConfig(tgid, uid, tids, durationNanos, tag, config);
        }

        /** Wrapper for HintManager.nativePauseHintSession */
        public void halPauseHintSession(long halPtr) {
            nativePauseHintSession(halPtr);
        }

        /** Wrapper for HintManager.nativeResumeHintSession */
        public void halResumeHintSession(long halPtr) {
            nativeResumeHintSession(halPtr);
        }

        /** Wrapper for HintManager.nativeCloseHintSession */
        public void halCloseHintSession(long halPtr) {
            nativeCloseHintSession(halPtr);
        }

        /** Wrapper for HintManager.nativeUpdateTargetWorkDuration */
        public void halUpdateTargetWorkDuration(long halPtr, long targetDurationNanos) {
            nativeUpdateTargetWorkDuration(halPtr, targetDurationNanos);
        }

        /** Wrapper for HintManager.nativeReportActualWorkDuration */
        public void halReportActualWorkDuration(
                long halPtr, long[] actualDurationNanos, long[] timeStampNanos) {
            nativeReportActualWorkDuration(halPtr, actualDurationNanos,
                    timeStampNanos);
        }

        /** Wrapper for HintManager.sendHint */
        public void halSendHint(long halPtr, int hint) {
            nativeSendHint(halPtr, hint);
        }

        /** Wrapper for HintManager.nativeSetThreads */
        public void halSetThreads(long halPtr, int[] tids) {
            nativeSetThreads(halPtr, tids);
        }

        /** Wrapper for HintManager.setMode */
        public void halSetMode(long halPtr, int mode, boolean enabled) {
            nativeSetMode(halPtr, mode, enabled);
        }

        /** Wrapper for HintManager.nativeReportActualWorkDuration */
        public void halReportActualWorkDuration(long halPtr, WorkDuration[] workDurations) {
            nativeReportActualWorkDuration(halPtr, workDurations);
        }
    }

    @VisibleForTesting
    final class MyUidObserver extends UidObserver {
        @GuardedBy("mLock")
        private final SparseIntArray mProcStatesCache = new SparseIntArray();
        public boolean isUidForeground(int uid) {
            synchronized (mLock) {
                return mProcStatesCache.get(uid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                        <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            FgThread.getHandler().post(() -> {
                synchronized (mLock) {
                    mProcStatesCache.delete(uid);
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap = mActiveSessions.get(uid);
                    if (tokenMap == null) {
                        return;
                    }
                    Slog.d(TAG, "Uid gone for " + uid);
                    for (int i = tokenMap.size() - 1; i >= 0; i--) {
                        // Will remove the session from tokenMap
                        ArraySet<AppHintSession> sessionSet = tokenMap.valueAt(i);
                        IntArray closedSessionsForSf = new IntArray();
                        // Batch the closure call to SF for all the sessions that die
                        for (int j = sessionSet.size() - 1; j >= 0; j--) {
                            AppHintSession session = sessionSet.valueAt(j);
                            if (session.isTrackedBySf()) {
                                // Mark it as untracked so we don't untrack again on close
                                session.setTrackedBySf(false);
                                closedSessionsForSf.add(session.getSessionId());
                            }
                        }
                        if (mSessionManager != null) {
                            try {
                                mSessionManager.trackedSessionsDied(closedSessionsForSf.toArray());
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Failed to communicate with SessionManager");
                            }
                        }
                        for (int j = sessionSet.size() - 1; j >= 0; j--) {
                            sessionSet.valueAt(j).close();
                        }
                    }
                }
                synchronized (mChannelMapLock) {
                    // Clean up the uid's session channels
                    final TreeMap<Integer, ChannelItem> uidMap = mChannelMap.get(uid);
                    if (uidMap != null) {
                        for (Map.Entry<Integer, ChannelItem> entry : uidMap.entrySet()) {
                            entry.getValue().closeChannel();
                        }
                        mChannelMap.remove(uid);
                    }
                }
            });
        }

        /**
         * The IUidObserver callback is called from the system_server, so it'll be a direct function
         * call from ActivityManagerService. Do not do heavy logic here.
         */
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            FgThread.getHandler().post(() -> {
                synchronized (mLock) {
                    boolean shouldCleanup = false;
                    if (mPowerHalVersion >= 4 && powerhintThreadCleanup()) {
                        int prevProcState = mProcStatesCache.get(uid, Integer.MAX_VALUE);
                        shouldCleanup =
                                prevProcState <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
                                        && procState
                                        > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                    }

                    mProcStatesCache.put(uid, procState);
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap = mActiveSessions.get(uid);
                    if (tokenMap == null) {
                        return;
                    }
                    if (shouldCleanup && powerhintThreadCleanup()) {
                        final Message msg = mCleanUpHandler.obtainMessage(EVENT_CLEAN_UP_UID,
                                uid);
                        mCleanUpHandler.sendMessageDelayed(msg, CLEAN_UP_UID_DELAY_MILLIS);
                        Slog.d(TAG, "Sent cleanup message for uid " + uid);
                    }
                    boolean shouldAllowUpdate = isUidForeground(uid);
                    for (int i = tokenMap.size() - 1; i >= 0; i--) {
                        final ArraySet<AppHintSession> sessionSet = tokenMap.valueAt(i);
                        for (int j = sessionSet.size() - 1; j >= 0; j--) {
                            sessionSet.valueAt(j).updateHintAllowedByProcState(shouldAllowUpdate);
                        }
                    }
                }
            });
        }
    }

    /**
     * Creates a channel item in the channel map if one does not exist, then returns
     * the entry in the channel map.
     */
    public ChannelItem getOrCreateMappedChannelItem(int tgid, int uid, IBinder token) {
        synchronized (mChannelMapLock) {
            if (!mChannelMap.containsKey(uid)) {
                mChannelMap.put(uid, new TreeMap<Integer, ChannelItem>());
            }
            TreeMap<Integer, ChannelItem> map = mChannelMap.get(uid);
            if (!map.containsKey(tgid)) {
                ChannelItem item = new ChannelItem(tgid, uid, token);
                item.openChannel();
                map.put(tgid, item);
            }
            return map.get(tgid);
        }
    }

    /**
     * This removes an entry in the binder token callback map when a channel is closed,
     * and unregisters its callbacks.
     */
    public void removeChannelItem(Integer tgid, Integer uid) {
        synchronized (mChannelMapLock) {
            TreeMap<Integer, ChannelItem> map = mChannelMap.get(uid);
            if (map != null) {
                ChannelItem item = map.get(tgid);
                if (item != null) {
                    item.closeChannel();
                    map.remove(tgid);
                }
                if (map.isEmpty()) {
                    mChannelMap.remove(uid);
                }
            }
        }
    }

    /**
     * Manages the lifecycle of a single channel. This includes caching the channel descriptor,
     * receiving binder token death notifications, and handling cleanup on uid termination. There
     * can only be one ChannelItem per (tgid, uid) pair in mChannelMap, and channel creation happens
     * when a ChannelItem enters the map, while destruction happens when it leaves the map.
     */
    private class ChannelItem implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            removeChannelItem(mTgid, mUid);
        }

        ChannelItem(int tgid, int uid, IBinder token) {
            this.mTgid = tgid;
            this.mUid = uid;
            this.mToken = token;
            this.mLinked = false;
            this.mConfig = null;
        }

        public void closeChannel() {
            if (mLinked) {
                mToken.unlinkToDeath(this, 0);
                mLinked = false;
            }
            if (mConfig != null) {
                try {
                    mPowerHal.closeSessionChannel(mTgid, mUid);
                } catch (RemoteException e) {
                    throw new IllegalStateException("Failed to close session channel!", e);
                }
                mConfig = null;
            }
        }

        public void openChannel() {
            if (!mLinked) {
                try {
                    mToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    throw new IllegalStateException("Client already dead", e);
                }
                mLinked = true;
            }
            if (mConfig == null) {
                try {
                    // This method uses PowerHAL directly through the SDK,
                    // to avoid needing to pass the ChannelConfig through JNI.
                    mConfig = mPowerHal.getSessionChannel(mTgid, mUid);
                } catch (RemoteException e) {
                    removeChannelItem(mTgid, mUid);
                    throw new IllegalStateException("Failed to create session channel!", e);
                }
            }
        }

        ChannelConfig getConfig() {
            return mConfig;
        }

        // To avoid accidental double-linking / unlinking
        boolean mLinked;
        final int mTgid;
        final int mUid;
        final IBinder mToken;
        ChannelConfig mConfig;
    }

    final class CleanUpHandler extends Handler {
        // status of processed tid used for caching
        private static final int TID_NOT_CHECKED = 0;
        private static final int TID_PASSED_CHECK = 1;
        private static final int TID_EXITED = 2;

        CleanUpHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == EVENT_CLEAN_UP_UID) {
                if (hasEqualMessages(msg.what, msg.obj)) {
                    removeEqualMessages(msg.what, msg.obj);
                    final Message newMsg = obtainMessage(msg.what, msg.obj);
                    sendMessageDelayed(newMsg, CLEAN_UP_UID_DELAY_MILLIS);
                    Slog.d(TAG, "Duplicate messages for " + msg.obj);
                    return;
                }
                Slog.d(TAG, "Starts cleaning for " + msg.obj);
                final int uid = (int) msg.obj;
                boolean isForeground = mUidObserver.isUidForeground(uid);
                // store all sessions in a list and release the global lock
                // we don't need to worry about stale data or racing as the session is synchronized
                // itself and will perform its own closed status check in setThreads call
                final List<AppHintSession> sessions;
                synchronized (mLock) {
                    final ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap =
                            mActiveSessions.get(uid);
                    if (tokenMap == null || tokenMap.isEmpty()) {
                        return;
                    }
                    sessions = new ArrayList<>(tokenMap.size());
                    for (int i = tokenMap.size() - 1; i >= 0; i--) {
                        final ArraySet<AppHintSession> set = tokenMap.valueAt(i);
                        for (int j = set.size() - 1; j >= 0; j--) {
                            sessions.add(set.valueAt(j));
                        }
                    }
                }
                final long[] durationList = new long[sessions.size()];
                final int[] invalidTidCntList = new int[sessions.size()];
                final SparseIntArray checkedTids = new SparseIntArray();
                int[] totalTidCnt = new int[1];
                for (int i = sessions.size() - 1; i >= 0; i--) {
                    final AppHintSession session = sessions.get(i);
                    final long start = System.nanoTime();
                    try {
                        final int invalidCnt = cleanUpSession(session, checkedTids, totalTidCnt);
                        final long elapsed = System.nanoTime() - start;
                        invalidTidCntList[i] = invalidCnt;
                        durationList[i] = elapsed;
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to clean up session " + session.mHalSessionPtr
                                + " for UID " + session.mUid);
                    }
                }
                logCleanUpMetrics(uid, invalidTidCntList, durationList, sessions.size(),
                        totalTidCnt[0], isForeground);
            }
        }

        private void logCleanUpMetrics(int uid, int[] count, long[] durationNsList, int sessionCnt,
                int totalTidCnt, boolean isForeground) {
            int maxInvalidTidCnt = Integer.MIN_VALUE;
            int totalInvalidTidCnt = 0;
            for (int i = 0; i < count.length; i++) {
                totalInvalidTidCnt += count[i];
                maxInvalidTidCnt = Math.max(maxInvalidTidCnt, count[i]);
            }
            if (DEBUG || totalInvalidTidCnt > 0) {
                Arrays.sort(durationNsList);
                long totalDurationNs = 0;
                for (int i = 0; i < durationNsList.length; i++) {
                    totalDurationNs += durationNsList[i];
                }
                int totalDurationUs = (int) TimeUnit.NANOSECONDS.toMicros(totalDurationNs);
                int maxDurationUs = (int) TimeUnit.NANOSECONDS.toMicros(
                        durationNsList[durationNsList.length - 1]);
                int minDurationUs = (int) TimeUnit.NANOSECONDS.toMicros(durationNsList[0]);
                int avgDurationUs = (int) TimeUnit.NANOSECONDS.toMicros(
                        totalDurationNs / durationNsList.length);
                int th90DurationUs = (int) TimeUnit.NANOSECONDS.toMicros(
                        durationNsList[(int) (durationNsList.length * 0.9)]);
                FrameworkStatsLog.write(FrameworkStatsLog.ADPF_HINT_SESSION_TID_CLEANUP, uid,
                        totalDurationUs, maxDurationUs, totalTidCnt, totalInvalidTidCnt,
                        maxInvalidTidCnt, sessionCnt, isForeground);
                Slog.w(TAG,
                        "Invalid tid found for UID" + uid + " in " + totalDurationUs + "us:\n\t"
                                + "count("
                                + " session: " + sessionCnt
                                + " totalTid: " + totalTidCnt
                                + " maxInvalidTid: " + maxInvalidTidCnt
                                + " totalInvalidTid: " + totalInvalidTidCnt + ")\n\t"
                                + "time per session("
                                + " min: " + minDurationUs + "us"
                                + " max: " + maxDurationUs + "us"
                                + " avg: " + avgDurationUs + "us"
                                + " 90%: " + th90DurationUs + "us" + ")\n\t"
                                + "isForeground: " + isForeground);
            }
        }

        // This will check if each TID currently linked to the session still exists. If it's
        // previously registered as not an isolated process, then it will run tkill(pid, tid, 0) to
        // verify that it's still running under the same pid. Otherwise, it will run
        // kill(tid, 0) to only check if it exists. The result will be cached in checkedTids
        // map with tid as the key and checked status as value.
        public int cleanUpSession(AppHintSession session, SparseIntArray checkedTids, int[] total) {
            if (session.isClosed() || session.isForcePaused()) {
                return 0;
            }
            final int pid = session.mPid;
            final int[] tids = session.getTidsInternal();
            if (total != null && total.length == 1) {
                total[0] += tids.length;
            }
            final IntArray filtered = new IntArray(tids.length);
            for (int i = 0; i < tids.length; i++) {
                int tid = tids[i];
                if (checkedTids.get(tid, 0) != TID_NOT_CHECKED) {
                    if (checkedTids.get(tid) == TID_PASSED_CHECK) {
                        filtered.add(tid);
                    }
                    continue;
                }
                // if it was registered as a non-isolated then we perform more restricted check
                final boolean isNotIsolated;
                synchronized (mNonIsolatedTidsLock) {
                    isNotIsolated = mNonIsolatedTids.containsKey(tid);
                }
                try {
                    if (isNotIsolated) {
                        Process.checkTid(pid, tid);
                    } else {
                        Process.checkPid(tid);
                    }
                    checkedTids.put(tid, TID_PASSED_CHECK);
                    filtered.add(tid);
                } catch (NoSuchElementException e) {
                    checkedTids.put(tid, TID_EXITED);
                } catch (Exception e) {
                    Slog.w(TAG, "Unexpected exception when checking TID " + tid + " under PID "
                            + pid + "(isolated: " + !isNotIsolated + ")", e);
                    // if anything unexpected happens then we keep it, but don't store it as checked
                    filtered.add(tid);
                }
            }
            final int diff = tids.length - filtered.size();
            if (diff > 0) {
                synchronized (session) {
                    // in case thread list is updated during the cleanup then we skip updating
                    // the session but just return the number for reporting purpose
                    final int[] newTids = session.getTidsInternal();
                    if (newTids.length != tids.length) {
                        Slog.d(TAG, "Skipped cleaning up the session as new tids are added");
                        return diff;
                    }
                    Arrays.sort(newTids);
                    Arrays.sort(tids);
                    if (!Arrays.equals(newTids, tids)) {
                        Slog.d(TAG, "Skipped cleaning up the session as new tids are updated");
                        return diff;
                    }
                    Slog.d(TAG, "Cleaned up " + diff + " invalid tids for session "
                            + session.mHalSessionPtr + " with UID " + session.mUid + "\n\t"
                            + "before: " + Arrays.toString(tids) + "\n\t"
                            + "after: " + filtered);
                    final int[] filteredTids = filtered.toArray();
                    if (filteredTids.length == 0) {
                        session.mShouldForcePause = true;
                        if (session.mUpdateAllowedByProcState) {
                            session.pause();
                        }
                    } else {
                        session.setThreadsInternal(filteredTids, false);
                    }
                }
            }
            return diff;
        }
    }

    @VisibleForTesting
    IHintManager.Stub getBinderServiceInstance() {
        return mService;
    }

    @VisibleForTesting
    Boolean hasChannel(int tgid, int uid) {
        synchronized (mChannelMapLock) {
            TreeMap<Integer, ChannelItem> uidMap = mChannelMap.get(uid);
            if (uidMap != null) {
                ChannelItem item = uidMap.get(tgid);
                return item != null;
            }
            return false;
        }
    }

    // returns the first invalid tid or null if not found
    private Integer checkTidValid(int uid, int tgid, int[] tids, IntArray nonIsolated) {
        // Make sure all tids belongs to the same UID (including isolated UID),
        // tids can belong to different application processes.
        List<Integer> isolatedPids = null;
        for (int i = 0; i < tids.length; i++) {
            int tid = tids[i];
            final String[] procStatusKeys = new String[]{
                    "Uid:",
                    "Tgid:"
            };
            long[] output = new long[procStatusKeys.length];
            Process.readProcLines("/proc/" + tid + "/status", procStatusKeys, output);
            int uidOfThreadId = (int) output[0];
            int pidOfThreadId = (int) output[1];

            // use PID check for non-isolated processes
            if (nonIsolated != null && pidOfThreadId == tgid) {
                nonIsolated.add(tid);
                continue;
            }
            // use UID check for isolated processes.
            if (uidOfThreadId == uid) {
                continue;
            }
            // Only call into AM if the tid is either isolated or invalid
            if (isolatedPids == null) {
                // To avoid deadlock, do not call into AMS if the call is from system.
                if (uid == Process.SYSTEM_UID) {
                    return tid;
                }
                isolatedPids = mAmInternal.getIsolatedProcesses(uid);
                if (isolatedPids == null) {
                    return tid;
                }
            }
            if (isolatedPids.contains(pidOfThreadId)) {
                continue;
            }
            return tid;
        }
        return null;
    }

    private String formatTidCheckErrMsg(int callingUid, int[] tids, Integer invalidTid) {
        return "Tid" + invalidTid + " from list " + Arrays.toString(tids)
                + " doesn't belong to the calling application " + callingUid;
    }

    private boolean contains(final int[] array, final int target) {
        for (int element : array) {
            if (element == target) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    final class BinderService extends IHintManager.Stub {
        @Override
        public IHintSession createHintSessionWithConfig(@NonNull IBinder token,
                    @SessionTag int tag, SessionCreationConfig creationConfig,
                    SessionConfig config) {
            if (!isHintSessionSupported()) {
                throw new UnsupportedOperationException("PowerHAL is not supported!");
            }

            java.util.Objects.requireNonNull(token);
            java.util.Objects.requireNonNull(creationConfig.tids);

            final int[] tids = creationConfig.tids;
            Preconditions.checkArgument(tids.length != 0, "tids should"
                    + " not be empty.");


            final int callingUid = Binder.getCallingUid();
            final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
            final long identity = Binder.clearCallingIdentity();
            final long durationNanos = creationConfig.targetWorkDurationNanos;

            Preconditions.checkArgument(checkGraphicsPipelineValid(creationConfig, callingUid),
                    "not enough of available graphics pipeline thread.");
            try {
                final IntArray nonIsolated = powerhintThreadCleanup() ? new IntArray(tids.length)
                        : null;
                final Integer invalidTid = checkTidValid(callingUid, callingTgid, tids,
                        nonIsolated);
                if (invalidTid != null) {
                    final String errMsg = formatTidCheckErrMsg(callingUid, tids, invalidTid);
                    Slogf.w(TAG, errMsg);
                    throw new SecurityException(errMsg);
                }
                if (resetOnForkEnabled()) {
                    try {
                        for (int tid : tids) {
                            int policy = Process.getThreadScheduler(tid);
                            // If the thread is not using the default scheduling policy (SCHED_OTHER),
                            // we don't change it.
                            if (policy != Process.SCHED_OTHER) {
                                continue;
                            }
                            // set the SCHED_RESET_ON_FORK flag.
                            int prio = Process.getThreadPriority(tid);
                            Process.setThreadScheduler(tid, Process.SCHED_OTHER | Process.SCHED_RESET_ON_FORK, 0);
                            Process.setThreadPriority(tid, prio);
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to set SCHED_RESET_ON_FORK for tids "
                                + Arrays.toString(tids), e);
                    }
                }

                if (adpfSessionTag() && tag == SessionTag.APP) {
                    // If the category of the app is a game,
                    // we change the session tag to SessionTag.GAME
                    // as it was not previously classified
                    switch (getUidApplicationCategory(callingUid)) {
                        case ApplicationInfo.CATEGORY_GAME -> tag = SessionTag.GAME;
                        case ApplicationInfo.CATEGORY_UNDEFINED ->
                            // We use CATEGORY_UNDEFINED to filter the case when
                            // PackageManager.NameNotFoundException is caught,
                            // which should not happen.
                            tag = SessionTag.APP;
                        default -> tag = SessionTag.APP;
                    }
                }
                config.id = -1;
                Long halSessionPtr = null;
                if (mConfigCreationSupport.get()) {
                    try {
                        halSessionPtr = mNativeWrapper.halCreateHintSessionWithConfig(
                                callingTgid, callingUid, tids, durationNanos, tag, config);
                    } catch (UnsupportedOperationException e) {
                        mConfigCreationSupport.set(false);
                    } catch (IllegalStateException e) {
                        Slog.e("createHintSessionWithConfig failed: ", e.getMessage());
                        throw new IllegalStateException(
                            "createHintSessionWithConfig failed: " + e.getMessage());
                    }
                }

                if (halSessionPtr == null) {
                    try {
                        halSessionPtr = mNativeWrapper.halCreateHintSession(callingTgid,
                                callingUid, tids, durationNanos);
                    } catch (UnsupportedOperationException e) {
                        Slog.w("createHintSession unsupported: ", e.getMessage());
                        throw new UnsupportedOperationException(
                            "createHintSession unsupported: " + e.getMessage());
                    } catch (IllegalStateException e) {
                        Slog.e("createHintSession failed: ", e.getMessage());
                        throw new IllegalStateException(
                            "createHintSession failed: " + e.getMessage());
                    }
                }

                if (powerhintThreadCleanup()) {
                    synchronized (mNonIsolatedTidsLock) {
                        for (int i = nonIsolated.size() - 1; i >= 0; i--) {
                            mNonIsolatedTids.putIfAbsent(nonIsolated.get(i), new ArraySet<>());
                            mNonIsolatedTids.get(nonIsolated.get(i)).add(halSessionPtr);
                        }
                    }
                }

                final long sessionIdForTracing = config.id != -1 ? config.id : halSessionPtr;
                logPerformanceHintSessionAtom(
                        callingUid, sessionIdForTracing, durationNanos, tids, tag);

                synchronized (mSessionSnapshotMapLock) {
                    // Update session snapshot upon session creation
                    mSessionSnapshotMap.computeIfAbsent(callingUid, k -> new ArrayMap<>())
                            .computeIfAbsent(tag, k -> new AppHintSessionSnapshot())
                            .updateUponSessionCreation(tids.length, durationNanos);
                }
                AppHintSession hs = null;
                synchronized (mLock) {
                    Integer configId = null;
                    if (config.id != -1) {
                        configId = new Integer((int) config.id);
                    }
                    hs = new AppHintSession(callingUid, callingTgid, tag, tids,
                            token, halSessionPtr, durationNanos, configId);
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap =
                            mActiveSessions.get(callingUid);
                    if (tokenMap == null) {
                        tokenMap = new ArrayMap<>(1);
                        mActiveSessions.put(callingUid, tokenMap);
                    }
                    ArraySet<AppHintSession> sessionSet = tokenMap.get(token);
                    if (sessionSet == null) {
                        sessionSet = new ArraySet<>(1);
                        tokenMap.put(token, sessionSet);
                    }
                    sessionSet.add(hs);
                    mUsesFmq = mUsesFmq || hasChannel(callingTgid, callingUid);
                }

                if (hs != null) {
                    boolean isGraphicsPipeline = false;
                    if (creationConfig.modesToEnable != null) {
                        for (int sessionMode : creationConfig.modesToEnable) {
                            if (sessionMode == SessionModes.GRAPHICS_PIPELINE.ordinal()) {
                                isGraphicsPipeline = true;
                            }
                            hs.setMode(sessionMode, true);
                        }
                    }

                    if (creationConfig.layerTokens != null
                            && creationConfig.layerTokens.length > 0) {
                        hs.associateToLayers(creationConfig.layerTokens);
                    }

                    synchronized (mThreadsUsageObject) {
                        mThreadsUsageMap.computeIfAbsent(callingUid, k -> new ArraySet<>());
                        ArraySet<ThreadUsageTracker> threadsSet = mThreadsUsageMap.get(callingUid);
                        for (int i = 0; i < tids.length; ++i) {
                            threadsSet.add(new ThreadUsageTracker(tids[i], isGraphicsPipeline));
                        }
                    }
                }

                return hs;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public @Nullable ChannelConfig getSessionChannel(IBinder token) {
            if (mPowerHalVersion < 5 || !adpfUseFmqChannel()
                    || mFMQUsesIntegratedEventFlag) {
                return null;
            }
            java.util.Objects.requireNonNull(token);
            final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
            final int callingUid = Binder.getCallingUid();
            ChannelItem item = getOrCreateMappedChannelItem(callingTgid, callingUid, token);
            // FMQ V1 requires a separate event flag to be passed, and the default no-op
            // implmenentation in PowerHAL does not return such a shared flag. This helps
            // avoid using the FMQ on a default impl that does not support it.
            if (item.getConfig().eventFlagDescriptor == null) {
                mFMQUsesIntegratedEventFlag = true;
                closeSessionChannel();
                return null;
            }
            return item.getConfig();
        };

        @Override
        public void closeSessionChannel() {
            if (mPowerHalVersion < 5 || !adpfUseFmqChannel()) {
                return;
            }
            final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
            final int callingUid = Binder.getCallingUid();
            removeChannelItem(callingTgid, callingUid);
        };

        @Override
        public long getHintSessionPreferredRate() {
            return mHintSessionPreferredRate;
        }

        @Override
        public int getMaxGraphicsPipelineThreadsCount() {
            return MAX_GRAPHICS_PIPELINE_THREADS_COUNT;
        }

        @Override
        public void setHintSessionThreads(@NonNull IHintSession hintSession, @NonNull int[] tids) {
            AppHintSession appHintSession = (AppHintSession) hintSession;
            appHintSession.setThreads(tids);
        }

        @Override
        public int[] getHintSessionThreadIds(@NonNull IHintSession hintSession) {
            AppHintSession appHintSession = (AppHintSession) hintSession;
            return appHintSession.getThreadIds();
        }

        @Override
        public CpuHeadroomResult getCpuHeadroom(@NonNull CpuHeadroomParamsInternal params) {
            if (!mSupportInfo.headroom.isCpuSupported) {
                throw new UnsupportedOperationException();
            }
            final CpuHeadroomParams halParams = new CpuHeadroomParams();
            halParams.tids = new int[]{Binder.getCallingPid()};
            halParams.calculationType = params.calculationType;
            halParams.calculationWindowMillis = params.calculationWindowMillis;
            if (params.usesDeviceHeadroom) {
                halParams.tids = new int[]{};
            } else if (params.tids != null && params.tids.length > 0) {
                if (params.tids.length > 5) {
                    throw new IllegalArgumentException(
                            "More than 5 TIDs is requested: " + params.tids.length);
                }
                if (SystemProperties.getBoolean(PROPERTY_CHECK_HEADROOM_TID, true)) {
                    final int tgid = Process.getThreadGroupLeader(Binder.getCallingPid());
                    for (int tid : params.tids) {
                        if (Process.getThreadGroupLeader(tid) != tgid) {
                            throw new SecurityException("TID " + tid
                                    + " doesn't belong to the calling process with pid "
                                    + tgid);
                        }
                    }
                }
                halParams.tids = params.tids;
            }
            if (halParams.calculationWindowMillis
                    == mDefaultCpuHeadroomCalculationWindowMillis) {
                synchronized (mCpuHeadroomLock) {
                    final CpuHeadroomResult res = mCpuHeadroomCache.get(halParams);
                    if (res != null) return res;
                }
            }
            // return from HAL directly
            try {
                final CpuHeadroomResult result = mPowerHal.getCpuHeadroom(halParams);
                if (result == null) {
                    Slog.wtf(TAG, "CPU headroom from Power HAL is invalid");
                    return null;
                }
                if (halParams.calculationWindowMillis
                        == mDefaultCpuHeadroomCalculationWindowMillis) {
                    synchronized (mCpuHeadroomLock) {
                        mCpuHeadroomCache.add(halParams, result);
                    }
                }
                return result;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get CPU headroom from Power HAL", e);
                return null;
            }
        }

        @Override
        public GpuHeadroomResult getGpuHeadroom(@NonNull GpuHeadroomParamsInternal params) {
            if (!mSupportInfo.headroom.isGpuSupported) {
                throw new UnsupportedOperationException();
            }
            final GpuHeadroomParams halParams = new GpuHeadroomParams();
            halParams.calculationType = params.calculationType;
            halParams.calculationWindowMillis = params.calculationWindowMillis;
            if (halParams.calculationWindowMillis
                    == mDefaultGpuHeadroomCalculationWindowMillis) {
                synchronized (mGpuHeadroomLock) {
                    final GpuHeadroomResult res = mGpuHeadroomCache.get(halParams);
                    if (res != null) return res;
                }
            }
            // return from HAL directly
            try {
                final GpuHeadroomResult headroom = mPowerHal.getGpuHeadroom(halParams);
                if (headroom == null) {
                    Slog.wtf(TAG, "GPU headroom from Power HAL is invalid");
                    return null;
                }
                if (halParams.calculationWindowMillis
                        == mDefaultGpuHeadroomCalculationWindowMillis) {
                    synchronized (mGpuHeadroomLock) {
                        mGpuHeadroomCache.add(halParams, headroom);
                    }
                }
                return headroom;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get GPU headroom from Power HAL", e);
                return null;
            }
        }

        @Override
        public long getCpuHeadroomMinIntervalMillis() {
            if (!mSupportInfo.headroom.isCpuSupported) {
                throw new UnsupportedOperationException();
            }
            return mSupportInfo.headroom.cpuMinIntervalMillis;
        }

        @Override
        public long getGpuHeadroomMinIntervalMillis() {
            if (!mSupportInfo.headroom.isGpuSupported) {
                throw new UnsupportedOperationException();
            }
            return mSupportInfo.headroom.gpuMinIntervalMillis;
        }

        @Override
        public void passSessionManagerBinder(IBinder sessionManager) {
            // Ensure caller is internal
            if (Process.myUid() != Binder.getCallingUid()) {
                return;
            }
            mSessionManager = ISessionManager.Stub.asInterface(sessionManager);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }
            pw.println("HintSessionPreferredRate: " + mHintSessionPreferredRate);
            pw.println("MaxGraphicsPipelineThreadsCount: " + MAX_GRAPHICS_PIPELINE_THREADS_COUNT);
            pw.println("HAL Support: " + isHintSessionSupported());
            pw.println("Active Sessions:");
            synchronized (mLock) {
                for (int i = 0; i < mActiveSessions.size(); i++) {
                    pw.println("Uid " + mActiveSessions.keyAt(i).toString() + ":");
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap =
                            mActiveSessions.valueAt(i);
                    for (int j = 0; j < tokenMap.size(); j++) {
                        ArraySet<AppHintSession> sessionSet = tokenMap.valueAt(j);
                        for (int k = 0; k < sessionSet.size(); ++k) {
                            pw.println("  Session:");
                            sessionSet.valueAt(k).dump(pw, "    ");
                        }
                    }
                }
            }
            pw.println("CPU Headroom Interval: " + mSupportInfo.headroom.cpuMinIntervalMillis);
            pw.println("GPU Headroom Interval: " + mSupportInfo.headroom.gpuMinIntervalMillis);
            try {
                CpuHeadroomParamsInternal params = new CpuHeadroomParamsInternal();
                params.usesDeviceHeadroom = true;
                CpuHeadroomResult ret = getCpuHeadroom(params);
                pw.println("CPU headroom: " + (ret == null ? "N/A" : ret.getGlobalHeadroom()));
            } catch (Exception e) {
                Slog.d(TAG, "Failed to dump CPU headroom", e);
                pw.println("CPU headroom: N/A");
            }
            try {
                GpuHeadroomResult ret = getGpuHeadroom(new GpuHeadroomParamsInternal());
                pw.println("GPU headroom: " + (ret == null ? "N/A" : ret.getGlobalHeadroom()));
            } catch (Exception e) {
                Slog.d(TAG, "Failed to dump GPU headroom", e);
                pw.println("GPU headroom: N/A");
            }
        }

        private boolean checkGraphicsPipelineValid(SessionCreationConfig creationConfig, int uid) {
            if (creationConfig.modesToEnable == null) {
                return true;
            }
            boolean setGraphicsPipeline = false;
            for (int modeToEnable : creationConfig.modesToEnable) {
                if (modeToEnable == SessionModes.GRAPHICS_PIPELINE.ordinal()) {
                    setGraphicsPipeline = true;
                }
            }
            if (!setGraphicsPipeline) {
                return true;
            }

            synchronized (mThreadsUsageObject) {
                // count used graphics pipeline threads for the calling UID
                // consider the case that new tids are overlapping with in session tids
                ArraySet<ThreadUsageTracker> threadsSet = mThreadsUsageMap.get(uid);
                if (threadsSet == null) {
                    return true;
                }

                final int newThreadCount = creationConfig.tids.length;
                int graphicsPipelineThreadCount = 0;
                for (ThreadUsageTracker t : threadsSet) {
                    // count graphics pipeline threads in use
                    // and exclude overlapping ones
                    if (t.isGraphicsPipeline()) {
                        graphicsPipelineThreadCount++;
                        if (contains(creationConfig.tids, t.getTid())) {
                            graphicsPipelineThreadCount--;
                        }
                    }
                }
                return graphicsPipelineThreadCount + newThreadCount
                        <= MAX_GRAPHICS_PIPELINE_THREADS_COUNT;
            }
        }

        private void logPerformanceHintSessionAtom(int uid, long sessionId,
                long targetDuration, int[] tids, @SessionTag int sessionTag) {
            FrameworkStatsLog.write(FrameworkStatsLog.PERFORMANCE_HINT_SESSION_REPORTED, uid,
                    sessionId, targetDuration, tids.length, sessionTag);
        }

        private int getUidApplicationCategory(int uid) {
            try {
                final String packageName = mPackageManager.getNameForUid(uid);
                final ApplicationInfo applicationInfo =
                        mPackageManager.getApplicationInfo(packageName, PackageManager.MATCH_ALL);
                return applicationInfo.category;
            } catch (PackageManager.NameNotFoundException e) {
                return ApplicationInfo.CATEGORY_UNDEFINED;
            }
        }
    }

    @VisibleForTesting
    final class AppHintSession extends IHintSession.Stub implements IBinder.DeathRecipient {
        protected final int mUid;
        protected final int mPid;
        protected final int mTag;
        protected int[] mThreadIds;
        protected final IBinder mToken;
        protected long mHalSessionPtr;
        protected long mTargetDurationNanos;
        protected boolean mUpdateAllowedByProcState;
        protected int[] mNewThreadIds;
        protected boolean mPowerEfficient;
        protected boolean mGraphicsPipeline;
        protected boolean mHasBeenPowerEfficient;
        protected boolean mHasBeenGraphicsPipeline;
        protected boolean mShouldForcePause;
        protected Integer mSessionId;
        protected boolean mTrackedBySF;

        enum SessionModes {
            POWER_EFFICIENCY,
            GRAPHICS_PIPELINE,
        };

        protected AppHintSession(
                int uid, int pid, int sessionTag, int[] threadIds, IBinder token,
                long halSessionPtr, long durationNanos, Integer sessionId) {
            mUid = uid;
            mPid = pid;
            mTag = sessionTag;
            mToken = token;
            mThreadIds = threadIds;
            mHalSessionPtr = halSessionPtr;
            mTargetDurationNanos = durationNanos;
            mUpdateAllowedByProcState = true;
            mPowerEfficient = false;
            mGraphicsPipeline = false;
            mHasBeenPowerEfficient = false;
            mHasBeenGraphicsPipeline = false;
            mShouldForcePause = false;
            mSessionId = sessionId;
            mTrackedBySF = false;
            final boolean allowed = mUidObserver.isUidForeground(mUid);
            updateHintAllowedByProcState(allowed);
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                mNativeWrapper.halCloseHintSession(mHalSessionPtr);
                throw new IllegalStateException("Client already dead", e);
            }
        }

        @VisibleForTesting
        boolean updateHintAllowedByProcState(boolean allowed) {
            synchronized (this) {
                if (allowed && !mUpdateAllowedByProcState && !mShouldForcePause) {
                    resume();
                }
                if (!allowed && mUpdateAllowedByProcState) {
                    pause();
                }
                mUpdateAllowedByProcState = allowed;
                return mUpdateAllowedByProcState;
            }
        }

        boolean isHintAllowed() {
            return mHalSessionPtr != 0 && mUpdateAllowedByProcState && !mShouldForcePause;
        }

        @Override
        public void updateTargetWorkDuration(long targetDurationNanos) {
            synchronized (this) {
                if (!isHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(targetDurationNanos > 0, "Expected"
                        + " the target duration to be greater than 0.");
                mNativeWrapper.halUpdateTargetWorkDuration(mHalSessionPtr, targetDurationNanos);
                mTargetDurationNanos = targetDurationNanos;
            }
            synchronized (mSessionSnapshotMapLock) {
                ArrayMap<Integer, AppHintSessionSnapshot> sessionSnapshots =
                        mSessionSnapshotMap.get(mUid);
                if (sessionSnapshots == null) {
                    Slogf.w(TAG, "Session snapshot map is null for uid " + mUid);
                    return;
                }
                AppHintSessionSnapshot sessionSnapshot = sessionSnapshots.get(mTag);
                if (sessionSnapshot == null) {
                    Slogf.w(TAG, "Session snapshot is null for uid " + mUid + " and tag " + mTag);
                    return;
                }
                sessionSnapshot.updateTargetDurationNs(mTargetDurationNanos);
            }
        }

        @Override
        public void reportActualWorkDuration(long[] actualDurationNanos, long[] timeStampNanos) {
            synchronized (this) {
                if (!isHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(actualDurationNanos.length != 0, "the count"
                        + " of hint durations shouldn't be 0.");
                Preconditions.checkArgument(actualDurationNanos.length == timeStampNanos.length,
                        "The length of durations and timestamps should be the same.");
                for (int i = 0; i < actualDurationNanos.length; i++) {
                    if (actualDurationNanos[i] <= 0) {
                        throw new IllegalArgumentException(
                                String.format("durations[%d]=%d should be greater than 0",
                                        i, actualDurationNanos[i]));
                    }
                }
                mNativeWrapper.halReportActualWorkDuration(mHalSessionPtr, actualDurationNanos,
                        timeStampNanos);
            }
        }

        /** TODO: consider monitor session threads and close session if any thread is dead. */
        @Override
        public void close() {
            synchronized (this) {
                if (mHalSessionPtr == 0) return;
                mNativeWrapper.halCloseHintSession(mHalSessionPtr);
                mHalSessionPtr = 0;
                try {
                    mToken.unlinkToDeath(this, 0);
                } catch (NoSuchElementException ignored) {
                    Slogf.d(TAG, "Death link does not exist for session with UID " + mUid);
                }
                if (mTrackedBySF) {
                    if (mSessionManager != null) {
                        try {
                            mSessionManager.trackedSessionsDied(new int[]{mSessionId});
                        } catch (RemoteException e) {
                            throw new IllegalStateException(
                                    "Could not communicate with SessionManager", e);
                        }
                        mTrackedBySF = false;
                    } else {
                        Slog.e(TAG, "SessionManager is null but there are tracked sessions");
                    }
                }
            }
            synchronized (mLock) {
                ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap = mActiveSessions.get(mUid);
                if (tokenMap == null) {
                    Slogf.w(TAG, "UID %d is not present in active session map", mUid);
                    return;
                }
                ArraySet<AppHintSession> sessionSet = tokenMap.get(mToken);
                if (sessionSet == null) {
                    Slogf.w(TAG, "Token %s is not present in token map", mToken.toString());
                    return;
                }
                sessionSet.remove(this);
                if (sessionSet.isEmpty()) tokenMap.remove(mToken);
                if (tokenMap.isEmpty()) mActiveSessions.remove(mUid);
            }
            synchronized (mSessionSnapshotMapLock) {
                ArrayMap<Integer, AppHintSessionSnapshot> sessionSnapshots =
                        mSessionSnapshotMap.get(mUid);
                if (sessionSnapshots == null) {
                    Slogf.w(TAG, "Session snapshot map is null for uid " + mUid);
                    return;
                }
                AppHintSessionSnapshot sessionSnapshot = sessionSnapshots.get(mTag);
                if (sessionSnapshot == null) {
                    Slogf.w(TAG, "Session snapshot is null for uid " + mUid + " and tag " + mTag);
                    return;
                }
                sessionSnapshot.updateUponSessionClose();
            }

            if (mGraphicsPipeline) {
                synchronized (mThreadsUsageObject) {
                    ArraySet<ThreadUsageTracker> threadsSet = mThreadsUsageMap.get(mUid);
                    if (threadsSet == null) {
                        Slogf.w(TAG, "Threads Set is null for uid " + mUid);
                        return;
                    }
                    // remove all tids associated with this session
                    for (int i = 0; i < threadsSet.size(); ++i) {
                        if (contains(mThreadIds, threadsSet.valueAt(i).getTid())) {
                            threadsSet.removeAt(i);
                        }
                    }
                    if (threadsSet.isEmpty()) {
                        mThreadsUsageMap.remove(mUid);
                    }
                }
            }
            if (powerhintThreadCleanup()) {
                synchronized (mNonIsolatedTidsLock) {
                    final int[] tids = getTidsInternal();
                    for (int tid : tids) {
                        if (mNonIsolatedTids.containsKey(tid)) {
                            mNonIsolatedTids.get(tid).remove(mHalSessionPtr);
                            if (mNonIsolatedTids.get(tid).isEmpty()) {
                                mNonIsolatedTids.remove(tid);
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void sendHint(@PerformanceHintManager.Session.Hint int hint) {
            synchronized (this) {
                if (!isHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(hint >= 0, "the hint ID value should be"
                        + " greater than zero.");
                mNativeWrapper.halSendHint(mHalSessionPtr, hint);
            }
        }

        @Override
        public void associateToLayers(IBinder[] layerTokens) {
            synchronized (this) {
                if (mSessionManager != null && mSessionId != null && layerTokens != null) {
                    // Sf only untracks a session when it dies
                    if (layerTokens.length > 0) {
                        mTrackedBySF = true;
                    }
                    try {
                        mSessionManager.associateSessionToLayers(mSessionId, mUid, layerTokens);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Could not communicate with SessionManager", e);
                    }
                }
            }
        }

        public void setThreads(@NonNull int[] tids) {
            setThreadsInternal(tids, true);
        }

        private void setThreadsInternal(int[] tids, boolean checkTid) {
            if (tids.length == 0) {
                throw new IllegalArgumentException("Thread id list can't be empty.");
            }


            final int callingUid = Binder.getCallingUid();
            if (mGraphicsPipeline) {
                synchronized (mThreadsUsageObject) {
                    // replace original tids with new tids
                    ArraySet<ThreadUsageTracker> threadsSet = mThreadsUsageMap.get(callingUid);
                    int graphicsPipelineThreadCount = 0;
                    if (threadsSet != null) {
                        // We count the graphics pipeline threads that are
                        // *not* in this session, since those in this session
                        // will be replaced. Then if the count plus the new tids
                        // is over max available graphics pipeline threads we raise
                        // an exception.
                        for (ThreadUsageTracker t : threadsSet) {
                            if (t.isGraphicsPipeline() && !contains(mThreadIds, t.getTid())) {
                                graphicsPipelineThreadCount++;
                            }
                        }
                        if (graphicsPipelineThreadCount + tids.length
                                > MAX_GRAPHICS_PIPELINE_THREADS_COUNT) {
                            throw new IllegalArgumentException(
                                    "Not enough available graphics pipeline threads.");
                        }
                    }
                }
            }

            synchronized (this) {
                if (mHalSessionPtr == 0) {
                    return;
                }
                if (!mUpdateAllowedByProcState) {
                    Slogf.v(TAG, "update hint not allowed, storing tids.");
                    mNewThreadIds = tids;
                    mShouldForcePause = false;
                    return;
                }
                if (checkTid) {
                    final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
                    final IntArray nonIsolated = powerhintThreadCleanup() ? new IntArray() : null;
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        final Integer invalidTid = checkTidValid(callingUid, callingTgid, tids,
                                nonIsolated);
                        if (invalidTid != null) {
                            final String errMsg = formatTidCheckErrMsg(callingUid, tids,
                                    invalidTid);
                            Slogf.w(TAG, errMsg);
                            throw new SecurityException(errMsg);
                        }
                        if (resetOnForkEnabled()) {
                            try {
                                for (int tid : tids) {
                                    int policy = Process.getThreadScheduler(tid);
                                    // If the thread is not using the default scheduling policy (SCHED_OTHER),
                                    // we don't change it.
                                    if (policy != Process.SCHED_OTHER) {
                                        continue;
                                    }
                                    // set the SCHED_RESET_ON_FORK flag.
                                    int prio = Process.getThreadPriority(tid);
                                    Process.setThreadScheduler(tid, Process.SCHED_OTHER | Process.SCHED_RESET_ON_FORK, 0);
                                    Process.setThreadPriority(tid, prio);
                                }
                            } catch (Exception e) {
                                Slog.e(TAG, "Failed to set SCHED_RESET_ON_FORK for tids "
                                        + Arrays.toString(tids), e);
                            }
                        }
                        if (powerhintThreadCleanup()) {
                            synchronized (mNonIsolatedTidsLock) {
                                for (int i = nonIsolated.size() - 1; i >= 0; i--) {
                                    mNonIsolatedTids.putIfAbsent(nonIsolated.get(i),
                                            new ArraySet<>());
                                    mNonIsolatedTids.get(nonIsolated.get(i)).add(mHalSessionPtr);
                                }
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                mNativeWrapper.halSetThreads(mHalSessionPtr, tids);

                synchronized (mThreadsUsageObject) {
                    // replace old tids with new ones
                    ArraySet<ThreadUsageTracker> threadsSet = mThreadsUsageMap.get(callingUid);
                    if (threadsSet == null) {
                        mThreadsUsageMap.put(callingUid, new ArraySet<ThreadUsageTracker>());
                        threadsSet = mThreadsUsageMap.get(callingUid);
                    }
                    for (int i = 0; i < threadsSet.size(); ++i) {
                        if (contains(mThreadIds, threadsSet.valueAt(i).getTid())) {
                            threadsSet.removeAt(i);
                        }
                    }
                    for (int tid : tids) {
                        threadsSet.add(new ThreadUsageTracker(tid, mGraphicsPipeline));
                    }
                }
                mThreadIds = tids;
                mNewThreadIds = null;
                // if the update is allowed but the session is force paused by tid clean up, then
                // it's waiting for this tid update to resume
                if (mShouldForcePause) {
                    resume();
                    mShouldForcePause = false;
                }
            }
            synchronized (mSessionSnapshotMapLock) {
                ArrayMap<Integer, AppHintSessionSnapshot> sessionSnapshots =
                        mSessionSnapshotMap.get(mUid);
                if (sessionSnapshots == null) {
                    Slogf.w(TAG, "Session snapshot map is null for uid " + mUid);
                    return;
                }
                AppHintSessionSnapshot sessionSnapshot = sessionSnapshots.get(mTag);
                if (sessionSnapshot == null) {
                    Slogf.w(TAG, "Session snapshot is null for uid " + mUid + " and tag "
                            + mTag);
                    return;
                }
                sessionSnapshot.updateThreadCount(tids.length);
            }
        }

        public int[] getThreadIds() {
            synchronized (this) {
                return Arrays.copyOf(mThreadIds, mThreadIds.length);
            }
        }

        @VisibleForTesting
        int[] getTidsInternal() {
            synchronized (this) {
                return mNewThreadIds != null ? Arrays.copyOf(mNewThreadIds, mNewThreadIds.length)
                        : Arrays.copyOf(mThreadIds, mThreadIds.length);
            }
        }

        boolean isClosed() {
            synchronized (this) {
                return mHalSessionPtr == 0;
            }
        }

        boolean isForcePaused() {
            synchronized (this) {
                return mShouldForcePause;
            }
        }
        @Override
        public void setMode(int mode, boolean enabled) {
            synchronized (this) {
                if (!isHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(mode >= 0, "the mode Id value should be"
                        + " greater than zero.");
                if (mode == SessionModes.POWER_EFFICIENCY.ordinal()) {
                    mPowerEfficient = enabled;
                } else if (mode == SessionModes.GRAPHICS_PIPELINE.ordinal()) {
                    mGraphicsPipeline = enabled;
                }
                mNativeWrapper.halSetMode(mHalSessionPtr, mode, enabled);
            }
            if (enabled) {
                if (mode == SessionModes.POWER_EFFICIENCY.ordinal()) {
                    if (!mHasBeenPowerEfficient) {
                        mHasBeenPowerEfficient = true;
                        synchronized (mSessionSnapshotMapLock) {
                            ArrayMap<Integer, AppHintSessionSnapshot> sessionSnapshots =
                                    mSessionSnapshotMap.get(mUid);
                            if (sessionSnapshots == null) {
                                Slogf.w(TAG, "Session snapshot map is null for uid " + mUid);
                                return;
                            }
                            AppHintSessionSnapshot sessionSnapshot = sessionSnapshots.get(mTag);
                            if (sessionSnapshot == null) {
                                Slogf.w(TAG, "Session snapshot is null for uid " + mUid
                                        + " and tag " + mTag);
                                return;
                            }
                            sessionSnapshot.logPowerEfficientSession();
                        }
                    }
                } else if (mode == SessionModes.GRAPHICS_PIPELINE.ordinal()) {
                    if (!mHasBeenGraphicsPipeline) {
                        mHasBeenGraphicsPipeline = true;
                        synchronized (mSessionSnapshotMapLock) {
                            ArrayMap<Integer, AppHintSessionSnapshot> sessionSnapshots =
                                    mSessionSnapshotMap.get(mUid);
                            if (sessionSnapshots == null) {
                                Slogf.w(TAG, "Session snapshot map is null for uid " + mUid);
                                return;
                            }
                            AppHintSessionSnapshot sessionSnapshot = sessionSnapshots.get(mTag);
                            if (sessionSnapshot == null) {
                                Slogf.w(TAG, "Session snapshot is null for uid " + mUid
                                        + " and tag " + mTag);
                                return;
                            }
                            sessionSnapshot.logGraphicsPipelineSession();
                        }
                    }
                }
            }
        }

        @Override
        public void reportActualWorkDuration2(WorkDuration[] workDurations) {
            synchronized (this) {
                if (!isHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(workDurations.length != 0, "the count"
                        + " of work durations shouldn't be 0.");
                for (int i = 0; i < workDurations.length; i++) {
                    validateWorkDuration(workDurations[i]);
                }
                mNativeWrapper.halReportActualWorkDuration(mHalSessionPtr, workDurations);
            }
        }

        public boolean isPowerEfficient() {
            synchronized (this) {
                return mPowerEfficient;
            }
        }

        public boolean isGraphicsPipeline() {
            synchronized (this) {
                return mGraphicsPipeline;
            }
        }

        public int getUid() {
            return mUid;
        }

        public boolean isTrackedBySf() {
            synchronized (this) {
                return mTrackedBySF;
            }
        }

        public void setTrackedBySf(boolean tracked) {
            synchronized (this) {
                mTrackedBySF = tracked;
            }
        }


        public int getTag() {
            return mTag;
        }

        public Integer getSessionId() {
            return mSessionId;
        }

        public long getTargetDurationNs() {
            synchronized (this) {
                return mTargetDurationNanos;
            }
        }

        void validateWorkDuration(WorkDuration workDuration) {
            if (DEBUG) {
                Slogf.d(TAG, "WorkDuration("
                        + workDuration.durationNanos + ", "
                        + workDuration.workPeriodStartTimestampNanos + ", "
                        + workDuration.cpuDurationNanos + ", "
                        + workDuration.gpuDurationNanos + ")");
            }

            // Allow work period start timestamp to be zero in system server side because
            // legacy API call will use zero value. It can not be estimated with the timestamp
            // the sample is received because the samples could stack up.
            if (workDuration.durationNanos <= 0) {
                throw new IllegalArgumentException(
                    TextUtils.formatSimple("Actual total duration (%d) should be greater than 0",
                            workDuration.durationNanos));
            }
            if (workDuration.workPeriodStartTimestampNanos < 0) {
                throw new IllegalArgumentException(
                    TextUtils.formatSimple(
                            "Work period start timestamp (%d) should be greater than 0",
                            workDuration.workPeriodStartTimestampNanos));
            }
            if (workDuration.cpuDurationNanos < 0) {
                throw new IllegalArgumentException(
                    TextUtils.formatSimple(
                        "Actual CPU duration (%d) should be greater than or equal to 0",
                            workDuration.cpuDurationNanos));
            }
            if (workDuration.gpuDurationNanos < 0) {
                throw new IllegalArgumentException(
                    TextUtils.formatSimple(
                        "Actual GPU duration (%d) should greater than or equal to 0",
                            workDuration.gpuDurationNanos));
            }
            if (workDuration.cpuDurationNanos
                    + workDuration.gpuDurationNanos <= 0) {
                throw new IllegalArgumentException(
                    TextUtils.formatSimple(
                        "The actual CPU duration (%d) and the actual GPU duration (%d)"
                        + " should not both be 0", workDuration.cpuDurationNanos,
                        workDuration.gpuDurationNanos));
            }
        }

        private void pause() {
            synchronized (this) {
                if (mHalSessionPtr == 0) return;
                mNativeWrapper.halPauseHintSession(mHalSessionPtr);
            }
        }

        private void resume() {
            synchronized (this) {
                if (mHalSessionPtr == 0) return;
                mNativeWrapper.halResumeHintSession(mHalSessionPtr);
                if (mNewThreadIds != null) {
                    mNativeWrapper.halSetThreads(mHalSessionPtr, mNewThreadIds);
                    mThreadIds = mNewThreadIds;
                    mNewThreadIds = null;
                }
            }
        }

        private void dump(PrintWriter pw, String prefix) {
            synchronized (this) {
                pw.println(prefix + "SessionPID: " + mPid);
                pw.println(prefix + "SessionUID: " + mUid);
                pw.println(prefix + "SessionTIDs: " + Arrays.toString(mThreadIds));
                pw.println(prefix + "SessionTargetDurationNanos: " + mTargetDurationNanos);
                pw.println(prefix + "SessionAllowedByProcState: " + mUpdateAllowedByProcState);
                pw.println(prefix + "SessionForcePaused: " + mShouldForcePause);
                pw.println(prefix + "PowerEfficient: " + (mPowerEfficient ? "true" : "false"));
                pw.println(prefix + "GraphicsPipeline: " + (mGraphicsPipeline ? "true" : "false"));
            }
        }

        @Override
        public void binderDied() {
            close();
        }

    }
}
