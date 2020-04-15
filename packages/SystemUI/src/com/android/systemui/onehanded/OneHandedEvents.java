/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.onehanded;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;

/**
 *  Interesting events related to the One-Handed.
 */
public class OneHandedEvents {
    private static final String TAG = "OneHandedEvents";

    public static Callback sCallback;
    @VisibleForTesting
    static UiEventLogger sUiEventLogger = new UiEventLoggerImpl();

    /**
     * One-Handed event types
     */
    // Triggers
    public static final int EVENT_ONE_HANDED_TRIGGER_GESTURE_IN = 0;
    public static final int EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT = 1;
    public static final int EVENT_ONE_HANDED_TRIGGER_OVERSPACE_OUT = 2;
    public static final int EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT = 3;
    public static final int EVENT_ONE_HANDED_TRIGGER_ROTATION_OUT = 4;
    public static final int EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT = 5;
    public static final int EVENT_ONE_HANDED_TRIGGER_TIMEOUT_OUT = 6;
    public static final int EVENT_ONE_HANDED_TRIGGER_SCREEN_OFF_OUT = 7;
    // Settings toggles
    public static final int EVENT_ONE_HANDED_SETTINGS_ENABLED_ON = 8;
    public static final int EVENT_ONE_HANDED_SETTINGS_ENABLED_OFF = 9;
    public static final int EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON = 10;
    public static final int EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF = 11;
    public static final int EVENT_ONE_HANDED_SETTINGS_TIMEOUT_EXIT_ON = 12;
    public static final int EVENT_ONE_HANDED_SETTINGS_TIMEOUT_EXIT_OFF = 13;
    public static final int EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_NEVER = 14;
    public static final int EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_4 = 15;
    public static final int EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_8 = 16;
    public static final int EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_12 = 17;

    private static final String[] EVENT_TAGS = {
            "one_handed_trigger_gesture_in",
            "one_handed_trigger_gesture_out",
            "one_handed_trigger_overspace_out",
            "one_handed_trigger_pop_ime_out",
            "one_handed_trigger_rotation_out",
            "one_handed_trigger_app_taps_out",
            "one_handed_trigger_timeout_out",
            "one_handed_trigger_screen_off_out",
            "one_handed_settings_enabled_on",
            "one_handed_settings_enabled_off",
            "one_handed_settings_app_taps_exit_on",
            "one_handed_settings_app_taps_exit_off",
            "one_handed_settings_timeout_exit_on",
            "one_handed_settings_timeout_exit_off",
            "one_handed_settings_timeout_seconds_never",
            "one_handed_settings_timeout_seconds_4",
            "one_handed_settings_timeout_seconds_8",
            "one_handed_settings_timeout_seconds_12"
    };

