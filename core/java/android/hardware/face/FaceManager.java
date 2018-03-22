/**
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

package android.hardware.face;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_FACE;
import static android.Manifest.permission.USE_BIOMETRIC;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.keystore.AndroidKeyStoreProvider;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;

import java.security.Signature;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A class that coordinates access to the face authentication hardware.
 * @hide
 */
@SystemService(Context.FACE_SERVICE)
public class FaceManager {
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
    //
    // Error messages from face authentication hardware during initialization, enrollment,
    // authentication or removal. Must agree with the list in HAL h file
    //
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
     * @hide
     */
    public static final int FACE_ERROR_VENDOR_BASE = 1000;
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

    //
    // Image acquisition messages. Must agree with those in HAL h file
    //
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
     * User's gaze strayed too far from the sensor causing significant parts of the user's face
     * to be hidden.
     * The user should be informed to turn the face front to the sensor.
     */
    public static final int FACE_ACQUIRED_POOR_GAZE = 10;
    /**
     * No face was detected in front of the sensor.
     * The user should be informed to point the sensor to a face when this is returned.
     */
    public static final int FACE_ACQUIRED_NOT_DETECTED = 11;
    /**
     * Hardware vendors may extend this list if there are conditions that do not fall under one of
     * the above categories. Vendors are responsible for providing error strings for these errors.
     *
     * @hide
     */
    public static final int FACE_ACQUIRED_VENDOR = 12;
    /**
     * @hide
     */
    public static final int FACE_ACQUIRED_VENDOR_BASE = 1000;
    private static final String TAG = "FaceManager";
    private static final boolean DEBUG = true;
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_ACQUIRED = 101;
    private static final int MSG_AUTHENTICATION_SUCCEEDED = 102;
    private static final int MSG_AUTHENTICATION_FAILED = 103;
    private static final int MSG_ERROR = 104;
    private static final int MSG_REMOVED = 105;
    private final Context mContext;
    private IFaceService mService;
    private IBinder mToken = new Binder();
    private AuthenticationCallback mAuthenticationCallback;
    private EnrollmentCallback mEnrollmentCallback;
    private RemovalCallback mRemovalCallback;
    private CryptoObject mCryptoObject;
    private Face mRemovalFace;
    private Handler mHandler;
    private IFaceServiceReceiver mServiceReceiver = new IFaceServiceReceiver.Stub() {

        @Override // binder call
        public void onEnrollResult(long deviceId, int faceId, int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, remaining, 0,
                    new Face(null, faceId, deviceId)).sendToTarget();
        }

