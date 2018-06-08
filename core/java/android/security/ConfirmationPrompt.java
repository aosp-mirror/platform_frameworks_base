/*
 * Copyright 2018 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Class used for displaying confirmation prompts.
 *
 * <p>Confirmation prompts are prompts shown to the user to confirm a given text and are
 * implemented in a way that a positive response indicates with high confidence that the user has
 * seen the given text, even if the Android framework (including the kernel) was
 * compromised. Implementing confirmation prompts with these guarantees requires dedicated
 * hardware-support and may not always be available.
 *
 * <p>Confirmation prompts are typically used with an external entitity - the <i>Relying Party</i> -
 * in the following way. The setup steps are as follows:
 * <ul>
 * <li> Before first use, the application generates a key-pair with the
 * {@link android.security.keystore.KeyGenParameterSpec.Builder#setUserConfirmationRequired
 * CONFIRMATION tag} set. Device attestation,
 * e.g. {@link java.security.KeyStore#getCertificateChain getCertificateChain()}, is used to
 * generate a certificate chain that includes the public key (<code>Kpub</code> in the following)
 * of the newly generated key.
 * <li> The application sends <code>Kpub</code> and the certificate chain resulting from device
 * attestation to the <i>Relying Party</i>.
 * <li> The <i>Relying Party</i> validates the certificate chain which involves checking the root
 * certificate is what is expected (e.g. a certificate from Google), each certificate signs the
 * next one in the chain, ending with <code>Kpub</code>, and that the attestation certificate
 * asserts that <code>Kpub</code> has the
 * {@link android.security.keystore.KeyGenParameterSpec.Builder#setUserConfirmationRequired
 * CONFIRMATION tag} set.
 * Additionally the relying party stores <code>Kpub</code> and associates it with the device
 * it was received from.
 * </ul>
 *
 * <p>The <i>Relying Party</i> is typically an external device (for example connected via
 * Bluetooth) or application server.
 *
 * <p>Before executing a transaction which requires a high assurance of user content, the
 * application does the following:
 * <ul>
 * <li> The application gets a cryptographic nonce from the <i>Relying Party</i> and passes this as
 * the <code>extraData</code> (via the Builder helper class) to the
 * {@link #presentPrompt presentPrompt()} method. The <i>Relying Party</i> stores the nonce locally
 * since it'll use it in a later step.
 * <li> If the user approves the prompt a <i>Confirmation Response</i> is returned in the
 * {@link ConfirmationCallback#onConfirmed onConfirmed(byte[])} callback as the
 * <code>dataThatWasConfirmed</code> parameter. This blob contains the text that was shown to the
 * user, the <code>extraData</code> parameter, and possibly other data.
 * <li> The application signs the <i>Confirmation Response</i> with the previously created key and
 * sends the blob and the signature to the <i>Relying Party</i>.
 * <li> The <i>Relying Party</i> checks that the signature was made with <code>Kpub</code> and then
 * extracts <code>promptText</code> matches what is expected and <code>extraData</code> matches the
 * previously created nonce. If all checks passes, the transaction is executed.
 * </ul>
 *
 * <p>A common way of implementing the "<code>promptText</code> is what is expected" check in the
 * last bullet, is to have the <i>Relying Party</i> generate <code>promptText</code> and store it
 * along the nonce in the <code>extraData</code> blob.
 */
public class ConfirmationPrompt {
    private static final String TAG = "ConfirmationPrompt";

    private CharSequence mPromptText;
    private byte[] mExtraData;
    private ConfirmationCallback mCallback;
    private Executor mExecutor;
    private Context mContext;

    private final KeyStore mKeyStore = KeyStore.getInstance();

