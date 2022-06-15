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

package android.hardware.face;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.CryptoObject;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * A class that coordinates access to the face authentication hardware.
 * @hide
 */
@SystemService(Context.FACE_SERVICE)
public class FaceManager implements BiometricAuthenticator, BiometricFaceConstants {

    private static final String TAG = "FaceManager";
    private static final boolean DEBUG = true;
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_ACQUIRED = 101;
    private static final int MSG_AUTHENTICATION_SUCCEEDED = 102;
    private static final int MSG_AUTHENTICATION_FAILED = 103;
    private static final int MSG_ERROR = 104;
    private static final int MSG_REMOVED = 105;
    private static final int MSG_GET_FEATURE_COMPLETED = 106;
    private static final int MSG_SET_FEATURE_COMPLETED = 107;
    private static final int MSG_CHALLENGE_GENERATED = 108;
    private static final int MSG_FACE_DETECTED = 109;
    private static final int MSG_AUTHENTICATION_FRAME = 112;
    private static final int MSG_ENROLLMENT_FRAME = 113;

    private final IFaceService mService;
    private final Context mContext;
    private final IBinder mToken = new Binder();
    @Nullable private AuthenticationCallback mAuthenticationCallback;
    @Nullable private FaceDetectionCallback mFaceDetectionCallback;
    @Nullable private EnrollmentCallback mEnrollmentCallback;
    @Nullable private RemovalCallback mRemovalCallback;
    @Nullable private SetFeatureCallback mSetFeatureCallback;
    @Nullable private GetFeatureCallback mGetFeatureCallback;
    @Nullable private GenerateChallengeCallback mGenerateChallengeCallback;
    private CryptoObject mCryptoObject;
    private Face mRemovalFace;
    private Handler mHandler;

    private final IFaceServiceReceiver mServiceReceiver = new IFaceServiceReceiver.Stub() {

        @Override // binder call
        public void onEnrollResult(Face face, int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, remaining, 0, face).sendToTarget();
        }

