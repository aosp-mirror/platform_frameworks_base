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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.SegmentFinder;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The text bounds information of a slice of text in the editor.
 *
 * <p> This class provides IME the layout information of the text within the range from
 * {@link #getStart()} to {@link #getEnd()}. It's intended to be used by IME as a supplementary API
 * to support handwriting gestures.
 * </p>
 */
public final class TextBoundsInfo implements Parcelable {
    /**
     * The flag indicating that the character is a whitespace.
     *
     * @see Builder#setCharacterFlags(int[])
     * @see #getCharacterFlags(int)
     */
    public static final int FLAG_CHARACTER_WHITESPACE = 1;

    /**
     * The flag indicating that the character is a linefeed character.
     *
     * @see Builder#setCharacterFlags(int[])
     * @see #getCharacterFlags(int)
     */
    public static final int FLAG_CHARACTER_LINEFEED = 1 << 1;

    /**
     * The flag indicating that the character is a punctuation.
     *
     * @see Builder#setCharacterFlags(int[])
     * @see #getCharacterFlags(int)
     */
    public static final int FLAG_CHARACTER_PUNCTUATION = 1 << 2;

    /**
     * The flag indicating that the line this character belongs to has RTL line direction. It's
     * required that all characters in the same line must have the same direction.
     *
     * @see Builder#setCharacterFlags(int[])
     * @see #getCharacterFlags(int)
     */
    public static final int FLAG_LINE_IS_RTL = 1 << 3;


    /** @hide */
    @IntDef(prefix = "FLAG_", flag = true, value = {
            FLAG_CHARACTER_WHITESPACE,
            FLAG_CHARACTER_LINEFEED,
            FLAG_CHARACTER_PUNCTUATION,
            FLAG_LINE_IS_RTL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CharacterFlags {}

    /** All the valid flags. */
    private static final int KNOWN_CHARACTER_FLAGS = FLAG_CHARACTER_WHITESPACE
            | FLAG_CHARACTER_LINEFEED | FLAG_CHARACTER_PUNCTUATION  | FLAG_LINE_IS_RTL;

    /**
     * The amount of shift to get the character's BiDi level from the internal character flags.
     */
    private static final int BIDI_LEVEL_SHIFT = 19;

    /**
     * The mask used to get the character's BiDi level from the internal character flags.
     */
    private static final int BIDI_LEVEL_MASK = 0x7F << BIDI_LEVEL_SHIFT;

    /**
     * The flag indicating that the character at the index is the start of a line segment.
     * This flag is only used internally to serialize the {@link SegmentFinder}.
     *
     * @see #writeToParcel(Parcel, int)
     */
    private static final int FLAG_LINE_SEGMENT_START = 1 << 31;

    /**
     * The flag indicating that the character at the index is the end of a line segment.
     * This flag is only used internally to serialize the {@link SegmentFinder}.
     *
     * @see #writeToParcel(Parcel, int)
     */
    private static final int FLAG_LINE_SEGMENT_END = 1 << 30;

    /**
     * The flag indicating that the character at the index is the start of a word segment.
     * This flag is only used internally to serialize the {@link SegmentFinder}.
     *
     * @see #writeToParcel(Parcel, int)
     */
    private static final int FLAG_WORD_SEGMENT_START = 1 << 29;

    /**
     * The flag indicating that the character at the index is the end of a word segment.
     * This flag is only used internally to serialize the {@link SegmentFinder}.
     *
     * @see #writeToParcel(Parcel, int)
     */
    private static final int FLAG_WORD_SEGMENT_END = 1 << 28;

    /**
     * The flag indicating that the character at the index is the start of a grapheme segment.
     * It's only used internally to serialize the {@link SegmentFinder}.
     *
     * @see #writeToParcel(Parcel, int)
     */
    private static final int FLAG_GRAPHEME_SEGMENT_START = 1 << 27;

    /**
     * The flag indicating that the character at the index is the end of a grapheme segment.
     * It's only used internally to serialize the {@link SegmentFinder}.
     *
     * @see #writeToParcel(Parcel, int)
     */
    private static final int FLAG_GRAPHEME_SEGMENT_END = 1 << 26;

    private final int mStart;
    private final int mEnd;
    private final float[] mMatrixValues;
    private final float[] mCharacterBounds;
    /**
     * The array that encodes character and BiDi levels. They are stored together to save memory
     * space, and it's easier during serialization.
     */
    private final int[] mInternalCharacterFlags;
    private final SegmentFinder mLineSegmentFinder;
    private final SegmentFinder mWordSegmentFinder;
    private final SegmentFinder mGraphemeSegmentFinder;

    /**
     * Returns a new instance of {@link android.graphics.Matrix} that indicates the transformation
     * matrix that is to be applied other positional data in this class.
     *
     * @return a new instance (copy) of the transformation matrix.
     */
    @NonNull
    public Matrix getMatrix() {
        final Matrix matrix = new Matrix();
        matrix.setValues(mMatrixValues);
        return matrix;
    }

    /**
     * Returns the index of the first character whose bounds information is available in this
     * {@link TextBoundsInfo}, inclusive.
     *
     * @see Builder#setStartAndEnd(int, int)
     */
    public int getStart() {
        return mStart;
    }

    /**
     * Returns the index of the last character whose bounds information is available in this
     * {@link TextBoundsInfo}, exclusive.
     *
     * @see Builder#setStartAndEnd(int, int)
     */
    public int getEnd() {
        return mEnd;
    }

    /**
     * Return the bounds of the character at the given {@code index}, in the coordinates of the
     * editor.
     *
     * @param index the index of the queried character.
     * @return the bounding box of the queried character.
     *
     * @throws IndexOutOfBoundsException if the given {@code index} is out of the range from
     * the {@code start} to the {@code end}.
     */
    @NonNull
    public RectF getCharacterBounds(int index) {
        if (index < mStart || index >= mEnd) {
            throw new IndexOutOfBoundsException("Index is out of the bounds of "
                    + "[" + mStart + ", " + mEnd + ").");
        }
        final int offset = 4 * (index - mStart);
        return new RectF(mCharacterBounds[offset], mCharacterBounds[offset + 1],
                mCharacterBounds[offset + 2], mCharacterBounds[offset + 3]);
    }

    /**
     * Return the flags associated with the character at the given {@code index}.
     * The flags contain the following information:
     * <ul>
     *     <li>The {@link #FLAG_CHARACTER_WHITESPACE} flag, indicating the character is a
     *     whitespace. </li>
     *     <li>The {@link #FLAG_CHARACTER_LINEFEED} flag, indicating the character is a
     *     linefeed. </li>
     *     <li>The {@link #FLAG_CHARACTER_PUNCTUATION} flag, indicating the character is a
     *     punctuation. </li>
     *     <li>The {@link #FLAG_LINE_IS_RTL} flag, indicating the line this character belongs to
     *     has RTL line direction. All characters in the same line must have the same line
     *     direction. Check {@link #getLineSegmentFinder()} for more information of
     *     line boundaries. </li>
     * </ul>
     *
     * @param index the index of the queried character.
     * @return the flags associated with the queried character.
     *
     * @throws IndexOutOfBoundsException if the given {@code index} is out of the range from
     * the {@code start} to the {@code end}.
     *
     * @see #FLAG_CHARACTER_WHITESPACE
     * @see #FLAG_CHARACTER_LINEFEED
     * @see #FLAG_CHARACTER_PUNCTUATION
     * @see #FLAG_LINE_IS_RTL
     */
    @CharacterFlags
    public int getCharacterFlags(int index) {
        if (index < mStart || index >= mEnd) {
            throw new IndexOutOfBoundsException("Index is out of the bounds of "
                    + "[" + mStart + ", " + mEnd + ").");
        }
        final int offset = index - mStart;
        return mInternalCharacterFlags[offset] & KNOWN_CHARACTER_FLAGS;
    }

    /**
     * The BiDi level of the character at the given {@code index}. <br/>
     * BiDi level is defined by
     * <a href="https://unicode.org/reports/tr9/#Basic_Display_Algorithm" >the unicode
     * bidirectional algorithm </a>. One can determine whether a character's direction is
     * right-to-left (RTL) or left-to-right (LTR) by checking the last bit of the BiDi level.
     * If it's 1, the character is RTL, otherwise the character is LTR. The BiDi level of a
     * character must be in the range of [0, 125].
     *
     * @param index the index of the queried character.
     * @return the BiDi level of the character, which is an integer in the range of [0, 125].
     * @throws IndexOutOfBoundsException if the given {@code index} is out of the range from
     * the {@code start} to the {@code end}.
     *
     * @see Builder#setCharacterBidiLevel(int[])
     */
    @IntRange(from = 0, to = 125)
    public int getCharacterBidiLevel(int index) {
        if (index < mStart || index >= mEnd) {
            throw new IndexOutOfBoundsException("Index is out of the bounds of "
                    + "[" + mStart + ", " + mEnd + ").");
        }
        final int offset = index - mStart;
        return (mInternalCharacterFlags[offset] & BIDI_LEVEL_MASK) >> BIDI_LEVEL_SHIFT;
    }

    /**
     * Returns the {@link SegmentFinder} that locates the word boundaries.
     *
     * @see Builder#setWordSegmentFinder(SegmentFinder)
     */
    @NonNull
    public SegmentFinder getWordSegmentFinder() {
        return mWordSegmentFinder;
    }

    /**
     * Returns the {@link SegmentFinder} that locates the grapheme boundaries.
     *
     * @see Builder#setGraphemeSegmentFinder(SegmentFinder)
     */
    @NonNull
    public SegmentFinder getGraphemeSegmentFinder() {
        return mGraphemeSegmentFinder;
    }

    /**
     * Returns the {@link SegmentFinder} that locates the line boundaries.
     *
     * @see Builder#setLineSegmentFinder(SegmentFinder)
     */
    @NonNull
    public SegmentFinder getLineSegmentFinder() {
        return mLineSegmentFinder;
    }

    /**
     * Describe the kinds of special objects contained in this Parcelable
     * instance's marshaled representation. For example, if the object will
     * include a file descriptor in the output of {@link #writeToParcel(Parcel, int)},
     * the return value of this method must include the
     * {@link #CONTENTS_FILE_DESCRIPTOR} bit.
     *
     * @return a bitmask indicating the set of special object types marshaled
     * by this Parcelable object instance.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStart);
        dest.writeInt(mEnd);
        dest.writeFloatArray(mMatrixValues);
        dest.writeFloatArray(mCharacterBounds);

        // The end can also be a break position. We need an extra space to encode the breaks.
        final int[] encodedFlags = Arrays.copyOf(mInternalCharacterFlags, mEnd - mStart + 1);
        encodeSegmentFinder(encodedFlags, FLAG_GRAPHEME_SEGMENT_START, FLAG_GRAPHEME_SEGMENT_END,
                mStart, mEnd, mGraphemeSegmentFinder);
        encodeSegmentFinder(encodedFlags, FLAG_WORD_SEGMENT_START, FLAG_WORD_SEGMENT_END, mStart,
                mEnd, mWordSegmentFinder);
        encodeSegmentFinder(encodedFlags, FLAG_LINE_SEGMENT_START, FLAG_LINE_SEGMENT_END, mStart,
                mEnd, mLineSegmentFinder);
        dest.writeIntArray(encodedFlags);
    }

    private TextBoundsInfo(Parcel source) {
        mStart = source.readInt();
        mEnd  = source.readInt();
        mMatrixValues = Objects.requireNonNull(source.createFloatArray());
        mCharacterBounds = Objects.requireNonNull(source.createFloatArray());
        final int[] encodedFlags = Objects.requireNonNull(source.createIntArray());

        mGraphemeSegmentFinder = decodeSegmentFinder(encodedFlags, FLAG_GRAPHEME_SEGMENT_START,
                FLAG_GRAPHEME_SEGMENT_END, mStart, mEnd);
        mWordSegmentFinder = decodeSegmentFinder(encodedFlags, FLAG_WORD_SEGMENT_START,
                FLAG_WORD_SEGMENT_END, mStart, mEnd);
        mLineSegmentFinder = decodeSegmentFinder(encodedFlags, FLAG_LINE_SEGMENT_START,
                FLAG_LINE_SEGMENT_END, mStart, mEnd);

        final int length = mEnd - mStart;
        final int flagsMask = KNOWN_CHARACTER_FLAGS | BIDI_LEVEL_MASK;
        mInternalCharacterFlags = new int[length];
        for (int i = 0; i < length; ++i) {
            // Remove the flags used to encoded segment boundaries.
            mInternalCharacterFlags[i] = encodedFlags[i] & flagsMask;
        }
    }

    private TextBoundsInfo(Builder builder) {
        mStart = builder.mStart;
        mEnd = builder.mEnd;
        mMatrixValues = Arrays.copyOf(builder.mMatrixValues, 9);
        final int length = mEnd - mStart;
        mCharacterBounds = Arrays.copyOf(builder.mCharacterBounds, 4 * length);
        // Store characterFlags and characterBidiLevels to save memory.
        mInternalCharacterFlags = new int[length];
        for (int index = 0; index < length; ++index) {
            mInternalCharacterFlags[index] = builder.mCharacterFlags[index]
                    | (builder.mCharacterBidiLevels[index] << BIDI_LEVEL_SHIFT);
        }
        mGraphemeSegmentFinder = builder.mGraphemeSegmentFinder;
        mWordSegmentFinder = builder.mWordSegmentFinder;
        mLineSegmentFinder = builder.mLineSegmentFinder;
    }

    /**
     * The CREATOR to make this class Parcelable.
     */
    @NonNull
    public static final Parcelable.Creator<TextBoundsInfo> CREATOR = new Creator<TextBoundsInfo>() {
        @Override
        public TextBoundsInfo createFromParcel(Parcel source) {
            return new TextBoundsInfo(source);
        }

        @Override
        public TextBoundsInfo[] newArray(int size) {
            return new TextBoundsInfo[size];
        }
    };

    private static final String TEXT_BOUNDS_INFO_KEY = "android.view.inputmethod.TextBoundsInfo";

    /**
     * Store the {@link TextBoundsInfo} into a {@link Bundle}. This method is used by
     * {@link RemoteInputConnectionImpl} to transfer the {@link TextBoundsInfo} from the editor
     * to IME.
     *
     * @see TextBoundsInfoResult
     * @see InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)
     * @hide
     */
    @NonNull
    public Bundle toBundle() {
        final Bundle bundle = new Bundle();
        bundle.putParcelable(TEXT_BOUNDS_INFO_KEY, this);
        return bundle;

    }

    /** @hide */
    @Nullable
    public static TextBoundsInfo createFromBundle(@Nullable Bundle bundle) {
        if (bundle == null) return null;
        return bundle.getParcelable(TEXT_BOUNDS_INFO_KEY, TextBoundsInfo.class);
    }

    /**
     * The builder class to create a {@link TextBoundsInfo} object.
     */
    public static final class Builder {
        private final float[] mMatrixValues = new float[9];
        private boolean mMatrixInitialized;
        private int mStart;
        private int mEnd;
        private float[] mCharacterBounds;
        private int[] mCharacterFlags;
        private int[] mCharacterBidiLevels;
        private SegmentFinder mLineSegmentFinder;
        private SegmentFinder mWordSegmentFinder;
        private SegmentFinder mGraphemeSegmentFinder;

        /** Clear all the parameters set on this {@link Builder} to reuse it. */
        @NonNull
        public Builder clear() {
            mMatrixInitialized = false;
            mStart = -1;
            mEnd = -1;
            mCharacterBounds = null;
            mCharacterFlags = null;
            mLineSegmentFinder = null;
            mWordSegmentFinder = null;
            mGraphemeSegmentFinder = null;
            return this;
        }

        /**
         * Sets the matrix that transforms local coordinates into screen coordinates.
         *
         * @param matrix transformation matrix from local coordinates into screen coordinates.
         * @throws NullPointerException if the given {@code matrix} is {@code null}.
         */
        @NonNull
        public Builder setMatrix(@NonNull Matrix matrix) {
            Objects.requireNonNull(matrix).getValues(mMatrixValues);
            mMatrixInitialized = true;
            return this;
        }

        /**
         * Set the start and end index of the {@link TextBoundsInfo}. It's the range of the
         * characters whose information is available in the {@link TextBoundsInfo}.
         *
         * @param start the start index of the {@link TextBoundsInfo}, inclusive.
         * @param end the end index of the {@link TextBoundsInfo}, exclusive.
         * @throws IllegalArgumentException if the given {@code start} or {@code end} is negative,
         * or {@code end} is smaller than the {@code start}.
         */
        @NonNull
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public Builder setStartAndEnd(@IntRange(from = 0) int start, @IntRange(from = 0) int end) {
            Preconditions.checkArgument(start >= 0);
            Preconditions.checkArgumentInRange(start, 0, end, "start");
            mStart = start;
            mEnd = end;
            return this;
        }

        /**
         * Set the characters bounds, in the coordinates of the editor. <br/>
         *
         * The given array should be divided into groups of four where each element represents
         * left, top, right and bottom of the character bounds respectively.
         * The bounds of the i-th character in the editor should be stored at index
         * 4 * (i - start). The length of the given array must equal to 4 * (end - start). <br/>
         *
         * Sometimes multiple characters in a single grapheme are rendered as one symbol on the
         * screen. So those characters only have one shared bounds. In this case, we recommend the
         * editor to assign all the width to the bounds of the first character in the grapheme,
         * and make the rest characters' bounds zero-width. <br/>
         *
         * For example, the string "'0xD83D' '0xDE00'" is rendered as one grapheme - a grinning face
         * emoji. If the bounds of the grapheme is: Rect(5, 10, 15, 20), the character bounds of the
         * string should be: [ Rect(5, 10, 15, 20), Rect(15, 10, 15, 20) ].
         *
         * @param characterBounds the array of the flattened character bounds.
         * @throws NullPointerException if the given {@code characterBounds} is {@code null}.
         */
        @NonNull
        public Builder setCharacterBounds(@NonNull float[] characterBounds) {
            mCharacterBounds = Objects.requireNonNull(characterBounds);
            return this;
        }

        /**
         * Set the flags of the characters. The flags of the i-th character in the editor is stored
         * at index (i - start). The length of the given array must equal to (end - start).
         * The flags contain the following information:
         * <ul>
         *     <li>The {@link #FLAG_CHARACTER_WHITESPACE} flag, indicating the character is a
         *     whitespace. </li>
         *     <li>The {@link #FLAG_CHARACTER_LINEFEED} flag, indicating the character is a
         *     linefeed. </li>
         *     <li>The {@link #FLAG_CHARACTER_PUNCTUATION} flag, indicating the character is a
         *     punctuation. </li>
         *     <li>The {@link #FLAG_LINE_IS_RTL} flag, indicating the line this character belongs to
         *     is RTL. All all character in the same line must have the same line direction. Check
         *     {@link #getLineSegmentFinder()} for more information of line boundaries. </li>
         * </ul>
         *
         * @param characterFlags the array of the character's flags.
         * @throws NullPointerException if the given {@code characterFlags} is {@code null}.
         * @throws IllegalArgumentException if the given {@code characterFlags} contains invalid
         * flags.
         *
         * @see #getCharacterFlags(int)
         */
        @NonNull
        public Builder setCharacterFlags(@NonNull int[] characterFlags) {
            Objects.requireNonNull(characterFlags);
            for (int characterFlag : characterFlags) {
                if ((characterFlag & (~KNOWN_CHARACTER_FLAGS)) != 0) {
                    throw new IllegalArgumentException("characterFlags contains invalid flags.");
                }
            }
            mCharacterFlags = characterFlags;
            return this;
        }

        /**
         * Set the BiDi levels for the character. The bidiLevel of the i-th character in the editor
         * is stored at index (i - start). The length of the given array must equal to
         * (end - start). <br/>
         *
         * BiDi level is defined by
         * <a href="https://unicode.org/reports/tr9/#Basic_Display_Algorithm" >the unicode
         * bidirectional algorithm </a>. One can determine whether a character's direction is
         * right-to-left (RTL) or left-to-right (LTR) by checking the last bit of the BiDi level.
         * If it's 1, the character is RTL, otherwise the character is LTR. The BiDi level of a
         * character must be in the range of [0, 125].
         * @param characterBidiLevels the array of the character's BiDi level.
         *
         * @throws NullPointerException if the given {@code characterBidiLevels} is {@code null}.
         * @throws IllegalArgumentException if the given {@code characterBidiLevels} contains an
         * element that's out of the range [0, 125].
         *
         * @see #getCharacterBidiLevel(int)
         */
        @NonNull
        public Builder setCharacterBidiLevel(@NonNull int[] characterBidiLevels) {
            Objects.requireNonNull(characterBidiLevels);
            for (int index = 0; index < characterBidiLevels.length; ++index) {
                Preconditions.checkArgumentInRange(characterBidiLevels[index], 0, 125,
                        "bidiLevels[" + index + "]");
            }
            mCharacterBidiLevels = characterBidiLevels;
            return this;
        }

        /**
         * Set the {@link SegmentFinder} that locates the grapheme cluster boundaries. Grapheme is
         * defined in <a href="https://unicode.org/reports/tr29/#Grapheme_Cluster_Boundaries">
         * the unicode annex #29: unicode text segmentation<a/>. It's a user-perspective character.
         * And it's usually the minimal unit for selection, backspace, deletion etc. <br/>
         *
         * Please note that only the grapheme segments within the range from start to end will
         * be available to the IME. The remaining information will be discarded during serialization
         * for better performance.
         *
         * @param graphemeSegmentFinder the {@link SegmentFinder} that locates the grapheme cluster
         *                              boundaries.
         * @throws NullPointerException if the given {@code graphemeSegmentFinder} is {@code null}.
         *
         * @see #getGraphemeSegmentFinder()
         * @see SegmentFinder
         * @see SegmentFinder.DefaultSegmentFinder
         */
        @NonNull
        public Builder setGraphemeSegmentFinder(@NonNull SegmentFinder graphemeSegmentFinder) {
            mGraphemeSegmentFinder = Objects.requireNonNull(graphemeSegmentFinder);
            return this;
        }

        /**
         * Set the {@link SegmentFinder} that locates the word boundaries. <br/>
         *
         * Please note that only the word segments within the range from start to end will
         * be available to the IME. The remaining information will be discarded during serialization
         * for better performance.
         * @param wordSegmentFinder set the {@link SegmentFinder} that locates the word boundaries.
         * @throws NullPointerException if the given {@code wordSegmentFinder} is {@code null}.
         *
         * @see #getWordSegmentFinder()
         * @see SegmentFinder
         * @see SegmentFinder.DefaultSegmentFinder
         */
        @NonNull
        public Builder setWordSegmentFinder(@NonNull SegmentFinder wordSegmentFinder) {
            mWordSegmentFinder = Objects.requireNonNull(wordSegmentFinder);
            return this;
        }

        /**
         * Set the {@link SegmentFinder} that locates the line boundaries. Aside from the hard
         * breaks in the text, it should also locate the soft line breaks added by the editor.
         * It is expected that the characters within the same line is rendered on the same baseline.
         * (Except for some text formatted as subscript and superscript.) <br/>
         *
         * Please note that only the line segments within the range from start to end will
         * be available to the IME. The remaining information will be discarded during serialization
         * for better performance.
         * @param lineSegmentFinder set the {@link SegmentFinder} that locates the line boundaries.
         * @throws NullPointerException if the given {@code lineSegmentFinder} is {@code null}.
         *
         * @see #getLineSegmentFinder()
         * @see SegmentFinder
         * @see SegmentFinder.DefaultSegmentFinder
         */
        @NonNull
        public Builder setLineSegmentFinder(@NonNull SegmentFinder lineSegmentFinder) {
            mLineSegmentFinder = Objects.requireNonNull(lineSegmentFinder);
            return this;
        }

        /**
         * Create the {@link TextBoundsInfo} using the parameters in this {@link Builder}.
         *
         * @throws IllegalStateException in the following conditions:
         * <ul>
         *     <li>if the {@code start} or {@code end} is not set.</li>
         *     <li>if the {@code matrix} is not set.</li>
         *     <li>if {@code characterBounds} is not set or its length doesn't equal to
         *     4 * ({@code end} - {@code start}).</li>
         *     <li>if the {@code characterFlags} is not set or its length doesn't equal to
         *     ({@code end} - {@code start}).</li>
         *     <li>if {@code graphemeSegmentFinder}, {@code wordSegmentFinder} or
         *     {@code lineSegmentFinder} is not set.</li>
         *     <li>if characters in the same line has inconsistent {@link #FLAG_LINE_IS_RTL}
         *     flag.</li>
         * </ul>
         */
        @NonNull
        public TextBoundsInfo build() {
            if (mStart < 0 || mEnd < 0) {
                throw new IllegalStateException("Start and end must be set.");
            }

            if (!mMatrixInitialized) {
                throw new IllegalStateException("Matrix must be set.");
            }

            if (mCharacterBounds == null) {
                throw new IllegalStateException("CharacterBounds must be set.");
            }

            if (mCharacterFlags == null) {
                throw new IllegalStateException("CharacterFlags must be set.");
            }

            if (mCharacterBidiLevels == null) {
                throw new IllegalStateException("CharacterBidiLevel must be set.");
            }

            if (mCharacterBounds.length != 4 * (mEnd - mStart)) {
                throw new IllegalStateException("The length of characterBounds doesn't match the "
                        + "length of the given start and end."
                        + " Expected length: " + (4 * (mEnd - mStart))
                        + " characterBounds length: " + mCharacterBounds.length);
            }
            if (mCharacterFlags.length != mEnd - mStart) {
                throw new IllegalStateException("The length of characterFlags doesn't match the "
                        + "length of the given start and end."
                        + " Expected length: " + (mEnd - mStart)
                        + " characterFlags length: " + mCharacterFlags.length);
            }
            if (mCharacterBidiLevels.length != mEnd - mStart) {
                throw new IllegalStateException("The length of characterBidiLevels doesn't match"
                        + " the length of the given start and end."
                        + " Expected length: " + (mEnd - mStart)
                        + " characterFlags length: " + mCharacterBidiLevels.length);
            }
            if (mGraphemeSegmentFinder == null) {
                throw new IllegalStateException("GraphemeSegmentFinder must be set.");
            }
            if (mWordSegmentFinder == null) {
                throw new IllegalStateException("WordSegmentFinder must be set.");
            }
            if (mLineSegmentFinder == null) {
                throw new IllegalStateException("LineSegmentFinder must be set.");
            }

            if (!isLineDirectionFlagConsistent(mCharacterFlags, mLineSegmentFinder, mStart, mEnd)) {
                throw new IllegalStateException("characters in the same line must have the same "
                        + "FLAG_LINE_IS_RTL flag value.");
            }
            return new TextBoundsInfo(this);
        }
    }

    /**
     * Encode the segment start and end positions in {@link SegmentFinder} to a flags array.
     *
     * For example:
     * Text: "A BC DE"
     * Input:
     *     start: 2, end: 7                                     // substring "BC DE"
     *     SegmentFinder: segment ranges = [(2, 4), (5, 7)]     // a word break iterator
     *     flags: [0x0000, 0x0000, 0x0080, 0x0000, 0x0000, 0x0000] // 0x0080 is whitespace
     *     segmentStartFlag: 0x0100
     *     segmentEndFlag: 0x0200
     * Output:
     *     flags: [0x0100, 0x0000, 0x0280, 0x0100, 0x0000, 0x0200]
     *  The index 2 and 5 encode segment starts, the index 4 and 7 encode a segment end.
     *
     * @param flags the flags array to receive the results.
     * @param segmentStartFlag the flag used to encode the segment start.
     * @param segmentEndFlag the flag used to encode the segment end.
     * @param start the start index of the encoded range, inclusive.
     * @param end the end index of the encoded range, inclusive.
     * @param segmentFinder the SegmentFinder to be encoded.
     *
     * @see #decodeSegmentFinder(int[], int, int, int, int)
     */
    private static void encodeSegmentFinder(@NonNull int[] flags, int segmentStartFlag,
            int segmentEndFlag, int start, int end, @NonNull SegmentFinder segmentFinder) {
        if (end - start + 1 != flags.length) {
            throw new IllegalStateException("The given flags array must have the same length as"
                    + " the given range. flags length: " + flags.length
                    + " range: [" + start + ", " + end + "]");
        }

        int segmentEnd = segmentFinder.nextEndBoundary(start);
        if (segmentEnd == SegmentFinder.DONE) return;
        int segmentStart = segmentFinder.previousStartBoundary(segmentEnd);

        while (segmentEnd != SegmentFinder.DONE && segmentEnd <= end) {
            if (segmentStart >= start) {
                flags[segmentStart - start] |= segmentStartFlag;
                flags[segmentEnd - start] |= segmentEndFlag;
            }
            segmentStart = segmentFinder.nextStartBoundary(segmentStart);
            segmentEnd = segmentFinder.nextEndBoundary(segmentEnd);
        }
    }

    /**
     * Decode a {@link SegmentFinder} from a flags array.
     *
     * For example:
     * Text: "A BC DE"
     * Input:
     *     start: 2, end: 7                                     // substring "BC DE"
     *     flags: [0x0100, 0x0000, 0x0280, 0x0100, 0x0000, 0x0200]
     *     segmentStartFlag: 0x0100
     *     segmentEndFlag: 0x0200
     * Output:
     *     SegmentFinder: segment ranges = [(2, 4), (5, 7)]
     *
     * @param flags the flags array to decode the SegmentFinder.
     * @param segmentStartFlag the flag to decode a segment start.
     * @param segmentEndFlag the flag to decode a segment end.
     * @param start the start index of the interested range, inclusive.
     * @param end the end index of the interested range, inclusive.
     *
     * @see #encodeSegmentFinder(int[], int, int, int, int, SegmentFinder)
     */
    private static SegmentFinder decodeSegmentFinder(int[] flags, int segmentStartFlag,
            int segmentEndFlag, int start, int end) {
        if (end - start + 1 != flags.length) {
            throw new IllegalStateException("The given flags array must have the same length as"
                    + " the given range. flags length: " + flags.length
                    + " range: [" + start + ", " + end + "]");
        }
        int[] breaks = ArrayUtils.newUnpaddedIntArray(10);
        int count = 0;
        for (int offset = 0; offset < flags.length; ++offset) {
            if ((flags[offset] & segmentStartFlag) == segmentStartFlag) {
                breaks = GrowingArrayUtils.append(breaks, count++, start + offset);
            }
            if ((flags[offset] & segmentEndFlag) == segmentEndFlag) {
                breaks = GrowingArrayUtils.append(breaks, count++, start + offset);
            }
        }
        return new SegmentFinder.DefaultSegmentFinder(Arrays.copyOf(breaks, count));
    }

    /**
     * Check whether the {@link #FLAG_LINE_IS_RTL} is the same for characters in the same line.
     * @return true if all characters in the same line has the same {@link #FLAG_LINE_IS_RTL} flag.
     */
    private static boolean isLineDirectionFlagConsistent(int[] characterFlags,
            SegmentFinder lineSegmentFinder, int start, int end) {
        int segmentEnd = lineSegmentFinder.nextEndBoundary(start);
        if (segmentEnd == SegmentFinder.DONE) return true;
        int segmentStart = lineSegmentFinder.previousStartBoundary(segmentEnd);

        while (segmentStart != SegmentFinder.DONE && segmentStart < end) {
            final int lineStart = Math.max(segmentStart, start);
            final int lineEnd = Math.min(segmentEnd, end);
            final boolean lineIsRtl = (characterFlags[lineStart - start] & FLAG_LINE_IS_RTL) != 0;
            for (int index = lineStart + 1; index < lineEnd; ++index) {
                final int flags = characterFlags[index - start];
                final boolean characterLineIsRtl = (flags & FLAG_LINE_IS_RTL) != 0;
                if (characterLineIsRtl != lineIsRtl) {
                    return false;
                }
            }

            segmentStart = lineSegmentFinder.nextStartBoundary(segmentStart);
            segmentEnd = lineSegmentFinder.nextEndBoundary(segmentEnd);
        }
        return true;
    }
}
