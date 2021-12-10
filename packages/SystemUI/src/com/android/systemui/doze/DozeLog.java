/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.annotation.IntDef;
import android.util.TimeUtils;

import androidx.annotation.NonNull;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Logs doze events for debugging and triaging purposes. Logs are dumped in bugreports or on demand:
 *      adb shell dumpsys activity service com.android.systemui/.SystemUIService \
 *      dependency DumpController DozeLog,DozeStats
 */
@SysUISingleton
public class DozeLog implements Dumpable {
    private final DozeLogger mLogger;

    private boolean mPulsing;
    private long mSince;
    private SummaryStats mPickupPulseNearVibrationStats;
    private SummaryStats mPickupPulseNotNearVibrationStats;
    private SummaryStats mNotificationPulseStats;
    private SummaryStats mScreenOnPulsingStats;
    private SummaryStats mScreenOnNotPulsingStats;
    private SummaryStats mEmergencyCallStats;
    private SummaryStats[][] mProxStats; // [reason][near/far]

    @Inject
    public DozeLog(
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DumpManager dumpManager,
            DozeLogger logger) {
        mLogger = logger;
        mSince = System.currentTimeMillis();
        mPickupPulseNearVibrationStats = new SummaryStats();
        mPickupPulseNotNearVibrationStats = new SummaryStats();
        mNotificationPulseStats = new SummaryStats();
        mScreenOnPulsingStats = new SummaryStats();
        mScreenOnNotPulsingStats = new SummaryStats();
        mEmergencyCallStats = new SummaryStats();
        mProxStats = new SummaryStats[TOTAL_REASONS][2];
        for (int i = 0; i < TOTAL_REASONS; i++) {
            mProxStats[i][0] = new SummaryStats();
            mProxStats[i][1] = new SummaryStats();
        }

        if (keyguardUpdateMonitor != null) {
            keyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        }

        dumpManager.registerDumpable("DumpStats", this);
    }

    /**
     * Appends pickup wakeup event to the logs
     */
    public void tracePickupWakeUp(boolean withinVibrationThreshold) {
        mLogger.logPickupWakeup(withinVibrationThreshold);
        (withinVibrationThreshold ? mPickupPulseNearVibrationStats
                : mPickupPulseNotNearVibrationStats).append();
    }

    /**
     * Appends pulse started event to the logs.
     * @param reason why the pulse started
     */
    public void tracePulseStart(@Reason int reason) {
        mLogger.logPulseStart(reason);
        mPulsing = true;
    }

    /**
     * Appends pulse finished event to the logs
     */
    public void tracePulseFinish() {
        mLogger.logPulseFinish();
        mPulsing = false;
    }

    /**
     * Appends pulse event to the logs
     */
    public void traceNotificationPulse() {
        mLogger.logNotificationPulse();
        mNotificationPulseStats.append();
    }

    /**
     * Appends dozing event to the logs
     * @param dozing true if dozing, else false
     */
    public void traceDozing(boolean dozing) {
        mLogger.logDozing(dozing);
        mPulsing = false;
    }

    /**
     * Appends dozing event to the logs
     * @param suppressed true if dozing is suppressed
     */
    public void traceDozingSuppressed(boolean suppressed) {
        mLogger.logDozingSuppressed(suppressed);
    }

    /**
     * Appends fling event to the logs
     */
    public void traceFling(boolean expand, boolean aboveThreshold, boolean thresholdNeeded,
            boolean screenOnFromTouch) {
        mLogger.logFling(expand, aboveThreshold, thresholdNeeded, screenOnFromTouch);
    }

    /**
     * Appends emergency call event to the logs
     */
    public void traceEmergencyCall() {
        mLogger.logEmergencyCall();
        mEmergencyCallStats.append();
    }

    /**
     * Appends keyguard bouncer changed event to the logs
     * @param showing true if the keyguard bouncer is showing, else false
     */
    public void traceKeyguardBouncerChanged(boolean showing) {
        mLogger.logKeyguardBouncerChanged(showing);
    }

    /**
     * Appends screen-on event to the logs
     */
    public void traceScreenOn() {
        mLogger.logScreenOn(mPulsing);
        (mPulsing ? mScreenOnPulsingStats : mScreenOnNotPulsingStats).append();
        mPulsing = false;
    }