    private void doCallback(int responseCode, byte[] dataThatWasConfirmed,
            ConfirmationCallback callback) {
        switch (responseCode) {
            case KeyStore.CONFIRMATIONUI_OK:
                callback.onConfirmed(dataThatWasConfirmed);
                break;

            case KeyStore.CONFIRMATIONUI_CANCELED:
                callback.onDismissed();
                break;

            case KeyStore.CONFIRMATIONUI_ABORTED:
                callback.onCanceled();
                break;

            case KeyStore.CONFIRMATIONUI_SYSTEM_ERROR:
                callback.onError(new Exception("System error returned by ConfirmationUI."));
                break;

            default:
                callback.onError(new Exception("Unexpected responseCode=" + responseCode
                                + " from onConfirmtionPromptCompleted() callback."));
                break;
        }
    }

    private final android.os.IBinder mCallbackBinder =
            new android.security.IConfirmationPromptCallback.Stub() {
                @Override
                public void onConfirmationPromptCompleted(
                        int responseCode, final byte[] dataThatWasConfirmed)
                        throws android.os.RemoteException {
                    if (mCallback != null) {
                        ConfirmationCallback callback = mCallback;
                        Executor executor = mExecutor;
                        mCallback = null;
                        mExecutor = null;
                        if (executor == null) {
                            doCallback(responseCode, dataThatWasConfirmed, callback);
                        } else {
                            executor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        doCallback(responseCode, dataThatWasConfirmed, callback);
                                    }
                                });
                        }
                    }
                }
            };

    /**
     * A builder that collects arguments, to be shown on the system-provided confirmation prompt.
     */
    public static final class Builder {

        private Context mContext;
        private CharSequence mPromptText;
        private byte[] mExtraData;

        /**
         * Creates a builder for the confirmation prompt.
         *
         * @param context the application context
         */
        public Builder(Context context) {
            mContext = context;
        }

        /**
         * Sets the prompt text for the prompt.
         *
         * @param promptText the text to present in the prompt.
         * @return the builder.
         */
        public Builder setPromptText(CharSequence promptText) {
            mPromptText = promptText;
            return this;
        }

        /**
         * Sets the extra data for the prompt.
         *
         * @param extraData data to include in the response data.
         * @return the builder.
         */
        public Builder setExtraData(byte[] extraData) {
            mExtraData = extraData;
            return this;
        }

        /**
         * Creates a {@link ConfirmationPrompt} with the arguments supplied to this builder.
         *
         * @return a {@link ConfirmationPrompt}
         * @throws IllegalArgumentException if any of the required fields are not set.
         */
        public ConfirmationPrompt build() {
            if (TextUtils.isEmpty(mPromptText)) {
                throw new IllegalArgumentException("prompt text must be set and non-empty");
            }
            if (mExtraData == null) {
                throw new IllegalArgumentException("extraData must be set");
            }
            return new ConfirmationPrompt(mContext, mPromptText, mExtraData);
        }
    }

    private ConfirmationPrompt(Context context, CharSequence promptText, byte[] extraData) {
        mContext = context;
        mPromptText = promptText;
        mExtraData = extraData;
    }

    private static final int UI_OPTION_ACCESSIBILITY_INVERTED_FLAG = 1 << 0;
    private static final int UI_OPTION_ACCESSIBILITY_MAGNIFIED_FLAG = 1 << 1;

    private int getUiOptionsAsFlags() {
        int uiOptionsAsFlags = 0;
        try {
            ContentResolver contentResolver = mContext.getContentResolver();
            int inversionEnabled = Settings.Secure.getInt(contentResolver,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
            if (inversionEnabled == 1) {
                uiOptionsAsFlags |= UI_OPTION_ACCESSIBILITY_INVERTED_FLAG;
            }
            float fontScale = Settings.System.getFloat(contentResolver,
                    Settings.System.FONT_SCALE);
            if (fontScale > 1.0) {
                uiOptionsAsFlags |= UI_OPTION_ACCESSIBILITY_MAGNIFIED_FLAG;
            }
        } catch (SettingNotFoundException e) {
            Log.w(TAG, "Unexpected SettingNotFoundException");
        }
        return uiOptionsAsFlags;
    }

    private static boolean isAccessibilityServiceRunning(Context context) {
        boolean serviceRunning = false;
        try {
            ContentResolver contentResolver = context.getContentResolver();
            int a11yEnabled = Settings.Secure.getInt(contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (a11yEnabled == 1) {
                serviceRunning = true;
            }
        } catch (SettingNotFoundException e) {
            Log.w(TAG, "Unexpected SettingNotFoundException");
            e.printStackTrace();
        }
        return serviceRunning;
    }

    /**
     * Requests a confirmation prompt to be presented to the user.
     *
     * When the prompt is no longer being presented, one of the methods in
     * {@link ConfirmationCallback} is called on the supplied callback object.
     *
     * Confirmation prompts may not be available when accessibility services are running so this
     * may fail with a {@link ConfirmationNotAvailableException} exception even if
     * {@link #isSupported} returns {@code true}.
     *
     * @param executor the executor identifying the thread that will receive the callback.
     * @param callback the callback to use when the prompt is done showing.
     * @throws IllegalArgumentException if the prompt text is too long or malfomed.
     * @throws ConfirmationAlreadyPresentingException if another prompt is being presented.
     * @throws ConfirmationNotAvailableException if confirmation prompts are not supported.
     */
    public void presentPrompt(@NonNull Executor executor, @NonNull ConfirmationCallback callback)
            throws ConfirmationAlreadyPresentingException,
            ConfirmationNotAvailableException {
        if (mCallback != null) {
            throw new ConfirmationAlreadyPresentingException();
        }
        if (isAccessibilityServiceRunning(mContext)) {
            throw new ConfirmationNotAvailableException();
        }
        mCallback = callback;
        mExecutor = executor;

        int uiOptionsAsFlags = getUiOptionsAsFlags();
        String locale = Locale.getDefault().toLanguageTag();
        int responseCode = mKeyStore.presentConfirmationPrompt(
                mCallbackBinder, mPromptText.toString(), mExtraData, locale, uiOptionsAsFlags);
        switch (responseCode) {
            case KeyStore.CONFIRMATIONUI_OK:
                return;

            case KeyStore.CONFIRMATIONUI_OPERATION_PENDING:
                throw new ConfirmationAlreadyPresentingException();

            case KeyStore.CONFIRMATIONUI_UNIMPLEMENTED:
                throw new ConfirmationNotAvailableException();

            case KeyStore.CONFIRMATIONUI_UIERROR:
                throw new IllegalArgumentException();

            default:
                // Unexpected error code.
                Log.w(TAG,
                        "Unexpected responseCode=" + responseCode
                        + " from presentConfirmationPrompt() call.");
                throw new IllegalArgumentException();
        }
    }

    /**
     * Cancels a prompt currently being displayed.
     *
     * On success, the
     * {@link ConfirmationCallback#onCanceled onCanceled()} method on
     * the supplied callback object will be called asynchronously.
     *
     * @throws IllegalStateException if no prompt is currently being presented.
     */
    public void cancelPrompt() {
        int responseCode = mKeyStore.cancelConfirmationPrompt(mCallbackBinder);
        if (responseCode == KeyStore.CONFIRMATIONUI_OK) {
            return;
        } else if (responseCode == KeyStore.CONFIRMATIONUI_OPERATION_PENDING) {
            throw new IllegalStateException();
        } else {
            // Unexpected error code.
            Log.w(TAG,
                    "Unexpected responseCode=" + responseCode
                    + " from cancelConfirmationPrompt() call.");
            throw new IllegalStateException();
        }
    }

    /**
     * Checks if the device supports confirmation prompts.
     *
     * @param context the application context.
     * @return true if confirmation prompts are supported by the device.
     */
    public static boolean isSupported(Context context) {
        if (isAccessibilityServiceRunning(context)) {
            return false;
        }
        return KeyStore.getInstance().isConfirmationPromptSupported();
    }
}
