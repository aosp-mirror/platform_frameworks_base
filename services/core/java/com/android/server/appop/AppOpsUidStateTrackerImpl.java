/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.ProcessCapability;
import static android.app.AppOpsManager.MIN_PRIORITY_UID_STATE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_TAKE_AUDIO_FOCUS;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.UID_STATE_MAX_LAST_NON_RESTRICTED;
import static android.app.AppOpsManager.UID_STATE_NONEXISTENT;
import static android.app.AppOpsManager.UID_STATE_TOP;
import static android.permission.flags.Flags.finishRunningOpsForKilledPackages;

import static com.android.server.appop.AppOpsUidStateTracker.processStateToUidState;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

class AppOpsUidStateTrackerImpl implements AppOpsUidStateTracker {

    private static final String LOG_TAG = AppOpsUidStateTrackerImpl.class.getSimpleName();

    private final DelayableExecutor mExecutor;
    private final Clock mClock;
    private ActivityManagerInternal mActivityManagerInternal;
    private AppOpsService.Constants mConstants;

    private SparseIntArray mUidStates = new SparseIntArray();
    private SparseIntArray mPendingUidStates = new SparseIntArray();
    private SparseIntArray mCapability = new SparseIntArray();
    private SparseIntArray mPendingCapability = new SparseIntArray();
    private SparseBooleanArray mAppWidgetVisible = new SparseBooleanArray();
    private SparseBooleanArray mPendingAppWidgetVisible = new SparseBooleanArray();
    private SparseLongArray mPendingCommitTime = new SparseLongArray();
    private SparseBooleanArray mPendingGone = new SparseBooleanArray();

    private ArrayMap<UidStateChangedCallback, Executor>
            mUidStateChangedCallbacks = new ArrayMap<>();

    private final EventLog mEventLog;

    @VisibleForTesting
    interface DelayableExecutor extends Executor {

        void execute(Runnable runnable);

        void executeDelayed(Runnable runnable, long delay);
    }

    AppOpsUidStateTrackerImpl(ActivityManagerInternal activityManagerInternal,
            Handler handler, Executor lockingExecutor, Clock clock,
            AppOpsService.Constants constants) {

        this(activityManagerInternal, new DelayableExecutor() {
            @Override
            public void execute(Runnable runnable) {
                handler.post(() -> lockingExecutor.execute(runnable));
            }

            @Override
            public void executeDelayed(Runnable runnable, long delay) {
                handler.postDelayed(() -> lockingExecutor.execute(runnable), delay);
            }
        }, clock, constants, handler.getLooper().getThread());
    }

    @VisibleForTesting
    AppOpsUidStateTrackerImpl(ActivityManagerInternal activityManagerInternal,
            DelayableExecutor executor, Clock clock, AppOpsService.Constants constants,
            Thread executorThread) {
        mActivityManagerInternal = activityManagerInternal;
        mExecutor = executor;
        mClock = clock;
        mConstants = constants;

        mEventLog = new EventLog(executor, executorThread);
    }

    @Override
    public int getUidState(int uid) {
        return getUidStateLocked(uid);
    }

    private int getUidStateLocked(int uid) {
        updateUidPendingStateIfNeeded(uid);
        return mUidStates.get(uid, MIN_PRIORITY_UID_STATE);
    }

    @Override
    public int evalMode(int uid, int code, int mode) {
        if (mode != MODE_FOREGROUND) {
            return mode;
        }

        int uidState = getUidState(uid);
        int uidCapability = getUidCapability(uid);
        int result = evalModeInternal(uid, code, uidState, uidCapability);

        mEventLog.logEvalForegroundMode(uid, uidState, uidCapability, code, result);
        return result;
    }

    private int evalModeInternal(int uid, int code, int uidState, int uidCapability) {
        if (getUidAppWidgetVisible(uid) || mActivityManagerInternal.isPendingTopUid(uid)
                || mActivityManagerInternal.isTempAllowlistedForFgsWhileInUse(uid)) {
            return MODE_ALLOWED;
        }

        int opCapability = getOpCapability(code);
        if (opCapability != PROCESS_CAPABILITY_NONE) {
            if ((uidCapability & opCapability) == 0) {
                return MODE_IGNORED;
            } else {
                return MODE_ALLOWED;
            }
        }

        if (uidState > AppOpsManager.resolveFirstUnrestrictedUidState(code)) {
            return MODE_IGNORED;
        }

        return MODE_ALLOWED;
    }

