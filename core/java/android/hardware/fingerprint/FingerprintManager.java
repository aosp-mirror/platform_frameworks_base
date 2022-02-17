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
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_POWER_BUTTON;

import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_AUTHENTICATE;
import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_HAS_ENROLLED_FINGERPRINTS;
import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_FINGERPRINT_MANAGER_IS_HARDWARE_DETECTED;

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
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricTestSession;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.SensorProperties;
import android.os.Binder;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
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
    private static final boolean DEBUG = true;
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_ACQUIRED = 101;
    private static final int MSG_AUTHENTICATION_SUCCEEDED = 102;
    private static final int MSG_AUTHENTICATION_FAILED = 103;
    private static final int MSG_ERROR = 104;
    private static final int MSG_REMOVED = 105;
    private static final int MSG_CHALLENGE_GENERATED = 106;
    private static final int MSG_FINGERPRINT_DETECTED = 107;
    private static final int MSG_UDFPS_POINTER_DOWN = 108;
    private static final int MSG_UDFPS_POINTER_UP = 109;

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
     * Request authentication with any single sensor.
     * @hide
     */
    public static final int SENSOR_ID_ANY = -1;

    private static class RemoveTracker {
        static final int REMOVE_SINGLE = 1;
        static final int REMOVE_ALL = 2;
        @IntDef({REMOVE_SINGLE, REMOVE_ALL})
        @interface RemoveRequest {}

        final @RemoveRequest int mRemoveRequest;
        @Nullable final Fingerprint mSingleFingerprint;

        RemoveTracker(@RemoveRequest int request, @Nullable Fingerprint fingerprint) {
            mRemoveRequest = request;
            mSingleFingerprint = fingerprint;
        }
    }

    private IFingerprintService mService;
    private Context mContext;
    private IBinder mToken = new Binder();
    private AuthenticationCallback mAuthenticationCallback;
    private FingerprintDetectionCallback mFingerprintDetectionCallback;
    private EnrollmentCallback mEnrollmentCallback;
    private RemovalCallback mRemovalCallback;
    private GenerateChallengeCallback mGenerateChallengeCallback;
    private CryptoObject mCryptoObject;
    @Nullable private RemoveTracker mRemoveTracker;
    private Handler mHandler;
    @Nullable private float[] mEnrollStageThresholds;

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
            return new BiometricTestSession(mContext, sensorId,
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
    public static abstract class AuthenticationCallback
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
    }

    /**
     * Callback structure provided to {@link FingerprintManager#enroll(byte[], CancellationSignal,
     * int, EnrollmentCallback)} must provide an implementation of this for listening to
     * fingerprint events.
     *
     * @hide
     */
    public static abstract class EnrollmentCallback {
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
    }

    /**
     * Callback structure provided to {@link #remove}. Users of {@link FingerprintManager} may
     * optionally provide an implementation of this to
     * {@link #remove(Fingerprint, int, RemovalCallback)} for listening to fingerprint template
     * removal events.
     *
     * @hide
     */
    public static abstract class RemovalCallback {
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
    public static abstract class LockoutResetCallback {

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
            mHandler = new MyHandler(handler.getLooper());
        } else if (mHandler.getLooper() != mContext.getMainLooper()) {
            mHandler = new MyHandler(mContext.getMainLooper());
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
     * @hide
     */
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            @NonNull AuthenticationCallback callback, Handler handler, int userId) {
        authenticate(crypto, cancel, callback, handler, SENSOR_ID_ANY, userId, 0 /* flags */);
    }

    /**
     * Per-user and per-sensor version of authenticate.
     * @hide
     */
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            @NonNull AuthenticationCallback callback, Handler handler, int sensorId, int userId,
            int flags) {

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

        final boolean ignoreEnrollmentState = flags == 0 ? false : true;

        if (mService != null) {
            try {
                useHandler(handler);
                mAuthenticationCallback = callback;
                mCryptoObject = crypto;
                final long operationId = crypto != null ? crypto.getOpId() : 0;
                final long authId = mService.authenticate(mToken, operationId, sensorId, userId,
                        mServiceReceiver, mContext.getOpPackageName(), ignoreEnrollmentState);
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
            @NonNull FingerprintDetectionCallback callback, int userId) {
        if (mService == null) {
            return;
        }

        if (cancel.isCanceled()) {
            Slog.w(TAG, "Detection already cancelled");
            return;
        }

        mFingerprintDetectionCallback = callback;

        try {
            final long authId = mService.detectFingerprint(mToken, userId, mServiceReceiver,
                    mContext.getOpPackageName());
            cancel.setOnCancelListener(new OnFingerprintDetectionCancelListener(authId));
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception when requesting finger detect", e);
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
            EnrollmentCallback callback, @EnrollReason int enrollReason) {
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

        if (mService != null) {
            try {
                mEnrollmentCallback = callback;
                final long enrollId = mService.enroll(mToken, hardwareAuthToken, userId,
                        mServiceReceiver, mContext.getOpPackageName(), enrollReason);
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
            mGenerateChallengeCallback = callback;
            mService.generateChallenge(mToken, sensorId, userId, mServiceReceiver,
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
            mRemovalCallback = callback;
            mRemoveTracker = new RemoveTracker(RemoveTracker.REMOVE_SINGLE, fp);
            mService.remove(mToken, fp.getBiometricId(), userId, mServiceReceiver,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all face templates for the given user.
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void removeAll(int userId, @NonNull RemovalCallback callback) {
        if (mService != null) {
            try {
                mRemovalCallback = callback;
                mRemoveTracker = new RemoveTracker(RemoveTracker.REMOVE_ALL, null /* fp */);
                mService.removeAll(mToken, userId, mServiceReceiver, mContext.getOpPackageName());
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
            return mService.getEnrolledFingerprints(userId, mContext.getOpPackageName());
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
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void setSidefpsController(@NonNull ISidefpsController controller) {
        if (mService == null) {
            Slog.w(TAG, "setSidefpsController: no fingerprint service");
            return;
        }

        try {
            mService.setSidefpsController(controller);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Forwards FingerprintStateListener to FingerprintService
     * @param listener new FingerprintStateListener being added
     * @hide
     */
    public void registerFingerprintStateListener(@NonNull FingerprintStateListener listener) {
        try {
            mService.registerFingerprintStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPointerDown(int sensorId, int x, int y, float minor, float major) {
        if (mService == null) {
            Slog.w(TAG, "onFingerDown: no fingerprint service");
            return;
        }

        try {
            mService.onPointerDown(sensorId, x, y, minor, major);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onPointerUp(int sensorId) {
        if (mService == null) {
            Slog.w(TAG, "onFingerDown: no fingerprint service");
            return;
        }

        try {
            mService.onPointerUp(sensorId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onUiReady(int sensorId) {
        if (mService == null) {
            Slog.w(TAG, "onUiReady: no fingerprint service");
            return;
        }

        try {
            mService.onUiReady(sensorId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
            return mService.hasEnrolledFingerprintsDeprecated(userId, mContext.getOpPackageName());
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
                return mService.isHardwareDetectedDeprecated(mContext.getOpPackageName());
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
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @NonNull
    public List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal() {
        try {
            if (mService == null) {
                return new ArrayList<>();
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
        return sensorProps.sensorType == TYPE_POWER_BUTTON;
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
                    sendEnrollResult((Fingerprint) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_ACQUIRED:
                    sendAcquiredResult(msg.arg1 /* acquire info */,
                            msg.arg2 /* vendorCode */);
                    break;
                case MSG_AUTHENTICATION_SUCCEEDED:
                    sendAuthenticatedSucceeded((Fingerprint) msg.obj, msg.arg1 /* userId */,
                            msg.arg2 == 1 /* isStrongBiometric */);
                    break;
                case MSG_AUTHENTICATION_FAILED:
                    sendAuthenticatedFailed();
                    break;
                case MSG_ERROR:
                    sendErrorResult(msg.arg1 /* errMsgId */, msg.arg2 /* vendorCode */);
                    break;
                case MSG_REMOVED:
                    sendRemovedResult((Fingerprint) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_CHALLENGE_GENERATED:
                    sendChallengeGenerated(msg.arg1 /* sensorId */, msg.arg2 /* userId */,
                            (long) msg.obj /* challenge */);
                    break;
                case MSG_FINGERPRINT_DETECTED:
                    sendFingerprintDetected(msg.arg1 /* sensorId */, msg.arg2 /* userId */,
                            (boolean) msg.obj /* isStrongBiometric */);
                    break;
                case MSG_UDFPS_POINTER_DOWN:
                    sendUdfpsPointerDown(msg.arg1 /* sensorId */);
                    break;
                case MSG_UDFPS_POINTER_UP:
                    sendUdfpsPointerUp(msg.arg1 /* sensorId */);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);

            }
        }
    }

    private void sendRemovedResult(Fingerprint fingerprint, int remaining) {
        if (mRemovalCallback == null) {
            return;
        }

        if (mRemoveTracker == null) {
            Slog.w(TAG, "Removal tracker is null");
            return;
        }

        if (mRemoveTracker.mRemoveRequest == RemoveTracker.REMOVE_SINGLE) {
            if (fingerprint == null) {
                Slog.e(TAG, "Received MSG_REMOVED, but fingerprint is null");
                return;
            }

            if (mRemoveTracker.mSingleFingerprint == null) {
                Slog.e(TAG, "Missing fingerprint");
                return;
            }

            final int fingerId = fingerprint.getBiometricId();
            int reqFingerId = mRemoveTracker.mSingleFingerprint.getBiometricId();
            if (reqFingerId != 0 && fingerId != 0 && fingerId != reqFingerId) {
                Slog.w(TAG, "Finger id didn't match: " + fingerId + " != " + reqFingerId);
                return;
            }
        }

        mRemovalCallback.onRemovalSucceeded(fingerprint, remaining);
    }

    private void sendEnrollResult(Fingerprint fp, int remaining) {
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentProgress(remaining);
        }
    }

    private void sendAuthenticatedSucceeded(Fingerprint fp, int userId, boolean isStrongBiometric) {
        if (mAuthenticationCallback != null) {
            final AuthenticationResult result =
                    new AuthenticationResult(mCryptoObject, fp, userId, isStrongBiometric);
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
            mAuthenticationCallback.onAuthenticationAcquired(acquireInfo);
        }
        final String msg = getAcquiredString(mContext, acquireInfo, vendorCode);
        if (msg == null) {
            return;
        }
        // emulate HAL 2.1 behavior and send real acquiredInfo
        final int clientInfo = acquireInfo == FINGERPRINT_ACQUIRED_VENDOR
                ? (vendorCode + FINGERPRINT_ACQUIRED_VENDOR_BASE) : acquireInfo;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentHelp(clientInfo, msg);
        } else if (mAuthenticationCallback != null) {
            if (acquireInfo != BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START) {
                mAuthenticationCallback.onAuthenticationHelp(clientInfo, msg);
            }
        }
    }

    private void sendErrorResult(int errMsgId, int vendorCode) {
        // emulate HAL 2.1 behavior and send real errMsgId
        final int clientErrMsgId = errMsgId == FINGERPRINT_ERROR_VENDOR
                ? (vendorCode + FINGERPRINT_ERROR_VENDOR_BASE) : errMsgId;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentError(clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        } else if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationError(clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        } else if (mRemovalCallback != null) {
            final Fingerprint fp = mRemoveTracker != null
                    ? mRemoveTracker.mSingleFingerprint : null;
            mRemovalCallback.onRemovalError(fp, clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        }
    }

    private void sendChallengeGenerated(int sensorId, int userId, long challenge) {
        if (mGenerateChallengeCallback == null) {
            Slog.e(TAG, "sendChallengeGenerated, callback null");
            return;
        }
        mGenerateChallengeCallback.onChallengeGenerated(sensorId, userId, challenge);
    }

    private void sendFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric) {
        if (mFingerprintDetectionCallback == null) {
            Slog.e(TAG, "sendFingerprintDetected, callback null");
            return;
        }
        mFingerprintDetectionCallback.onFingerprintDetected(sensorId, userId, isStrongBiometric);
    }

    private void sendUdfpsPointerDown(int sensorId) {
        if (mAuthenticationCallback == null) {
            Slog.e(TAG, "sendUdfpsPointerDown, callback null");
            return;
        }
        mAuthenticationCallback.onUdfpsPointerDown(sensorId);
    }

    private void sendUdfpsPointerUp(int sensorId) {
        if (mAuthenticationCallback == null) {
            Slog.e(TAG, "sendUdfpsPointerUp, callback null");
            return;
        }
        mAuthenticationCallback.onUdfpsPointerUp(sensorId);
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
        mHandler = new MyHandler(context);
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
            mService.cancelAuthentication(mToken, mContext.getOpPackageName(), requestId);
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
    private static float[] createEnrollStageThresholds(@NonNull Context context) {
        // TODO(b/200604947): Fetch this value from FingerprintService, rather than internal config
        final String[] enrollStageThresholdStrings = context.getResources().getStringArray(
                com.android.internal.R.array.config_udfps_enroll_stage_thresholds);

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
            case FINGERPRINT_ACQUIRED_GOOD:
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
            case FINGERPRINT_ACQUIRED_VENDOR: {
                String[] msgArray = context.getResources().getStringArray(
                        com.android.internal.R.array.fingerprint_acquired_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
                break;
            case FINGERPRINT_ACQUIRED_START:
                return null;
        }
        Slog.w(TAG, "Invalid acquired message: " + acquireInfo + ", " + vendorCode);
        return null;
    }

    private IFingerprintServiceReceiver mServiceReceiver = new IFingerprintServiceReceiver.Stub() {

        @Override // binder call
        public void onEnrollResult(Fingerprint fp, int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, remaining, 0, fp).sendToTarget();
        }

        @Override // binder call
        public void onAcquired(int acquireInfo, int vendorCode) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, vendorCode).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationSucceeded(Fingerprint fp, int userId,
                boolean isStrongBiometric) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_SUCCEEDED, userId, isStrongBiometric ? 1 : 0,
                    fp).sendToTarget();
        }

        @Override
        public void onFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric) {
            mHandler.obtainMessage(MSG_FINGERPRINT_DETECTED, sensorId, userId, isStrongBiometric)
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
        public void onRemoved(Fingerprint fp, int remaining) {
            mHandler.obtainMessage(MSG_REMOVED, remaining, 0, fp).sendToTarget();
        }

        @Override // binder call
        public void onChallengeGenerated(int sensorId, int userId, long challenge) {
            mHandler.obtainMessage(MSG_CHALLENGE_GENERATED, sensorId, userId, challenge)
                    .sendToTarget();
        }

        @Override // binder call
        public void onUdfpsPointerDown(int sensorId) {
            mHandler.obtainMessage(MSG_UDFPS_POINTER_DOWN, sensorId, 0).sendToTarget();
        }

        @Override // binder call
        public void onUdfpsPointerUp(int sensorId) {
            mHandler.obtainMessage(MSG_UDFPS_POINTER_UP, sensorId, 0).sendToTarget();
        }
    };

}