        @Override // binder call
        public void onAcquired(int acquireInfo, int vendorCode) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, vendorCode).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationSucceeded(Face face, int userId, boolean isStrongBiometric) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_SUCCEEDED, userId,
                    isStrongBiometric ? 1 : 0, face).sendToTarget();
        }

        @Override // binder call
        public void onFaceDetected(int sensorId, int userId, boolean isStrongBiometric) {
            mHandler.obtainMessage(MSG_FACE_DETECTED, sensorId, userId, isStrongBiometric)
                    .sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationFailed() {
            mHandler.obtainMessage(MSG_AUTHENTICATION_FAILED).sendToTarget();
        }

        @Override // binder call
        public void onError(int error, int vendorCode) {
            mHandler.obtainMessage(MSG_ERROR, error, vendorCode).sendToTarget();
        }

        @Override // binder call
        public void onRemoved(Face face, int remaining) {
            mHandler.obtainMessage(MSG_REMOVED, remaining, 0, face).sendToTarget();
        }

        @Override
        public void onFeatureSet(boolean success, int feature) {
            mHandler.obtainMessage(MSG_SET_FEATURE_COMPLETED, feature, 0, success).sendToTarget();
        }

        @Override
        public void onFeatureGet(boolean success, int[] features, boolean[] featureState) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = success;
            args.arg2 = features;
            args.arg3 = featureState;
            mHandler.obtainMessage(MSG_GET_FEATURE_COMPLETED, args).sendToTarget();
        }

        @Override
        public void onChallengeGenerated(int sensorId, int userId, long challenge) {
            mHandler.obtainMessage(MSG_CHALLENGE_GENERATED, sensorId, userId, challenge)
                    .sendToTarget();
        }

        @Override
        public void onAuthenticationFrame(FaceAuthenticationFrame frame) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_FRAME, frame).sendToTarget();
        }

        @Override
        public void onEnrollmentFrame(FaceEnrollFrame frame) {
            mHandler.obtainMessage(MSG_ENROLLMENT_FRAME, frame).sendToTarget();
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
     * Request authentication of a crypto object. This call operates the face recognition hardware
     * and starts capturing images. It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} or
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is called, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param crypto   object associated with the call or null if none required.
     * @param cancel   an object that can be used to cancel authentication
     * @param callback an object to receive authentication events
     * @param handler  an optional handler to handle callback events
     * @param userId   userId to authenticate for
     * @throws IllegalArgumentException if the crypto operation is not supported or is not backed
     *                                  by
     *                                  <a href="{@docRoot}training/articles/keystore.html">Android
     *                                  Keystore facility</a>.
     * @throws IllegalStateException    if the crypto primitive is not initialized.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            @NonNull AuthenticationCallback callback, @Nullable Handler handler, int userId,
            boolean isKeyguardBypassEnabled) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an authentication callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Slog.w(TAG, "authentication already canceled");
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
                final long operationId = crypto != null ? crypto.getOpId() : 0;
                Trace.beginSection("FaceManager#authenticate");
                mService.authenticate(mToken, operationId, userId, mServiceReceiver,
                        mContext.getOpPackageName(), isKeyguardBypassEnabled);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception while authenticating: ", e);
                if (callback != null) {
                    // Though this may not be a hardware issue, it will cause apps to give up or
                    // try again later.
                    callback.onAuthenticationError(FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(mContext, FACE_ERROR_HW_UNAVAILABLE,
                                    0 /* vendorCode */));
                }
            } finally {
                Trace.endSection();
            }
        }
    }

    /**
     * Uses the face hardware to detect for the presence of a face, without giving details about
     * accept/reject/lockout.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void detectFace(@NonNull CancellationSignal cancel,
            @NonNull FaceDetectionCallback callback, int userId) {
        if (mService == null) {
            return;
        }

        if (cancel.isCanceled()) {
            Slog.w(TAG, "Detection already cancelled");
            return;
        } else {
            cancel.setOnCancelListener(new OnFaceDetectionCancelListener());
        }

        mFaceDetectionCallback = callback;

        try {
            mService.detectFace(mToken, userId, mServiceReceiver, mContext.getOpPackageName());
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception when requesting finger detect", e);
        }
    }

    /**
     * Defaults to {@link FaceManager#enroll(int, byte[], CancellationSignal, EnrollmentCallback,
     * int[], Surface)} with {@code previewSurface} set to null.
     *
     * @see FaceManager#enroll(int, byte[], CancellationSignal, EnrollmentCallback, int[], Surface)
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void enroll(int userId, byte[] hardwareAuthToken, CancellationSignal cancel,
            EnrollmentCallback callback, int[] disabledFeatures) {
        enroll(userId, hardwareAuthToken, cancel, callback, disabledFeatures,
                null /* previewSurface */, false /* debugConsent */);
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
     * @param hardwareAuthToken a unique token provided by a recent creation or
     *                          verification of device credentials (e.g. pin, pattern or password).
     * @param cancel            an object that can be used to cancel enrollment
     * @param userId            the user to whom this face will belong to
     * @param callback          an object to receive enrollment events
     * @param previewSurface    optional camera preview surface for a single-camera device.
     *                          Must be null if not used.
     * @param debugConsent      a feature flag that the user has consented to debug.
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void enroll(int userId, byte[] hardwareAuthToken, CancellationSignal cancel,
            EnrollmentCallback callback, int[] disabledFeatures, @Nullable Surface previewSurface,
            boolean debugConsent) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an enrollment callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Slog.w(TAG, "enrollment already canceled");
                return;
            } else {
                cancel.setOnCancelListener(new OnEnrollCancelListener());
            }
        }

        if (mService != null) {
            try {
                mEnrollmentCallback = callback;
                Trace.beginSection("FaceManager#enroll");
                mService.enroll(userId, mToken, hardwareAuthToken, mServiceReceiver,
                        mContext.getOpPackageName(), disabledFeatures, previewSurface,
                        debugConsent);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in enroll: ", e);
                // Though this may not be a hardware issue, it will cause apps to give up or
                // try again later.
                callback.onEnrollmentError(FACE_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FACE_ERROR_HW_UNAVAILABLE,
                                0 /* vendorCode */));
            } finally {
                Trace.endSection();
            }
        }
    }

    /**
     * Request face authentication enrollment for a remote client, for example Android Auto.
     * This call operates the face authentication hardware and starts capturing images.
     * Progress will be indicated by callbacks to the
     * {@link EnrollmentCallback} object. It terminates when
     * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)} or
     * {@link EnrollmentCallback#onEnrollmentProgress(int) is called with remaining == 0, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param hardwareAuthToken    a unique token provided by a recent creation or verification of
     *                 device credentials (e.g. pin, pattern or password).
     * @param cancel   an object that can be used to cancel enrollment
     * @param userId   the user to whom this face will belong to
     * @param callback an object to receive enrollment events
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void enrollRemotely(int userId, byte[] hardwareAuthToken, CancellationSignal cancel,
            EnrollmentCallback callback, int[] disabledFeatures) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an enrollment callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Slog.w(TAG, "enrollRemotely is already canceled.");
                return;
            } else {
                cancel.setOnCancelListener(new OnEnrollCancelListener());
            }
        }

        if (mService != null) {
            try {
                mEnrollmentCallback = callback;
                Trace.beginSection("FaceManager#enrollRemotely");
                mService.enrollRemotely(userId, mToken, hardwareAuthToken, mServiceReceiver,
                        mContext.getOpPackageName(), disabledFeatures);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in enrollRemotely: ", e);
                // Though this may not be a hardware issue, it will cause apps to give up or
                // try again later.
                callback.onEnrollmentError(FACE_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FACE_ERROR_HW_UNAVAILABLE,
                                0 /* vendorCode */));
            } finally {
                Trace.endSection();
            }
        }
    }

    /**
     * Generates a unique random challenge in the TEE. A typical use case is to have it wrapped in a
     * HardwareAuthenticationToken, minted by Gatekeeper upon PIN/Pattern/Password verification.
     * The HardwareAuthenticationToken can then be sent to the biometric HAL together with a
     * request to perform sensitive operation(s) (for example enroll or setFeature), represented
     * by the challenge. Doing this ensures that a the sensitive operation cannot be performed
     * unless the user has entered confirmed PIN/Pattern/Password.
     *
     * @see com.android.server.locksettings.LockSettingsService
     *
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void generateChallenge(int sensorId, int userId, GenerateChallengeCallback callback) {
        if (mService != null) {
            try {
                mGenerateChallengeCallback = callback;
                mService.generateChallenge(mToken, sensorId, userId, mServiceReceiver,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Same as {@link #generateChallenge(int, int, GenerateChallengeCallback)}, but assumes the
     * first enumerated sensor.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void generateChallenge(int userId, GenerateChallengeCallback callback) {
        final List<FaceSensorPropertiesInternal> faceSensorProperties =
                getSensorPropertiesInternal();
        if (faceSensorProperties.isEmpty()) {
            Slog.e(TAG, "No sensors");
            return;
        }

        final int sensorId = faceSensorProperties.get(0).sensorId;
        generateChallenge(sensorId, userId, callback);
    }

    /**
     * Invalidates the current challenge.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void revokeChallenge(int sensorId, int userId, long challenge) {
        if (mService != null) {
            try {
                mService.revokeChallenge(mToken, sensorId, userId,
                        mContext.getOpPackageName(), challenge);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
     *
     * @param sensorId Sensor ID that this operation takes effect for
     * @param userId User ID that this operation takes effect for.
     * @param hardwareAuthToken An opaque token returned by password confirmation.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void resetLockout(int sensorId, int userId, @Nullable byte[] hardwareAuthToken) {
        if (mService != null) {
            try {
                mService.resetLockout(mToken, sensorId, userId, hardwareAuthToken,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void setFeature(int userId, int feature, boolean enabled, byte[] hardwareAuthToken,
            SetFeatureCallback callback) {
        if (mService != null) {
            try {
                mSetFeatureCallback = callback;
                mService.setFeature(mToken, userId, feature, enabled, hardwareAuthToken,
                        mServiceReceiver, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void getFeature(int userId, int feature, GetFeatureCallback callback) {
        if (mService != null) {
            try {
                mGetFeatureCallback = callback;
                mService.getFeature(mToken, userId, feature, mServiceReceiver,
                        mContext.getOpPackageName());
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
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void remove(Face face, int userId, RemovalCallback callback) {
        if (mService != null) {
            try {
                mRemovalCallback = callback;
                mRemovalFace = face;
                mService.remove(mToken, face.getBiometricId(), userId, mServiceReceiver,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Removes all face templates for the given user.
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public void removeAll(int userId, @NonNull RemovalCallback callback) {
        if (mService != null) {
            try {
                mRemovalCallback = callback;
                mService.removeAll(mToken, userId, mServiceReceiver, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Obtain the enrolled face template.
     *
     * @return the current face item
     * @hide
     */
    @RequiresPermission(MANAGE_BIOMETRIC)
    public List<Face> getEnrolledFaces(int userId) {
        final List<FaceSensorPropertiesInternal> faceSensorProperties =
                getSensorPropertiesInternal();
        if (faceSensorProperties.isEmpty()) {
            Slog.e(TAG, "No sensors");
            return new ArrayList<>();
        }

        if (mService != null) {
            try {
                return mService.getEnrolledFaces(faceSensorProperties.get(0).sensorId, userId,
                        mContext.getOpPackageName());
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
    @RequiresPermission(MANAGE_BIOMETRIC)
    public List<Face> getEnrolledFaces() {
        return getEnrolledFaces(UserHandle.myUserId());
    }

    /**
     * Determine if there is a face enrolled.
     *
     * @return true if a face is enrolled, false otherwise
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public boolean hasEnrolledTemplates() {
        return hasEnrolledTemplates(UserHandle.myUserId());
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            USE_BIOMETRIC_INTERNAL,
            INTERACT_ACROSS_USERS})
    public boolean hasEnrolledTemplates(int userId) {
        final List<FaceSensorPropertiesInternal> faceSensorProperties =
                getSensorPropertiesInternal();
        if (faceSensorProperties.isEmpty()) {
            Slog.e(TAG, "No sensors");
            return false;
        }

        if (mService != null) {
            try {
                return mService.hasEnrolledFaces(faceSensorProperties.get(0).sensorId, userId,
                        mContext.getOpPackageName());
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
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public boolean isHardwareDetected() {
        final List<FaceSensorPropertiesInternal> faceSensorProperties =
                getSensorPropertiesInternal();
        if (faceSensorProperties.isEmpty()) {
            Slog.e(TAG, "No sensors");
            return false;
        }

        if (mService != null) {
            try {
                return mService.isHardwareDetected(faceSensorProperties.get(0).sensorId,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "isFaceHardwareDetected(): Service not connected!");
        }
        return false;
    }

    /**
     * Retrieves a list of properties for all face authentication sensors on the device.
     * @hide
     */
    @NonNull
    public List<FaceSensorProperties> getSensorProperties() {
        final List<FaceSensorProperties> properties = new ArrayList<>();
        final List<FaceSensorPropertiesInternal> internalProperties
                = getSensorPropertiesInternal();
        for (FaceSensorPropertiesInternal internalProp : internalProperties) {
            properties.add(FaceSensorProperties.from(internalProp));
        }
        return properties;
    }

    /**
     * Get statically configured sensor properties.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @NonNull
    public List<FaceSensorPropertiesInternal> getSensorPropertiesInternal() {
        try {
            if (mService == null) {
                return new ArrayList<>();
            }
            return mService.getSensorPropertiesInternal(mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return new ArrayList<>();
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void addLockoutResetCallback(final LockoutResetCallback callback) {
        if (mService != null) {
            try {
                final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
                mService.addLockoutResetCallback(
                        new IBiometricServiceLockoutResetCallback.Stub() {

                            @Override
                            public void onLockoutReset(int sensorId, IRemoteCallback serverCallback)
                                    throws RemoteException {
                                try {
                                    final PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                                            PowerManager.PARTIAL_WAKE_LOCK,
                                            "faceLockoutResetCallback");
                                    wakeLock.acquire();
                                    mHandler.post(() -> {
                                        try {
                                            callback.onLockoutReset(sensorId);
                                        } finally {
                                            wakeLock.release();
                                        }
                                    });
                                } finally {
                                    serverCallback.sendResult(null /* data */);
                                }
                            }
                        }, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "addLockoutResetCallback(): Service not connected!");
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

    private void cancelFaceDetect() {
        if (mService == null) {
            return;
        }

        try {
            mService.cancelFaceDetect(mToken, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public static String getErrorString(Context context, int errMsg, int vendorCode) {
        switch (errMsg) {
            case FACE_ERROR_HW_UNAVAILABLE:
                return context.getString(
                        com.android.internal.R.string.face_error_hw_not_available);
            case FACE_ERROR_UNABLE_TO_PROCESS:
                return context.getString(
                        com.android.internal.R.string.face_error_unable_to_process);
            case FACE_ERROR_TIMEOUT:
                return context.getString(com.android.internal.R.string.face_error_timeout);
            case FACE_ERROR_NO_SPACE:
                return context.getString(com.android.internal.R.string.face_error_no_space);
            case FACE_ERROR_CANCELED:
                return context.getString(com.android.internal.R.string.face_error_canceled);
            case FACE_ERROR_LOCKOUT:
                return context.getString(com.android.internal.R.string.face_error_lockout);
            case FACE_ERROR_LOCKOUT_PERMANENT:
                return context.getString(
                        com.android.internal.R.string.face_error_lockout_permanent);
            case FACE_ERROR_USER_CANCELED:
                return context.getString(com.android.internal.R.string.face_error_user_canceled);
            case FACE_ERROR_NOT_ENROLLED:
                return context.getString(com.android.internal.R.string.face_error_not_enrolled);
            case FACE_ERROR_HW_NOT_PRESENT:
                return context.getString(com.android.internal.R.string.face_error_hw_not_present);
            case BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return context.getString(
                        com.android.internal.R.string.face_error_security_update_required);
            case BIOMETRIC_ERROR_RE_ENROLL:
                return context.getString(
                        com.android.internal.R.string.face_recalibrate_notification_content);
            case FACE_ERROR_VENDOR: {
                String[] msgArray = context.getResources().getStringArray(
                        com.android.internal.R.array.face_error_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }

        // This is used as a last resort in case a vendor string is missing
        // It should not happen for anything other than FACE_ERROR_VENDOR, but
        // warn and use the default if all else fails.
        // TODO(b/196639965): update string
        Slog.w(TAG, "Invalid error message: " + errMsg + ", " + vendorCode);
        return "";
    }

    /**
     * Used so BiometricPrompt can map the face ones onto existing public constants.
     * @hide
     */
    public static int getMappedAcquiredInfo(int acquireInfo, int vendorCode) {
        switch (acquireInfo) {
            case FACE_ACQUIRED_GOOD:
                return BiometricConstants.BIOMETRIC_ACQUIRED_GOOD;
            case FACE_ACQUIRED_INSUFFICIENT:
            case FACE_ACQUIRED_TOO_BRIGHT:
            case FACE_ACQUIRED_TOO_DARK:
                return BiometricConstants.BIOMETRIC_ACQUIRED_INSUFFICIENT;
            case FACE_ACQUIRED_TOO_CLOSE:
            case FACE_ACQUIRED_TOO_FAR:
            case FACE_ACQUIRED_TOO_HIGH:
            case FACE_ACQUIRED_TOO_LOW:
            case FACE_ACQUIRED_TOO_RIGHT:
            case FACE_ACQUIRED_TOO_LEFT:
                return BiometricConstants.BIOMETRIC_ACQUIRED_PARTIAL;
            case FACE_ACQUIRED_POOR_GAZE:
            case FACE_ACQUIRED_NOT_DETECTED:
            case FACE_ACQUIRED_TOO_MUCH_MOTION:
            case FACE_ACQUIRED_RECALIBRATE:
                return BiometricConstants.BIOMETRIC_ACQUIRED_INSUFFICIENT;
            case FACE_ACQUIRED_VENDOR:
                return BiometricConstants.BIOMETRIC_ACQUIRED_VENDOR_BASE + vendorCode;
            default:
                return BiometricConstants.BIOMETRIC_ACQUIRED_GOOD;
        }
    }

    /**
     * Container for callback data from {@link FaceManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}.
     * @hide
     */
    public static class AuthenticationResult {
        private final Face mFace;
        private final CryptoObject mCryptoObject;
        private final int mUserId;
        private final boolean mIsStrongBiometric;

        /**
         * Authentication result
         *
         * @param crypto the crypto object
         * @param face   the recognized face data, if allowed.
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto, Face face, int userId,
                boolean isStrongBiometric) {
            mCryptoObject = crypto;
            mFace = face;
            mUserId = userId;
            mIsStrongBiometric = isStrongBiometric;
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

        /**
         * Check whether the strength of the face modality associated with this operation is strong
         * (i.e. not weak or convenience).
         *
         * @hide
         */
        public boolean isStrongBiometric() {
            return mIsStrongBiometric;
        }
    }

    /**
     * Callback structure provided to {@link FaceManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}. Users of {@link
     * FaceManager#authenticate(CryptoObject, CancellationSignal,
     * int, AuthenticationCallback, Handler) } must provide an implementation of this for listening
     * to face events.
     * @hide
     */
    public abstract static class AuthenticationCallback
            extends BiometricAuthenticator.AuthenticationCallback {

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
     * @hide
     */
    public interface FaceDetectionCallback {
        void onFaceDetected(int sensorId, int userId, boolean isStrongBiometric);
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
         * Called each time a single frame is captured during enrollment.
         *
         * <p>For older, non-AIDL implementations, only {@code helpCode} and {@code helpMessage} are
         * supported. Sensible default values will be provided for all other arguments.
         *
         * @param helpCode    An integer identifying the capture status for this frame.
         * @param helpMessage A human-readable help string that can be shown in UI.
         * @param cell        The cell captured during this frame of enrollment, if any.
         * @param stage       An integer representing the current stage of enrollment.
         * @param pan         The horizontal pan of the detected face. Values in the range [-1, 1]
         *                    indicate a good capture.
         * @param tilt        The vertical tilt of the detected face. Values in the range [-1, 1]
         *                    indicate a good capture.
         * @param distance    The distance of the detected face from the device. Values in
         *                    the range [-1, 1] indicate a good capture.
         */
        public void onEnrollmentFrame(
                int helpCode,
                @Nullable CharSequence helpMessage,
                @Nullable FaceEnrollCell cell,
                @FaceEnrollStages.FaceEnrollStage int stage,
                float pan,
                float tilt,
                float distance) {
            onEnrollmentHelp(helpCode, helpMessage);
        }

        /**
         * Called as each enrollment step progresses. Enrollment is considered complete when
         * remaining reaches 0. This function will not be called if enrollment fails. See
         * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)}
         *
         * @param remaining The number of remaining steps
         */
        public void onEnrollmentProgress(int remaining) {
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
        public void onRemovalSucceeded(@Nullable Face face, int remaining) {
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
        public void onLockoutReset(int sensorId) {
        }
    }

    /**
     * @hide
     */
    public abstract static class SetFeatureCallback {
        public abstract void onCompleted(boolean success, int feature);
    }

    /**
     * @hide
     */
    public abstract static class GetFeatureCallback {
        public abstract void onCompleted(boolean success, int[] features, boolean[] featureState);
    }

    /**
     * Callback structure provided to {@link #generateChallenge(int, int,
     * GenerateChallengeCallback)}.
     *
     * @hide
     */
    public interface GenerateChallengeCallback {
        /**
         * Invoked when a challenge has been generated.
         */
        void onGenerateChallengeResult(int sensorId, int userId, long challenge);
    }

    private class OnEnrollCancelListener implements OnCancelListener {
        @Override
        public void onCancel() {
            cancelEnrollment();
        }
    }

    private class OnAuthenticationCancelListener implements OnCancelListener {
        private final CryptoObject mCrypto;

        OnAuthenticationCancelListener(CryptoObject crypto) {
            mCrypto = crypto;
        }

        @Override
        public void onCancel() {
            cancelAuthentication(mCrypto);
        }
    }

    private class OnFaceDetectionCancelListener implements OnCancelListener {
        @Override
        public void onCancel() {
            cancelFaceDetect();
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
            Trace.beginSection("FaceManager#handleMessage: " + Integer.toString(msg.what));
            switch (msg.what) {
                case MSG_ENROLL_RESULT:
                    sendEnrollResult((Face) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_ACQUIRED:
                    sendAcquiredResult(msg.arg1 /* acquire info */, msg.arg2 /* vendorCode */);
                    break;
                case MSG_AUTHENTICATION_SUCCEEDED:
                    sendAuthenticatedSucceeded((Face) msg.obj, msg.arg1 /* userId */,
                            msg.arg2 == 1 /* isStrongBiometric */);
                    break;
                case MSG_AUTHENTICATION_FAILED:
                    sendAuthenticatedFailed();
                    break;
                case MSG_ERROR:
                    sendErrorResult(msg.arg1 /* errMsgId */, msg.arg2 /* vendorCode */);
                    break;
                case MSG_REMOVED:
                    sendRemovedResult((Face) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_SET_FEATURE_COMPLETED:
                    sendSetFeatureCompleted((boolean) msg.obj /* success */,
                            msg.arg1 /* feature */);
                    break;
                case MSG_GET_FEATURE_COMPLETED:
                    SomeArgs args = (SomeArgs) msg.obj;
                    sendGetFeatureCompleted((boolean) args.arg1 /* success */,
                            (int[]) args.arg2 /* features */,
                            (boolean[]) args.arg3 /* featureState */);
                    args.recycle();
                    break;
                case MSG_CHALLENGE_GENERATED:
                    sendChallengeGenerated(msg.arg1 /* sensorId */, msg.arg2 /* userId */,
                            (long) msg.obj /* challenge */);
                    break;
                case MSG_FACE_DETECTED:
                    sendFaceDetected(msg.arg1 /* sensorId */, msg.arg2 /* userId */,
                            (boolean) msg.obj /* isStrongBiometric */);
                    break;
                case MSG_AUTHENTICATION_FRAME:
                    sendAuthenticationFrame((FaceAuthenticationFrame) msg.obj /* frame */);
                    break;
                case MSG_ENROLLMENT_FRAME:
                    sendEnrollmentFrame((FaceEnrollFrame) msg.obj /* frame */);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
            Trace.endSection();
        }
    }

    private void sendSetFeatureCompleted(boolean success, int feature) {
        if (mSetFeatureCallback == null) {
            return;
        }
        mSetFeatureCallback.onCompleted(success, feature);
    }

    private void sendGetFeatureCompleted(boolean success, int[] features, boolean[] featureState) {
        if (mGetFeatureCallback == null) {
            return;
        }
        mGetFeatureCallback.onCompleted(success, features, featureState);
    }

    private void sendChallengeGenerated(int sensorId, int userId, long challenge) {
        if (mGenerateChallengeCallback == null) {
            return;
        }
        mGenerateChallengeCallback.onGenerateChallengeResult(sensorId, userId, challenge);
    }

    private void sendFaceDetected(int sensorId, int userId, boolean isStrongBiometric) {
        if (mFaceDetectionCallback == null) {
            Slog.e(TAG, "sendFaceDetected, callback null");
            return;
        }
        mFaceDetectionCallback.onFaceDetected(sensorId, userId, isStrongBiometric);
    }

    private void sendRemovedResult(Face face, int remaining) {
        if (mRemovalCallback == null) {
            return;
        }
        mRemovalCallback.onRemovalSucceeded(face, remaining);
    }

    private void sendErrorResult(int errMsgId, int vendorCode) {
        // emulate HAL 2.1 behavior and send real errMsgId
        final int clientErrMsgId = errMsgId == FACE_ERROR_VENDOR
                ? (vendorCode + FACE_ERROR_VENDOR_BASE) : errMsgId;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentError(clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        } else if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationError(clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        } else if (mRemovalCallback != null) {
            mRemovalCallback.onRemovalError(mRemovalFace, clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        }
    }

    private void sendEnrollResult(Face face, int remaining) {
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentProgress(remaining);
        }
    }

    private void sendAuthenticatedSucceeded(Face face, int userId, boolean isStrongBiometric) {
        if (mAuthenticationCallback != null) {
            final AuthenticationResult result =
                    new AuthenticationResult(mCryptoObject, face, userId, isStrongBiometric);
            mAuthenticationCallback.onAuthenticationSucceeded(result);
        }
    }

    private void sendAuthenticatedFailed() {
        if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationFailed();
        }
    }

    private void sendAcquiredResult(int acquireInfo, int vendorCode) {
        if (mAuthenticationCallback != null) {
            final FaceAuthenticationFrame frame = new FaceAuthenticationFrame(
                    new FaceDataFrame(acquireInfo, vendorCode));
            sendAuthenticationFrame(frame);
        } else if (mEnrollmentCallback != null) {
            final FaceEnrollFrame frame = new FaceEnrollFrame(
                    null /* cell */,
                    FaceEnrollStages.UNKNOWN,
                    new FaceDataFrame(acquireInfo, vendorCode));
            sendEnrollmentFrame(frame);
        }
    }

    private void sendAuthenticationFrame(@Nullable FaceAuthenticationFrame frame) {
        if (frame == null) {
            Slog.w(TAG, "Received null authentication frame");
        } else if (mAuthenticationCallback != null) {
            // TODO(b/178414967): Send additional frame data to callback
            final int acquireInfo = frame.getData().getAcquiredInfo();
            final int vendorCode = frame.getData().getVendorCode();
            final int helpCode = getHelpCode(acquireInfo, vendorCode);
            final String helpMessage = getAuthHelpMessage(mContext, acquireInfo, vendorCode);
            mAuthenticationCallback.onAuthenticationAcquired(acquireInfo);

            // Ensure that only non-null help messages are sent.
            if (helpMessage != null) {
                mAuthenticationCallback.onAuthenticationHelp(helpCode, helpMessage);
            }
        }
    }

    private void sendEnrollmentFrame(@Nullable FaceEnrollFrame frame) {
        if (frame == null) {
            Slog.w(TAG, "Received null enrollment frame");
        } else if (mEnrollmentCallback != null) {
            final FaceDataFrame data = frame.getData();
            final int acquireInfo = data.getAcquiredInfo();
            final int vendorCode = data.getVendorCode();
            final int helpCode = getHelpCode(acquireInfo, vendorCode);
            final String helpMessage = getEnrollHelpMessage(mContext, acquireInfo, vendorCode);
            mEnrollmentCallback.onEnrollmentFrame(
                    helpCode,
                    helpMessage,
                    frame.getCell(),
                    frame.getStage(),
                    data.getPan(),
                    data.getTilt(),
                    data.getDistance());
        }
    }

    private static int getHelpCode(int acquireInfo, int vendorCode) {
        return acquireInfo == FACE_ACQUIRED_VENDOR
                ? vendorCode + FACE_ACQUIRED_VENDOR_BASE
                : acquireInfo;
    }

    /**
     * @hide
     */
    @Nullable
    public static String getAuthHelpMessage(Context context, int acquireInfo, int vendorCode) {
        switch (acquireInfo) {
            // No help message is needed for a good capture.
            case FACE_ACQUIRED_GOOD:
            case FACE_ACQUIRED_START:
                return null;

            // Consolidate positional feedback to reduce noise during authentication.
            case FACE_ACQUIRED_NOT_DETECTED:
            case FACE_ACQUIRED_TOO_CLOSE:
            case FACE_ACQUIRED_TOO_FAR:
            case FACE_ACQUIRED_TOO_HIGH:
            case FACE_ACQUIRED_TOO_LOW:
            case FACE_ACQUIRED_TOO_RIGHT:
            case FACE_ACQUIRED_TOO_LEFT:
            case FACE_ACQUIRED_POOR_GAZE:
            case FACE_ACQUIRED_PAN_TOO_EXTREME:
            case FACE_ACQUIRED_TILT_TOO_EXTREME:
            case FACE_ACQUIRED_ROLL_TOO_EXTREME:
                return context.getString(R.string.face_acquired_poor_gaze);

            // Provide more detailed feedback for other soft errors.
            case FACE_ACQUIRED_INSUFFICIENT:
                return context.getString(R.string.face_acquired_insufficient);
            case FACE_ACQUIRED_TOO_BRIGHT:
                return context.getString(R.string.face_acquired_too_bright);
            case FACE_ACQUIRED_TOO_DARK:
                return context.getString(R.string.face_acquired_too_dark);
            case FACE_ACQUIRED_TOO_MUCH_MOTION:
                return context.getString(R.string.face_acquired_too_much_motion);
            case FACE_ACQUIRED_RECALIBRATE:
                return context.getString(R.string.face_acquired_recalibrate);
            case FACE_ACQUIRED_TOO_DIFFERENT:
                return context.getString(R.string.face_acquired_too_different);
            case FACE_ACQUIRED_TOO_SIMILAR:
                return context.getString(R.string.face_acquired_too_similar);
            case FACE_ACQUIRED_FACE_OBSCURED:
                return context.getString(R.string.face_acquired_obscured);
            case FACE_ACQUIRED_SENSOR_DIRTY:
                return context.getString(R.string.face_acquired_sensor_dirty);

            // Find and return the appropriate vendor-specific message.
            case FACE_ACQUIRED_VENDOR: {
                String[] msgArray = context.getResources().getStringArray(
                        R.array.face_acquired_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }

        Slog.w(TAG, "Unknown authentication acquired message: " + acquireInfo + ", " + vendorCode);
        return null;
    }

    /**
     * @hide
     */
    @Nullable
    public static String getEnrollHelpMessage(Context context, int acquireInfo, int vendorCode) {
        switch (acquireInfo) {
            case FACE_ACQUIRED_GOOD:
            case FACE_ACQUIRED_START:
                return null;
            case FACE_ACQUIRED_INSUFFICIENT:
                return context.getString(R.string.face_acquired_insufficient);
            case FACE_ACQUIRED_TOO_BRIGHT:
                return context.getString(R.string.face_acquired_too_bright);
            case FACE_ACQUIRED_TOO_DARK:
                return context.getString(R.string.face_acquired_too_dark);
            case FACE_ACQUIRED_TOO_CLOSE:
                return context.getString(R.string.face_acquired_too_close);
            case FACE_ACQUIRED_TOO_FAR:
                return context.getString(R.string.face_acquired_too_far);
            case FACE_ACQUIRED_TOO_HIGH:
                // TODO(b/181269243): Change back once error codes are fixed.
                return context.getString(R.string.face_acquired_too_low);
            case FACE_ACQUIRED_TOO_LOW:
                // TODO(b/181269243) Change back once error codes are fixed.
                return context.getString(R.string.face_acquired_too_high);
            case FACE_ACQUIRED_TOO_RIGHT:
                // TODO(b/181269243) Change back once error codes are fixed.
                return context.getString(R.string.face_acquired_too_left);
            case FACE_ACQUIRED_TOO_LEFT:
                // TODO(b/181269243) Change back once error codes are fixed.
                return context.getString(R.string.face_acquired_too_right);
            case FACE_ACQUIRED_POOR_GAZE:
                return context.getString(R.string.face_acquired_poor_gaze);
            case FACE_ACQUIRED_NOT_DETECTED:
                return context.getString(R.string.face_acquired_not_detected);
            case FACE_ACQUIRED_TOO_MUCH_MOTION:
                return context.getString(R.string.face_acquired_too_much_motion);
            case FACE_ACQUIRED_RECALIBRATE:
                return context.getString(R.string.face_acquired_recalibrate);
            case FACE_ACQUIRED_TOO_DIFFERENT:
                return context.getString(R.string.face_acquired_too_different);
            case FACE_ACQUIRED_TOO_SIMILAR:
                return context.getString(R.string.face_acquired_too_similar);
            case FACE_ACQUIRED_PAN_TOO_EXTREME:
                return context.getString(R.string.face_acquired_pan_too_extreme);
            case FACE_ACQUIRED_TILT_TOO_EXTREME:
                return context.getString(R.string.face_acquired_tilt_too_extreme);
            case FACE_ACQUIRED_ROLL_TOO_EXTREME:
                return context.getString(R.string.face_acquired_roll_too_extreme);
            case FACE_ACQUIRED_FACE_OBSCURED:
            case FACE_ACQUIRED_DARK_GLASSES_DETECTED:
            case FACE_ACQUIRED_MOUTH_COVERING_DETECTED:
                return context.getString(R.string.face_acquired_obscured);
            case FACE_ACQUIRED_SENSOR_DIRTY:
                return context.getString(R.string.face_acquired_sensor_dirty);
            case FACE_ACQUIRED_VENDOR: {
                String[] msgArray = context.getResources().getStringArray(
                        R.array.face_acquired_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }
        Slog.w(TAG, "Unknown enrollment acquired message: " + acquireInfo + ", " + vendorCode);
        return null;
    }
}
