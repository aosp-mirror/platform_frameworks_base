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

package com.android.server.display;

import android.annotation.IntDef;
import android.hardware.display.DisplayManagerInternal;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A utility class to acquire/release suspend blockers and manage appropriate states around it.
 * It is also a channel to asynchronously update the PowerManagerService about the changes in the
 * display states as needed.
 */
public final class WakelockController {
    public static final int WAKE_LOCK_PROXIMITY_POSITIVE = 1;
    public static final int WAKE_LOCK_PROXIMITY_NEGATIVE = 2;
    public static final int WAKE_LOCK_PROXIMITY_DEBOUNCE = 3;
    public static final int WAKE_LOCK_STATE_CHANGED = 4;
    public static final int WAKE_LOCK_OVERRIDE_DOZE_SCREEN_STATE = 5;
    public static final int WAKE_LOCK_UNFINISHED_BUSINESS = 6;

    @VisibleForTesting
    static final int WAKE_LOCK_MAX = WAKE_LOCK_UNFINISHED_BUSINESS;

    private static final String TAG = "WakelockController";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.WakelockController DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    @IntDef(flag = true, prefix = "WAKE_LOCK_", value = {
            WAKE_LOCK_PROXIMITY_POSITIVE,
            WAKE_LOCK_PROXIMITY_NEGATIVE,
            WAKE_LOCK_PROXIMITY_DEBOUNCE,
            WAKE_LOCK_STATE_CHANGED,
            WAKE_LOCK_OVERRIDE_DOZE_SCREEN_STATE,
            WAKE_LOCK_UNFINISHED_BUSINESS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WAKE_LOCK_TYPE {
    }

    private final Object mLock = new Object();

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks;

    // Identifiers for suspend blocker acquisition requests
    private final String mSuspendBlockerIdUnfinishedBusiness;
    @GuardedBy("mLock")
    private final String mSuspendBlockerOverrideDozeScreenState;
    private final String mSuspendBlockerIdOnStateChanged;
    private final String mSuspendBlockerIdProxPositive;
    private final String mSuspendBlockerIdProxNegative;
    private final String mSuspendBlockerIdProxDebounce;

    // True if we have unfinished business and are holding a suspend-blocker.
    private boolean mUnfinishedBusiness;

    // True if we have are holding a suspend-blocker to override the doze screen state.
    @GuardedBy("mLock")
    private boolean mIsOverrideDozeScreenStateAcquired;

    // True if we have have debounced the proximity change impact and are holding a suspend-blocker.
    private boolean mHasProximityDebounced;

    // The ID of the LogicalDisplay tied to this.
    private final int mDisplayId;
    private final String mTag;

    // When true, it implies a wakelock is being held to guarantee the update happens before we
    // collapse into suspend and so needs to be cleaned up if the thread is exiting.
    // Should only be accessed on the Handler thread of the class managing the Display states
    // (i.e. DisplayPowerController2).
    private boolean mOnStateChangedPending;

    // When true, it implies that a positive proximity wakelock is currently held. Used to keep
    // track if suspend blocker acquisitions is pending when shutting down the
    // DisplayPowerController2. Should only be accessed on the Handler thread of the class
    // managing the Display states (i.e. DisplayPowerController2).
    private boolean mIsProximityPositiveAcquired;

    // When true, it implies that a negative proximity wakelock is currently held. Used to keep
    // track if suspend blocker acquisitions is pending when shutting down the
    // DisplayPowerController2. Should only be accessed on the Handler thread of the class
    // managing the Display states (i.e. DisplayPowerController2).
    private boolean mIsProximityNegativeAcquired;

    /**
     * The constructor of WakelockController. Manages the initialization of all the local entities
     * needed for its appropriate functioning.
     */
    public WakelockController(int displayId,
            DisplayManagerInternal.DisplayPowerCallbacks callbacks) {
        mDisplayId = displayId;
        mTag = TAG + "[" + mDisplayId + "]";
        mDisplayPowerCallbacks = callbacks;
        mSuspendBlockerIdUnfinishedBusiness = "[" + displayId + "]unfinished business";
        mSuspendBlockerOverrideDozeScreenState =  "[" + displayId + "]override doze screen state";
        mSuspendBlockerIdOnStateChanged = "[" + displayId + "]on state changed";
        mSuspendBlockerIdProxPositive = "[" + displayId + "]prox positive";
        mSuspendBlockerIdProxNegative = "[" + displayId + "]prox negative";
        mSuspendBlockerIdProxDebounce = "[" + displayId + "]prox debounce";
    }

    /**
     * A utility to acquire a wakelock
     *
     * @param wakelock The type of Wakelock to be acquired
     * @return True of the wakelock is successfully acquired. False if it is already acquired
     */
    public boolean acquireWakelock(@WAKE_LOCK_TYPE int wakelock) {
        return acquireWakelockInternal(wakelock);
    }

    /**
     * A utility to release a wakelock
     *
     * @param wakelock The type of Wakelock to be released
     * @return True of an acquired wakelock is successfully released. False if it is already
     * acquired
     */
    public boolean releaseWakelock(@WAKE_LOCK_TYPE int wakelock) {
        return releaseWakelockInternal(wakelock);
    }

    /**
     * A utility to release all the wakelock acquired by the system
     */
    public void releaseAll() {
        for (int i = WAKE_LOCK_PROXIMITY_POSITIVE; i <= WAKE_LOCK_MAX; i++) {
            releaseWakelockInternal(i);
        }
    }

    private boolean acquireWakelockInternal(@WAKE_LOCK_TYPE int wakelock) {
        switch (wakelock) {
            case WAKE_LOCK_PROXIMITY_POSITIVE:
                return acquireProxPositiveSuspendBlocker();
            case WAKE_LOCK_PROXIMITY_NEGATIVE:
                return acquireProxNegativeSuspendBlocker();
            case WAKE_LOCK_PROXIMITY_DEBOUNCE:
                return acquireProxDebounceSuspendBlocker();
            case WAKE_LOCK_STATE_CHANGED:
                return acquireStateChangedSuspendBlocker();
            case WAKE_LOCK_OVERRIDE_DOZE_SCREEN_STATE:
                synchronized (mLock) {
                    return acquireOverrideDozeScreenStateSuspendBlockerLocked();
                }
            case WAKE_LOCK_UNFINISHED_BUSINESS:
                return acquireUnfinishedBusinessSuspendBlocker();
            default:
                throw new RuntimeException("Invalid wakelock attempted to be acquired");
        }
    }

    private boolean releaseWakelockInternal(@WAKE_LOCK_TYPE int wakelock) {
        switch (wakelock) {
            case WAKE_LOCK_PROXIMITY_POSITIVE:
                return releaseProxPositiveSuspendBlocker();
            case WAKE_LOCK_PROXIMITY_NEGATIVE:
                return releaseProxNegativeSuspendBlocker();
            case WAKE_LOCK_PROXIMITY_DEBOUNCE:
                return releaseProxDebounceSuspendBlocker();
            case WAKE_LOCK_STATE_CHANGED:
                return releaseStateChangedSuspendBlocker();
            case WAKE_LOCK_OVERRIDE_DOZE_SCREEN_STATE:
                synchronized (mLock) {
                    return releaseOverrideDozeScreenStateSuspendBlockerLocked();
                }
            case WAKE_LOCK_UNFINISHED_BUSINESS:
                return releaseUnfinishedBusinessSuspendBlocker();
            default:
                throw new RuntimeException("Invalid wakelock attempted to be released");
        }
    }

    /**
     * Acquires the proximity positive wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean acquireProxPositiveSuspendBlocker() {
        if (!mIsProximityPositiveAcquired) {
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdProxPositive);
            mIsProximityPositiveAcquired = true;
            return true;
        }
        return false;
    }

    /**
     * Acquires the state change wakelock and notifies the PowerManagerService about the changes.
     */
    private boolean acquireStateChangedSuspendBlocker() {
        // Grab a wake lock if we have change of the display state
        if (!mOnStateChangedPending) {
            if (DEBUG) {
                Slog.d(mTag, "State Changed...");
            }
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdOnStateChanged);
            mOnStateChangedPending = true;
            return true;
        }
        return false;
    }

    /**
     * Releases the state change wakelock and notifies the PowerManagerService about the changes.
     */
    private boolean releaseStateChangedSuspendBlocker() {
        if (mOnStateChangedPending) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdOnStateChanged);
            mOnStateChangedPending = false;
            return true;
        }
        return false;
    }

    /**
     * Acquires the suspend blocker to override the doze screen state and notifies the
     * PowerManagerService about the changes. Note that this utility is syncronized because a
     * request to override the doze screen state can come from a non-power thread.
     */
    @GuardedBy("mLock")
    private boolean acquireOverrideDozeScreenStateSuspendBlockerLocked() {
        // Grab a wake lock if we have unfinished business.
        if (!mIsOverrideDozeScreenStateAcquired) {
            if (DEBUG) {
                Slog.d(mTag, "Acquiring suspend blocker to override the doze screen state...");
            }
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerOverrideDozeScreenState);
            mIsOverrideDozeScreenStateAcquired = true;
            return true;
        }
        return false;
    }

    /**
     * Releases the override doze screen state suspend blocker and notifies the PowerManagerService
     * about the changes.
     */
    @GuardedBy("mLock")
    private boolean releaseOverrideDozeScreenStateSuspendBlockerLocked() {
        if (mIsOverrideDozeScreenStateAcquired) {
            if (DEBUG) {
                Slog.d(mTag, "Finished overriding doze screen state...");
            }
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerOverrideDozeScreenState);
            mIsOverrideDozeScreenStateAcquired = false;
            return true;
        }
        return false;
    }

    /**
     * Acquires the unfinished business wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean acquireUnfinishedBusinessSuspendBlocker() {
        // Grab a wake lock if we have unfinished business.
        if (!mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(mTag, "Unfinished business...");
            }
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdUnfinishedBusiness);
            mUnfinishedBusiness = true;
            return true;
        }
        return false;
    }

    /**
     * Releases the unfinished business wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean releaseUnfinishedBusinessSuspendBlocker() {
        if (mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(mTag, "Finished business...");
            }
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdUnfinishedBusiness);
            mUnfinishedBusiness = false;
            return true;
        }
        return false;
    }

    /**
     * Releases the proximity positive wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean releaseProxPositiveSuspendBlocker() {
        if (mIsProximityPositiveAcquired) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxPositive);
            mIsProximityPositiveAcquired = false;
            return true;
        }
        return false;
    }

    /**
     * Acquires the proximity negative wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean acquireProxNegativeSuspendBlocker() {
        if (!mIsProximityNegativeAcquired) {
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdProxNegative);
            mIsProximityNegativeAcquired = true;
            return true;
        }
        return false;
    }

    /**
     * Releases the proximity negative wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean releaseProxNegativeSuspendBlocker() {
        if (mIsProximityNegativeAcquired) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxNegative);
            mIsProximityNegativeAcquired = false;
            return true;
        }
        return false;
    }

    /**
     * Acquires the proximity debounce wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean acquireProxDebounceSuspendBlocker() {
        if (!mHasProximityDebounced) {
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdProxDebounce);
            mHasProximityDebounced = true;
            return true;
        }
        return false;
    }

    /**
     * Releases the proximity debounce wakelock and notifies the PowerManagerService about the
     * changes.
     */
    private boolean releaseProxDebounceSuspendBlocker() {
        if (mHasProximityDebounced) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxDebounce);
            mHasProximityDebounced = false;
            return true;
        }
        return false;
    }

    /**
     * Gets the Runnable to be executed when the proximity becomes positive.
     */
    public Runnable getOnProximityPositiveRunnable() {
        return () -> {
            if (mIsProximityPositiveAcquired) {
                mIsProximityPositiveAcquired = false;
                mDisplayPowerCallbacks.onProximityPositive();
                mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxPositive);
            }
        };
    }

    /**
     * Gets the Runnable to be executed when the display state changes
     */
    public Runnable getOnStateChangedRunnable() {
        return () -> {
            if (mOnStateChangedPending) {
                mOnStateChangedPending = false;
                mDisplayPowerCallbacks.onStateChanged();
                mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdOnStateChanged);
            }
        };
    }

    /**
     * Gets the Runnable to be executed when the proximity becomes negative.
     */
    public Runnable getOnProximityNegativeRunnable() {
        return () -> {
            if (mIsProximityNegativeAcquired) {
                mIsProximityNegativeAcquired = false;
                mDisplayPowerCallbacks.onProximityNegative();
                mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxNegative);
            }
        };
    }

    /**
     * Dumps the current state of this
     */
    public void dumpLocal(PrintWriter pw) {
        pw.println("WakelockController State:");
        pw.println("-------------------------");
        pw.println("  mDisplayId=" + mDisplayId);
        pw.println("  mUnfinishedBusiness=" + hasUnfinishedBusiness());
        pw.println("  mOnStateChangePending=" + isOnStateChangedPending());
        pw.println("  mOnProximityPositiveMessages=" + isProximityPositiveAcquired());
        pw.println("  mOnProximityNegativeMessages=" + isProximityNegativeAcquired());
        pw.println("  mIsOverrideDozeScreenStateAcquired=" + isOverrideDozeScreenStateAcquired());
    }

    @VisibleForTesting
    String getSuspendBlockerUnfinishedBusinessId() {
        return mSuspendBlockerIdUnfinishedBusiness;
    }

    @VisibleForTesting
    String getSuspendBlockerOnStateChangedId() {
        return mSuspendBlockerIdOnStateChanged;
    }

    @VisibleForTesting
    String getSuspendBlockerProxPositiveId() {
        return mSuspendBlockerIdProxPositive;
    }

    @VisibleForTesting
    String getSuspendBlockerProxNegativeId() {
        return mSuspendBlockerIdProxNegative;
    }

    @VisibleForTesting
    String getSuspendBlockerProxDebounceId() {
        return mSuspendBlockerIdProxDebounce;
    }

    @VisibleForTesting
    String getSuspendBlockerOverrideDozeScreenState() {
        synchronized (mLock) {
            return mSuspendBlockerOverrideDozeScreenState;
        }
    }

    @VisibleForTesting
    boolean hasUnfinishedBusiness() {
        return mUnfinishedBusiness;
    }

    @VisibleForTesting
    boolean isOnStateChangedPending() {
        return mOnStateChangedPending;
    }

    @VisibleForTesting
    boolean isProximityPositiveAcquired() {
        return mIsProximityPositiveAcquired;
    }

    @VisibleForTesting
    boolean isProximityNegativeAcquired() {
        return mIsProximityNegativeAcquired;
    }

    @VisibleForTesting
    boolean hasProximitySensorDebounced() {
        return mHasProximityDebounced;
    }

    @VisibleForTesting
    boolean isOverrideDozeScreenStateAcquired() {
        synchronized (mLock) {
            return mIsOverrideDozeScreenStateAcquired;
        }
    }
}
