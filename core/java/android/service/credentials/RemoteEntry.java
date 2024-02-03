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
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An entry to be shown on the UI. This entry represents remote execution of a get/create flow
 * whereby credentials are retrieved from, or stored to a remote device.
 *
 * <p>If user selects this entry, the corresponding {@link PendingIntent} set on the
 * {@code slice} as a {@link androidx.slice.core.SliceAction} will get invoked.
 * Once the resulting activity fulfills the required user engagement,
 * the {@link android.app.Activity} result should be set to {@link android.app.Activity#RESULT_OK},
 * and the result of the operation must be set as the activity result.
 *
 * For a get flow, invoked through {@link CredentialProviderService#onBeginGetCredential},
 * providers must set a {@link android.credentials.GetCredentialResponse} on the activity result,
 * against the key {@link CredentialProviderService#EXTRA_GET_CREDENTIAL_RESPONSE}.
 *
 * For a creates flow, invoked through {@link CredentialProviderService#onBeginCreateCredential},
 * providers must set a {@link android.credentials.CreateCredentialResponse} on the activity
 * result against the ket {@link CredentialProviderService#EXTRA_CREATE_CREDENTIAL_RESPONSE}.
 */
public final class RemoteEntry implements Parcelable {
    private final @NonNull Slice mSlice;

    private RemoteEntry(@NonNull Parcel in) {
        mSlice = in.readTypedObject(Slice.CREATOR);
    }

    @NonNull
    public static final Creator<RemoteEntry> CREATOR = new Creator<RemoteEntry>() {
        @Override
        public RemoteEntry createFromParcel(@NonNull Parcel in) {
            return new RemoteEntry(in);
        }

        @Override
        public RemoteEntry[] newArray(int size) {
            return new RemoteEntry[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mSlice, flags);
    }

    /**
     * Constructs a RemoteEntry to be displayed on the UI.
     *
     * @param slice the slice containing the metadata to be shown on the UI, must be constructed
     *              through the {@link androidx.credentials.provider} Jetpack library;
     *              If constructed manually, the {@code slice} object must
     *              contain the non-null properties of the
     *              {@link androidx.credentials.provider.RemoteEntry} class populated as slice items
     *              against specific hints as used in the class's {@code toSlice} method,
     *              since the Android System uses this library to parse the {@code slice} and
     *              extract the required attributes
     */
    public RemoteEntry(
            @NonNull Slice slice) {
        this.mSlice = slice;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSlice);
    }

    /** Returns the content to be displayed with this remote entry on the UI. */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }
}
