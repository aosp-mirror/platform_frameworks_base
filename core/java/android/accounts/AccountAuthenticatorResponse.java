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

import android.annotation.UnsupportedAppUsage;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Object used to communicate responses back to the AccountManager
 */
public class AccountAuthenticatorResponse implements Parcelable {
    private static final String TAG = "AccountAuthenticator";

    private IAccountAuthenticatorResponse mAccountAuthenticatorResponse;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public AccountAuthenticatorResponse(IAccountAuthenticatorResponse response) {
        mAccountAuthenticatorResponse = response;
    }

    public AccountAuthenticatorResponse(Parcel parcel) {
        mAccountAuthenticatorResponse =
                IAccountAuthenticatorResponse.Stub.asInterface(parcel.readStrongBinder());
    }

    public void onResult(Bundle result) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            result.keySet(); // force it to be unparcelled
            Log.v(TAG, "AccountAuthenticatorResponse.onResult: "
                    + AccountManager.sanitizeResult(result));
        }
        try {
            mAccountAuthenticatorResponse.onResult(result);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onRequestContinued() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "AccountAuthenticatorResponse.onRequestContinued");
        }
        try {
            mAccountAuthenticatorResponse.onRequestContinued();
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onError(int errorCode, String errorMessage) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "AccountAuthenticatorResponse.onError: " + errorCode + ", " + errorMessage);
        }
        try {
            mAccountAuthenticatorResponse.onError(errorCode, errorMessage);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mAccountAuthenticatorResponse.asBinder());
    }

    public static final @android.annotation.NonNull Creator<AccountAuthenticatorResponse> CREATOR =
            new Creator<AccountAuthenticatorResponse>() {
        public AccountAuthenticatorResponse createFromParcel(Parcel source) {
            return new AccountAuthenticatorResponse(source);
        }

        public AccountAuthenticatorResponse[] newArray(int size) {
            return new AccountAuthenticatorResponse[size];
        }
    };
}
