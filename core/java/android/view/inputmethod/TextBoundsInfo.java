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
import android.text.Layout;
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
 * {@link #getStartIndex()} to {@link #getEndIndex()}. It's intended to be used by IME as a
 * supplementary API to support handwriting gestures.
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
     * Set the given {@link android.graphics.Matrix} to be the transformation
     * matrix that is to be applied other positional data in this class.
     */
    @NonNull
    public void getMatrix(@NonNull Matrix matrix) {
        Objects.requireNonNull(matrix);
        matrix.setValues(mMatrixValues);
    }

    /**
     * Returns the index of the first character whose bounds information is available in this
     * {@link TextBoundsInfo}, inclusive.
     *
     * @see Builder#setStartAndEnd(int, int)
     */
    public int getStartIndex() {
        return mStart;
    }

    /**
     * Returns the index of the last character whose bounds information is available in this
     * {@link TextBoundsInfo}, exclusive.
     *
     * @see Builder#setStartAndEnd(int, int)
     */
    public int getEndIndex() {
        return mEnd;
    }

    /**
     * Set the bounds of the character at the given {@code index} to the given {@link RectF}, in
     * the coordinates of the editor.
     *
     * @param index the index of the queried character.
     * @param bounds the {@link RectF} used to receive the result.
     *
     * @throws IndexOutOfBoundsException if the given {@code index} is out of the range from
     * the {@code start} to the {@code end}.
     */
    @NonNull
    public void getCharacterBounds(int index, @NonNull RectF bounds) {
        if (index < mStart || index >= mEnd) {
            throw new IndexOutOfBoundsException("Index is out of the bounds of "
                    + "[" + mStart + ", " + mEnd + ").");
        }
        final int offset = 4 * (index - mStart);
        bounds.set(mCharacterBounds[offset], mCharacterBounds[offset + 1],
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
     * Return the index of the closest character to the given position.
     * It's similar to the text layout API {@link Layout#getOffsetForHorizontal(int, float)}.
     * And it's mainly used to find the cursor index (the index of the character before which the
     * cursor should be placed) for the given position. It's guaranteed that the returned index is
     * a grapheme break. Check {@link #getGraphemeSegmentFinder()} for more information.
     *
     * <p>It's assumed that the editor lays out text in horizontal lines from top to bottom and each
     * line is laid out according to the display algorithm specified in
     * <a href="https://unicode.org/reports/tr9/#Basic_Display_Algorithm"> unicode bidirectional
     * algorithm</a>.
     * </p>
     *
     * <p> This method won't check the text ranges whose line information is missing. For example,
     * the {@link TextBoundsInfo}'s range is from index 5 to 15. If the associated
     * {@link SegmentFinder} only identifies one line range from 7 to 12. Then this method
     * won't check the text in the ranges of [5, 7) and [12, 15).
     * </p>
     *
     * <p> Under the following conditions, this method will return -1 indicating that no valid
     * character is found:
     * <ul>
     *   <li> The given {@code y} coordinate is above the first line or below the last line (the
     *   first line or the last line is identified by the {@link SegmentFinder} returned from
     *   {@link #getLineSegmentFinder()}). </li>
     *   <li> There is no character in this {@link TextBoundsInfo}. </li>
     * </ul>
     * </p>
     *
     * @param x the x coordinates of the interested location, in the editor's coordinates.
     * @param y the y coordinates of the interested location, in the editor's coordinates.
     * @return the index of the character whose position is closest to the given location. It will
     * return -1 if it can't find a character.
     *
     * @see Layout#getOffsetForHorizontal(int, float)
     */
    public int getOffsetForPosition(float x, float y) {
        final int[] lineRange = new int[2];
        final RectF lineBounds = new RectF();
        getLineInfo(y, lineRange, lineBounds);
        // No line is found, return -1;
        if (lineRange[0] == -1 || lineRange[1] == -1) return -1;
        final int lineStart = lineRange[0];
        final int lineEnd = lineRange[1];

        final boolean lineEndsWithLinefeed =
                (getCharacterFlags(lineEnd - 1) & FLAG_CHARACTER_LINEFEED) != 0;

        // Consider the following 2 cases:
        // Case 1:
        //   Text: "AB\nCD"
        //   Layout: AB
        //           CD
        // Case 2:
        //   Text: "ABCD"
        //   Layout: AB
        //           CD
        // If user wants to insert a 'X' character at the end of the first line:
        //   In case 1, 'X' is inserted before the last character '\n'.
        //   In case 2, 'X' is inserted after the last character 'B'.
        // So if a line ends with linefeed, it shouldn't check the cursor position after the last
        // character.
        final int lineLimit;
        if (lineEndsWithLinefeed) {
            lineLimit = lineEnd;
        } else {
            lineLimit = lineEnd + 1;
        }
        // Point graphemeStart to the start of the first grapheme segment intersects with the line.
        int graphemeStart = mGraphemeSegmentFinder.nextEndBoundary(lineStart);
        // The grapheme information is missing.
        if (graphemeStart == SegmentFinder.DONE) return -1;
        graphemeStart = mGraphemeSegmentFinder.previousStartBoundary(graphemeStart);

        int target = -1;
        float minDistance = Float.MAX_VALUE;
        while (graphemeStart != SegmentFinder.DONE && graphemeStart < lineLimit) {
            if (graphemeStart >= lineStart) {
                float cursorPosition = getCursorHorizontalPosition(graphemeStart, lineStart,
                        lineEnd, lineBounds.left, lineBounds.right);
                final float distance = Math.abs(cursorPosition - x);
                if (distance < minDistance) {
                    minDistance = distance;
                    target = graphemeStart;
                }
            }
            graphemeStart = mGraphemeSegmentFinder.nextStartBoundary(graphemeStart);
        }

        return target;
    }

    /**
     * Whether the primary position at the given index is the previous character's trailing
     * position. <br/>
     *
     * For LTR character, trailing position is its right edge. For RTL character, trailing position
     * is its left edge.
     *
     * The primary position is defined as the position of a newly inserted character with the
     * context direction at the given offset. In contrast, the secondary position is the position
     * of a newly inserted character with the context's opposite direction at the given offset.
     *
     * In Android, the trailing position is used for primary position when the direction run after
     * the given index has a higher level than the current direction run.
     *
     * <p>
     * For example:
     * (L represents LTR character, and R represents RTL character. The number is the index)
     * <pre>
     * input text:          L0 L1 L2 R3 R4 R5 L6 L7 L8
     * render result:       L0 L1 L2 R5 R4 R3 L6 L7 L8
     * BiDi Run:            [ Run 0 ][ Run 1 ][ Run 2 ]
     * BiDi Level:          0  0  0  1  1  1  0  0  0
     * </pre>
     *
     * The index 3 is a BiDi transition point, the cursor can be placed either after L2 or before
     * R3. Because the bidi level of run 1 is higher than the run 0, this method returns true. And
     * the cursor should be placed after L2.
     * <pre>
     * render result:       L0 L1 L2 R5 R4 R3 L6 L7 L8
     * position after L2:           |
     * position before R3:                   |
     * result position:             |
     * </pre>
     *
     * The index 6 is also a Bidi transition point, the 2 possible cursor positions are exactly the
     * same as index 3. However, since the bidi level of run 2 is higher than the run 1, this
     * method returns false. And the cursor should be placed before L6.
     * <pre>
     * render result:       L0 L1 L2 R5 R4 R3 L6 L7 L8
     * position after R5:           |
     * position before L6:                   |
     * result position:                      |
     * </pre>
     *
     * This method helps guarantee that the cursor index and the cursor position forms a one to
     * one relation.
     * </p>
     *
     * @param offset the offset of the character in front of which the cursor is placed. It must be
     *              the start index of a grapheme. And it must be in the range from lineStart to
     *              lineEnd. An offset equal to lineEnd is allowed. It indicates that the cursor is
     *              placed at the end of current line instead of the start of the following line.
     * @param lineStart the start index of the line that index belongs to, inclusive.
     * @param lineEnd the end index of the line that index belongs to, exclusive.
     * @return true if primary position is the trailing position of the previous character.
     *
     * @see #getCursorHorizontalPosition(int, int, int, float, float)
     */
    private boolean primaryIsTrailingPrevious(int offset, int lineStart, int lineEnd) {
        final int bidiLevel;
        if (offset < lineEnd) {
            bidiLevel = getCharacterBidiLevel(offset);
        } else {
            // index equals to lineEnd, use line's BiDi level for the BiDi run.
            boolean lineIsRtl =
                    (getCharacterFlags(offset - 1) & FLAG_LINE_IS_RTL) == FLAG_LINE_IS_RTL;
            bidiLevel = lineIsRtl ? 1 : 0;
        }
        final int bidiLevelBefore;
        if (offset > lineStart) {
            // Here it assumes index is always the start of a grapheme. And (index - 1) belongs to
            // the previous grapheme.
            bidiLevelBefore = getCharacterBidiLevel(offset - 1);
        } else {
            // index equals to lineStart, use line's BiDi level for previous BiDi run.
            boolean lineIsRtl =
                    (getCharacterFlags(offset) & FLAG_LINE_IS_RTL) == FLAG_LINE_IS_RTL;
            bidiLevelBefore = lineIsRtl ? 1 : 0;
        }
        return bidiLevelBefore < bidiLevel;
    }

    /**
     * Returns the x coordinates of the cursor at the given index. (The index of the character
     * before which the cursor should be placed.)
     *
     * @param index the character index before which the cursor is placed. It must be the start
     *              index of a grapheme. It must be in the range from lineStart to lineEnd.
     *              An index equal to lineEnd is allowed. It indicates that the cursor is
     *              placed at the end of current line instead of the start of the following line.
     * @param lineStart start index of the line that index belongs to, inclusive.
     * @param lineEnd end index of the line that index belongs, exclusive.
     * @return the x coordinates of the cursor at the given index,
     *
     * @see #primaryIsTrailingPrevious(int, int, int)
     */
    private float getCursorHorizontalPosition(int index, int lineStart, int lineEnd,
            float lineLeft, float lineRight) {
        Preconditions.checkArgumentInRange(index, lineStart, lineEnd, "index");
        final boolean lineIsRtl = (getCharacterFlags(lineStart) & FLAG_LINE_IS_RTL) != 0;
        final boolean isPrimaryIsTrailingPrevious =
                primaryIsTrailingPrevious(index, lineStart, lineEnd);

        // The index of the character used to compute the cursor position.
        final int targetIndex;
        // Whether to use the start position of the character.
        // For LTR character start is the left edge. For RTL character, start is the right edge.
        final boolean isStart;
        if (isPrimaryIsTrailingPrevious) {
            // (index - 1) belongs to the previous line(if any), return the line start position.
            if (index <= lineStart) {
                return lineIsRtl ? lineRight : lineLeft;
            }
            targetIndex = index - 1;
            isStart = false;
        } else {
            // index belongs to the next line(if any), return the line end position.
            if (index >= lineEnd) {
                return lineIsRtl ? lineLeft : lineRight;
            }
            targetIndex = index;
            isStart = true;
        }

        // The BiDi level is odd when the character is RTL.
        final boolean isRtl = (getCharacterBidiLevel(targetIndex) & 1) != 0;
        final int offset = targetIndex - mStart;
        // If the character is RTL, the start is the right edge. Otherwise, the start is the
        // left edge:
        //  +-----------------------+
        //  |       | start | end   |
        //  |-------+-------+-------|
        //  | RTL   | right | left  |
        //  |-------+-------+-------|
        //  | LTR   | left  | right |
        //  +-------+-------+-------+
        return (isRtl != isStart) ? mCharacterBounds[4 * offset] : mCharacterBounds[4 * offset + 2];
    }

    /**
     * Return the minimal rectangle that contains all the characters in the given range.
     *
     * @param start the start index of the given range, inclusive.
     * @param end the end index of the given range, exclusive.
     * @param rectF the {@link RectF} to receive the bounds.
     */
    private void getBoundsForRange(int start, int end, @NonNull RectF rectF) {
        Preconditions.checkArgumentInRange(start, mStart, mEnd - 1, "start");
        Preconditions.checkArgumentInRange(end, start, mEnd, "end");
        if (end <= start) {
            rectF.setEmpty();
            return;
        }

        rectF.left = Float.MAX_VALUE;
        rectF.top = Float.MAX_VALUE;
        rectF.right = Float.MIN_VALUE;
        rectF.bottom = Float.MIN_VALUE;
        for (int index = start; index < end; ++index) {
            final int offset = index - mStart;
            rectF.left = Math.min(rectF.left, mCharacterBounds[4 * offset]);
            rectF.top = Math.min(rectF.top, mCharacterBounds[4 * offset + 1]);
            rectF.right = Math.max(rectF.right, mCharacterBounds[4 * offset + 2]);
            rectF.bottom = Math.max(rectF.bottom, mCharacterBounds[4 * offset + 3]);
        }
    }

    /**
     * Return the character range and bounds of the closest line to the given {@code y} coordinate,
     * in the editor's local coordinates.
     *
     * If the given y is above the first line or below the last line -1 will be returned for line
     * start and end.
     *
     * This method assumes that the lines are laid out from the top to bottom.
     *
     * @param y the y coordinates used to search for the line.
     * @param characterRange a two element array used to receive the character range of the line.
     *                       If no valid line is found -1 will be returned for both start and end.
     * @param bounds {@link RectF} to receive the line bounds result, nullable. If given, it can
     *                            still be modified even if no valid line is found.
     */
    private void getLineInfo(float y, @NonNull int[] characterRange, @Nullable RectF bounds) {
        characterRange[0] = -1;
        characterRange[1] = -1;

        // Starting from the first line.
        int currentLineEnd = mLineSegmentFinder.nextEndBoundary(mStart);
        if (currentLineEnd == SegmentFinder.DONE) return;
        int currentLineStart = mLineSegmentFinder.previousStartBoundary(currentLineEnd);

        float top = Float.MAX_VALUE;
        float bottom = Float.MIN_VALUE;
        float minDistance = Float.MAX_VALUE;
        final RectF currentLineBounds = new RectF();
        while (currentLineStart != SegmentFinder.DONE && currentLineStart < mEnd) {
            final int lineStartInRange = Math.max(mStart, currentLineStart);
            final int lineEndInRange = Math.min(mEnd, currentLineEnd);
            getBoundsForRange(lineStartInRange, lineEndInRange, currentLineBounds);

            top = Math.min(currentLineBounds.top, top);
            bottom = Math.max(currentLineBounds.bottom, bottom);

            final float distance = verticalDistance(currentLineBounds, y);

            if (distance == 0f) {
                characterRange[0] = currentLineStart;
                characterRange[1] = currentLineEnd;
                if (bounds != null) {
                    bounds.set(currentLineBounds);
                }
                return;
            }

            if (distance < minDistance) {
                minDistance = distance;
                characterRange[0] = currentLineStart;
                characterRange[1] = currentLineEnd;
                if (bounds != null) {
                    bounds.set(currentLineBounds);
                }
            }
            if (y < bounds.top) break;
            currentLineStart = mLineSegmentFinder.nextStartBoundary(currentLineStart);
            currentLineEnd = mLineSegmentFinder.nextEndBoundary(currentLineEnd);
        }

        // y is above the first line or below the last line. The founded line is still invalid,
        // clear the result.
        if (y < top || y > bottom) {
            characterRange[0] = -1;
            characterRange[1] = -1;
            if (bounds != null) {
                bounds.setEmpty();
            }
        }
    }

    /**
     * Finds the range of text which is inside the specified rectangle area. This method is a
     * counterpart of the
     * {@link Layout#getRangeForRect(RectF, SegmentFinder, Layout.TextInclusionStrategy)}.
     *
     * <p>It's assumed that the editor lays out text in horizontal lines from top to bottom
     * and each line is laid out according to the display algorithm specified in
     * <a href="https://unicode.org/reports/tr9/#Basic_Display_Algorithm"> unicode bidirectional
     * algorithm</a>.
     * </p>
     *
     * <p> This method won't check the text ranges whose line information is missing. For example,
     * the {@link TextBoundsInfo}'s range is from index 5 to 15. If the associated line
     * {@link SegmentFinder} only identifies one line range from 7 to 12. Then this method
     * won't check the text in the ranges of [5, 7) and [12, 15).
     * </p>
     *
     * @param area area for which the text range will be found
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered as a
     *     text segment
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *          specified area
     * @return the text range stored in a two element int array. The first element is the
     * start (inclusive) of the text range, and the second element is the end (exclusive) character
     * offsets of the text range, or null if there are no text segments inside the area.
     *
     * @see Layout#getRangeForRect(RectF, SegmentFinder, Layout.TextInclusionStrategy)
     */
    @Nullable
    public int[] getRangeForRect(@NonNull RectF area, @NonNull SegmentFinder segmentFinder,
            @NonNull Layout.TextInclusionStrategy inclusionStrategy) {
        int lineEnd = mLineSegmentFinder.nextEndBoundary(mStart);
        // Line information is missing.
        if (lineEnd == SegmentFinder.DONE) return null;
        int lineStart = mLineSegmentFinder.previousStartBoundary(lineEnd);

        int start = -1;
        while (lineStart != SegmentFinder.DONE && start == -1) {
            start = getStartForRectWithinLine(lineStart, lineEnd, area, segmentFinder,
                    inclusionStrategy);
            lineStart = mLineSegmentFinder.nextStartBoundary(lineStart);
            lineEnd = mLineSegmentFinder.nextEndBoundary(lineEnd);
        }

        // Can't find the start index; the specified contains no valid segment.
        if (start == -1) return null;

        lineStart = mLineSegmentFinder.previousStartBoundary(mEnd);
        // Line information is missing.
        if (lineStart == SegmentFinder.DONE) return null;
        lineEnd = mLineSegmentFinder.nextEndBoundary(lineStart);
        int end = -1;
        while (lineEnd > start && end == -1) {
            end = getEndForRectWithinLine(lineStart, lineEnd, area, segmentFinder,
                    inclusionStrategy);
            lineStart = mLineSegmentFinder.previousStartBoundary(lineStart);
            lineEnd = mLineSegmentFinder.previousEndBoundary(lineEnd);
        }

        // We've already found start, end is guaranteed to be found at this point.
        start = segmentFinder.previousStartBoundary(start + 1);
        end = segmentFinder.nextEndBoundary(end - 1);
        return new int[] { start, end };
    }

    /**
     * Find the start character index of the first text segments within a line inside the specified
     * {@code area}.
     *
     * @param lineStart the start of this line, inclusive .
     * @param lineEnd the end of this line, exclusive.
     * @param area the area inside which the text segments will be found.
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered a
     *                      text segment.
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *                          specified area.
     * @return the start index of the first segment in the area.
     */
    private int getStartForRectWithinLine(int lineStart, int lineEnd, @NonNull RectF area,
            @NonNull SegmentFinder segmentFinder,
            @NonNull Layout.TextInclusionStrategy inclusionStrategy) {
        if (lineStart >= lineEnd) return -1;

        int runStart = lineStart;
        int runLevel = -1;
        // Check the BiDi runs and search for the start index.
        for (int index = lineStart; index < lineEnd; ++index) {
            final int level = getCharacterBidiLevel(index);
            if (level != runLevel) {
                final int start = getStartForRectWithinRun(runStart, index, area, segmentFinder,
                        inclusionStrategy);
                if (start != -1) {
                    return start;
                }

                runStart = index;
                runLevel = level;
            }
        }
        return getStartForRectWithinRun(runStart, lineEnd, area, segmentFinder, inclusionStrategy);
    }

    /**
     * Find the start character index of the first text segments within the directional run inside
     * the specified {@code area}.
     *
     * @param runStart the start of this directional run, inclusive.
     * @param runEnd the end of this directional run, exclusive.
     * @param area the area inside which the text segments will be found.
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered a
     *                      text segment.
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *                          specified area.
     * @return the start index of the first segment in the area.
     */
    private int getStartForRectWithinRun(int runStart, int runEnd, @NonNull RectF area,
            @NonNull SegmentFinder segmentFinder,
            @NonNull Layout.TextInclusionStrategy inclusionStrategy) {
        if (runStart >= runEnd) return -1;

        int segmentEndOffset = segmentFinder.nextEndBoundary(runStart);
        // No segment is found in run.
        if (segmentEndOffset == SegmentFinder.DONE) return -1;
        int segmentStartOffset = segmentFinder.previousStartBoundary(segmentEndOffset);

        final RectF segmentBounds = new RectF();
        while (segmentStartOffset != SegmentFinder.DONE && segmentStartOffset < runEnd) {
            final int start = Math.max(runStart, segmentStartOffset);
            final int end = Math.min(runEnd, segmentEndOffset);
            getBoundsForRange(start, end, segmentBounds);
            // Find the first segment inside the area, return the start.
            if (inclusionStrategy.isSegmentInside(segmentBounds, area)) return start;

            segmentStartOffset = segmentFinder.nextStartBoundary(segmentStartOffset);
            segmentEndOffset = segmentFinder.nextEndBoundary(segmentEndOffset);
        }
        return -1;
    }

    /**
     * Find the end character index of the last text segments within a line inside the specified
     * {@code area}.
     *
     * @param lineStart the start of this line, inclusive .
     * @param lineEnd the end of this line, exclusive.
     * @param area the area inside which the text segments will be found.
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered a
     *                      text segment.
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *                          specified area.
     * @return the end index of the last segment in the area.
     */
    private int getEndForRectWithinLine(int lineStart, int lineEnd, @NonNull RectF area,
            @NonNull SegmentFinder segmentFinder,
            @NonNull Layout.TextInclusionStrategy inclusionStrategy) {
        if (lineStart >= lineEnd) return -1;
        lineStart = Math.max(lineStart, mStart);
        lineEnd = Math.min(lineEnd, mEnd);

        // The exclusive run end index.
        int runEnd = lineEnd;
        int runLevel = -1;
        // Check the BiDi runs backwards and search for the end index.
        for (int index = lineEnd - 1; index >= lineStart; --index) {
            final int level = getCharacterBidiLevel(index);
            if (level != runLevel) {
                final int end = getEndForRectWithinRun(index + 1, runEnd, area, segmentFinder,
                        inclusionStrategy);
                if (end != -1) return end;

                runEnd = index + 1;
                runLevel = level;
            }
        }
        return getEndForRectWithinRun(lineStart, runEnd, area, segmentFinder, inclusionStrategy);
    }

    /**
     * Find the end character index of the last text segments within the directional run inside the
     * specified {@code area}.
     *
     * @param runStart the start of this directional run, inclusive.
     * @param runEnd the end of this directional run, exclusive.
     * @param area the area inside which the text segments will be found.
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered a
     *                      text segment.
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *                          specified area.
     * @return the end index of the last segment in the area.
     */
    private int getEndForRectWithinRun(int runStart, int runEnd, @NonNull RectF area,
            @NonNull SegmentFinder segmentFinder,
            @NonNull Layout.TextInclusionStrategy inclusionStrategy) {
        if (runStart >= runEnd) return -1;

        int segmentStart = segmentFinder.previousStartBoundary(runEnd);
        // No segment is found before the runEnd.
        if (segmentStart == SegmentFinder.DONE) return -1;
        int segmentEnd = segmentFinder.nextEndBoundary(segmentStart);

        final RectF segmentBounds = new RectF();
        while (segmentEnd != SegmentFinder.DONE && segmentEnd > runStart) {
            final int start = Math.max(runStart, segmentStart);
            final int end = Math.min(runEnd, segmentEnd);
            getBoundsForRange(start, end, segmentBounds);
            // Find the last segment inside the area, return the end.
            if (inclusionStrategy.isSegmentInside(segmentBounds, area)) return end;

            segmentStart = segmentFinder.previousStartBoundary(segmentStart);
            segmentEnd = segmentFinder.previousEndBoundary(segmentEnd);
        }
        return -1;
    }

    /**
     * Get the vertical distance from the {@code pointF} to the {@code rectF}. It's useful to find
     * the corresponding line for a given point.
     */
    private static float verticalDistance(@NonNull RectF rectF, float y) {
        if (rectF.top <= y && y < rectF.bottom) {
            return 0f;
        }
        if (y < rectF.top) {
            return rectF.top - y;
        }
        return y - rectF.bottom;
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
        private int mStart = -1;
        private int mEnd = -1;
        private float[] mCharacterBounds;
        private int[] mCharacterFlags;
        private int[] mCharacterBidiLevels;
        private SegmentFinder mLineSegmentFinder;
        private SegmentFinder mWordSegmentFinder;
        private SegmentFinder mGraphemeSegmentFinder;

        /**
         * Create a builder for {@link TextBoundsInfo}.
         * @param start the start index of the {@link TextBoundsInfo}, inclusive.
         * @param end the end index of the {@link TextBoundsInfo}, exclusive.
         * @throws IllegalArgumentException if the given {@code start} or {@code end} is negative,
         * or {@code end} is smaller than the {@code start}.
         */
        public Builder(int start, int end) {
            setStartAndEnd(start, end);
        }

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
         * @see SegmentFinder.PrescribedSegmentFinder
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
         * @see SegmentFinder.PrescribedSegmentFinder
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
         * @see SegmentFinder.PrescribedSegmentFinder
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
        return new SegmentFinder.PrescribedSegmentFinder(Arrays.copyOf(breaks, count));
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