    /**
     * Appends screen-off event to the logs
     * @param why reason the screen is off
     */
    public void traceScreenOff(int why) {
        mLogger.logScreenOff(why);
    }

    /**
     * Appends missed tick event to the logs
     * @param delay of the missed tick
     */
    public void traceMissedTick(String delay) {
        mLogger.logMissedTick(delay);
    }

    /**
     * Appends time tick scheduled event to the logs
     * @param when time tick scheduled at
     * @param triggerAt time tick trigger at
     */
    public void traceTimeTickScheduled(long when, long triggerAt) {
        mLogger.logTimeTickScheduled(when, triggerAt);
    }

    /**
     * Appends keyguard visibility change event to the logs
     * @param showing whether the keyguard is now showing
     */
    public void traceKeyguard(boolean showing) {
        mLogger.logKeyguardVisibilityChange(showing);
        if (!showing) mPulsing = false;
    }

    /**
     * Appends doze state changed event to the logs
     * @param state new DozeMachine state
     */
    public void traceState(DozeMachine.State state) {
        mLogger.logDozeStateChanged(state);
    }

    /**
     * Appends doze state changed sent to all DozeMachine parts event to the logs
     * @param state new DozeMachine state
     */
    public void traceDozeStateSendComplete(DozeMachine.State state) {
        mLogger.logStateChangedSent(state);
    }

    /**
     * Appends display state delayed by UDFPS event to the logs
     * @param delayedDisplayState the display screen state that was delayed
     */
    public void traceDisplayStateDelayedByUdfps(int delayedDisplayState) {
        mLogger.logDisplayStateDelayedByUdfps(delayedDisplayState);
    }

    /**
     * Appends display state changed event to the logs
     * @param displayState new DozeMachine state
     */
    public void traceDisplayState(int displayState) {
        mLogger.logDisplayStateChanged(displayState);
    }

    /**
     * Appends wake-display event to the logs.
     * @param wake if we're waking up or sleeping.
     */
    public void traceWakeDisplay(boolean wake, @Reason int reason) {
        mLogger.logWakeDisplay(wake, reason);
    }

    /**
     * Appends proximity result event to the logs
     * @param near true if near, else false
     * @param reason why proximity result was triggered
     */
    public void traceProximityResult(boolean near, long millis, @Reason int reason) {
        mLogger.logProximityResult(near, millis, reason);
        mProxStats[reason][near ? 0 : 1].append();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        synchronized (DozeLog.class) {
            pw.print("  Doze summary stats (for ");
            TimeUtils.formatDuration(System.currentTimeMillis() - mSince, pw);
            pw.println("):");
            mPickupPulseNearVibrationStats.dump(pw, "Pickup pulse (near vibration)");
            mPickupPulseNotNearVibrationStats.dump(pw, "Pickup pulse (not near vibration)");
            mNotificationPulseStats.dump(pw, "Notification pulse");
            mScreenOnPulsingStats.dump(pw, "Screen on (pulsing)");
            mScreenOnNotPulsingStats.dump(pw, "Screen on (not pulsing)");
            mEmergencyCallStats.dump(pw, "Emergency call");
            for (int i = 0; i < TOTAL_REASONS; i++) {
                final String reason = reasonToString(i);
                mProxStats[i][0].dump(pw, "Proximity near (" + reason + ")");
                mProxStats[i][1].dump(pw, "Proximity far (" + reason + ")");
            }
        }
    }

    /**
     * Appends pulse dropped event to logs
     */
    public void tracePulseDropped(boolean pulsePending, DozeMachine.State state, boolean blocked) {
        mLogger.logPulseDropped(pulsePending, state, blocked);
    }

    /**
     * Appends pulse dropped event to logs
     * @param reason why the pulse was dropped
     */
    public void tracePulseDropped(String reason) {
        mLogger.logPulseDropped(reason);
    }

    /**
     * Appends pulse touch displayed by prox sensor event to logs
     * @param disabled
     */
    public void tracePulseTouchDisabledByProx(boolean disabled) {
        mLogger.logPulseTouchDisabledByProx(disabled);
    }

    /**
     * Appends sensor triggered event to logs
     * @param reason why the sensor was triggered
     */
    public void traceSensor(@Reason int reason) {
        mLogger.logSensorTriggered(reason);
    }

