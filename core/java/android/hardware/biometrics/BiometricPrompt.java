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
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.security.identity.IdentityCredential;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.Signature;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A class that manages a system-provided biometric dialog.
 */
public class BiometricPrompt implements BiometricAuthenticator, BiometricConstants {

    private static final String TAG = "BiometricPrompt";

    /**
     * @hide
     */
    public static final String KEY_TITLE = "title";
    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public static final String KEY_USE_DEFAULT_TITLE = "use_default_title";
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
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public static final String KEY_DEVICE_CREDENTIAL_TITLE = "device_credential_title";
    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public static final String KEY_DEVICE_CREDENTIAL_SUBTITLE = "device_credential_subtitle";
    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public static final String KEY_DEVICE_CREDENTIAL_DESCRIPTION = "device_credential_description";
    /**
     * @hide
     */
    public static final String KEY_NEGATIVE_TEXT = "negative_text";
    /**
     * @hide
     */
    public static final String KEY_REQUIRE_CONFIRMATION = "require_confirmation";
    /**
     * This is deprecated. Internally we should use {@link #KEY_AUTHENTICATORS_ALLOWED}
     * @hide
     */
    public static final String KEY_ALLOW_DEVICE_CREDENTIAL = "allow_device_credential";
    /**
     * If this key is set, we will ignore {@link #KEY_ALLOW_DEVICE_CREDENTIAL}
     * @hide
     */
    public static final String KEY_AUTHENTICATORS_ALLOWED = "authenticators_allowed";
    /**
     * If this is set, check the Device Policy Manager for allowed biometrics.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public static final String EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS = "check_dpm";
    /**
     * Request to receive system events, such as back gesture/button. See
     * {@link AuthenticationCallback#onSystemEvent(int)}
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public static final String KEY_RECEIVE_SYSTEM_EVENTS = "receive_system_events";

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
    public static final int DISMISSED_REASON_BIOMETRIC_CONFIRMED = 1;

    /**
     * Dialog is done animating away after user clicked on the button set via
     * {@link BiometricPrompt.Builder#setNegativeButton(CharSequence, Executor,
     * DialogInterface.OnClickListener)}.
     * @hide
     */
    public static final int DISMISSED_REASON_NEGATIVE = 2;

    /**
     * @hide
     */
    public static final int DISMISSED_REASON_USER_CANCEL = 3;

    /**
     * Authenticated, confirmation not required. Dialog animated away.
     * @hide
     */
    public static final int DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED = 4;

    /**
     * Error message shown on SystemUI. When BiometricService receives this, the UI is already
     * gone.
     * @hide
     */
    public static final int DISMISSED_REASON_ERROR = 5;

    /**
     * Dialog dismissal requested by BiometricService.
     * @hide
     */
    public static final int DISMISSED_REASON_SERVER_REQUESTED = 6;

    /**
     * @hide
     */
    public static final int DISMISSED_REASON_CREDENTIAL_CONFIRMED = 7;

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
     */
    public static class Builder {
        private final Bundle mBundle;
        private ButtonInfo mPositiveButtonInfo;
        private ButtonInfo mNegativeButtonInfo;
        private Context mContext;

        /**
         * Creates a builder for a {@link BiometricPrompt} dialog.
         * @param context The {@link Context} that will be used to build the prompt.
         */
        public Builder(Context context) {
            mBundle = new Bundle();
            mContext = context;
        }

        /**
         * Required: Sets the title that will be shown on the prompt.
         * @param title The title to display.
         * @return This builder.
         */
        @NonNull
        public Builder setTitle(@NonNull CharSequence title) {
            mBundle.putCharSequence(KEY_TITLE, title);
            return this;
        }

        /**
         * Shows a default, modality-specific title for the prompt if the title would otherwise be
         * null or empty. Currently for internal use only.
         * @return This builder.
         * @hide
         */
        @RequiresPermission(USE_BIOMETRIC_INTERNAL)
        @NonNull
        public Builder setUseDefaultTitle() {
            mBundle.putBoolean(KEY_USE_DEFAULT_TITLE, true);
            return this;
        }

        /**
         * Optional: Sets a subtitle that will be shown on the prompt.
         * @param subtitle The subtitle to display.
         * @return This builder.
         */
        @NonNull
        public Builder setSubtitle(@NonNull CharSequence subtitle) {
            mBundle.putCharSequence(KEY_SUBTITLE, subtitle);
            return this;
        }

