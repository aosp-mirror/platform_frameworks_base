/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import static android.view.Display.DEFAULT_DISPLAY;

/**
 * Constants for interfacing with WindowManagerService and WindowManagerPolicyInternal.
 * @hide
 */
public interface WindowManagerPolicyConstants {
    // Policy flags.  These flags are also defined in frameworks/base/include/ui/Input.h.
    int FLAG_WAKE = 0x00000001;
    int FLAG_VIRTUAL = 0x00000002;

    int FLAG_INJECTED = 0x01000000;
    int FLAG_TRUSTED = 0x02000000;
    int FLAG_FILTERED = 0x04000000;
    int FLAG_DISABLE_KEY_REPEAT = 0x08000000;

    int FLAG_INTERACTIVE = 0x20000000;
    int FLAG_PASS_TO_USER = 0x40000000;

    // Flags for IActivityManager.keyguardGoingAway()
    int KEYGUARD_GOING_AWAY_FLAG_TO_SHADE = 1 << 0;
    int KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS = 1 << 1;
    int KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER = 1 << 2;

    // Flags used for indicating whether the internal and/or external input devices
    // of some type are available.
    int PRESENCE_INTERNAL = 1 << 0;
    int PRESENCE_EXTERNAL = 1 << 1;

    // Navigation bar position values
    int NAV_BAR_LEFT = 1 << 0;
    int NAV_BAR_RIGHT = 1 << 1;
    int NAV_BAR_BOTTOM = 1 << 2;

    /**
     * Broadcast sent when a user activity is detected.
     */
    String ACTION_USER_ACTIVITY_NOTIFICATION =
            "android.intent.action.USER_ACTIVITY_NOTIFICATION";

    /**
     * Sticky broadcast of the current HDMI plugged state.
     */
    String ACTION_HDMI_PLUGGED = "android.intent.action.HDMI_PLUGGED";

    /**
     * Extra in {@link #ACTION_HDMI_PLUGGED} indicating the state: true if
     * plugged in to HDMI, false if not.
     */
    String EXTRA_HDMI_PLUGGED_STATE = "state";

    /**
     * Set to {@code true} when intent was invoked from pressing the home key.
     * @hide
     */
    String EXTRA_FROM_HOME_KEY = "android.intent.extra.FROM_HOME_KEY";

    // TODO: move this to a more appropriate place.
    interface PointerEventListener {
        /**
         * 1. onPointerEvent will be called on the service.UiThread.
         * 2. motionEvent will be recycled after onPointerEvent returns so if it is needed later a
         * copy() must be made and the copy must be recycled.
         **/
        void onPointerEvent(MotionEvent motionEvent);

        /**
         * @see #onPointerEvent(MotionEvent)
         **/
        default void onPointerEvent(MotionEvent motionEvent, int displayId) {
            if (displayId == DEFAULT_DISPLAY) {
                onPointerEvent(motionEvent);
            }
        }
    }

    /** Screen turned off because of a device admin */
    int OFF_BECAUSE_OF_ADMIN = 1;
    /** Screen turned off because of power button */
    int OFF_BECAUSE_OF_USER = 2;
    /** Screen turned off because of timeout */
    int OFF_BECAUSE_OF_TIMEOUT = 3;

    int APPLICATION_LAYER = 2;
    int APPLICATION_MEDIA_SUBLAYER = -2;
    int APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1;
    int APPLICATION_PANEL_SUBLAYER = 1;
    int APPLICATION_SUB_PANEL_SUBLAYER = 2;
    int APPLICATION_ABOVE_SUB_PANEL_SUBLAYER = 3;

    /**
     * Convert the off reason to a human readable format.
     */
    static String offReasonToString(int why) {
        switch (why) {
            case OFF_BECAUSE_OF_ADMIN:
                return "OFF_BECAUSE_OF_ADMIN";
            case OFF_BECAUSE_OF_USER:
                return "OFF_BECAUSE_OF_USER";
            case OFF_BECAUSE_OF_TIMEOUT:
                return "OFF_BECAUSE_OF_TIMEOUT";
            default:
                return Integer.toString(why);
        }
    }
}
