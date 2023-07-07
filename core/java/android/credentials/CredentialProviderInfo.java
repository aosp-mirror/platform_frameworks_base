/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.credentials;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ServiceInfo} and meta-data about a credential provider.
 *
 * @hide
 */
@TestApi
public final class CredentialProviderInfo implements Parcelable {
    @NonNull private final ServiceInfo mServiceInfo;
    @NonNull private final Set<String> mCapabilities = new HashSet<>();
    @Nullable private final CharSequence mOverrideLabel;
    @Nullable private CharSequence mSettingsSubtitle = null;
    private final boolean mIsSystemProvider;
    private final boolean mIsEnabled;
    private final boolean mIsPrimary;

    /**
     * Constructs an information instance of the credential provider.
     *
     * @param builder the builder object.
     */
    private CredentialProviderInfo(@NonNull Builder builder) {
        mServiceInfo = builder.mServiceInfo;
        mCapabilities.addAll(builder.mCapabilities);
        mIsSystemProvider = builder.mIsSystemProvider;
        mSettingsSubtitle = builder.mSettingsSubtitle;
        mIsEnabled = builder.mIsEnabled;
        mIsPrimary = builder.mIsPrimary;
        mOverrideLabel = builder.mOverrideLabel;
    }

    /** Returns true if the service supports the given {@code credentialType}, false otherwise. */
    @NonNull
    public boolean hasCapability(@NonNull String credentialType) {
        return mCapabilities.contains(credentialType);
    }

    /** Returns the service info. */
    @NonNull
    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    /** Returns whether it is a system provider. */
    public boolean isSystemProvider() {
        return mIsSystemProvider;
    }

    /** Returns the service icon. */
    @Nullable
    public Drawable getServiceIcon(@NonNull Context context) {
        return mServiceInfo.loadIcon(context.getPackageManager());
    }

    /** Returns the service label. */
    @Nullable
    public CharSequence getLabel(@NonNull Context context) {
        if (mOverrideLabel != null) {
            return mOverrideLabel;
        }
        return mServiceInfo.loadSafeLabel(context.getPackageManager());
    }

    /** Returns a list of capabilities this provider service can support. */
    @NonNull
    public List<String> getCapabilities() {
        List<String> capabilities = new ArrayList<>();
        for (String capability : mCapabilities) {
            capabilities.add(capability);
        }
        return Collections.unmodifiableList(capabilities);
    }

    /** Returns whether the provider is enabled by the user. */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Returns whether the provider is set as primary by the user.
     *
     * @hide
     */
    public boolean isPrimary() {
        return mIsPrimary;
    }

    /** Returns the settings subtitle. */
    @Nullable
    public CharSequence getSettingsSubtitle() {
        return mSettingsSubtitle;
    }

    /** Returns the component name for the service. */
    @NonNull
    public ComponentName getComponentName() {
        return mServiceInfo.getComponentName();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mServiceInfo, flags);
        dest.writeBoolean(mIsSystemProvider);
        dest.writeBoolean(mIsEnabled);
        dest.writeBoolean(mIsPrimary);
        TextUtils.writeToParcel(mOverrideLabel, dest, flags);
        TextUtils.writeToParcel(mSettingsSubtitle, dest, flags);

        List<String> capabilities = getCapabilities();
        dest.writeStringList(capabilities);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "CredentialProviderInfo {"
                + "serviceInfo="
                + mServiceInfo
                + ", "
                + "isSystemProvider="
                + mIsSystemProvider
                + ", "
                + "isEnabled="
                + mIsEnabled
                + ", "
                + "isPrimary="
                + mIsPrimary
                + ", "
                + "overrideLabel="
                + mOverrideLabel
                + ", "
                + "settingsSubtitle="
                + mSettingsSubtitle
                + ", "
                + "capabilities="
                + String.join(",", mCapabilities)
                + "}";
    }

    private CredentialProviderInfo(@NonNull Parcel in) {
        mServiceInfo = in.readTypedObject(ServiceInfo.CREATOR);
        mIsSystemProvider = in.readBoolean();
        mIsEnabled = in.readBoolean();
        mIsPrimary = in.readBoolean();
        mOverrideLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mSettingsSubtitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);

        List<String> capabilities = new ArrayList<>();
        in.readStringList(capabilities);
        mCapabilities.addAll(capabilities);
    }

    public static final @NonNull Parcelable.Creator<CredentialProviderInfo> CREATOR =
            new Parcelable.Creator<CredentialProviderInfo>() {
                @Override
                public CredentialProviderInfo[] newArray(int size) {
                    return new CredentialProviderInfo[size];
                }

                @Override
                public CredentialProviderInfo createFromParcel(@NonNull Parcel in) {
                    return new CredentialProviderInfo(in);
                }
            };

    /** A builder for {@link CredentialProviderInfo} objects. */
    public static final class Builder {

        @NonNull private ServiceInfo mServiceInfo;
        @NonNull private Set<String> mCapabilities = new HashSet<>();
        private boolean mIsSystemProvider = false;
        @Nullable private CharSequence mSettingsSubtitle = null;
        private boolean mIsEnabled = false;
        private boolean mIsPrimary = false;
        @Nullable private CharSequence mOverrideLabel = null;

        /**
         * Creates a new builder.
         *
         * @param serviceInfo the service info of the credential provider service.
         */
        public Builder(@NonNull ServiceInfo serviceInfo) {
            mServiceInfo = serviceInfo;
        }

        /** Sets whether it is a system provider. */
        public @NonNull Builder setSystemProvider(boolean isSystemProvider) {
            mIsSystemProvider = isSystemProvider;
            return this;
        }

        /**
         * Sets the label to be used instead of getting from the system (for unit tests).
         *
         * @hide
         */
        public @NonNull Builder setOverrideLabel(@NonNull CharSequence overrideLabel) {
            mOverrideLabel = overrideLabel;
            return this;
        }

        /** Sets the settings subtitle. */
        public @NonNull Builder setSettingsSubtitle(@Nullable CharSequence settingsSubtitle) {
            mSettingsSubtitle = settingsSubtitle;
            return this;
        }

        /** Sets a list of capabilities this provider service can support. */
        public @NonNull Builder addCapabilities(@NonNull List<String> capabilities) {
            mCapabilities.addAll(capabilities);
            return this;
        }

        /**
         * Sets a list of capabilities this provider service can support.
         *
         * @hide
         */
        public @NonNull Builder addCapabilities(@NonNull Set<String> capabilities) {
            mCapabilities.addAll(capabilities);
            return this;
        }

        /** Sets whether it is enabled by the user. */
        public @NonNull Builder setEnabled(boolean isEnabled) {
            mIsEnabled = isEnabled;
            return this;
        }

        /**
         * Sets whether it is set as primary by the user.
         *
         * <p>Primary provider will be used for saving credentials by default. In most cases, there
         * should only one primary provider exist. However, if there are multiple credential
         * providers exist in the same package, all of them will be marked as primary.
         *
         * @hide
         */
        public @NonNull Builder setPrimary(boolean isPrimary) {
            mIsPrimary = isPrimary;
            return this;
        }

        /** Builds a new {@link CredentialProviderInfo} instance. */
        public @NonNull CredentialProviderInfo build() {
            return new CredentialProviderInfo(this);
        }
    }
}
