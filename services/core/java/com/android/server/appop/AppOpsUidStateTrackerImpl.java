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

import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.ProcessCapability;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.UID_STATE_CACHED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.UID_STATE_MAX_LAST_NON_RESTRICTED;
import static android.app.AppOpsManager.UID_STATE_TOP;

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

import com.android.internal.os.Clock;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;

class AppOpsUidStateTrackerImpl implements AppOpsUidStateTracker {

    private static final String LOG_TAG = AppOpsUidStateTrackerImpl.class.getSimpleName();

    private final Handler mHandler;
    private final Clock mClock;
    private ActivityManagerInternal mActivityManagerInternal;
    private AppOpsService.Constants mConstants;

    private SparseIntArray mUidStates = new SparseIntArray();
    private SparseIntArray mPendingUidStates = new SparseIntArray();
    private SparseIntArray mCapability = new SparseIntArray();
    private SparseIntArray mPendingCapability = new SparseIntArray();
    private SparseBooleanArray mVisibleAppWidget = new SparseBooleanArray();
    private SparseBooleanArray mPendingVisibleAppWidget = new SparseBooleanArray();
    private SparseLongArray mPendingCommitTime = new SparseLongArray();

    private ArrayMap<UidStateChangedCallback, Handler> mUidStateChangedCallbacks = new ArrayMap<>();

    AppOpsUidStateTrackerImpl(ActivityManagerInternal activityManagerInternal,
            Handler handler, Clock clock, AppOpsService.Constants constants) {
        mActivityManagerInternal = activityManagerInternal;
        mHandler = handler;
        mClock = clock;
        mConstants = constants;
    }

    @Override
    public int getUidState(int uid) {
        return getUidStateLocked(uid);
    }

    private int getUidStateLocked(int uid) {
        updateUidPendingStateIfNeeded(uid);
        return mUidStates.get(uid, UID_STATE_CACHED);
    }

    @Override
    public int evalMode(int uid, int code, int mode) {
        if (mode != AppOpsManager.MODE_FOREGROUND) {
            return mode;
        }

        int uidStateValue;
        int capability;
        boolean visibleAppWidget;
        boolean pendingTop;
        boolean tempAllowlist;
        uidStateValue = getUidState(uid);
        capability = getUidCapability(uid);
        visibleAppWidget = getUidVisibleAppWidget(uid);
        pendingTop = mActivityManagerInternal.isPendingTopUid(uid);
        tempAllowlist = mActivityManagerInternal.isTempAllowlistedForFgsWhileInUse(uid);

        return evalMode(uidStateValue, code, mode, capability, visibleAppWidget, pendingTop,
                tempAllowlist);
    }

