/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Layout;
import android.text.SpannedString;
import android.text.TextUtils;
import android.view.inputmethod.SparseRectFArray.SparseRectFArrayBuilder;

import java.util.Arrays;
import java.util.Objects;

/**
 * Positional information about the text insertion point and characters in the composition string.
 *
 * <p>This class encapsulates locations of the text insertion point and the composition string in
 * the screen coordinates so that IMEs can render their UI components near where the text is
 * actually inserted.</p>
 */
public final class CursorAnchorInfo implements Parcelable {
    /**
     * The pre-computed hash code.
     */
    private final int mHashCode;

    /**
     * The index of the first character of the selected text (inclusive). {@code -1} when there is
     * no text selection.
     */
    private final int mSelectionStart;
    /**
     * The index of the first character of the selected text (exclusive). {@code -1} when there is
     * no text selection.
     */
    private final int mSelectionEnd;

    /**
     * The index of the first character of the composing text (inclusive). {@code -1} when there is
     * no composing text.
     */
    private final int mComposingTextStart;
    /**
     * The text, tracked as a composing region.
     */
    private final CharSequence mComposingText;

    /**
     * Flags of the insertion marker. See {@link #FLAG_HAS_VISIBLE_REGION} for example.
     */
    private final int mInsertionMarkerFlags;
    /**
     * Horizontal position of the insertion marker, in the local coordinates that will be
     * transformed with the transformation matrix when rendered on the screen. This should be
     * calculated or compatible with {@link Layout#getPrimaryHorizontal(int)}. This can be
     * {@code java.lang.Float.NaN} when no value is specified.
     */
    private final float mInsertionMarkerHorizontal;
    /**
     * Vertical position of the insertion marker, in the local coordinates that will be
     * transformed with the transformation matrix when rendered on the screen. This should be
     * calculated or compatible with {@link Layout#getLineTop(int)}. This can be
     * {@code java.lang.Float.NaN} when no value is specified.
     */
    private final float mInsertionMarkerTop;
    /**
     * Vertical position of the insertion marker, in the local coordinates that will be
     * transformed with the transformation matrix when rendered on the screen. This should be
     * calculated or compatible with {@link Layout#getLineBaseline(int)}. This can be
     * {@code java.lang.Float.NaN} when no value is specified.
     */
    private final float mInsertionMarkerBaseline;
    /**
     * Vertical position of the insertion marker, in the local coordinates that will be
     * transformed with the transformation matrix when rendered on the screen. This should be
     * calculated or compatible with {@link Layout#getLineBottom(int)}. This can be
     * {@code java.lang.Float.NaN} when no value is specified.
     */
    private final float mInsertionMarkerBottom;

    /**
     * Container of rectangular position of characters, keyed with character index in a unit of
     * Java chars, in the local coordinates that will be transformed with the transformation matrix
     * when rendered on the screen.
     */
    private final SparseRectFArray mCharacterBoundsArray;

    /**
     * Transformation matrix that is applied to any positional information of this class to
     * transform local coordinates into screen coordinates.
     */
    @NonNull
    private final float[] mMatrixValues;

    /**
     * Flag for {@link #getInsertionMarkerFlags()} and {@link #getCharacterBoundsFlags(int)}: the
     * insertion marker or character bounds have at least one visible region.
     */
    public static final int FLAG_HAS_VISIBLE_REGION = 0x01;

    /**
     * Flag for {@link #getInsertionMarkerFlags()} and {@link #getCharacterBoundsFlags(int)}: the
     * insertion marker or character bounds have at least one invisible (clipped) region.
     */
    public static final int FLAG_HAS_INVISIBLE_REGION = 0x02;

    /**
     * Flag for {@link #getInsertionMarkerFlags()} and {@link #getCharacterBoundsFlags(int)}: the
     * insertion marker or character bounds is placed at right-to-left (RTL) character.
     */
    public static final int FLAG_IS_RTL = 0x04;

