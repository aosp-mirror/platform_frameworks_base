/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.policy.DevicePostureController.Callback;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Listener for device posture changes. This can be used to query the current posture, or register
 * for events when it changes.
 */
public interface DevicePostureController extends CallbackController<Callback> {
    @IntDef(prefix = {"DEVICE_POSTURE_"}, value = {
            DEVICE_POSTURE_UNKNOWN,
            DEVICE_POSTURE_CLOSED,
            DEVICE_POSTURE_HALF_OPENED,
            DEVICE_POSTURE_OPENED,
            DEVICE_POSTURE_FLIPPED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DevicePostureInt {}

    // NOTE: These constants **must** match those defined for Jetpack Sidecar. This is because we
    // use the Device State -> Jetpack Posture map in DevicePostureControllerImpl to translate
    // between the two.
    int DEVICE_POSTURE_UNKNOWN = 0;
    int DEVICE_POSTURE_CLOSED = 1;
    int DEVICE_POSTURE_HALF_OPENED = 2;
    int DEVICE_POSTURE_OPENED = 3;
    int DEVICE_POSTURE_FLIPPED = 4;
    int SUPPORTED_POSTURES_SIZE = DEVICE_POSTURE_FLIPPED + 1;

    /** Return the current device posture. */
    @DevicePostureInt int getDevicePosture();

    /**
     * String representation of DevicePostureInt.
     */
    static String devicePostureToString(@DevicePostureInt int posture) {
        switch (posture) {
            case DEVICE_POSTURE_CLOSED:
                return "DEVICE_POSTURE_CLOSED";
            case DEVICE_POSTURE_HALF_OPENED:
                return "DEVICE_POSTURE_HALF_OPENED";
            case DEVICE_POSTURE_OPENED:
                return "DEVICE_POSTURE_OPENED";
            case DEVICE_POSTURE_FLIPPED:
                return "DEVICE_POSTURE_FLIPPED";
            case DEVICE_POSTURE_UNKNOWN:
                return "DEVICE_POSTURE_UNKNOWN";
            default:
                return "UNSUPPORTED POSTURE posture=" + posture;
        }
    }

    /** Callback to be notified about device posture changes. */
    interface Callback {
        /**
         * Called when the posture changes. If there are multiple active displays ("concurrent"),
         * this will report the physical posture of the device (also known as the base device
         * state).
         */
        void onPostureChanged(@DevicePostureInt int posture);
    }
}