    /**
     * Appends doze suppressed event to the logs
     * @param suppressedState The {@link DozeMachine.State} that was suppressed
     */
    public void traceDozeSuppressed(DozeMachine.State suppressedState) {
        mLogger.logDozeSuppressed(suppressedState);
    }

    /**
     * Appends new AOD sreen brightness to logs
     * @param brightness display brightness setting
     */
    public void traceDozeScreenBrightness(int brightness) {
        mLogger.logDozeScreenBrightness(brightness);
    }

    /**
    * Appends new AOD dimming scrim opacity to logs
    * @param scrimOpacity
     */
    public void traceSetAodDimmingScrim(float scrimOpacity) {
        mLogger.logSetAodDimmingScrim((long) scrimOpacity);
    }

    private class SummaryStats {
        private int mCount;

        public void append() {
            mCount++;
        }

        public void dump(PrintWriter pw, String type) {
            if (mCount == 0) return;
            pw.print("    ");
            pw.print(type);
            pw.print(": n=");
            pw.print(mCount);
            pw.print(" (");
            final double perHr = (double) mCount / (System.currentTimeMillis() - mSince)
                    * 1000 * 60 * 60;
            pw.print(perHr);
            pw.print("/hr)");
            pw.println();
        }
    }

    private final KeyguardUpdateMonitorCallback mKeyguardCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onEmergencyCallAction() {
            traceEmergencyCall();
        }

        @Override
        public void onKeyguardBouncerChanged(boolean bouncer) {
            traceKeyguardBouncerChanged(bouncer);
        }

        @Override
        public void onStartedWakingUp() {
            traceScreenOn();
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            traceScreenOff(why);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            traceKeyguard(showing);
        }
    };

    /**
     * Converts the reason (integer) to a user-readable string
     */
    public static String reasonToString(@Reason int pulseReason) {
        switch (pulseReason) {
            case PULSE_REASON_INTENT: return "intent";
            case PULSE_REASON_NOTIFICATION: return "notification";
            case PULSE_REASON_SENSOR_SIGMOTION: return "sigmotion";
            case REASON_SENSOR_PICKUP: return "pickup";
            case REASON_SENSOR_DOUBLE_TAP: return "doubletap";
            case PULSE_REASON_SENSOR_LONG_PRESS: return "longpress";
            case PULSE_REASON_DOCKING: return "docking";
            case PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN: return "reach-wakelockscreen";
            case REASON_SENSOR_WAKE_UP: return "presence-wakeup";
            case REASON_SENSOR_TAP: return "tap";
            case REASON_SENSOR_UDFPS_LONG_PRESS: return "udfps";
            case REASON_SENSOR_QUICK_PICKUP: return "quickPickup";
            default: throw new IllegalArgumentException("invalid reason: " + pulseReason);
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({PULSE_REASON_NONE, PULSE_REASON_INTENT, PULSE_REASON_NOTIFICATION,
            PULSE_REASON_SENSOR_SIGMOTION, REASON_SENSOR_PICKUP, REASON_SENSOR_DOUBLE_TAP,
            PULSE_REASON_SENSOR_LONG_PRESS, PULSE_REASON_DOCKING, REASON_SENSOR_WAKE_UP,
            PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN, REASON_SENSOR_TAP,
            REASON_SENSOR_UDFPS_LONG_PRESS, REASON_SENSOR_QUICK_PICKUP})
    public @interface Reason {}
    public static final int PULSE_REASON_NONE = -1;
    public static final int PULSE_REASON_INTENT = 0;
    public static final int PULSE_REASON_NOTIFICATION = 1;
    public static final int PULSE_REASON_SENSOR_SIGMOTION = 2;
    public static final int REASON_SENSOR_PICKUP = 3;
    public static final int REASON_SENSOR_DOUBLE_TAP = 4;
    public static final int PULSE_REASON_SENSOR_LONG_PRESS = 5;
    public static final int PULSE_REASON_DOCKING = 6;
    public static final int REASON_SENSOR_WAKE_UP = 7;
    public static final int PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN = 8;
    public static final int REASON_SENSOR_TAP = 9;
    public static final int REASON_SENSOR_UDFPS_LONG_PRESS = 10;
    public static final int REASON_SENSOR_QUICK_PICKUP = 11;

    public static final int TOTAL_REASONS = 12;
}
