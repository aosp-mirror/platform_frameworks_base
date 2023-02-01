/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-provider metadata and entries for the create-credential flow.
 *
 * @hide
 */
@TestApi
public final class CreateCredentialProviderData extends ProviderData implements Parcelable {
    @NonNull
    private final List<Entry> mSaveEntries;
    @Nullable
    private final Entry mRemoteEntry;

    public CreateCredentialProviderData(
            @NonNull String providerFlattenedComponentName, @NonNull List<Entry> saveEntries,
            @Nullable Entry remoteEntry) {
        super(providerFlattenedComponentName);
        mSaveEntries = saveEntries;
        mRemoteEntry = remoteEntry;
    }

    @NonNull
    public List<Entry> getSaveEntries() {
        return mSaveEntries;
    }

    @Nullable
    public Entry getRemoteEntry() {
        return mRemoteEntry;
    }

    private CreateCredentialProviderData(@NonNull Parcel in) {
        super(in);

        List<Entry> credentialEntries = new ArrayList<>();
        in.readTypedList(credentialEntries, Entry.CREATOR);
        mSaveEntries = credentialEntries;
        AnnotationValidations.validate(NonNull.class, null, mSaveEntries);

        Entry remoteEntry = in.readTypedObject(Entry.CREATOR);
        mRemoteEntry = remoteEntry;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedList(mSaveEntries);
        dest.writeTypedObject(mRemoteEntry, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<CreateCredentialProviderData> CREATOR =
            new Creator<>() {
                @Override
                public CreateCredentialProviderData createFromParcel(@NonNull Parcel in) {
                    return new CreateCredentialProviderData(in);
                }

                @Override
                public CreateCredentialProviderData[] newArray(int size) {
                    return new CreateCredentialProviderData[size];
                }
            };

    /**
     * Builder for {@link CreateCredentialProviderData}.
     *
     * @hide
     */
    @TestApi
    public static final class Builder {
        @NonNull private String mProviderFlattenedComponentName;
        @NonNull private List<Entry> mSaveEntries = new ArrayList<>();
        @Nullable private Entry mRemoteEntry = null;

        /** Constructor with required properties. */
        public Builder(@NonNull String providerFlattenedComponentName) {
            mProviderFlattenedComponentName = providerFlattenedComponentName;
        }

        /** Sets the list of save credential entries to be displayed to the user. */
        @NonNull
        public Builder setSaveEntries(@NonNull List<Entry> credentialEntries) {
            mSaveEntries = credentialEntries;
            return this;
        }

        /** Sets the remote entry of the provider. */
        @NonNull
        public Builder setRemoteEntry(@Nullable Entry remoteEntry) {
            mRemoteEntry = remoteEntry;
            return this;
        }

        /** Builds a {@link CreateCredentialProviderData}. */
        @NonNull
        public CreateCredentialProviderData build() {
            return new CreateCredentialProviderData(mProviderFlattenedComponentName,
                    mSaveEntries, mRemoteEntry);
        }
    }
}