    private int getOpCapability(int opCode) {
        switch (opCode) {
            case AppOpsManager.OP_FINE_LOCATION:
            case AppOpsManager.OP_COARSE_LOCATION:
            case AppOpsManager.OP_MONITOR_LOCATION:
            case AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION:
                return PROCESS_CAPABILITY_FOREGROUND_LOCATION;
            case OP_CAMERA:
                return PROCESS_CAPABILITY_FOREGROUND_CAMERA;
            case OP_RECORD_AUDIO:
            case OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO:
                return PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
            case OP_TAKE_AUDIO_FOCUS:
                return PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
            default:
                return PROCESS_CAPABILITY_NONE;
        }
    }

    @Override
    public boolean isUidInForeground(int uid) {
        return evalMode(uid, OP_NONE, MODE_FOREGROUND) == MODE_ALLOWED;
    }

    @Override
    public void addUidStateChangedCallback(Executor executor, UidStateChangedCallback callback) {
        if (mUidStateChangedCallbacks.containsKey(callback)) {
            throw new IllegalStateException("Callback is already registered.");
        }

        mUidStateChangedCallbacks.put(callback, executor);
    }

    @Override
    public void removeUidStateChangedCallback(UidStateChangedCallback callback) {
        if (!mUidStateChangedCallbacks.containsKey(callback)) {
            throw new IllegalStateException("Callback is not registered.");
        }
        mUidStateChangedCallbacks.remove(callback);
    }

    @Override
    public void updateAppWidgetVisibility(SparseArray<String> uidPackageNames, boolean visible) {
        int numUids = uidPackageNames.size();
        for (int i = 0; i < numUids; i++) {
            int uid = uidPackageNames.keyAt(i);
            mPendingAppWidgetVisible.put(uid, visible);

            commitUidPendingState(uid);
        }
    }

    @Override
    public void updateUidProcState(int uid, int procState, int capability) {
        int uidState = processStateToUidState(procState);

        int prevUidState = mUidStates.get(uid, AppOpsManager.MIN_PRIORITY_UID_STATE);
        int prevCapability = mCapability.get(uid, PROCESS_CAPABILITY_NONE);
        int pendingUidState = mPendingUidStates.get(uid, MIN_PRIORITY_UID_STATE);
        int pendingCapability = mPendingCapability.get(uid, PROCESS_CAPABILITY_NONE);
        long pendingStateCommitTime = mPendingCommitTime.get(uid, 0);
        if ((pendingStateCommitTime == 0
                && (uidState != prevUidState || capability != prevCapability))
                || (pendingStateCommitTime != 0
                && (uidState != pendingUidState || capability != pendingCapability))) {

            // If this process update results in a capability or uid state change, log it. It's
            // not interesting otherwise.
            mEventLog.logUpdateUidProcState(uid, procState, capability);
            mPendingUidStates.put(uid, uidState);
            mPendingCapability.put(uid, capability);

            if (procState == PROCESS_STATE_NONEXISTENT) {
                mPendingGone.put(uid, true);
                commitUidPendingState(uid);
            } else if (uidState < prevUidState
                    || (uidState <= UID_STATE_MAX_LAST_NON_RESTRICTED
                    && prevUidState > UID_STATE_MAX_LAST_NON_RESTRICTED)) {
                // We are moving to a more important state, or the new state may be in the
                // foreground and the old state is in the background, then always do it
                // immediately.
                commitUidPendingState(uid);
            } else if (uidState == prevUidState && capability != prevCapability) {
                // No change on process state, but process capability has changed.
                commitUidPendingState(uid);
            } else if (uidState <= UID_STATE_MAX_LAST_NON_RESTRICTED) {
                // We are moving to a less important state, but it doesn't cross the restriction
                // threshold.
                commitUidPendingState(uid);
            } else if (pendingStateCommitTime == 0) {
                // We are moving to a less important state for the first time,
                // delay the application for a bit.
                final long settleTime;
                if (prevUidState <= UID_STATE_TOP) {
                    settleTime = mConstants.TOP_STATE_SETTLE_TIME;
                } else if (prevUidState <= UID_STATE_FOREGROUND_SERVICE) {
                    settleTime = mConstants.FG_SERVICE_STATE_SETTLE_TIME;
                } else {
                    settleTime = mConstants.BG_STATE_SETTLE_TIME;
                }
                final long commitTime = mClock.elapsedRealtime() + settleTime;
                mPendingCommitTime.put(uid, commitTime);

                mExecutor.executeDelayed(PooledLambda.obtainRunnable(
                                AppOpsUidStateTrackerImpl::updateUidPendingStateIfNeeded, this,
                                uid), settleTime + 1);
            }
        }
    }

