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
import android.hardware.face.FaceManager;

/**
 * Interface containing all of the face-specific constants.
 *
 * NOTE: The error messages must be consistent between BiometricConstants, Biometric*Constants,
 *       and the frameworks/support/biometric/.../BiometricConstants files.
 *
 * @hide
 */
public interface BiometricFaceConstants {
    //
    // Accessibility constants
    //
    /**
     * Require the user to look at the device during enrollment and
     * authentication. Note this is to accommodate people who have limited
     * vision.
     */
    public static final int FEATURE_REQUIRE_ATTENTION = 1;
    /**
     * Require a diverse set of poses during enrollment. Note this is to
     * accommodate people with limited mobility.
     */
    public static final int FEATURE_REQUIRE_REQUIRE_DIVERSITY = 2;

    //
    // Error messages from face authentication hardware during initialization, enrollment,
    // authentication or removal. Must agree with the list in HAL h file
    //
    /**
     * The hardware is unavailable. Try again later.
     */
    public static final int FACE_ERROR_HW_UNAVAILABLE = 1;

    /**
     * Error state returned when the sensor was unable to process the current image.
     */
    public static final int FACE_ERROR_UNABLE_TO_PROCESS = 2;

    /**
     * Error state returned when the current request has been running too long. This is intended to
     * prevent programs from waiting for the face authentication sensor indefinitely. The timeout is
     * platform and sensor-specific, but is generally on the order of 30 seconds.
     */
    public static final int FACE_ERROR_TIMEOUT = 3;

    /**
     * Error state returned for operations like enrollment; the operation cannot be completed
     * because there's not enough storage remaining to complete the operation.
     */
    public static final int FACE_ERROR_NO_SPACE = 4;

    /**
     * The operation was canceled because the face authentication sensor is unavailable. For
     * example, this may happen when the user is switched, the device is locked or another pending
     * operation prevents or disables it.
     */
    public static final int FACE_ERROR_CANCELED = 5;

    /**
     * The {@link FaceManager#remove} call failed. Typically this will happen when the
     * provided face id was incorrect.
     *
     * @hide
     */
    public static final int FACE_ERROR_UNABLE_TO_REMOVE = 6;

    /**
     * The operation was canceled because the API is locked out due to too many attempts.
     * This occurs after 5 failed attempts, and lasts for 30 seconds.
     */
    public static final int FACE_ERROR_LOCKOUT = 7;

    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     * These messages are typically reserved for internal operations such as enrollment, but may be
     * used to express vendor errors not covered by the ones in HAL h file. Applications are
     * expected to show the error message string if they happen, but are advised not to rely on the
     * message id since they will be device and vendor-specific
     */
    public static final int FACE_ERROR_VENDOR = 8;

    /**
     * The operation was canceled because FACE_ERROR_LOCKOUT occurred too many times.
     * Face authentication is disabled until the user unlocks with strong authentication
     * (PIN/Pattern/Password)
     */
    public static final int FACE_ERROR_LOCKOUT_PERMANENT = 9;

    /**
     * The user canceled the operation. Upon receiving this, applications should use alternate
     * authentication (e.g. a password). The application should also provide the means to return
     * to face authentication, such as a "use face authentication" button.
     */
    public static final int FACE_ERROR_USER_CANCELED = 10;

    /**
     * The user does not have a face enrolled.
     */
    public static final int FACE_ERROR_NOT_ENROLLED = 11;

    /**
     * The device does not have a face sensor. This message will propagate if the calling app
     * ignores the result from PackageManager.hasFeature(FEATURE_FACE) and calls
     * this API anyway. Apps should always check for the feature before calling this API.
     */
    public static final int FACE_ERROR_HW_NOT_PRESENT = 12;

    /**
     * The user pressed the negative button. This is a placeholder that is currently only used
     * by the support library.
     * @hide
     */
    public static final int FACE_ERROR_NEGATIVE_BUTTON = 13;

    /**
     * The device does not have pin, pattern, or password set up. See
     * {@link BiometricPrompt.Builder#setDeviceCredentialAllowed(boolean)} and
     * {@link KeyguardManager#isDeviceSecure()}
     */
    public static final int BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14;

    /**
     * @hide
     */
    public static final int FACE_ERROR_VENDOR_BASE = 1000;

    //
    // Image acquisition messages. These will not be sent to the user, since they conflict with
    // existing constants. These must agree with face@1.0/types.hal.
    //

    /**
     * The image acquired was good.
     */
    public static final int FACE_ACQUIRED_GOOD = 0;

    /**
     * The face image was not good enough to process due to a detected condition.
     * (See {@link #FACE_ACQUIRED_TOO_BRIGHT or @link #FACE_ACQUIRED_TOO_DARK}).
     */
    public static final int FACE_ACQUIRED_INSUFFICIENT = 1;

