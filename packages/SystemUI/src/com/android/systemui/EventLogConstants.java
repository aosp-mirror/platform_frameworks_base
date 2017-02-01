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

package com.android.systemui;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Constants to be passed as sysui_* eventlog parameters.
 */
public class EventLogConstants {
    /** The user swiped up on the lockscreen, unlocking the device. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_UP_UNLOCK = 1;
    /** The user swiped down on the lockscreen, going to the full shade. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_FULL_SHADE = 2;
    /** The user tapped in an empty area, causing the unlock hint to be shown. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_TAP_UNLOCK_HINT = 3;
    /** The user swiped inward on the camera icon, launching the camera. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_CAMERA = 4;
    /** The user swiped inward on the dialer icon, launching the dialer. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_DIALER = 5;
    /** The user tapped the lock, locking the device. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_TAP_LOCK = 6;
    /** The user tapped a notification, needs to tap again to launch. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_TAP_NOTIFICATION_ACTIVATE = 7;
    /** The user swiped down to open quick settings, from keyguard. */
    private static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_QS = 8;
    /** The user swiped down to open quick settings, from shade. */
    private static final int SYSUI_SHADE_GESTURE_SWIPE_DOWN_QS = 9;
    /** The user tapped on the status bar to open quick settings, from shade. */
    private static final int SYSUI_TAP_TO_OPEN_QS = 10;

    public static final int[] METRICS_GESTURE_TYPE_MAP = {
            MetricsEvent.VIEW_UNKNOWN,         // there is no type 0
            MetricsEvent.ACTION_LS_UNLOCK,     // SYSUI_LOCKSCREEN_GESTURE_SWIPE_UP_UNLOCK
            MetricsEvent.ACTION_LS_SHADE,      // SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_FULL_SHADE
            MetricsEvent.ACTION_LS_HINT,       // SYSUI_LOCKSCREEN_GESTURE_TAP_UNLOCK_HINT
            MetricsEvent.ACTION_LS_CAMERA,     // SYSUI_LOCKSCREEN_GESTURE_SWIPE_CAMERA
            MetricsEvent.ACTION_LS_DIALER,     // SYSUI_LOCKSCREEN_GESTURE_SWIPE_DIALER
            MetricsEvent.ACTION_LS_LOCK,       // SYSUI_LOCKSCREEN_GESTURE_TAP_LOCK
            MetricsEvent.ACTION_LS_NOTE,       // SYSUI_LOCKSCREEN_GESTURE_TAP_NOTIFICATION_ACTIVATE
            MetricsEvent.ACTION_LS_QS,         // SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_QS
            MetricsEvent.ACTION_SHADE_QS_PULL, // SYSUI_SHADE_GESTURE_SWIPE_DOWN_QS
            MetricsEvent.ACTION_SHADE_QS_TAP   // SYSUI_TAP_TO_OPEN_QS
    };

    /** Secondary user tries binding to the system sysui service */
    public static final int SYSUI_RECENTS_CONNECTION_USER_BIND_SERVICE = 1;
    /** Secondary user is bound to the system sysui service */
    public static final int SYSUI_RECENTS_CONNECTION_USER_SYSTEM_BOUND = 2;
    /** Secondary user loses connection after system sysui has died */
    public static final int SYSUI_RECENTS_CONNECTION_USER_SYSTEM_UNBOUND = 3;
    /** System sysui registers secondary user's callbacks */
    public static final int SYSUI_RECENTS_CONNECTION_SYSTEM_REGISTER_USER = 4;
    /** System sysui unregisters secondary user's callbacks (after death) */
    public static final int SYSUI_RECENTS_CONNECTION_SYSTEM_UNREGISTER_USER = 5;
}
