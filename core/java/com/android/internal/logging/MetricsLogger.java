/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.logging;


import android.content.Context;
import android.os.Build;
import android.view.View;

/**
 * Log all the things.
 *
 * @hide
 */
public class MetricsLogger implements MetricsConstants {
    // These constants are temporary, they should migrate to MetricsConstants.

    public static final int NOTIFICATION_ZEN_MODE_SCHEDULE_RULE = 144;
    public static final int NOTIFICATION_ZEN_MODE_EXTERNAL_RULE = 145;
    public static final int ACTION_BAN_APP_NOTES = 146;
    public static final int NOTIFICATION_ZEN_MODE_EVENT_RULE = 147;
    public static final int ACTION_DISMISS_ALL_NOTES = 148;
    public static final int QS_DND_DETAILS = 149;
    public static final int QS_BLUETOOTH_DETAILS = 150;
    public static final int QS_CAST_DETAILS = 151;
    public static final int QS_WIFI_DETAILS = 152;
    public static final int QS_WIFI_TOGGLE = 153;
    public static final int QS_BLUETOOTH_TOGGLE = 154;
    public static final int QS_CELLULAR_TOGGLE = 155;
    public static final int QS_SWITCH_USER = 156;
    public static final int QS_CAST_SELECT = 157;
    public static final int QS_CAST_DISCONNECT = 158;
    public static final int ACTION_BLUETOOTH_TOGGLE = 159;
    public static final int ACTION_BLUETOOTH_SCAN = 160;
    public static final int ACTION_BLUETOOTH_RENAME = 161;
    public static final int ACTION_BLUETOOTH_FILES = 162;
    public static final int QS_DND_TIME = 163;
    public static final int QS_DND_CONDITION_SELECT = 164;
    public static final int QS_DND_ZEN_SELECT = 165;
    public static final int QS_DND_TOGGLE = 166;
    public static final int ACTION_ZEN_ALLOW_REMINDERS = 167;
    public static final int ACTION_ZEN_ALLOW_EVENTS = 168;
    public static final int ACTION_ZEN_ALLOW_MESSAGES = 169;
    public static final int ACTION_ZEN_ALLOW_CALLS = 170;
    public static final int ACTION_ZEN_ALLOW_REPEAT_CALLS = 171;
    public static final int ACTION_ZEN_ADD_RULE = 172;
    public static final int ACTION_ZEN_ADD_RULE_OK = 173;
    public static final int ACTION_ZEN_DELETE_RULE = 174;
    public static final int ACTION_ZEN_DELETE_RULE_OK = 175;
    public static final int ACTION_ZEN_ENABLE_RULE = 176;
    public static final int ACTION_AIRPLANE_TOGGLE = 177;
    public static final int ACTION_CELL_DATA_TOGGLE = 178;
    public static final int NOTIFICATION_ACCESS = 179;
    public static final int NOTIFICATION_ZEN_MODE_ACCESS = 180;

    public static void visible(Context context, int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 100);
    }

    public static void hidden(Context context, int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 0);
    }

    public static void visibility(Context context, int category, boolean visibile)
            throws IllegalArgumentException {
        if (visibile) {
            visible(context, category);
        } else {
            hidden(context, category);
        }
    }

    public static void visibility(Context context, int category, int vis)
            throws IllegalArgumentException {
        visibility(context, category, vis == View.VISIBLE);
    }

    public static void action(Context context, int category) {
        action(context, category, "");
    }

    public static void action(Context context, int category, int value) {
        action(context, category, Integer.toString(value));
    }

    public static void action(Context context, int category, boolean value) {
        action(context, category, Boolean.toString(value));
    }

    public static void action(Context context, int category, String pkg) {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiAction(category, pkg);
    }

    /** Add an integer value to the monotonically increasing counter with the given name. */
    public static void count(Context context, String name, int value) {
        EventLogTags.writeSysuiCount(name, value);
    }

    /** Increment the bucket with the integer label on the histogram with the given name. */
    public static void histogram(Context context, String name, int bucket) {
        EventLogTags.writeSysuiHistogram(name, bucket);
    }
}
