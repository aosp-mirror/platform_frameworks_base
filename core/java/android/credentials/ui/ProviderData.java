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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.Icon;
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
    private final String mProviderDisplayName;
    @NonNull
    private final Icon mIcon;
    @NonNull
    private final List<Entry> mCredentialEntries;
    @NonNull
    private final List<Entry> mActionChips;
    @Nullable
    private final Entry mAuthenticationEntry;

    private final @CurrentTimeMillisLong long mLastUsedTimeMillis;

    public ProviderData(
            @NonNull String providerId, @NonNull String providerDisplayName,
            @NonNull Icon icon, @NonNull List<Entry> credentialEntries,
            @NonNull List<Entry> actionChips, @Nullable Entry authenticationEntry,
            @CurrentTimeMillisLong long lastUsedTimeMillis) {
        mProviderId = providerId;
        mProviderDisplayName = providerDisplayName;
        mIcon = icon;
        mCredentialEntries = credentialEntries;
        mActionChips = actionChips;
        mAuthenticationEntry = authenticationEntry;
        mLastUsedTimeMillis = lastUsedTimeMillis;
    }

    /** Returns the unique provider id. */
    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    @NonNull
    public String getProviderDisplayName() {
        return mProviderDisplayName;
    }

    @NonNull
    public Icon getIcon() {
        return mIcon;
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

    /** Returns the time when the provider was last used. */
    public @CurrentTimeMillisLong long getLastUsedTimeMillis() {
        return mLastUsedTimeMillis;
    }

    protected ProviderData(@NonNull Parcel in) {
        String providerId = in.readString8();
        mProviderId = providerId;
        AnnotationValidations.validate(NonNull.class, null, mProviderId);

        String providerDisplayName = in.readString8();
        mProviderDisplayName = providerDisplayName;
        AnnotationValidations.validate(NonNull.class, null, mProviderDisplayName);

        Icon icon = in.readTypedObject(Icon.CREATOR);
        mIcon = icon;
        AnnotationValidations.validate(NonNull.class, null, mIcon);

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

        long lastUsedTimeMillis = in.readLong();
        mLastUsedTimeMillis = lastUsedTimeMillis;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mProviderId);
        dest.writeString8(mProviderDisplayName);
        dest.writeTypedObject(mIcon, flags);
        dest.writeTypedList(mCredentialEntries);
        dest.writeTypedList(mActionChips);
        dest.writeTypedObject(mAuthenticationEntry, flags);
        dest.writeLong(mLastUsedTimeMillis);
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

    /**
     * Builder for {@link ProviderData}.
     *
     * @hide
     */
    public static class Builder {
        private @NonNull String mProviderId;
        private @NonNull String mProviderDisplayName;
        private @NonNull Icon mIcon;
        private @NonNull List<Entry> mCredentialEntries = new ArrayList<>();
        private @NonNull List<Entry> mActionChips = new ArrayList<>();
        private @Nullable Entry mAuthenticationEntry = null;
        private @CurrentTimeMillisLong long mLastUsedTimeMillis = 0L;

        /** Constructor with required properties. */
        public Builder(@NonNull String providerId, @NonNull String providerDisplayName,
                @NonNull Icon icon) {
            mProviderId = providerId;
            mProviderDisplayName = providerDisplayName;
            mIcon = icon;
        }

        /** Sets the unique provider id. */
        @NonNull
        public Builder setProviderId(@NonNull String providerId) {
            mProviderId = providerId;
            return this;
        }

        /** Sets the provider display name to be displayed to the user. */
        @NonNull
        public Builder setProviderDisplayName(@NonNull String providerDisplayName) {
            mProviderDisplayName = providerDisplayName;
            return this;
        }

        /** Sets the provider icon to be displayed to the user. */
        @NonNull
        public Builder setIcon(@NonNull Icon icon) {
            mIcon = icon;
            return this;
        }

        /** Sets the list of save / get credential entries to be displayed to the user. */
        @NonNull
        public Builder setCredentialEntries(@NonNull List<Entry> credentialEntries) {
            mCredentialEntries = credentialEntries;
            return this;
        }

        /** Sets the list of action chips to be displayed to the user. */
        @NonNull
        public Builder setActionChips(@NonNull List<Entry> actionChips) {
            mActionChips = actionChips;
            return this;
        }

        /** Sets the authentication entry to be displayed to the user. */
        @NonNull
        public Builder setAuthenticationEntry(@Nullable Entry authenticationEntry) {
            mAuthenticationEntry = authenticationEntry;
            return this;
        }

        /** Sets the time when the provider was last used. */
        @NonNull
        public Builder setLastUsedTimeMillis(@CurrentTimeMillisLong long lastUsedTimeMillis) {
            mLastUsedTimeMillis = lastUsedTimeMillis;
            return this;
        }

        /** Builds a {@link ProviderData}. */
        @NonNull
        public ProviderData build() {
            return new ProviderData(mProviderId, mProviderDisplayName, mIcon, mCredentialEntries,
                mActionChips, mAuthenticationEntry, mLastUsedTimeMillis);
        }
    }
}
