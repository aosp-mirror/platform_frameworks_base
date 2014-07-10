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

import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Layout;
import android.text.SpannedString;
import android.text.TextUtils;
import android.view.inputmethod.SparseRectFArray.SparseRectFArrayBuilder;

import java.util.Objects;

/**
 * Positional information about the text insertion point and characters in the composition string.
 *
 * <p>This class encapsulates locations of the text insertion point and the composition string in
 * the screen coordinates so that IMEs can render their UI components near where the text is
 * actually inserted.</p>
 */
public final class CursorAnchorInfo implements Parcelable {
    private final int mSelectionStart;
    private final int mSelectionEnd;

    private final int mComposingTextStart;
    /**
     * The text, tracked as a composing region.
     */
    private final CharSequence mComposingText;

    /**
     * {@code True} if the insertion marker is partially or entirely clipped by other UI elements.
     */
    private final boolean mInsertionMarkerClipped;
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
    private final SparseRectFArray mCharacterRects;

    /**
     * Transformation matrix that is applied to any positional information of this class to
     * transform local coordinates into screen coordinates.
     */
    private final Matrix mMatrix;

    public static final int CHARACTER_RECT_TYPE_MASK = 0x0f;
    /**
     * Type for {@link #CHARACTER_RECT_TYPE_MASK}: the editor did not specify any type of this
     * character. Editor authors should not use this flag.
     */
    public static final int CHARACTER_RECT_TYPE_UNSPECIFIED = 0;
    /**
     * Type for {@link #CHARACTER_RECT_TYPE_MASK}: the character is entirely visible.
     */
    public static final int CHARACTER_RECT_TYPE_FULLY_VISIBLE = 1;
    /**
     * Type for {@link #CHARACTER_RECT_TYPE_MASK}: some area of the character is invisible.
     */
    public static final int CHARACTER_RECT_TYPE_PARTIALLY_VISIBLE = 2;
    /**
     * Type for {@link #CHARACTER_RECT_TYPE_MASK}: the character is entirely invisible.
     */
    public static final int CHARACTER_RECT_TYPE_INVISIBLE = 3;
    /**
     * Type for {@link #CHARACTER_RECT_TYPE_MASK}: the editor gave up to calculate the rectangle
     * for this character. Input method authors should ignore the returned rectangle.
     */
    public static final int CHARACTER_RECT_TYPE_NOT_FEASIBLE = 4;

