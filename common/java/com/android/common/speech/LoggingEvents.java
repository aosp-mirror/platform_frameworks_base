/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.common.speech;

/**
 * Logging event constants used for Voice Search and VoiceIME. These are the
 * keys and values of extras to be specified in logging broadcast intents.
 * This class is used by clients of the android.speech APIs to log how the
 * user interacts with the IME settings and speech recognition result.
 */
public class LoggingEvents {
    // The name of the broadcast intent for logging.
    public static final String ACTION_LOG_EVENT = "com.android.common.speech.LOG_EVENT";

    // The extra key used for the name of the app being logged.
    public static final String EXTRA_APP_NAME = "app_name";

    // The extra key used for the name of the app issuing the VoiceSearch
    // or VoiceIME request
    public static final String EXTRA_CALLING_APP_NAME = "";

    // The extra key used for the event value. The possible event values depend
    // on the app being logged for, and are defined in the subclasses below.
    public static final String EXTRA_EVENT = "extra_event";

    // The extra key used to log the time in milliseconds at which the EXTRA_EVENT
    // occurred in the client.
    public static final String EXTRA_TIMESTAMP = "timestamp";

    // The extra key used (with a boolean value of 'true') as a way to trigger a
    // flush of the log events to the server.
    public static final String EXTRA_FLUSH = "flush";

    /**
     * Logging event constants for voice search. Below are the extra values for
     * {@link LoggingEvents#EXTRA_EVENT}, clustered with keys to additional
     * extras for some events that need to be included as additional fields in
     * the event. Note that this is not representative of *all* voice search
     * events - only the ones that need to be reported from outside the voice
     * search app, such as from Browser.
     */
    public class VoiceSearch {
        // The app name to be used for logging VoiceSearch events.
        public static final String APP_NAME = "googlemobile";

        public static final int RETRY = 0;

        public static final int N_BEST_REVEAL = 1;

        public static final int N_BEST_CHOOSE = 2;
        public static final String EXTRA_N_BEST_CHOOSE_INDEX = "index";  // value should be int

        public static final int QUERY_UPDATED = 3;
        public static final String EXTRA_QUERY_UPDATED_VALUE = "value";  // value should be String
    }

    /**
     * Logging event constants for VoiceIME. Below are the extra values for
     * {@link LoggingEvents#EXTRA_EVENT}, clustered with keys to additional
     * extras for some events that need to be included as additional fields in
     * the event.
     */
    public class VoiceIme {
        // The app name to be used for logging VoiceIME events.
        public static final String APP_NAME = "voiceime";

        public static final int KEYBOARD_WARNING_DIALOG_SHOWN = 0;

        public static final int KEYBOARD_WARNING_DIALOG_DISMISSED = 1;

        public static final int KEYBOARD_WARNING_DIALOG_OK = 2;

        public static final int KEYBOARD_WARNING_DIALOG_CANCEL = 3;

        public static final int SETTINGS_WARNING_DIALOG_SHOWN = 4;

        public static final int SETTINGS_WARNING_DIALOG_DISMISSED = 5;

        public static final int SETTINGS_WARNING_DIALOG_OK = 6;

        public static final int SETTINGS_WARNING_DIALOG_CANCEL = 7;

        public static final int SWIPE_HINT_DISPLAYED = 8;

        public static final int PUNCTUATION_HINT_DISPLAYED = 9;

        public static final int CANCEL_DURING_LISTENING = 10;

        public static final int CANCEL_DURING_WORKING = 11;

        public static final int CANCEL_DURING_ERROR = 12;

        public static final int ERROR = 13;
        public static final String EXTRA_ERROR_CODE = "code";  // value should be int

        public static final int START = 14;
        public static final String EXTRA_START_LOCALE = "locale";  // value should be String
        public static final String EXTRA_START_SWIPE = "swipe";  // value should be boolean
        // EXTRA_START_SWIPE is deprecated; switch to EXTRA_START_METHOD instead
        public static final String EXTRA_START_METHOD = "method";  // value should be int below
        public static final int START_METHOD_BUTTON = 1;
        public static final int START_METHOD_SWIPE = 2;
        public static final int START_METHOD_MOTION = 3;

        public static final int VOICE_INPUT_DELIVERED = 15;

        public static final int N_BEST_CHOOSE = 16;
        public static final String EXTRA_N_BEST_CHOOSE_INDEX = "index";  // value should be int

        public static final int TEXT_MODIFIED = 17;
        public static final String EXTRA_TEXT_MODIFIED_LENGTH = "length";  // value should be int
        public static final String EXTRA_TEXT_MODIFIED_TYPE = "type";  // value should be int below
        public static final int TEXT_MODIFIED_TYPE_CHOOSE_SUGGESTION = 1;
        public static final int TEXT_MODIFIED_TYPE_TYPING_DELETION = 2;
        public static final int TEXT_MODIFIED_TYPE_TYPING_INSERTION = 3;
        public static final int TEXT_MODIFIED_TYPE_TYPING_INSERTION_PUNCTUATION = 4;

        public static final int INPUT_ENDED = 18;

        public static final int VOICE_INPUT_SETTING_ENABLED = 19;

        public static final int VOICE_INPUT_SETTING_DISABLED = 20;

        public static final int IME_TEXT_ACCEPTED = 21;
    }

}