        @Override // binder call
        public void onAcquired(long deviceId, int acquireInfo, int vendorCode) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, vendorCode, deviceId).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationSucceeded(long deviceId, Face face) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_SUCCEEDED, face).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationFailed(long deviceId) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_FAILED).sendToTarget();
        }

        @Override // binder call
        public void onError(long deviceId, int error, int vendorCode) {
            mHandler.obtainMessage(MSG_ERROR, error, vendorCode, deviceId).sendToTarget();
        }

        @Override // binder call
        public void onRemoved(long deviceId, int faceId, int remaining) {
            mHandler.obtainMessage(MSG_REMOVED, remaining, 0,
                    new Face(null, faceId, deviceId)).sendToTarget();
        }
    };

    /**
     * @hide
     */
    public FaceManager(Context context, IFaceService service) {
        mContext = context;
        mService = service;
        if (mService == null) {
            Slog.v(TAG, "FaceAuthenticationManagerService was null");
        }
        mHandler = new MyHandler(context);
    }

    /**
     * Request authentication of a crypto object. This call operates the face recognition hardware
     * and starts capturing images. It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} or
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is called, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param crypto   object associated with the call or null if none required.
     * @param cancel   an object that can be used to cancel authentication
     * @param flags    optional flags; should be 0
     * @param callback an object to receive authentication events
     * @param handler  an optional handler to handle callback events
     * @throws IllegalArgumentException if the crypto operation is not supported or is not backed
     *                                  by
     *                                  <a href="{@docRoot}training/articles/keystore.html">Android
     *                                  Keystore facility</a>.
     * @throws IllegalStateException    if the crypto primitive is not initialized.
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            int flags, @NonNull AuthenticationCallback callback, @Nullable Handler handler) {
        authenticate(crypto, cancel, flags, callback, handler, UserHandle.myUserId());
    }

    /**
     * Use the provided handler thread for events.
     */
    private void useHandler(Handler handler) {
        if (handler != null) {
            mHandler = new MyHandler(handler.getLooper());
        } else if (mHandler.getLooper() != mContext.getMainLooper()) {
            mHandler = new MyHandler(mContext.getMainLooper());
        }
    }

    /**
     * Per-user version
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            int flags, @NonNull AuthenticationCallback callback, Handler handler, int userId) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an authentication callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Log.w(TAG, "authentication already canceled");
                return;
            } else {
                cancel.setOnCancelListener(new OnAuthenticationCancelListener(crypto));
            }
        }

        if (mService != null) {
            try {
                useHandler(handler);
                mAuthenticationCallback = callback;
                mCryptoObject = crypto;
                long sessionId = crypto != null ? crypto.getOpId() : 0;
                mService.authenticate(mToken, sessionId, mServiceReceiver, flags,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception while authenticating: ", e);
                if (callback != null) {
                    // Though this may not be a hardware issue, it will cause apps to give up or try
                    // again later.
                    callback.onAuthenticationError(FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                }
            }
        }
    }

    /**
     * Request face authentication enrollment. This call operates the face authentication hardware
     * and starts capturing images. Progress will be indicated by callbacks to the
     * {@link EnrollmentCallback} object. It terminates when
     * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)} or
     * {@link EnrollmentCallback#onEnrollmentProgress(int) is called with remaining == 0, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param token    a unique token provided by a recent creation or verification of device
     *                 credentials (e.g. pin, pattern or password).
     * @param cancel   an object that can be used to cancel enrollment
     * @param flags    optional flags
     * @param userId   the user to whom this face will belong to
     * @param callback an object to receive enrollment events
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public void enroll(byte[] token, CancellationSignal cancel, int flags,
            int userId, EnrollmentCallback callback) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = getCurrentUserId();
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an enrollment callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Log.w(TAG, "enrollment already canceled");
                return;
            } else {
                cancel.setOnCancelListener(new OnEnrollCancelListener());
            }
        }

        if (mService != null) {
            try {
                mEnrollmentCallback = callback;
                mService.enroll(mToken, token, userId, mServiceReceiver, flags,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in enroll: ", e);
                if (callback != null) {
                    // Though this may not be a hardware issue, it will cause apps to give up or try
                    // again later.
                    callback.onEnrollmentError(FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                }
            }
        }
    }

    /**
     * Requests a pre-enrollment auth token to tie enrollment to the confirmation of
     * existing device credentials (e.g. pin/pattern/password).
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public long preEnroll() {
        long result = 0;
        if (mService != null) {
            try {
                result = mService.preEnroll(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return result;
    }

    /**
     * Finishes enrollment and cancels the current auth token.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public int postEnroll() {
        int result = 0;
        if (mService != null) {
            try {
                result = mService.postEnroll(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return result;
    }

    /**
     * Sets the active user. This is meant to be used to select the current profile for enrollment
     * to allow separate enrolled faces for a work profile
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public void setActiveUser(int userId) {
        if (mService != null) {
            try {
                mService.setActiveUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Remove given face template from face hardware and/or protected storage.
     *
     * @param face     the face item to remove
     * @param userId   the user who this face belongs to
     * @param callback an optional callback to verify that face templates have been
     *                 successfully removed. May be null if no callback is required.
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public void remove(Face face, int userId, RemovalCallback callback) {
        if (mService != null) {
            try {
                mRemovalCallback = callback;
                mRemovalFace = face;
                mService.remove(mToken, userId, mServiceReceiver);
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in remove: ", e);
                if (callback != null) {
                    callback.onRemovalError(face, FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                }
            }
        }
    }

    /**
     * Obtain the enrolled face template.
     *
     * @return the current face item
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC)
    public Face getEnrolledFace(int userId) {
        if (mService != null) {
            try {
                return mService.getEnrolledFace(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Obtain the enrolled face template.
     *
     * @return the current face item
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC)
    public Face getEnrolledFace() {
        return getEnrolledFace(UserHandle.myUserId());
    }

    /**
     * Determine if there is a face enrolled.
     *
     * @return true if a face is enrolled, false otherwise
     */
    @RequiresPermission(USE_BIOMETRIC)
    public boolean hasEnrolledFace() {
        if (mService != null) {
            try {
                return mService.hasEnrolledFace(
                        UserHandle.myUserId(), mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            USE_BIOMETRIC,
            INTERACT_ACROSS_USERS})
    public boolean hasEnrolledFace(int userId) {
        if (mService != null) {
            try {
                return mService.hasEnrolledFace(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine if face authentication sensor hardware is present and functional.
     *
     * @return true if hardware is present and functional, false otherwise.
     */
    @RequiresPermission(USE_BIOMETRIC)
    public boolean isHardwareDetected() {
        if (mService != null) {
            try {
                long deviceId = 0; /* TODO: plumb hardware id to FPMS */
                return mService.isHardwareDetected(deviceId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "isFaceHardwareDetected(): Service not connected!");
        }
        return false;
    }

    /**
     * Retrieves the authenticator token for binding keys to the lifecycle
     * of the calling user's face. Used only by internal clients.
     *
     * @hide
     */
    public long getAuthenticatorId() {
        if (mService != null) {
            try {
                return mService.getAuthenticatorId(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "getAuthenticatorId(): Service not connected!");
        }
        return 0;
    }

    /**
     * Reset the lockout timer when asked to do so by keyguard.
     *
     * @param token an opaque token returned by password confirmation.
     * @hide
     */
    public void resetTimeout(byte[] token) {
        if (mService != null) {
            try {
                mService.resetTimeout(token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "resetTimeout(): Service not connected!");
        }
    }

    /**
     * @hide
     */
    public void addLockoutResetCallback(final LockoutResetCallback callback) {
        if (mService != null) {
            try {
                final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
                mService.addLockoutResetCallback(
                        new IFaceServiceLockoutResetCallback.Stub() {

                            @Override
                            public void onLockoutReset(long deviceId,
                                    IRemoteCallback serverCallback)
                                    throws RemoteException {
                                try {
                                    final PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                                            PowerManager.PARTIAL_WAKE_LOCK,
                                            "faceLockoutResetCallback");
                                    wakeLock.acquire();
                                    mHandler.post(() -> {
                                        try {
                                            callback.onLockoutReset();
                                        } finally {
                                            wakeLock.release();
                                        }
                                    });
                                } finally {
                                    serverCallback.sendResult(null /* data */);
                                }
                            }
                        });
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "addLockoutResetCallback(): Service not connected!");
        }
    }

    private int getCurrentUserId() {
        try {
            return ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void cancelEnrollment() {
        if (mService != null) {
            try {
                mService.cancelEnrollment(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private void cancelAuthentication(CryptoObject cryptoObject) {
        if (mService != null) {
            try {
                mService.cancelAuthentication(mToken, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private String getErrorString(int errMsg, int vendorCode) {
        switch (errMsg) {
            case FACE_ERROR_HW_UNAVAILABLE:
                return mContext.getString(
                        com.android.internal.R.string.face_error_hw_not_available);
            case FACE_ERROR_UNABLE_TO_PROCESS:
                return mContext.getString(
                        com.android.internal.R.string.face_error_unable_to_process);
            case FACE_ERROR_TIMEOUT:
                return mContext.getString(com.android.internal.R.string.face_error_timeout);
            case FACE_ERROR_NO_SPACE:
                return mContext.getString(com.android.internal.R.string.face_error_no_space);
            case FACE_ERROR_CANCELED:
                return mContext.getString(com.android.internal.R.string.face_error_canceled);
            case FACE_ERROR_LOCKOUT:
                return mContext.getString(com.android.internal.R.string.face_error_lockout);
            case FACE_ERROR_LOCKOUT_PERMANENT:
                return mContext.getString(
                        com.android.internal.R.string.face_error_lockout_permanent);
            case FACE_ERROR_NOT_ENROLLED:
                return mContext.getString(com.android.internal.R.string.face_error_not_enrolled);
            case FACE_ERROR_HW_NOT_PRESENT:
                return mContext.getString(com.android.internal.R.string.face_error_hw_not_present);
            case FACE_ERROR_VENDOR: {
                String[] msgArray = mContext.getResources().getStringArray(
                        com.android.internal.R.array.face_error_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }
        Slog.w(TAG, "Invalid error message: " + errMsg + ", " + vendorCode);
        return null;
    }

    private String getAcquiredString(int acquireInfo, int vendorCode) {
        switch (acquireInfo) {
            case FACE_ACQUIRED_GOOD:
                return null;
            case FACE_ACQUIRED_INSUFFICIENT:
                return mContext.getString(R.string.face_acquired_insufficient);
            case FACE_ACQUIRED_TOO_BRIGHT:
                return mContext.getString(R.string.face_acquired_too_bright);
            case FACE_ACQUIRED_TOO_DARK:
                return mContext.getString(R.string.face_acquired_too_dark);
            case FACE_ACQUIRED_TOO_CLOSE:
                return mContext.getString(R.string.face_acquired_too_close);
            case FACE_ACQUIRED_TOO_FAR:
                return mContext.getString(R.string.face_acquired_too_far);
            case FACE_ACQUIRED_TOO_HIGH:
                return mContext.getString(R.string.face_acquired_too_high);
            case FACE_ACQUIRED_TOO_LOW:
                return mContext.getString(R.string.face_acquired_too_low);
            case FACE_ACQUIRED_TOO_RIGHT:
                return mContext.getString(R.string.face_acquired_too_right);
            case FACE_ACQUIRED_TOO_LEFT:
                return mContext.getString(R.string.face_acquired_too_left);
            case FACE_ACQUIRED_POOR_GAZE:
                return mContext.getString(R.string.face_acquired_poor_gaze);
            case FACE_ACQUIRED_NOT_DETECTED:
                return mContext.getString(R.string.face_acquired_not_detected);
            case FACE_ACQUIRED_VENDOR: {
                String[] msgArray = mContext.getResources().getStringArray(
                        R.array.face_acquired_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }
        Slog.w(TAG, "Invalid acquired message: " + acquireInfo + ", " + vendorCode);
        return null;
    }

    /**
     * A wrapper class for the crypto objects supported by FaceAuthenticationManager.
     */
    public static final class CryptoObject {

        private final Object mCrypto;

        public CryptoObject(@NonNull Signature signature) {
            mCrypto = signature;
        }

        public CryptoObject(@NonNull Cipher cipher) {
            mCrypto = cipher;
        }

        public CryptoObject(@NonNull Mac mac) {
            mCrypto = mac;
        }

        /**
         * Get {@link Signature} object.
         *
         * @return {@link Signature} object or null if this doesn't contain one.
         */
        public Signature getSignature() {
            return mCrypto instanceof Signature ? (Signature) mCrypto : null;
        }

        /**
         * Get {@link Cipher} object.
         *
         * @return {@link Cipher} object or null if this doesn't contain one.
         */
        public Cipher getCipher() {
            return mCrypto instanceof Cipher ? (Cipher) mCrypto : null;
        }

        /**
         * Get {@link Mac} object.
         *
         * @return {@link Mac} object or null if this doesn't contain one.
         */
        public Mac getMac() {
            return mCrypto instanceof Mac ? (Mac) mCrypto : null;
        }

        /**
         * @return the opId associated with this object or 0 if none
         * @hide
         */
        public long getOpId() {
            return mCrypto != null
                    ? AndroidKeyStoreProvider.getKeyStoreOperationHandle(mCrypto) : 0;
        }
    }

    /**
     * Container for callback data from {@link FaceManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}.
     */
    public static class AuthenticationResult {
        private Face mFace;
        private CryptoObject mCryptoObject;
        private int mUserId;

        /**
         * Authentication result
         *
         * @param crypto the crypto object
         * @param face   the recognized face data, if allowed.
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto, Face face, int userId) {
            mCryptoObject = crypto;
            mFace = face;
            mUserId = userId;
        }

        /**
         * Obtain the crypto object associated with this transaction
         *
         * @return crypto object provided to {@link FaceManager#authenticate
         * (CryptoObject,
         * CancellationSignal, int, AuthenticationCallback, Handler)}.
         */
        public CryptoObject getCryptoObject() {
            return mCryptoObject;
        }

        /**
         * Obtain the Face associated with this operation. Applications are strongly
         * discouraged from associating specific faces with specific applications or operations.
         *
         * @hide
         */
        public Face getFace() {
            return mFace;
        }

        /**
         * Obtain the userId for which this face was authenticated.
         *
         * @hide
         */
        public int getUserId() {
            return mUserId;
        }
    }

    /**
     * Callback structure provided to {@link FaceManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}. Users of {@link
     * FaceManager#authenticate(CryptoObject, CancellationSignal,
     * int, AuthenticationCallback, Handler) } must provide an implementation of this for listening
     * to face events.
     */
    public abstract static class AuthenticationCallback {

        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         *
         * @param errorCode An integer identifying the error message
         * @param errString A human-readable error string that can be shown in UI
         */
        public void onAuthenticationError(int errorCode, CharSequence errString) {
        }

        /**
         * Called when a recoverable error has been encountered during authentication. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it."
         *
         * @param helpCode   An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
        }

        /**
         * Called when a face is recognized.
         *
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(AuthenticationResult result) {
        }

        /**
         * Called when a face is detected but not recognized.
         */
        public void onAuthenticationFailed() {
        }

        /**
         * Called when a face image has been acquired, but wasn't processed yet.
         *
         * @param acquireInfo one of FACE_ACQUIRED_* constants
         * @hide
         */
        public void onAuthenticationAcquired(int acquireInfo) {
        }
    }

    /**
     * Callback structure provided to {@link FaceManager#enroll(long,
     * EnrollmentCallback, CancellationSignal, int). Users of {@link #FaceAuthenticationManager()}
     * must provide an implementation of this to {@link FaceManager#enroll(long,
     * CancellationSignal, int, EnrollmentCallback) for listening to face enrollment events.
     *
     * @hide
     */
    public abstract static class EnrollmentCallback {

        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         *
         * @param errMsgId  An integer identifying the error message
         * @param errString A human-readable error string that can be shown in UI
         */
        public void onEnrollmentError(int errMsgId, CharSequence errString) {
        }

        /**
         * Called when a recoverable error has been encountered during enrollment. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Image too dark, uncover light source" or what they need to do next, such as
         * "Rotate face up / down."
         *
         * @param helpMsgId  An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        public void onEnrollmentHelp(int helpMsgId, CharSequence helpString) {
        }

        /**
         * Called as each enrollment step progresses. Enrollment is considered complete when
         * remaining reaches 0. This function will not be called if enrollment fails. See
         * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)}
         *
         * @param remaining The number of remaining steps
         * @param vendorMsg Vendor feedback about the current enroll attempt. Use it to customize
         *                  the GUI according to vendor's requirements.
         */
        public void onEnrollmentProgress(int remaining, long vendorMsg) {
        }
    }

    /**
     * Callback structure provided to {@link #remove}. Users of {@link FaceManager}
     * may
     * optionally provide an implementation of this to
     * {@link #remove(Face, int, RemovalCallback)} for listening to face template
     * removal events.
     *
     * @hide
     */
    public abstract static class RemovalCallback {

        /**
         * Called when the given face can't be removed.
         *
         * @param face      The face that the call attempted to remove
         * @param errMsgId  An associated error message id
         * @param errString An error message indicating why the face id can't be removed
         */
        public void onRemovalError(Face face, int errMsgId, CharSequence errString) {
        }

        /**
         * Called when a given face is successfully removed.
         *
         * @param face The face template that was removed.
         */
        public void onRemovalSucceeded(Face face) {
        }
    }

    /**
     * @hide
     */
    public abstract static class LockoutResetCallback {

        /**
         * Called when lockout period expired and clients are allowed to listen for face
         * authentication
         * again.
         */
        public void onLockoutReset() {
        }
    }

    private class OnEnrollCancelListener implements OnCancelListener {
        @Override
        public void onCancel() {
            cancelEnrollment();
        }
    }

    private class OnAuthenticationCancelListener implements OnCancelListener {
        private CryptoObject mCrypto;

        OnAuthenticationCancelListener(CryptoObject crypto) {
            mCrypto = crypto;
        }

        @Override
        public void onCancel() {
            cancelAuthentication(mCrypto);
        }
    }

    private class MyHandler extends Handler {
        private MyHandler(Context context) {
            super(context.getMainLooper());
        }

        private MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_ENROLL_RESULT:
                    sendEnrollResult((EnrollResultMsg) msg.obj);
                    break;
                case MSG_ACQUIRED:
                    sendAcquiredResult((Long) msg.obj /* deviceId */, msg.arg1 /* acquire info */,
                            msg.arg2 /* vendorCode */);
                    break;
                case MSG_AUTHENTICATION_SUCCEEDED:
                    sendAuthenticatedSucceeded((Face) msg.obj, msg.arg1 /* userId */);
                    break;
                case MSG_AUTHENTICATION_FAILED:
                    sendAuthenticatedFailed();
                    break;
                case MSG_ERROR:
                    sendErrorResult((Long) msg.obj /* deviceId */, msg.arg1 /* errMsgId */,
                            msg.arg2 /* vendorCode */);
                    break;
                case MSG_REMOVED:
                    sendRemovedResult((Face) msg.obj);
                    break;
            }
        }

        private void sendRemovedResult(Face face) {
            if (mRemovalCallback == null) {
                return;
            }
            if (face == null) {
                Log.e(TAG, "Received MSG_REMOVED, but face is null");
                return;
            }


            mRemovalCallback.onRemovalSucceeded(face);
        }

        private void sendErrorResult(long deviceId, int errMsgId, int vendorCode) {
            // emulate HAL 2.1 behavior and send real errMsgId
            final int clientErrMsgId = errMsgId == FACE_ERROR_VENDOR
                    ? (vendorCode + FACE_ERROR_VENDOR_BASE) : errMsgId;
            if (mEnrollmentCallback != null) {
                mEnrollmentCallback.onEnrollmentError(clientErrMsgId,
                        getErrorString(errMsgId, vendorCode));
            } else if (mAuthenticationCallback != null) {
                mAuthenticationCallback.onAuthenticationError(clientErrMsgId,
                        getErrorString(errMsgId, vendorCode));
            } else if (mRemovalCallback != null) {
                mRemovalCallback.onRemovalError(mRemovalFace, clientErrMsgId,
                        getErrorString(errMsgId, vendorCode));
            }
        }

        private void sendEnrollResult(EnrollResultMsg faceWrapper) {
            if (mEnrollmentCallback != null) {
                int remaining = faceWrapper.getRemaining();
                long vendorMsg = faceWrapper.getVendorMsg();
                mEnrollmentCallback.onEnrollmentProgress(remaining, vendorMsg);
            }
        }

        private void sendAuthenticatedSucceeded(Face face, int userId) {
            if (mAuthenticationCallback != null) {
                final AuthenticationResult result =
                        new AuthenticationResult(mCryptoObject, face, userId);
                mAuthenticationCallback.onAuthenticationSucceeded(result);
            }
        }

        private void sendAuthenticatedFailed() {
            if (mAuthenticationCallback != null) {
                mAuthenticationCallback.onAuthenticationFailed();
            }
        }

        private void sendAcquiredResult(long deviceId, int acquireInfo, int vendorCode) {
            if (mAuthenticationCallback != null) {
                mAuthenticationCallback.onAuthenticationAcquired(acquireInfo);
            }
            final String msg = getAcquiredString(acquireInfo, vendorCode);
            if (msg == null) {
                return;
            }
            final int clientInfo = acquireInfo == FACE_ACQUIRED_VENDOR
                    ? (vendorCode + FACE_ACQUIRED_VENDOR_BASE) : acquireInfo;
            if (mEnrollmentCallback != null) {
                mEnrollmentCallback.onEnrollmentHelp(clientInfo, msg);
            } else if (mAuthenticationCallback != null) {
                mAuthenticationCallback.onAuthenticationHelp(clientInfo, msg);
            }
        }
    }

    private class EnrollResultMsg {
        private final Face mFace;
        private final int mRemaining;
        private final long mVendorMsg;

        EnrollResultMsg(Face face, int remaining, long vendorMsg) {
            mFace = face;
            mRemaining = remaining;
            mVendorMsg = vendorMsg;
        }

        Face getFace() {
            return mFace;
        }

        long getVendorMsg() {
            return mVendorMsg;
        }

        int getRemaining() {
            return mRemaining;
        }
    }
}
