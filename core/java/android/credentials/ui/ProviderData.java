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
 * Holds metadata and credential entries for a single provider.
 *
 * @hide
 */
public class ProviderData implements Parcelable {

    /**
     * The intent extra key for the list of {@code ProviderData} when launching the UX
     * activities.
     */
    public static final String EXTRA_PROVIDER_DATA_LIST =
            "android.credentials.ui.extra.PROVIDER_DATA_LIST";

    @NonNull
    private final String mProviderId;
    @NonNull
    private final List<Entry> mCredentialEntries;
    @NonNull
    private final List<Entry> mActionChips;
    @Nullable
    private final Entry mAuthenticationEntry;

    public ProviderData(
            @NonNull String providerId,
            @NonNull List<Entry> credentialEntries,
            @NonNull List<Entry> actionChips,
            @Nullable Entry authenticationEntry) {
        mProviderId = providerId;
        mCredentialEntries = credentialEntries;
        mActionChips = actionChips;
        mAuthenticationEntry = authenticationEntry;
    }

    /** Returns the provider package name. */
    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    @NonNull
    public List<Entry> getCredentialEntries() {
        return mCredentialEntries;
    }

    @NonNull
    public List<Entry> getActionChips() {
        return mActionChips;
    }

    @Nullable
    public Entry getAuthenticationEntry() {
        return mAuthenticationEntry;
    }

    protected ProviderData(@NonNull Parcel in) {
        String providerId = in.readString8();
        mProviderId = providerId;
        AnnotationValidations.validate(NonNull.class, null, mProviderId);

        List<Entry> credentialEntries = new ArrayList<>();
        in.readTypedList(credentialEntries, Entry.CREATOR);
        mCredentialEntries = credentialEntries;
        AnnotationValidations.validate(NonNull.class, null, mCredentialEntries);

        List<Entry> actionChips  = new ArrayList<>();
        in.readTypedList(actionChips, Entry.CREATOR);
        mActionChips = actionChips;
        AnnotationValidations.validate(NonNull.class, null, mActionChips);

        Entry authenticationEntry = in.readTypedObject(Entry.CREATOR);
        mAuthenticationEntry = authenticationEntry;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mProviderId);
        dest.writeTypedList(mCredentialEntries);
        dest.writeTypedList(mActionChips);
        dest.writeTypedObject(mAuthenticationEntry, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<ProviderData> CREATOR = new Creator<ProviderData>() {
        @Override
        public ProviderData createFromParcel(@NonNull Parcel in) {
            return new ProviderData(in);
        }

        @Override
        public ProviderData[] newArray(int size) {
            return new ProviderData[size];
        }
    };
}
