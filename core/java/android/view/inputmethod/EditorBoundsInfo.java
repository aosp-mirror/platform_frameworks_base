/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Container of rectangular position related info for the Editor.
 */
public final class EditorBoundsInfo implements Parcelable {

    /**
     * The bounding box of the of currently focused text editor in local coordinates.
     */
    private final RectF mEditorBounds;

    /**
     * The bounding box of the of currently focused text editor with additional padding around it
     * for initiating Stylus Handwriting in the current window.
     */
    private final RectF mHandwritingBounds;

    private final int mHashCode;

    private EditorBoundsInfo(@NonNull Parcel source) {
        mHashCode = source.readInt();
        mEditorBounds = source.readTypedObject(RectF.CREATOR);
        mHandwritingBounds = source.readTypedObject(RectF.CREATOR);
    }

    /**
     * Returns the bounds of the Editor in local coordinates.
     *
     * Screen coordinates can be obtained by transforming with the
     * {@link CursorAnchorInfo#getMatrix} of the containing {@code CursorAnchorInfo}.
     */
    @Nullable
    public RectF getEditorBounds() {
        return mEditorBounds;
    }

    /**
     * Returns the bounds of the area that should be considered for initiating stylus handwriting
     * in local coordinates.
     *
     * Screen coordinates can be obtained by transforming with the
     * {@link CursorAnchorInfo#getMatrix} of the containing {@code CursorAnchorInfo}.
     */
    @Nullable
    public RectF getHandwritingBounds() {
        return mHandwritingBounds;
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    @Override
    public String toString() {
        return "EditorBoundsInfo{mEditorBounds=" + mEditorBounds
                + " mHandwritingBounds=" + mHandwritingBounds
                + "}";
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        }
        EditorBoundsInfo bounds;
        if (obj instanceof  EditorBoundsInfo) {
            bounds = (EditorBoundsInfo) obj;
        } else {
            return false;
        }
        return Objects.equals(bounds.mEditorBounds, mEditorBounds)
                && Objects.equals(bounds.mHandwritingBounds, mHandwritingBounds);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mHashCode);
        dest.writeTypedObject(mEditorBounds, flags);
        dest.writeTypedObject(mHandwritingBounds, flags);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<EditorBoundsInfo> CREATOR
            = new Parcelable.Creator<EditorBoundsInfo>() {
        @Override
        public EditorBoundsInfo createFromParcel(@NonNull Parcel source) {
            return new EditorBoundsInfo(source);
        }

        @Override
        public EditorBoundsInfo[] newArray(int size) {
            return new EditorBoundsInfo[size];
        }
    };

    /**
     * Builder for {@link CursorAnchorInfo}.
     */
    public static final class Builder {
        private RectF mEditorBounds = null;
        private RectF mHandwritingBounds = null;

        /**
         * Sets the bounding box of the current editor.
         *
         * @param bounds {@link RectF} in local coordinates.
         */
        @NonNull
        public EditorBoundsInfo.Builder setEditorBounds(@Nullable RectF bounds) {
            mEditorBounds = bounds;
            return this;
        }

        /**
         * Sets the current editor's bounds with padding for handwriting.
         *
         * @param bounds {@link RectF} in local coordinates.
         */
        @NonNull
        public EditorBoundsInfo.Builder setHandwritingBounds(@Nullable RectF bounds) {
            mHandwritingBounds = bounds;
            return this;
        }

        /**
         * Returns {@link EditorBoundsInfo} using parameters in this
         *   {@link EditorBoundsInfo.Builder}.
         */
        @NonNull
        public EditorBoundsInfo build() {
            return new EditorBoundsInfo(this);
        }
    }

    private EditorBoundsInfo(final EditorBoundsInfo.Builder builder) {
        mEditorBounds = builder.mEditorBounds;
        mHandwritingBounds = builder.mHandwritingBounds;

        int hash = Objects.hashCode(mEditorBounds);
        hash *= 31;
        hash += Objects.hashCode(mHandwritingBounds);
        mHashCode = hash;
    }
}
