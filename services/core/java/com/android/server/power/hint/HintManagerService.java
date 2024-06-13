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
import android.hardware.power.IPower;
import android.hardware.power.SessionConfig;
import android.hardware.power.SessionTag;
import android.hardware.power.WorkDuration;
import android.os.Binder;
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
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
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


    @VisibleForTesting final long mHintSessionPreferredRate;

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

    /** Lock to protect mActiveSessions and the UidObserver. */
    private final Object mLock = new Object();

    /** Lock to protect mChannelMap. */
    private final Object mChannelMapLock = new Object();

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
    private final PackageManager mPackageManager;

    private static final String PROPERTY_SF_ENABLE_CPU_HINT = "debug.sf.enable_adpf_cpu_hint";
    private static final String PROPERTY_HWUI_ENABLE_HINT_MANAGER = "debug.hwui.use_hint_manager";

    @VisibleForTesting final IHintManager.Stub mService = new BinderService();

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
        mNativeWrapper = injector.createNativeWrapper();
        mNativeWrapper.halInit();
        mHintSessionPreferredRate = mNativeWrapper.halGetHintSessionPreferredRate();
        mUidObserver = new MyUidObserver();
        mAmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mPowerHal = injector.createIPower();
        mPowerHalVersion = 0;
        if (mPowerHal != null) {
            try {
                mPowerHalVersion = mPowerHal.getInterfaceVersion();
            } catch (RemoteException e) {
                throw new IllegalStateException("Could not contact PowerHAL!", e);
            }
        }
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

    private boolean isHalSupported() {
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
                    isHwuiHintManagerEnabled));
        }
        return android.app.StatsManager.PULL_SUCCESS;
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
                    if (powerhintThreadCleanup()) {
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
                try  {
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
    private Integer checkTidValid(int uid, int tgid, int [] tids, IntArray nonIsolated) {
        // Make sure all tids belongs to the same UID (including isolated UID),
        // tids can belong to different application processes.
        List<Integer> isolatedPids = null;
        for (int i = 0; i < tids.length; i++) {
            int tid = tids[i];
            final String[] procStatusKeys = new String[] {
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

    @VisibleForTesting
    final class BinderService extends IHintManager.Stub {
        @Override
        public IHintSession createHintSessionWithConfig(@NonNull IBinder token,
                @NonNull int[] tids, long durationNanos, @SessionTag int tag,
                @Nullable SessionConfig config) {
            if (!isHalSupported()) {
                throw new UnsupportedOperationException("PowerHAL is not supported!");
            }

            java.util.Objects.requireNonNull(token);
            java.util.Objects.requireNonNull(tids);
            Preconditions.checkArgument(tids.length != 0, "tids should"
                    + " not be empty.");

            final int callingUid = Binder.getCallingUid();
            final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
            final long identity = Binder.clearCallingIdentity();
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

                if (adpfSessionTag() && tag == SessionTag.APP) {
                    // If the category of the app is a game,
                    // we change the session tag to SessionTag.GAME
                    // as it was not previously classified
                    switch (getUidApplicationCategory(callingUid)) {
                        case ApplicationInfo.CATEGORY_GAME:
                            tag = SessionTag.GAME;
                            break;
                        case ApplicationInfo.CATEGORY_UNDEFINED:
                            // We use CATEGORY_UNDEFINED to filter the case when
                            // PackageManager.NameNotFoundException is caught,
                            // which should not happen.
                            tag = SessionTag.APP;
                            break;
                        default:
                            tag = SessionTag.APP;
                    }
                }

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

                final long sessionId = config != null ? config.id : halSessionPtr;
                logPerformanceHintSessionAtom(
                        callingUid, sessionId, durationNanos, tids, tag);

                synchronized (mLock) {
                    AppHintSession hs = new AppHintSession(callingUid, callingTgid, tids, token,
                            halSessionPtr, durationNanos);
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
                    return hs;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public ChannelConfig getSessionChannel(IBinder token) {
            if (mPowerHalVersion < 5 || !adpfUseFmqChannel()) {
                return null;
            }
            java.util.Objects.requireNonNull(token);
            final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
            final int callingUid = Binder.getCallingUid();
            ChannelItem item = getOrCreateMappedChannelItem(callingTgid, callingUid, token);
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
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }
            pw.println("HintSessionPreferredRate: " + mHintSessionPreferredRate);
            pw.println("HAL Support: " + isHalSupported());
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
        protected int[] mThreadIds;
        protected final IBinder mToken;
        protected long mHalSessionPtr;
        protected long mTargetDurationNanos;
        protected boolean mUpdateAllowedByProcState;
        protected int[] mNewThreadIds;
        protected boolean mPowerEfficient;
        protected boolean mShouldForcePause;

        private enum SessionModes {
            POWER_EFFICIENCY,
        };

        protected AppHintSession(
                int uid, int pid, int[] threadIds, IBinder token,
                long halSessionPtr, long durationNanos) {
            mUid = uid;
            mPid = pid;
            mToken = token;
            mThreadIds = threadIds;
            mHalSessionPtr = halSessionPtr;
            mTargetDurationNanos = durationNanos;
            mUpdateAllowedByProcState = true;
            mPowerEfficient = false;
            mShouldForcePause = false;
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
                if (allowed && !mUpdateAllowedByProcState && !mShouldForcePause) resume();
                if (!allowed && mUpdateAllowedByProcState) pause();
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

        public void setThreads(@NonNull int[] tids) {
            setThreadsInternal(tids, true);
        }

        private void setThreadsInternal(int[] tids, boolean checkTid) {
            if (tids.length == 0) {
                throw new IllegalArgumentException("Thread id list can't be empty.");
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
                    final int callingUid = Binder.getCallingUid();
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
                mThreadIds = tids;
                mNewThreadIds = null;
                // if the update is allowed but the session is force paused by tid clean up, then
                // it's waiting for this tid update to resume
                if (mShouldForcePause) {
                    resume();
                    mShouldForcePause = false;
                }
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
                }
                mNativeWrapper.halSetMode(mHalSessionPtr, mode, enabled);
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
            }
        }

        @Override
        public void binderDied() {
            close();
        }

    }
}
