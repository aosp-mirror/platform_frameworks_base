/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.apc.IConfirmationCallback;
import android.security.apc.IProtectedConfirmation;
import android.security.apc.ResponseCode;
import android.util.Log;

/**
 * @hide
 */
public class AndroidProtectedConfirmation {
    private static final String TAG = "AndroidProtectedConfirmation";

    public static final int ERROR_OK = ResponseCode.OK;
    public static final int ERROR_CANCELED = ResponseCode.CANCELLED;
    public static final int ERROR_ABORTED = ResponseCode.ABORTED;
    public static final int ERROR_OPERATION_PENDING = ResponseCode.OPERATION_PENDING;
    public static final int ERROR_IGNORED = ResponseCode.IGNORED;
    public static final int ERROR_SYSTEM_ERROR = ResponseCode.SYSTEM_ERROR;
    public static final int ERROR_UNIMPLEMENTED = ResponseCode.UNIMPLEMENTED;

    public static final int FLAG_UI_OPTION_INVERTED =
            IProtectedConfirmation.FLAG_UI_OPTION_INVERTED;
    public static final int FLAG_UI_OPTION_MAGNIFIED =
            IProtectedConfirmation.FLAG_UI_OPTION_MAGNIFIED;

    private IProtectedConfirmation mProtectedConfirmation;

    public AndroidProtectedConfirmation() {
        mProtectedConfirmation = null;
    }

    private synchronized IProtectedConfirmation getService() {
        if (mProtectedConfirmation == null) {
            mProtectedConfirmation = IProtectedConfirmation.Stub.asInterface(ServiceManager
                    .getService("android.security.apc"));
        }
        return mProtectedConfirmation;
    }

    /**
     * Requests keystore call into the confirmationui HAL to display a prompt.
     * @deprecated Android Protected Confirmation had a low adoption rate among Android device
     *             makers and developers alike. Given the lack of devices supporting the
     *             feature, it is deprecated. Developers can use auth-bound Keystore keys
     *             as a partial replacement.
     *
     * @param listener the binder to use for callbacks.
     * @param promptText the prompt to display.
     * @param extraData extra data / nonce from application.
     * @param locale the locale as a BCP 47 language tag.
     * @param uiOptionsAsFlags the UI options to use, as flags.
     * @return one of the {@code CONFIRMATIONUI_*} constants, for
     * example {@code KeyStore.CONFIRMATIONUI_OK}.
     */
    @Deprecated
    public int presentConfirmationPrompt(IConfirmationCallback listener, String promptText,
                                         byte[] extraData, String locale, int uiOptionsAsFlags) {
        try {
            getService().presentPrompt(listener, promptText, extraData, locale,
                                                     uiOptionsAsFlags);
            return ERROR_OK;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return ERROR_SYSTEM_ERROR;
        } catch (ServiceSpecificException e) {
            return e.errorCode;
        }
    }

    /**
     * Requests keystore call into the confirmationui HAL to cancel displaying a prompt.
     * @deprecated Android Protected Confirmation had a low adoption rate among Android device
     *             makers and developers alike. Given the lack of devices supporting the
     *             feature, it is deprecated. Developers can use auth-bound Keystore keys
     *             as a partial replacement.
     *
     * @param listener the binder passed to the {@link #presentConfirmationPrompt} method.
     * @return one of the {@code CONFIRMATIONUI_*} constants, for
     * example {@code KeyStore.CONFIRMATIONUI_OK}.
     */
    @Deprecated
    public int cancelConfirmationPrompt(IConfirmationCallback listener) {
        try {
            getService().cancelPrompt(listener);
            return ERROR_OK;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return ERROR_SYSTEM_ERROR;
        } catch (ServiceSpecificException e) {
            return e.errorCode;
        }
    }

    /**
     * Requests keystore to check if the confirmationui HAL is available.
     * @deprecated Android Protected Confirmation had a low adoption rate among Android device
     *             makers and developers alike. Given the lack of devices supporting the
     *             feature, it is deprecated. Developers can use auth-bound Keystore keys
     *             as a partial replacement.
     *
     * @return whether the confirmationUI HAL is available.
     */
    @Deprecated
    public boolean isConfirmationPromptSupported() {
        try {
            return getService().isSupported();
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

}