    public CursorAnchorInfo(final Parcel source) {
        mSelectionStart = source.readInt();
        mSelectionEnd = source.readInt();
        mComposingTextStart = source.readInt();
        mComposingText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mInsertionMarkerClipped = (source.readInt() != 0);
        mInsertionMarkerHorizontal = source.readFloat();
        mInsertionMarkerTop = source.readFloat();
        mInsertionMarkerBaseline = source.readFloat();
        mInsertionMarkerBottom = source.readFloat();
        mCharacterRects = source.readParcelable(SparseRectFArray.class.getClassLoader());
        mMatrix = new Matrix();
        mMatrix.setValues(source.createFloatArray());
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSelectionStart);
        dest.writeInt(mSelectionEnd);
        dest.writeInt(mComposingTextStart);
        TextUtils.writeToParcel(mComposingText, dest, flags);
        dest.writeInt(mInsertionMarkerClipped ? 1 : 0);
        dest.writeFloat(mInsertionMarkerHorizontal);
        dest.writeFloat(mInsertionMarkerTop);
        dest.writeFloat(mInsertionMarkerBaseline);
        dest.writeFloat(mInsertionMarkerBottom);
        dest.writeParcelable(mCharacterRects, flags);
        final float[] matrixArray = new float[9];
        mMatrix.getValues(matrixArray);
        dest.writeFloatArray(matrixArray);
    }

    @Override
    public int hashCode(){
        // TODO: Improve the hash function.
        final float floatHash = mInsertionMarkerHorizontal + mInsertionMarkerTop
                + mInsertionMarkerBaseline + mInsertionMarkerBottom;
        int hash = floatHash > 0 ? (int) floatHash : (int)(-floatHash);
        hash *= 31;
        hash += (mInsertionMarkerClipped ? 2 : 1);
        hash *= 31;
        hash += mSelectionStart + mSelectionEnd + mComposingTextStart;
        hash *= 31;
        hash += Objects.hashCode(mComposingText);
        hash *= 31;
        hash += Objects.hashCode(mCharacterRects);
        hash *= 31;
        hash += Objects.hashCode(mMatrix);
        return hash;
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
    public boolean equals(Object obj){
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
        if (mSelectionStart != that.mSelectionStart || mSelectionEnd != that.mSelectionEnd) {
            return false;
        }
        if (mComposingTextStart != that.mComposingTextStart
                || !Objects.equals(mComposingText, that.mComposingText)) {
            return false;
        }
        if (mInsertionMarkerClipped != that.mInsertionMarkerClipped
                || !areSameFloatImpl(mInsertionMarkerHorizontal, that.mInsertionMarkerHorizontal)
                || !areSameFloatImpl(mInsertionMarkerTop, that.mInsertionMarkerTop)
                || !areSameFloatImpl(mInsertionMarkerBaseline, that.mInsertionMarkerBaseline)
                || !areSameFloatImpl(mInsertionMarkerBottom, that.mInsertionMarkerBottom)) {
            return false;
        }
        if (!Objects.equals(mComposingTextStart, that.mComposingTextStart)) {
            return false;
        }
        if (!Objects.equals(mCharacterRects, that.mCharacterRects)) {
            return false;
        }
        if (!Objects.equals(mMatrix, that.mMatrix)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "SelectionInfo{mSelection=" + mSelectionStart + "," + mSelectionEnd
                + " mComposingTextStart=" + mComposingTextStart
                + " mComposingText=" + Objects.toString(mComposingText)
                + " mInsertionMarkerClipped=" + mInsertionMarkerClipped
                + " mInsertionMarkerHorizontal=" + mInsertionMarkerHorizontal
                + " mInsertionMarkerTop=" + mInsertionMarkerTop
                + " mInsertionMarkerBaseline=" + mInsertionMarkerBaseline
                + " mInsertionMarkerBottom=" + mInsertionMarkerBottom
                + " mCharacterRects=" + Objects.toString(mCharacterRects)
                + " mMatrix=" + Objects.toString(mMatrix)
                + "}";
    }

    /**
     * Builder for {@link CursorAnchorInfo}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        /**
         * Sets the text range of the selection. Calling this can be skipped if there is no
         * selection.
         */
        public Builder setSelectionRange(final int newStart, final int newEnd) {
            mSelectionStart = newStart;
            mSelectionEnd = newEnd;
            return this;
        }
        private int mSelectionStart = -1;
        private int mSelectionEnd = -1;

        /**
         * Sets the text range of the composing text. Calling this can be skipped if there is
         * no composing text.
         * @param index index where the composing text starts.
         * @param composingText the entire composing text.
         */
        public Builder setComposingText(final int index, final CharSequence composingText) {
            mComposingTextStart = index;
            if (composingText == null) {
                mComposingText = null;
            } else {
                // Make a snapshot of the given char sequence.
                mComposingText = new SpannedString(composingText);
            }
            return this;
        }
        private int mComposingTextStart = -1;
        private CharSequence mComposingText = null;

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
         * @param clipped {@code true} is the insertion marker is partially or entierly clipped by
         * other UI elements.
         */
        public Builder setInsertionMarkerLocation(final float horizontalPosition,
                final float lineTop, final float lineBaseline, final float lineBottom,
                final boolean clipped){
            mInsertionMarkerHorizontal = horizontalPosition;
            mInsertionMarkerTop = lineTop;
            mInsertionMarkerBaseline = lineBaseline;
            mInsertionMarkerBottom = lineBottom;
            mInsertionMarkerClipped = clipped;
            return this;
        }
        private float mInsertionMarkerHorizontal = Float.NaN;
        private float mInsertionMarkerTop = Float.NaN;
        private float mInsertionMarkerBaseline = Float.NaN;
        private float mInsertionMarkerBottom = Float.NaN;
        private boolean mInsertionMarkerClipped = false;

        /**
         * Adds the bounding box of the character specified with the index.
         *
         * @param index index of the character in Java chars units. Must be specified in
         * ascending order across successive calls.
         * @param leadingEdgeX x coordinate of the leading edge of the character in local
         * coordinates, that is, left edge for LTR text and right edge for RTL text.
         * @param leadingEdgeY y coordinate of the leading edge of the character in local
         * coordinates.
         * @param trailingEdgeX x coordinate of the trailing edge of the character in local
         * coordinates, that is, right edge for LTR text and left edge for RTL text.
         * @param trailingEdgeY y coordinate of the trailing edge of the character in local
         * coordinates.
         * @param flags type and flags for this character. See
         * {@link #CHARACTER_RECT_TYPE_FULLY_VISIBLE} for example.
         * @throws IllegalArgumentException If the index is a negative value, or not greater than
         * all of the previously called indices.
         */
        public Builder addCharacterRect(final int index, final float leadingEdgeX,
                final float leadingEdgeY, final float trailingEdgeX, final float trailingEdgeY,
                final int flags) {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be a negative integer.");
            }
            final int type = flags & CHARACTER_RECT_TYPE_MASK;
            if (type == CHARACTER_RECT_TYPE_UNSPECIFIED) {
                throw new IllegalArgumentException("Type except for "
                        + "CHARACTER_RECT_TYPE_UNSPECIFIED must be specified.");
            }
            if (mCharacterRectBuilder == null) {
                mCharacterRectBuilder = new SparseRectFArrayBuilder();
            }
            mCharacterRectBuilder.append(index, leadingEdgeX, leadingEdgeY, trailingEdgeX,
                    trailingEdgeY, flags);
            return this;
        }
        private SparseRectFArrayBuilder mCharacterRectBuilder = null;

        /**
         * Sets the matrix that transforms local coordinates into screen coordinates.
         * @param matrix transformation matrix from local coordinates into screen coordinates. null
         * is interpreted as an identity matrix.
         */
        public Builder setMatrix(final Matrix matrix) {
            mMatrix.set(matrix != null ? matrix : Matrix.IDENTITY_MATRIX);
            mMatrixInitialized = true;
            return this;
        }
        private final Matrix mMatrix = new Matrix(Matrix.IDENTITY_MATRIX);
        private boolean mMatrixInitialized = false;

        /**
         * @return {@link CursorAnchorInfo} using parameters in this {@link Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are specified but
         * the coordinate transformation matrix is not provided via {@link #setMatrix(Matrix)}.
         */
        public CursorAnchorInfo build() {
            if (!mMatrixInitialized) {
                // Coordinate transformation matrix is mandatory when positional parameters are
                // specified.
                if ((mCharacterRectBuilder != null && !mCharacterRectBuilder.isEmpty()) ||
                        !Float.isNaN(mInsertionMarkerHorizontal) ||
                        !Float.isNaN(mInsertionMarkerTop) ||
                        !Float.isNaN(mInsertionMarkerBaseline) ||
                        !Float.isNaN(mInsertionMarkerBottom)) {
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
            mInsertionMarkerClipped = false;
            mInsertionMarkerHorizontal = Float.NaN;
            mInsertionMarkerTop = Float.NaN;
            mInsertionMarkerBaseline = Float.NaN;
            mInsertionMarkerBottom = Float.NaN;
            mMatrix.set(Matrix.IDENTITY_MATRIX);
            mMatrixInitialized = false;
            if (mCharacterRectBuilder != null) {
                mCharacterRectBuilder.reset();
            }
        }
    }

    private CursorAnchorInfo(final Builder builder) {
        mSelectionStart = builder.mSelectionStart;
        mSelectionEnd = builder.mSelectionEnd;
        mComposingTextStart = builder.mComposingTextStart;
        mComposingText = builder.mComposingText;
        mInsertionMarkerClipped = builder.mInsertionMarkerClipped;
        mInsertionMarkerHorizontal = builder.mInsertionMarkerHorizontal;
        mInsertionMarkerTop = builder.mInsertionMarkerTop;
        mInsertionMarkerBaseline = builder.mInsertionMarkerBaseline;
        mInsertionMarkerBottom = builder.mInsertionMarkerBottom;
        mCharacterRects = builder.mCharacterRectBuilder != null ?
                builder.mCharacterRectBuilder.build() : null;
        mMatrix = new Matrix(builder.mMatrix);
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
     * Returns the visibility of the insertion marker.
     * @return {@code true} if the insertion marker is partially or entirely clipped.
     */
    public boolean isInsertionMarkerClipped() {
        return mInsertionMarkerClipped;
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
     * <p>
     * Note that coordinates are not necessarily contiguous or even monotonous, especially when
     * RTL text and LTR text are mixed.
     * </p>
     * @param index index of the character in a Java chars.
     * @return a new instance of {@link RectF} that represents the location of the character in
     * local coordinates. null if the character is invisible or the application did not provide
     * the location. Note that the {@code left} field can be greater than the {@code right} field
     * if the character is in RTL text. Returns {@code null} if no location information is
     * available.
     */
    // TODO: Prepare a document about the expected behavior for surrogate pairs, combining
    // characters, and non-graphical chars.
    public RectF getCharacterRect(final int index) {
        if (mCharacterRects == null) {
            return null;
        }
        return mCharacterRects.get(index);
    }

    /**
     * Returns the flags associated with the character specified with the index.
     * @param index index of the character in a Java chars.
     * @return {@link #CHARACTER_RECT_TYPE_UNSPECIFIED} if no flag is specified.
     */
    // TODO: Prepare a document about the expected behavior for surrogate pairs, combining
    // characters, and non-graphical chars.
    public int getCharacterRectFlags(final int index) {
        if (mCharacterRects == null) {
            return CHARACTER_RECT_TYPE_UNSPECIFIED;
        }
        return mCharacterRects.getFlags(index, CHARACTER_RECT_TYPE_UNSPECIFIED);
    }

    /**
     * Returns a new instance of {@link android.graphics.Matrix} that indicates the transformation
     * matrix that is to be applied other positional data in this class.
     * @return a new instance (copy) of the transformation matrix.
     */
    public Matrix getMatrix() {
        return new Matrix(mMatrix);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<CursorAnchorInfo> CREATOR
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
