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
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.TextView;

import java.util.Objects;

/**
 * A sub-class of {@link HandwritingGesture} for selecting an area of text using single rectangle.
 * This class holds the information required for selection of text in
 * toolkit widgets like {@link TextView}.
 * <p>Note: This selects all text <em>within</em> the given area. To select a range <em>between</em>
 * two areas, use {@link SelectRangeGesture}.</p>
 */
public final class SelectGesture extends HandwritingGesture implements Parcelable {

    private @Granularity int mGranularity;
    private RectF mArea;

    private SelectGesture(int granularity, RectF area, String fallbackText) {
        mType = GESTURE_TYPE_SELECT;
        mArea = area;
        mGranularity = granularity;
        mFallbackText = fallbackText;
    }

    private SelectGesture(@NonNull Parcel source) {
        mType = GESTURE_TYPE_SELECT;
        mFallbackText = source.readString8();
        mGranularity = source.readInt();
        mArea = source.readTypedObject(RectF.CREATOR);
    }

    /**
     * Returns Granular level on which text should be operated.
     * @see #GRANULARITY_CHARACTER
     * @see #GRANULARITY_WORD
     */
    @Granularity
    public int getGranularity() {
        return mGranularity;
    }

    /**
     * Returns the Selection area {@link RectF} in screen coordinates.
     *
     * Getter for selection area set with {@link Builder#setSelectionArea(RectF)}.
     */
    @NonNull
    public RectF getSelectionArea() {
        return mArea;
    }


    /**
     * Builder for {@link SelectGesture}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        private int mGranularity;
        private RectF mArea;
        private String mFallbackText;

        /**
         * Define text selection granularity. Intersecting words/characters will be
         * included in the operation.
         * @param granularity {@link HandwritingGesture#GRANULARITY_WORD} or
         * {@link HandwritingGesture#GRANULARITY_CHARACTER}.
         * @return {@link Builder}.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setGranularity(@Granularity int granularity) {
            mGranularity = granularity;
            return this;
        }

        /**
         * Set rectangular single/multiline text selection area intersecting with text.
         *
         * The resulting selection would be performed for all text intersecting rectangle. The
         * selection includes the first word/character in the  rectangle, and the last
         * word/character in the rectangle, and includes  everything in between even if it's not
         * in the rectangle.
         *
         * Intersection is determined using
         * {@link #setGranularity(int)}. e.g. {@link HandwritingGesture#GRANULARITY_WORD} includes
         * all the words with their width/height center included in the selection rectangle.
         * @param area {@link RectF} (in screen coordinates) for which text will be selection.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setSelectionArea(@NonNull RectF area) {
            mArea = area;
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
         * @return {@link SelectGesture} using parameters in this {@link Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are not specified.
         */
        @NonNull
        public SelectGesture build() {
            if (mArea == null || mArea.isEmpty()) {
                throw new IllegalArgumentException("Selection area must be set.");
            }
            if (mGranularity <= GRANULARITY_UNDEFINED) {
                throw new IllegalArgumentException("Selection granularity must be set.");
            }
            return new SelectGesture(mGranularity, mArea, mFallbackText);
        }
    }

    /**
     * Used to make this class parcelable.
     */
    @NonNull
    public static final Parcelable.Creator<SelectGesture> CREATOR =
            new Parcelable.Creator<SelectGesture>() {
        @Override
        public SelectGesture createFromParcel(Parcel source) {
            return new SelectGesture(source);
        }

        @Override
        public SelectGesture[] newArray(int size) {
            return new SelectGesture[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mGranularity, mArea, mFallbackText);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SelectGesture)) return false;

        SelectGesture that = (SelectGesture) o;

        if (mGranularity != that.mGranularity) return false;
        if (!Objects.equals(mFallbackText, that.mFallbackText)) return false;
        return Objects.equals(mArea, that.mArea);
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
        dest.writeInt(mGranularity);
        dest.writeTypedObject(mArea, flags);
    }
}
