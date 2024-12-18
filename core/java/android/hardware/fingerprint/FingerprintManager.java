/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.fingerprint;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;
import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_NONE;
import static android.hardware.biometrics.Flags.FLAG_ADD_KEY_AGREEMENT_CRYPTO_OBJECT;
import static android.hardware.fingerprint.FingerprintCallback.REMOVE_ALL;
import static android.hardware.fingerprint.FingerprintCallback.REMOVE_SINGLE;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_POWER_BUTTON;

import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_AUTHENTICATE;
import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_HAS_ENROLLED_FINGERPRINTS;
import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_IS_HARDWARE_DETECTED;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.BiometricTestSession;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.os.Binder;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.identity.IdentityCredential;
import android.security.identity.PresentationSession;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;

/**
 * A class that coordinates access to the fingerprint hardware.
 * @deprecated See {@link BiometricPrompt} which shows a system-provided dialog upon starting
 * authentication. In a world where devices may have different types of biometric authentication,
 * it's much more realistic to have a system-provided authentication dialog since the method may
 * vary by vendor/device.
 */
@SuppressWarnings("deprecation")
@Deprecated
@SystemService(Context.FINGERPRINT_SERVICE)
@RequiresFeature(PackageManager.FEATURE_FINGERPRINT)
public class FingerprintManager implements BiometricAuthenticator, BiometricFingerprintConstants {
    private static final String TAG = "FingerprintManager";

    /**
     * @hide
     */
    public static final int ENROLL_FIND_SENSOR = 1;
    /**
     * @hide
     */
    public static final int ENROLL_ENROLL = 2;