    private static int evalMode(int uidState, int code, int mode, int capability,
            boolean appWidgetVisible, boolean pendingTop, boolean tempAllowlist) {
        if (mode != AppOpsManager.MODE_FOREGROUND) {
            return mode;
        }

        if (appWidgetVisible || pendingTop || tempAllowlist) {
            return MODE_ALLOWED;
        }

        switch (code) {
            case AppOpsManager.OP_FINE_LOCATION:
            case AppOpsManager.OP_COARSE_LOCATION:
            case AppOpsManager.OP_MONITOR_LOCATION:
            case AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION:
                if ((capability & PROCESS_CAPABILITY_FOREGROUND_LOCATION) == 0) {
                    return MODE_IGNORED;
                } else {
                    return MODE_ALLOWED;
                }
            case OP_CAMERA:
                if ((capability & PROCESS_CAPABILITY_FOREGROUND_CAMERA) == 0) {
                    return MODE_IGNORED;
                } else {
                    return MODE_ALLOWED;
                }
            case OP_RECORD_AUDIO:
                if ((capability & PROCESS_CAPABILITY_FOREGROUND_MICROPHONE) == 0) {
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

    @Override
    public void addUidStateChangedCallback(Handler handler, UidStateChangedCallback callback) {
        if (mUidStateChangedCallbacks.containsKey(callback)) {
            throw new IllegalStateException("Callback is already registered.");
        }
        mUidStateChangedCallbacks.put(callback, handler);
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
            mPendingVisibleAppWidget.put(uid, visible);

            commitUidPendingState(uid);
        }
    }

    @Override
    public void updateUidProcState(int uid, int procState, int capability) {
        if (procState == PROCESS_STATE_NONEXISTENT) {
            mUidStates.delete(uid);
            mPendingUidStates.delete(uid);
            mCapability.delete(uid);
            mPendingCapability.delete(uid);
            mVisibleAppWidget.delete(uid);
            mPendingVisibleAppWidget.delete(uid);
            mPendingCommitTime.delete(uid);
            return;
        }

        int uidState = processStateToUidState(procState);

        int prevUidState = mUidStates.get(uid, AppOpsManager.MIN_PRIORITY_UID_STATE);
        int prevCapability = mCapability.get(uid, PROCESS_CAPABILITY_NONE);
        long pendingStateCommitTime = mPendingCommitTime.get(uid, 0);
        if (uidState != prevUidState || capability != prevCapability) {
            mPendingUidStates.put(uid, uidState);
            mPendingCapability.put(uid, capability);

            if (uidState < prevUidState
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

                mHandler.sendMessageDelayed(PooledLambda.obtainMessage(
                                AppOpsUidStateTrackerImpl::updateUidPendingStateIfNeeded, this,
                                uid), settleTime + 1);
            }
        }
    }

    @Override
    public void dumpUidState(PrintWriter pw, int uid, long nowElapsed) {
        int state = mUidStates.get(uid, UID_STATE_CACHED);
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
        boolean appWidgetVisible = mVisibleAppWidget.get(uid, false);
        // if no pendingAppWidgetVisible set to appWidgetVisible to suppress output
        boolean pendingAppWidgetVisible = mPendingVisibleAppWidget.get(uid, appWidgetVisible);
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
        int pendingUidState = mPendingUidStates.get(uid, UID_STATE_CACHED);
        int pendingCapability = mPendingCapability.get(uid, PROCESS_CAPABILITY_NONE);
        boolean pendingVisibleAppWidget = mPendingVisibleAppWidget.get(uid, false);

        int uidState = mUidStates.get(uid, UID_STATE_CACHED);
        int capability = mCapability.get(uid, PROCESS_CAPABILITY_NONE);
        boolean visibleAppWidget = mVisibleAppWidget.get(uid, false);

        if (uidState != pendingUidState
                || capability != pendingCapability
                || visibleAppWidget != pendingVisibleAppWidget) {
            boolean foregroundChange = uidState <= UID_STATE_MAX_LAST_NON_RESTRICTED
                    != pendingUidState > UID_STATE_MAX_LAST_NON_RESTRICTED
                    || capability != pendingCapability
                    || visibleAppWidget != pendingVisibleAppWidget;

            for (int i = 0; i < mUidStateChangedCallbacks.size(); i++) {
                UidStateChangedCallback cb = mUidStateChangedCallbacks.keyAt(i);
                Handler h = mUidStateChangedCallbacks.valueAt(i);

                h.sendMessage(PooledLambda.obtainMessage(UidStateChangedCallback::onUidStateChanged,
                        cb, uid, uidState, foregroundChange));
            }
        }

        mUidStates.put(uid, pendingUidState);
        mCapability.put(uid, pendingCapability);
        mVisibleAppWidget.put(uid, pendingVisibleAppWidget);

        mPendingUidStates.delete(uid);
        mPendingCapability.delete(uid);
        mPendingVisibleAppWidget.delete(uid);
        mPendingCommitTime.delete(uid);
    }

    private @ProcessCapability int getUidCapability(int uid) {
        return mCapability.get(uid, ActivityManager.PROCESS_CAPABILITY_NONE);
    }

    private boolean getUidVisibleAppWidget(int uid) {
        return mVisibleAppWidget.get(uid, false);
    }
}