    @Override
    public void dumpUidState(PrintWriter pw, int uid, long nowElapsed) {
        int state = mUidStates.get(uid, MIN_PRIORITY_UID_STATE);
        // if no pendingState set to state to suppress output
        int pendingState = mPendingUidStates.get(uid, state);
        pw.print("    state=");
        pw.println(AppOpsManager.getUidStateName(state));
        if (state != pendingState) {
            pw.print("    pendingState=");
            pw.println(AppOpsManager.getUidStateName(pendingState));
        }
        int capability = mCapability.get(uid, PROCESS_CAPABILITY_NONE);
        // if no pendingCapability set to capability to suppress output
        int pendingCapability = mPendingCapability.get(uid, capability);
        pw.print("    capability=");
        ActivityManager.printCapabilitiesFull(pw, capability);
        pw.println();
        if (capability != pendingCapability) {
            pw.print("    pendingCapability=");
            ActivityManager.printCapabilitiesFull(pw, pendingCapability);
            pw.println();
        }
        boolean appWidgetVisible = mAppWidgetVisible.get(uid, false);
        // if no pendingAppWidgetVisible set to appWidgetVisible to suppress output
        boolean pendingAppWidgetVisible = mPendingAppWidgetVisible.get(uid, appWidgetVisible);
        pw.print("    appWidgetVisible=");
        pw.println(appWidgetVisible);
        if (appWidgetVisible != pendingAppWidgetVisible) {
            pw.print("    pendingAppWidgetVisible=");
            pw.println(pendingAppWidgetVisible);
        }
        long pendingStateCommitTime = mPendingCommitTime.get(uid, 0);
        if (pendingStateCommitTime != 0) {
            pw.print("    pendingStateCommitTime=");
            TimeUtils.formatDuration(pendingStateCommitTime, nowElapsed, pw);
            pw.println();
        }
    }

    @Override
    public void dumpEvents(PrintWriter pw) {
        mEventLog.dumpEvents(pw);
    }

    private void updateUidPendingStateIfNeeded(int uid) {
        updateUidPendingStateIfNeededLocked(uid);
    }

    private void updateUidPendingStateIfNeededLocked(int uid) {
        long pendingCommitTime = mPendingCommitTime.get(uid, 0);
        if (pendingCommitTime != 0) {
            long currentTime = mClock.elapsedRealtime();
            if (currentTime < mPendingCommitTime.get(uid)) {
                return;
            }
            commitUidPendingState(uid);
        }
    }

    private void commitUidPendingState(int uid) {
        int pendingUidState = mPendingUidStates.get(uid,
                mUidStates.get(uid, MIN_PRIORITY_UID_STATE));
        int pendingCapability = mPendingCapability.get(uid,
                mCapability.get(uid, PROCESS_CAPABILITY_NONE));
        boolean pendingAppWidgetVisible = mPendingAppWidgetVisible.get(uid,
                mAppWidgetVisible.get(uid, false));

        int uidState = mUidStates.get(uid, MIN_PRIORITY_UID_STATE);
        int capability = mCapability.get(uid, PROCESS_CAPABILITY_NONE);
        boolean appWidgetVisible = mAppWidgetVisible.get(uid, false);

        boolean foregroundChange = uidState <= UID_STATE_MAX_LAST_NON_RESTRICTED
                != pendingUidState <= UID_STATE_MAX_LAST_NON_RESTRICTED
                || capability != pendingCapability
                || appWidgetVisible != pendingAppWidgetVisible;

        if (uidState != pendingUidState
                || capability != pendingCapability
                || appWidgetVisible != pendingAppWidgetVisible) {

            if (foregroundChange) {
                // To save on memory usage, log only interesting changes.
                mEventLog.logCommitUidState(uid, pendingUidState, pendingCapability,
                        pendingAppWidgetVisible, appWidgetVisible != pendingAppWidgetVisible);
            }

            for (int i = 0; i < mUidStateChangedCallbacks.size(); i++) {
                UidStateChangedCallback cb = mUidStateChangedCallbacks.keyAt(i);
                Executor executor = mUidStateChangedCallbacks.valueAt(i);

                executor.execute(PooledLambda.obtainRunnable(
                        UidStateChangedCallback::onUidStateChanged, cb, uid, pendingUidState,
                        foregroundChange));
            }
        }

        if (mPendingGone.get(uid, false)) {
            mUidStates.delete(uid);
            mCapability.delete(uid);
            mAppWidgetVisible.delete(uid);
            mPendingGone.delete(uid);
            if (finishRunningOpsForKilledPackages()) {
                for (int i = 0; i < mUidStateChangedCallbacks.size(); i++) {
                    UidStateChangedCallback cb = mUidStateChangedCallbacks.keyAt(i);
                    Executor executor = mUidStateChangedCallbacks.valueAt(i);

                    executor.execute(PooledLambda.obtainRunnable(
                            UidStateChangedCallback::onUidStateChanged, cb, uid,
                            UID_STATE_NONEXISTENT, foregroundChange));
                }
            }
        } else {
            mUidStates.put(uid, pendingUidState);
            mCapability.put(uid, pendingCapability);
            mAppWidgetVisible.put(uid, pendingAppWidgetVisible);
        }

        mPendingUidStates.delete(uid);
        mPendingCapability.delete(uid);
        mPendingAppWidgetVisible.delete(uid);
        mPendingCommitTime.delete(uid);
    }

