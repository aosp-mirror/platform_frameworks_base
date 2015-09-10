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
    // Temporary constants go here, to await migration to MetricsConstants.
    // next value is 239;
    public static final int ACTION_ASSIST_LONG_PRESS = 239;
    public static final int FINGERPRINT_ENROLLING = 240;
    public static final int FINGERPRINT_FIND_SENSOR = 241;
    public static final int FINGERPRINT_ENROLL_FINISH = 242;
    public static final int FINGERPRINT_ENROLL_INTRO = 243;
    public static final int FINGERPRINT_ENROLL_ONBOARD = 244;
    public static final int FINGERPRINT_ENROLL_SIDECAR = 245;
    public static final int FINGERPRINT_ENROLLING_SETUP = 246;
    public static final int FINGERPRINT_FIND_SENSOR_SETUP = 247;
    public static final int FINGERPRINT_ENROLL_FINISH_SETUP = 248;
    public static final int FINGERPRINT_ENROLL_INTRO_SETUP = 249;
    public static final int FINGERPRINT_ENROLL_ONBOARD_SETUP = 250;
    public static final int ACTION_FINGERPRINT_ENROLL = 251;
    public static final int ACTION_FINGERPRINT_AUTH = 252;
    public static final int ACTION_FINGERPRINT_DELETE = 253;
    public static final int ACTION_FINGERPRINT_RENAME = 254;
    public static final int ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE = 255;
    public static final int ACTION_WIGGLE_CAMERA_GESTURE = 256;

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
