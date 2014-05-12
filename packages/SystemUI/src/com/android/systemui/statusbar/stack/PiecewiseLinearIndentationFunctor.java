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
 * limitations under the License
 */

package com.android.systemui.statusbar.stack;

import java.util.ArrayList;

/**
 * A Functor which interpolates the stack distance linearly based on base values.
 * The base values are based on an interpolation between a linear function and a
 * quadratic function
 */
public class PiecewiseLinearIndentationFunctor extends StackIndentationFunctor {

    private final ArrayList<Float> mBaseValues;
    private final float mLinearPart;

    /**
     * @param maxItemsInStack The maximum number of items which should be visible at the same time,
     *                        i.e the function returns totalTransitionDistance for the element with
     *                        index maxItemsInStack
     * @param peekSize The visual appearance of this is how far the cards in the stack peek
     *                 out below the top card and it is measured in real pixels.
     *                 Note that the visual appearance does not necessarily always correspond to
     *                 the actual visual distance below the top card but is a maximum,
     *                 achieved when the next card just starts transitioning into the stack and
     *                 the stack is full.
     *                 If distanceToPeekStart is 0, we directly start at the peek, otherwise the
     *                 first element transitions between 0 and distanceToPeekStart.
     *                 Visualization:
     *           ---------------------------------------------------   ---
     *          |                                                   |   |
     *          |                  FIRST ITEM                       |   | <- distanceToPeekStart
     *          |                                                   |   |
     *          |---------------------------------------------------|  ---  ---
     *          |__________________SECOND ITEM______________________|        |  <- peekSize
     *          |===================================================|       _|_
     *
     * @param distanceToPeekStart The distance to the start of the peak.
     * @param linearPart The interpolation factor between the linear and the quadratic amount taken.
     *                   This factor must be somewhere in [0 , 1]
     */
    PiecewiseLinearIndentationFunctor(int maxItemsInStack,
                                      int peekSize,
                                      int distanceToPeekStart,
                                      float linearPart) {
        super(maxItemsInStack, peekSize, distanceToPeekStart);
        mBaseValues = new ArrayList<Float>(maxItemsInStack+1);
        initBaseValues();
        mLinearPart = linearPart;
    }

    private void initBaseValues() {
        int sumOfSquares = getSumOfSquares(mMaxItemsInStack-1);
        int totalWeight = 0;
        mBaseValues.add(0.0f);
        for (int i = 0; i < mMaxItemsInStack - 1; i++) {
            totalWeight += (mMaxItemsInStack - i - 1) * (mMaxItemsInStack - i - 1);
            mBaseValues.add((float) totalWeight / sumOfSquares);
        }
    }

    /**
     * Get the sum of squares up to and including n, i.e sum(i * i, 1, n)
     *
     * @param n the maximum square to include
     * @return
     */
    private int getSumOfSquares(int n) {
        return n * (n + 1) * (2 * n + 1) / 6;
    }

    @Override
    public float getValue(float itemsBefore) {
        if (mStackStartsAtPeek) {
            // We directly start at the stack, so no initial interpolation.
            itemsBefore++;
        }
        if (itemsBefore < 0) {
            return 0;
        } else if (itemsBefore >= mMaxItemsInStack) {
            return mTotalTransitionDistance;
        }
        int below = (int) itemsBefore;
        float partialIn = itemsBefore - below;

        if (below == 0) {
            return mDistanceToPeekStart * partialIn;
        } else {
            float result = mDistanceToPeekStart;
            float progress = mBaseValues.get(below - 1) * (1 - partialIn)
                    + mBaseValues.get(below) * partialIn;
            result += (progress * (1 - mLinearPart)
                    + (itemsBefore - 1) / (mMaxItemsInStack - 1)  * mLinearPart) * mPeekSize;
            return result;
        }
    }
}
