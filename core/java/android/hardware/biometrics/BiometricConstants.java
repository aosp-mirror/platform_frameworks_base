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

import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.IntDef;
import android.compat.annotation.UnsupportedAppUsage;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
     * OEMs should use this constant if there are conditions that do not fit under any of the other
     * publicly defined constants, and must provide appropriate strings for these
     * errors to the {@link BiometricPrompt.AuthenticationCallback#onAuthenticationError(int,
     * CharSequence)} callback. OEMs should expect that the error message will be shown to users.
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
     * {@link BiometricPrompt.Builder#setAllowedAuthenticators(int)},
     * {@link Authenticators#DEVICE_CREDENTIAL}, and {@link BiometricManager#canAuthenticate(int)}.
     */
    int BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14;

    /**
     * A security vulnerability has been discovered and the sensor is unavailable until a
     * security update has addressed this issue. This error can be received if for example,
     * authentication was requested with {@link Authenticators#BIOMETRIC_STRONG}, but the
     * sensor's strength can currently only meet {@link Authenticators#BIOMETRIC_WEAK}.
     */
    int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15;

    /**
     * This constant is only used by SystemUI. It notifies SystemUI that authentication was paused
     * because the authentication attempt was unsuccessful.
     * @hide
     */
    int BIOMETRIC_PAUSED_REJECTED = 100;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    int BIOMETRIC_ERROR_VENDOR_BASE = 1000;

    @IntDef({BIOMETRIC_SUCCESS,
            BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
            BIOMETRIC_ERROR_TIMEOUT,
            BIOMETRIC_ERROR_NO_SPACE,
            BIOMETRIC_ERROR_CANCELED,
            BIOMETRIC_ERROR_UNABLE_TO_REMOVE,
            BIOMETRIC_ERROR_LOCKOUT,
            BIOMETRIC_ERROR_VENDOR,
            BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            BIOMETRIC_ERROR_USER_CANCELED,
            BIOMETRIC_ERROR_NO_BIOMETRICS,
            BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BIOMETRIC_ERROR_NEGATIVE_BUTTON,
            BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
            BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BIOMETRIC_PAUSED_REJECTED,
            BIOMETRIC_ERROR_VENDOR_BASE})
    @Retention(RetentionPolicy.SOURCE)
    @interface Errors {}

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

    //
    // Internal messages.
    //

    /**
     * See {@link BiometricPrompt.Builder#setReceiveSystemEvents(boolean)}. This message is sent
     * immediately when the user cancels authentication for example by tapping the back button or
     * tapping the scrim. This is before {@link #BIOMETRIC_ERROR_USER_CANCELED}, which is sent when
     * dismissal animation completes.
     * @hide
     */
    int BIOMETRIC_SYSTEM_EVENT_EARLY_USER_CANCEL = 1;

}
