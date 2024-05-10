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
import android.inputmethodservice.InputMethodService;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Layout;
import android.text.SpannedString;
import android.text.TextUtils;
import android.view.inputmethod.SparseRectFArray.SparseRectFArrayBuilder;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
     * Container of rectangular position of Editor in the local coordinates that will be transformed
     * with the transformation matrix when rendered on the screen.
     * @see EditorBoundsInfo
     */
    private final EditorBoundsInfo mEditorBoundsInfo;

    /**
     * Transformation matrix that is applied to any positional information of this class to
     * transform local coordinates into screen coordinates.
     */
    @NonNull
    private final float[] mMatrixValues;

    /**
     * Information about text appearance in the editor for use by {@link InputMethodService}.
     */
    @Nullable
    private final TextAppearanceInfo mTextAppearanceInfo;

    /**
     * A list of visible line bounds stored in a float array. This array is divided into segment of
     * four where each element in the segment represents left, top, right respectively and bottom
     * of the line bounds.
     */
    private final float[] mVisibleLineBounds;

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
        mCharacterBoundsArray = source.readTypedObject(SparseRectFArray.CREATOR);
        mEditorBoundsInfo = source.readTypedObject(EditorBoundsInfo.CREATOR);
        mMatrixValues = source.createFloatArray();
        mVisibleLineBounds = source.createFloatArray();
        mTextAppearanceInfo = source.readTypedObject(TextAppearanceInfo.CREATOR);
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
        dest.writeTypedObject(mCharacterBoundsArray, flags);
        dest.writeTypedObject(mEditorBoundsInfo, flags);
        dest.writeFloatArray(mMatrixValues);
        dest.writeFloatArray(mVisibleLineBounds);
        dest.writeTypedObject(mTextAppearanceInfo, flags);
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

        if (!Objects.equals(mEditorBoundsInfo, that.mEditorBoundsInfo)) {
            return false;
        }

        if (!Arrays.equals(mVisibleLineBounds, that.mVisibleLineBounds)) {
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

        if (!Objects.equals(mTextAppearanceInfo, that.mTextAppearanceInfo)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return "CursorAnchorInfo{mHashCode=" + mHashCode
                + " mSelection=" + mSelectionStart + "," + mSelectionEnd
                + " mComposingTextStart=" + mComposingTextStart
                + " mComposingText=" + mComposingText
                + " mInsertionMarkerFlags=" + mInsertionMarkerFlags
                + " mInsertionMarkerHorizontal=" + mInsertionMarkerHorizontal
                + " mInsertionMarkerTop=" + mInsertionMarkerTop
                + " mInsertionMarkerBaseline=" + mInsertionMarkerBaseline
                + " mInsertionMarkerBottom=" + mInsertionMarkerBottom
                + " mCharacterBoundsArray=" + mCharacterBoundsArray
                + " mEditorBoundsInfo=" + mEditorBoundsInfo
                + " mVisibleLineBounds=" + getVisibleLineBounds()
                + " mMatrix=" + Arrays.toString(mMatrixValues)
                + " mTextAppearanceInfo=" + mTextAppearanceInfo
                + "}";
    }

    /**
     * Builder for {@link CursorAnchorInfo}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        private static final int LINE_BOUNDS_INITIAL_SIZE = 4;
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
        private EditorBoundsInfo mEditorBoundsInfo = null;
        private float[] mMatrixValues = null;
        private boolean mMatrixInitialized = false;
        private float[] mVisibleLineBounds = new float[LINE_BOUNDS_INITIAL_SIZE * 4];
        private int mVisibleLineBoundsCount = 0;
        private TextAppearanceInfo mTextAppearanceInfo = null;

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
         * Sets the current editor related bounds.
         *
         * @param bounds {@link EditorBoundsInfo} in local coordinates.
         */
        @NonNull
        public Builder setEditorBoundsInfo(@Nullable EditorBoundsInfo bounds) {
            mEditorBoundsInfo = bounds;
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
         * Set the information related to text appearance, which is extracted from the original
         * {@link TextView}.
         * @param textAppearanceInfo {@link TextAppearanceInfo} of TextView.
         */
        @NonNull
        public Builder setTextAppearanceInfo(@Nullable TextAppearanceInfo textAppearanceInfo) {
            mTextAppearanceInfo = textAppearanceInfo;
            return this;
        }

        /**
         * Add the bounds of a visible text line of the current editor.
         *
         * The line bounds should not include the vertical space between lines or the horizontal
         * space before and after a line.
         * It's preferable if the line bounds are added in the logical order, so that IME can
         * process them easily.
         *
         * @param left the left bound of the left-most character in the line
         * @param top the top bound of the top-most character in the line
         * @param right the right bound of the right-most character in the line
         * @param bottom the bottom bound of the bottom-most character in the line
         *
         * @see CursorAnchorInfo#getVisibleLineBounds()
         * @see #clearVisibleLineBounds()
         */
        @NonNull
        public Builder addVisibleLineBounds(float left, float top, float right, float bottom) {
            if (mVisibleLineBounds.length <= mVisibleLineBoundsCount + 4) {
                mVisibleLineBounds =
                        Arrays.copyOf(mVisibleLineBounds, (mVisibleLineBoundsCount + 4) * 2);
            }
            mVisibleLineBounds[mVisibleLineBoundsCount++] = left;
            mVisibleLineBounds[mVisibleLineBoundsCount++] = top;
            mVisibleLineBounds[mVisibleLineBoundsCount++] = right;
            mVisibleLineBounds[mVisibleLineBoundsCount++] = bottom;
            return this;
        }

        /**
         * Clear the visible text line bounds previously added to this {@link Builder}.
         *
         * @see #addVisibleLineBounds(float, float, float, float)
         */
        @NonNull
        public Builder clearVisibleLineBounds() {
            // Since mVisibleLineBounds is copied in build(), we only need to reset
            // mVisibleLineBoundsCount to 0. And mVisibleLineBounds will be reused for better
            // performance.
            mVisibleLineBoundsCount = 0;
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
                final boolean hasVisibleLineBounds = (mVisibleLineBounds != null
                        && mVisibleLineBoundsCount > 0);
                if (hasCharacterBounds
                        || hasVisibleLineBounds
                        || !Float.isNaN(mInsertionMarkerHorizontal)
                        || !Float.isNaN(mInsertionMarkerTop)
                        || !Float.isNaN(mInsertionMarkerBaseline)
                        || !Float.isNaN(mInsertionMarkerBottom)) {
                    throw new IllegalArgumentException("Coordinate transformation matrix is " +
                            "required when positional parameters are specified.");
                }
            }
            return CursorAnchorInfo.create(this);
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
            mEditorBoundsInfo = null;
            clearVisibleLineBounds();
            mTextAppearanceInfo = null;
        }
    }

    private static CursorAnchorInfo create(Builder builder) {
        final SparseRectFArray characterBoundsArray =
                builder.mCharacterBoundsArrayBuilder != null
                        ? builder.mCharacterBoundsArrayBuilder.build()
                        : null;
        final float[] matrixValues = new float[9];
        if (builder.mMatrixInitialized) {
            System.arraycopy(builder.mMatrixValues, 0, matrixValues, 0, 9);
        } else {
            Matrix.IDENTITY_MATRIX.getValues(matrixValues);
        }

        return new CursorAnchorInfo(builder.mSelectionStart, builder.mSelectionEnd,
                builder.mComposingTextStart, builder.mComposingText, builder.mInsertionMarkerFlags,
                builder.mInsertionMarkerHorizontal, builder.mInsertionMarkerTop,
                builder.mInsertionMarkerBaseline, builder.mInsertionMarkerBottom,
                characterBoundsArray, builder.mEditorBoundsInfo, matrixValues,
                Arrays.copyOf(builder.mVisibleLineBounds, builder.mVisibleLineBoundsCount),
                builder.mTextAppearanceInfo);
    }

    private CursorAnchorInfo(int selectionStart, int selectionEnd, int composingTextStart,
            @Nullable CharSequence composingText, int insertionMarkerFlags,
            float insertionMarkerHorizontal, float insertionMarkerTop,
            float insertionMarkerBaseline, float insertionMarkerBottom,
            @Nullable SparseRectFArray characterBoundsArray,
            @Nullable EditorBoundsInfo editorBoundsInfo,
            @NonNull float[] matrixValues, @Nullable float[] visibleLineBounds,
            @Nullable TextAppearanceInfo textAppearanceInfo) {
        mSelectionStart = selectionStart;
        mSelectionEnd = selectionEnd;
        mComposingTextStart = composingTextStart;
        mComposingText = composingText;
        mInsertionMarkerFlags = insertionMarkerFlags;
        mInsertionMarkerHorizontal = insertionMarkerHorizontal;
        mInsertionMarkerTop = insertionMarkerTop;
        mInsertionMarkerBaseline = insertionMarkerBaseline;
        mInsertionMarkerBottom = insertionMarkerBottom;
        mCharacterBoundsArray = characterBoundsArray;
        mEditorBoundsInfo = editorBoundsInfo;
        mMatrixValues = matrixValues;
        mVisibleLineBounds = visibleLineBounds;
        mTextAppearanceInfo = textAppearanceInfo;

        // To keep hash function simple, we only use some complex objects for hash.
        int hashCode = Objects.hashCode(mComposingText);
        hashCode *= 31;
        hashCode += Arrays.hashCode(mMatrixValues);
        mHashCode = hashCode;
    }

    /**
     * Creates a new instance of {@link CursorAnchorInfo} by applying {@code parentMatrix} to
     * the coordinate transformation matrix.
     *
     * @param original     {@link CursorAnchorInfo} to be cloned from.
     * @param parentMatrix {@link Matrix} to be applied to {@code original.getMatrix()}
     * @return A new instance of {@link CursorAnchorInfo} whose {@link CursorAnchorInfo#getMatrix()}
     *         returns {@code parentMatrix * original.getMatrix()}.
     * @hide
     */
    public static CursorAnchorInfo createForAdditionalParentMatrix(CursorAnchorInfo original,
            @NonNull Matrix parentMatrix) {
        return new CursorAnchorInfo(original.mSelectionStart, original.mSelectionEnd,
                original.mComposingTextStart, original.mComposingText,
                original.mInsertionMarkerFlags, original.mInsertionMarkerHorizontal,
                original.mInsertionMarkerTop, original.mInsertionMarkerBaseline,
                original.mInsertionMarkerBottom, original.mCharacterBoundsArray,
                original.mEditorBoundsInfo, computeMatrixValues(parentMatrix, original),
                original.mVisibleLineBounds, original.mTextAppearanceInfo);
    }

    /**
     * Returns a float array that represents {@link Matrix} elements for
     * {@code parentMatrix * info.getMatrix()}.
     *
     * @param parentMatrix {@link Matrix} to be multiplied.
     * @param info         {@link CursorAnchorInfo} to provide {@link Matrix} to be multiplied.
     * @return {@code parentMatrix * info.getMatrix()}.
     */
    private static float[] computeMatrixValues(@NonNull Matrix parentMatrix,
            @NonNull CursorAnchorInfo info) {
        if (parentMatrix.isIdentity()) {
            return info.mMatrixValues;
        }

        final Matrix newMatrix = new Matrix();
        newMatrix.setValues(info.mMatrixValues);
        newMatrix.postConcat(parentMatrix);

        final float[] matrixValues = new float[9];
        newMatrix.getValues(matrixValues);
        return matrixValues;
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
     * Returns the list of {@link RectF}s indicating the locations of the visible line bounds in
     * the editor.
     * @return the visible line bounds in the local coordinates as a list of {@link RectF}.
     *
     * @see Builder#addVisibleLineBounds(float, float, float, float)
     */
    @NonNull
    public List<RectF> getVisibleLineBounds() {
        if (mVisibleLineBounds == null) {
            return Collections.emptyList();
        }
        final List<RectF> result = new ArrayList<>(mVisibleLineBounds.length / 4);
        for (int index = 0; index < mVisibleLineBounds.length;) {
            final RectF rectF = new RectF(
                    mVisibleLineBounds[index++],
                    mVisibleLineBounds[index++],
                    mVisibleLineBounds[index++],
                    mVisibleLineBounds[index++]);
            result.add(rectF);
        }
        return result;
    }

    /**
     * Returns {@link EditorBoundsInfo} for the current editor, or {@code null} if IME is not
     * subscribed with {@link InputConnection#CURSOR_UPDATE_FILTER_EDITOR_BOUNDS}
     * or {@link InputConnection#CURSOR_UPDATE_MONITOR}.
     */
    @Nullable
    public EditorBoundsInfo getEditorBoundsInfo() {
        return mEditorBoundsInfo;
    }

    /**
     * Returns {@link TextAppearanceInfo} for the current editor, or {@code null} if IME is not
     * subscribed with {@link InputConnection#CURSOR_UPDATE_FILTER_TEXT_APPEARANCE}
     * or {@link InputConnection#CURSOR_UPDATE_MONITOR}.
     */
    @Nullable
    public TextAppearanceInfo getTextAppearanceInfo() {
        return mTextAppearanceInfo;
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
