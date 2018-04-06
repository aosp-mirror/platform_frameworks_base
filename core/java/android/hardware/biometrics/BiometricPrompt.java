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

import static android.Manifest.permission.USE_BIOMETRIC;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.TextUtils;

import java.security.Signature;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A class that manages a system-provided biometric dialog.
 */
public class BiometricPrompt implements BiometricAuthenticator, BiometricConstants {

    /**
     * @hide
     */
    public static final String KEY_TITLE = "title";
    /**
     * @hide
     */
    public static final String KEY_SUBTITLE = "subtitle";
    /**
     * @hide
     */
    public static final String KEY_DESCRIPTION = "description";
    /**
     * @hide
     */
    public static final String KEY_POSITIVE_TEXT = "positive_text";
    /**
     * @hide
     */
    public static final String KEY_NEGATIVE_TEXT = "negative_text";

    /**
     * Error/help message will show for this amount of time.
     * For error messages, the dialog will also be dismissed after this amount of time.
     * Error messages will be propagated back to the application via AuthenticationCallback
     * after this amount of time.
     * @hide
     */
    public static final int HIDE_DIALOG_DELAY = 2000; // ms
    /**
     * @hide
     */
    public static final int DISMISSED_REASON_POSITIVE = 1;

    /**
     * @hide
     */
    public static final int DISMISSED_REASON_NEGATIVE = 2;

    /**
     * @hide
     */
    public static final int DISMISSED_REASON_USER_CANCEL = 3;

    private static class ButtonInfo {
        Executor executor;
        DialogInterface.OnClickListener listener;
        ButtonInfo(Executor ex, DialogInterface.OnClickListener l) {
            executor = ex;
            listener = l;
        }
    }

    /**
     * A builder that collects arguments to be shown on the system-provided biometric dialog.
     **/
    public static class Builder {
        private final Bundle mBundle;
        private ButtonInfo mPositiveButtonInfo;
        private ButtonInfo mNegativeButtonInfo;
        private Context mContext;

        /**
         * Creates a builder for a biometric dialog.
         * @param context
         */
        public Builder(Context context) {
            mBundle = new Bundle();
            mContext = context;
        }

        /**
         * Required: Set the title to display.
         * @param title
         * @return
         */
        public Builder setTitle(@NonNull CharSequence title) {
            mBundle.putCharSequence(KEY_TITLE, title);
            return this;
        }

        /**
         * Optional: Set the subtitle to display.
         * @param subtitle
         * @return
         */
        public Builder setSubtitle(@NonNull CharSequence subtitle) {
            mBundle.putCharSequence(KEY_SUBTITLE, subtitle);
            return this;
        }

        /**
         * Optional: Set the description to display.
         * @param description
         * @return
         */
        public Builder setDescription(@NonNull CharSequence description) {
            mBundle.putCharSequence(KEY_DESCRIPTION, description);
            return this;
        }

        /**
         * Optional: Set the text for the positive button. If not set, the positive button
         * will not show.
         * @param text
         * @return
         * @hide
         */
        public Builder setPositiveButton(@NonNull CharSequence text,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull DialogInterface.OnClickListener listener) {
            if (TextUtils.isEmpty(text)) {
                throw new IllegalArgumentException("Text must be set and non-empty");
            }
            if (executor == null) {
                throw new IllegalArgumentException("Executor must not be null");
            }
            if (listener == null) {
                throw new IllegalArgumentException("Listener must not be null");
            }
            mBundle.putCharSequence(KEY_POSITIVE_TEXT, text);
            mPositiveButtonInfo = new ButtonInfo(executor, listener);
            return this;
        }

        /**
         * Required: Set the text for the negative button. This would typically be used as a
         * "Cancel" button, but may be also used to show an alternative method for authentication,
         * such as screen that asks for a backup password.
         * @param text
         * @return
         */
        public Builder setNegativeButton(@NonNull CharSequence text,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull DialogInterface.OnClickListener listener) {
            if (TextUtils.isEmpty(text)) {
                throw new IllegalArgumentException("Text must be set and non-empty");
            }
            if (executor == null) {
                throw new IllegalArgumentException("Executor must not be null");
            }
            if (listener == null) {
                throw new IllegalArgumentException("Listener must not be null");
            }
            mBundle.putCharSequence(KEY_NEGATIVE_TEXT, text);
            mNegativeButtonInfo = new ButtonInfo(executor, listener);
            return this;
        }

