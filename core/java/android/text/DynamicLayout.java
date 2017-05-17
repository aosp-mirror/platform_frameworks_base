/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text;

import android.graphics.Paint;
import android.graphics.Rect;
import android.text.style.ReplacementSpan;
import android.text.style.UpdateLayout;
import android.text.style.WrapTogetherSpan;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.lang.ref.WeakReference;

/**
 * DynamicLayout is a text layout that updates itself as the text is edited.
 * <p>This is used by widgets to control text layout. You should not need
 * to use this class directly unless you are implementing your own widget
 * or custom display object, or need to call
 * {@link android.graphics.Canvas#drawText(java.lang.CharSequence, int, int, float, float, android.graphics.Paint)
 *  Canvas.drawText()} directly.</p>
 */
public class DynamicLayout extends Layout
{
    private static final int PRIORITY = 128;
    private static final int BLOCK_MINIMUM_CHARACTER_LENGTH = 400;

    /**
     * Make a layout for the specified text that will be updated as
     * the text is changed.
     */
    public DynamicLayout(CharSequence base,
                         TextPaint paint,
                         int width, Alignment align,
                         float spacingmult, float spacingadd,
                         boolean includepad) {
        this(base, base, paint, width, align, spacingmult, spacingadd,
             includepad);
    }

    /**
     * Make a layout for the transformed text (password transformation
     * being the primary example of a transformation)
     * that will be updated as the base text is changed.
     */
    public DynamicLayout(CharSequence base, CharSequence display,
                         TextPaint paint,
                         int width, Alignment align,
                         float spacingmult, float spacingadd,
                         boolean includepad) {
        this(base, display, paint, width, align, spacingmult, spacingadd,
             includepad, null, 0);
    }

    /**
     * Make a layout for the transformed text (password transformation
     * being the primary example of a transformation)
     * that will be updated as the base text is changed.
     * If ellipsize is non-null, the Layout will ellipsize the text
     * down to ellipsizedWidth.
     */
    public DynamicLayout(CharSequence base, CharSequence display,
                         TextPaint paint,
                         int width, Alignment align,
                         float spacingmult, float spacingadd,
                         boolean includepad,
                         TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        this(base, display, paint, width, align, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                spacingmult, spacingadd, includepad,
                StaticLayout.BREAK_STRATEGY_SIMPLE, StaticLayout.HYPHENATION_FREQUENCY_NONE,
                Layout.JUSTIFICATION_MODE_NONE, ellipsize, ellipsizedWidth);
    }

