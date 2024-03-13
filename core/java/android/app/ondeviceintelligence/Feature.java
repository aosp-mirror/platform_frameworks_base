/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * Represents a typical feature associated with on-device intelligence.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public final class Feature implements Parcelable {
    private final int mId;
    @Nullable
    private final String mName;
    @Nullable
    private final String mModelName;
    private final int mType;
    private final int mVariant;
    @NonNull
    private final PersistableBundle mFeatureParams;

    /* package-private */ Feature(
            int id,
            @Nullable String name,
            @Nullable String modelName,
            int type,
            int variant,
            @NonNull PersistableBundle featureParams) {
        this.mId = id;
        this.mName = name;
        this.mModelName = modelName;
        this.mType = type;
        this.mVariant = variant;
        this.mFeatureParams = featureParams;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFeatureParams);
    }

    /** Returns the unique and immutable identifier of this feature. */
    public int getId() {
        return mId;
    }

    /** Returns human-readable name of this feature. */
    public @Nullable String getName() {
        return mName;
    }

    /** Returns base model name of this feature. */
    public @Nullable String getModelName() {
        return mModelName;
    }

    /** Returns type identifier of this feature. */
    public int getType() {
        return mType;
    }

    /** Returns variant kind for this feature. */
    public int getVariant() {
        return mVariant;
    }

    public @NonNull PersistableBundle getFeatureParams() {
        return mFeatureParams;
    }

    @Override
    public String toString() {
        return "Feature { " +
                "id = " + mId + ", " +
                "name = " + mName + ", " +
                "modelName = " + mModelName + ", " +
                "type = " + mType + ", " +
                "variant = " + mVariant + ", " +
                "featureParams = " + mFeatureParams +
                " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        Feature that = (Feature) o;
        //noinspection PointlessBooleanExpression
        return true
                && mId == that.mId
                && java.util.Objects.equals(mName, that.mName)
                && java.util.Objects.equals(mModelName, that.mModelName)
                && mType == that.mType
                && mVariant == that.mVariant
                && java.util.Objects.equals(mFeatureParams, that.mFeatureParams);
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + mId;
        _hash = 31 * _hash + java.util.Objects.hashCode(mName);
        _hash = 31 * _hash + java.util.Objects.hashCode(mModelName);
        _hash = 31 * _hash + mType;
        _hash = 31 * _hash + mVariant;
        _hash = 31 * _hash + java.util.Objects.hashCode(mFeatureParams);
        return _hash;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte flg = 0;
        if (mName != null) flg |= 0x2;
        if (mModelName != null) flg |= 0x4;
        dest.writeByte(flg);
        dest.writeInt(mId);
        if (mName != null) dest.writeString8(mName);
        if (mModelName != null) dest.writeString8(mModelName);
        dest.writeInt(mType);
        dest.writeInt(mVariant);
        dest.writeTypedObject(mFeatureParams, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ Feature(@NonNull Parcel in) {
        byte flg = in.readByte();
        int id = in.readInt();
        String name = (flg & 0x2) == 0 ? null : in.readString();
        String modelName = (flg & 0x4) == 0 ? null : in.readString();
        int type = in.readInt();
        int variant = in.readInt();
        PersistableBundle featureParams = (PersistableBundle) in.readTypedObject(
                PersistableBundle.CREATOR);

        this.mId = id;
        this.mName = name;
        this.mModelName = modelName;
        this.mType = type;
        this.mVariant = variant;
        this.mFeatureParams = featureParams;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFeatureParams);
    }

    public static final @NonNull Parcelable.Creator<Feature> CREATOR
            = new Parcelable.Creator<Feature>() {
        @Override
        public Feature[] newArray(int size) {
            return new Feature[size];
        }

        @Override
        public Feature createFromParcel(@NonNull Parcel in) {
            return new Feature(in);
        }
    };

    /**
     * A builder for {@link Feature}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private int mId;
        private @Nullable String mName;
        private @Nullable String mModelName;
        private int mType;
        private int mVariant;
        private @NonNull PersistableBundle mFeatureParams;

        private long mBuilderFieldsSet = 0L;

        /**
         * Provides a builder instance to create a feature for given id.
         * @param id the unique identifier for the feature.
         */
        public Builder(int id) {
            mId = id;
            mFeatureParams = new PersistableBundle();
        }

        public @NonNull Builder setName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mName = value;
            return this;
        }

        public @NonNull Builder setModelName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mModelName = value;
            return this;
        }

        public @NonNull Builder setType(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mType = value;
            return this;
        }

        public @NonNull Builder setVariant(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mVariant = value;
            return this;
        }

        public @NonNull Builder setFeatureParams(@NonNull PersistableBundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mFeatureParams = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull Feature build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40; // Mark builder used

            Feature o = new Feature(
                    mId,
                    mName,
                    mModelName,
                    mType,
                    mVariant,
                    mFeatureParams);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x40) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
