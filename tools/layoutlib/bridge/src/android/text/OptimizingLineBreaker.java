/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.NonNull;
import android.text.Primitive.PrimitiveType;
import android.text.StaticLayout.LineBreaks;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import static android.text.Primitive.PrimitiveType.PENALTY_INFINITY;


// Based on the native implementation of OptimizingLineBreaker in
// frameworks/base/core/jni/android_text_StaticLayout.cpp revision b808260
/**
 * A more complex version of line breaking where we try to prevent the right edge from being too
 * jagged.
 */
public class OptimizingLineBreaker extends LineBreaker {

    public OptimizingLineBreaker(@NonNull List<Primitive> primitives, @NonNull LineWidth lineWidth,
            @NonNull TabStops tabStops) {
        super(primitives, lineWidth, tabStops);
    }

    @Override
    public void computeBreaks(@NonNull LineBreaks breakInfo) {
        int numBreaks = mPrimitives.size();
        assert numBreaks > 0;
        if (numBreaks == 1) {
            // This can be true only if it's an empty paragraph.
            Primitive p = mPrimitives.get(0);
            assert p.type == PrimitiveType.PENALTY;
            breakInfo.breaks = new int[]{0};
            breakInfo.widths = new float[]{p.width};
            breakInfo.flags = new int[]{0};
            return;
        }
        Node[] opt = new Node[numBreaks];
        opt[0] = new Node(-1, 0, 0, 0, false);
        opt[numBreaks - 1] = new Node(-1, 0, 0, 0, false);

        ArrayList<Integer> active = new ArrayList<Integer>();
        active.add(0);
        int lastBreak = 0;
        for (int i = 0; i < numBreaks; i++) {
            Primitive p = mPrimitives.get(i);
            if (p.type == PrimitiveType.PENALTY) {
                boolean finalBreak = (i + 1 == numBreaks);
                Node bestBreak = null;

                for (ListIterator<Integer> it = active.listIterator(); it.hasNext();
                        /* incrementing done in loop */) {
                    int pos = it.next();
                    int lines = opt[pos].mPrevCount;
                    float maxWidth = mLineWidth.getLineWidth(lines);
                    // we have to compute metrics every time --
                    // we can't really pre-compute this stuff and just deal with breaks
                    // because of the way tab characters work, this makes it computationally
                    // harder, but this way, we can still optimize while treating tab characters
                    // correctly
                    LineMetrics lineMetrics = computeMetrics(pos, i);
                    if (lineMetrics.mPrintedWidth <= maxWidth) {
                        float demerits = computeDemerits(maxWidth, lineMetrics.mPrintedWidth,
                                finalBreak, p.penalty) + opt[pos].mDemerits;
                        if (bestBreak == null || demerits < bestBreak.mDemerits) {
                            if (bestBreak == null) {
                                bestBreak = new Node(pos, opt[pos].mPrevCount + 1, demerits,
                                        lineMetrics.mPrintedWidth, lineMetrics.mHasTabs);
                            } else {
                                bestBreak.mPrev = pos;
                                bestBreak.mPrevCount = opt[pos].mPrevCount + 1;
                                bestBreak.mDemerits = demerits;
                                bestBreak.mWidth = lineMetrics.mPrintedWidth;
                                bestBreak.mHasTabs = lineMetrics.mHasTabs;
                            }
                        }
                    } else {
                        it.remove();
                    }
                }
                if (p.penalty == -PENALTY_INFINITY) {
                    active.clear();
                }
                if (bestBreak != null) {
                    opt[i] = bestBreak;
                    active.add(i);
                    lastBreak = i;
                }
                if (active.isEmpty()) {
                    // we can't give up!
                    LineMetrics lineMetrics = new LineMetrics();
                    int lines = opt[lastBreak].mPrevCount;
                    float maxWidth = mLineWidth.getLineWidth(lines);
                    int breakIndex = desperateBreak(lastBreak, numBreaks, maxWidth, lineMetrics);
                    opt[breakIndex] = new Node(lastBreak, lines + 1, 0 /*doesn't matter*/,
                            lineMetrics.mWidth, lineMetrics.mHasTabs);
                    active.add(breakIndex);
                    lastBreak = breakIndex;
                    i = breakIndex; // incremented by i++
                }
            }
        }

        int idx = numBreaks - 1;
        int count = opt[idx].mPrevCount;
        resize(breakInfo, count);
        while (opt[idx].mPrev != -1) {
            count--;
            assert count >=0;

            breakInfo.breaks[count] = mPrimitives.get(idx).location;
            breakInfo.widths[count] = opt[idx].mWidth;
            breakInfo.flags [count] = opt[idx].mHasTabs ? TAB_MASK : 0;
            idx = opt[idx].mPrev;
        }
    }

