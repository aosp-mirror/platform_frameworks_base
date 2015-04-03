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

/**
 * Log all the things.
 *
 * @hide
 */
public class MetricsLogger implements MetricsConstants {
    // These constants are temporary, they should migrate to MetricsConstants.
    public static final int APPLICATIONS_ADVANCED = 132;
    public static final int LOCATION_SCANNING = 133;
    public static final int MANAGE_APPLICATIONS_ALL = 134;
    public static final int MANAGE_APPLICATIONS_NOTIFICATIONS = 135;

    public static final int ACTION_WIFI_ADD_NETWORK = 136;
    public static final int ACTION_WIFI_CONNECT = 137;
    public static final int ACTION_WIFI_FORCE_SCAN = 138;
    public static final int ACTION_WIFI_FORGET = 139;
    public static final int ACTION_WIFI_OFF = 140;
    public static final int ACTION_WIFI_ON = 141;

    public static final int MANAGE_PERMISSIONS = 142;
    public static final int NOTIFICATION_ZEN_MODE_PRIORITY = 143;
    public static final int NOTIFICATION_ZEN_MODE_AUTOMATION = 144;

    public static void visible(Context context, int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 100);
    }

    public static void hidden(Context context, int category) {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 0);
    }

    public static void action(Context context, int category) {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiAction(category);
    }
}
