/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accounts;

import android.os.RemoteException;

/**
 * Object that wraps calls to an {@link IAccountAuthenticatorResponse} object.
 * TODO: this interface is still in flux
 */
public class AccountAuthenticatorResponse {
    private IAccountAuthenticatorResponse mAccountAuthenticatorResponse;

    public AccountAuthenticatorResponse(IAccountAuthenticatorResponse response) {
        mAccountAuthenticatorResponse = response;
    }

    public void onFinished(int result) {
        try {
            mAccountAuthenticatorResponse.onIntResult(result);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onFinished(String result) {
        try {
            mAccountAuthenticatorResponse.onStringResult(result);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onFinished(boolean result) {
        try {
            mAccountAuthenticatorResponse.onBooleanResult(result);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onError(int errorCode, String errorMessage) {
        try {
            mAccountAuthenticatorResponse.onError(errorCode, errorMessage);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public IAccountAuthenticatorResponse getIAccountAuthenticatorResponse() {
        return mAccountAuthenticatorResponse;
    }
}