    private @ProcessCapability int getUidCapability(int uid) {
        return mCapability.get(uid, ActivityManager.PROCESS_CAPABILITY_NONE);
    }

    private boolean getUidAppWidgetVisible(int uid) {
        return mAppWidgetVisible.get(uid, false);
    }

    private static class EventLog {

        // Memory usage: 16 * size bytes
        private static final int UPDATE_UID_PROC_STATE_LOG_MAX_SIZE = 200;
        // Memory usage: 20 * size bytes
        private static final int COMMIT_UID_STATE_LOG_MAX_SIZE = 200;
        // Memory usage: 24 * size bytes
        private static final int EVAL_FOREGROUND_MODE_MAX_SIZE = 200;

        private static final int APP_WIDGET_VISIBLE = 1 << 0;
        private static final int APP_WIDGET_VISIBLE_CHANGED = 1 << 1;

        private final DelayableExecutor mExecutor;
        private final Thread mExecutorThread;

        private int[][] mUpdateUidProcStateLog = new int[UPDATE_UID_PROC_STATE_LOG_MAX_SIZE][3];
        private long[] mUpdateUidProcStateLogTimestamps =
                new long[UPDATE_UID_PROC_STATE_LOG_MAX_SIZE];
        private int mUpdateUidProcStateLogSize = 0;
        private int mUpdateUidProcStateLogHead = 0;

        private int[][] mCommitUidStateLog = new int[COMMIT_UID_STATE_LOG_MAX_SIZE][4];
        private long[] mCommitUidStateLogTimestamps = new long[COMMIT_UID_STATE_LOG_MAX_SIZE];
        private int mCommitUidStateLogSize = 0;
        private int mCommitUidStateLogHead = 0;

        private int[][] mEvalForegroundModeLog = new int[EVAL_FOREGROUND_MODE_MAX_SIZE][5];
        private long[] mEvalForegroundModeLogTimestamps = new long[EVAL_FOREGROUND_MODE_MAX_SIZE];
        private int mEvalForegroundModeLogSize = 0;
        private int mEvalForegroundModeLogHead = 0;

        EventLog(DelayableExecutor executor, Thread executorThread) {
            mExecutor = executor;
            mExecutorThread = executorThread;
        }

        void logUpdateUidProcState(int uid, int procState, int capability) {
            if (UPDATE_UID_PROC_STATE_LOG_MAX_SIZE == 0) {
                return;
            }
            mExecutor.execute(PooledLambda.obtainRunnable(EventLog::logUpdateUidProcStateAsync,
                    this, System.currentTimeMillis(), uid, procState, capability));
        }

        void logUpdateUidProcStateAsync(long timestamp, int uid, int procState, int capability) {
            int idx = (mUpdateUidProcStateLogHead + mUpdateUidProcStateLogSize)
                    % UPDATE_UID_PROC_STATE_LOG_MAX_SIZE;
            if (mUpdateUidProcStateLogSize == UPDATE_UID_PROC_STATE_LOG_MAX_SIZE) {
                mUpdateUidProcStateLogHead =
                        (mUpdateUidProcStateLogHead + 1) % UPDATE_UID_PROC_STATE_LOG_MAX_SIZE;
            } else {
                mUpdateUidProcStateLogSize++;
            }

            mUpdateUidProcStateLog[idx][0] = uid;
            mUpdateUidProcStateLog[idx][1] = procState;
            mUpdateUidProcStateLog[idx][2] = capability;
            mUpdateUidProcStateLogTimestamps[idx] = timestamp;
        }