    /**
     * Make a layout for the transformed text (password transformation
     * being the primary example of a transformation)
     * that will be updated as the base text is changed.
     * If ellipsize is non-null, the Layout will ellipsize the text
     * down to ellipsizedWidth.
     * *
     * *@hide
     */
    public DynamicLayout(CharSequence base, CharSequence display,
                         TextPaint paint,
                         int width, Alignment align, TextDirectionHeuristic textDir,
                         float spacingmult, float spacingadd,
                         boolean includepad, int breakStrategy, int hyphenationFrequency,
                         int justificationMode, TextUtils.TruncateAt ellipsize,
                         int ellipsizedWidth) {
        super((ellipsize == null)
                ? display
                : (display instanceof Spanned)
                    ? new SpannedEllipsizer(display)
                    : new Ellipsizer(display),
              paint, width, align, textDir, spacingmult, spacingadd);

        mBase = base;
        mDisplay = display;

        if (ellipsize != null) {
            mInts = new PackedIntVector(COLUMNS_ELLIPSIZE);
            mEllipsizedWidth = ellipsizedWidth;
            mEllipsizeAt = ellipsize;
        } else {
            mInts = new PackedIntVector(COLUMNS_NORMAL);
            mEllipsizedWidth = width;
            mEllipsizeAt = null;
        }

        mObjects = new PackedObjectVector<Directions>(1);

        mIncludePad = includepad;
        mBreakStrategy = breakStrategy;
        mJustificationMode = justificationMode;
        mHyphenationFrequency = hyphenationFrequency;

        /*
         * This is annoying, but we can't refer to the layout until
         * superclass construction is finished, and the superclass
         * constructor wants the reference to the display text.
         *
         * This will break if the superclass constructor ever actually
         * cares about the content instead of just holding the reference.
         */
        if (ellipsize != null) {
            Ellipsizer e = (Ellipsizer) getText();

            e.mLayout = this;
            e.mWidth = ellipsizedWidth;
            e.mMethod = ellipsize;
            mEllipsize = true;
        }

        // Initial state is a single line with 0 characters (0 to 0),
        // with top at 0 and bottom at whatever is natural, and
        // undefined ellipsis.

        int[] start;

        if (ellipsize != null) {
            start = new int[COLUMNS_ELLIPSIZE];
            start[ELLIPSIS_START] = ELLIPSIS_UNDEFINED;
        } else {
            start = new int[COLUMNS_NORMAL];
        }

        Directions[] dirs = new Directions[] { DIRS_ALL_LEFT_TO_RIGHT };

        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int asc = fm.ascent;
        int desc = fm.descent;

        start[DIR] = DIR_LEFT_TO_RIGHT << DIR_SHIFT;
        start[TOP] = 0;
        start[DESCENT] = desc;
        mInts.insertAt(0, start);

        start[TOP] = desc - asc;
        mInts.insertAt(1, start);

        mObjects.insertAt(0, dirs);

        // Update from 0 characters to whatever the real text is
        reflow(base, 0, 0, base.length());

        if (base instanceof Spannable) {
            if (mWatcher == null)
                mWatcher = new ChangeWatcher(this);

            // Strip out any watchers for other DynamicLayouts.
            Spannable sp = (Spannable) base;
            ChangeWatcher[] spans = sp.getSpans(0, sp.length(), ChangeWatcher.class);
            for (int i = 0; i < spans.length; i++)
                sp.removeSpan(spans[i]);

            sp.setSpan(mWatcher, 0, base.length(),
                       Spannable.SPAN_INCLUSIVE_INCLUSIVE |
                       (PRIORITY << Spannable.SPAN_PRIORITY_SHIFT));
        }
    }

