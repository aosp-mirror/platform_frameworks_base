/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.credentials;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Log;

/**
 * Callback to be invoked as a response to {@link CreateCredentialRequest}.
 *
 * @hide
 */
public final class CreateCredentialCallback {
    private static final String TAG = "CreateCredentialCallback";

    private final ICreateCredentialCallback mCallback;

    /** @hide */
    public CreateCredentialCallback(@NonNull ICreateCredentialCallback callback) {
        mCallback = callback;
    }

    /**
     * Invoked on a successful response for {@link CreateCredentialRequest}
     * @param response The response from the credential provider.
     */
    public void onSuccess(@NonNull CreateCredentialResponse response) {
        try {
            mCallback.onSuccess(response);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Invoked on a failure response for {@link CreateCredentialRequest}
     * @param errorCode The code defining the type of error.
     * @param message The message corresponding to the failure.
     */
    public void onFailure(int errorCode, @Nullable CharSequence message) {
        Log.w(TAG, "onFailure: " + message);
        try {
            mCallback.onFailure(errorCode, message);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}
