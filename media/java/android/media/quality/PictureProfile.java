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
import android.media.tv.flags.Flags;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * @hide
 */

@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public class PictureProfile implements Parcelable {
    @Nullable
    private Long mId;
    @NonNull
    private final String mName;
    @Nullable
    private final String mInputId;
    @Nullable
    private final String mPackageName;
    @NonNull
    private final Bundle mParams;

    protected PictureProfile(Parcel in) {
        if (in.readByte() == 0) {
            mId = null;
        } else {
            mId = in.readLong();
        }
        mName = in.readString();
        mInputId = in.readString();
        mPackageName = in.readString();
        mParams = in.readBundle();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mId == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(mId);
        }
        dest.writeString(mName);
        dest.writeString(mInputId);
        dest.writeString(mPackageName);
        dest.writeBundle(mParams);
    }

    @Override
    public int describeContents() {
        return 0;
    }

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
            @Nullable Long id,
            @NonNull String name,
            @Nullable String inputId,
            @Nullable String packageName,
            @NonNull Bundle params) {
        this.mId = id;
        this.mName = name;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, name);
        this.mInputId = inputId;
        this.mPackageName = packageName;
        this.mParams = params;
    }

    @Nullable
    public Long getProfileId() {
        return mId;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    @Nullable
    public String getInputId() {
        return mInputId;
    }

    @Nullable
    public String getPackageName() {
        return mPackageName;
    }
    @NonNull
    public Bundle getParameters() {
        return new Bundle(mParams);
    }

    /**
     * A builder for {@link PictureProfile}
     */
    public static class Builder {
        @Nullable
        private Long mId;
        @NonNull
        private String mName;
        @Nullable
        private String mInputId;
        @Nullable
        private String mPackageName;
        @NonNull
        private Bundle mParams;

        /**
         * Creates a new Builder.
         *
         * @hide
         */
        public Builder(@NonNull String name) {
            mName = name;
            com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, name);
        }

        /**
         * Copy constructor.
         *
         * @hide
         */
        public Builder(@NonNull PictureProfile p) {
            mId = null; // ID needs to be reset
            mName = p.getName();
            mPackageName = p.getPackageName();
            mInputId = p.getInputId();
        }

        /* @hide using by MediaQualityService */

        /**
         * Sets profile ID.
         * @hide using by MediaQualityService
         */
        @NonNull
        public Builder setProfileId(@Nullable Long id) {
            mId = id;
            return this;
        }

        /**
         * Sets input ID.
         */
        @NonNull
        public Builder setInputId(@NonNull String value) {
            mInputId = value;
            return this;
        }

        /**
         * Sets package name of the profile.
         */
        @NonNull
        public Builder setPackageName(@NonNull String value) {
            mPackageName = value;
            return this;
        }

        /**
         * Sets profile parameters.
         */
        @NonNull
        public Builder setParameters(@NonNull Bundle params) {
            mParams = new Bundle(params);
            return this;
        }

        /**
         * Builds the instance.
         */
        @NonNull
        public PictureProfile build() {

            PictureProfile o = new PictureProfile(
                    mId,
                    mName,
                    mInputId,
                    mPackageName,
                    mParams);
            return o;
        }
    }
}
