/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.app.prediction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * TODO(b/111701043): Add java docs
 *
 * @hide
 */
@SystemApi
public final class AppPredictionContext implements Parcelable {

    private final int mPredictedTargetCount;
    @NonNull
    private final String mUiSurface;
    @NonNull
    private final String mPackageName;
    @Nullable
    private final Bundle mExtras;

    private AppPredictionContext(@NonNull String uiSurface, int numPredictedTargets,
            @NonNull String packageName, @Nullable Bundle extras) {
        mUiSurface = uiSurface;
        mPredictedTargetCount = numPredictedTargets;
        mPackageName = packageName;
        mExtras = extras;
    }

    private AppPredictionContext(Parcel parcel) {
        mUiSurface = parcel.readString();
        mPredictedTargetCount = parcel.readInt();
        mPackageName = parcel.readString();
        mExtras = parcel.readBundle();
    }

    public String getUiSurface() {
        return mUiSurface;
    }

    public int getPredictedTargetCount() {
        return mPredictedTargetCount;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUiSurface);
        dest.writeInt(mPredictedTargetCount);
        dest.writeString(mPackageName);
        dest.writeBundle(mExtras);
    }

    /**
     * @see Parcelable.Creator
     */
    public static final Parcelable.Creator<AppPredictionContext> CREATOR =
            new Parcelable.Creator<AppPredictionContext>() {
                public AppPredictionContext createFromParcel(Parcel parcel) {
                    return new AppPredictionContext(parcel);
                }

                public AppPredictionContext[] newArray(int size) {
                    return new AppPredictionContext[size];
                }
            };

    /**
     * A builder for app prediction contexts.
     * @hide
     */
    @SystemApi
    public static final class Builder {

        @NonNull
        private final String mPackageName;

        private int mPredictedTargetCount;
        @Nullable
        private String mUiSurface;
        @Nullable
        private Bundle mExtras;

        /**
         * @hide
         */
        public Builder(@NonNull Context context) {
            mPackageName = context.getPackageName();
        }


        /**
         * Sets the number of prediction targets as a hint.
         */
        public Builder setPredictedTargetCount(int predictedTargetCount) {
            mPredictedTargetCount = predictedTargetCount;
            return this;
        }

        /**
         * Sets the UI surface.
         */
        public Builder setUiSurface(@Nullable String uiSurface) {
            mUiSurface = uiSurface;
            return this;
        }

        /**
         * Sets the extras.
         */
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds a new context instance.
         */
        public AppPredictionContext build() {
            return new AppPredictionContext(mUiSurface, mPredictedTargetCount, mPackageName,
                    mExtras);
        }
    }
}