        /**
         * Creates a {@link BiometricPrompt}.
         * @return a {@link BiometricPrompt}
         * @throws IllegalArgumentException if any of the required fields are not set.
         */
        public BiometricPrompt build() {
            final CharSequence title = mBundle.getCharSequence(KEY_TITLE);
            final CharSequence negative = mBundle.getCharSequence(KEY_NEGATIVE_TEXT);

            if (TextUtils.isEmpty(title)) {
                throw new IllegalArgumentException("Title must be set and non-empty");
            } else if (TextUtils.isEmpty(negative)) {
                throw new IllegalArgumentException("Negative text must be set and non-empty");
            }
            return new BiometricPrompt(mContext, mBundle, mPositiveButtonInfo, mNegativeButtonInfo);
        }
    }

    private PackageManager mPackageManager;
    private FingerprintManager mFingerprintManager;
    private Bundle mBundle;
    private ButtonInfo mPositiveButtonInfo;
    private ButtonInfo mNegativeButtonInfo;

    IBiometricPromptReceiver mDialogReceiver = new IBiometricPromptReceiver.Stub() {
        @Override
        public void onDialogDismissed(int reason) {
            // Check the reason and invoke OnClickListener(s) if necessary
            if (reason == DISMISSED_REASON_POSITIVE) {
                mPositiveButtonInfo.executor.execute(() -> {
                    mPositiveButtonInfo.listener.onClick(null, DialogInterface.BUTTON_POSITIVE);
                });
            } else if (reason == DISMISSED_REASON_NEGATIVE) {
                mNegativeButtonInfo.executor.execute(() -> {
                    mNegativeButtonInfo.listener.onClick(null, DialogInterface.BUTTON_NEGATIVE);
                });
            }
        }
    };

    private BiometricPrompt(Context context, Bundle bundle,
            ButtonInfo positiveButtonInfo, ButtonInfo negativeButtonInfo) {
        mBundle = bundle;
        mPositiveButtonInfo = positiveButtonInfo;
        mNegativeButtonInfo = negativeButtonInfo;
        mFingerprintManager = context.getSystemService(FingerprintManager.class);
        mPackageManager = context.getPackageManager();
    }

