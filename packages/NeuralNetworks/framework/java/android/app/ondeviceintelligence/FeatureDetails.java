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
import android.annotation.SystemApi;
import android.os.Parcelable;
import android.os.PersistableBundle;

import androidx.annotation.IntDef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.text.MessageFormat;

/**
 * Represents a status of a requested {@link Feature}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public final class FeatureDetails implements Parcelable {
    @Status
    private final int mFeatureStatus;
    @NonNull
    private final PersistableBundle mFeatureDetailParams;

    /** Invalid or unavailable {@code AiFeature}. */
    public static final int FEATURE_STATUS_UNAVAILABLE = 0;

    /** Feature can be downloaded on request. */
    public static final int FEATURE_STATUS_DOWNLOADABLE = 1;

    /** Feature is being downloaded. */
    public static final int FEATURE_STATUS_DOWNLOADING = 2;

    /** Feature is fully downloaded and ready to use. */
    public static final int FEATURE_STATUS_AVAILABLE = 3;

    /** Underlying service is unavailable and feature status cannot be fetched. */
    public static final int FEATURE_STATUS_SERVICE_UNAVAILABLE = 4;

    /**
     * @hide
     */
    @IntDef(value = {
            FEATURE_STATUS_UNAVAILABLE,
            FEATURE_STATUS_DOWNLOADABLE,
            FEATURE_STATUS_DOWNLOADING,
            FEATURE_STATUS_AVAILABLE,
            FEATURE_STATUS_SERVICE_UNAVAILABLE
    }, open = true)
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {
    }

    public FeatureDetails(
            @Status int featureStatus,
            @NonNull PersistableBundle featureDetailParams) {
        this.mFeatureStatus = featureStatus;
        com.android.internal.util.AnnotationValidations.validate(
                Status.class, null, mFeatureStatus);
        this.mFeatureDetailParams = featureDetailParams;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFeatureDetailParams);
    }

    public FeatureDetails(
            @Status int featureStatus) {
        this.mFeatureStatus = featureStatus;
        com.android.internal.util.AnnotationValidations.validate(
                Status.class, null, mFeatureStatus);
        this.mFeatureDetailParams = new PersistableBundle();
    }


    /**
     * Returns an integer value associated with the feature status.
     */
    public @Status int getFeatureStatus() {
        return mFeatureStatus;
    }


    /**
     * Returns a persistable bundle contain any additional status related params.
     */
    public @NonNull PersistableBundle getFeatureDetailParams() {
        return mFeatureDetailParams;
    }

    @Override
    public String toString() {
        return MessageFormat.format("FeatureDetails '{' status = {0}, "
                        + "persistableBundle = {1} '}'",
                mFeatureStatus,
                mFeatureDetailParams);
    }

    @Override
    public boolean equals(@android.annotation.Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        FeatureDetails that = (FeatureDetails) o;
        return mFeatureStatus == that.mFeatureStatus
                && java.util.Objects.equals(mFeatureDetailParams, that.mFeatureDetailParams);
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + mFeatureStatus;
        _hash = 31 * _hash + java.util.Objects.hashCode(mFeatureDetailParams);
        return _hash;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mFeatureStatus);
        dest.writeTypedObject(mFeatureDetailParams, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    FeatureDetails(@NonNull android.os.Parcel in) {
        int status = in.readInt();
        PersistableBundle persistableBundle = (PersistableBundle) in.readTypedObject(
                PersistableBundle.CREATOR);

        this.mFeatureStatus = status;
        com.android.internal.util.AnnotationValidations.validate(
                Status.class, null, mFeatureStatus);
        this.mFeatureDetailParams = persistableBundle;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFeatureDetailParams);
    }


    public static final @NonNull Parcelable.Creator<FeatureDetails> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public FeatureDetails[] newArray(int size) {
                    return new FeatureDetails[size];
                }

                @Override
                public FeatureDetails createFromParcel(@NonNull android.os.Parcel in) {
                    return new FeatureDetails(in);
                }
            };

}