    private static void resize(LineBreaks lineBreaks, int size) {
        if (lineBreaks.breaks.length == size) {
            return;
        }
        int[] breaks = new int[size];
        float[] widths = new float[size];
        int[] flags = new int[size];

        int toCopy = Math.min(size, lineBreaks.breaks.length);
        System.arraycopy(lineBreaks.breaks, 0, breaks, 0, toCopy);
        System.arraycopy(lineBreaks.widths, 0, widths, 0, toCopy);
        System.arraycopy(lineBreaks.flags, 0, flags, 0, toCopy);

        lineBreaks.breaks = breaks;
        lineBreaks.widths = widths;
        lineBreaks.flags = flags;
    }

    @NonNull
    private LineMetrics computeMetrics(int start, int end) {
        boolean f = false;
        float w = 0, pw = 0;
        for (int i = start; i < end; i++) {
            Primitive p = mPrimitives.get(i);
            if (p.type == PrimitiveType.BOX || p.type == PrimitiveType.GLUE) {
                w += p.width;
                if (p.type == PrimitiveType.BOX) {
                    pw = w;
                }
            } else if (p.type == PrimitiveType.VARIABLE) {
                w = mTabStops.width(w);
                f = true;
            }
        }
        return new LineMetrics(w, pw, f);
    }

    private static float computeDemerits(float maxWidth, float width, boolean finalBreak,
            float penalty) {
        float deviation = finalBreak ? 0 : maxWidth - width;
        return (deviation * deviation) + penalty;
    }

    /**
     * @return the last break position or -1 if failed.
     */
    @SuppressWarnings("ConstantConditions")  // method too complex to be analyzed.
    private int desperateBreak(int start, int limit, float maxWidth,
            @NonNull LineMetrics lineMetrics) {
        float w = 0, pw = 0;
        boolean breakFound = false;
        int breakIndex = 0, firstTabIndex = Integer.MAX_VALUE;
        for (int i = start; i < limit; i++) {
            Primitive p = mPrimitives.get(i);

            if (p.type == PrimitiveType.BOX || p.type == PrimitiveType.GLUE) {
                w += p.width;
                if (p.type == PrimitiveType.BOX) {
                    pw = w;
                }
            } else if (p.type == PrimitiveType.VARIABLE) {
                w = mTabStops.width(w);
                firstTabIndex = Math.min(firstTabIndex, i);
            }

            if (pw > maxWidth && breakFound) {
                break;
            }

            // must make progress
            if (i > start &&
                    (p.type == PrimitiveType.PENALTY || p.type == PrimitiveType.WORD_BREAK)) {
                breakFound = true;
                breakIndex = i;
            }
        }

        if (breakFound) {
            lineMetrics.mWidth = w;
            lineMetrics.mPrintedWidth = pw;
            lineMetrics.mHasTabs = (start <= firstTabIndex && firstTabIndex < breakIndex);
            return breakIndex;
        } else {
            return -1;
        }
    }

    private static class LineMetrics {
        /** Actual width of the line. */
        float mWidth;
        /** Width of the line minus trailing whitespace. */
        float mPrintedWidth;
        boolean mHasTabs;

        public LineMetrics() {
        }

        public LineMetrics(float width, float printedWidth, boolean hasTabs) {
            mWidth = width;
            mPrintedWidth = printedWidth;
            mHasTabs = hasTabs;
        }
    }

    /**
     * A struct to store the info about a break.
     */
    @SuppressWarnings("SpellCheckingInspection")  // For the word struct.
    private static class Node {
        // -1 for the first node.
        int mPrev;
        // number of breaks so far.
        int mPrevCount;
        float mDemerits;
        float mWidth;
        boolean mHasTabs;

        public Node(int prev, int prevCount, float demerits, float width, boolean hasTabs) {
            mPrev = prev;
            mPrevCount = prevCount;
            mDemerits = demerits;
            mWidth = width;
            mHasTabs = hasTabs;
        }
    }
}
