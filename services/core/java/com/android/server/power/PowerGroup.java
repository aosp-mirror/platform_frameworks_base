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

package com.android.server.power;

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;
import static android.os.PowerManagerInternal.isInteractive;

import static com.android.internal.util.LatencyTracker.ACTION_TURN_ON_SCREEN;
import static com.android.server.power.PowerManagerService.TRACE_SCREEN_ON;
import static com.android.server.power.PowerManagerService.USER_ACTIVITY_SCREEN_BRIGHT;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_DOZE;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_DRAW;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_SCREEN_BRIGHT;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_SCREEN_DIM;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_STAY_AWAKE;

import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Trace;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.LatencyTracker;

/**
 * Used to store power related requests to every display in a
 * {@link com.android.server.display.DisplayGroup}.
 * For each {@link com.android.server.display.DisplayGroup} there exists a {@link PowerGroup}.
 * The mapping is tracked in {@link PowerManagerService}.
 * <p><b>Note:</b> Methods with the {@code *Locked} suffix require the
 * {@code PowerManagerService#mLock} to be held by the caller.
 */
public class PowerGroup {
    private static final String TAG = PowerGroup.class.getSimpleName();
    private static final boolean DEBUG = false;

    @VisibleForTesting
    final DisplayPowerRequest mDisplayPowerRequest = new DisplayPowerRequest();
    private final PowerGroupListener mWakefulnessListener;
    private final Notifier mNotifier;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final boolean mSupportsSandman;
    private final int mGroupId;
    /** True if DisplayManagerService has applied all the latest display states that were requested
     *  for this group. */
    private boolean mReady;
    /** True if this group is in the process of powering on */
    private boolean mPoweringOn;
    /** True if this group is about to dream */
    private boolean mIsSandmanSummoned;
    private int mUserActivitySummary;
    /** The current wakefulness of this group */
    private int mWakefulness;
    private int mWakeLockSummary;
    private long mLastPowerOnTime;
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;
    @PowerManager.UserActivityEvent
    private int mLastUserActivityEvent;
    /** Timestamp (milliseconds since boot) of the last time the power group was awoken.*/
    private long mLastWakeTime;
    /** Timestamp (milliseconds since boot) of the last time the power group was put to sleep. */
    private long mLastSleepTime;

    PowerGroup(int groupId, PowerGroupListener wakefulnessListener, Notifier notifier,
            DisplayManagerInternal displayManagerInternal, int wakefulness, boolean ready,
            boolean supportsSandman, long eventTime) {
        mGroupId = groupId;
        mWakefulnessListener = wakefulnessListener;
        mNotifier = notifier;
        mDisplayManagerInternal = displayManagerInternal;
        mWakefulness = wakefulness;
        mReady = ready;
        mSupportsSandman = supportsSandman;
        mLastWakeTime = eventTime;
        mLastSleepTime = eventTime;
    }

    PowerGroup(int wakefulness, PowerGroupListener wakefulnessListener, Notifier notifier,
            DisplayManagerInternal displayManagerInternal, long eventTime) {
        mGroupId = Display.DEFAULT_DISPLAY_GROUP;
        mWakefulnessListener = wakefulnessListener;
        mNotifier = notifier;
        mDisplayManagerInternal = displayManagerInternal;
        mWakefulness = wakefulness;
        mReady = false;
        mSupportsSandman = true;
        mLastWakeTime = eventTime;
        mLastSleepTime = eventTime;
    }

    long getLastWakeTimeLocked() {
        return mLastWakeTime;
    }

    long getLastSleepTimeLocked() {
        return mLastSleepTime;
    }

    int getWakefulnessLocked() {
        return mWakefulness;
    }

    int getGroupId() {
        return mGroupId;
    }

