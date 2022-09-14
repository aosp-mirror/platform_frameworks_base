/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** A subclass of {@link HandwritingGesture} for removing whitespace from text. */
public final class RemoveSpaceGesture extends HandwritingGesture implements Parcelable {

    private final PointF mStartPoint;
    private final PointF mEndPoint;

    private RemoveSpaceGesture(PointF startPoint, PointF endPoint, String fallbackText) {
        mType = GESTURE_TYPE_REMOVE_SPACE;
        mStartPoint = startPoint;
        mEndPoint = endPoint;
        mFallbackText = fallbackText;
    }

    private RemoveSpaceGesture(@NonNull Parcel source) {
        mType = GESTURE_TYPE_REMOVE_SPACE;
        mStartPoint = source.readTypedObject(PointF.CREATOR);
        mEndPoint = source.readTypedObject(PointF.CREATOR);
        mFallbackText = source.readString8();
    }

    /** Returns the start point in screen coordinates set with {@link Builder#setPoints}. */
    @NonNull
    public PointF getStartPoint() {
        return mStartPoint;
    }

    /** Returns the end point in screen coordinates set with {@link Builder#setPoints}. */
    @NonNull
    public PointF getEndPoint() {
        return mEndPoint;
    }

    /** Builder for {@link RemoveSpaceGesture}. This class is not designed to be thread-safe. */
    public static final class Builder {
        private PointF mStartPoint;
        private PointF mEndPoint;
        private String mFallbackText;

        /**
         * Sets the start and end points in screen coordinates of the line to apply the remove space
         * operation. All whitespace characters touched by the line joining the points will be
         * deleted.
         *
         * <p>The operation will only be performed on a single line of text. If the start and end
         * points are on different lines of text, the line will be adjusted to cover only the first
         * line of text containing one of the points.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setPoints(@NonNull PointF startPoint, @NonNull PointF endPoint) {
            mStartPoint = startPoint;
            mEndPoint = endPoint;
            return this;
        }

        /**
         * Sets fallback text that will be committed at current cursor position if there is no
         * whitespace beneath the gesture line.
         */
        @NonNull
        public Builder setFallbackText(@Nullable String fallbackText) {
            mFallbackText = fallbackText;
            return this;
        }

        /**
         * @return {@link RemoveSpaceGesture} using parameters in this {@link Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are not specified.
         */
        @NonNull
        public RemoveSpaceGesture build() {
            if (mStartPoint == null || mEndPoint == null) {
                throw new IllegalArgumentException("Start and end points must be set.");
            }
            return new RemoveSpaceGesture(mStartPoint, mEndPoint, mFallbackText);
        }
    }

    /** Used to make this class parcelable. */
    @NonNull
    public static final Parcelable.Creator<RemoveSpaceGesture> CREATOR =
            new Parcelable.Creator<RemoveSpaceGesture>() {
                @Override
                public RemoveSpaceGesture createFromParcel(Parcel source) {
                    return new RemoveSpaceGesture(source);
                }

                @Override
                public RemoveSpaceGesture[] newArray(int size) {
                    return new RemoveSpaceGesture[size];
                }
            };

    @Override
    public int hashCode() {
        return Objects.hash(mStartPoint, mEndPoint, mFallbackText);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RemoveSpaceGesture)) return false;
        RemoveSpaceGesture that = (RemoveSpaceGesture) o;
        return Objects.equals(mStartPoint, that.mStartPoint)
                && Objects.equals(mEndPoint, that.mEndPoint)
                && Objects.equals(mFallbackText, that.mFallbackText);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest {@link Parcel} to be written
     * @param flags flags used for parceling
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mStartPoint, flags);
        dest.writeTypedObject(mEndPoint, flags);
        dest.writeString8(mFallbackText);
    }
}