    /**
     * Events definition that related to One-Handed gestures.
     */
    @VisibleForTesting
    public enum OneHandedTriggerEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "One-Handed trigger in via NavigationBar area")
        ONE_HANDED_TRIGGER_GESTURE_IN(366),

        @UiEvent(doc = "One-Handed trigger out via NavigationBar area")
        ONE_HANDED_TRIGGER_GESTURE_OUT(367),

        @UiEvent(doc = "One-Handed trigger out via Overspace area")
        ONE_HANDED_TRIGGER_OVERSPACE_OUT(368),

        @UiEvent(doc = "One-Handed trigger out while IME pop up")
        ONE_HANDED_TRIGGER_POP_IME_OUT(369),

        @UiEvent(doc = "One-Handed trigger out while device rotation to landscape")
        ONE_HANDED_TRIGGER_ROTATION_OUT(370),

        @UiEvent(doc = "One-Handed trigger out when an Activity is launching")
        ONE_HANDED_TRIGGER_APP_TAPS_OUT(371),

        @UiEvent(doc = "One-Handed trigger out when one-handed mode times up")
        ONE_HANDED_TRIGGER_TIMEOUT_OUT(372),

        @UiEvent(doc = "One-Handed trigger out when screen off")
        ONE_HANDED_TRIGGER_SCREEN_OFF_OUT(449);

        private final int mId;

        OneHandedTriggerEvent(int id) {
            mId = id;
        }

        public int getId() {
            return mId;
        }
    }

    /**
     * Events definition that related to Settings toggles.
     */
    @VisibleForTesting
    public enum OneHandedSettingsTogglesEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "One-Handed mode enabled toggle on")
        ONE_HANDED_SETTINGS_TOGGLES_ENABLED_ON(356),

        @UiEvent(doc = "One-Handed mode enabled toggle off")
        ONE_HANDED_SETTINGS_TOGGLES_ENABLED_OFF(357),

        @UiEvent(doc = "One-Handed mode app-taps-exit toggle on")
        ONE_HANDED_SETTINGS_TOGGLES_APP_TAPS_EXIT_ON(358),

        @UiEvent(doc = "One-Handed mode app-taps-exit toggle off")
        ONE_HANDED_SETTINGS_TOGGLES_APP_TAPS_EXIT_OFF(359),

        @UiEvent(doc = "One-Handed mode timeout-exit toggle on")
        ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_EXIT_ON(360),

        @UiEvent(doc = "One-Handed mode timeout-exit toggle off")
        ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_EXIT_OFF(361),

        @UiEvent(doc = "One-Handed mode timeout value changed to never timeout")
        ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_NEVER(362),

        @UiEvent(doc = "One-Handed mode timeout value changed to 4 seconds")
        ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_4(363),

        @UiEvent(doc = "One-Handed mode timeout value changed to 8 seconds")
        ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_8(364),

        @UiEvent(doc = "One-Handed mode timeout value changed to 12 seconds")
        ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_12(365);

        private final int mId;

        OneHandedSettingsTogglesEvent(int id) {
            mId = id;
        }

        public int getId() {
            return mId;
        }
    }


    /**
     * Logs an event to the system log, to sCallback if present, and to the logEvent destinations.
     * @param tag One of the EVENT_* codes above.
     */
    public static void writeEvent(int tag) {
        final long time = System.currentTimeMillis();
        logEvent(tag);
        if (sCallback != null) {
            sCallback.writeEvent(time, tag);
        }
    }

    /**
     * Logs an event to the UiEvent (Westworld) logging.
     * @param event One of the EVENT_* codes above.
     * @return String a readable description of the event.  Begins "writeEvent <tag_description>"
     * if the tag is valid.
     */
    public static String logEvent(int event) {
        if (event >= EVENT_TAGS.length) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("writeEvent ").append(EVENT_TAGS[event]);
        switch (event) {
            // Triggers
            case EVENT_ONE_HANDED_TRIGGER_GESTURE_IN:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_GESTURE_IN);
                break;
            case EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_GESTURE_OUT);
                break;
            case EVENT_ONE_HANDED_TRIGGER_OVERSPACE_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_OVERSPACE_OUT);
                break;
            case EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_POP_IME_OUT);
                break;
            case EVENT_ONE_HANDED_TRIGGER_ROTATION_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_ROTATION_OUT);
                break;
            case EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_APP_TAPS_OUT);
                break;
            case EVENT_ONE_HANDED_TRIGGER_TIMEOUT_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_TIMEOUT_OUT);
                break;
            case EVENT_ONE_HANDED_TRIGGER_SCREEN_OFF_OUT:
                sUiEventLogger.log(OneHandedTriggerEvent.ONE_HANDED_TRIGGER_SCREEN_OFF_OUT);
                break;
            // Settings
            case EVENT_ONE_HANDED_SETTINGS_ENABLED_ON:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_ENABLED_ON);
                break;
            case EVENT_ONE_HANDED_SETTINGS_ENABLED_OFF:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_ENABLED_OFF);
                break;
            case EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_APP_TAPS_EXIT_ON);
                break;
            case EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_APP_TAPS_EXIT_OFF);
                break;
            case EVENT_ONE_HANDED_SETTINGS_TIMEOUT_EXIT_ON:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_EXIT_ON);
                break;
            case EVENT_ONE_HANDED_SETTINGS_TIMEOUT_EXIT_OFF:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_EXIT_OFF);
                break;
            case EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_NEVER:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_NEVER);
                break;
            case EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_4:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_4);
                break;
            case EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_8:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_8);
                break;
            case EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_12:
                sUiEventLogger.log(OneHandedSettingsTogglesEvent
                        .ONE_HANDED_SETTINGS_TOGGLES_TIMEOUT_SECONDS_12);
                break;
            default:
                // Do nothing
                break;
        }
        return sb.toString();
    }

    /**
     * An interface for logging an event to the system log, if Callback present.
     */
    public interface Callback {
        /**
         *
         * @param time System current time.
         * @param tag Event tag.
         */
        void writeEvent(long time, int tag);
    }
}
