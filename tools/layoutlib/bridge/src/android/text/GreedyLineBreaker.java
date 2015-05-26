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

import static android.text.Primitive.PrimitiveType.PENALTY_INFINITY;

// Based on the native implementation of GreedyLineBreaker in
// frameworks/base/core/jni/android_text_StaticLayout.cpp revision b808260
public class GreedyLineBreaker extends LineBreaker {

    public GreedyLineBreaker(@NonNull List<Primitive> primitives, @NonNull LineWidth lineWidth,
            @NonNull TabStops tabStops) {
        super(primitives, lineWidth, tabStops);
    }

    @Override
    public void computeBreaks(@NonNull LineBreaks lineBreaks) {
        BreakInfo breakInfo = new BreakInfo();
        int lineNum = 0;
        float width = 0, printedWidth = 0;
        boolean breakFound = false, goodBreakFound = false;
        int breakIndex = 0, goodBreakIndex = 0;
        float breakWidth = 0, goodBreakWidth = 0;
        int firstTabIndex = Integer.MAX_VALUE;

        float maxWidth = mLineWidth.getLineWidth(lineNum);

        int numPrimitives = mPrimitives.size();
        // greedily fit as many characters as possible on each line
        // loop over all primitives, and choose the best break point
        // (if possible, a break point without splitting a word)
        // after going over the maximum length
        for (int i = 0; i < numPrimitives; i++) {
            Primitive p = mPrimitives.get(i);

            // update the current line width
            if (p.type == PrimitiveType.BOX || p.type == PrimitiveType.GLUE) {
                width += p.width;
                if (p.type == PrimitiveType.BOX) {
                    printedWidth = width;
                }
            } else if (p.type == PrimitiveType.VARIABLE) {
                width = mTabStops.width(width);
                // keep track of first tab character in the region we are examining
                // so we can determine whether or not a line contains a tab
                firstTabIndex = Math.min(firstTabIndex, i);
            }

            // find the best break point for the characters examined so far
            if (printedWidth > maxWidth) {
                //noinspection StatementWithEmptyBody
                if (breakFound || goodBreakFound) {
                    if (goodBreakFound) {
                        // a true line break opportunity existed in the characters examined so far,
                        // so there is no need to split a word
                        i = goodBreakIndex; // no +1 because of i++
                        lineNum++;
                        maxWidth = mLineWidth.getLineWidth(lineNum);
                        breakInfo.mBreaksList.add(mPrimitives.get(goodBreakIndex).location);
                        breakInfo.mWidthsList.add(goodBreakWidth);
                        breakInfo.mFlagsList.add(firstTabIndex < goodBreakIndex);
                        firstTabIndex = Integer.MAX_VALUE;
                    } else {
                        // must split a word because there is no other option
                        i = breakIndex; // no +1 because of i++
                        lineNum++;
                        maxWidth = mLineWidth.getLineWidth(lineNum);
                        breakInfo.mBreaksList.add(mPrimitives.get(breakIndex).location);
                        breakInfo.mWidthsList.add(breakWidth);
                        breakInfo.mFlagsList.add(firstTabIndex < breakIndex);
                        firstTabIndex = Integer.MAX_VALUE;
                    }
                    printedWidth = width = 0;
                    goodBreakFound = breakFound = false;
                    goodBreakWidth = breakWidth = 0;
                    continue;
                } else {
                    // no choice, keep going... must make progress by putting at least one
                    // character on a line, even if part of that character is cut off --
                    // there is no other option
                }
            }

            // update possible break points
            if (p.type == PrimitiveType.PENALTY &&
                    p.penalty < PENALTY_INFINITY) {
                // this does not handle penalties with width

                // handle forced line break
                if (p.penalty == -PENALTY_INFINITY) {
                    lineNum++;
                    maxWidth = mLineWidth.getLineWidth(lineNum);
                    breakInfo.mBreaksList.add(p.location);
                    breakInfo.mWidthsList.add(printedWidth);
                    breakInfo.mFlagsList.add(firstTabIndex < i);
                    firstTabIndex = Integer.MAX_VALUE;
                    printedWidth = width = 0;
                    goodBreakFound = breakFound = false;
                    goodBreakWidth = breakWidth = 0;
                    continue;
                }
                if (i > breakIndex && (printedWidth <= maxWidth || !breakFound)) {
                    breakFound = true;
                    breakIndex = i;
                    breakWidth = printedWidth;
                }
                if (i > goodBreakIndex && printedWidth <= maxWidth) {
                    goodBreakFound = true;
                    goodBreakIndex = i;
                    goodBreakWidth = printedWidth;
                }
            } else if (p.type == PrimitiveType.WORD_BREAK) {
                // only do this if necessary -- we don't want to break words
                // when possible, but sometimes it is unavoidable
                if (i > breakIndex && (printedWidth <= maxWidth || !breakFound)) {
                    breakFound = true;
                    breakIndex = i;
                    breakWidth = printedWidth;
                }
            }
        }

        if (breakFound || goodBreakFound) {
            // output last break if there are more characters to output
            if (goodBreakFound) {
                breakInfo.mBreaksList.add(mPrimitives.get(goodBreakIndex).location);
                breakInfo.mWidthsList.add(goodBreakWidth);
                breakInfo.mFlagsList.add(firstTabIndex < goodBreakIndex);
            } else {
                breakInfo.mBreaksList.add(mPrimitives.get(breakIndex).location);
                breakInfo.mWidthsList.add(breakWidth);
                breakInfo.mFlagsList.add(firstTabIndex < breakIndex);
            }
        }
        breakInfo.copyTo(lineBreaks);
    }

    private static class BreakInfo {
        List<Integer> mBreaksList = new ArrayList<Integer>();
        List<Float> mWidthsList = new ArrayList<Float>();
        List<Boolean> mFlagsList = new ArrayList<Boolean>();

        public void copyTo(LineBreaks lineBreaks) {
            if (lineBreaks.breaks.length != mBreaksList.size()) {
                lineBreaks.breaks = new int[mBreaksList.size()];
                lineBreaks.widths = new float[mWidthsList.size()];
                lineBreaks.flags = new int[mFlagsList.size()];
            }

            int i = 0;
            for (int b : mBreaksList) {
                lineBreaks.breaks[i] = b;
                i++;
            }
            i = 0;
            for (float b : mWidthsList) {
                lineBreaks.widths[i] = b;
                i++;
            }
            i = 0;
            for (boolean b : mFlagsList) {
                lineBreaks.flags[i] = b ? TAB_MASK : 0;
                i++;
            }

            mBreaksList = null;
            mWidthsList = null;
            mFlagsList = null;
        }
    }
}
