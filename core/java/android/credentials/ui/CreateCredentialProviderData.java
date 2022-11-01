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
public class CreateCredentialProviderData extends ProviderData implements Parcelable {
    @NonNull
    private final List<Entry> mSaveEntries;
    @NonNull
    private final List<Entry> mActionChips;
    private final boolean mIsDefaultProvider;
    @Nullable
    private final Entry mRemoteEntry;

    public CreateCredentialProviderData(
            @NonNull String providerFlattenedComponentName, @NonNull List<Entry> saveEntries,
            @NonNull List<Entry> actionChips, boolean isDefaultProvider,
            @Nullable Entry remoteEntry) {
        super(providerFlattenedComponentName);
        mSaveEntries = saveEntries;
        mActionChips = actionChips;
        mIsDefaultProvider = isDefaultProvider;
        mRemoteEntry = remoteEntry;
    }

    @NonNull
    public List<Entry> getSaveEntries() {
        return mSaveEntries;
    }

    @NonNull
    public List<Entry> getActionChips() {
        return mActionChips;
    }

    public boolean isDefaultProvider() {
        return mIsDefaultProvider;
    }

    @Nullable
    public Entry getRemoteEntry() {
        return mRemoteEntry;
    }

    protected CreateCredentialProviderData(@NonNull Parcel in) {
        super(in);

        List<Entry> credentialEntries = new ArrayList<>();
        in.readTypedList(credentialEntries, Entry.CREATOR);
        mSaveEntries = credentialEntries;
        AnnotationValidations.validate(NonNull.class, null, mSaveEntries);

        List<Entry> actionChips  = new ArrayList<>();
        in.readTypedList(actionChips, Entry.CREATOR);
        mActionChips = actionChips;
        AnnotationValidations.validate(NonNull.class, null, mActionChips);

        mIsDefaultProvider = in.readBoolean();

        Entry remoteEntry = in.readTypedObject(Entry.CREATOR);
        mRemoteEntry = remoteEntry;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedList(mSaveEntries);
        dest.writeTypedList(mActionChips);
        dest.writeBoolean(isDefaultProvider());
        dest.writeTypedObject(mRemoteEntry, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<CreateCredentialProviderData> CREATOR =
            new Creator<CreateCredentialProviderData>() {
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
    public static class Builder {
        private @NonNull String mProviderFlattenedComponentName;
        private @NonNull List<Entry> mSaveEntries = new ArrayList<>();
        private @NonNull List<Entry> mActionChips = new ArrayList<>();
        private boolean mIsDefaultProvider = false;
        private @Nullable Entry mRemoteEntry = null;

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

        /** Sets the list of action chips to be displayed to the user. */
        @NonNull
        public Builder setActionChips(@NonNull List<Entry> actionChips) {
            mActionChips = actionChips;
            return this;
        }

        /** Sets whether this provider is the user's selected default provider. */
        @NonNull
        public Builder setIsDefaultProvider(boolean isDefaultProvider) {
            mIsDefaultProvider = isDefaultProvider;
            return this;
        }

        /** Builds a {@link CreateCredentialProviderData}. */
        @NonNull
        public CreateCredentialProviderData build() {
            return new CreateCredentialProviderData(mProviderFlattenedComponentName,
                    mSaveEntries, mActionChips, mIsDefaultProvider, mRemoteEntry);
        }
    }
}