    private void reflow(CharSequence s, int where, int before, int after) {
        if (s != mBase)
            return;

        CharSequence text = mDisplay;
        int len = text.length();

        // seek back to the start of the paragraph

        int find = TextUtils.lastIndexOf(text, '\n', where - 1);
        if (find < 0)
            find = 0;
        else
            find = find + 1;

        {
            int diff = where - find;
            before += diff;
            after += diff;
            where -= diff;
        }

        // seek forward to the end of the paragraph

        int look = TextUtils.indexOf(text, '\n', where + after);
        if (look < 0)
            look = len;
        else
            look++; // we want the index after the \n

        int change = look - (where + after);
        before += change;
        after += change;

        // seek further out to cover anything that is forced to wrap together

        if (text instanceof Spanned) {
            Spanned sp = (Spanned) text;
            boolean again;

            do {
                again = false;

                Object[] force = sp.getSpans(where, where + after,
                                             WrapTogetherSpan.class);

                for (int i = 0; i < force.length; i++) {
                    int st = sp.getSpanStart(force[i]);
                    int en = sp.getSpanEnd(force[i]);

                    if (st < where) {
                        again = true;

                        int diff = where - st;
                        before += diff;
                        after += diff;
                        where -= diff;
                    }

                    if (en > where + after) {
                        again = true;

                        int diff = en - (where + after);
                        before += diff;
                        after += diff;
                    }
                }
            } while (again);
        }

        // find affected region of old layout

        int startline = getLineForOffset(where);
        int startv = getLineTop(startline);

        int endline = getLineForOffset(where + before);
        if (where + after == len)
            endline = getLineCount();
        int endv = getLineTop(endline);
        boolean islast = (endline == getLineCount());

        // generate new layout for affected text

        StaticLayout reflowed;
        StaticLayout.Builder b;

        synchronized (sLock) {
            reflowed = sStaticLayout;
            b = sBuilder;
            sStaticLayout = null;
            sBuilder = null;
        }

        if (reflowed == null) {
            reflowed = new StaticLayout(null);
            b = StaticLayout.Builder.obtain(text, where, where + after, getPaint(), getWidth());
        }

        b.setText(text, where, where + after)
                .setPaint(getPaint())
                .setWidth(getWidth())
                .setTextDirection(getTextDirectionHeuristic())
                .setLineSpacing(getSpacingAdd(), getSpacingMultiplier())
                .setEllipsizedWidth(mEllipsizedWidth)
                .setEllipsize(mEllipsizeAt)
                .setBreakStrategy(mBreakStrategy)
                .setHyphenationFrequency(mHyphenationFrequency)
                .setJustificationMode(mJustificationMode);
        reflowed.generate(b, false, true);
        int n = reflowed.getLineCount();
        // If the new layout has a blank line at the end, but it is not
        // the very end of the buffer, then we already have a line that
        // starts there, so disregard the blank line.

        if (where + after != len && reflowed.getLineStart(n - 1) == where + after)
            n--;

        // remove affected lines from old layout
        mInts.deleteAt(startline, endline - startline);
        mObjects.deleteAt(startline, endline - startline);

        // adjust offsets in layout for new height and offsets

        int ht = reflowed.getLineTop(n);
        int toppad = 0, botpad = 0;

        if (mIncludePad && startline == 0) {
            toppad = reflowed.getTopPadding();
            mTopPadding = toppad;
            ht -= toppad;
        }
        if (mIncludePad && islast) {
            botpad = reflowed.getBottomPadding();
            mBottomPadding = botpad;
            ht += botpad;
        }

        mInts.adjustValuesBelow(startline, START, after - before);
        mInts.adjustValuesBelow(startline, TOP, startv - endv + ht);

        // insert new layout

        int[] ints;

        if (mEllipsize) {
            ints = new int[COLUMNS_ELLIPSIZE];
            ints[ELLIPSIS_START] = ELLIPSIS_UNDEFINED;
        } else {
            ints = new int[COLUMNS_NORMAL];
        }

        Directions[] objects = new Directions[1];

        for (int i = 0; i < n; i++) {
            final int start = reflowed.getLineStart(i);
            ints[START] = start;
            ints[DIR] |= reflowed.getParagraphDirection(i) << DIR_SHIFT;
            ints[TAB] |= reflowed.getLineContainsTab(i) ? TAB_MASK : 0;

            int top = reflowed.getLineTop(i) + startv;
            if (i > 0)
                top -= toppad;
            ints[TOP] = top;

            int desc = reflowed.getLineDescent(i);
            if (i == n - 1)
                desc += botpad;

            ints[DESCENT] = desc;
            objects[0] = reflowed.getLineDirections(i);

            final int end = (i == n - 1) ? where + after : reflowed.getLineStart(i + 1);
            ints[HYPHEN] = reflowed.getHyphen(i) & HYPHEN_MASK;
            ints[MAY_PROTRUDE_FROM_TOP_OR_BOTTOM] |=
                    contentMayProtrudeFromLineTopOrBottom(text, start, end) ?
                            MAY_PROTRUDE_FROM_TOP_OR_BOTTOM_MASK : 0;

            if (mEllipsize) {
                ints[ELLIPSIS_START] = reflowed.getEllipsisStart(i);
                ints[ELLIPSIS_COUNT] = reflowed.getEllipsisCount(i);
            }

            mInts.insertAt(startline + i, ints);
            mObjects.insertAt(startline + i, objects);
        }

        updateBlocks(startline, endline - 1, n);

        b.finish();
        synchronized (sLock) {
            sStaticLayout = reflowed;
            sBuilder = b;
        }
    }

    private boolean contentMayProtrudeFromLineTopOrBottom(CharSequence text, int start, int end) {
        if (text instanceof Spanned) {
            final Spanned spanned = (Spanned) text;
            if (spanned.getSpans(start, end, ReplacementSpan.class).length > 0) {
                return true;
            }
        }
        // Spans other than ReplacementSpan can be ignored because line top and bottom are
        // disjunction of all tops and bottoms, although it's not optimal.
        final Paint paint = getPaint();
        paint.getTextBounds(text, start, end, mTempRect);
        final Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        return mTempRect.top < fm.top || mTempRect.bottom > fm.bottom;
    }

