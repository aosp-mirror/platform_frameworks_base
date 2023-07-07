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

import static android.os.IInputConstants.POLICY_FLAG_INJECTED_FROM_ACCESSIBILITY;

import android.annotation.IntDef;
import android.os.PowerManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constants for interfacing with WindowManagerService and WindowManagerPolicyInternal.
 * @hide
 */
public interface WindowManagerPolicyConstants {
    // Policy flags.  These flags are also defined in frameworks/base/include/ui/Input.h and
    // frameworks/native/libs/input/android/os/IInputConstants.aidl
    int FLAG_WAKE = 0x00000001;
    int FLAG_VIRTUAL = 0x00000002;

    int FLAG_INJECTED_FROM_ACCESSIBILITY = POLICY_FLAG_INJECTED_FROM_ACCESSIBILITY;
    int FLAG_INJECTED = 0x01000000;
    int FLAG_TRUSTED = 0x02000000;
    int FLAG_FILTERED = 0x04000000;
    int FLAG_DISABLE_KEY_REPEAT = 0x08000000;

    int FLAG_INTERACTIVE = 0x20000000;
    int FLAG_PASS_TO_USER = 0x40000000;

    // Flags for IActivityTaskManager.keyguardGoingAway()
    int KEYGUARD_GOING_AWAY_FLAG_TO_SHADE = 1 << 0;
    int KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS = 1 << 1;
    int KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER = 1 << 2;
    int KEYGUARD_GOING_AWAY_FLAG_SUBTLE_WINDOW_ANIMATIONS = 1 << 3;
    int KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT = 1 << 4;

    // Flags used for indicating whether the internal and/or external input devices
    // of some type are available.
    int PRESENCE_INTERNAL = 1 << 0;
    int PRESENCE_EXTERNAL = 1 << 1;

    // Navigation bar position values
    int NAV_BAR_INVALID = -1;
    int NAV_BAR_LEFT = 1 << 0;
    int NAV_BAR_RIGHT = 1 << 1;
    int NAV_BAR_BOTTOM = 1 << 2;

    // Navigation bar interaction modes
    int NAV_BAR_MODE_3BUTTON = 0;
    int NAV_BAR_MODE_2BUTTON = 1;
    int NAV_BAR_MODE_GESTURAL = 2;

    // Associated overlays for each nav bar mode
    String NAV_BAR_MODE_3BUTTON_OVERLAY = "com.android.internal.systemui.navbar.threebutton";
    String NAV_BAR_MODE_2BUTTON_OVERLAY = "com.android.internal.systemui.navbar.twobutton";
    String NAV_BAR_MODE_GESTURAL_OVERLAY = "com.android.internal.systemui.navbar.gestural";

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

    /**
     * Extra for the start reason of the HOME intent.
     * Will be {@link PowerManager#WAKE_REASON_WAKE_KEY} or
     * {@link PowerManager#WAKE_REASON_POWER_BUTTON} when intent was sent through
     * {@link PhoneWindowManager#shouldWakeUpWithHomeIntent}.
     * @hide
     */
    String EXTRA_START_REASON = "android.intent.extra.EXTRA_START_REASON";

    // TODO: move this to a more appropriate place.
    interface PointerEventListener {
        /**
         * 1. onPointerEvent will be called on the service.UiThread.
         * 2. motionEvent will be recycled after onPointerEvent returns so if it is needed later a
         * copy() must be made and the copy must be recycled.
         **/
        void onPointerEvent(MotionEvent motionEvent);
    }

