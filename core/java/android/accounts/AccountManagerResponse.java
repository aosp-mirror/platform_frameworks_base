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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * Used to return a response to the AccountManager.
 * @hide
 */
public class AccountManagerResponse implements Parcelable {
    private IAccountManagerResponse mResponse;

    /** @hide */
    public AccountManagerResponse(IAccountManagerResponse response) {
        mResponse = response;
    }

    /** @hide */
    public AccountManagerResponse(Parcel parcel) {
        mResponse =
                IAccountManagerResponse.Stub.asInterface(parcel.readStrongBinder());
    }

    public void onResult(Bundle result) {
        try {
            mResponse.onResult(result);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    public void onError(int errorCode, String errorMessage) {
        try {
            mResponse.onError(errorCode, errorMessage);
        } catch (RemoteException e) {
            // this should never happen
        }
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mResponse.asBinder());
    }

    /** @hide */
    public static final @android.annotation.NonNull Creator<AccountManagerResponse> CREATOR =
            new Creator<AccountManagerResponse>() {
        public AccountManagerResponse createFromParcel(Parcel source) {
            return new AccountManagerResponse(source);
        }

        public AccountManagerResponse[] newArray(int size) {
            return new AccountManagerResponse[size];
        }
    };
}
