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

import android.util.TimeUtils;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.DumpController;
import com.android.systemui.log.SysuiLog;

import java.io.PrintWriter;
import java.util.Date;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Logs doze events for debugging and triaging purposes. Logs are dumped in bugreports or on demand:
 *      adb shell dumpsys activity service com.android.systemui/.SystemUIService \
 *      dependency DumpController DozeLog
 */
@Singleton
public class DozeLog extends SysuiLog<DozeEvent> {
    private static final String TAG = "DozeLog";

    private DozeEvent mRecycledEvent;

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
    public DozeLog(KeyguardUpdateMonitor keyguardUpdateMonitor, DumpController dumpController) {
        super(dumpController, TAG, MAX_DOZE_DEBUG_LOGS, MAX_DOZE_LOGS);
        mSince = System.currentTimeMillis();
        mPickupPulseNearVibrationStats = new SummaryStats();
        mPickupPulseNotNearVibrationStats = new SummaryStats();
        mNotificationPulseStats = new SummaryStats();
        mScreenOnPulsingStats = new SummaryStats();
        mScreenOnNotPulsingStats = new SummaryStats();
        mEmergencyCallStats = new SummaryStats();
        mProxStats = new SummaryStats[DozeEvent.TOTAL_REASONS][2];
        for (int i = 0; i < DozeEvent.TOTAL_REASONS; i++) {
            mProxStats[i][0] = new SummaryStats();
            mProxStats[i][1] = new SummaryStats();
        }

        if (keyguardUpdateMonitor != null) {
            keyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        }
    }

    /**
     * Appends pickup wakeup event to the logs
     */
    public void tracePickupWakeUp(boolean withinVibrationThreshold) {
        log(DozeEvent.PICKUP_WAKEUP, "withinVibrationThreshold=" + withinVibrationThreshold);
        if (mEnabled) {
            (withinVibrationThreshold ? mPickupPulseNearVibrationStats
                    : mPickupPulseNotNearVibrationStats).append();
        }
    }

    /**
     * Appends pulse started event to the logs.
     * @param reason why the pulse started
     */
    public void tracePulseStart(@DozeEvent.Reason int reason) {
        log(DozeEvent.PULSE_START, DozeEvent.reasonToString(reason));
        if (mEnabled) mPulsing = true;
    }

    /**
     * Appends pulse finished event to the logs
     */
    public void tracePulseFinish() {
        log(DozeEvent.PULSE_FINISH);
        if (mEnabled) mPulsing = false;
    }

    /**
     * Appends pulse event to the logs
     */
    public void traceNotificationPulse() {
        log(DozeEvent.NOTIFICATION_PULSE);
        if (mEnabled) mNotificationPulseStats.append();
    }

    /**
     * Appends dozing event to the logs
     * @param dozing true if dozing, else false
     */
    public void traceDozing(boolean dozing) {
        log(DozeEvent.DOZING, "dozing=" + dozing);
        if (mEnabled) mPulsing = false;
    }

    /**
     * Appends fling event to the logs
     */
    public void traceFling(boolean expand, boolean aboveThreshold, boolean thresholdNeeded,
            boolean screenOnFromTouch) {
        log(DozeEvent.FLING, "expand=" + expand
                + " aboveThreshold=" + aboveThreshold
                + " thresholdNeeded=" + thresholdNeeded
                + " screenOnFromTouch=" + screenOnFromTouch);
    }

    /**
     * Appends emergency call event to the logs
     */
    public void traceEmergencyCall() {
        log(DozeEvent.EMERGENCY_CALL);
        if (mEnabled) mEmergencyCallStats.append();
    }

    /**
     * Appends keyguard bouncer changed event to the logs
     * @param showing true if the keyguard bouncer is showing, else false
     */
    public void traceKeyguardBouncerChanged(boolean showing) {
        log(DozeEvent.KEYGUARD_BOUNCER_CHANGED, "showing=" + showing);
    }

    /**
     * Appends screen-on event to the logs
     */
    public void traceScreenOn() {
        log(DozeEvent.SCREEN_ON, "pulsing=" + mPulsing);
        if (mEnabled) {
            (mPulsing ? mScreenOnPulsingStats : mScreenOnNotPulsingStats).append();
            mPulsing = false;
        }
    }

    /**
     * Appends screen-off event to the logs
     * @param why reason the screen is off
     */
    public void traceScreenOff(int why) {
        log(DozeEvent.SCREEN_OFF, "why=" + why);
    }

    /**
     * Appends missed tick event to the logs
     * @param delay of the missed tick
     */
    public void traceMissedTick(String delay) {
        log(DozeEvent.MISSED_TICK, "delay=" + delay);
    }

