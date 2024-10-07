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

import android.annotation.IntDef;
import android.app.KeyguardManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.hardware.fingerprint.FingerprintEnrollOptions.EnrollReason;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
     * @hide
     */
    @IntDef({FINGERPRINT_ERROR_HW_UNAVAILABLE,
            FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
            FINGERPRINT_ERROR_TIMEOUT,
            FINGERPRINT_ERROR_NO_SPACE,
            FINGERPRINT_ERROR_CANCELED,
            FINGERPRINT_ERROR_UNABLE_TO_REMOVE,
            FINGERPRINT_ERROR_LOCKOUT,
            FINGERPRINT_ERROR_VENDOR,
            FINGERPRINT_ERROR_LOCKOUT_PERMANENT,
            FINGERPRINT_ERROR_USER_CANCELED,
            FINGERPRINT_ERROR_NO_FINGERPRINTS,
            FINGERPRINT_ERROR_HW_NOT_PRESENT,
            FINGERPRINT_ERROR_NEGATIVE_BUTTON,
            BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
            BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BIOMETRIC_ERROR_RE_ENROLL,
            FINGERPRINT_ERROR_UNKNOWN,
            FINGERPRINT_ERROR_BAD_CALIBRATION,
            BIOMETRIC_ERROR_POWER_PRESSED})
    @Retention(RetentionPolicy.SOURCE)
    @interface FingerprintError {}

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
    int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15;

    /**
     * Authentication cannot proceed because re-enrollment is required.
     * @hide
     */
    int BIOMETRIC_ERROR_RE_ENROLL = 16;

    /**
     * Unknown error received from the HAL.
     * @hide
     */
    int FINGERPRINT_ERROR_UNKNOWN = 17;

    /**
     * Error indicating that the fingerprint sensor has bad calibration.
     * @hide
     */
    int FINGERPRINT_ERROR_BAD_CALIBRATION = 18;

    /**
     * A power press stopped this biometric operation.
     * @hide
     */
    int BIOMETRIC_ERROR_POWER_PRESSED = 19;

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int FINGERPRINT_ERROR_VENDOR_BASE = 1000;

    //
    // Image acquisition messages. Must agree with those in fingerprint.h
    //

    /**
     * @hide
     */
    @IntDef({FINGERPRINT_ACQUIRED_GOOD,
            FINGERPRINT_ACQUIRED_PARTIAL,
            FINGERPRINT_ACQUIRED_INSUFFICIENT,
            FINGERPRINT_ACQUIRED_IMAGER_DIRTY,
            FINGERPRINT_ACQUIRED_TOO_SLOW,
            FINGERPRINT_ACQUIRED_TOO_FAST,
            FINGERPRINT_ACQUIRED_VENDOR,
            FINGERPRINT_ACQUIRED_START,
            FINGERPRINT_ACQUIRED_UNKNOWN,
            FINGERPRINT_ACQUIRED_IMMOBILE,
            FINGERPRINT_ACQUIRED_TOO_BRIGHT,
            FINGERPRINT_ACQUIRED_POWER_PRESSED,
            FINGERPRINT_ACQUIRED_RE_ENROLL_OPTIONAL,
            FINGERPRINT_ACQUIRED_RE_ENROLL_FORCED})
    @Retention(RetentionPolicy.SOURCE)
    @interface FingerprintAcquired {}

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
     * Unknown acquired code received from the HAL.
     * @hide
     */
    int FINGERPRINT_ACQUIRED_UNKNOWN = 8;

    /**
     * This message may be sent during enrollment if the same area of the finger has already
     * been captured during this enrollment session. In general, enrolling multiple areas of the
     * same finger can help against false rejections.
     * @hide
     */
    int FINGERPRINT_ACQUIRED_IMMOBILE = 9;

    /**
     * For sensors that require illumination, such as optical under-display fingerprint sensors,
     * the image was too bright to be used for matching.
     * @hide
     */
    int FINGERPRINT_ACQUIRED_TOO_BRIGHT = 10;

    /**
     * For sensors that have the power button co-located with their sensor, this event will
     * be sent during enrollment.
     * @hide
     */
    int FINGERPRINT_ACQUIRED_POWER_PRESSED = 11;

    /**
     * This message is sent to encourage the user to re-enroll their fingerprints.
     * @hide
     */
    int FINGERPRINT_ACQUIRED_RE_ENROLL_OPTIONAL = 12;

    /**
     * This message is sent to force the user to re-enroll their fingerprints.
     * @hide
     */
    int FINGERPRINT_ACQUIRED_RE_ENROLL_FORCED = 13;

    /**
     * @hide
     */
    int FINGERPRINT_ACQUIRED_VENDOR_BASE = 1000;

    /**
     * Whether the FingerprintAcquired message is a signal to disable the UDFPS display mode.
     * We want to disable the UDFPS mode as soon as possible to conserve power and provide better
     * UX. For example, prolonged high-brightness illumination of optical sensors can be unpleasant
     * to the user, can cause long term display burn-in, and can drain the battery faster.
     */
    static boolean shouldDisableUdfpsDisplayMode(@FingerprintAcquired int acquiredInfo) {
        switch (acquiredInfo) {
            case FINGERPRINT_ACQUIRED_START:
                // Keep the UDFPS mode because the authentication just began.
                return false;
            case FINGERPRINT_ACQUIRED_GOOD:
            case FINGERPRINT_ACQUIRED_PARTIAL:
            case FINGERPRINT_ACQUIRED_INSUFFICIENT:
            case FINGERPRINT_ACQUIRED_IMAGER_DIRTY:
            case FINGERPRINT_ACQUIRED_TOO_SLOW:
            case FINGERPRINT_ACQUIRED_TOO_FAST:
            case FINGERPRINT_ACQUIRED_IMMOBILE:
            case FINGERPRINT_ACQUIRED_TOO_BRIGHT:
            case FINGERPRINT_ACQUIRED_VENDOR:
                // Disable the UDFPS mode because the image capture has finished. The overlay
                // can be hidden later, once the authentication result arrives.
                return true;
            case FINGERPRINT_ACQUIRED_UNKNOWN:
            default:
                // Keep the UDFPS mode in case of an unknown message.
                return false;
        }
    }


    /**
     * Converts FaceEnrollOptions.reason into BiometricsProtoEnums.enrollReason
     */
    static int reasonToMetric(@EnrollReason int reason) {
        switch(reason) {
            case FingerprintEnrollOptions.ENROLL_REASON_RE_ENROLL_NOTIFICATION:
                return BiometricsProtoEnums.ENROLLMENT_SOURCE_FRR_NOTIFICATION;
            case FingerprintEnrollOptions.ENROLL_REASON_SETTINGS:
                return BiometricsProtoEnums.ENROLLMENT_SOURCE_SETTINGS;
            case FingerprintEnrollOptions.ENROLL_REASON_SUW:
                return BiometricsProtoEnums.ENROLLMENT_SOURCE_SUW;
            default:
                return BiometricsProtoEnums.ENROLLMENT_SOURCE_UNKNOWN;
        }

    }
}
