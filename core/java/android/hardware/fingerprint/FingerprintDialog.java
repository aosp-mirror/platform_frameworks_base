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

package android.hardware.fingerprint;

import static android.Manifest.permission.USE_FINGERPRINT;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.fingerprint.FingerprintManager.AuthenticationCallback;
import android.hardware.fingerprint.FingerprintManager.AuthenticationResult;
import android.hardware.fingerprint.IFingerprintDialogReceiver;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.TextUtils;

import java.util.concurrent.Executor;

/**
 * A class that manages a system-provided fingerprint dialog.
 */
public class FingerprintDialog {

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
    public static final int HIDE_DIALOG_DELAY = 3000; // ms
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
     * A builder that collects arguments, to be shown on the system-provided fingerprint dialog.
     **/
    public static class Builder {
        private final Bundle bundle;
        private ButtonInfo positiveButtonInfo;
        private ButtonInfo negativeButtonInfo;

        /**
         * Creates a builder for a fingerprint dialog.
         */
        public Builder() {
            bundle = new Bundle();
        }

        /**
         * Required: Set the title to display.
         * @param title
         * @return
         */
        public Builder setTitle(@NonNull CharSequence title) {
            bundle.putCharSequence(KEY_TITLE, title);
            return this;
        }

        /**
         * Optional: Set the subtitle to display.
         * @param subtitle
         * @return
         */
        public Builder setSubtitle(@NonNull CharSequence subtitle) {
            bundle.putCharSequence(KEY_SUBTITLE, subtitle);
            return this;
        }

        /**
         * Optional: Set the description to display.
         * @param description
         * @return
         */
        public Builder setDescription(@NonNull CharSequence description) {
            bundle.putCharSequence(KEY_DESCRIPTION, description);
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
            bundle.putCharSequence(KEY_POSITIVE_TEXT, text);
            positiveButtonInfo = new ButtonInfo(executor, listener);
            return this;
        }

        /**
         * Required: Set the text for the negative button.
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
            bundle.putCharSequence(KEY_NEGATIVE_TEXT, text);
            negativeButtonInfo = new ButtonInfo(executor, listener);
            return this;
        }

        /**
         * Creates a {@link FingerprintDialog} with the arguments supplied to this builder.
         * @param context
         * @return a {@link FingerprintDialog}
         * @throws IllegalArgumentException if any of the required fields are not set.
         */
        public FingerprintDialog build(Context context) {
            final CharSequence title = bundle.getCharSequence(KEY_TITLE);
            final CharSequence negative = bundle.getCharSequence(KEY_NEGATIVE_TEXT);

            if (TextUtils.isEmpty(title)) {
                throw new IllegalArgumentException("Title must be set and non-empty");
            } else if (TextUtils.isEmpty(negative)) {
                throw new IllegalArgumentException("Negative text must be set and non-empty");
            }
            return new FingerprintDialog(context, bundle, positiveButtonInfo, negativeButtonInfo);
        }
    }

    private FingerprintManager mFingerprintManager;
    private Bundle mBundle;
    private ButtonInfo mPositiveButtonInfo;
    private ButtonInfo mNegativeButtonInfo;

    IFingerprintDialogReceiver mDialogReceiver = new IFingerprintDialogReceiver.Stub() {
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

    private FingerprintDialog(Context context, Bundle bundle,
            ButtonInfo positiveButtonInfo, ButtonInfo negativeButtonInfo) {
        mBundle = bundle;
        mPositiveButtonInfo = positiveButtonInfo;
        mNegativeButtonInfo = negativeButtonInfo;
        mFingerprintManager = context.getSystemService(FingerprintManager.class);
    }

    /**
     * This call warms up the fingerprint hardware, displays a system-provided dialog,
     * and starts scanning for a fingerprint. It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} is called, when
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is called,
     * when {@link AuthenticationCallback#onAuthenticationFailed()} is called or when the user
     * dismisses the system-provided dialog, at which point the crypto object becomes invalid.
     * This operation can be canceled by using the provided cancel object. The application will
     * receive authentication errors through {@link AuthenticationCallback}, and button events
     * through the corresponding callback set in
     * {@link Builder#setNegativeButton(CharSequence, Executor, DialogInterface.OnClickListener)}.
     * It is safe to reuse the {@link FingerprintDialog} object, and calling
     * {@link FingerprintDialog#authenticate(CancellationSignal, Executor, AuthenticationCallback)}
     * while an existing authentication attempt is occurring will stop the previous client and
     * start a new authentication. The interrupted client will receive a cancelled notification
     * through {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)}.
     *
     * @throws IllegalArgumentException if any of the arguments are null
     *
     * @param crypto object associated with the call
     * @param cancel an object that can be used to cancel authentication
     * @param executor an executor to handle callback events
     * @param callback an object to receive authentication events
     */
    @RequiresPermission(USE_FINGERPRINT)
    public void authenticate(@NonNull FingerprintManager.CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull FingerprintManager.AuthenticationCallback callback) {
        mFingerprintManager.authenticate(crypto, cancel, mBundle, executor, mDialogReceiver,
                callback);
    }

    /**
     * This call warms up the fingerprint hardware, displays a system-provided dialog,
     * and starts scanning for a fingerprint. It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} is called, when
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is called,
     * when {@link AuthenticationCallback#onAuthenticationFailed()} is called or when the user
     * dismisses the system-provided dialog. This operation can be canceled by using the provided
     * cancel object. The application will receive authentication errors through
     * {@link AuthenticationCallback}, and button events through the corresponding callback set in
     * {@link Builder#setNegativeButton(CharSequence, Executor, DialogInterface.OnClickListener)}.
     * It is safe to reuse the {@link FingerprintDialog} object, and calling
     * {@link FingerprintDialog#authenticate(CancellationSignal, Executor, AuthenticationCallback)}
     * while an existing authentication attempt is occurring will stop the previous client and
     * start a new authentication. The interrupted client will receive a cancelled notification
     * through {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)}.
     *
     * @throws IllegalArgumentException if any of the arguments are null
     *
     * @param cancel an object that can be used to cancel authentication
     * @param executor an executor to handle callback events
     * @param callback an object to receive authentication events
     */
    @RequiresPermission(USE_FINGERPRINT)
    public void authenticate(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull FingerprintManager.AuthenticationCallback callback) {
        mFingerprintManager.authenticate(cancel, mBundle, executor, mDialogReceiver, callback);
    }
}
