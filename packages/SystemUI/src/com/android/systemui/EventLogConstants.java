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

/**
 * Constants to be passed as sysui_* eventlog parameters.
 */
public class EventLogConstants {
    /** The user swiped up on the lockscreen, unlocking the device. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_UP_UNLOCK = 1;
    /** The user swiped down on the lockscreen, going to the full shade. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_FULL_SHADE = 2;
    /** The user tapped in an empty area, causing the unlock hint to be shown. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_TAP_UNLOCK_HINT = 3;
    /** The user swiped inward on the camera icon, launching the camera. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_CAMERA = 4;
    /** The user swiped inward on the dialer icon, launching the dialer. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_SWIPE_DIALER = 5;
    /** The user tapped the lock, locking the device. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_TAP_LOCK = 6;
    /** The user tapped a notification, needs to tap again to launch. */
    public static final int SYSUI_LOCKSCREEN_GESTURE_TAP_NOTIFICATION_ACTIVATE = 7;
}
