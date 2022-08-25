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
import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * A sub-class of {@link HandwritingGesture} for inserting text at the defined insertion point.
 * This class holds the information required for insertion of text in
 * toolkit widgets like {@link TextView}.
 */
public final class InsertGesture extends HandwritingGesture implements Parcelable {

    private String mTextToInsert;
    private PointF mPoint;

    private InsertGesture(String text, PointF point, String fallbackText) {
        mPoint = point;
        mTextToInsert = text;
        mFallbackText = fallbackText;
    }

    private InsertGesture(final Parcel source) {
        mFallbackText = source.readString8();
        mTextToInsert = source.readString8();
        mPoint = source.readTypedObject(PointF.CREATOR);
    }

    /** Returns the text that will be inserted at {@link #getInsertionPoint()} **/
    @Nullable
    public String getTextToInsert() {
        return mTextToInsert;
    }

    /**
     * Returns the insertion point {@link PointF} (in screen coordinates) where
     * {@link #getTextToInsert()} will be inserted.
     */
    @Nullable
    public PointF getInsertionPoint() {
        return mPoint;
    }

    /**
     * Builder for {@link InsertGesture}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        private String mText;
        private PointF mPoint;
        private String mFallbackText;

        /** set the text that will be inserted at {@link #setInsertionPoint(PointF)} **/
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setTextToInsert(@NonNull String text) {
            mText = text;
            return this;
        }

        /**
         * Sets the insertion point (in screen coordinates) where {@link #setTextToInsert(String)}
         * should be inserted.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setInsertionPoint(@NonNull PointF point) {
            mPoint = point;
            return this;
        }

        /**
         * Set fallback text that will be committed at current cursor position if there is no
         * applicable text beneath the area of gesture.
         * @param fallbackText text to set
         */
        @NonNull
        public Builder setFallbackText(@Nullable String fallbackText) {
            mFallbackText = fallbackText;
            return this;
        }

        /**
         * @return {@link InsertGesture} using parameters in this {@link InsertGesture.Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are not specified.
         */
        @NonNull
        public InsertGesture build() {
            if (mPoint == null) {
                throw new IllegalArgumentException("Insertion point must be set.");
            }
            if (TextUtils.isEmpty(mText)) {
                throw new IllegalArgumentException("Text to insert must be non-empty.");
            }
            return new InsertGesture(mText, mPoint, mFallbackText);
        }
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Creator<InsertGesture> CREATOR =
            new Creator<InsertGesture>() {
        @Override
        public InsertGesture createFromParcel(Parcel source) {
            return new InsertGesture(source);
        }

        @Override
        public InsertGesture[] newArray(int size) {
            return new InsertGesture[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mPoint, mTextToInsert, mFallbackText);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InsertGesture)) return false;

        InsertGesture that = (InsertGesture) o;

        if (!Objects.equals(mFallbackText, that.mFallbackText)) return false;
        if (!Objects.equals(mTextToInsert, that.mTextToInsert)) return false;
        return Objects.equals(mPoint, that.mPoint);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mFallbackText);
        dest.writeString8(mTextToInsert);
        dest.writeTypedObject(mPoint, flags);
    }
}
