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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ACTIVITY_BG_START_GRACE_PERIOD_MS;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_FG_ONLY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * A per-process controller to decide whether the process can start activity or foreground service
 * (especially from background). All methods of this class must be thread safe. The caller does not
 * need to hold WM lock, e.g. lock contention of WM lock shouldn't happen when starting service.
 */
class BackgroundLaunchProcessController {
    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "BackgroundLaunchProcessController" : TAG_ATM;

    /** It is {@link ActivityTaskManagerService#hasActiveVisibleWindow(int)}. */
    private final IntPredicate mUidHasActiveVisibleWindowPredicate;

    private final @Nullable BackgroundActivityStartCallback mBackgroundActivityStartCallback;

    /**
     * A set of tokens that currently contribute to this process being temporarily allowed
     * to start activities even if it's not in the foreground. The values of this map are optional
     * (can be null) and are used to trace back the grant to the notification token mechanism.
     */
    @GuardedBy("this")
    private @Nullable ArrayMap<Binder, IBinder> mBackgroundActivityStartTokens;

    /** Set of UIDs of clients currently bound to this process and opt in to allow this process to
     * launch background activity.
     */
    @GuardedBy("this")
    private @Nullable IntArray mBalOptInBoundClientUids;

    BackgroundLaunchProcessController(@NonNull IntPredicate uidHasActiveVisibleWindowPredicate,
            @Nullable BackgroundActivityStartCallback callback) {
        mUidHasActiveVisibleWindowPredicate = uidHasActiveVisibleWindowPredicate;
        mBackgroundActivityStartCallback = callback;
    }