    /**
     * Sets the {@code wakefulness} value for this {@link PowerGroup}.
     *
     * @return {@code true} if the wakefulness value was changed; {@code false} otherwise.
     */
    boolean setWakefulnessLocked(int newWakefulness, long eventTime, int uid, int reason, int opUid,
            String opPackageName, String details) {
        if (mWakefulness != newWakefulness) {
            if (newWakefulness == WAKEFULNESS_AWAKE) {
                setLastPowerOnTimeLocked(eventTime);
                setIsPoweringOnLocked(true);
                mLastWakeTime = eventTime;
            } else if (isInteractive(mWakefulness) && !isInteractive(newWakefulness)) {
                mLastSleepTime = eventTime;
            }
            mWakefulness = newWakefulness;
            mWakefulnessListener.onWakefulnessChangedLocked(mGroupId, mWakefulness, eventTime,
                    reason, uid, opUid, opPackageName, details);
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if every display in this group has its requested state matching
     * its actual state.
     */
    boolean isReadyLocked() {
        return mReady;
    }

    /**
     * Sets whether the displays of this group are all ready.
     *
     * <p>A display is ready if its reported
     * {@link android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks#onStateChanged()
     * actual state} matches its
     * {@link android.hardware.display.DisplayManagerInternal#requestPowerState requested state}.
     *
     * @param isReady {@code true} if every display in the group is ready; otherwise {@code false}.
     * @return {@code true} if the ready state changed; otherwise {@code false}.
     */
    boolean setReadyLocked(boolean isReady) {
        if (mReady != isReady) {
            mReady = isReady;
            return true;
        }
        return false;
    }

    long getLastPowerOnTimeLocked() {
        return mLastPowerOnTime;
    }

    void setLastPowerOnTimeLocked(long time) {
        mLastPowerOnTime = time;
    }

    boolean isPoweringOnLocked() {
        return mPoweringOn;
    }

    void setIsPoweringOnLocked(boolean isPoweringOnNew) {
        mPoweringOn = isPoweringOnNew;
    }

    boolean isSandmanSummonedLocked() {
        return mIsSandmanSummoned;
    }

    /**
     * Sets whether or not the sandman is summoned for this {@link PowerGroup}.
     *
     * @param isSandmanSummoned {@code true} to summon the sandman; {@code false} to unsummon.
     */
    void setSandmanSummonedLocked(boolean isSandmanSummoned) {
        mIsSandmanSummoned = isSandmanSummoned;
    }

    void wakeUpLocked(long eventTime, @PowerManager.WakeReason int reason, String details, int uid,
            String opPackageName, int opUid, LatencyTracker latencyTracker) {
        if (eventTime < mLastSleepTime || mWakefulness == WAKEFULNESS_AWAKE) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "wakePowerGroup" + mGroupId);
        try {
            Slog.i(TAG, "Waking up power group from "
                    + PowerManagerInternal.wakefulnessToString(mWakefulness)
                    + " (groupId=" + mGroupId
                    + ", uid=" + uid
                    + ", reason=" + PowerManager.wakeReasonToString(reason)
                    + ", details=" + details
                    + ")...");
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, TRACE_SCREEN_ON, mGroupId);
            // The instrument will be timed out automatically after 2 seconds.
            latencyTracker.onActionStart(ACTION_TURN_ON_SCREEN, String.valueOf(mGroupId));

            setWakefulnessLocked(WAKEFULNESS_AWAKE, eventTime, uid, reason, opUid,
                    opPackageName, details);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    boolean dreamLocked(long eventTime, int uid, boolean allowWake) {
        if (eventTime < mLastWakeTime || (!allowWake && mWakefulness != WAKEFULNESS_AWAKE)) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "dreamPowerGroup" + getGroupId());
        try {
            Slog.i(TAG, "Napping power group (groupId=" + getGroupId() + ", uid=" + uid + ")...");
            setSandmanSummonedLocked(true);
            setWakefulnessLocked(WAKEFULNESS_DREAMING, eventTime, uid, /* reason= */0,
                    /* opUid= */ 0, /* opPackageName= */ null, /* details= */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    boolean dozeLocked(long eventTime, int uid, @PowerManager.GoToSleepReason int reason) {
        if (eventTime < getLastWakeTimeLocked() || !isInteractive(mWakefulness)) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "powerOffDisplay");
        try {
            reason = Math.min(PowerManager.GO_TO_SLEEP_REASON_MAX,
                    Math.max(reason, PowerManager.GO_TO_SLEEP_REASON_MIN));
            long millisSinceLastUserActivity = eventTime - Math.max(
                    mLastUserActivityTimeNoChangeLights, mLastUserActivityTime);
            Slog.i(TAG, "Powering off display group due to "
                    + PowerManager.sleepReasonToString(reason)
                    + " (groupId= " + getGroupId() + ", uid= " + uid
                    + ", millisSinceLastUserActivity=" + millisSinceLastUserActivity
                    + ", lastUserActivityEvent=" + PowerManager.userActivityEventToString(
                    mLastUserActivityEvent) + ")...");

            setSandmanSummonedLocked(/* isSandmanSummoned= */ true);
            setWakefulnessLocked(WAKEFULNESS_DOZING, eventTime, uid, reason, /* opUid= */ 0,
                    /* opPackageName= */ null, /* details= */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    boolean sleepLocked(long eventTime, int uid, @PowerManager.GoToSleepReason int reason) {
        if (eventTime < mLastWakeTime || getWakefulnessLocked() == WAKEFULNESS_ASLEEP) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "sleepPowerGroup");
        try {
            Slog.i(TAG,
                    "Sleeping power group (groupId=" + getGroupId() + ", uid=" + uid + ", reason="
                            + PowerManager.sleepReasonToString(reason) + ")...");
            setSandmanSummonedLocked(/* isSandmanSummoned= */ true);
            setWakefulnessLocked(WAKEFULNESS_ASLEEP, eventTime, uid, reason, /* opUid= */0,
                    /* opPackageName= */ null, /* details= */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    long getLastUserActivityTimeLocked() {
        return mLastUserActivityTime;
    }

    void setLastUserActivityTimeLocked(long lastUserActivityTime,
            @PowerManager.UserActivityEvent int event) {
        mLastUserActivityTime = lastUserActivityTime;
        mLastUserActivityEvent = event;
    }

    public long getLastUserActivityTimeNoChangeLightsLocked() {
        return mLastUserActivityTimeNoChangeLights;
    }

    public void setLastUserActivityTimeNoChangeLightsLocked(long time,
            @PowerManager.UserActivityEvent int event) {
        mLastUserActivityTimeNoChangeLights = time;
        mLastUserActivityEvent = event;
    }

    public int getUserActivitySummaryLocked() {
        return mUserActivitySummary;
    }

    public boolean isPolicyBrightLocked() {
        return mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_BRIGHT;
    }

    public boolean isPolicyDimLocked() {
        return mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_DIM;
    }

    public boolean isBrightOrDimLocked() {
        return mDisplayPowerRequest.isBrightOrDim();
    }

    public void setUserActivitySummaryLocked(int summary) {
        mUserActivitySummary = summary;
    }

    public int getWakeLockSummaryLocked() {
        return mWakeLockSummary;
    }

    /**
     * Query whether a wake lock is at least partially responsible for keeping the device awake.
     *
     * This does not necessarily mean the wake lock is the sole reason the device is awake; there
     * could also be user activity keeping the device awake, for example. It just means a wake lock
     * is being held that would keep the device awake even if nothing else was.
     *
     * @return whether the PowerGroup is being kept awake at least in part because a wake lock is
     *         being held.
     */
    public boolean hasWakeLockKeepingScreenOnLocked() {
        final int screenOnWakeLockMask =
                WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_SCREEN_DIM | WAKE_LOCK_STAY_AWAKE;
        return (mWakeLockSummary & (screenOnWakeLockMask)) != 0;
    }

    public void setWakeLockSummaryLocked(int summary) {
        mWakeLockSummary = summary;
    }

    /**
     * Whether or not this DisplayGroup supports dreaming.
     * @return {@code true} if this DisplayGroup supports dreaming; otherwise {@code false}.
     */
    public boolean supportsSandmanLocked() {
        return mSupportsSandman;
    }

    /**
     * Return true if we must keep a suspend blocker active on behalf of a power group.
     * We do so if the screen is on or is in transition between states.
     */
    boolean needSuspendBlockerLocked(boolean proximityPositive,
            boolean suspendWhenScreenOffDueToProximityConfig) {
        if (isBrightOrDimLocked()) {
            // If we asked for the screen to be on but it is off due to the proximity
            // sensor then we may suspend but only if the configuration allows it.
            // On some hardware it may not be safe to suspend because the proximity
            // sensor may not be correctly configured as a wake-up source.
            if (!mDisplayPowerRequest.useProximitySensor || !proximityPositive
                    || !suspendWhenScreenOffDueToProximityConfig) {
                return true;
            }
        }

        if (mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_DOZE
                && mDisplayPowerRequest.dozeScreenState == Display.STATE_ON) {
            // Although we are in DOZE and would normally allow the device to suspend,
            // the doze service has explicitly requested the display to remain in the ON
            // state which means we should hold the display suspend blocker.
            return true;
        }
        return false;
    }

    @VisibleForTesting
    int getDesiredScreenPolicyLocked(boolean quiescent, boolean dozeAfterScreenOff,
            boolean bootCompleted, boolean screenBrightnessBoostInProgress,
            boolean brightWhenDozing) {
        final int wakefulness = getWakefulnessLocked();
        final int wakeLockSummary = getWakeLockSummaryLocked();
        if (wakefulness == WAKEFULNESS_ASLEEP || quiescent) {
            return DisplayPowerRequest.POLICY_OFF;
        } else if (wakefulness == WAKEFULNESS_DOZING) {
            if ((wakeLockSummary & WAKE_LOCK_DOZE) != 0) {
                return DisplayPowerRequest.POLICY_DOZE;
            }
            if (dozeAfterScreenOff) {
                return DisplayPowerRequest.POLICY_OFF;
            }
            if (brightWhenDozing) {
                return DisplayPowerRequest.POLICY_BRIGHT;
            }
            // Fall through and preserve the current screen policy if not configured to
            // bright when dozing or doze after screen off.  This causes the screen off transition
            // to be skipped.
        }

        if ((wakeLockSummary & WAKE_LOCK_SCREEN_BRIGHT) != 0
                || !bootCompleted
                || (getUserActivitySummaryLocked() & USER_ACTIVITY_SCREEN_BRIGHT) != 0
                || screenBrightnessBoostInProgress) {
            return DisplayPowerRequest.POLICY_BRIGHT;
        }

        return DisplayPowerRequest.POLICY_DIM;
    }

    int getPolicyLocked() {
        return mDisplayPowerRequest.policy;
    }

    boolean updateLocked(float screenBrightnessOverride, CharSequence overrideTag,
            boolean useProximitySensor, boolean boostScreenBrightness, int dozeScreenState,
            @Display.StateReason int dozeScreenStateReason,
            float dozeScreenBrightness, boolean useNormalBrightnessForDoze,
            boolean overrideDrawWakeLock,
            PowerSaveState powerSaverState, boolean quiescent,
            boolean dozeAfterScreenOff, boolean bootCompleted,
            boolean screenBrightnessBoostInProgress, boolean waitForNegativeProximity,
            boolean brightWhenDozing) {
        mDisplayPowerRequest.policy = getDesiredScreenPolicyLocked(quiescent, dozeAfterScreenOff,
                bootCompleted, screenBrightnessBoostInProgress, brightWhenDozing);
        mDisplayPowerRequest.screenBrightnessOverride = screenBrightnessOverride;
        mDisplayPowerRequest.screenBrightnessOverrideTag = overrideTag;
        mDisplayPowerRequest.useProximitySensor = useProximitySensor;
        mDisplayPowerRequest.boostScreenBrightness = boostScreenBrightness;

        if (mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_DOZE) {
            mDisplayPowerRequest.dozeScreenState = dozeScreenState;
            mDisplayPowerRequest.dozeScreenStateReason = dozeScreenStateReason;
            if ((getWakeLockSummaryLocked() & WAKE_LOCK_DRAW) != 0 && !overrideDrawWakeLock) {
                if (mDisplayPowerRequest.dozeScreenState == Display.STATE_DOZE_SUSPEND) {
                    mDisplayPowerRequest.dozeScreenState = Display.STATE_DOZE;
                    mDisplayPowerRequest.dozeScreenStateReason =
                            Display.STATE_REASON_DRAW_WAKE_LOCK;
                }
                if (mDisplayPowerRequest.dozeScreenState == Display.STATE_ON_SUSPEND) {
                    mDisplayPowerRequest.dozeScreenState = Display.STATE_ON;
                    mDisplayPowerRequest.dozeScreenStateReason =
                            Display.STATE_REASON_DRAW_WAKE_LOCK;
                }
            }
            mDisplayPowerRequest.dozeScreenBrightness = dozeScreenBrightness;
            mDisplayPowerRequest.useNormalBrightnessForDoze = useNormalBrightnessForDoze;
        } else {
            mDisplayPowerRequest.dozeScreenState = Display.STATE_UNKNOWN;
            mDisplayPowerRequest.dozeScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mDisplayPowerRequest.dozeScreenStateReason =
                    Display.STATE_REASON_DEFAULT_POLICY;
            mDisplayPowerRequest.useNormalBrightnessForDoze = false;
        }
        mDisplayPowerRequest.lowPowerMode = powerSaverState.batterySaverEnabled;
        mDisplayPowerRequest.screenLowPowerBrightnessFactor = powerSaverState.brightnessFactor;
        boolean ready = mDisplayManagerInternal.requestPowerState(mGroupId, mDisplayPowerRequest,
                waitForNegativeProximity);
        mNotifier.onScreenPolicyUpdate(mGroupId, mDisplayPowerRequest.policy);
        return ready;
    }

    protected interface PowerGroupListener {
        /**
         * Informs the recipient about a wakefulness change of a {@link PowerGroup}.
         *
         * @param groupId The PowerGroup's id for which the wakefulness has changed.
         * @param wakefulness The new wakefulness.
         * @param eventTime The time of the event.
         * @param reason The reason, any of {@link android.os.PowerManager.WakeReason} or
         *               {@link android.os.PowerManager.GoToSleepReason}.
         * @param uid The uid which caused the wakefulness change.
         * @param opUid The uid used for AppOps.
         * @param opPackageName The Package name used for AppOps.
         * @param details Details about the event.
         */
        void onWakefulnessChangedLocked(int groupId, int wakefulness, long eventTime, int reason,
                int uid, int opUid, String opPackageName, String details);
    }
}