    /**
     * Appends time tick scheduled event to the logs
     * @param when time tick scheduled at
     * @param triggerAt time tick trigger at
     */
    public void traceTimeTickScheduled(long when, long triggerAt) {
        log(DozeEvent.TIME_TICK_SCHEDULED,
                "scheduledAt=" + DATE_FORMAT.format(new Date(when))
                + " triggerAt=" + DATE_FORMAT.format(new Date(triggerAt)));
    }

    /**
     * Appends keyguard visibility change event to the logs
     * @param showing whether the keyguard is now showing
     */
    public void traceKeyguard(boolean showing) {
        log(DozeEvent.KEYGUARD_VISIBILITY_CHANGE, "showing=" + showing);
        if (mEnabled && !showing) mPulsing = false;
    }

    /**
     * Appends doze state changed event to the logs
     * @param state new DozeMachine state
     */
    public void traceState(DozeMachine.State state) {
        log(DozeEvent.DOZE_STATE_CHANGED, state.name());
    }

    /**
     * Appends wake-display event to the logs.
     * @param wake if we're waking up or sleeping.
     */
    public void traceWakeDisplay(boolean wake) {
        log(DozeEvent.WAKE_DISPLAY, "wake=" + wake);
    }

    /**
     * Appends proximity result event to the logs
     * @param near true if near, else false
     * @param millis
     * @param reason why proximity result was triggered
     */
    public void traceProximityResult(boolean near, long millis, @DozeEvent.Reason int reason) {
        log(DozeEvent.PROXIMITY_RESULT,
                " reason=" + DozeEvent.reasonToString(reason)
                        + " near=" + near
                        + " millis=" + millis);
        if (mEnabled) mProxStats[reason][near ? 0 : 1].append();
    }

    /**
     * Prints doze log timeline and consolidated stats
     * @param pw
     */
    public void dump(PrintWriter pw) {
        synchronized (DozeLog.class) {
            super.dump(null, pw, null); // prints timeline

            pw.print("  Doze summary stats (for ");
            TimeUtils.formatDuration(System.currentTimeMillis() - mSince, pw);
            pw.println("):");
            mPickupPulseNearVibrationStats.dump(pw, "Pickup pulse (near vibration)");
            mPickupPulseNotNearVibrationStats.dump(pw, "Pickup pulse (not near vibration)");
            mNotificationPulseStats.dump(pw, "Notification pulse");
            mScreenOnPulsingStats.dump(pw, "Screen on (pulsing)");
            mScreenOnNotPulsingStats.dump(pw, "Screen on (not pulsing)");
            mEmergencyCallStats.dump(pw, "Emergency call");
            for (int i = 0; i < DozeEvent.TOTAL_REASONS; i++) {
                final String reason = DozeEvent.reasonToString(i);
                mProxStats[i][0].dump(pw, "Proximity near (" + reason + ")");
                mProxStats[i][1].dump(pw, "Proximity far (" + reason + ")");
            }
        }
    }

    private void log(@DozeEvent.EventType int eventType) {
        log(eventType, "");
    }

    private void log(@DozeEvent.EventType int eventType, String msg) {
        if (mRecycledEvent != null) {
            mRecycledEvent = log(mRecycledEvent.init(eventType, msg));
        } else {
            mRecycledEvent = log(new DozeEvent().init(eventType, msg));
        }
    }

    /**
     * Appends pulse dropped event to logs
     */
    public void tracePulseDropped(boolean pulsePending, DozeMachine.State state, boolean blocked) {
        log(DozeEvent.PULSE_DROPPED, "pulsePending=" + pulsePending + " state="
                + state.name() + " blocked=" + blocked);
    }

    /**
     * Appends pulse dropped event to logs
     * @param reason why the pulse was dropped
     */
    public void tracePulseDropped(String reason) {
        log(DozeEvent.PULSE_DROPPED, "why=" + reason);
    }

    /**
     * Appends pulse touch displayed by prox sensor event to logs
     * @param disabled
     */
    public void tracePulseTouchDisabledByProx(boolean disabled) {
        log(DozeEvent.PULSE_DISABLED_BY_PROX, "disabled=" + disabled);
    }

    /**
     * Appends sensor triggered event to logs
     * @param reason why the sensor was triggered
     */
    public void traceSensor(@DozeEvent.Reason int reason) {
        log(DozeEvent.SENSOR_TRIGGERED, "type=" + DozeEvent.reasonToString(reason));
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

    private static final int MAX_DOZE_DEBUG_LOGS = 400;
    private static final int MAX_DOZE_LOGS = 50;
}