    /**
     * Create the initial block structure, cutting the text into blocks of at least
     * BLOCK_MINIMUM_CHARACTER_SIZE characters, aligned on the ends of paragraphs.
     */
    private void createBlocks() {
        int offset = BLOCK_MINIMUM_CHARACTER_LENGTH;
        mNumberOfBlocks = 0;
        final CharSequence text = mDisplay;

        while (true) {
            offset = TextUtils.indexOf(text, '\n', offset);
            if (offset < 0) {
                addBlockAtOffset(text.length());
                break;
            } else {
                addBlockAtOffset(offset);
                offset += BLOCK_MINIMUM_CHARACTER_LENGTH;
            }
        }

        // mBlockIndices and mBlockEndLines should have the same length
        mBlockIndices = new int[mBlockEndLines.length];
        for (int i = 0; i < mBlockEndLines.length; i++) {
            mBlockIndices[i] = INVALID_BLOCK_INDEX;
        }
    }

    /**
     * @hide
     */
    public ArraySet<Integer> getBlocksAlwaysNeedToBeRedrawn() {
        return mBlocksAlwaysNeedToBeRedrawn;
    }

    private void updateAlwaysNeedsToBeRedrawn(int blockIndex) {
        int startLine = blockIndex == 0 ? 0 : (mBlockEndLines[blockIndex - 1] + 1);
        int endLine = mBlockEndLines[blockIndex];
        for (int i = startLine; i <= endLine; i++) {
            if (getContentMayProtrudeFromTopOrBottom(i)) {
                if (mBlocksAlwaysNeedToBeRedrawn == null) {
                    mBlocksAlwaysNeedToBeRedrawn = new ArraySet<>();
                }
                mBlocksAlwaysNeedToBeRedrawn.add(blockIndex);
                return;
            }
        }
        if (mBlocksAlwaysNeedToBeRedrawn != null) {
            mBlocksAlwaysNeedToBeRedrawn.remove(blockIndex);
        }
    }

    /**
     * Create a new block, ending at the specified character offset.
     * A block will actually be created only if has at least one line, i.e. this offset is
     * not on the end line of the previous block.
     */
    private void addBlockAtOffset(int offset) {
        final int line = getLineForOffset(offset);
        if (mBlockEndLines == null) {
            // Initial creation of the array, no test on previous block ending line
            mBlockEndLines = ArrayUtils.newUnpaddedIntArray(1);
            mBlockEndLines[mNumberOfBlocks] = line;
            updateAlwaysNeedsToBeRedrawn(mNumberOfBlocks);
            mNumberOfBlocks++;
            return;
        }

        final int previousBlockEndLine = mBlockEndLines[mNumberOfBlocks - 1];
        if (line > previousBlockEndLine) {
            mBlockEndLines = GrowingArrayUtils.append(mBlockEndLines, mNumberOfBlocks, line);
            updateAlwaysNeedsToBeRedrawn(mNumberOfBlocks);
            mNumberOfBlocks++;
        }
    }