        void logCommitUidState(int uid, int uidState, int capability, boolean appWidgetVisible,
                boolean appWidgetVisibleChanged) {
            if (COMMIT_UID_STATE_LOG_MAX_SIZE == 0) {
                return;
            }
            mExecutor.execute(PooledLambda.obtainRunnable(EventLog::logCommitUidStateAsync,
                    this, System.currentTimeMillis(), uid, uidState, capability, appWidgetVisible,
                    appWidgetVisibleChanged));
        }

        void logCommitUidStateAsync(long timestamp, int uid, int uidState, int capability,
                boolean appWidgetVisible, boolean appWidgetVisibleChanged) {
            int idx = (mCommitUidStateLogHead + mCommitUidStateLogSize)
                    % COMMIT_UID_STATE_LOG_MAX_SIZE;
            if (mCommitUidStateLogSize == COMMIT_UID_STATE_LOG_MAX_SIZE) {
                mCommitUidStateLogHead =
                        (mCommitUidStateLogHead + 1) % COMMIT_UID_STATE_LOG_MAX_SIZE;
            } else {
                mCommitUidStateLogSize++;
            }

            mCommitUidStateLog[idx][0] = uid;
            mCommitUidStateLog[idx][1] = uidState;
            mCommitUidStateLog[idx][2] = capability;
            mCommitUidStateLog[idx][3] = 0;
            if (appWidgetVisible) {
                mCommitUidStateLog[idx][3] += APP_WIDGET_VISIBLE;
            }
            if (appWidgetVisibleChanged) {
                mCommitUidStateLog[idx][3] += APP_WIDGET_VISIBLE_CHANGED;
            }
            mCommitUidStateLogTimestamps[idx] = timestamp;
        }

        void logEvalForegroundMode(int uid, int uidState, int capability, int code, int result) {
            if (EVAL_FOREGROUND_MODE_MAX_SIZE == 0) {
                return;
            }
            mExecutor.execute(PooledLambda.obtainRunnable(EventLog::logEvalForegroundModeAsync,
                    this, System.currentTimeMillis(), uid, uidState, capability, code, result));
        }

        void logEvalForegroundModeAsync(long timestamp, int uid, int uidState, int capability,
                int code, int result) {
            int idx = (mEvalForegroundModeLogHead + mEvalForegroundModeLogSize)
                    % EVAL_FOREGROUND_MODE_MAX_SIZE;
            if (mEvalForegroundModeLogSize == EVAL_FOREGROUND_MODE_MAX_SIZE) {
                mEvalForegroundModeLogHead =
                        (mEvalForegroundModeLogHead + 1) % EVAL_FOREGROUND_MODE_MAX_SIZE;
            } else {
                mEvalForegroundModeLogSize++;
            }

            mEvalForegroundModeLog[idx][0] = uid;
            mEvalForegroundModeLog[idx][1] = uidState;
            mEvalForegroundModeLog[idx][2] = capability;
            mEvalForegroundModeLog[idx][3] = code;
            mEvalForegroundModeLog[idx][4] = result;
            mEvalForegroundModeLogTimestamps[idx] = timestamp;
        }

