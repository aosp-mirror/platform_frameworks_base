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

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import com.android.systemui.R;

/**
 * The Algorithm of the {@link com.android.systemui.statusbar.stack
 * .NotificationStackScrollLayout} which can be queried for {@link com.android.systemui.statusbar
 * .stack.StackScrollState}
 */
public class StackScrollAlgorithm {

    private static final int MAX_ITEMS_IN_BOTTOM_STACK = 3;
    private static final int MAX_ITEMS_IN_TOP_STACK = 3;

    private int mPaddingBetweenElements;
    private int mCollapsedSize;
    private int mTopStackPeekSize;
    private int mBottomStackPeekSize;
    private int mZDistanceBetweenElements;
    private int mZBasicHeight;

    private StackIndentationFunctor mTopStackIndentationFunctor;
    private StackIndentationFunctor mBottomStackIndentationFunctor;

    private float mLayoutHeight;
    private StackScrollAlgorithmState mTempAlgorithmState = new StackScrollAlgorithmState();

    public StackScrollAlgorithm(Context context) {
        initConstants(context);
    }

    private void initConstants(Context context) {

        // currently the padding is in the elements themself
        mPaddingBetweenElements = 0;
        mCollapsedSize = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_row_min_height);
        mTopStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.top_stack_peek_amount);
        mBottomStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_peek_amount);
        mZDistanceBetweenElements = context.getResources()
                .getDimensionPixelSize(R.dimen.z_distance_between_notifications);
        mZBasicHeight = (MAX_ITEMS_IN_BOTTOM_STACK + 1) * mZDistanceBetweenElements;

        mTopStackIndentationFunctor = new PiecewiseLinearIndentationFunctor(
                MAX_ITEMS_IN_TOP_STACK,
                mTopStackPeekSize,
                mCollapsedSize + mPaddingBetweenElements,
                0.5f);
        mBottomStackIndentationFunctor = new PiecewiseLinearIndentationFunctor(
                MAX_ITEMS_IN_BOTTOM_STACK,
                mBottomStackPeekSize,
                mBottomStackPeekSize,
                0.5f);
    }


    public void getStackScrollState(StackScrollState resultState) {
        // The state of the local variables are saved in an algorithmState to easily subdivide it
        // into multiple phases.
        StackScrollAlgorithmState algorithmState = mTempAlgorithmState;

        // First we reset the view states to their default values.
        resultState.resetViewStates();

        // The first element is always in there so it's initialized with 1.0f.
        algorithmState.itemsInTopStack = 1.0f;
        algorithmState.partialInTop = 0.0f;
        algorithmState.lastTopStackIndex = 0;
        algorithmState.scrollY = resultState.getScrollY();
        algorithmState.itemsInBottomStack = 0.0f;

        // Phase 1:
        findNumberOfItemsInTopStackAndUpdateState(resultState, algorithmState);

        // Phase 2:
        updatePositionsForState(resultState, algorithmState);

        // Phase 3:
        updateZValuesForState(resultState, algorithmState);

        // Write the algorithm state to the result.
        resultState.setScrollY(algorithmState.scrollY);
    }

    /**
     * Determine the positions for the views. This is the main part of the algorithm.
     *
     * @param resultState The result state to update if a change to the properties of a child occurs
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     *                       and which will be updated
     */
    private void updatePositionsForState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {
        float stackHeight = getLayoutHeight();

        // The position where the bottom stack starts.
        float transitioningPositionStart = stackHeight - mCollapsedSize - mBottomStackPeekSize;

        // The y coordinate of the current child.
        float currentYPosition = 0.0f;

        // How far in is the element currently transitioning into the bottom stack.
        float yPositionInScrollView = 0.0f;

        ViewGroup hostView = resultState.getHostView();
        int childCount = hostView.getChildCount();
        int numberOfElementsCompletelyIn = (int) algorithmState.itemsInTopStack;
        for (int i = 0; i < childCount; i++) {
            View child = hostView.getChildAt(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            childViewState.yTranslation = currentYPosition;
            int childHeight = child.getHeight();
            // The y position after this element
            float nextYPosition = currentYPosition + childHeight + mPaddingBetweenElements;
            float yPositionInScrollViewAfterElement = yPositionInScrollView
                    + childHeight
                    + mPaddingBetweenElements;
            float scrollOffset = yPositionInScrollViewAfterElement - algorithmState.scrollY;
            if (i < algorithmState.lastTopStackIndex) {
                // Case 1:
                // We are in the top Stack
                nextYPosition = updateStateForTopStackChild(algorithmState,
                        numberOfElementsCompletelyIn,
                        i, childViewState);

            } else if (i == algorithmState.lastTopStackIndex) {
                // Case 2:
                // First element of regular scrollview comes next, so the position is just the
                // scrolling position
                nextYPosition = scrollOffset;
            } else if (nextYPosition >= transitioningPositionStart) {
                if (currentYPosition >= transitioningPositionStart) {
                    // Case 3:
                    // According to the regular scroll view we are fully translated out of the
                    // bottom of the screen so we are fully in the bottom stack
                    nextYPosition = updateStateForChildFullyInBottomStack(algorithmState,
                            transitioningPositionStart, childViewState, childHeight);


                } else {
                    // Case 4:
                    // According to the regular scroll view we are currently translating out of /
                    // into the bottom of the screen
                    nextYPosition = updateStateForChildTransitioningInBottom(
                            algorithmState, stackHeight, transitioningPositionStart,
                            currentYPosition, childViewState,
                            childHeight, nextYPosition);
                }
            }
            currentYPosition = nextYPosition;
            yPositionInScrollView = yPositionInScrollViewAfterElement;
        }
    }

    private float updateStateForChildTransitioningInBottom(StackScrollAlgorithmState algorithmState,
            float stackHeight, float transitioningPositionStart, float currentYPosition,
            StackScrollState.ViewState childViewState, int childHeight, float nextYPosition) {
        float newSize = transitioningPositionStart + mCollapsedSize - currentYPosition;
        newSize = Math.min(childHeight, newSize);
        // Transitioning element on top of bottom stack:
        algorithmState.partialInBottom = 1.0f - (
                (stackHeight - mBottomStackPeekSize - nextYPosition) / mCollapsedSize);
        // Our element can be expanded, so we might even have to scroll further than
        // mCollapsedSize
        algorithmState.partialInBottom = Math.min(1.0f, algorithmState.partialInBottom);
        float offset = mBottomStackIndentationFunctor.getValue(
                algorithmState.partialInBottom);
        nextYPosition = transitioningPositionStart + offset;
        algorithmState.itemsInBottomStack += algorithmState.partialInBottom;
        // TODO: only temporarily collapse
        if (childHeight != (int) newSize) {
            childViewState.height = (int) newSize;
        }
        return nextYPosition;
    }

    private float updateStateForChildFullyInBottomStack(StackScrollAlgorithmState algorithmState,
            float transitioningPositionStart, StackScrollState.ViewState childViewState,
            int childHeight) {

        float nextYPosition;
        algorithmState.itemsInBottomStack += 1.0f;
        if (algorithmState.itemsInBottomStack < MAX_ITEMS_IN_BOTTOM_STACK) {
            // We are visually entering the bottom stack
            nextYPosition = transitioningPositionStart
                    + mBottomStackIndentationFunctor.getValue(
                            algorithmState.itemsInBottomStack);
        } else {
            // we are fully inside the stack
            if (algorithmState.itemsInBottomStack > MAX_ITEMS_IN_BOTTOM_STACK + 2) {
                childViewState.alpha = 0.0f;
            } else if (algorithmState.itemsInBottomStack
                    > MAX_ITEMS_IN_BOTTOM_STACK + 1) {
                childViewState.alpha = 1.0f - algorithmState.partialInBottom;
            }
            nextYPosition = transitioningPositionStart + mBottomStackPeekSize;
        }
        // TODO: only temporarily collapse
        if (childHeight != mCollapsedSize) {
            childViewState.height = mCollapsedSize;
        }
        return nextYPosition;
    }

    private float updateStateForTopStackChild(StackScrollAlgorithmState algorithmState,
            int numberOfElementsCompletelyIn, int i, StackScrollState.ViewState childViewState) {

        float nextYPosition = 0;

        // First we calculate the index relative to the current stack window of size at most
        // {@link #MAX_ITEMS_IN_TOP_STACK}
        int paddedIndex = i
                - Math.max(numberOfElementsCompletelyIn - MAX_ITEMS_IN_TOP_STACK, 0);
        if (paddedIndex >= 0) {
            // We are currently visually entering the top stack
            nextYPosition = mCollapsedSize + mPaddingBetweenElements -
                    mTopStackIndentationFunctor.getValue(
                            algorithmState.itemsInTopStack - i - 1);
            if (paddedIndex == 0 && i != 0) {
                childViewState.alpha = 1.0f - algorithmState.partialInTop;
            }
        } else {
            // We are hidden behind the top card and faded out, so we can hide ourselfs
            if (i != 0) {
                childViewState.alpha = 0.0f;
            }
        }
        return nextYPosition;
    }

    /**
     * Find the number of items in the top stack and update the result state if needed.
     *
     * @param resultState The result state to update if a height change of an child occurs
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     *                       and which will be updated
     */
    private void findNumberOfItemsInTopStackAndUpdateState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {

        // The y Position if the element would be in a regular scrollView
        float yPositionInScrollView = 0.0f;
        ViewGroup hostView = resultState.getHostView();
        int childCount = hostView.getChildCount();

        // find the number of elements in the top stack.
        for (int i = 0; i < childCount; i++) {
            View child = hostView.getChildAt(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            int childHeight = child.getHeight();
            float yPositionInScrollViewAfterElement = yPositionInScrollView
                    + childHeight
                    + mPaddingBetweenElements;
            if (yPositionInScrollView < algorithmState.scrollY) {
                if (yPositionInScrollViewAfterElement <= algorithmState.scrollY) {
                    // According to the regular scroll view we are fully off screen
                    algorithmState.itemsInTopStack += 1.0f;
                    if (childHeight != mCollapsedSize) {
                        childViewState.height = mCollapsedSize;
                    }
                } else {
                    // According to the regular scroll view we are partially off screen
                    // If it is expanded we have to collapse it to a new size
                    float newSize = yPositionInScrollViewAfterElement
                            - mPaddingBetweenElements
                            - algorithmState.scrollY;

                    // How much did we scroll into this child
                    algorithmState.partialInTop = (mCollapsedSize - newSize) / (mCollapsedSize
                            + mPaddingBetweenElements);

                    // Our element can be expanded, so this can get negative
                    algorithmState.partialInTop = Math.max(0.0f, algorithmState.partialInTop);
                    algorithmState.itemsInTopStack += algorithmState.partialInTop;
                    // TODO: handle overlapping sizes with end stack
                    newSize = Math.max(mCollapsedSize, newSize);
                    // TODO: only temporarily collapse
                    if (newSize != childHeight) {
                        childViewState.height = (int) newSize;

                        // We decrease scrollY by the same amount we made this child smaller.
                        // The new scroll position is therefore the start of the element
                        algorithmState.scrollY = (int) yPositionInScrollView;
                        resultState.setScrollY(algorithmState.scrollY);
                    }
                    if (childHeight > mCollapsedSize) {
                        // If we are just resizing this child, this element is not treated to be
                        // transitioning into the stack and therefore it is the last element in
                        // the stack.
                        algorithmState.lastTopStackIndex = i;
                        break;
                    }
                }
            } else {
                algorithmState.lastTopStackIndex = i;

                // We are already past the stack so we can end the loop
                break;
            }
            yPositionInScrollView = yPositionInScrollViewAfterElement;
        }
    }

    /**
     * Calculate the Z positions for all children based on the number of items in both stacks and
     * save it in the resultState
     *
     * @param resultState The result state to update the zTranslation values
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     */
    private void updateZValuesForState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {
        ViewGroup hostView = resultState.getHostView();
        int childCount = hostView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = hostView.getChildAt(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            if (i < algorithmState.itemsInTopStack) {
                float stackIndex = algorithmState.itemsInTopStack - i;
                stackIndex = Math.min(stackIndex, MAX_ITEMS_IN_TOP_STACK + 2);
                childViewState.zTranslation = mZBasicHeight
                        + stackIndex * mZDistanceBetweenElements;
            } else if (i > (childCount - 1 - algorithmState.itemsInBottomStack)) {
                float numItemsAbove = i - (childCount - 1 - algorithmState.itemsInBottomStack);
                float translationZ = mZBasicHeight
                        - numItemsAbove * mZDistanceBetweenElements;
                childViewState.zTranslation = translationZ;
            } else {
                childViewState.zTranslation = mZBasicHeight;
            }
        }
    }

    public float getLayoutHeight() {
        return mLayoutHeight;
    }

    public void setLayoutHeight(float layoutHeight) {
        this.mLayoutHeight = layoutHeight;
    }

    class StackScrollAlgorithmState {

        /**
         * The scroll position of the algorithm
         */
        public int scrollY;

        /**
         *  The quantity of items which are in the top stack.
         */
        public float itemsInTopStack;

        /**
         * how far in is the element currently transitioning into the top stack
         */
        public float partialInTop;

        /**
         * The last item index which is in the top stack.
         * NOTE: In the top stack the item after the transitioning element is also in the stack!
         * This is needed to ensure a smooth transition between the y position in the regular
         * scrollview and the one in the stack.
         */
        public int lastTopStackIndex;

        /**
         * The quantity of items which are in the bottom stack.
         */
        public float itemsInBottomStack;

        /**
         * how far in is the element currently transitioning into the bottom stack
         */
        public float partialInBottom;
    }

}
