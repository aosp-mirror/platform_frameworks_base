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
import android.graphics.PointF;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A subclass of {@link HandwritingGesture} for deleting or inserting whitespace in text. If the
 * gesture is drawn over whitespace, then the whitespace will be deleted. Otherwise, a space will be
 * inserted.
 */
public final class JoinOrSplitGesture extends HandwritingGesture implements Parcelable {

    private final PointF mPoint;

    private JoinOrSplitGesture(PointF point, String fallbackText) {
        mType = GESTURE_TYPE_JOIN_OR_SPLIT;
        mPoint = point;
        mFallbackText = fallbackText;
    }

    private JoinOrSplitGesture(@NonNull Parcel source) {
        mType = GESTURE_TYPE_JOIN_OR_SPLIT;
        mPoint = source.readTypedObject(PointF.CREATOR);
        mFallbackText = source.readString8();
    }

    /**
     * Returns the gesture point in screen coordinates set with {@link Builder#setJoinOrSplitPoint}.
     */
    @NonNull
    public PointF getJoinOrSplitPoint() {
        return mPoint;
    }

    /** Builder for {@link JoinOrSplitGesture}. This class is not designed to be thread-safe. */
    public static final class Builder {
        private PointF mPoint;
        private String mFallbackText;

        /**
         * Sets the point to apply the join or split operation in screen coordinates.
         *
         * <p>If the text cursor position closest to the point is inside or on the boundary of
         * whitespace, then the whitespace will be deleted, joining the text on either side of the
         * whitespace. If there are multiple consecutive whitespace characters, then the entire
         * whitespace block will be deleted.
         *
         * <p>Otherwise, if the text cursor position closest to the point is not touching
         * whitespace, then a space will be inserted at that position.
         */
        @NonNull
        public Builder setJoinOrSplitPoint(@NonNull PointF point) {
            mPoint = point;
            return this;
        }

        /**
         * Sets fallback text that will be committed at current cursor position if there is no
         * applicable text beneath the gesture point.
         */
        @NonNull
        public Builder setFallbackText(@Nullable String fallbackText) {
            mFallbackText = fallbackText;
            return this;
        }

        /**
         * @return {@link JoinOrSplitGesture} using parameters in this {@link Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are not specified.
         */
        @NonNull
        public JoinOrSplitGesture build() {
            if (mPoint == null) {
                throw new IllegalArgumentException("Point must be set.");
            }
            return new JoinOrSplitGesture(mPoint, mFallbackText);
        }
    }

    /** Used to make this class parcelable. */
    @NonNull
    public static final Parcelable.Creator<JoinOrSplitGesture> CREATOR =
            new Parcelable.Creator<JoinOrSplitGesture>() {
                @Override
                public JoinOrSplitGesture createFromParcel(Parcel source) {
                    return new JoinOrSplitGesture(source);
                }

                @Override
                public JoinOrSplitGesture[] newArray(int size) {
                    return new JoinOrSplitGesture[size];
                }
            };

    @Override
    public int hashCode() {
        return Objects.hash(mPoint, mFallbackText);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JoinOrSplitGesture)) return false;
        JoinOrSplitGesture that = (JoinOrSplitGesture) o;
        return Objects.equals(mPoint, that.mPoint)
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
        dest.writeTypedObject(mPoint, flags);
        dest.writeString8(mFallbackText);
    }
}