        void dumpEvents(PrintWriter pw) {
            int updateIdx = 0;
            int commitIdx = 0;
            int evalIdx = 0;

            while (updateIdx < mUpdateUidProcStateLogSize
                    || commitIdx < mCommitUidStateLogSize
                    || evalIdx < mEvalForegroundModeLogSize) {
                int updatePtr = 0;
                int commitPtr = 0;
                int evalPtr = 0;
                if (UPDATE_UID_PROC_STATE_LOG_MAX_SIZE != 0) {
                    updatePtr = (mUpdateUidProcStateLogHead + updateIdx)
                            % UPDATE_UID_PROC_STATE_LOG_MAX_SIZE;
                }
                if (COMMIT_UID_STATE_LOG_MAX_SIZE != 0) {
                    commitPtr = (mCommitUidStateLogHead + commitIdx)
                            % COMMIT_UID_STATE_LOG_MAX_SIZE;
                }
                if (EVAL_FOREGROUND_MODE_MAX_SIZE != 0) {
                    evalPtr = (mEvalForegroundModeLogHead + evalIdx)
                            % EVAL_FOREGROUND_MODE_MAX_SIZE;
                }

                long aTimestamp = updateIdx < mUpdateUidProcStateLogSize
                        ? mUpdateUidProcStateLogTimestamps[updatePtr] : Long.MAX_VALUE;
                long bTimestamp = commitIdx < mCommitUidStateLogSize
                        ? mCommitUidStateLogTimestamps[commitPtr] : Long.MAX_VALUE;
                long cTimestamp = evalIdx < mEvalForegroundModeLogSize
                        ? mEvalForegroundModeLogTimestamps[evalPtr] : Long.MAX_VALUE;

                if (aTimestamp <= bTimestamp && aTimestamp <= cTimestamp) {
                    dumpUpdateUidProcState(pw, updatePtr);
                    updateIdx++;
                } else if (bTimestamp <= cTimestamp) {
                    dumpCommitUidState(pw, commitPtr);
                    commitIdx++;
                } else {
                    dumpEvalForegroundMode(pw, evalPtr);
                    evalIdx++;
                }
            }
        }

        void dumpUpdateUidProcState(PrintWriter pw, int idx) {
            long timestamp = mUpdateUidProcStateLogTimestamps[idx];
            int uid = mUpdateUidProcStateLog[idx][0];
            int procState = mUpdateUidProcStateLog[idx][1];
            int capability = mUpdateUidProcStateLog[idx][2];

            TimeUtils.dumpTime(pw, timestamp);

            pw.print(" UPDATE_UID_PROC_STATE");

            pw.print(" uid=");
            pw.print(String.format("%-8d", uid));

            pw.print(" procState=");
            pw.print(String.format("%-30s", ActivityManager.procStateToString(procState)));

            pw.print(" capability=");
            pw.print(ActivityManager.getCapabilitiesSummary(capability) + " ");

            pw.println();
        }

        void dumpCommitUidState(PrintWriter pw, int idx) {
            long timestamp = mCommitUidStateLogTimestamps[idx];
            int uid = mCommitUidStateLog[idx][0];
            int uidState = mCommitUidStateLog[idx][1];
            int capability = mCommitUidStateLog[idx][2];
            boolean appWidgetVisible = (mCommitUidStateLog[idx][3] & APP_WIDGET_VISIBLE) != 0;
            boolean appWidgetVisibleChanged =
                    (mCommitUidStateLog[idx][3] & APP_WIDGET_VISIBLE_CHANGED) != 0;

            TimeUtils.dumpTime(pw, timestamp);

            pw.print(" COMMIT_UID_STATE     ");

            pw.print(" uid=");
            pw.print(String.format("%-8d", uid));

            pw.print(" uidState=");
            pw.print(String.format("%-30s", AppOpsManager.uidStateToString(uidState)));

            pw.print(" capability=");
            pw.print(ActivityManager.getCapabilitiesSummary(capability) + " ");

            pw.print(" appWidgetVisible=");
            pw.print(appWidgetVisible);

            if (appWidgetVisibleChanged) {
                pw.print(" (changed)");
            }

            pw.println();
        }

        void dumpEvalForegroundMode(PrintWriter pw, int idx) {
            long timestamp = mEvalForegroundModeLogTimestamps[idx];
            int uid = mEvalForegroundModeLog[idx][0];
            int uidState = mEvalForegroundModeLog[idx][1];
            int capability = mEvalForegroundModeLog[idx][2];
            int code = mEvalForegroundModeLog[idx][3];
            int result = mEvalForegroundModeLog[idx][4];

            TimeUtils.dumpTime(pw, timestamp);

            pw.print(" EVAL_FOREGROUND_MODE ");

            pw.print(" uid=");
            pw.print(String.format("%-8d", uid));

            pw.print(" uidState=");
            pw.print(String.format("%-30s", AppOpsManager.uidStateToString(uidState)));

            pw.print(" capability=");
            pw.print(ActivityManager.getCapabilitiesSummary(capability) + " ");

            pw.print(" code=");
            pw.print(String.format("%-20s", AppOpsManager.opToName(code)));

            pw.print(" result=");
            pw.print(AppOpsManager.modeToName(result));

            pw.println();
        }
    }
}