        /**
         * Optional: Sets a description that will be shown on the prompt.
         * @param description The description to display.
         * @return This builder.
         */
        @NonNull
        public Builder setDescription(@NonNull CharSequence description) {
            mBundle.putCharSequence(KEY_DESCRIPTION, description);
            return this;
        }

        /**
         * Sets an optional title, subtitle, and/or description that will override other text when
         * the user is authenticating with PIN/pattern/password. Currently for internal use only.
         * @return This builder.
         * @hide
         */
        @RequiresPermission(USE_BIOMETRIC_INTERNAL)
        @NonNull
        public Builder setTextForDeviceCredential(
                @Nullable CharSequence title,
                @Nullable CharSequence subtitle,
                @Nullable CharSequence description) {
            if (title != null) {
                mBundle.putCharSequence(KEY_DEVICE_CREDENTIAL_TITLE, title);
            }
            if (subtitle != null) {
                mBundle.putCharSequence(KEY_DEVICE_CREDENTIAL_SUBTITLE, subtitle);
            }
            if (description != null) {
                mBundle.putCharSequence(KEY_DEVICE_CREDENTIAL_DESCRIPTION, description);
            }
            return this;
        }

        /**
         * Required: Sets the text, executor, and click listener for the negative button on the
         * prompt. This is typically a cancel button, but may be also used to show an alternative
         * method for authentication, such as a screen that asks for a backup password.
         *
         * <p>Note that this setting is not required, and in fact is explicitly disallowed, if
         * device credential authentication is enabled via {@link #setAllowedAuthenticators(int)} or
         * {@link #setDeviceCredentialAllowed(boolean)}.
         *
         * @param text Text to be shown on the negative button for the prompt.
         * @param executor Executor that will be used to run the on click callback.
         * @param listener Listener containing a callback to be run when the button is pressed.
         * @return This builder.
         */
        @NonNull
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
         * Optional: Sets a hint to the system for whether to require user confirmation after
         * authentication. For example, implicit modalities like face and iris are passive, meaning
         * they don't require an explicit user action to complete authentication. If set to true,
         * these modalities should require the user to take some action (e.g. press a button)
         * before {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is
         * called. Defaults to true.
         *
         * <p>A typical use case for not requiring confirmation would be for low-risk transactions,
         * such as re-authenticating a recently authenticated application. A typical use case for
         * requiring confirmation would be for authorizing a purchase.
         *
         * <p>Note that this just passes a hint to the system, which the system may then ignore. For
         * example, a value of false may be ignored if the user has disabled implicit authentication
         * in Settings, or if it does not apply to a particular modality (e.g. fingerprint).
         *
         * @param requireConfirmation true if explicit user confirmation should be required, or
         *                            false otherwise.
         * @return This builder.
         */
        @NonNull
        public Builder setConfirmationRequired(boolean requireConfirmation) {
            mBundle.putBoolean(KEY_REQUIRE_CONFIRMATION, requireConfirmation);
            return this;
        }

        /**
         * Optional: If enabled, the user will be given the option to authenticate with their device
         * PIN, pattern, or password. Developers should first check {@link
         * BiometricManager#canAuthenticate(int)} for {@link Authenticators#DEVICE_CREDENTIAL}
         * before enabling. If the device is not secured with a credential,
         * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} will be invoked
         * with {@link BiometricPrompt#BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL}. Defaults to false.
         *
         * <p>Note that enabling this option replaces the negative button on the prompt with one
         * that allows the user to authenticate with their device credential, making it an error to
         * call {@link #setNegativeButton(CharSequence, Executor, DialogInterface.OnClickListener)}.
         *
         * @param allowed true if the prompt should fall back to asking for the user's device
         *                credential (PIN/pattern/password), or false otherwise.
         * @return This builder.
         *
         * @deprecated Replaced by {@link #setAllowedAuthenticators(int)}.
         */
        @Deprecated
        @NonNull
        public Builder setDeviceCredentialAllowed(boolean allowed) {
            mBundle.putBoolean(KEY_ALLOW_DEVICE_CREDENTIAL, allowed);
            return this;
        }

        /**
         * Optional: Specifies the type(s) of authenticators that may be invoked by
         * {@link BiometricPrompt} to authenticate the user. Available authenticator types are
         * defined in {@link Authenticators} and can be combined via bitwise OR. Defaults to:
         * <ul>
         *     <li>{@link Authenticators#BIOMETRIC_WEAK} for non-crypto authentication, or</li>
         *     <li>{@link Authenticators#BIOMETRIC_STRONG} for crypto-based authentication.</li>
         * </ul>
         *
         * <p>If this method is used and no authenticator of any of the specified types is available
         * at the time <code>BiometricPrompt#authenticate(...)</code> is called, authentication will
         * be canceled and {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)}
         * will be invoked with an appropriate error code.
         *
         * <p>This method should be preferred over {@link #setDeviceCredentialAllowed(boolean)} and
         * overrides the latter if both are used. Using this method to enable device credential
         * authentication (with {@link Authenticators#DEVICE_CREDENTIAL}) will replace the negative
         * button on the prompt, making it an error to also call
         * {@link #setNegativeButton(CharSequence, Executor, DialogInterface.OnClickListener)}.
         *
         * @param authenticators A bit field representing all valid authenticator types that may be
         *                       invoked by the prompt.
         * @return This builder.
         */
        @NonNull
        public Builder setAllowedAuthenticators(@Authenticators.Types int authenticators) {
            mBundle.putInt(KEY_AUTHENTICATORS_ALLOWED, authenticators);
            return this;
        }

        /**
         * If set check the Device Policy Manager for disabled biometrics.
         *
         * @param checkDevicePolicyManager
         * @return This builder.
         * @hide
         */
        @NonNull
        public Builder setDisallowBiometricsIfPolicyExists(boolean checkDevicePolicyManager) {
            mBundle.putBoolean(EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS,
                    checkDevicePolicyManager);
            return this;
        }

        /**
         * If set, receive internal events via {@link AuthenticationCallback#onSystemEvent(int)}
         * @param set
         * @return This builder.
         * @hide
         */
        @NonNull
        public Builder setReceiveSystemEvents(boolean set) {
            mBundle.putBoolean(KEY_RECEIVE_SYSTEM_EVENTS, set);
            return this;
        }

        /**
         * Creates a {@link BiometricPrompt}.
         *
         * @return An instance of {@link BiometricPrompt}.
         *
         * @throws IllegalArgumentException If any required fields are unset, or if given any
         * invalid combination of field values.
         */
        @NonNull
        public BiometricPrompt build() {
            final CharSequence title = mBundle.getCharSequence(KEY_TITLE);
            final CharSequence negative = mBundle.getCharSequence(KEY_NEGATIVE_TEXT);
            final boolean useDefaultTitle = mBundle.getBoolean(KEY_USE_DEFAULT_TITLE, false);
            final boolean deviceCredentialAllowed = mBundle.getBoolean(KEY_ALLOW_DEVICE_CREDENTIAL);
            final @Authenticators.Types int authenticators =
                    mBundle.getInt(KEY_AUTHENTICATORS_ALLOWED, 0);
            final boolean willShowDeviceCredentialButton = deviceCredentialAllowed
                    || (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;

            if (TextUtils.isEmpty(title) && !useDefaultTitle) {
                throw new IllegalArgumentException("Title must be set and non-empty");
            } else if (TextUtils.isEmpty(negative) && !willShowDeviceCredentialButton) {
                throw new IllegalArgumentException("Negative text must be set and non-empty");
            } else if (!TextUtils.isEmpty(negative) && willShowDeviceCredentialButton) {
                throw new IllegalArgumentException("Can't have both negative button behavior"
                        + " and device credential enabled");
            }
            return new BiometricPrompt(mContext, mBundle, mPositiveButtonInfo, mNegativeButtonInfo);
        }
    }

    private class OnAuthenticationCancelListener implements CancellationSignal.OnCancelListener {
        @Override
        public void onCancel() {
            cancelAuthentication();
        }
    }

    private final IBinder mToken = new Binder();
    private final Context mContext;
    private final IAuthService mService;
    private final Bundle mBundle;
    private final ButtonInfo mPositiveButtonInfo;
    private final ButtonInfo mNegativeButtonInfo;

    private CryptoObject mCryptoObject;
    private Executor mExecutor;
    private AuthenticationCallback mAuthenticationCallback;

    private final IBiometricServiceReceiver mBiometricServiceReceiver =
            new IBiometricServiceReceiver.Stub() {

        @Override
        public void onAuthenticationSucceeded(@AuthenticationResultType int authenticationType)
                throws RemoteException {
            mExecutor.execute(() -> {
                final AuthenticationResult result =
                        new AuthenticationResult(mCryptoObject, authenticationType);
                mAuthenticationCallback.onAuthenticationSucceeded(result);
            });
        }

        @Override
        public void onAuthenticationFailed() throws RemoteException {
            mExecutor.execute(() -> {
                mAuthenticationCallback.onAuthenticationFailed();
            });
        }

        @Override
        public void onError(int modality, int error, int vendorCode) throws RemoteException {
            mExecutor.execute(() -> {
                String errorMessage;
                switch (modality) {
                    case TYPE_FACE:
                        errorMessage = FaceManager.getErrorString(mContext, error, vendorCode);
                        break;

                    case TYPE_FINGERPRINT:
                        errorMessage = FingerprintManager.getErrorString(mContext, error,
                                vendorCode);
                        break;

                    default:
                        errorMessage = "";
                }
                mAuthenticationCallback.onAuthenticationError(error, errorMessage);
            });
        }

        @Override
        public void onAcquired(int acquireInfo, String message) throws RemoteException {
            mExecutor.execute(() -> {
                mAuthenticationCallback.onAuthenticationHelp(acquireInfo, message);
            });
        }

        @Override
        public void onDialogDismissed(int reason) throws RemoteException {
            // Check the reason and invoke OnClickListener(s) if necessary
            if (reason == DISMISSED_REASON_BIOMETRIC_CONFIRMED) {
                mPositiveButtonInfo.executor.execute(() -> {
                    mPositiveButtonInfo.listener.onClick(null, DialogInterface.BUTTON_POSITIVE);
                });
            } else if (reason == DISMISSED_REASON_NEGATIVE) {
                mNegativeButtonInfo.executor.execute(() -> {
                    mNegativeButtonInfo.listener.onClick(null, DialogInterface.BUTTON_NEGATIVE);
                });
            }
        }

        @Override
        public void onSystemEvent(int event) throws RemoteException {
            mExecutor.execute(() -> {
                mAuthenticationCallback.onSystemEvent(event);
            });
        }
    };

    private BiometricPrompt(Context context, Bundle bundle,
            ButtonInfo positiveButtonInfo, ButtonInfo negativeButtonInfo) {
        mContext = context;
        mBundle = bundle;
        mPositiveButtonInfo = positiveButtonInfo;
        mNegativeButtonInfo = negativeButtonInfo;
        mService = IAuthService.Stub.asInterface(
                ServiceManager.getService(Context.AUTH_SERVICE));
    }

    /**
     * Gets the title for the prompt, as set by {@link Builder#setTitle(CharSequence)}.
     * @return The title of the prompt, which is guaranteed to be non-null.
     */
    @NonNull
    public CharSequence getTitle() {
        return mBundle.getCharSequence(KEY_TITLE, "");
    }

    /**
     * Whether to use a default modality-specific title. For internal use only.
     * @return See {@link Builder#setUseDefaultTitle()}.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public boolean shouldUseDefaultTitle() {
        return mBundle.getBoolean(KEY_USE_DEFAULT_TITLE, false);
    }

    /**
     * Gets the subtitle for the prompt, as set by {@link Builder#setSubtitle(CharSequence)}.
     * @return The subtitle for the prompt, or null if the prompt has no subtitle.
     */
    @Nullable
    public CharSequence getSubtitle() {
        return mBundle.getCharSequence(KEY_SUBTITLE);
    }

    /**
     * Gets the description for the prompt, as set by {@link Builder#setDescription(CharSequence)}.
     * @return The description for the prompt, or null if the prompt has no description.
     */
    @Nullable
    public CharSequence getDescription() {
        return mBundle.getCharSequence(KEY_DESCRIPTION);
    }

    /**
     * Gets the negative button text for the prompt, as set by
     * {@link Builder#setNegativeButton(CharSequence, Executor, DialogInterface.OnClickListener)}.
     * @return The negative button text for the prompt, or null if no negative button text was set.
     */
    @Nullable
    public CharSequence getNegativeButtonText() {
        return mBundle.getCharSequence(KEY_NEGATIVE_TEXT);
    }

    /**
     * Determines if explicit user confirmation is required by the prompt, as set by
     * {@link Builder#setConfirmationRequired(boolean)}.
     *
     * @return true if explicit user confirmation is required, or false otherwise.
     */
    public boolean isConfirmationRequired() {
        return mBundle.getBoolean(KEY_REQUIRE_CONFIRMATION, true);
    }

    /**
     * Gets the type(s) of authenticators that may be invoked by the prompt to authenticate the
     * user, as set by {@link Builder#setAllowedAuthenticators(int)}.
     *
     * @return A bit field representing the type(s) of authenticators that may be invoked by the
     * prompt (as defined by {@link Authenticators}), or 0 if this field was not set.
     */
    @Nullable
    public int getAllowedAuthenticators() {
        return mBundle.getInt(KEY_AUTHENTICATORS_ALLOWED, 0);
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

        public CryptoObject(@NonNull IdentityCredential credential) {
            super(credential);
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
         */
        public @Nullable IdentityCredential getIdentityCredential() {
            return super.getIdentityCredential();
        }
    }

    /**
     * Authentication type reported by {@link AuthenticationResult} when the user authenticated by
     * entering their device PIN, pattern, or password.
     */
    public static final int AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL = 1;

    /**
     * Authentication type reported by {@link AuthenticationResult} when the user authenticated by
     * presenting some form of biometric (e.g. fingerprint or face).
     */
    public static final int AUTHENTICATION_RESULT_TYPE_BIOMETRIC = 2;

    /**
     * An {@link IntDef} representing the type of auth, as reported by {@link AuthenticationResult}.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL, AUTHENTICATION_RESULT_TYPE_BIOMETRIC})
    public @interface AuthenticationResultType {
    }

    /**
     * Container for callback data from {@link #authenticate(CancellationSignal, Executor,
     * AuthenticationCallback)} and {@link #authenticate(CryptoObject, CancellationSignal, Executor,
     * AuthenticationCallback)}.
     */
    public static class AuthenticationResult extends BiometricAuthenticator.AuthenticationResult {
        /**
         * Authentication result
         * @param crypto
         * @param authenticationType
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto,
                @AuthenticationResultType int authenticationType) {
            // Identifier and userId is not used for BiometricPrompt.
            super(crypto, authenticationType, null /* identifier */, 0 /* userId */);
        }

        /**
         * Provides the crypto object associated with this transaction.
         * @return The crypto object provided to {@link #authenticate(CryptoObject,
         * CancellationSignal, Executor, AuthenticationCallback)}
         */
        public CryptoObject getCryptoObject() {
            return (CryptoObject) super.getCryptoObject();
        }

        /**
         * Provides the type of authentication (e.g. device credential or biometric) that was
         * requested from and successfully provided by the user.
         *
         * @return An integer value representing the authentication method used.
         */
        public @AuthenticationResultType int getAuthenticationType() {
            return super.getAuthenticationType();
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
         * Receiver for internal system events. See {@link Builder#setReceiveSystemEvents(boolean)}
         * @hide
         */
        public void onSystemEvent(int event) {}
    }

    /**
     * Authenticates for the given user.
     *
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     * @param userId The user to authenticate
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void authenticateUser(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback,
            int userId) {
        if (cancel == null) {
            throw new IllegalArgumentException("Must supply a cancellation signal");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must supply an executor");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply a callback");
        }
        authenticateInternal(null /* crypto */, cancel, executor, callback, userId);
    }

