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

import android.app.KeyguardManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.fingerprint.FingerprintManager;

/**
 * Interface containing all of the fingerprint-specific constants.
 *
 * NOTE: The error messages must be consistent between BiometricConstants, Biometric*Constants,
 *       and the frameworks/support/biometric/.../BiometricConstants files.
 *
 * @hide
 */
public interface BiometricFingerprintConstants {
    //
    // Error messages from fingerprint hardware during initilization, enrollment, authentication or
    // removal. Must agree with the list in fingerprint.h
    //

    /**
     * The hardware is unavailable. Try again later.
     */
    int FINGERPRINT_ERROR_HW_UNAVAILABLE = 1;

    /**
     * Error state returned when the sensor was unable to process the current image.
     */
    int FINGERPRINT_ERROR_UNABLE_TO_PROCESS = 2;

    /**
     * Error state returned when the current request has been running too long. This is intended to
     * prevent programs from waiting for the fingerprint sensor indefinitely. The timeout is
     * platform and sensor-specific, but is generally on the order of 30 seconds.
     */
    int FINGERPRINT_ERROR_TIMEOUT = 3;

    /**
     * Error state returned for operations like enrollment; the operation cannot be completed
     * because there's not enough storage remaining to complete the operation.
     */
    int FINGERPRINT_ERROR_NO_SPACE = 4;

    /**
     * The operation was canceled because the fingerprint sensor is unavailable. For example,
     * this may happen when the user is switched, the device is locked or another pending operation
     * prevents or disables it.
     */
    int FINGERPRINT_ERROR_CANCELED = 5;

    /**
     * The {@link FingerprintManager#remove} call failed. Typically this will happen when the
     * provided fingerprint id was incorrect.
     *
     * @hide
     */
    int FINGERPRINT_ERROR_UNABLE_TO_REMOVE = 6;

    /**
     * The operation was canceled because the API is locked out due to too many attempts.
     * This occurs after 5 failed attempts, and lasts for 30 seconds.
     */
    int FINGERPRINT_ERROR_LOCKOUT = 7;

    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     * These messages are typically reserved for internal operations such as enrollment, but may be
     * used to express vendor errors not covered by the ones in fingerprint.h. Applications are
     * expected to show the error message string if they happen, but are advised not to rely on the
     * message id since they will be device and vendor-specific
     */
    int FINGERPRINT_ERROR_VENDOR = 8;

    /**
     * The operation was canceled because FINGERPRINT_ERROR_LOCKOUT occurred too many times.
     * Fingerprint authentication is disabled until the user unlocks with strong authentication
     * (PIN/Pattern/Password)
     */
    int FINGERPRINT_ERROR_LOCKOUT_PERMANENT = 9;

    /**
     * The user canceled the operation. Upon receiving this, applications should use alternate
     * authentication (e.g. a password). The application should also provide the means to return
     * to fingerprint authentication, such as a "use fingerprint" button.
     */
    int FINGERPRINT_ERROR_USER_CANCELED = 10;

    /**
     * The user does not have any fingerprints enrolled.
     */
    int FINGERPRINT_ERROR_NO_FINGERPRINTS = 11;

    /**
     * The device does not have a fingerprint sensor.
     */
    int FINGERPRINT_ERROR_HW_NOT_PRESENT = 12;

    /**
     * The user pressed the negative button. This is a placeholder that is currently only used
     * by the support library.
     *
     * @hide
     */
    int FINGERPRINT_ERROR_NEGATIVE_BUTTON = 13;

    /**
     * The device does not have pin, pattern, or password set up. See
     * {@link BiometricPrompt.Builder#setDeviceCredentialAllowed(boolean)} and
     * {@link KeyguardManager#isDeviceSecure()}
     *
     * @hide
     */
    int BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14;

    /**
     * A security vulnerability has been discovered and the sensor is unavailable until a
     * security update has addressed this issue. This error can be received if for example,
     * authentication was requested with {@link Authenticators#BIOMETRIC_STRONG}, but the
     * sensor's strength can currently only meet {@link Authenticators#BIOMETRIC_WEAK}.
     * @hide
     */
    public static final int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    int FINGERPRINT_ERROR_VENDOR_BASE = 1000;

    //
    // Image acquisition messages. Must agree with those in fingerprint.h
    //

    /**
     * The image acquired was good.
     */
    int FINGERPRINT_ACQUIRED_GOOD = 0;

    /**
     * Only a partial fingerprint image was detected. During enrollment, the user should be
     * informed on what needs to happen to resolve this problem, e.g. "press firmly on sensor."
     */
    int FINGERPRINT_ACQUIRED_PARTIAL = 1;

    /**
     * The fingerprint image was too noisy to process due to a detected condition (i.e. dry skin) or
     * a possibly dirty sensor (See {@link #FINGERPRINT_ACQUIRED_IMAGER_DIRTY}).
     */
    int FINGERPRINT_ACQUIRED_INSUFFICIENT = 2;

    /**
     * The fingerprint image was too noisy due to suspected or detected dirt on the sensor.
     * For example, it's reasonable return this after multiple
     * {@link #FINGERPRINT_ACQUIRED_INSUFFICIENT} or actual detection of dirt on the sensor
     * (stuck pixels, swaths, etc.). The user is expected to take action to clean the sensor
     * when this is returned.
     */
    int FINGERPRINT_ACQUIRED_IMAGER_DIRTY = 3;

    /**
     * The fingerprint image was unreadable due to lack of motion. This is most appropriate for
     * linear array sensors that require a swipe motion.
     */
    int FINGERPRINT_ACQUIRED_TOO_SLOW = 4;

    /**
     * The fingerprint image was incomplete due to quick motion. While mostly appropriate for
     * linear array sensors,  this could also happen if the finger was moved during acquisition.
     * The user should be asked to move the finger slower (linear) or leave the finger on the sensor
     * longer.
     */
    int FINGERPRINT_ACQUIRED_TOO_FAST = 5;

    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     *
     * @hide
     */
    int FINGERPRINT_ACQUIRED_VENDOR = 6;

    /**
     * This message represents the earliest message sent at the beginning of the authentication
     * pipeline. It is expected to be used to measure latency. Note this should be sent whenever
     * authentication is restarted.
     * The framework will measure latency based on the time between the last START message and the
     * onAuthenticated callback.
     *
     * @hide
     */
    int FINGERPRINT_ACQUIRED_START = 7;

    /**
     * @hide
     */
    int FINGERPRINT_ACQUIRED_VENDOR_BASE = 1000;
}