    /**
     * This method is called every time the layout is reflowed after an edition.
     * It updates the internal block data structure. The text is split in blocks
     * of contiguous lines, with at least one block for the entire text.
     * When a range of lines is edited, new blocks (from 0 to 3 depending on the
     * overlap structure) will replace the set of overlapping blocks.
     * Blocks are listed in order and are represented by their ending line number.
     * An index is associated to each block (which will be used by display lists),
     * this class simply invalidates the index of blocks overlapping a modification.
     *
     * @param startLine the first line of the range of modified lines
     * @param endLine the last line of the range, possibly equal to startLine, lower
     * than getLineCount()
     * @param newLineCount the number of lines that will replace the range, possibly 0
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void updateBlocks(int startLine, int endLine, int newLineCount) {
        if (mBlockEndLines == null) {
            createBlocks();
            return;
        }

        int firstBlock = -1;
        int lastBlock = -1;
        for (int i = 0; i < mNumberOfBlocks; i++) {
            if (mBlockEndLines[i] >= startLine) {
                firstBlock = i;
                break;
            }
        }
        for (int i = firstBlock; i < mNumberOfBlocks; i++) {
            if (mBlockEndLines[i] >= endLine) {
                lastBlock = i;
                break;
            }
        }
        final int lastBlockEndLine = mBlockEndLines[lastBlock];

        boolean createBlockBefore = startLine > (firstBlock == 0 ? 0 :
                mBlockEndLines[firstBlock - 1] + 1);
        boolean createBlock = newLineCount > 0;
        boolean createBlockAfter = endLine < mBlockEndLines[lastBlock];

        int numAddedBlocks = 0;
        if (createBlockBefore) numAddedBlocks++;
        if (createBlock) numAddedBlocks++;
        if (createBlockAfter) numAddedBlocks++;

        final int numRemovedBlocks = lastBlock - firstBlock + 1;
        final int newNumberOfBlocks = mNumberOfBlocks + numAddedBlocks - numRemovedBlocks;

        if (newNumberOfBlocks == 0) {
            // Even when text is empty, there is actually one line and hence one block
            mBlockEndLines[0] = 0;
            mBlockIndices[0] = INVALID_BLOCK_INDEX;
            mNumberOfBlocks = 1;
            return;
        }

        if (newNumberOfBlocks > mBlockEndLines.length) {
            int[] blockEndLines = ArrayUtils.newUnpaddedIntArray(
                    Math.max(mBlockEndLines.length * 2, newNumberOfBlocks));
            int[] blockIndices = new int[blockEndLines.length];
            System.arraycopy(mBlockEndLines, 0, blockEndLines, 0, firstBlock);
            System.arraycopy(mBlockIndices, 0, blockIndices, 0, firstBlock);
            System.arraycopy(mBlockEndLines, lastBlock + 1,
                    blockEndLines, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
            System.arraycopy(mBlockIndices, lastBlock + 1,
                    blockIndices, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
            mBlockEndLines = blockEndLines;
            mBlockIndices = blockIndices;
        } else if (numAddedBlocks + numRemovedBlocks != 0) {
            System.arraycopy(mBlockEndLines, lastBlock + 1,
                    mBlockEndLines, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
            System.arraycopy(mBlockIndices, lastBlock + 1,
                    mBlockIndices, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
        }

        if (numAddedBlocks + numRemovedBlocks != 0 && mBlocksAlwaysNeedToBeRedrawn != null) {
            final ArraySet<Integer> set = new ArraySet<>();
            for (int i = 0; i < mBlocksAlwaysNeedToBeRedrawn.size(); i++) {
                Integer block = mBlocksAlwaysNeedToBeRedrawn.valueAt(i);
                if (block > firstBlock) {
                    block += numAddedBlocks - numRemovedBlocks;
                }
                set.add(block);
            }
            mBlocksAlwaysNeedToBeRedrawn = set;
        }

        mNumberOfBlocks = newNumberOfBlocks;
        int newFirstChangedBlock;
        final int deltaLines = newLineCount - (endLine - startLine + 1);
        if (deltaLines != 0) {
            // Display list whose index is >= mIndexFirstChangedBlock is valid
            // but it needs to update its drawing location.
            newFirstChangedBlock = firstBlock + numAddedBlocks;
            for (int i = newFirstChangedBlock; i < mNumberOfBlocks; i++) {
                mBlockEndLines[i] += deltaLines;
            }
        } else {
            newFirstChangedBlock = mNumberOfBlocks;
        }
        mIndexFirstChangedBlock = Math.min(mIndexFirstChangedBlock, newFirstChangedBlock);

        int blockIndex = firstBlock;
        if (createBlockBefore) {
            mBlockEndLines[blockIndex] = startLine - 1;
            updateAlwaysNeedsToBeRedrawn(blockIndex);
            mBlockIndices[blockIndex] = INVALID_BLOCK_INDEX;
            blockIndex++;
        }

        if (createBlock) {
            mBlockEndLines[blockIndex] = startLine + newLineCount - 1;
            updateAlwaysNeedsToBeRedrawn(blockIndex);
            mBlockIndices[blockIndex] = INVALID_BLOCK_INDEX;
            blockIndex++;
        }

        if (createBlockAfter) {
            mBlockEndLines[blockIndex] = lastBlockEndLine + deltaLines;
            updateAlwaysNeedsToBeRedrawn(blockIndex);
            mBlockIndices[blockIndex] = INVALID_BLOCK_INDEX;
        }
    }

    /**
     * This method is used for test purposes only.
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setBlocksDataForTest(int[] blockEndLines, int[] blockIndices, int numberOfBlocks,
            int totalLines) {
        mBlockEndLines = new int[blockEndLines.length];
        mBlockIndices = new int[blockIndices.length];
        System.arraycopy(blockEndLines, 0, mBlockEndLines, 0, blockEndLines.length);
        System.arraycopy(blockIndices, 0, mBlockIndices, 0, blockIndices.length);
        mNumberOfBlocks = numberOfBlocks;
        while (mInts.size() < totalLines) {
            mInts.insertAt(mInts.size(), new int[COLUMNS_NORMAL]);
        }
    }

    /**
     * @hide
     */
    public int[] getBlockEndLines() {
        return mBlockEndLines;
    }

