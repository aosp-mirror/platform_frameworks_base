/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;

/**
 * <p><code>ConvertCredentialCallback</code> handles convertCredentialResponse from Autofill
 * Service.
 *
 * @hide
 */
public final class ConvertCredentialCallback {

    private static final String TAG = "ConvertCredentialCallback";

    private final IConvertCredentialCallback mCallback;

    /** @hide */
    public ConvertCredentialCallback(IConvertCredentialCallback callback) {
        mCallback = callback;
    }

    /**
     * Notifies the Android System that a convertCredentialRequest was fulfilled by the service.
     *
     * @param convertCredentialResponse the result
     */
    public void onSuccess(@NonNull ConvertCredentialResponse convertCredentialResponse) {
        try {
            mCallback.onSuccess(convertCredentialResponse);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notifies the Android System that a convert credential request has failed
     *
     * @param message the error message
     */
    public void onFailure(@Nullable CharSequence message) {
        try {
            mCallback.onFailure(message);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}
