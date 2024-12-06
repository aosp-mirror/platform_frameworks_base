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

package android.media.quality;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.media.tv.TvInputInfo;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Profile for picture quality.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public final class PictureProfile implements Parcelable {
    @Nullable
    private String mId;
    private final int mType;
    @NonNull
    private final String mName;
    @Nullable
    private final String mInputId;
    @NonNull
    private final String mPackageName;
    @NonNull
    private final PersistableBundle mParams;
    private final PictureProfileHandle mHandle;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "TYPE_", value = {
            TYPE_SYSTEM,
            TYPE_APPLICATION})
    public @interface ProfileType {}

    /**
     * System profile type.
     *
     * <p>A profile of system type is managed by the system, and readable to the package returned by
     * {@link #getPackageName()}.
     */
    public static final int TYPE_SYSTEM = 1;
    /**
     * Application profile type.
     *
     * <p>A profile of application type is managed by the package returned by
     * {@link #getPackageName()}.
     */
    public static final int TYPE_APPLICATION = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "ERROR_", value = {
            ERROR_UNKNOWN,
            ERROR_NO_PERMISSION,
            ERROR_DUPLICATE,
            ERROR_INVALID_ARGUMENT,
            ERROR_NOT_ALLOWLISTED
    })
    public @interface ErrorCode {}

    /**
     * Error code for unknown errors.
     */
    public static final int ERROR_UNKNOWN = 0;

    /**
     * Error code for missing necessary permission to handle the profiles.
     */
    public static final int ERROR_NO_PERMISSION = 1;

    /**
     * Error code for creating a profile with existing profile type and name.
     *
     * @see #getProfileType()
     * @see #getName()
     */
    public static final int ERROR_DUPLICATE = 2;

    /**
     * Error code for invalid argument.
     */
    public static final int ERROR_INVALID_ARGUMENT = 3;

    /**
     * Error code for the case when an operation requires an allowlist but the caller is not in the
     * list.
     *
     * @see MediaQualityManager#getPictureProfileAllowList()
     */
    public static final int ERROR_NOT_ALLOWLISTED = 4;


    private PictureProfile(@NonNull Parcel in) {
        mId = in.readString();
        mType = in.readInt();
        mName = in.readString();
        mInputId = in.readString();
        mPackageName = in.readString();
        mParams = in.readPersistableBundle();
        mHandle = in.readParcelable(PictureProfileHandle.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mType);
        dest.writeString(mName);
        dest.writeString(mInputId);
        dest.writeString(mPackageName);
        dest.writePersistableBundle(mParams);
        dest.writeParcelable(mHandle, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<PictureProfile> CREATOR = new Creator<PictureProfile>() {
        @Override
        public PictureProfile createFromParcel(Parcel in) {
            return new PictureProfile(in);
        }

        @Override
        public PictureProfile[] newArray(int size) {
            return new PictureProfile[size];
        }
    };


    /**
     * Creates a new PictureProfile.
     *
     * @hide
     */
    public PictureProfile(
            @Nullable String id,
            int type,
            @NonNull String name,
            @Nullable String inputId,
            @NonNull String packageName,
            @NonNull PersistableBundle params,
            @NonNull PictureProfileHandle handle) {
        this.mId = id;
        this.mType = type;
        this.mName = name;
        this.mInputId = inputId;
        this.mPackageName = packageName;
        this.mParams = params;
        this.mHandle = handle;
    }

    /**
     * Gets profile ID.
     *
     * <p>A profile ID is a globally unique ID generated and assigned by the system. For profile
     * objects retrieved from system (e.g {@link MediaQualityManager#getAvailablePictureProfiles()})
     * this profile ID is non-null; For profiles built locally with {@link Builder}, it's
     * {@code null}.
     *
     * @return the unique profile ID; {@code null} if the profile is built locally with
     * {@link Builder}.
     */
    @Nullable
    public String getProfileId() {
        return mId;
    }

    /**
     * Only used by system to assign the ID.
     * @hide
     */
    public void setProfileId(String id) {
        mId = id;
    }

    /**
     * Gets profile type.
     */
    @ProfileType
    public int getProfileType() {
        return mType;
    }

    /**
     * Gets the profile name.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Gets the input ID if the profile is for a TV input.
     *
     * @return the corresponding TV input ID; {@code null} if the profile is not associated with a
     * TV input.
     *
     * @see TvInputInfo#getId()
     */
    @Nullable
    public String getInputId() {
        return mInputId;
    }

    /**
     * Gets the package name of this profile.
     *
     * <p>The package name defines the user of a profile. Only this specific package and system app
     * can access to this profile.
     *
     * @return the package name; {@code null} if the profile is built locally using
     * {@link Builder} and the package is not set.
     */
    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Gets the parameters of this profile.
     *
     * <p>The keys of commonly used parameters can be found in
     * {@link MediaQualityContract.PictureQuality}.
     */
    @NonNull
    public PersistableBundle getParameters() {
        return new PersistableBundle(mParams);
    }

    /**
     * Gets profile handle
     * @hide
     */
    @NonNull
    public PictureProfileHandle getHandle() {
        return mHandle;
    }

    /**
     * A builder for {@link PictureProfile}.
     */
    public static final class Builder {
        @Nullable
        private String mId;
        private int mType = TYPE_APPLICATION;
        @NonNull
        private String mName;
        @Nullable
        private String mInputId;
        @NonNull
        private String mPackageName;
        @NonNull
        private PersistableBundle mParams;
        private PictureProfileHandle mHandle;

        /**
         * Creates a new Builder.
         */
        public Builder(@NonNull String name) {
            mName = name;
        }

        /**
         * Copy constructor of builder.
         */
        public Builder(@NonNull PictureProfile p) {
            mId = null; // ID needs to be reset
            mType = p.getProfileType();
            mName = p.getName();
            mPackageName = p.getPackageName();
            mInputId = p.getInputId();
            mParams = p.getParameters();
            mHandle = p.getHandle();
        }

        /**
         * Only used by system to assign the ID.
         * @hide
         */
        @NonNull
        public Builder setProfileId(@Nullable String id) {
            mId = id;
            return this;
        }

        /**
         * Sets profile type.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
        @NonNull
        public Builder setProfileType(@ProfileType int value) {
            mType = value;
            return this;
        }

        /**
         * Sets input ID.
         *
         * @see PictureProfile#getInputId()
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
        @NonNull
        public Builder setInputId(@NonNull String value) {
            mInputId = value;
            return this;
        }

        /**
         * Sets package name of the profile.
         *
         * @see PictureProfile#getPackageName()
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE)
        @NonNull
        public Builder setPackageName(@NonNull String value) {
            mPackageName = value;
            return this;
        }

        /**
         * Sets profile parameters.
         *
         * @see PictureProfile#getParameters()
         */
        @NonNull
        public Builder setParameters(@NonNull PersistableBundle params) {
            mParams = new PersistableBundle(params);
            return this;
        }

        /**
         * Sets profile handle.
         * @hide
         */
        @NonNull
        public Builder setHandle(@NonNull PictureProfileHandle handle) {
            mHandle = handle;
            return this;
        }

        /**
         * Builds the instance.
         */
        @NonNull
        public PictureProfile build() {

            PictureProfile o = new PictureProfile(
                    mId,
                    mType,
                    mName,
                    mInputId,
                    mPackageName,
                    mParams,
                    mHandle);
            return o;
        }
    }
}
