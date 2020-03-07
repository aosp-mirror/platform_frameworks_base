/**
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
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_FINGERPRINT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricNativeHandleUtils;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricNativeHandle;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.NativeHandle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.identity.IdentityCredential;
import android.util.Slog;

import java.security.Signature;
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
    private static final int MSG_ENUMERATED = 106;

    private IFingerprintService mService;
    private Context mContext;
    private IBinder mToken = new Binder();
    private AuthenticationCallback mAuthenticationCallback;
    private EnrollmentCallback mEnrollmentCallback;
    private RemovalCallback mRemovalCallback;
    private EnumerateCallback mEnumerateCallback;
    private CryptoObject mCryptoObject;
    private Fingerprint mRemovalFingerprint;
    private Handler mHandler;

    private class OnEnrollCancelListener implements OnCancelListener {
        @Override
        public void onCancel() {
            cancelEnrollment();
        }
    }

    private class OnAuthenticationCancelListener implements OnCancelListener {
        private android.hardware.biometrics.CryptoObject mCrypto;

        public OnAuthenticationCancelListener(android.hardware.biometrics.CryptoObject crypto) {
            mCrypto = crypto;
        }

        @Override
        public void onCancel() {
            cancelAuthentication(mCrypto);
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
         */
        public IdentityCredential getIdentityCredential() {
            return super.getIdentityCredential();
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
    };

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
    };

    /**
     * Callback structure provided to {@link FingerprintManager#enroll(byte[], CancellationSignal,
     * int, int, EnrollmentCallback)} must provide an implementation of this for listening to
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
    };

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
        public void onRemovalSucceeded(Fingerprint fp, int remaining) { }
    };

    /**
     * Callback structure provided to {@link FingerprintManager#enumerate(int, EnumerateCallback)}.
     * Users of{@link #FingerprintManager} may optionally provide an implementation of this to
     * {@link FingerprintManager#enumerate(int, EnumerateCallback)} for listening to
     * fingerprint template removal events.
     *
     * @hide
     */
    public static abstract class EnumerateCallback {
        /**
         * Called when the given fingerprint can't be removed.
         * @param errMsgId An associated error message id
         * @param errString An error message indicating why the fingerprint id can't be removed
         */
        public void onEnumerateError(int errMsgId, CharSequence errString) { }

        /**
         * Called when a given fingerprint is successfully removed.
         * @param fingerprint the fingerprint template that was removed.
         */
        public void onEnumerate(Fingerprint fingerprint) { }
    };

    /**
     * @hide
     */
    public static abstract class LockoutResetCallback {

        /**
         * Called when lockout period expired and clients are allowed to listen for fingerprint
         * again.
         */
        public void onLockoutReset() { }
    };

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
        authenticate(crypto, cancel, flags, callback, handler, mContext.getUserId());
    }

    /**
     * Use the provided handler thread for events.
     * @param handler
     */
    private void useHandler(Handler handler) {
        if (handler != null) {
            mHandler = new MyHandler(handler.getLooper());
        } else if (mHandler.getLooper() != mContext.getMainLooper()){
            mHandler = new MyHandler(mContext.getMainLooper());
        }
    }

    /**
     * Defaults to {@link FingerprintManager#authenticate(CryptoObject, CancellationSignal, int,
     * AuthenticationCallback, Handler, int, NativeHandle)} with {@code windowId} set to null.
     *
     * @see FingerprintManager#authenticate(CryptoObject, CancellationSignal, int,
     * AuthenticationCallback, Handler, int, NativeHandle)
     *
     * @hide
     */
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            int flags, @NonNull AuthenticationCallback callback, Handler handler, int userId) {
        authenticate(crypto, cancel, flags, callback, handler, userId, null /* windowId */);
    }

    /**
     * Per-user version, see {@link FingerprintManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}. This version does not
     * display the BiometricPrompt.
     * @param userId the user ID that the fingerprint hardware will authenticate for.
     * @param windowId for optical fingerprint sensors that require active illumination by the OLED
     *        display. Should be null for devices that don't require illumination.
     * @hide
     */
    @RequiresPermission(anyOf = {USE_BIOMETRIC, USE_FINGERPRINT})
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            int flags, @NonNull AuthenticationCallback callback, Handler handler, int userId,
            @Nullable NativeHandle windowId) {
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
            IBiometricNativeHandle handle = BiometricNativeHandleUtils.dup(windowId);
            try {
                useHandler(handler);
                mAuthenticationCallback = callback;
                mCryptoObject = crypto;
                long sessionId = crypto != null ? crypto.getOpId() : 0;
                mService.authenticate(mToken, sessionId, userId, mServiceReceiver, flags,
                        mContext.getOpPackageName(), handle);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception while authenticating: ", e);
                // Though this may not be a hardware issue, it will cause apps to give up or try
                // again later.
                callback.onAuthenticationError(FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                                0 /* vendorCode */));
            } finally {
                BiometricNativeHandleUtils.close(handle);
            }
        }
    }

    /**
     * Defaults to {@link FingerprintManager#enroll(byte[], CancellationSignal, int, int,
     * EnrollmentCallback, NativeHandle)} with {@code windowId} set to null.
     *
     * @see FingerprintManager#enroll(byte[], CancellationSignal, int, int, EnrollmentCallback,
     * NativeHandle)
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void enroll(byte [] token, CancellationSignal cancel, int flags,
            int userId, EnrollmentCallback callback) {
        enroll(token, cancel, flags, userId, callback, null /* windowId */);
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
     * @param flags optional flags
     * @param userId the user to whom this fingerprint will belong to
     * @param callback an object to receive enrollment events
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void enroll(byte [] token, CancellationSignal cancel, int flags,
            int userId, EnrollmentCallback callback, @Nullable NativeHandle windowId) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = getCurrentUserId();
        }
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
            IBiometricNativeHandle handle = BiometricNativeHandleUtils.dup(windowId);
            try {
                mEnrollmentCallback = callback;
                mService.enroll(mToken, token, userId, mServiceReceiver, flags,
                        mContext.getOpPackageName(), handle);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in enroll: ", e);
                // Though this may not be a hardware issue, it will cause apps to give up or try
                // again later.
                callback.onEnrollmentError(FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                                0 /* vendorCode */));
            } finally {
                BiometricNativeHandleUtils.close(handle);
            }
        }
    }

    /**
     * Requests a pre-enrollment auth token to tie enrollment to the confirmation of
     * existing device credentials (e.g. pin/pattern/password).
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public long preEnroll() {
        long result = 0;
        if (mService != null) try {
            result = mService.preEnroll(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Finishes enrollment and cancels the current auth token.
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public int postEnroll() {
        int result = 0;
        if (mService != null) try {
            result = mService.postEnroll(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Sets the active user. This is meant to be used to select the current profile for enrollment
     * to allow separate enrolled fingers for a work profile
     * @param userId
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    @Override
    public void setActiveUser(int userId) {
        if (mService != null) try {
            mService.setActiveUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            mRemovalFingerprint = fp;
            mService.remove(mToken, fp.getBiometricId(), fp.getGroupId(), userId, mServiceReceiver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception in remove: ", e);
            if (callback != null) {
                callback.onRemovalError(fp, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                            0 /* vendorCode */));
            }
        }
    }

    /**
     * Enumerate all fingerprint templates stored in hardware and/or protected storage.
     * @param userId the user who this fingerprint belongs to
     * @param callback an optional callback to verify that fingerprint templates have been
     * successfully removed. May be null of no callback is required.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FINGERPRINT)
    public void enumerate(int userId, @NonNull EnumerateCallback callback) {
        if (mService != null) try {
            mEnumerateCallback = callback;
            mService.enumerate(mToken, userId, mServiceReceiver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception in enumerate: ", e);
            if (callback != null) {
                callback.onEnumerateError(FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        getErrorString(mContext, FINGERPRINT_ERROR_HW_UNAVAILABLE,
                            0 /* vendorCode */));
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
    @UnsupportedAppUsage
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
    @Override
    public boolean hasEnrolledTemplates() {
        return hasEnrolledFingerprints();
    }

    /**
     * @hide
     */
    @Override
    public boolean hasEnrolledTemplates(int userId) {
        return hasEnrolledFingerprints(userId);
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
        if (mService != null) try {
            return mService.hasEnrolledFingerprints(
                    mContext.getUserId(), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            USE_FINGERPRINT,
            INTERACT_ACROSS_USERS})
    public boolean hasEnrolledFingerprints(int userId) {
        if (mService != null) try {
            return mService.hasEnrolledFingerprints(userId, mContext.getOpPackageName());
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
    @Override
    public boolean isHardwareDetected() {
        if (mService != null) {
            try {
                return mService.isHardwareDetected(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "isFingerprintHardwareDetected(): Service not connected!");
        }
        return false;
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
                    public void onLockoutReset(long deviceId, IRemoteCallback serverCallback)
                            throws RemoteException {
                        try {
                            final PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                                    PowerManager.PARTIAL_WAKE_LOCK, "lockoutResetCallback");
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
                    sendAcquiredResult((Long) msg.obj /* deviceId */, msg.arg1 /* acquire info */,
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
                    sendErrorResult((Long) msg.obj /* deviceId */, msg.arg1 /* errMsgId */,
                            msg.arg2 /* vendorCode */);
                    break;
                case MSG_REMOVED:
                    sendRemovedResult((Fingerprint) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_ENUMERATED:
                    sendEnumeratedResult((Long) msg.obj /* deviceId */, msg.arg1 /* fingerId */,
                            msg.arg2 /* groupId */);
                    break;
            }
        }
    };

    private void sendRemovedResult(Fingerprint fingerprint, int remaining) {
        if (mRemovalCallback == null) {
            return;
        }
        if (fingerprint == null) {
            Slog.e(TAG, "Received MSG_REMOVED, but fingerprint is null");
            return;
        }

        int fingerId = fingerprint.getBiometricId();
        int reqFingerId = mRemovalFingerprint.getBiometricId();
        if (reqFingerId != 0 && fingerId != 0 && fingerId != reqFingerId) {
            Slog.w(TAG, "Finger id didn't match: " + fingerId + " != " + reqFingerId);
            return;
        }
        int groupId = fingerprint.getGroupId();
        int reqGroupId = mRemovalFingerprint.getGroupId();
        if (groupId != reqGroupId) {
            Slog.w(TAG, "Group id didn't match: " + groupId + " != " + reqGroupId);
            return;
        }

        mRemovalCallback.onRemovalSucceeded(fingerprint, remaining);
    }

    private void sendEnumeratedResult(long deviceId, int fingerId, int groupId) {
        if (mEnumerateCallback != null) {
            mEnumerateCallback.onEnumerate(new Fingerprint(null, groupId, fingerId, deviceId));
        }
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

    private void sendAcquiredResult(long deviceId, int acquireInfo, int vendorCode) {
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

    private void sendErrorResult(long deviceId, int errMsgId, int vendorCode) {
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
            mRemovalCallback.onRemovalError(mRemovalFingerprint, clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        } else if (mEnumerateCallback != null) {
            mEnumerateCallback.onEnumerateError(clientErrMsgId,
                    getErrorString(mContext, errMsgId, vendorCode));
        }
    }

    /**
     * @hide
     */
    public FingerprintManager(Context context, IFingerprintService service) {
        mContext = context;
        mService = service;
        if (mService == null) {
            Slog.v(TAG, "FingerprintManagerService was null");
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

    private void cancelEnrollment() {
        if (mService != null) try {
            mService.cancelEnrollment(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void cancelAuthentication(android.hardware.biometrics.CryptoObject cryptoObject) {
        if (mService != null) try {
            mService.cancelAuthentication(mToken, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
            case FINGERPRINT_ERROR_VENDOR: {
                    String[] msgArray = context.getResources().getStringArray(
                            com.android.internal.R.array.fingerprint_error_vendor);
                    if (vendorCode < msgArray.length) {
                        return msgArray[vendorCode];
                    }
                }
        }
        Slog.w(TAG, "Invalid error message: " + errMsg + ", " + vendorCode);
        return null;
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
        public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, remaining, 0,
                    new Fingerprint(null, groupId, fingerId, deviceId)).sendToTarget();
        }

        @Override // binder call
        public void onAcquired(long deviceId, int acquireInfo, int vendorCode) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, vendorCode,
                    deviceId).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationSucceeded(long deviceId, Fingerprint fp, int userId,
                boolean isStrongBiometric) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_SUCCEEDED, userId, isStrongBiometric ? 1 : 0,
                    fp).sendToTarget();
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
        public void onRemoved(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.obtainMessage(MSG_REMOVED, remaining, 0,
                    new Fingerprint(null, groupId, fingerId, deviceId)).sendToTarget();
        }

        @Override // binder call
        public void onEnumerated(long deviceId, int fingerId, int groupId, int remaining) {
            // TODO: propagate remaining
            mHandler.obtainMessage(MSG_ENUMERATED, fingerId, groupId, deviceId).sendToTarget();
        }
    };

}