    /**
     * @hide
     */
    public int[] getBlockIndices() {
        return mBlockIndices;
    }

    /**
     * @hide
     */
    public int getBlockIndex(int index) {
        return mBlockIndices[index];
    }

    /**
     * @hide
     * @param index
     */
    public void setBlockIndex(int index, int blockIndex) {
        mBlockIndices[index] = blockIndex;
    }

    /**
     * @hide
     */
    public int getNumberOfBlocks() {
        return mNumberOfBlocks;
    }

    /**
     * @hide
     */
    public int getIndexFirstChangedBlock() {
        return mIndexFirstChangedBlock;
    }

    /**
     * @hide
     */
    public void setIndexFirstChangedBlock(int i) {
        mIndexFirstChangedBlock = i;
    }

    @Override
    public int getLineCount() {
        return mInts.size() - 1;
    }

    @Override
    public int getLineTop(int line) {
        return mInts.getValue(line, TOP);
    }

    @Override
    public int getLineDescent(int line) {
        return mInts.getValue(line, DESCENT);
    }

    @Override
    public int getLineStart(int line) {
        return mInts.getValue(line, START) & START_MASK;
    }

    @Override
    public boolean getLineContainsTab(int line) {
        return (mInts.getValue(line, TAB) & TAB_MASK) != 0;
    }

    @Override
    public int getParagraphDirection(int line) {
        return mInts.getValue(line, DIR) >> DIR_SHIFT;
    }

    @Override
    public final Directions getLineDirections(int line) {
        return mObjects.getValue(line, 0);
    }

    @Override
    public int getTopPadding() {
        return mTopPadding;
    }

    @Override
    public int getBottomPadding() {
        return mBottomPadding;
    }

    /**
     * @hide
     */
    @Override
    public int getHyphen(int line) {
        return mInts.getValue(line, HYPHEN) & HYPHEN_MASK;
    }

    private boolean getContentMayProtrudeFromTopOrBottom(int line) {
        return (mInts.getValue(line, MAY_PROTRUDE_FROM_TOP_OR_BOTTOM)
                & MAY_PROTRUDE_FROM_TOP_OR_BOTTOM_MASK) != 0;
    }

    @Override
    public int getEllipsizedWidth() {
        return mEllipsizedWidth;
    }

    private static class ChangeWatcher implements TextWatcher, SpanWatcher {
        public ChangeWatcher(DynamicLayout layout) {
            mLayout = new WeakReference<DynamicLayout>(layout);
        }

        private void reflow(CharSequence s, int where, int before, int after) {
            DynamicLayout ml = mLayout.get();

            if (ml != null)
                ml.reflow(s, where, before, after);
            else if (s instanceof Spannable)
                ((Spannable) s).removeSpan(this);
        }