    /**
     * @hide
     */
    @IntDef({ENROLL_FIND_SENSOR, ENROLL_ENROLL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnrollReason {}

    /**
     * Udfps ui event of overlay is shown on the screen.
     * @hide
     */
    public static final int UDFPS_UI_OVERLAY_SHOWN = 1;
    /**
     * Udfps ui event of the udfps UI being ready (e.g. HBM illumination is enabled).
     * @hide
     */
    public static final int UDFPS_UI_READY = 2;

    /**
     * @hide
     */
    @IntDef({UDFPS_UI_OVERLAY_SHOWN, UDFPS_UI_READY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface UdfpsUiEvent{}

    /**
     * Request authentication with any single sensor.
     * @hide
     */
    public static final int SENSOR_ID_ANY = -1;

    private final IFingerprintService mService;
    private final Context mContext;
    private final IBinder mToken = new Binder();

    private Handler mHandler;
    @Nullable private float[] mEnrollStageThresholds;
    private List<FingerprintSensorPropertiesInternal> mProps = new ArrayList<>();
    private HandlerExecutor mExecutor;

    /**
     * Retrieves a list of properties for all fingerprint sensors on the device.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public List<SensorProperties> getSensorProperties() {
        final List<SensorProperties> properties = new ArrayList<>();
        final List<FingerprintSensorPropertiesInternal> internalProperties
                = getSensorPropertiesInternal();
        for (FingerprintSensorPropertiesInternal internalProp : internalProperties) {
            properties.add(FingerprintSensorProperties.from(internalProp));
        }
        return properties;
    }

    /**
     * Retrieves a test session for FingerprintManager.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public BiometricTestSession createTestSession(int sensorId) {
        try {
            return new BiometricTestSession(mContext, getSensorProperties(), sensorId,
                    (context, sensorId1, callback) -> mService
                            .createTestSession(sensorId1, callback, context.getOpPackageName()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private class OnEnrollCancelListener implements OnCancelListener {
        private final long mAuthRequestId;

        private OnEnrollCancelListener(long id) {
            mAuthRequestId = id;
        }

        @Override
        public void onCancel() {
            Slog.d(TAG, "Cancel fingerprint enrollment requested for: " + mAuthRequestId);
            cancelEnrollment(mAuthRequestId);
        }
    }

    private class OnAuthenticationCancelListener implements OnCancelListener {
        private final long mAuthRequestId;

        OnAuthenticationCancelListener(long id) {
            mAuthRequestId = id;
        }

        @Override
        public void onCancel() {
            Slog.d(TAG, "Cancel fingerprint authentication requested for: " + mAuthRequestId);
            cancelAuthentication(mAuthRequestId);
        }
    }

    private class OnFingerprintDetectionCancelListener implements OnCancelListener {
        private final long mAuthRequestId;

        OnFingerprintDetectionCancelListener(long id) {
            mAuthRequestId = id;
        }

        @Override
        public void onCancel() {
            Slog.d(TAG, "Cancel fingerprint detect requested for: " + mAuthRequestId);
            cancelFingerprintDetect(mAuthRequestId);
        }
    }

    /**
     * A wrapper class for the crypto objects supported by FingerprintManager. Currently the
     * framework supports {@link Signature}, {@link Cipher} and {@link Mac} objects.
     * @deprecated See {@link android.hardware.biometrics.BiometricPrompt.CryptoObject}
     */
    @Deprecated
    public static final class CryptoObject extends android.hardware.biometrics.CryptoObject {
        public CryptoObject(@NonNull Signature signature) {
            super(signature);
        }

        public CryptoObject(@NonNull Cipher cipher) {
            super(cipher);
        }

        public CryptoObject(@NonNull Mac mac) {
            super(mac);
        }

        /**
         * Get {@link Signature} object.
         * @return {@link Signature} object or null if this doesn't contain one.
         */
        public Signature getSignature() {
            return super.getSignature();
        }

        /**
         * Get {@link Cipher} object.
         * @return {@link Cipher} object or null if this doesn't contain one.
         */
        public Cipher getCipher() {
            return super.getCipher();
        }

        /**
         * Get {@link Mac} object.
         * @return {@link Mac} object or null if this doesn't contain one.
         */
        public Mac getMac() {
            return super.getMac();
        }

        /**
         * Get {@link IdentityCredential} object.
         * @return {@link IdentityCredential} object or null if this doesn't contain one.
         * @hide
         * @deprecated Use {@link PresentationSession} instead of {@link IdentityCredential}.
         */
        @Deprecated
        public IdentityCredential getIdentityCredential() {
            return super.getIdentityCredential();
        }

        /**
         * Get {@link PresentationSession} object.
         * @return {@link PresentationSession} object or null if this doesn't contain one.
         * @hide
         */
        public PresentationSession getPresentationSession() {
            return super.getPresentationSession();
        }

        /**
         * Get {@link KeyAgreement} object.
         * @return {@link KeyAgreement} object or null if this doesn't contain one.
         * @hide
         */
        @FlaggedApi(FLAG_ADD_KEY_AGREEMENT_CRYPTO_OBJECT)
        public KeyAgreement getKeyAgreement() {
            return super.getKeyAgreement();
        }
    }

    /**
     * Container for callback data from {@link FingerprintManager#authenticate(CryptoObject,
     *     CancellationSignal, int, AuthenticationCallback, Handler)}.
     * @deprecated See {@link android.hardware.biometrics.BiometricPrompt.AuthenticationResult}
     */
    @Deprecated
    public static class AuthenticationResult {
        private Fingerprint mFingerprint;
        private CryptoObject mCryptoObject;
        private int mUserId;
        private boolean mIsStrongBiometric;

        /**
         * Authentication result
         *
         * @param crypto the crypto object
         * @param fingerprint the recognized fingerprint data, if allowed.
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto, Fingerprint fingerprint, int userId,
                boolean isStrongBiometric) {
            mCryptoObject = crypto;
            mFingerprint = fingerprint;
            mUserId = userId;
            mIsStrongBiometric = isStrongBiometric;
        }

        /**
         * Obtain the crypto object associated with this transaction
         * @return crypto object provided to {@link FingerprintManager#authenticate(CryptoObject,
         *     CancellationSignal, int, AuthenticationCallback, Handler)}.
         */
        public CryptoObject getCryptoObject() { return mCryptoObject; }

        /**
         * Obtain the Fingerprint associated with this operation. Applications are strongly
         * discouraged from associating specific fingers with specific applications or operations.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public Fingerprint getFingerprint() { return mFingerprint; }

        /**
         * Obtain the userId for which this fingerprint was authenticated.
         * @hide
         */
        public int getUserId() { return mUserId; }

        /**
         * Check whether the strength of the fingerprint modality associated with this operation is
         * strong (i.e. not weak or convenience).
         * @hide
         */
        public boolean isStrongBiometric() {
            return mIsStrongBiometric;
        }
    }

    /**
     * Callback structure provided to {@link FingerprintManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}. Users of {@link
     * FingerprintManager#authenticate(CryptoObject, CancellationSignal,
     * int, AuthenticationCallback, Handler) } must provide an implementation of this for listening to
     * fingerprint events.
     * @deprecated See {@link android.hardware.biometrics.BiometricPrompt.AuthenticationCallback}
     */
    @Deprecated
    public abstract static class AuthenticationCallback
            extends BiometricAuthenticator.AuthenticationCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         * @param errorCode An integer identifying the error message
         * @param errString A human-readable error string that can be shown in UI
         */
        @Override
        public void onAuthenticationError(int errorCode, CharSequence errString) { }

        /**
         * Called when a recoverable error has been encountered during authentication. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it."
         * @param helpCode An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        @Override
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) { }

        /**
         * Called when a fingerprint is recognized.
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(AuthenticationResult result) { }

        /**
         * Called when a fingerprint is valid but not recognized.
         */
        @Override
        public void onAuthenticationFailed() { }

        /**
         * Called when a fingerprint image has been acquired, but wasn't processed yet.
         *
         * @param acquireInfo one of FINGERPRINT_ACQUIRED_* constants
         * @hide
         */
        @Override
        public void onAuthenticationAcquired(int acquireInfo) {}

        /**
         * Invoked for under-display fingerprint sensors when a touch has been detected on the
         * sensor area.
         * @hide
         */
        public void onUdfpsPointerDown(int sensorId) {}

        /**
         * Invoked for under-display fingerprint sensors when a touch has been removed from the
         * sensor area.
         * @hide
         */
        public void onUdfpsPointerUp(int sensorId) {}
    }

    /**
     * Callback structure provided for {@link #detectFingerprint(CancellationSignal,
     * FingerprintDetectionCallback, int, Surface)}.
     * @hide
     */
    public interface FingerprintDetectionCallback {
        /**
         * Invoked when a fingerprint has been detected.
         */
        void onFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric);

        /**
         * An error has occurred with fingerprint detection.
         *
         * This callback signifies that this operation has been completed, and
         * no more callbacks should be expected.
         */
        default void onDetectionError(int errorMsgId) {}
    }

    /**
     * Callback structure provided to {@link FingerprintManager#enroll(byte[], CancellationSignal,
     * int, EnrollmentCallback)} must provide an implementation of this for listening to
     * fingerprint events.
     *
     * @hide
     */
    public abstract static class EnrollmentCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         * @param errMsgId An integer identifying the error message
         * @param errString A human-readable error string that can be shown in UI
         */
        public void onEnrollmentError(int errMsgId, CharSequence errString) { }

        /**
         * Called when a recoverable error has been encountered during enrollment. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it" or what they need to do next, such as
         * "Touch sensor again."
         * @param helpMsgId An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        public void onEnrollmentHelp(int helpMsgId, CharSequence helpString) { }

        /**
         * Called as each enrollment step progresses. Enrollment is considered complete when
         * remaining reaches 0. This function will not be called if enrollment fails. See
         * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)}
         * @param remaining The number of remaining steps
         */
        public void onEnrollmentProgress(int remaining) { }

        /**
         * Called when a fingerprint image has been acquired.
         * @param isAcquiredGood whether the fingerprint image was good.
         */
        public void onAcquired(boolean isAcquiredGood){ }

        /**
         * Called when a pointer down event has occurred.
         */
        public void onUdfpsPointerDown(int sensorId){ }

        /**
         * Called when a pointer up event has occurred.
         */
        public void onUdfpsPointerUp(int sensorId){ }

        /**
         * Called when udfps overlay is shown.
         */
        public void onUdfpsOverlayShown() { }
    }

    /**
     * Callback structure provided to {@link #remove}. Users of {@link FingerprintManager} may
     * optionally provide an implementation of this to
     * {@link #remove(Fingerprint, int, RemovalCallback)} for listening to fingerprint template
     * removal events.
     *
     * @hide
     */
    public abstract static class RemovalCallback {
        /**
         * Called when the given fingerprint can't be removed.
         * @param fp The fingerprint that the call attempted to remove
         * @param errMsgId An associated error message id
         * @param errString An error message indicating why the fingerprint id can't be removed
         */
        public void onRemovalError(Fingerprint fp, int errMsgId, CharSequence errString) { }

        /**
         * Called when a given fingerprint is successfully removed.
         * @param fp The fingerprint template that was removed.
         * @param remaining The number of fingerprints yet to be removed in this operation. If
         *         {@link #remove} is called on one fingerprint, this should be 0. If
         *         {@link #remove} is called on a group, this should be the number of remaining
         *         fingerprints in the group, and 0 after the last fingerprint is removed.
         */
        public void onRemovalSucceeded(@Nullable Fingerprint fp, int remaining) { }
    }

    /**
     * @hide
     */
    public abstract static class LockoutResetCallback {

        /**
         * Called when lockout period expired and clients are allowed to listen for fingerprint
         * again.
         */
        public void onLockoutReset(int sensorId) { }
    }

    /**
     * Callbacks for generate challenge operations.
     *
     * @hide
     */
    public interface GenerateChallengeCallback {
        /** Called when a challenged has been generated. */
        void onChallengeGenerated(int sensorId, int userId, long challenge);
    }

    /**
     * Use the provided handler thread for events.
     * @param handler
     */
    private void useHandler(Handler handler) {
        if (handler != null) {
            mHandler = handler;
            mExecutor = new HandlerExecutor(mHandler);
        } else if (mHandler != mContext.getMainThreadHandler()) {
            mHandler = mContext.getMainThreadHandler();
            mExecutor = new HandlerExecutor(mHandler);
        }
    }

    /**
     * Request authentication of a crypto object. This call warms up the fingerprint hardware
     * and starts scanning for a fingerprint. It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} or
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is called, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param crypto object associated with the call or null if none required.
     * @param cancel an object that can be used to cancel authentication
     * @param flags optional flags; should be 0
     * @param callback an object to receive authentication events
     * @param handler an optional handler to handle callback events
     *
     * @throws IllegalArgumentException if the crypto operation is not supported or is not backed
     *         by <a href="{@docRoot}training/articles/keystore.html">Android Keystore
     *         facility</a>.
     * @throws IllegalStateException if the crypto primitive is not initialized.
     * @deprecated See {@link BiometricPrompt#authenticate(CancellationSignal, Executor,
     * BiometricPrompt.AuthenticationCallback)} and {@link BiometricPrompt#authenticate(
     * BiometricPrompt.CryptoObject, CancellationSignal, Executor,
     * BiometricPrompt.AuthenticationCallback)}
     */
    @Deprecated
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            int flags, @NonNull AuthenticationCallback callback, @Nullable Handler handler) {
        authenticate(crypto, cancel, callback, handler, SENSOR_ID_ANY, mContext.getUserId(), flags);
    }