    /**
     * The face image was too bright due to too much ambient light.
     * For example, it's reasonable to return this after multiple
     * {@link #FACE_ACQUIRED_INSUFFICIENT}
     * The user is expected to take action to retry in better lighting conditions
     * when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_BRIGHT = 2;

    /**
     * The face image was too dark due to illumination light obscured.
     * For example, it's reasonable to return this after multiple
     * {@link #FACE_ACQUIRED_INSUFFICIENT}
     * The user is expected to take action to retry in better lighting conditions
     * when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_DARK = 3;

    /**
     * The detected face is too close to the sensor, and the image can't be processed.
     * The user should be informed to move farther from the sensor when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_CLOSE = 4;

    /**
     * The detected face is too small, as the user might be too far from the sensor.
     * The user should be informed to move closer to the sensor when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_FAR = 5;

    /**
     * Only the upper part of the face was detected. The sensor field of view is too high.
     * The user should be informed to move up with respect to the sensor when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_HIGH = 6;

    /**
     * Only the lower part of the face was detected. The sensor field of view is too low.
     * The user should be informed to move down with respect to the sensor when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_LOW = 7;

    /**
     * Only the right part of the face was detected. The sensor field of view is too far right.
     * The user should be informed to move to the right with respect to the sensor
     * when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_RIGHT = 8;

    /**
     * Only the left part of the face was detected. The sensor field of view is too far left.
     * The user should be informed to move to the left with respect to the sensor
     * when this is returned.
     */
    public static final int FACE_ACQUIRED_TOO_LEFT = 9;

    /**
     * The user's eyes have strayed away from the sensor. If this message is sent, the user should
     * be informed to look at the device. If the user can't be found in the frame, one of the other
     * acquisition messages should be sent, e.g. FACE_ACQUIRED_NOT_DETECTED.
     */
    public static final int FACE_ACQUIRED_POOR_GAZE = 10;

    /**
     * No face was detected in front of the sensor.
     * The user should be informed to point the sensor to a face when this is returned.
     */
    public static final int FACE_ACQUIRED_NOT_DETECTED = 11;

    /**
     * Too much motion was detected.
     * The user should be informed to keep their face steady relative to the
     * sensor.
     */
    public static final int FACE_ACQUIRED_TOO_MUCH_MOTION = 12;

    /**
     * The sensor needs to be re-calibrated. This is an unexpected condition, and should only be
     * sent if a serious, uncorrectable, and unrecoverable calibration issue is detected which
     * requires user intervention, e.g. re-enrolling. The expected response to this message is to
     * direct the user to re-enroll.
     */
    public static final int FACE_ACQUIRED_RECALIBRATE = 13;

    /**
     * The face is too different from a previous acquisition. This condition
     * only applies to enrollment. This can happen if the user passes the
     * device to someone else in the middle of enrollment.
     */
    public static final int FACE_ACQUIRED_TOO_DIFFERENT = 14;

    /**
     * The face is too similar to a previous acquisition. This condition only
     * applies to enrollment. The user should change their pose.
     */
    public static final int FACE_ACQUIRED_TOO_SIMILAR = 15;

    /**
     * The magnitude of the pan angle of the user’s face with respect to the sensor’s
     * capture plane is too high.
     *
     * The pan angle is defined as the angle swept out by the user’s face turning
     * their neck left and right. The pan angle would be zero if the user faced the
     * camera directly.
     *
     * The user should be informed to look more directly at the camera.
     */
    public static final int FACE_ACQUIRED_PAN_TOO_EXTREME = 16;

    /**
     * The magnitude of the tilt angle of the user’s face with respect to the sensor’s
     * capture plane is too high.
     *
     * The tilt angle is defined as the angle swept out by the user’s face looking up
     * and down. The tilt angle would be zero if the user faced the camera directly.
     *
     * The user should be informed to look more directly at the camera.
     */
    public static final int FACE_ACQUIRED_TILT_TOO_EXTREME = 17;

    /**
     * The magnitude of the roll angle of the user’s face with respect to the sensor’s
     * capture plane is too high.
     *
     * The roll angle is defined as the angle swept out by the user’s face tilting their head
     * towards their shoulders to the left and right. The roll angle would be zero if the user's
     * head is vertically aligned with the camera.
     *
     * The user should be informed to look more directly at the camera.
     */
    public static final int FACE_ACQUIRED_ROLL_TOO_EXTREME = 18;

    /**
     * The user’s face has been obscured by some object.
     *
     * The user should be informed to remove any objects from the line of sight from
     * the sensor to the user’s face.
     */
    public static final int FACE_ACQUIRED_FACE_OBSCURED = 19;

    /**
     * This message represents the earliest message sent at the beginning of the authentication
     * pipeline. It is expected to be used to measure latency. For example, in a camera-based
     * authentication system it's expected to be sent prior to camera initialization. Note this
     * should be sent whenever authentication is restarted (see IBiometricsFace#userActivity).
     * The framework will measure latency based on the time between the last START message and the
     * onAuthenticated callback.
     */
    public static final int FACE_ACQUIRED_START = 20;

    /**
     * The sensor is dirty. The user should be informed to clean the sensor.
     */
    public static final int FACE_ACQUIRED_SENSOR_DIRTY = 21;

    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     *
     * @hide
     */
    public static final int FACE_ACQUIRED_VENDOR = 22;

    /**
     * @hide
     */
    public static final int FACE_ACQUIRED_VENDOR_BASE = 1000;
}
