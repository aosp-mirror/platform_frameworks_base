/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.doze;

import android.annotation.IntDef;

import com.android.systemui.log.RichEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event related to dozing. {@link DozeLog} stores and prints these events for debugging
 * and triaging purposes.
 */
public class DozeEvent extends RichEvent {
    public static final int TOTAL_EVENT_TYPES = 19;

    public DozeEvent(int logLevel, int type, String reason) {
        super(logLevel, type, reason);
    }

    /**
     * Event labels for each doze event
     * Index corresponds to the integer associated with each {@link EventType}
     */
    @Override
    public String[] getEventLabels() {
        final String[] events = new String[]{
                "PickupWakeup",
                "PulseStart",
                "PulseFinish",
                "NotificationPulse",
                "Dozing",
                "Fling",
                "EmergencyCall",
                "KeyguardBouncerChanged",
                "ScreenOn",
                "ScreenOff",
                "MissedTick",
                "TimeTickScheduled",
                "KeyguardVisibilityChanged",
                "DozeStateChanged",
                "WakeDisplay",
                "ProximityResult",
                "PulseDropped",
                "PulseDisabledByProx",
                "SensorTriggered"
        };

        if (events.length != TOTAL_EVENT_TYPES) {
            throw new IllegalStateException("DozeEvent events.length should match TOTAL_EVENT_TYPES"
                    + " events.length=" + events.length
                    + " TOTAL_EVENT_LENGTH=" + TOTAL_EVENT_TYPES);
        }
        return events;
    }

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
            case PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN: return "wakelockscreen";
            case REASON_SENSOR_WAKE_UP: return "wakeup";
            case REASON_SENSOR_TAP: return "tap";
            default: throw new IllegalArgumentException("invalid reason: " + pulseReason);
        }
    }

    /**
     * Builds a DozeEvent.
     */
    public static class DozeEventBuilder extends RichEvent.Builder<DozeEventBuilder> {
        @Override
        public DozeEventBuilder getBuilder() {
            return this;
        }

        @Override
        public RichEvent build() {
            return new DozeEvent(mLogLevel, mType, mReason);
        }
    }

    @IntDef({PICKUP_WAKEUP, PULSE_START, PULSE_FINISH, NOTIFICATION_PULSE, DOZING, FLING,
            EMERGENCY_CALL, KEYGUARD_BOUNCER_CHANGED, SCREEN_ON, SCREEN_OFF, MISSED_TICK,
            TIME_TICK_SCHEDULED, KEYGUARD_VISIBILITY_CHANGE, DOZE_STATE_CHANGED, WAKE_DISPLAY,
            PROXIMITY_RESULT, PULSE_DROPPED, PULSE_DISABLED_BY_PROX, SENSOR_TRIGGERED})
    /**
     * Types of DozeEvents
     */
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}
    public static final int PICKUP_WAKEUP = 0;
    public static final int PULSE_START = 1;
    public static final int PULSE_FINISH = 2;
    public static final int NOTIFICATION_PULSE = 3;
    public static final int DOZING = 4;
    public static final int FLING = 5;
    public static final int EMERGENCY_CALL = 6;
    public static final int KEYGUARD_BOUNCER_CHANGED = 7;
    public static final int SCREEN_ON = 8;
    public static final int SCREEN_OFF = 9;
    public static final int MISSED_TICK = 10;
    public static final int TIME_TICK_SCHEDULED = 11;
    public static final int KEYGUARD_VISIBILITY_CHANGE = 12;
    public static final int DOZE_STATE_CHANGED = 13;
    public static final int WAKE_DISPLAY = 14;
    public static final int PROXIMITY_RESULT = 15;
    public static final int PULSE_DROPPED = 16;
    public static final int PULSE_DISABLED_BY_PROX = 17;
    public static final int SENSOR_TRIGGERED = 18;

    public static final int TOTAL_REASONS = 10;
    @IntDef({PULSE_REASON_NONE, PULSE_REASON_INTENT, PULSE_REASON_NOTIFICATION,
            PULSE_REASON_SENSOR_SIGMOTION, REASON_SENSOR_PICKUP, REASON_SENSOR_DOUBLE_TAP,
            PULSE_REASON_SENSOR_LONG_PRESS, PULSE_REASON_DOCKING, REASON_SENSOR_WAKE_UP,
            PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN, REASON_SENSOR_TAP})
    @Retention(RetentionPolicy.SOURCE)
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
}
