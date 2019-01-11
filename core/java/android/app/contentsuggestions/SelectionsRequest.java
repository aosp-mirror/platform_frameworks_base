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

package android.app.contentsuggestions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
@SystemApi
public final class SelectionsRequest implements Parcelable {
    private final int mTaskId;
    @Nullable
    private final Point mInterestPoint;
    @Nullable
    private final Bundle mExtras;

    private SelectionsRequest(int taskId, @Nullable Point interestPoint, @Nullable Bundle extras) {
        mTaskId = taskId;
        mInterestPoint = interestPoint;
        mExtras = extras;
    }

    /**
     * Return the request task id.
     */
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * Return the request point of interest.
     */
    public Point getInterestPoint() {
        return mInterestPoint;
    }

    /**
     * Return the request extras.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTaskId);
        dest.writeTypedObject(mInterestPoint, flags);
        dest.writeBundle(mExtras);
    }

    public static final Creator<SelectionsRequest> CREATOR =
            new Creator<SelectionsRequest>() {
        @Override
        public SelectionsRequest createFromParcel(Parcel source) {
            return new SelectionsRequest(
                    source.readInt(), source.readTypedObject(Point.CREATOR), source.readBundle());
        }

        @Override
        public SelectionsRequest[] newArray(int size) {
            return new SelectionsRequest[size];
        }
    };

    /**
     * A builder for selections requests events.
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private final int mTaskId;
        private Point mInterestPoint;
        private Bundle mExtras;

        public Builder(int taskId) {
            mTaskId = taskId;
        }

        /**
         * Sets the request extras.
         */
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Sets the request interest point.
         */
        public Builder setInterestPoint(@NonNull Point interestPoint) {
            mInterestPoint = interestPoint;
            return this;
        }

        /**
         * Builds a new request instance.
         */
        public SelectionsRequest build() {
            return new SelectionsRequest(mTaskId, mInterestPoint, mExtras);
        }
    }
}