    public CursorAnchorInfo(final Parcel source) {
        mHashCode = source.readInt();
        mSelectionStart = source.readInt();
        mSelectionEnd = source.readInt();
        mComposingTextStart = source.readInt();
        mComposingText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mInsertionMarkerFlags = source.readInt();
        mInsertionMarkerHorizontal = source.readFloat();
        mInsertionMarkerTop = source.readFloat();
        mInsertionMarkerBaseline = source.readFloat();
        mInsertionMarkerBottom = source.readFloat();
        mCharacterBoundsArray = source.readParcelable(SparseRectFArray.class.getClassLoader());
        mMatrixValues = source.createFloatArray();
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mHashCode);
        dest.writeInt(mSelectionStart);
        dest.writeInt(mSelectionEnd);
        dest.writeInt(mComposingTextStart);
        TextUtils.writeToParcel(mComposingText, dest, flags);
        dest.writeInt(mInsertionMarkerFlags);
        dest.writeFloat(mInsertionMarkerHorizontal);
        dest.writeFloat(mInsertionMarkerTop);
        dest.writeFloat(mInsertionMarkerBaseline);
        dest.writeFloat(mInsertionMarkerBottom);
        dest.writeParcelable(mCharacterBoundsArray, flags);
        dest.writeFloatArray(mMatrixValues);
    }

    @Override
    public int hashCode(){
        return mHashCode;
    }

    /**
     * Compares two float values. Returns {@code true} if {@code a} and {@code b} are
     * {@link Float#NaN} at the same time.
     */
    private static boolean areSameFloatImpl(final float a, final float b) {
        if (Float.isNaN(a) && Float.isNaN(b)) {
            return true;
        }
        return a == b;
    }

    @Override
    public boolean equals(@Nullable Object obj){
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CursorAnchorInfo)) {
            return false;
        }
        final CursorAnchorInfo that = (CursorAnchorInfo) obj;
        if (hashCode() != that.hashCode()) {
            return false;
        }

        // Check fields that are not covered by hashCode() first.

        if (mSelectionStart != that.mSelectionStart || mSelectionEnd != that.mSelectionEnd) {
            return false;
        }

        if (mInsertionMarkerFlags != that.mInsertionMarkerFlags
                || !areSameFloatImpl(mInsertionMarkerHorizontal, that.mInsertionMarkerHorizontal)
                || !areSameFloatImpl(mInsertionMarkerTop, that.mInsertionMarkerTop)
                || !areSameFloatImpl(mInsertionMarkerBaseline, that.mInsertionMarkerBaseline)
                || !areSameFloatImpl(mInsertionMarkerBottom, that.mInsertionMarkerBottom)) {
            return false;
        }

        if (!Objects.equals(mCharacterBoundsArray, that.mCharacterBoundsArray)) {
            return false;
        }

        // Following fields are (partially) covered by hashCode().

        if (mComposingTextStart != that.mComposingTextStart
                || !Objects.equals(mComposingText, that.mComposingText)) {
            return false;
        }

        // We do not use Arrays.equals(float[], float[]) to keep the previous behavior regarding
        // NaN, 0.0f, and -0.0f.
        if (mMatrixValues.length != that.mMatrixValues.length) {
            return false;
        }
        for (int i = 0; i < mMatrixValues.length; ++i) {
            if (mMatrixValues[i] != that.mMatrixValues[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "CursorAnchorInfo{mHashCode=" + mHashCode
                + " mSelection=" + mSelectionStart + "," + mSelectionEnd
                + " mComposingTextStart=" + mComposingTextStart
                + " mComposingText=" + Objects.toString(mComposingText)
                + " mInsertionMarkerFlags=" + mInsertionMarkerFlags
                + " mInsertionMarkerHorizontal=" + mInsertionMarkerHorizontal
                + " mInsertionMarkerTop=" + mInsertionMarkerTop
                + " mInsertionMarkerBaseline=" + mInsertionMarkerBaseline
                + " mInsertionMarkerBottom=" + mInsertionMarkerBottom
                + " mCharacterBoundsArray=" + Objects.toString(mCharacterBoundsArray)
                + " mMatrix=" + Arrays.toString(mMatrixValues)
                + "}";
    }

    /**
     * Builder for {@link CursorAnchorInfo}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        private int mSelectionStart = -1;
        private int mSelectionEnd = -1;
        private int mComposingTextStart = -1;
        private CharSequence mComposingText = null;
        private float mInsertionMarkerHorizontal = Float.NaN;
        private float mInsertionMarkerTop = Float.NaN;
        private float mInsertionMarkerBaseline = Float.NaN;
        private float mInsertionMarkerBottom = Float.NaN;
        private int mInsertionMarkerFlags = 0;
        private SparseRectFArrayBuilder mCharacterBoundsArrayBuilder = null;
        private float[] mMatrixValues = null;
        private boolean mMatrixInitialized = false;

        /**
         * Sets the text range of the selection. Calling this can be skipped if there is no
         * selection.
         */
        public Builder setSelectionRange(final int newStart, final int newEnd) {
            mSelectionStart = newStart;
            mSelectionEnd = newEnd;
            return this;
        }

        /**
         * Sets the text range of the composing text. Calling this can be skipped if there is
         * no composing text.
         * @param composingTextStart index where the composing text starts.
         * @param composingText the entire composing text.
         */
        public Builder setComposingText(final int composingTextStart,
            final CharSequence composingText) {
            mComposingTextStart = composingTextStart;
            if (composingText == null) {
                mComposingText = null;
            } else {
                // Make a snapshot of the given char sequence.
                mComposingText = new SpannedString(composingText);
            }
            return this;
        }

        /**
         * Sets the location of the text insertion point (zero width cursor) as a rectangle in
         * local coordinates. Calling this can be skipped when there is no text insertion point;
         * however if there is an insertion point, editors must call this method.
         * @param horizontalPosition horizontal position of the insertion marker, in the local
         * coordinates that will be transformed with the transformation matrix when rendered on the
         * screen. This should be calculated or compatible with
         * {@link Layout#getPrimaryHorizontal(int)}.
         * @param lineTop vertical position of the insertion marker, in the local coordinates that
         * will be transformed with the transformation matrix when rendered on the screen. This
         * should be calculated or compatible with {@link Layout#getLineTop(int)}.
         * @param lineBaseline vertical position of the insertion marker, in the local coordinates
         * that will be transformed with the transformation matrix when rendered on the screen. This
         * should be calculated or compatible with {@link Layout#getLineBaseline(int)}.
         * @param lineBottom vertical position of the insertion marker, in the local coordinates
         * that will be transformed with the transformation matrix when rendered on the screen. This
         * should be calculated or compatible with {@link Layout#getLineBottom(int)}.
         * @param flags flags of the insertion marker. See {@link #FLAG_HAS_VISIBLE_REGION} for
         * example.
         */
        public Builder setInsertionMarkerLocation(final float horizontalPosition,
                final float lineTop, final float lineBaseline, final float lineBottom,
                final int flags){
            mInsertionMarkerHorizontal = horizontalPosition;
            mInsertionMarkerTop = lineTop;
            mInsertionMarkerBaseline = lineBaseline;
            mInsertionMarkerBottom = lineBottom;
            mInsertionMarkerFlags = flags;
            return this;
        }

        /**
         * Adds the bounding box of the character specified with the index.
         *
         * @param index index of the character in Java chars units. Must be specified in
         * ascending order across successive calls.
         * @param left x coordinate of the left edge of the character in local coordinates.
         * @param top y coordinate of the top edge of the character in local coordinates.
         * @param right x coordinate of the right edge of the character in local coordinates.
         * @param bottom y coordinate of the bottom edge of the character in local coordinates.
         * @param flags flags for this character bounds. See {@link #FLAG_HAS_VISIBLE_REGION},
         * {@link #FLAG_HAS_INVISIBLE_REGION} and {@link #FLAG_IS_RTL}. These flags must be
         * specified when necessary.
         * @throws IllegalArgumentException If the index is a negative value, or not greater than
         * all of the previously called indices.
         */
        public Builder addCharacterBounds(final int index, final float left, final float top,
                final float right, final float bottom, final int flags) {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be a negative integer.");
            }
            if (mCharacterBoundsArrayBuilder == null) {
                mCharacterBoundsArrayBuilder = new SparseRectFArrayBuilder();
            }
            mCharacterBoundsArrayBuilder.append(index, left, top, right, bottom, flags);
            return this;
        }

        /**
         * Sets the matrix that transforms local coordinates into screen coordinates.
         * @param matrix transformation matrix from local coordinates into screen coordinates. null
         * is interpreted as an identity matrix.
         */
        public Builder setMatrix(final Matrix matrix) {
            if (mMatrixValues == null) {
                mMatrixValues = new float[9];
            }
            (matrix != null ? matrix : Matrix.IDENTITY_MATRIX).getValues(mMatrixValues);
            mMatrixInitialized = true;
            return this;
        }

        /**
         * @return {@link CursorAnchorInfo} using parameters in this {@link Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are specified but
         * the coordinate transformation matrix is not provided via {@link #setMatrix(Matrix)}.
         */
        public CursorAnchorInfo build() {
            if (!mMatrixInitialized) {
                // Coordinate transformation matrix is mandatory when at least one positional
                // parameter is specified.
                final boolean hasCharacterBounds = (mCharacterBoundsArrayBuilder != null
                        && !mCharacterBoundsArrayBuilder.isEmpty());
                if (hasCharacterBounds
                        || !Float.isNaN(mInsertionMarkerHorizontal)
                        || !Float.isNaN(mInsertionMarkerTop)
                        || !Float.isNaN(mInsertionMarkerBaseline)
                        || !Float.isNaN(mInsertionMarkerBottom)) {
                    throw new IllegalArgumentException("Coordinate transformation matrix is " +
                            "required when positional parameters are specified.");
                }
            }
            return new CursorAnchorInfo(this);
        }

        /**
         * Resets the internal state so that this instance can be reused to build another
         * instance of {@link CursorAnchorInfo}.
         */
        public void reset() {
            mSelectionStart = -1;
            mSelectionEnd = -1;
            mComposingTextStart = -1;
            mComposingText = null;
            mInsertionMarkerFlags = 0;
            mInsertionMarkerHorizontal = Float.NaN;
            mInsertionMarkerTop = Float.NaN;
            mInsertionMarkerBaseline = Float.NaN;
            mInsertionMarkerBottom = Float.NaN;
            mMatrixInitialized = false;
            if (mCharacterBoundsArrayBuilder != null) {
                mCharacterBoundsArrayBuilder.reset();
            }
        }
    }

    private CursorAnchorInfo(final Builder builder) {
        mSelectionStart = builder.mSelectionStart;
        mSelectionEnd = builder.mSelectionEnd;
        mComposingTextStart = builder.mComposingTextStart;
        mComposingText = builder.mComposingText;
        mInsertionMarkerFlags = builder.mInsertionMarkerFlags;
        mInsertionMarkerHorizontal = builder.mInsertionMarkerHorizontal;
        mInsertionMarkerTop = builder.mInsertionMarkerTop;
        mInsertionMarkerBaseline = builder.mInsertionMarkerBaseline;
        mInsertionMarkerBottom = builder.mInsertionMarkerBottom;
        mCharacterBoundsArray = builder.mCharacterBoundsArrayBuilder != null
                ? builder.mCharacterBoundsArrayBuilder.build() : null;
        mMatrixValues = new float[9];
        if (builder.mMatrixInitialized) {
            System.arraycopy(builder.mMatrixValues, 0, mMatrixValues, 0, 9);
        } else {
            Matrix.IDENTITY_MATRIX.getValues(mMatrixValues);
        }

        // To keep hash function simple, we only use some complex objects for hash.
        int hash = Objects.hashCode(mComposingText);
        hash *= 31;
        hash += Arrays.hashCode(mMatrixValues);
        mHashCode = hash;
    }

    /**
     * Returns the index where the selection starts.
     * @return {@code -1} if there is no selection.
     */
    public int getSelectionStart() {
        return mSelectionStart;
    }

    /**
     * Returns the index where the selection ends.
     * @return {@code -1} if there is no selection.
     */
    public int getSelectionEnd() {
        return mSelectionEnd;
    }

    /**
     * Returns the index where the composing text starts.
     * @return {@code -1} if there is no composing text.
     */
    public int getComposingTextStart() {
        return mComposingTextStart;
    }

    /**
     * Returns the entire composing text.
     * @return {@code null} if there is no composition.
     */
    public CharSequence getComposingText() {
        return mComposingText;
    }

    /**
     * Returns the flag of the insertion marker.
     * @return the flag of the insertion marker. {@code 0} if no flag is specified.
     */
    public int getInsertionMarkerFlags() {
        return mInsertionMarkerFlags;
    }

    /**
     * Returns the horizontal start of the insertion marker, in the local coordinates that will
     * be transformed with {@link #getMatrix()} when rendered on the screen.
     * @return x coordinate that is compatible with {@link Layout#getPrimaryHorizontal(int)}.
     * Pay special care to RTL/LTR handling.
     * {@code java.lang.Float.NaN} if not specified.
     * @see Layout#getPrimaryHorizontal(int)
     */
    public float getInsertionMarkerHorizontal() {
        return mInsertionMarkerHorizontal;
    }

    /**
     * Returns the vertical top position of the insertion marker, in the local coordinates that
     * will be transformed with {@link #getMatrix()} when rendered on the screen.
     * @return y coordinate that is compatible with {@link Layout#getLineTop(int)}.
     * {@code java.lang.Float.NaN} if not specified.
     */
    public float getInsertionMarkerTop() {
        return mInsertionMarkerTop;
    }

    /**
     * Returns the vertical baseline position of the insertion marker, in the local coordinates
     * that will be transformed with {@link #getMatrix()} when rendered on the screen.
     * @return y coordinate that is compatible with {@link Layout#getLineBaseline(int)}.
     * {@code java.lang.Float.NaN} if not specified.
     */
    public float getInsertionMarkerBaseline() {
        return mInsertionMarkerBaseline;
    }

    /**
     * Returns the vertical bottom position of the insertion marker, in the local coordinates
     * that will be transformed with {@link #getMatrix()} when rendered on the screen.
     * @return y coordinate that is compatible with {@link Layout#getLineBottom(int)}.
     * {@code java.lang.Float.NaN} if not specified.
     */
    public float getInsertionMarkerBottom() {
        return mInsertionMarkerBottom;
    }

    /**
     * Returns a new instance of {@link RectF} that indicates the location of the character
     * specified with the index.
     * @param index index of the character in a Java chars.
     * @return the character bounds in local coordinates as a new instance of {@link RectF}.
     */
    public RectF getCharacterBounds(final int index) {
        if (mCharacterBoundsArray == null) {
            return null;
        }
        return mCharacterBoundsArray.get(index);
    }

    /**
     * Returns the flags associated with the character bounds specified with the index.
     * @param index index of the character in a Java chars.
     * @return {@code 0} if no flag is specified.
     */
    public int getCharacterBoundsFlags(final int index) {
        if (mCharacterBoundsArray == null) {
            return 0;
        }
        return mCharacterBoundsArray.getFlags(index, 0);
    }

    /**
     * Returns a new instance of {@link android.graphics.Matrix} that indicates the transformation
     * matrix that is to be applied other positional data in this class.
     * @return a new instance (copy) of the transformation matrix.
     */
    public Matrix getMatrix() {
        final Matrix matrix = new Matrix();
        matrix.setValues(mMatrixValues);
        return matrix;
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<CursorAnchorInfo> CREATOR
            = new Parcelable.Creator<CursorAnchorInfo>() {
        @Override
        public CursorAnchorInfo createFromParcel(Parcel source) {
            return new CursorAnchorInfo(source);
        }

        @Override
        public CursorAnchorInfo[] newArray(int size) {
            return new CursorAnchorInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}
