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

package android.security.keystore;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFormatException;

/**
 * The Java side of the KeystoreResponse.
 * <p>
 * Serialization code for this and subclasses must be kept in sync with system/security/keystore.
 * @hide
 */
public class KeystoreResponse implements Parcelable {
    public final int error_code_;
    public final String error_msg_;

    public static final @android.annotation.NonNull Parcelable.Creator<KeystoreResponse> CREATOR = new
            Parcelable.Creator<KeystoreResponse>() {
                @Override
                public KeystoreResponse createFromParcel(Parcel in) {
                    final int error_code = in.readInt();
                    final String error_msg = in.readString();
                    return new KeystoreResponse(error_code, error_msg);
                }

                @Override
                public KeystoreResponse[] newArray(int size) {
                    return new KeystoreResponse[size];
                }
            };

    protected KeystoreResponse(int error_code, String error_msg) {
        this.error_code_ = error_code;
        this.error_msg_ = error_msg;
    }

    /**
     * @return the error_code_
     */
    public final int getErrorCode() {
        return error_code_;
    }

    /**
     * @return the error_msg_
     */
    public final String getErrorMessage() {
        return error_msg_;
    }

    
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(error_code_);
        out.writeString(error_msg_);
    }
}
