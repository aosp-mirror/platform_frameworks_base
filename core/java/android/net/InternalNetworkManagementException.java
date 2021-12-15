/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public final class InternalNetworkManagementException
        extends RuntimeException implements Parcelable {

    /* @hide */
    public InternalNetworkManagementException(@NonNull final Throwable t) {
        super(t);
    }

    private InternalNetworkManagementException(@NonNull final Parcel source) {
        super(source.readString());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getCause().getMessage());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<InternalNetworkManagementException> CREATOR =
            new Parcelable.Creator<InternalNetworkManagementException>() {
                @Override
                public InternalNetworkManagementException[] newArray(int size) {
                    return new InternalNetworkManagementException[size];
                }

                @Override
                public InternalNetworkManagementException createFromParcel(@NonNull Parcel source) {
                    return new InternalNetworkManagementException(source);
                }
            };
}