    /**
     * This call warms up the biometric hardware, displays a system-provided dialog, and starts
     * scanning for a biometric. It terminates when {@link
     * AuthenticationCallback#onAuthenticationError(int, CharSequence)} is called, when {@link
     * AuthenticationCallback#onAuthenticationSucceeded( AuthenticationResult)}, or when the user
     * dismisses the system-provided dialog, at which point the crypto object becomes invalid. This
     * operation can be canceled by using the provided cancel object. The application will receive
     * authentication errors through {@link AuthenticationCallback}, and button events through the
     * corresponding callback set in {@link Builder#setNegativeButton(CharSequence, Executor,
     * DialogInterface.OnClickListener)}. It is safe to reuse the {@link BiometricPrompt} object,
     * and calling {@link BiometricPrompt#authenticate(CancellationSignal, Executor,
     * AuthenticationCallback)} while an existing authentication attempt is occurring will stop the
     * previous client and start a new authentication. The interrupted client will receive a
     * cancelled notification through {@link AuthenticationCallback#onAuthenticationError(int,
     * CharSequence)}.
     *
     * <p>Note: Applications generally should not cancel and start authentication in quick
     * succession. For example, to properly handle authentication across configuration changes, it's
     * recommended to use BiometricPrompt in a fragment with setRetainInstance(true). By doing so,
     * the application will not need to cancel/restart authentication during the configuration
     * change.
     *
     * <p>Per the Android CDD, only biometric authenticators that meet or exceed the requirements
     * for <strong>Strong</strong> are permitted to integrate with Keystore to perform related
     * cryptographic operations. Therefore, it is an error to call this method after explicitly
     * calling {@link Builder#setAllowedAuthenticators(int)} with any biometric strength other than
     * {@link Authenticators#BIOMETRIC_STRONG}.
     *
     * @throws IllegalArgumentException If any argument is null, or if the allowed biometric
     * authenticator strength is explicitly set to {@link Authenticators#BIOMETRIC_WEAK}. Prior to
     * {@link android.os.Build.VERSION_CODES#R}, this exception is also thrown if
     * {@link Builder#setDeviceCredentialAllowed(boolean)} was explicitly set to true.
     *
     * @param crypto A cryptographic operation to be unlocked after successful authentication.
     * @param cancel An object that can be used to cancel authentication.
     * @param executor An executor to handle callback events.
     * @param callback An object to receive authentication events.
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@NonNull CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback) {
        if (crypto == null) {
            throw new IllegalArgumentException("Must supply a crypto object");
        }
        if (cancel == null) {
            throw new IllegalArgumentException("Must supply a cancellation signal");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must supply an executor");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply a callback");
        }

        // Disallow explicitly setting any non-Strong biometric authenticator types.
        final @Authenticators.Types int authenticators = mBundle.getInt(
                KEY_AUTHENTICATORS_ALLOWED, Authenticators.BIOMETRIC_STRONG);
        final int biometricStrength = authenticators & Authenticators.BIOMETRIC_WEAK;
        if ((biometricStrength & ~Authenticators.BIOMETRIC_STRONG) != 0) {
            throw new IllegalArgumentException("Only Strong biometrics supported with crypto");
        }

        authenticateInternal(crypto, cancel, executor, callback, mContext.getUserId());
    }

    /**
     * This call warms up the biometric hardware, displays a system-provided dialog, and starts
     * scanning for a biometric. It terminates when {@link
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
     * <p>Note: Applications generally should not cancel and start authentication in quick
     * succession. For example, to properly handle authentication across configuration changes, it's
     * recommended to use BiometricPrompt in a fragment with setRetainInstance(true). By doing so,
     * the application will not need to cancel/restart authentication during the configuration
     * change.
     *
     * @throws IllegalArgumentException If any of the arguments are null.
     *
     * @param cancel An object that can be used to cancel authentication.
     * @param executor An executor to handle callback events.
     * @param callback An object to receive authentication events.
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback) {
        if (cancel == null) {
            throw new IllegalArgumentException("Must supply a cancellation signal");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must supply an executor");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply a callback");
        }
        authenticateInternal(null /* crypto */, cancel, executor, callback, mContext.getUserId());
    }

    private void cancelAuthentication() {
        if (mService != null) {
            try {
                mService.cancelAuthentication(mToken, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to cancel authentication", e);
            }
        }
    }

    private void authenticateInternal(@Nullable CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback,
            int userId) {
        try {
            if (cancel.isCanceled()) {
                Log.w(TAG, "Authentication already canceled");
                return;
            } else {
                cancel.setOnCancelListener(new OnAuthenticationCancelListener());
            }

            mCryptoObject = crypto;
            mExecutor = executor;
            mAuthenticationCallback = callback;
            final long sessionId = crypto != null ? crypto.getOpId() : 0;

            final Bundle bundle;
            if (crypto != null) {
                // Allowed authenticators should default to BIOMETRIC_STRONG for crypto auth.
                // Note that we use a new bundle here so as to not overwrite the application's
                // preference, since it is possible that the same prompt configuration be used
                // without a crypto object later.
                bundle = new Bundle(mBundle);
                bundle.putInt(KEY_AUTHENTICATORS_ALLOWED,
                        mBundle.getInt(KEY_AUTHENTICATORS_ALLOWED,
                                Authenticators.BIOMETRIC_STRONG));
            } else {
                bundle = mBundle;
            }

            mService.authenticate(mToken, sessionId, userId, mBiometricServiceReceiver,
                    mContext.getOpPackageName(), bundle);

        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception while authenticating", e);
            mExecutor.execute(() -> {
                callback.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        mContext.getString(R.string.biometric_error_hw_unavailable));
            });
        }
    }
}