        public void beforeTextChanged(CharSequence s, int where, int before, int after) {
            // Intentionally empty
        }

        public void onTextChanged(CharSequence s, int where, int before, int after) {
            reflow(s, where, before, after);
        }

        public void afterTextChanged(Editable s) {
            // Intentionally empty
        }

        public void onSpanAdded(Spannable s, Object o, int start, int end) {
            if (o instanceof UpdateLayout)
                reflow(s, start, end - start, end - start);
        }

        public void onSpanRemoved(Spannable s, Object o, int start, int end) {
            if (o instanceof UpdateLayout)
                reflow(s, start, end - start, end - start);
        }

        public void onSpanChanged(Spannable s, Object o, int start, int end, int nstart, int nend) {
            if (o instanceof UpdateLayout) {
                reflow(s, start, end - start, end - start);
                reflow(s, nstart, nend - nstart, nend - nstart);
            }
        }

        private WeakReference<DynamicLayout> mLayout;
    }

    @Override
    public int getEllipsisStart(int line) {
        if (mEllipsizeAt == null) {
            return 0;
        }

        return mInts.getValue(line, ELLIPSIS_START);
    }

    @Override
    public int getEllipsisCount(int line) {
        if (mEllipsizeAt == null) {
            return 0;
        }

        return mInts.getValue(line, ELLIPSIS_COUNT);
    }

    private CharSequence mBase;
    private CharSequence mDisplay;
    private ChangeWatcher mWatcher;
    private boolean mIncludePad;
    private boolean mEllipsize;
    private int mEllipsizedWidth;
    private TextUtils.TruncateAt mEllipsizeAt;
    private int mBreakStrategy;
    private int mHyphenationFrequency;
    private int mJustificationMode;

    private PackedIntVector mInts;
    private PackedObjectVector<Directions> mObjects;

    /**
     * Value used in mBlockIndices when a block has been created or recycled and indicating that its
     * display list needs to be re-created.
     * @hide
     */
    public static final int INVALID_BLOCK_INDEX = -1;
    // Stores the line numbers of the last line of each block (inclusive)
    private int[] mBlockEndLines;
    // The indices of this block's display list in TextView's internal display list array or
    // INVALID_BLOCK_INDEX if this block has been invalidated during an edition
    private int[] mBlockIndices;
    // Set of blocks that always need to be redrawn.
    private ArraySet<Integer> mBlocksAlwaysNeedToBeRedrawn;
    // Number of items actually currently being used in the above 2 arrays
    private int mNumberOfBlocks;
    // The first index of the blocks whose locations are changed
    private int mIndexFirstChangedBlock;

    private int mTopPadding, mBottomPadding;

    private Rect mTempRect = new Rect();

    private static StaticLayout sStaticLayout = null;
    private static StaticLayout.Builder sBuilder = null;

    private static final Object[] sLock = new Object[0];

    // START, DIR, and TAB share the same entry.
    private static final int START = 0;
    private static final int DIR = START;
    private static final int TAB = START;
    private static final int TOP = 1;
    private static final int DESCENT = 2;
    // HYPHEN and MAY_PROTRUDE_FROM_TOP_OR_BOTTOM share the same entry.
    private static final int HYPHEN = 3;
    private static final int MAY_PROTRUDE_FROM_TOP_OR_BOTTOM = HYPHEN;
    private static final int COLUMNS_NORMAL = 4;

    private static final int ELLIPSIS_START = 4;
    private static final int ELLIPSIS_COUNT = 5;
    private static final int COLUMNS_ELLIPSIZE = 6;

    private static final int START_MASK = 0x1FFFFFFF;
    private static final int DIR_SHIFT  = 30;
    private static final int TAB_MASK   = 0x20000000;
    private static final int HYPHEN_MASK = 0xFF;
    private static final int MAY_PROTRUDE_FROM_TOP_OR_BOTTOM_MASK = 0x100;

    private static final int ELLIPSIS_UNDEFINED = 0x80000000;
}
