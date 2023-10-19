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
 * An entry to be shown on the UI. This entry represents where the credential to be created will
 * be stored. Examples include user's account, family group etc.
 *
 * <p>If user selects this entry, the corresponding {@link PendingIntent} set on the
 * {@code slice} as a {@link androidx.slice.core.SliceAction} will get invoked.
 * Once the resulting activity fulfills the required user engagement,
 * the {@link android.app.Activity} result should be set to {@link android.app.Activity#RESULT_OK},
 * and the {@link CredentialProviderService#EXTRA_CREATE_CREDENTIAL_RESPONSE} must be set with a
 * {@link android.credentials.CreateCredentialResponse} object.
 */
public final class CreateEntry implements Parcelable {
    private final @NonNull Slice mSlice;

    private CreateEntry(@NonNull Parcel in) {
        mSlice = in.readTypedObject(Slice.CREATOR);
    }

    @NonNull
    public static final Creator<CreateEntry> CREATOR = new Creator<CreateEntry>() {
        @Override
        public CreateEntry createFromParcel(@NonNull Parcel in) {
            return new CreateEntry(in);
        }

        @Override
        public CreateEntry[] newArray(int size) {
            return new CreateEntry[size];
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
     * Constructs a CreateEntry to be displayed on the UI.
     *
     * @param slice the slice containing the metadata to be shown on the UI, must be constructed
     *              through the {@link androidx.credentials.provider} Jetpack library;
     *              If constructed manually, the {@code slice} object must
     *              contain the non-null properties of the
     *              {@link androidx.credentials.provider.CreateEntry} class populated as slice items
     *              against specific hints as used in the class's {@code toSlice} method,
     *              since the Android System uses this library to parse the {@code slice} and
     *              extract the required attributes
     */
    public CreateEntry(
            @NonNull Slice slice) {
        this.mSlice = slice;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSlice);
    }

    /** Returns the content to be displayed with this create entry on the UI. */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }
}