    boolean areBackgroundActivityStartsAllowed(int pid, int uid, String packageName,
            int appSwitchState, boolean isCheckingForFgsStart,
            boolean hasActivityInVisibleTask, boolean hasBackgroundActivityStartPrivileges,
            long lastStopAppSwitchesTime, long lastActivityLaunchTime,
            long lastActivityFinishTime) {
        // If app switching is not allowed, we ignore all the start activity grace period
        // exception so apps cannot start itself in onPause() after pressing home button.
        if (appSwitchState == APP_SWITCH_ALLOW) {
            // Allow if any activity in the caller has either started or finished very recently, and
            // it must be started or finished after last stop app switches time.
            final long now = SystemClock.uptimeMillis();
            if (now - lastActivityLaunchTime < ACTIVITY_BG_START_GRACE_PERIOD_MS
                    || now - lastActivityFinishTime < ACTIVITY_BG_START_GRACE_PERIOD_MS) {
                // If activity is started and finished before stop app switch time, we should not
                // let app to be able to start background activity even it's in grace period.
                if (lastActivityLaunchTime > lastStopAppSwitchesTime
                        || lastActivityFinishTime > lastStopAppSwitchesTime) {
                    if (DEBUG_ACTIVITY_STARTS) {
                        Slog.d(TAG, "[Process(" + pid
                                + ")] Activity start allowed: within "
                                + ACTIVITY_BG_START_GRACE_PERIOD_MS + "ms grace period");
                    }
                    return true;
                }
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "[Process(" + pid + ")] Activity start within "
                            + ACTIVITY_BG_START_GRACE_PERIOD_MS
                            + "ms grace period but also within stop app switch window");
                }

            }
        }
        // Allow if the proc is instrumenting with background activity starts privs.
        if (hasBackgroundActivityStartPrivileges) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[Process(" + pid
                        + ")] Activity start allowed: process instrumenting with background "
                        + "activity starts privileges");
            }
            return true;
        }
        // Allow if the caller has an activity in any foreground task.
        if (hasActivityInVisibleTask
                && (appSwitchState == APP_SWITCH_ALLOW || appSwitchState == APP_SWITCH_FG_ONLY)) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[Process(" + pid
                        + ")] Activity start allowed: process has activity in foreground task");
            }
            return true;
        }
        // Allow if the caller is bound by a UID that's currently foreground.
        if (isBoundByForegroundUid()) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[Process(" + pid
                        + ")] Activity start allowed: process bound by foreground uid");
            }
            return true;
        }
        // Allow if the flag was explicitly set.
        if (isBackgroundStartAllowedByToken(uid, packageName, isCheckingForFgsStart)) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[Process(" + pid
                        + ")] Activity start allowed: process allowed by token");
            }
            return true;
        }
        return false;
    }

    /**
     * If there are no tokens, we don't allow *by token*. If there are tokens and
     * isCheckingForFgsStart is false, we ask the callback if the start is allowed for these tokens,
     * otherwise if there is no callback we allow.
     */
    private boolean isBackgroundStartAllowedByToken(int uid, String packageName,
            boolean isCheckingForFgsStart) {
        synchronized (this) {
            if (mBackgroundActivityStartTokens == null
                    || mBackgroundActivityStartTokens.isEmpty()) {
                return false;
            }
            if (isCheckingForFgsStart) {
                // BG-FGS-start only checks if there is a token.
                return true;
            }

            if (mBackgroundActivityStartCallback == null) {
                // We have tokens but no callback to decide => allow.
                return true;
            }
            // The callback will decide.
            return mBackgroundActivityStartCallback.isActivityStartAllowed(
                    mBackgroundActivityStartTokens.values(), uid, packageName);
        }
    }

    private boolean isBoundByForegroundUid() {
        synchronized (this) {
            if (mBalOptInBoundClientUids != null) {
                for (int i = mBalOptInBoundClientUids.size() - 1; i >= 0; i--) {
                    if (mUidHasActiveVisibleWindowPredicate.test(mBalOptInBoundClientUids.get(i))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void clearBalOptInBoundClientUids() {
        synchronized (this) {
            if (mBalOptInBoundClientUids == null) {
                mBalOptInBoundClientUids = new IntArray();
            } else {
                mBalOptInBoundClientUids.clear();
            }
        }
    }

    void addBoundClientUid(int clientUid, String clientPackageName, int bindFlags) {
        if ((bindFlags & Context.BIND_DENY_ACTIVITY_STARTS) == 0) {
            if (mBalOptInBoundClientUids == null) {
                mBalOptInBoundClientUids = new IntArray();
            }
            if (mBalOptInBoundClientUids.indexOf(clientUid) == -1) {
                mBalOptInBoundClientUids.add(clientUid);
            }
        }
    }

    /**
     * Allows background activity starts using token {@code entity}. Optionally, you can provide
     * {@code originatingToken} if you have one such originating token, this is useful for tracing
     * back the grant in the case of the notification token.
     *
     * If {@code entity} is already added, this method will update its {@code originatingToken}.
     */
    void addOrUpdateAllowBackgroundActivityStartsToken(Binder entity,
            @Nullable IBinder originatingToken) {
        synchronized (this) {
            if (mBackgroundActivityStartTokens == null) {
                mBackgroundActivityStartTokens = new ArrayMap<>();
            }
            mBackgroundActivityStartTokens.put(entity, originatingToken);
        }
    }

    /**
     * Removes token {@code entity} that allowed background activity starts added via {@link
     * #addOrUpdateAllowBackgroundActivityStartsToken(Binder, IBinder)}.
     */
    void removeAllowBackgroundActivityStartsToken(Binder entity) {
        synchronized (this) {
            if (mBackgroundActivityStartTokens != null) {
                mBackgroundActivityStartTokens.remove(entity);
            }
        }
    }

    /**
     * Returns whether this process is allowed to close system dialogs via a background activity
     * start token that allows the close system dialogs operation (eg. notification).
     */
    boolean canCloseSystemDialogsByToken(int uid) {
        if (mBackgroundActivityStartCallback == null) {
            return false;
        }
        synchronized (this) {
            if (mBackgroundActivityStartTokens == null
                    || mBackgroundActivityStartTokens.isEmpty()) {
                return false;
            }
            return mBackgroundActivityStartCallback.canCloseSystemDialogs(
                    mBackgroundActivityStartTokens.values(), uid);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        synchronized (this) {
            if (mBackgroundActivityStartTokens != null
                    && !mBackgroundActivityStartTokens.isEmpty()) {
                pw.print(prefix);
                pw.println("Background activity start tokens (token: originating token):");
                for (int i = mBackgroundActivityStartTokens.size() - 1; i >= 0; i--) {
                    pw.print(prefix);
                    pw.print("  - ");
                    pw.print(mBackgroundActivityStartTokens.keyAt(i));
                    pw.print(": ");
                    pw.println(mBackgroundActivityStartTokens.valueAt(i));
                }
            }
            if (mBalOptInBoundClientUids != null && mBalOptInBoundClientUids.size() > 0) {
                pw.print(prefix);
                pw.print("BoundClientUids:");
                pw.println(Arrays.toString(mBalOptInBoundClientUids.toArray()));
            }
        }
    }
}