    @IntDef(prefix = { "OFF_BECAUSE_OF_" }, value = {
            OFF_BECAUSE_OF_ADMIN,
            OFF_BECAUSE_OF_USER,
            OFF_BECAUSE_OF_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OffReason{}

    static @OffReason int translateSleepReasonToOffReason(
            @PowerManager.GoToSleepReason int reason) {
        switch (reason) {
            case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                return OFF_BECAUSE_OF_ADMIN;
            case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
            case PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE:
                return OFF_BECAUSE_OF_TIMEOUT;
            default:
                return OFF_BECAUSE_OF_USER;
        }
    }

    /** Screen turned off because of a device admin */
    int OFF_BECAUSE_OF_ADMIN = 1;
    /** Screen turned off because of power button */
    int OFF_BECAUSE_OF_USER = 2;
    /** Screen turned off because of timeout */
    int OFF_BECAUSE_OF_TIMEOUT = 3;

    @IntDef(prefix = { "ON_BECAUSE_OF_" }, value = {
            ON_BECAUSE_OF_USER,
            ON_BECAUSE_OF_APPLICATION,
            ON_BECAUSE_OF_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OnReason{}

    /** Convert the on reason to a human readable format */
    static String onReasonToString(@OnReason int why) {
        switch (why) {
            case ON_BECAUSE_OF_USER:
                return "ON_BECAUSE_OF_USER";
            case ON_BECAUSE_OF_APPLICATION:
                return "ON_BECAUSE_OF_APPLICATION";
            case ON_BECAUSE_OF_UNKNOWN:
                return "ON_BECAUSE_OF_UNKNOWN";
            default:
                return Integer.toString(why);
        }
    }

    static @OnReason int translateWakeReasonToOnReason(@PowerManager.WakeReason int reason) {
        switch (reason) {
            case PowerManager.WAKE_REASON_POWER_BUTTON:
            case PowerManager.WAKE_REASON_PLUGGED_IN:
            case PowerManager.WAKE_REASON_GESTURE:
            case PowerManager.WAKE_REASON_TAP:
            case PowerManager.WAKE_REASON_LIFT:
            case PowerManager.WAKE_REASON_BIOMETRIC:
            case PowerManager.WAKE_REASON_CAMERA_LAUNCH:
            case PowerManager.WAKE_REASON_WAKE_KEY:
            case PowerManager.WAKE_REASON_WAKE_MOTION:
            case PowerManager.WAKE_REASON_LID:
                return ON_BECAUSE_OF_USER;
            case PowerManager.WAKE_REASON_APPLICATION:
                return ON_BECAUSE_OF_APPLICATION;
            default:
                return ON_BECAUSE_OF_UNKNOWN;
        }
    }

    /** Screen turned on because of a user-initiated action. */
    int ON_BECAUSE_OF_USER = 1;
    /** Screen turned on because of an application request or event */
    int ON_BECAUSE_OF_APPLICATION = 2;
    /** Screen turned on for an unknown reason */
    int ON_BECAUSE_OF_UNKNOWN = 3;

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

    /**
     * How much to multiply the policy's type layer, to reserve room
     * for multiple windows of the same type and Z-ordering adjustment
     * with TYPE_LAYER_OFFSET.
     */
    int TYPE_LAYER_MULTIPLIER = 10000;

    /**
     * Offset from TYPE_LAYER_MULTIPLIER for moving a group of windows above
     * or below others in the same layer.
     */
    int TYPE_LAYER_OFFSET = 1000;

    /**
     * How much to increment the layer for each window, to reserve room
     * for effect surfaces between them.
     */
    int WINDOW_LAYER_MULTIPLIER = 5;

    /**
     * Animation thumbnail is as far as possible below the window above
     * the thumbnail (or in other words as far as possible above the window
     * below it).
     */
    int LAYER_OFFSET_THUMBNAIL = WINDOW_LAYER_MULTIPLIER - 1;

    int WATERMARK_LAYER = TYPE_LAYER_MULTIPLIER * 100;
    int STRICT_MODE_LAYER = TYPE_LAYER_MULTIPLIER * 101;
    int WINDOW_FREEZE_LAYER = TYPE_LAYER_MULTIPLIER * 200;

    /**
     * Layers for screen rotation animation. We put these layers above
     * WINDOW_FREEZE_LAYER so that screen freeze will cover all windows.
     */
    int SCREEN_FREEZE_LAYER_BASE = WINDOW_FREEZE_LAYER + TYPE_LAYER_MULTIPLIER;
}