    /**
     * Per-user version of authenticate.
     * @deprecated use {@link #authenticate(CryptoObject, CancellationSignal, AuthenticationCallback, Handler, FingerprintAuthenticateOptions)}.
     * @hide
     */
    @Deprecated
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            @NonNull AuthenticationCallback callback, Handler handler, int userId) {
        authenticate(crypto, cancel, callback, handler, SENSOR_ID_ANY, userId, 0 /* flags */);
    }

    /**
     * Per-user and per-sensor version of authenticate.
     * @deprecated use {@link #authenticate(CryptoObject, CancellationSignal, AuthenticationCallback, Handler, FingerprintAuthenticateOptions)}.
     * @hide
     */
    @Deprecated
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            @NonNull AuthenticationCallback callback, Handler handler, int sensorId, int userId,
            int flags) {
        authenticate(crypto, cancel, callback, handler, new FingerprintAuthenticateOptions.Builder()
                .setSensorId(sensorId)
                .setUserId(userId)
                .setIgnoreEnrollmentState(flags != 0)
                .build());
    }

    /**
     * Version of authenticate with additional options.
     * @hide
     */
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            @NonNull AuthenticationCallback callback, @NonNull Handler handler,
            @NonNull FingerprintAuthenticateOptions options) {
        FrameworkStatsLog.write(FrameworkStatsLog.AUTH_DEPRECATED_API_USED,
                AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_AUTHENTICATE,
                mContext.getApplicationInfo().uid,
                mContext.getApplicationInfo().targetSdkVersion);

        if (callback == null) {
            throw new IllegalArgumentException("Must supply an authentication callback");
        }

        if (cancel != null && cancel.isCanceled()) {
            Slog.w(TAG, "authentication already canceled");
            return;
        }

        options.setOpPackageName(mContext.getOpPackageName());
        options.setAttributionTag(mContext.getAttributionTag());

        if (mService != null) {
            try {
                final FingerprintCallback fingerprintCallback = new FingerprintCallback(callback,
                        crypto);
                useHandler(handler);
                final long operationId = crypto != null ? crypto.getOpId() : 0;
                final long authId = mService.authenticate(mToken, operationId,
                        new FingerprintServiceReceiver(fingerprintCallback), options);
                if (cancel != null) {
                    cancel.setOnCancelListener(new OnAuthenticationCancelListener(authId));
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception while authenticating: ", e);
                // Though this may not be a hardware issue, it will cause apps to give up or try
                // again later.
                callback.onAuthenticationError(FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                                0 /* vendorCode */));
            }
        }
    }

    /**
     * Uses the fingerprint hardware to detect for the presence of a finger, without giving details
     * about accept/reject/lockout.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void detectFingerprint(@NonNull CancellationSignal cancel,
            @NonNull FingerprintDetectionCallback callback, @NonNull FingerprintAuthenticateOptions options) {
        if (mService == null) {
            return;
        }

        if (cancel.isCanceled()) {
            Slog.w(TAG, "Detection already cancelled");
            return;
        }

        options.setOpPackageName(mContext.getOpPackageName());
        options.setAttributionTag(mContext.getAttributionTag());

        final FingerprintCallback fingerprintCallback = new FingerprintCallback(callback);

        try {
            final long authId = mService.detectFingerprint(mToken,
                    new FingerprintServiceReceiver(fingerprintCallback), options);
            cancel.setOnCancelListener(new OnFingerprintDetectionCancelListener(authId));
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception when requesting finger detect", e);
        }
    }

    /**
     * Set whether the HAL should ignore display touches.
     * Only applies to sensors where the HAL is reponsible for handling touches.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void setIgnoreDisplayTouches(long requestId, int sensorId, boolean ignoreTouch) {
        if (mService == null) {
            Slog.w(TAG, "setIgnoreDisplayTouches: no fingerprint service");
            return;
        }

        try {
            mService.setIgnoreDisplayTouches(requestId, sensorId, ignoreTouch);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request fingerprint enrollment. This call warms up the fingerprint hardware
     * and starts scanning for fingerprints. Progress will be indicated by callbacks to the
     * {@link EnrollmentCallback} object. It terminates when
     * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)} or
     * {@link EnrollmentCallback#onEnrollmentProgress(int) is called with remaining == 0, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     * @param token a unique token provided by a recent creation or verification of device
     * credentials (e.g. pin, pattern or password).
     * @param cancel an object that can be used to cancel enrollment
     * @param userId the user to whom this fingerprint will belong to
     * @param callback an object to receive enrollment events
     * @param shouldLogMetrics a flag that indicates if enrollment failure/success metrics
     * should be logged.
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void enroll(byte [] hardwareAuthToken, CancellationSignal cancel, int userId,
            EnrollmentCallback callback, @EnrollReason int enrollReason,
            FingerprintEnrollOptions options) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = getCurrentUserId();
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an enrollment callback");
        }

        if (cancel != null && cancel.isCanceled()) {
            Slog.w(TAG, "enrollment already canceled");
            return;
        }

        if (hardwareAuthToken == null) {
            callback.onEnrollmentError(FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
                    getErrorString(mContext, FINGERPRINT_ERROR_UNABLE_TO_PROCESS,
                            0 /* vendorCode */));
            return;
        }

        if (mService != null) {
            try {
                final FingerprintCallback fingerprintCallback = new FingerprintCallback(callback);
                final long enrollId = mService.enroll(mToken, hardwareAuthToken, userId,
                        new FingerprintServiceReceiver(fingerprintCallback),
                        mContext.getOpPackageName(), enrollReason, options);
                if (cancel != null) {
                    cancel.setOnCancelListener(new OnEnrollCancelListener(enrollId));
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in enroll: ", e);
                // Though this may not be a hardware issue, it will cause apps to give up or try
                // again later.
                callback.onEnrollmentError(FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                                0 /* vendorCode */));
            }
        }
    }

    /**
     * Generates a unique random challenge in the TEE. A typical use case is to have it wrapped in a
     * HardwareAuthenticationToken, minted by Gatekeeper upon PIN/Pattern/Password verification.
     * The HardwareAuthenticationToken can then be sent to the biometric HAL together with a
     * request to perform sensitive operation(s) (for example enroll), represented by the challenge.
     * Doing this ensures that a the sensitive operation cannot be performed unless the user has
     * entered confirmed PIN/Pattern/Password.
     *
     * @see com.android.server.locksettings.LockSettingsService
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void generateChallenge(int sensorId, int userId, GenerateChallengeCallback callback) {
        if (mService != null) try {
                final FingerprintCallback fingerprintCallback = new FingerprintCallback(callback);
                mService.generateChallenge(mToken, sensorId, userId,
                        new FingerprintServiceReceiver(fingerprintCallback),
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
    }

    /**
     * Same as {@link #generateChallenge(int, GenerateChallengeCallback)}, but assumes the first
     * enumerated sensor.
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void generateChallenge(int userId, GenerateChallengeCallback callback) {
        final FingerprintSensorPropertiesInternal sensorProps = getFirstFingerprintSensor();
        if (sensorProps == null) {
            Slog.e(TAG, "No sensors");
            return;
        }
        generateChallenge(sensorProps.sensorId, userId, callback);
    }

    /**
     * Revokes the specified challenge.
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void revokeChallenge(int userId, long challenge) {
        if (mService != null) {
            try {
                final FingerprintSensorPropertiesInternal sensorProps = getFirstFingerprintSensor();
                if (sensorProps == null) {
                    Slog.e(TAG, "No sensors");
                    return;
                }
                mService.revokeChallenge(mToken, sensorProps.sensorId, userId,
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
    @RequiresPermission(RESET_FINGERPRINT_LOCKOUT)
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
     * Remove given fingerprint template from fingerprint hardware and/or protected storage.
     * @param fp the fingerprint item to remove
     * @param userId the user who this fingerprint belongs to
     * @param callback an optional callback to verify that fingerprint templates have been
     * successfully removed. May be null of no callback is required.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void remove(Fingerprint fp, int userId, RemovalCallback callback) {
        if (mService != null) try {
                final FingerprintCallback fingerprintCallback = new FingerprintCallback(callback,
                        REMOVE_SINGLE, fp);
                mService.remove(mToken, fp.getBiometricId(), userId,
                        new FingerprintServiceReceiver(fingerprintCallback),
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
    }

    /**
     * Removes all fingerprint templates for the given user.
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void removeAll(int userId, @NonNull RemovalCallback callback) {
        if (mService != null) {
            try {
                final FingerprintCallback fingerprintCallback = new FingerprintCallback(callback,
                        REMOVE_ALL, null);
                mService.removeAll(mToken, userId,
                        new FingerprintServiceReceiver(fingerprintCallback),
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Renames the given fingerprint template
     * @param fpId the fingerprint id
     * @param userId the user who this fingerprint belongs to
     * @param newName the new name
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void rename(int fpId, int userId, String newName) {
        // Renames the given fpId
        if (mService != null) {
            try {
                mService.rename(fpId, userId, newName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "rename(): Service not connected!");
        }
    }

    /**
     * Obtain the list of enrolled fingerprints templates.
     * @return list of current fingerprint items
     *
     * @hide
     */
    @RequiresPermission(USE_FINGERPRINT)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public List<Fingerprint> getEnrolledFingerprints(int userId) {
        if (mService != null) try {
                return mService.getEnrolledFingerprints(
                        userId, mContext.getOpPackageName(), mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return null;
    }

    /**
     * Obtain the list of enrolled fingerprints templates.
     * @return list of current fingerprint items
     *
     * @hide
     */
    @RequiresPermission(USE_FINGERPRINT)
    @UnsupportedAppUsage
    public List<Fingerprint> getEnrolledFingerprints() {
        return getEnrolledFingerprints(mContext.getUserId());
    }

    /**
     * @hide
     */
    public boolean hasEnrolledTemplates() {
        return hasEnrolledFingerprints();
    }

    /**
     * @hide
     */
    public boolean hasEnrolledTemplates(int userId) {
        return hasEnrolledFingerprints(userId);
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
        if (mService == null) {
            Slog.w(TAG, "setUdfpsOverlayController: no fingerprint service");
            return;
        }

        try {
            mService.setUdfpsOverlayController(controller);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forwards BiometricStateListener to FingerprintService
     * @param listener new BiometricStateListener being added
     * @hide
     */
    public void registerBiometricStateListener(@NonNull BiometricStateListener listener) {
        try {
            mService.registerBiometricStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPointerDown(long requestId, int sensorId, int x, int y,
            float minor, float major) {
        if (mService == null) {
            Slog.w(TAG, "onPointerDown: no fingerprint service");
            return;
        }

        final PointerContext pc = new PointerContext();
        pc.x = (int) x;
        pc.y = (int) y;
        pc.minor = minor;
        pc.major = major;

        try {
            mService.onPointerDown(requestId, sensorId, pc);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPointerUp(long requestId, int sensorId) {
        if (mService == null) {
            Slog.w(TAG, "onPointerUp: no fingerprint service");
            return;
        }

        final PointerContext pc = new PointerContext();

        try {
            mService.onPointerUp(requestId, sensorId, pc);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * TODO(b/218388821): The parameter list should be replaced with PointerContext.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPointerDown(
            long requestId,
            int sensorId,
            int pointerId,
            float x,
            float y,
            float minor,
            float major,
            float orientation,
            long time,
            long gestureStart,
            boolean isAod) {
        if (mService == null) {
            Slog.w(TAG, "onPointerDown: no fingerprint service");
            return;
        }

        final PointerContext pc = new PointerContext();
        pc.pointerId = pointerId;
        pc.x = x;
        pc.y = y;
        pc.minor = minor;
        pc.major = major;
        pc.orientation = orientation;
        pc.time = time;
        pc.gestureStart = gestureStart;
        pc.isAod = isAod;

        try {
            mService.onPointerDown(requestId, sensorId, pc);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * TODO(b/218388821): The parameter list should be replaced with PointerContext.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPointerUp(
            long requestId,
            int sensorId,
            int pointerId,
            float x,
            float y,
            float minor,
            float major,
            float orientation,
            long time,
            long gestureStart,
            boolean isAod) {
        if (mService == null) {
            Slog.w(TAG, "onPointerUp: no fingerprint service");
            return;
        }

        final PointerContext pc = new PointerContext();
        pc.pointerId = pointerId;
        pc.x = x;
        pc.y = y;
        pc.minor = minor;
        pc.major = major;
        pc.orientation = orientation;
        pc.time = time;
        pc.gestureStart = gestureStart;
        pc.isAod = isAod;

        try {
            mService.onPointerUp(requestId, sensorId, pc);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onUdfpsUiEvent(@UdfpsUiEvent int event, long requestId, int sensorId) {
        if (mService == null) {
            Slog.w(TAG, "onUdfpsUiEvent: no fingerprint service");
            return;
        }

        try {
            mService.onUdfpsUiEvent(event, requestId, sensorId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This is triggered by SideFpsEventHandler
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPowerPressed() {
        Slog.i(TAG, "onPowerPressed");
        mExecutor.execute(() -> sendPowerPressed());
    }

    /**
     * Determine if there is at least one fingerprint enrolled.
     *
     * @return true if at least one fingerprint is enrolled, false otherwise
     * @deprecated See {@link BiometricPrompt} and
     * {@link FingerprintManager#FINGERPRINT_ERROR_NO_FINGERPRINTS}
     */
    @Deprecated
    @RequiresPermission(USE_FINGERPRINT)
    public boolean hasEnrolledFingerprints() {
        FrameworkStatsLog.write(FrameworkStatsLog.AUTH_DEPRECATED_API_USED,
                AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_HAS_ENROLLED_FINGERPRINTS,
                mContext.getApplicationInfo().uid,
                mContext.getApplicationInfo().targetSdkVersion);

        return hasEnrolledFingerprints(UserHandle.myUserId());
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            USE_FINGERPRINT,
            INTERACT_ACROSS_USERS})
    public boolean hasEnrolledFingerprints(int userId) {
        if (mService != null) try {
                return mService.hasEnrolledFingerprintsDeprecated(
                        userId, mContext.getOpPackageName(), mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Determine if fingerprint hardware is present and functional.
     *
     * @return true if hardware is present and functional, false otherwise.
     * @deprecated See {@link BiometricPrompt} and
     * {@link FingerprintManager#FINGERPRINT_ERROR_HW_UNAVAILABLE}
     */
    @Deprecated
    @RequiresPermission(USE_FINGERPRINT)
    public boolean isHardwareDetected() {
        FrameworkStatsLog.write(FrameworkStatsLog.AUTH_DEPRECATED_API_USED,
                AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_IS_HARDWARE_DETECTED,
                mContext.getApplicationInfo().uid,
                mContext.getApplicationInfo().targetSdkVersion);

        if (mService != null) {
            try {
                return mService.isHardwareDetectedDeprecated(
                        mContext.getOpPackageName(), mContext.getAttributionTag());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "isFingerprintHardwareDetected(): Service not connected!");
        }
        return false;
    }

    /**
     * Get statically configured sensor properties.
     * @deprecated Generally unsafe to use, use
     * {@link FingerprintManager#addAuthenticatorsRegisteredCallback} API instead.
     * In most cases this method will work as expected, but during early boot up, it will be
     * null/empty and there is no way for the caller to know when it's actual value is ready.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @NonNull
    public List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal() {
        try {
            if (!mProps.isEmpty() || mService == null) {
                return mProps;
            }
            return mService.getSensorPropertiesInternal(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the device has a power button fingerprint sensor.
     * @return boolean indicating whether power button is fingerprint sensor
     * @hide
     */
    public boolean isPowerbuttonFps() {
        final FingerprintSensorPropertiesInternal sensorProps = getFirstFingerprintSensor();
        return sensorProps == null ? false : sensorProps.sensorType == TYPE_POWER_BUTTON;
    }

    /**
     * Adds a callback that gets called when the service registers all of the fingerprint
     * authenticators (HALs).
     *
     * If the fingerprint authenticators are already registered when the callback is added, the
     * callback is invoked immediately.
     *
     * The callback is automatically removed after it's invoked.
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void addAuthenticatorsRegisteredCallback(
            IFingerprintAuthenticatorsRegisteredCallback callback) {
        if (mService != null) {
            try {
                mService.addAuthenticatorsRegisteredCallback(callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "addProvidersAvailableCallback(): Service not connected!");
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @BiometricConstants.LockoutMode
    public int getLockoutModeForUser(int sensorId, int userId) {
        if (mService != null) {
            try {
                return mService.getLockoutModeForUser(sensorId, userId);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
        return BIOMETRIC_LOCKOUT_NONE;
    }

    /**
     * Schedules a watchdog.
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void scheduleWatchdog() {
        try {
            mService.scheduleWatchdog();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
                        new IBiometricServiceLockoutResetCallback.Stub() {

                    @Override
                    public void onLockoutReset(int sensorId, IRemoteCallback serverCallback)
                            throws RemoteException {
                        try {
                            final PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                                    PowerManager.PARTIAL_WAKE_LOCK, "lockoutResetCallback");
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

    private void sendPowerPressed() {
        try {
            mService.onPowerPressed();
        } catch (RemoteException e) {
            Slog.e(TAG, "Error sending power press", e);
        }
    }

    /**
     * @hide
     */
    public FingerprintManager(Context context, IFingerprintService service) {
        mContext = context;
        mService = service;
        if (mService == null) {
            Slog.v(TAG, "FingerprintService was null");
        }
        if (context.checkCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL)
                == PackageManager.PERMISSION_GRANTED) {
            addAuthenticatorsRegisteredCallback(
                    new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                @NonNull List<FingerprintSensorPropertiesInternal> sensors) {
                            mProps = sensors;
                        }
                    });
        }
        mHandler = context.getMainThreadHandler();
        mExecutor = new HandlerExecutor(mHandler);
    }

    private int getCurrentUserId() {
        try {
            return ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    private FingerprintSensorPropertiesInternal getFirstFingerprintSensor() {
        final List<FingerprintSensorPropertiesInternal> allSensors = getSensorPropertiesInternal();
        return allSensors.isEmpty() ? null : allSensors.get(0);
    }

    private void cancelEnrollment(long requestId) {
        if (mService != null) try {
            mService.cancelEnrollment(mToken, requestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void cancelAuthentication(long requestId) {
        if (mService != null) try {
                mService.cancelAuthentication(
                        mToken,
                        mContext.getOpPackageName(),
                        mContext.getAttributionTag(),
                        requestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void cancelFingerprintDetect(long requestId) {
        if (mService == null) {
            return;
        }

        try {
            mService.cancelFingerprintDetect(mToken, mContext.getOpPackageName(), requestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public int getEnrollStageCount() {
        if (mEnrollStageThresholds == null) {
            mEnrollStageThresholds = createEnrollStageThresholds(mContext);
        }
        return mEnrollStageThresholds.length + 1;
    }

    /**
     * @hide
     */
    public float getEnrollStageThreshold(int index) {
        if (mEnrollStageThresholds == null) {
            mEnrollStageThresholds = createEnrollStageThresholds(mContext);
        }

        if (index < 0 || index > mEnrollStageThresholds.length) {
            Slog.w(TAG, "Unsupported enroll stage index: " + index);
            return index < 0 ? 0f : 1f;
        }

        // The implicit threshold for the final stage is always 1.
        return index == mEnrollStageThresholds.length ? 1f : mEnrollStageThresholds[index];
    }

    @NonNull
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    private float[] createEnrollStageThresholds(@NonNull Context context) {
        // TODO(b/200604947): Fetch this value from FingerprintService, rather than internal config
        final String[] enrollStageThresholdStrings;
        if (isPowerbuttonFps()) {
            enrollStageThresholdStrings = context.getResources().getStringArray(
                    com.android.internal.R.array.config_sfps_enroll_stage_thresholds);
        } else {
            enrollStageThresholdStrings = context.getResources().getStringArray(
                    com.android.internal.R.array.config_udfps_enroll_stage_thresholds);
        }

        final float[] enrollStageThresholds = new float[enrollStageThresholdStrings.length];
        for (int i = 0; i < enrollStageThresholds.length; i++) {
            enrollStageThresholds[i] = Float.parseFloat(enrollStageThresholdStrings[i]);
        }
        return enrollStageThresholds;
    }

    /**
     * @hide
     */
    public static String getErrorString(Context context, int errMsg, int vendorCode) {
        switch (errMsg) {
            case FINGERPRINT_ERROR_HW_UNAVAILABLE:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_hw_not_available);
            case FINGERPRINT_ERROR_UNABLE_TO_PROCESS:
                return context.getString(
                    com.android.internal.R.string.fingerprint_error_unable_to_process);
            case FINGERPRINT_ERROR_TIMEOUT:
                return context.getString(com.android.internal.R.string.fingerprint_error_timeout);
            case FINGERPRINT_ERROR_NO_SPACE:
                return context.getString(
                    com.android.internal.R.string.fingerprint_error_no_space);
            case FINGERPRINT_ERROR_CANCELED:
                return context.getString(com.android.internal.R.string.fingerprint_error_canceled);
            case FINGERPRINT_ERROR_LOCKOUT:
                return context.getString(com.android.internal.R.string.fingerprint_error_lockout);
            case FINGERPRINT_ERROR_LOCKOUT_PERMANENT:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_lockout_permanent);
            case FINGERPRINT_ERROR_USER_CANCELED:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_user_canceled);
            case FINGERPRINT_ERROR_NO_FINGERPRINTS:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_no_fingerprints);
            case FINGERPRINT_ERROR_HW_NOT_PRESENT:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_hw_not_present);
            case BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_security_update_required);
            case FINGERPRINT_ERROR_BAD_CALIBRATION:
                return context.getString(
                            com.android.internal.R.string.fingerprint_error_bad_calibration);
            case BIOMETRIC_ERROR_POWER_PRESSED:
                return context.getString(
                    com.android.internal.R.string.fingerprint_error_power_pressed);
            case FINGERPRINT_ERROR_VENDOR: {
                String[] msgArray = context.getResources().getStringArray(
                        com.android.internal.R.array.fingerprint_error_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }

        // This is used as a last resort in case a vendor string is missing
        // It should not happen for anything other than FINGERPRINT_ERROR_VENDOR, but
        // warn and use the default if all else fails.
        Slog.w(TAG, "Invalid error message: " + errMsg + ", " + vendorCode);
        return context.getString(
                com.android.internal.R.string.fingerprint_error_vendor_unknown);
    }

    /**
     * @hide
     */
    public static String getAcquiredString(Context context, int acquireInfo, int vendorCode) {
        switch (acquireInfo) {
            case FINGERPRINT_ACQUIRED_GOOD, FINGERPRINT_ACQUIRED_START:
                return null;
            case FINGERPRINT_ACQUIRED_PARTIAL:
                return context.getString(
                    com.android.internal.R.string.fingerprint_acquired_partial);
            case FINGERPRINT_ACQUIRED_INSUFFICIENT:
                return context.getString(
                    com.android.internal.R.string.fingerprint_acquired_insufficient);
            case FINGERPRINT_ACQUIRED_IMAGER_DIRTY:
                return context.getString(
                    com.android.internal.R.string.fingerprint_acquired_imager_dirty);
            case FINGERPRINT_ACQUIRED_TOO_SLOW:
                return context.getString(
                    com.android.internal.R.string.fingerprint_acquired_too_slow);
            case FINGERPRINT_ACQUIRED_TOO_FAST:
                return context.getString(
                    com.android.internal.R.string.fingerprint_acquired_too_fast);
            case FINGERPRINT_ACQUIRED_IMMOBILE:
                return context.getString(
                    com.android.internal.R.string.fingerprint_acquired_immobile);
            case FINGERPRINT_ACQUIRED_TOO_BRIGHT:
                return context.getString(
                   com.android.internal.R.string.fingerprint_acquired_too_bright);
            case FINGERPRINT_ACQUIRED_POWER_PRESSED:
                return context.getString(
                        com.android.internal.R.string.fingerprint_acquired_power_press);
            case FINGERPRINT_ACQUIRED_VENDOR: {
                String[] msgArray = context.getResources().getStringArray(
                        com.android.internal.R.array.fingerprint_acquired_vendor);
                if (vendorCode < msgArray.length && !msgArray[vendorCode].isEmpty()) {
                    return msgArray[vendorCode];
                }
            }
        }
        Slog.w(TAG, "Invalid acquired message: " + acquireInfo + ", " + vendorCode);
        return null;
    }

    class FingerprintServiceReceiver extends IFingerprintServiceReceiver.Stub {
        private final FingerprintCallback mFingerprintCallback;

        FingerprintServiceReceiver(FingerprintCallback fingerprintCallback) {
            mFingerprintCallback = fingerprintCallback;
        }

        @Override // binder call
        public void onEnrollResult(Fingerprint fp, int remaining) {
            mExecutor.execute(() -> mFingerprintCallback.sendEnrollResult(remaining));
        }

        @Override // binder call
        public void onAcquired(int acquireInfo, int vendorCode) {
            mExecutor.execute(() -> mFingerprintCallback.sendAcquiredResult(mContext, acquireInfo,
                    vendorCode));
        }

        @Override // binder call
        public void onAuthenticationSucceeded(Fingerprint fp, int userId,
                boolean isStrongBiometric) {
            mExecutor.execute(() -> mFingerprintCallback.sendAuthenticatedSucceeded(fp, userId,
                    isStrongBiometric));
        }

        @Override
        public void onFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric) {
            mExecutor.execute(() -> mFingerprintCallback.sendFingerprintDetected(sensorId, userId,
                    isStrongBiometric));
        }

        @Override // binder call
        public void onAuthenticationFailed() {
            mExecutor.execute(mFingerprintCallback::sendAuthenticatedFailed);
        }

        @Override // binder call
        public void onError(int error, int vendorCode) {
            mExecutor.execute(() -> mFingerprintCallback.sendErrorResult(mContext, error,
                    vendorCode));
        }

        @Override // binder call
        public void onRemoved(Fingerprint fp, int remaining) {
            mExecutor.execute(() -> mFingerprintCallback.sendRemovedResult(fp, remaining));
        }

        @Override // binder call
        public void onChallengeGenerated(int sensorId, int userId, long challenge) {
            mExecutor.execute(() -> mFingerprintCallback.sendChallengeGenerated(challenge, sensorId,
                    userId));
        }

        @Override // binder call
        public void onUdfpsPointerDown(int sensorId) {
            mExecutor.execute(() -> mFingerprintCallback.sendUdfpsPointerDown(sensorId));
        }

        @Override // binder call
        public void onUdfpsPointerUp(int sensorId) {
            mExecutor.execute(() -> mFingerprintCallback.sendUdfpsPointerUp(sensorId));
        }

        @Override
        public void onUdfpsOverlayShown() {
            mExecutor.execute(mFingerprintCallback::sendUdfpsOverlayShown);
        }
    }
}
