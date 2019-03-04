/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import android.annotation.UnsupportedAppUsage;
import android.app.KeyguardManager;


/**
 * Interface containing all of the biometric modality agnostic constants.
 *
 * NOTE: The error messages must be consistent between BiometricConstants, Biometric*Constants,
 *       and the frameworks/support/biometric/.../BiometricConstants files.
 *
 * @hide
 */
public interface BiometricConstants {
    //
    // Error messages from biometric hardware during initilization, enrollment, authentication or
    // removal.
    //

    /**
     * This was not added here since it would update BiometricPrompt API. But, is used in
     * BiometricManager.
     * @hide
     */
    int BIOMETRIC_SUCCESS = 0;

    /**
     * The hardware is unavailable. Try again later.
     */
    int BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;

    /**
     * Error state returned when the sensor was unable to process the current image.
     */
    int BIOMETRIC_ERROR_UNABLE_TO_PROCESS = 2;

    /**
     * Error state returned when the current request has been running too long. This is intended to
     * prevent programs from waiting for the biometric sensor indefinitely. The timeout is platform
     * and sensor-specific, but is generally on the order of 30 seconds.
     */
    int BIOMETRIC_ERROR_TIMEOUT = 3;

    /**
     * Error state returned for operations like enrollment; the operation cannot be completed
     * because there's not enough storage remaining to complete the operation.
     */
    int BIOMETRIC_ERROR_NO_SPACE = 4;

    /**
     * The operation was canceled because the biometric sensor is unavailable. For example, this may
     * happen when the user is switched, the device is locked or another pending operation prevents
     * or disables it.
     */
    int BIOMETRIC_ERROR_CANCELED = 5;

    /**
     * The {@link BiometricManager#remove} call failed. Typically this will happen when the provided
     * biometric id was incorrect.
     *
     * @hide
     */
    int BIOMETRIC_ERROR_UNABLE_TO_REMOVE = 6;

    /**
     * The operation was canceled because the API is locked out due to too many attempts.
     * This occurs after 5 failed attempts, and lasts for 30 seconds.
     */
    int BIOMETRIC_ERROR_LOCKOUT = 7;

    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     * These messages are typically reserved for internal operations such as enrollment, but may be
     * used to express vendor errors not otherwise covered. Applications are expected to show the
     * error message string if they happen, but are advised not to rely on the message id since they
     * will be device and vendor-specific
     */
    int BIOMETRIC_ERROR_VENDOR = 8;

    /**
     * The operation was canceled because BIOMETRIC_ERROR_LOCKOUT occurred too many times.
     * Biometric authentication is disabled until the user unlocks with strong authentication
     * (PIN/Pattern/Password)
     */
    int BIOMETRIC_ERROR_LOCKOUT_PERMANENT = 9;

    /**
     * The user canceled the operation. Upon receiving this, applications should use alternate
     * authentication (e.g. a password). The application should also provide the means to return to
     * biometric authentication, such as a "use <biometric>" button.
     */
    int BIOMETRIC_ERROR_USER_CANCELED = 10;

    /**
     * The user does not have any biometrics enrolled.
     */
    int BIOMETRIC_ERROR_NO_BIOMETRICS = 11;

    /**
     * The device does not have a biometric sensor.
     */
    int BIOMETRIC_ERROR_HW_NOT_PRESENT = 12;

    /**
     * The user pressed the negative button. This is a placeholder that is currently only used
     * by the support library.
     * @hide
     */
    int BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13;

    /**
     * The device does not have pin, pattern, or password set up. See
     * {@link BiometricPrompt.Builder#setDeviceCredentialAllowed(boolean)} and
     * {@link KeyguardManager#isDeviceSecure()}
     */
    int BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    int BIOMETRIC_ERROR_VENDOR_BASE = 1000;

    //
    // Image acquisition messages.
    //

    /**
     * The image acquired was good.
     */
    int BIOMETRIC_ACQUIRED_GOOD = 0;

    /**
     * Only a partial biometric image was detected. During enrollment, the user should be informed
     * on what needs to happen to resolve this problem, e.g. "press firmly on sensor." (for
     * fingerprint)
     */
    int BIOMETRIC_ACQUIRED_PARTIAL = 1;

    /**
     * The biometric image was too noisy to process due to a detected condition or a possibly dirty
     * sensor (See {@link #BIOMETRIC_ACQUIRED_IMAGER_DIRTY}).
     */
    int BIOMETRIC_ACQUIRED_INSUFFICIENT = 2;

    /**
     * The biometric image was too noisy due to suspected or detected dirt on the sensor.  For
     * example, it's reasonable return this after multiple {@link #BIOMETRIC_ACQUIRED_INSUFFICIENT}
     * or actual detection of dirt on the sensor (stuck pixels, swaths, etc.). The user is expected
     * to take action to clean the sensor when this is returned.
     */
    int BIOMETRIC_ACQUIRED_IMAGER_DIRTY = 3;

    /**
     * The biometric image was unreadable due to lack of motion.
     */
    int BIOMETRIC_ACQUIRED_TOO_SLOW = 4;

    /**
     * The biometric image was incomplete due to quick motion. For example, this could also happen
     * if the user moved during acquisition. The user should be asked to repeat the operation more
     * slowly.
     */
    int BIOMETRIC_ACQUIRED_TOO_FAST = 5;

    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     * @hide
     */
    int BIOMETRIC_ACQUIRED_VENDOR = 6;
    /**
     * @hide
     */
    int BIOMETRIC_ACQUIRED_VENDOR_BASE = 1000;
}
