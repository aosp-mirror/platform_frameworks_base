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
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.credentials.GetCredentialResponse;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A credential entry that is to be displayed on the account selector that is presented to the
 * user.
 *
 * <p>If user selects this entry, the corresponding {@link PendingIntent},
 * set on the {@code slice} as a {@link androidx.slice.core.SliceAction} will be
 * invoked to launch activities that require some user engagement before getting
 * the credential corresponding to this entry, e.g. authentication, confirmation etc.
 *
 * Once the activity fulfills the required user engagement, the {@link android.app.Activity}
 * result should be set to {@link android.app.Activity#RESULT_OK}, and the
 * {@link CredentialProviderService#EXTRA_GET_CREDENTIAL_RESPONSE} must be set with a
 * {@link GetCredentialResponse} object.
 *
 * <p>Any class that derives this class must only add extra field values to the {@code slice}
 * object passed into the constructor. Any other field will not be parceled through. If the
 * derived class has custom parceling implementation, this class will not be able to unpack
 * the parcel without having access to that implementation.
 */
@SuppressLint("ParcelNotFinal")
public class CredentialEntry implements Parcelable {
    /** The type of the credential entry to be shown on the UI. */
    private final @NonNull String mType;

    /** The object containing display content to be shown along with this credential entry
     * on the UI. */
    private final @NonNull Slice mSlice;

    public CredentialEntry(@NonNull String type, @NonNull Slice slice) {
        mType = type;
        mSlice = slice;
    }

    private CredentialEntry(@NonNull Parcel in) {
        mType = in.readString8();
        mSlice = in.readTypedObject(Slice.CREATOR);
    }

    @NonNull
    public static final Creator<CredentialEntry> CREATOR =
            new Creator<CredentialEntry>() {
                @Override
                public CredentialEntry createFromParcel(@NonNull Parcel in) {
                    return new CredentialEntry(in);
                }

                @Override
                public CredentialEntry[] newArray(int size) {
                    return new CredentialEntry[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeTypedObject(mSlice, flags);
    }

    /**
     * Returns the specific credential type of the entry.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the {@link Slice} object containing UI display content to be shown for this entry.
     */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }
}
