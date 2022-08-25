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
 * A sub-class of {@link HandwritingGesture} for deleting an area of text.
 * This class holds the information required for deletion of text in
 * toolkit widgets like {@link TextView}.
 */
public final class DeleteGesture extends HandwritingGesture implements Parcelable {

    private @Granularity int mGranularity;
    private RectF mArea;

    private DeleteGesture(@Granularity int granularity, RectF area, String fallbackText) {
        mArea = area;
        mGranularity = granularity;
        mFallbackText = fallbackText;
    }

    private DeleteGesture(@NonNull final Parcel source) {
        mFallbackText = source.readString8();
        mGranularity = source.readInt();
        mArea = source.readTypedObject(RectF.CREATOR);
    }

    /**
     * Returns Granular level on which text should be operated.
     * @see HandwritingGesture#GRANULARITY_CHARACTER
     * @see HandwritingGesture#GRANULARITY_WORD
     */
    @Granularity
    public int getGranularity() {
        return mGranularity;
    }

    /**
     * Returns the deletion area {@link RectF} in screen coordinates.
     *
     * Getter for deletion area set with {@link DeleteGesture.Builder#setDeletionArea(RectF)}.
     * {@code null} if area was not set.
     */
    @NonNull
    public RectF getDeletionArea() {
        return mArea;
    }

    /**
     * Builder for {@link DeleteGesture}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        private int mGranularity;
        private RectF mArea;
        private String mFallbackText;

        /**
         * Set text deletion granularity. Intersecting words/characters will be
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
         * Set rectangular single/multiline text deletion area intersecting with text.
         *
         * The resulting deletion would be performed for all text intersecting rectangle. The
         * deletion includes the first word/character in the rectangle, and the last
         * word/character in the rectangle, and includes  everything in between even if it's not
         * in the rectangle.
         *
         * Intersection is determined using
         * {@link #setGranularity(int)}. e.g. {@link HandwritingGesture#GRANULARITY_WORD} includes
         * all the words with their width/height center included in the deletion rectangle.
         * @param area {@link RectF} (in screen coordinates) for which text will be deleted.
         * @see HandwritingGesture#GRANULARITY_WORD
         * @see HandwritingGesture#GRANULARITY_CHARACTER
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setDeletionArea(@NonNull RectF area) {
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
         * @return {@link DeleteGesture} using parameters in this {@link DeleteGesture.Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are not specified.
         */
        @NonNull
        public DeleteGesture build() {
            if (mArea == null || mArea.isEmpty()) {
                throw new IllegalArgumentException("Deletion area must be set.");
            }
            if (mGranularity <= GRANULARITY_UNDEFINED) {
                throw new IllegalArgumentException("Deletion granularity must be set.");
            }
            return new DeleteGesture(mGranularity, mArea, mFallbackText);
        }
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Creator<DeleteGesture> CREATOR =
            new Creator<DeleteGesture>() {
        @Override
        public DeleteGesture createFromParcel(Parcel source) {
            return new DeleteGesture(source);
        }

        @Override
        public DeleteGesture[] newArray(int size) {
            return new DeleteGesture[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mArea, mGranularity, mFallbackText);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeleteGesture)) return false;

        DeleteGesture that = (DeleteGesture) o;

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
