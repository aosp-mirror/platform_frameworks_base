/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.settings.preferences;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Data object representation of a Settings Preference definition and state.
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class SettingsPreferenceMetadata implements Parcelable {

    @NonNull
    private final String mKey;
    @NonNull
    private final String mScreenKey;
    @Nullable
    private final String mTitle;
    @Nullable
    private final String mSummary;
    @NonNull
    private final List<String> mBreadcrumbs;
    @NonNull
    private final List<String> mReadPermissions;
    @NonNull
    private final List<String> mWritePermissions;
    private final boolean mEnabled;
    private final boolean mAvailable;
    private final boolean mWritable;
    private final boolean mRestricted;
    private final int mSensitivity;
    @Nullable
    private final Intent mLaunchIntent;
    @NonNull
    private final Bundle mExtras;

    /**
     * Returns the key of Preference.
     */
    @NonNull
    public String getKey() {
        return mKey;
    }

    /**
     * Returns the screen key of Preference.
     */
    @NonNull
    public String getScreenKey() {
        return mScreenKey;
    }

    /**
     * Returns the title of Preference.
     */
    @Nullable
    public String getTitle() {
        return mTitle;
    }

    /**
     * Returns the summary of Preference.
     */
    @Nullable
    public String getSummary() {
        return mSummary;
    }

    /**
     * Returns the breadcrumbs (navigation context) of Preference.
     * <p>May be empty.
     */
    @NonNull
    public List<String> getBreadcrumbs() {
        return mBreadcrumbs;
    }

    /**
     * Returns the permissions required to read this Preference's value.
     * <p>May be empty.
     */
    @NonNull
    public List<String> getReadPermissions() {
        return mReadPermissions;
    }

    /**
     * Returns the permissions required to write this Preference's value.
     * <p>May be empty.
     */
    @NonNull
    public List<String> getWritePermissions() {
        return mWritePermissions;
    }

    /**
     * Returns whether Preference is enabled.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Returns whether Preference is available.
     */
    public boolean isAvailable() {
        return mAvailable;
    }

    /**
     * Returns whether Preference is writable.
     */
    public boolean isWritable() {
        return mWritable;
    }

    /**
     * Returns whether Preference is restricted.
     * <p>If true, this means the Preference is treated as a Restricted Preference which indicates
     * that it could be conditionally disabled/unavailable due to admin settings.
     */
    public boolean isRestricted() {
        return mRestricted;
    }

    /**
     * Returns the write-level sensitivity of Preference.
     */
    @WriteSensitivity
    public int getWriteSensitivity() {
        return mSensitivity;
    }

    /**
     * Returns the intent to launch the host app page for this Preference.
     */
    @SuppressLint("IntentBuilderName")
    @Nullable
    public Intent getLaunchIntent() {
        return mLaunchIntent;
    }

    /**
     * Returns any additional fields specific to this preference.
     * <p>Treat all data as optional. This may contain unstructured data for a given preference,
     * where the type and format of this data may only known by inspecting the source code of that
     * preference. As such, any access of this data must handle failures gracefully to account for
     * changing or missing data.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /** @hide */
    @IntDef(value = {
            NO_SENSITIVITY,
            EXPECT_POST_CONFIRMATION,
            EXPECT_PRE_CONFIRMATION,
            NO_DIRECT_ACCESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WriteSensitivity {}

    /**
     * Indicates preference is not sensitive.
     * <p>Its value is writable without explicit consent, assuming all necessary permissions are
     * granted.
     */
    public static final int NO_SENSITIVITY = 0;
    /**
     * Indicates preference is mildly sensitive.
     * <p>In addition to necessary permissions, after writing its value the user should be
     * given the option to revert back.
     */
    public static final int EXPECT_POST_CONFIRMATION = 1;
    /**
     * Indicates preference is sensitive.
     * <p>In addition to necessary permissions, the user should be prompted for confirmation prior
     * to making a change. Otherwise it is suggested to provide a deeplink to the Preference's page
     * instead, accessible via {@link #getLaunchIntent}.
     */
    public static final int EXPECT_PRE_CONFIRMATION = 2;
    /**
     * Indicates preference is highly sensitivity and carries significant user-risk.
     * <p>This Preference cannot be changed through this API and no direct deeplink is available.
     * Other Metadata is still available.
     */
    public static final int NO_DIRECT_ACCESS = 3;

    private SettingsPreferenceMetadata(@NonNull Builder builder) {
        mKey = builder.mKey;
        mScreenKey = builder.mScreenKey;
        mTitle = builder.mTitle;
        mSummary = builder.mSummary;
        mBreadcrumbs = builder.mBreadcrumbs;
        mReadPermissions = builder.mReadPermissions;
        mWritePermissions = builder.mWritePermissions;
        mEnabled = builder.mEnabled;
        mAvailable = builder.mAvailable;
        mWritable = builder.mWritable;
        mRestricted = builder.mRestricted;
        mSensitivity = builder.mSensitivity;
        mLaunchIntent = builder.mLaunchIntent;
        mExtras = Objects.requireNonNullElseGet(builder.mExtras, Bundle::new);
    }
    @SuppressLint("ParcelClassLoader")
    private SettingsPreferenceMetadata(@NonNull Parcel in) {
        mKey = Objects.requireNonNull(in.readString8());
        mScreenKey = Objects.requireNonNull(in.readString8());
        mTitle = in.readString8();
        mSummary = in.readString8();
        mBreadcrumbs = new ArrayList<>();
        in.readStringList(mBreadcrumbs);
        mReadPermissions = new ArrayList<>();
        in.readStringList(mReadPermissions);
        mWritePermissions = new ArrayList<>();
        in.readStringList(mWritePermissions);
        mEnabled = in.readBoolean();
        mAvailable = in.readBoolean();
        mWritable = in.readBoolean();
        mRestricted = in.readBoolean();
        mSensitivity = in.readInt();
        mLaunchIntent = in.readParcelable(Intent.class.getClassLoader(),
                Intent.class);
        mExtras = Objects.requireNonNullElseGet(in.readBundle(), Bundle::new);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mKey);
        dest.writeString8(mScreenKey);
        dest.writeString8(mTitle);
        dest.writeString8(mSummary);
        dest.writeStringList(mBreadcrumbs);
        dest.writeStringList(mReadPermissions);
        dest.writeStringList(mWritePermissions);
        dest.writeBoolean(mEnabled);
        dest.writeBoolean(mAvailable);
        dest.writeBoolean(mWritable);
        dest.writeBoolean(mRestricted);
        dest.writeInt(mSensitivity);
        dest.writeParcelable(mLaunchIntent, flags);
        dest.writeBundle(mExtras);
    }

    /**
     * Parcelable Creator for {@link SettingsPreferenceMetadata}.
     */
    @NonNull
    public static final Creator<SettingsPreferenceMetadata> CREATOR = new Creator<>() {
        @Override
        public SettingsPreferenceMetadata createFromParcel(@NonNull Parcel in) {
            return new SettingsPreferenceMetadata(in);
        }

        @Override
        public SettingsPreferenceMetadata[] newArray(int size) {
            return new SettingsPreferenceMetadata[size];
        }
    };

    /**
     * Builder to construct {@link SettingsPreferenceMetadata}.
     */
    public static final class Builder {
        private final String mScreenKey;
        private final String mKey;
        private String mTitle;
        private String mSummary;
        private List<String> mBreadcrumbs = Collections.emptyList();
        private List<String> mReadPermissions = Collections.emptyList();
        private List<String> mWritePermissions = Collections.emptyList();
        private boolean mEnabled = false;
        private boolean mAvailable = false;
        private boolean mWritable = false;
        private boolean mRestricted = false;
        @WriteSensitivity private int mSensitivity = NO_DIRECT_ACCESS;
        private Intent mLaunchIntent;
        private Bundle mExtras;

        /**
         * Create Builder instance.
         * @param screenKey required to be not empty
         * @param key required to be not empty
         */
        public Builder(@NonNull String screenKey, @NonNull String key) {
            if (TextUtils.isEmpty(screenKey)) {
                throw new IllegalArgumentException("screenKey cannot be empty");
            }
            if (TextUtils.isEmpty(key)) {
                throw new IllegalArgumentException("key cannot be empty");
            }
            mScreenKey = screenKey;
            mKey = key;
        }

        /**
         * Sets the preference title.
         */
        @NonNull
        public Builder setTitle(@Nullable String title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets the preference summary.
         */
        @NonNull
        public Builder setSummary(@Nullable String summary) {
            mSummary = summary;
            return this;
        }

        /**
         * Sets the preference breadcrumbs (navigation context).
         */
        @NonNull
        public Builder setBreadcrumbs(@NonNull List<String> breadcrumbs) {
            mBreadcrumbs = breadcrumbs;
            return this;
        }

        /**
         * Sets the permissions required for reading this preference.
         */
        @NonNull
        public Builder setReadPermissions(@NonNull List<String> readPermissions) {
            mReadPermissions = readPermissions;
            return this;
        }

        /**
         * Sets the permissions required for writing this preference.
         */
        @NonNull
        public Builder setWritePermissions(@NonNull List<String> writePermissions) {
            mWritePermissions = writePermissions;
            return this;
        }

        /**
         * Set whether the preference is enabled.
         */
        @NonNull
        public Builder setEnabled(boolean enabled) {
            mEnabled = enabled;
            return this;
        }

        /**
         * Sets whether the preference is available.
         */
        @NonNull
        public Builder setAvailable(boolean available) {
            mAvailable = available;
            return this;
        }

        /**
         * Sets whether the preference is writable.
         */
        @NonNull
        public Builder setWritable(boolean writable) {
            mWritable = writable;
            return this;
        }

        /**
         * Sets whether the preference is restricted.
         */
        @NonNull
        public Builder setRestricted(boolean restricted) {
            mRestricted = restricted;
            return this;
        }

        /**
         * Sets the preference write-level sensitivity.
         */
        @NonNull
        public Builder setWriteSensitivity(@WriteSensitivity int sensitivity) {
            mSensitivity = sensitivity;
            return this;
        }

        /**
         * Sets the intent to launch the host app page for this preference.
         */
        @NonNull
        public Builder setLaunchIntent(@Nullable Intent launchIntent) {
            mLaunchIntent = launchIntent;
            return this;
        }

        /**
         * Sets additional fields specific to this preference. Treat all data as optional.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Constructs an immutable {@link SettingsPreferenceMetadata} object.
         */
        @NonNull
        public SettingsPreferenceMetadata build() {
            if (mSensitivity == NO_DIRECT_ACCESS) {
                mLaunchIntent = null;
            }
            return new SettingsPreferenceMetadata(this);
        }
    }
}