    /**
     * A wrapper class for the crypto objects supported by BiometricPrompt. Currently the framework
     * supports {@link Signature}, {@link Cipher} and {@link Mac} objects.
     */
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
    }

    /**
     * Container for callback data from {@link #authenticate( CancellationSignal, Executor,
     * AuthenticationCallback)} and {@link #authenticate(CryptoObject, CancellationSignal, Executor,
     * AuthenticationCallback)}
     */
    public static class AuthenticationResult extends BiometricAuthenticator.AuthenticationResult {
        /**
         * Authentication result
         * @param crypto
         * @param identifier
         * @param userId
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto, BiometricIdentifier identifier,
                int userId) {
            super(crypto, identifier, userId);
        }
        /**
         * Obtain the crypto object associated with this transaction
         * @return crypto object provided to {@link #authenticate( CryptoObject, CancellationSignal,
         * Executor, AuthenticationCallback)}
         */
        public CryptoObject getCryptoObject() {
            return (CryptoObject) super.getCryptoObject();
        }
    }

    /**
     * Callback structure provided to {@link BiometricPrompt#authenticate(CancellationSignal,
     * Executor, AuthenticationCallback)} or {@link BiometricPrompt#authenticate(CryptoObject,
     * CancellationSignal, Executor, AuthenticationCallback)}. Users must provide an implementation
     * of this for listening to authentication events.
     */
    public abstract static class AuthenticationCallback extends
            BiometricAuthenticator.AuthenticationCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further actions will be made on this object.
         * @param errorCode An integer identifying the error message
         * @param errString A human-readable error string that can be shown on an UI
         */
        @Override
        public void onAuthenticationError(int errorCode, CharSequence errString) {}

        /**
         * Called when a recoverable error has been encountered during authentication. The help
         * string is provided to give the user guidance for what went wrong, such as "Sensor dirty,
         * please clean it."
         * @param helpCode An integer identifying the error message
         * @param helpString A human-readable string that can be shown on an UI
         */
        @Override
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {}

        /**
         * Called when a biometric is recognized.
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(AuthenticationResult result) {}

        /**
         * Called when a biometric is valid but not recognized.
         */
        @Override
        public void onAuthenticationFailed() {}

        /**
         * Called when a biometric has been acquired, but hasn't been processed yet.
         * @hide
         */
        @Override
        public void onAuthenticationAcquired(int acquireInfo) {}

        /**
         * @param result An object containing authentication-related data
         * @hide
         */
        @Override
        public void onAuthenticationSucceeded(BiometricAuthenticator.AuthenticationResult result) {
            onAuthenticationSucceeded(new AuthenticationResult(
                    (CryptoObject) result.getCryptoObject(),
                    result.getId(),
                    result.getUserId()));
        }
    }

    /**
     * @param crypto Object associated with the call
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     * @hide
     */
    @Override
    public void authenticate(@NonNull android.hardware.biometrics.CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BiometricAuthenticator.AuthenticationCallback callback) {
        if (!(callback instanceof BiometricPrompt.AuthenticationCallback)) {
            throw new IllegalArgumentException("Callback cannot be casted");
        }
        authenticate(crypto, cancel, executor, (AuthenticationCallback) callback);
    }

    /**
     *
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     * @hide
     */
    @Override
    public void authenticate(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BiometricAuthenticator.AuthenticationCallback callback) {
        if (!(callback instanceof BiometricPrompt.AuthenticationCallback)) {
            throw new IllegalArgumentException("Callback cannot be casted");
        }
        authenticate(cancel, executor, (AuthenticationCallback) callback);
    }

    /**
     * This call warms up the fingerprint hardware, displays a system-provided dialog, and starts
     * scanning for a fingerprint. It terminates when {@link
     * AuthenticationCallback#onAuthenticationError(int, CharSequence)} is called, when {@link
     * AuthenticationCallback#onAuthenticationSucceeded( AuthenticationResult)}, or when the user
     * dismisses the system-provided dialog, at which point the crypto object becomes invalid. This
     * operation can be canceled by using the provided cancel object. The application will receive
     * authentication errors through {@link AuthenticationCallback}, and button events through the
     * corresponding callback set in {@link Builder#setNegativeButton(CharSequence, Executor,
     * DialogInterface.OnClickListener)}. It is safe to reuse the {@link BiometricPrompt} object,
     * and calling {@link BiometricPrompt#authenticate( CancellationSignal, Executor,
     * AuthenticationCallback)} while an existing authentication attempt is occurring will stop the
     * previous client and start a new authentication. The interrupted client will receive a
     * cancelled notification through {@link AuthenticationCallback#onAuthenticationError(int,
     * CharSequence)}.
     *
     * @throws IllegalArgumentException If any of the arguments are null
     *
     * @param crypto Object associated with the call
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@NonNull CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback) {
        if (handlePreAuthenticationErrors(callback, executor)) {
            return;
        }
        mFingerprintManager.authenticate(crypto, cancel, mBundle, executor, mDialogReceiver,
                callback);
    }

    /**
     * This call warms up the fingerprint hardware, displays a system-provided dialog, and starts
     * scanning for a fingerprint. It terminates when {@link
     * AuthenticationCallback#onAuthenticationError(int, CharSequence)} is called, when {@link
     * AuthenticationCallback#onAuthenticationSucceeded( AuthenticationResult)} is called, or when
     * the user dismisses the system-provided dialog.  This operation can be canceled by using the
     * provided cancel object. The application will receive authentication errors through {@link
     * AuthenticationCallback}, and button events through the corresponding callback set in {@link
     * Builder#setNegativeButton(CharSequence, Executor, DialogInterface.OnClickListener)}.  It is
     * safe to reuse the {@link BiometricPrompt} object, and calling {@link
     * BiometricPrompt#authenticate(CancellationSignal, Executor, AuthenticationCallback)} while
     * an existing authentication attempt is occurring will stop the previous client and start a new
     * authentication. The interrupted client will receive a cancelled notification through {@link
     * AuthenticationCallback#onAuthenticationError(int, CharSequence)}.
     *
     * @throws IllegalArgumentException If any of the arguments are null
     *
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback) {
        if (handlePreAuthenticationErrors(callback, executor)) {
            return;
        }
        mFingerprintManager.authenticate(cancel, mBundle, executor, mDialogReceiver, callback);
    }

    private boolean handlePreAuthenticationErrors(AuthenticationCallback callback,
            Executor executor) {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            sendError(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, callback,
                      executor);
            return true;
        } else if (!mFingerprintManager.isHardwareDetected()) {
            sendError(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, callback,
                      executor);
            return true;
        } else if (!mFingerprintManager.hasEnrolledFingerprints()) {
            sendError(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, callback,
                      executor);
            return true;
        }
        return false;
    }

    private void sendError(int error, AuthenticationCallback callback, Executor executor) {
        executor.execute(() -> {
            callback.onAuthenticationError(error, mFingerprintManager.getErrorString(
                    error, 0 /* vendorCode */));
        });
    }
}
