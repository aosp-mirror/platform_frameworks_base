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

/**
 * A functor which can be queried for offset given the number of items before it.
 */
public abstract class StackIndentationFunctor {

    protected final int mTotalTransitionDistance;
    protected final int mDistanceToPeekStart;
    protected int mMaxItemsInStack;
    protected int mPeekSize;
    protected boolean mStackStartsAtPeek;

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
     *                 If totalTransitionDistance is equal to this, we directly start at the peek,
     *                 otherwise the first element transitions between 0 and
     *                 totalTransitionDistance - peekSize.
     *                 Visualization:
     *           ---------------------------------------------------   ---
     *          |                                                   |   |
     *          |                  FIRST ITEM                       |   | <- totalTransitionDistance
     *          |                                                   |   |
     *          |---------------------------------------------------|   |   ---
     *          |__________________SECOND ITEM______________________|   |    |  <- peekSize
     *          |===================================================|  _|_  _|_
     *
     * @param totalTransitionDistance The total transition distance an element has to go through
     */
    StackIndentationFunctor(int maxItemsInStack, int peekSize, int totalTransitionDistance) {
        mTotalTransitionDistance = totalTransitionDistance;
        mDistanceToPeekStart = mTotalTransitionDistance - peekSize;
        mStackStartsAtPeek = mDistanceToPeekStart == 0;
        mMaxItemsInStack = maxItemsInStack;
        mPeekSize = peekSize;

    }

    public void setPeekSize(int mPeekSize) {
        this.mPeekSize = mPeekSize;
    }

    /**
     * Gets the offset of this Functor given a the quantity of items before it
     *
     * @param itemsBefore how many items are already in the stack before this element
     * @return the offset
     */
    public abstract float getValue(float itemsBefore);
}
