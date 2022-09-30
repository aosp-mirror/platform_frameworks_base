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

import android.hardware.display.DisplayManagerInternal;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * A utility class to acquire/release suspend blockers and manage appropriate states around it.
 * It is also a channel to asynchronously update the PowerManagerService about the changes in the
 * display states as needed.
 */
public final class WakelockController {
    private static final boolean DEBUG = false;

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks;

    // Identifiers for suspend blocker acquisition requests
    private final String mSuspendBlockerIdUnfinishedBusiness;
    private final String mSuspendBlockerIdOnStateChanged;
    private final String mSuspendBlockerIdProxPositive;
    private final String mSuspendBlockerIdProxNegative;
    private final String mSuspendBlockerIdProxDebounce;

    // True if we have unfinished business and are holding a suspend-blocker.
    private boolean mUnfinishedBusiness;

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

    // Count of positive proximity messages currently held. Used to keep track of how many
    // suspend blocker acquisitions are pending when shutting down the DisplayPowerController2.
    // Should only be accessed on the Handler thread of the class managing the Display states
    // (i.e. DisplayPowerController2).
    private int mOnProximityPositiveMessages;

    // Count of negative proximity messages currently held. Used to keep track of how many
    // suspend blocker acquisitions are pending when shutting down the DisplayPowerController2.
    // Should only be accessed on the Handler thread of the class managing the Display states
    // (i.e. DisplayPowerController2).
    private int mOnProximityNegativeMessages;

    /**
     * The constructor of WakelockController. Manages the initialization of all the local entities
     * needed for its appropriate functioning.
     */
    public WakelockController(int displayId,
            DisplayManagerInternal.DisplayPowerCallbacks callbacks) {
        mDisplayId = displayId;
        mTag = "WakelockController[" + mDisplayId + "]";
        mDisplayPowerCallbacks = callbacks;
        mSuspendBlockerIdUnfinishedBusiness = "[" + displayId + "]unfinished business";
        mSuspendBlockerIdOnStateChanged = "[" + displayId + "]on state changed";
        mSuspendBlockerIdProxPositive = "[" + displayId + "]prox positive";
        mSuspendBlockerIdProxNegative = "[" + displayId + "]prox negative";
        mSuspendBlockerIdProxDebounce = "[" + displayId + "]prox debounce";
    }

    /**
     * Acquires the state change wakelock and notifies the PowerManagerService about the changes.
     */
    public boolean acquireStateChangedSuspendBlocker() {
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
    public void releaseStateChangedSuspendBlocker() {
        if (mOnStateChangedPending) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdOnStateChanged);
            mOnStateChangedPending = false;
        }
    }

    /**
     * Acquires the unfinished business wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void acquireUnfinishedBusinessSuspendBlocker() {
        // Grab a wake lock if we have unfinished business.
        if (!mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(mTag, "Unfinished business...");
            }
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdUnfinishedBusiness);
            mUnfinishedBusiness = true;
        }
    }

    /**
     * Releases the unfinished business wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void releaseUnfinishedBusinessSuspendBlocker() {
        if (mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(mTag, "Finished business...");
            }
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdUnfinishedBusiness);
            mUnfinishedBusiness = false;
        }
    }

    /**
     * Acquires the proximity positive wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void acquireProxPositiveSuspendBlocker() {
        mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdProxPositive);
        mOnProximityPositiveMessages++;
    }

    /**
     * Releases the proximity positive wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void releaseProxPositiveSuspendBlocker() {
        for (int i = 0; i < mOnProximityPositiveMessages; i++) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxPositive);
        }
        mOnProximityPositiveMessages = 0;
    }

    /**
     * Acquires the proximity negative wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void acquireProxNegativeSuspendBlocker() {
        mOnProximityNegativeMessages++;
        mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdProxNegative);
    }

    /**
     * Releases the proximity negative wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void releaseProxNegativeSuspendBlocker() {
        for (int i = 0; i < mOnProximityNegativeMessages; i++) {
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxNegative);
        }
        mOnProximityNegativeMessages = 0;
    }

    /**
     * Acquires the proximity debounce wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public void acquireProxDebounceSuspendBlocker() {
        if (!mHasProximityDebounced) {
            mDisplayPowerCallbacks.acquireSuspendBlocker(mSuspendBlockerIdProxDebounce);
        }
        mHasProximityDebounced = true;
    }

    /**
     * Releases the proximity debounce wakelock and notifies the PowerManagerService about the
     * changes.
     */
    public boolean releaseProxDebounceSuspendBlocker() {
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
            mOnProximityPositiveMessages--;
            mDisplayPowerCallbacks.onProximityPositive();
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxPositive);
        };
    }

    /**
     * Gets the Runnable to be executed when the display state changes
     */
    public Runnable getOnStateChangedRunnable() {
        return () -> {
            mOnStateChangedPending = false;
            mDisplayPowerCallbacks.onStateChanged();
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdOnStateChanged);
        };
    }

    /**
     * Gets the Runnable to be executed when the proximity becomes negative.
     */
    public Runnable getOnProximityNegativeRunnable() {
        return () -> {
            mOnProximityNegativeMessages--;
            mDisplayPowerCallbacks.onProximityNegative();
            mDisplayPowerCallbacks.releaseSuspendBlocker(mSuspendBlockerIdProxNegative);
        };
    }

    /**
     * Dumps the current state of this
     */
    public void dumpLocal(PrintWriter pw) {
        pw.println("WakelockController State:");
        pw.println("  mDisplayId=" + mDisplayId);
        pw.println("  mUnfinishedBusiness=" + hasUnfinishedBusiness());
        pw.println("  mOnStateChangePending=" + isOnStateChangedPending());
        pw.println("  mOnProximityPositiveMessages=" + getOnProximityPositiveMessages());
        pw.println("  mOnProximityNegativeMessages=" + getOnProximityNegativeMessages());
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
    boolean hasUnfinishedBusiness() {
        return mUnfinishedBusiness;
    }

    @VisibleForTesting
    boolean isOnStateChangedPending() {
        return mOnStateChangedPending;
    }

    @VisibleForTesting
    int getOnProximityPositiveMessages() {
        return mOnProximityPositiveMessages;
    }

    @VisibleForTesting
    int getOnProximityNegativeMessages() {
        return mOnProximityNegativeMessages;
    }

    @VisibleForTesting
    boolean hasProximitySensorDebounced() {
        return mHasProximityDebounced;
    }
}
