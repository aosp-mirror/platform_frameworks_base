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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;

import java.util.ArrayList;

/**
 * The Algorithm of the {@link com.android.systemui.statusbar.stack
 * .NotificationStackScrollLayout} which can be queried for {@link com.android.systemui.statusbar
 * .stack.StackScrollState}
 */
public class StackScrollAlgorithm {

    private static final String LOG_TAG = "StackScrollAlgorithm";

    private static final int MAX_ITEMS_IN_BOTTOM_STACK = 3;
    private static final int MAX_ITEMS_IN_TOP_STACK = 3;

    /** When a child is activated, the other cards' alpha fade to this value. */
    private static final float ACTIVATED_INVERSE_ALPHA = 0.9f;
    public static final float DIMMED_SCALE = 0.95f;

    private int mPaddingBetweenElements;
    private int mCollapsedSize;
    private int mTopStackPeekSize;
    private int mBottomStackPeekSize;
    private int mZDistanceBetweenElements;
    private int mZBasicHeight;
    private int mRoundedRectCornerRadius;

    private StackIndentationFunctor mTopStackIndentationFunctor;
    private StackIndentationFunctor mBottomStackIndentationFunctor;

    private int mLayoutHeight;

    /** mLayoutHeight - mTopPadding */
    private int mInnerHeight;
    private int mTopPadding;
    private StackScrollAlgorithmState mTempAlgorithmState = new StackScrollAlgorithmState();
    private boolean mIsExpansionChanging;
    private int mFirstChildMaxHeight;
    private boolean mIsExpanded;
    private ExpandableView mFirstChildWhileExpanding;
    private boolean mExpandedOnStart;
    private int mTopStackTotalSize;
    private int mPaddingBetweenElementsDimmed;
    private int mPaddingBetweenElementsNormal;
    private int mBottomStackSlowDownLength;
    private int mTopStackSlowDownLength;
    private int mCollapseSecondCardPadding;
    private boolean mIsSmallScreen;
    private int mMaxNotificationHeight;

    public StackScrollAlgorithm(Context context) {
        initConstants(context);
        updatePadding(false);
    }

    private void updatePadding(boolean dimmed) {
        mPaddingBetweenElements = dimmed
                ? mPaddingBetweenElementsDimmed
                : mPaddingBetweenElementsNormal;
        mTopStackTotalSize = mTopStackSlowDownLength + mPaddingBetweenElements
                + mTopStackPeekSize;
        mTopStackIndentationFunctor = new PiecewiseLinearIndentationFunctor(
                MAX_ITEMS_IN_TOP_STACK,
                mTopStackPeekSize,
                mTopStackTotalSize - mTopStackPeekSize,
                0.5f);
        mBottomStackIndentationFunctor = new PiecewiseLinearIndentationFunctor(
                MAX_ITEMS_IN_BOTTOM_STACK,
                mBottomStackPeekSize,
                getBottomStackSlowDownLength(),
                0.5f);
    }

    public int getBottomStackSlowDownLength() {
        return mBottomStackSlowDownLength + mPaddingBetweenElements;
    }

    private void initConstants(Context context) {
        mPaddingBetweenElementsDimmed = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_padding_dimmed);
        mPaddingBetweenElementsNormal = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_padding);
        mCollapsedSize = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_min_height);
        mMaxNotificationHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_max_height);
        mTopStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.top_stack_peek_amount);
        mBottomStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_peek_amount);
        mZDistanceBetweenElements = context.getResources()
                .getDimensionPixelSize(R.dimen.z_distance_between_notifications);
        mZBasicHeight = (MAX_ITEMS_IN_BOTTOM_STACK + 1) * mZDistanceBetweenElements;
        mBottomStackSlowDownLength = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_slow_down_length);
        mTopStackSlowDownLength = context.getResources()
                .getDimensionPixelSize(R.dimen.top_stack_slow_down_length);
        mRoundedRectCornerRadius = context.getResources().getDimensionPixelSize(
                R.dimen.notification_material_rounded_rect_radius);
        mCollapseSecondCardPadding = context.getResources().getDimensionPixelSize(
                R.dimen.notification_collapse_second_card_padding);
    }


    public void getStackScrollState(AmbientState ambientState, StackScrollState resultState) {
        // The state of the local variables are saved in an algorithmState to easily subdivide it
        // into multiple phases.
        StackScrollAlgorithmState algorithmState = mTempAlgorithmState;

        // First we reset the view states to their default values.
        resultState.resetViewStates();

        algorithmState.itemsInTopStack = 0.0f;
        algorithmState.partialInTop = 0.0f;
        algorithmState.lastTopStackIndex = 0;
        algorithmState.scrolledPixelsTop = 0;
        algorithmState.itemsInBottomStack = 0.0f;
        algorithmState.partialInBottom = 0.0f;
        float bottomOverScroll = ambientState.getOverScrollAmount(false /* onTop */);

        int scrollY = ambientState.getScrollY();

        // Due to the overScroller, the stackscroller can have negative scroll state. This is
        // already accounted for by the top padding and doesn't need an additional adaption
        scrollY = Math.max(0, scrollY);
        algorithmState.scrollY = (int) (scrollY + mCollapsedSize + bottomOverScroll);

        updateVisibleChildren(resultState, algorithmState);

        // Phase 1:
        findNumberOfItemsInTopStackAndUpdateState(resultState, algorithmState);

        // Phase 2:
        updatePositionsForState(resultState, algorithmState);

        // Phase 3:
        updateZValuesForState(resultState, algorithmState);

        handleDraggedViews(ambientState, resultState, algorithmState);
        updateDimmedActivatedHideSensitive(ambientState, resultState, algorithmState);
        updateClipping(resultState, algorithmState);
        updateScrimAmount(resultState, algorithmState, ambientState.getScrimAmount());
        updateSpeedBumpState(resultState, algorithmState, ambientState.getSpeedBumpIndex());
    }

    private void updateSpeedBumpState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, int speedBumpIndex) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);

            // The speed bump can also be gone, so equality needs to be taken when comparing
            // indices.
            childViewState.belowSpeedBump = speedBumpIndex != -1 && i >= speedBumpIndex;
        }
    }

    private void updateScrimAmount(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, float scrimAmount) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            childViewState.scrimAmount = scrimAmount;
        }
    }

    private void updateClipping(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {
        float previousNotificationEnd = 0;
        float previousNotificationStart = 0;
        boolean previousNotificationIsSwiped = false;
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState state = resultState.getViewStateForView(child);
            float newYTranslation = state.yTranslation + state.height * (1f - state.scale) / 2f;
            float newHeight = state.height * state.scale;
            // apply clipping and shadow
            float newNotificationEnd = newYTranslation + newHeight;

            // In the unlocked shade we have to clip a little bit higher because of the rounded
            // corners of the notifications.
            float clippingCorrection = state.dimmed ? 0 : mRoundedRectCornerRadius * state.scale;

            // When the previous notification is swiped, we don't clip the content to the
            // bottom of it.
            float clipHeight = previousNotificationIsSwiped
                    ? newHeight
                    : newNotificationEnd - (previousNotificationEnd - clippingCorrection);

            updateChildClippingAndBackground(state, newHeight, clipHeight,
                    newHeight - (previousNotificationStart - newYTranslation));

            if (!child.isTransparent()) {
                // Only update the previous values if we are not transparent,
                // otherwise we would clip to a transparent view.
                previousNotificationStart = newYTranslation + state.clipTopAmount * state.scale;
                previousNotificationEnd = newNotificationEnd;
                previousNotificationIsSwiped = child.getTranslationX() != 0;
            }
        }
    }

    /**
     * Updates the shadow outline and the clipping for a view.
     *
     * @param state the viewState to update
     * @param realHeight the currently applied height of the view
     * @param clipHeight the desired clip height, the rest of the view will be clipped from the top
     * @param backgroundHeight the desired background height. The shadows of the view will be
     *                         based on this height and the content will be clipped from the top
     */
    private void updateChildClippingAndBackground(StackScrollState.ViewState state,
            float realHeight, float clipHeight, float backgroundHeight) {
        if (realHeight > clipHeight) {
            // Rather overlap than create a hole.
            state.topOverLap = (int) Math.floor((realHeight - clipHeight) / state.scale);
        } else {
            state.topOverLap = 0;
        }
        if (realHeight > backgroundHeight) {
            // Rather overlap than create a hole.
            state.clipTopAmount = (int) Math.floor((realHeight - backgroundHeight) / state.scale);
        } else {
            state.clipTopAmount = 0;
        }
    }

    /**
     * Updates the dimmed, activated and hiding sensitive states of the children.
     */
    private void updateDimmedActivatedHideSensitive(AmbientState ambientState,
            StackScrollState resultState, StackScrollAlgorithmState algorithmState) {
        boolean dimmed = ambientState.isDimmed();
        boolean dark = ambientState.isDark();
        boolean hideSensitive = ambientState.isHideSensitive();
        View activatedChild = ambientState.getActivatedChild();
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            childViewState.dimmed = dimmed;
            childViewState.dark = dark;
            childViewState.hideSensitive = hideSensitive;
            boolean isActivatedChild = activatedChild == child;
            childViewState.scale = !dimmed || isActivatedChild
                    ? 1.0f
                    : DIMMED_SCALE;
            if (dimmed && activatedChild != null) {
                if (!isActivatedChild) {
                    childViewState.alpha *= ACTIVATED_INVERSE_ALPHA;
                } else {
                    childViewState.zTranslation += 2.0f * mZDistanceBetweenElements;
                }
            }
        }
    }

    /**
     * Handle the special state when views are being dragged
     */
    private void handleDraggedViews(AmbientState ambientState, StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {
        ArrayList<View> draggedViews = ambientState.getDraggedViews();
        for (View draggedView : draggedViews) {
            int childIndex = algorithmState.visibleChildren.indexOf(draggedView);
            if (childIndex >= 0 && childIndex < algorithmState.visibleChildren.size() - 1) {
                View nextChild = algorithmState.visibleChildren.get(childIndex + 1);
                if (!draggedViews.contains(nextChild)) {
                    // only if the view is not dragged itself we modify its state to be fully
                    // visible
                    StackScrollState.ViewState viewState = resultState.getViewStateForView(
                            nextChild);
                    // The child below the dragged one must be fully visible
                    viewState.alpha = 1;
                }

                // Lets set the alpha to the one it currently has, as its currently being dragged
                StackScrollState.ViewState viewState = resultState.getViewStateForView(draggedView);
                // The dragged child should keep the set alpha
                viewState.alpha = draggedView.getAlpha();
            }
        }
    }

    /**
     * Update the visible children on the state.
     */
    private void updateVisibleChildren(StackScrollState resultState,
            StackScrollAlgorithmState state) {
        ViewGroup hostView = resultState.getHostView();
        int childCount = hostView.getChildCount();
        state.visibleChildren.clear();
        state.visibleChildren.ensureCapacity(childCount);
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = (ExpandableView) hostView.getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                StackScrollState.ViewState viewState = resultState.getViewStateForView(v);
                viewState.notGoneIndex = state.visibleChildren.size();
                state.visibleChildren.add(v);
            }
        }
    }

    /**
     * Determine the positions for the views. This is the main part of the algorithm.
     *
     * @param resultState The result state to update if a change to the properties of a child occurs
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     */
    private void updatePositionsForState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {

        // The starting position of the bottom stack peek
        float bottomPeekStart = mInnerHeight - mBottomStackPeekSize;

        // The position where the bottom stack starts.
        float bottomStackStart = bottomPeekStart - mBottomStackSlowDownLength;

        // The y coordinate of the current child.
        float currentYPosition = 0.0f;

        // How far in is the element currently transitioning into the bottom stack.
        float yPositionInScrollView = 0.0f;

        int childCount = algorithmState.visibleChildren.size();
        int numberOfElementsCompletelyIn = (int) algorithmState.itemsInTopStack;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            childViewState.location = StackScrollState.ViewState.LOCATION_UNKNOWN;
            int childHeight = getMaxAllowedChildHeight(child);
            float yPositionInScrollViewAfterElement = yPositionInScrollView
                    + childHeight
                    + mPaddingBetweenElements;
            float scrollOffset = yPositionInScrollView - algorithmState.scrollY + mCollapsedSize;

            if (i == algorithmState.lastTopStackIndex + 1) {
                // Normally the position of this child is the position in the regular scrollview,
                // but if the two stacks are very close to each other,
                // then have have to push it even more upwards to the position of the bottom
                // stack start.
                currentYPosition = Math.min(scrollOffset, bottomStackStart);
            }
            childViewState.yTranslation = currentYPosition;

            // The y position after this element
            float nextYPosition = currentYPosition + childHeight +
                    mPaddingBetweenElements;

            if (i <= algorithmState.lastTopStackIndex) {
                // Case 1:
                // We are in the top Stack
                updateStateForTopStackChild(algorithmState,
                        numberOfElementsCompletelyIn, i, childHeight, childViewState, scrollOffset);
                clampPositionToTopStackEnd(childViewState, childHeight);

                // check if we are overlapping with the bottom stack
                if (childViewState.yTranslation + childHeight + mPaddingBetweenElements
                        >= bottomStackStart && !mIsExpansionChanging && i != 0 && mIsSmallScreen) {
                    // we just collapse this element slightly
                    int newSize = (int) Math.max(bottomStackStart - mPaddingBetweenElements -
                            childViewState.yTranslation, mCollapsedSize);
                    childViewState.height = newSize;
                    updateStateForChildTransitioningInBottom(algorithmState, bottomStackStart,
                            bottomPeekStart, childViewState.yTranslation, childViewState,
                            childHeight);
                }
                clampPositionToBottomStackStart(childViewState, childViewState.height);
            } else if (nextYPosition >= bottomStackStart) {
                // Case 2:
                // We are in the bottom stack.
                if (currentYPosition >= bottomStackStart) {
                    // According to the regular scroll view we are fully translated out of the
                    // bottom of the screen so we are fully in the bottom stack
                    updateStateForChildFullyInBottomStack(algorithmState,
                            bottomStackStart, childViewState, childHeight);
                } else {
                    // According to the regular scroll view we are currently translating out of /
                    // into the bottom of the screen
                    updateStateForChildTransitioningInBottom(algorithmState,
                            bottomStackStart, bottomPeekStart, currentYPosition,
                            childViewState, childHeight);
                }
            } else {
                // Case 3:
                // We are in the regular scroll area.
                childViewState.location = StackScrollState.ViewState.LOCATION_MAIN_AREA;
                clampYTranslation(childViewState, childHeight);
            }

            // The first card is always rendered.
            if (i == 0) {
                childViewState.alpha = 1.0f;
                childViewState.yTranslation = Math.max(mCollapsedSize - algorithmState.scrollY, 0);
                if (childViewState.yTranslation + childViewState.height
                        > bottomPeekStart - mCollapseSecondCardPadding) {
                    childViewState.height = (int) Math.max(
                            bottomPeekStart - mCollapseSecondCardPadding
                                    - childViewState.yTranslation, mCollapsedSize);
                }
                childViewState.location = StackScrollState.ViewState.LOCATION_FIRST_CARD;
            }
            if (childViewState.location == StackScrollState.ViewState.LOCATION_UNKNOWN) {
                Log.wtf(LOG_TAG, "Failed to assign location for child " + i);
            }
            currentYPosition = childViewState.yTranslation + childHeight + mPaddingBetweenElements;
            yPositionInScrollView = yPositionInScrollViewAfterElement;

            childViewState.yTranslation += mTopPadding;
        }
    }

    /**
     * Clamp the yTranslation both up and down to valid positions.
     *
     * @param childViewState the view state of the child
     * @param childHeight the height of this child
     */
    private void clampYTranslation(StackScrollState.ViewState childViewState, int childHeight) {
        clampPositionToBottomStackStart(childViewState, childHeight);
        clampPositionToTopStackEnd(childViewState, childHeight);
    }

    /**
     * Clamp the yTranslation of the child down such that its end is at most on the beginning of
     * the bottom stack.
     *
     * @param childViewState the view state of the child
     * @param childHeight the height of this child
     */
    private void clampPositionToBottomStackStart(StackScrollState.ViewState childViewState,
            int childHeight) {
        childViewState.yTranslation = Math.min(childViewState.yTranslation,
                mInnerHeight - mBottomStackPeekSize - mCollapseSecondCardPadding - childHeight);
    }

    /**
     * Clamp the yTranslation of the child up such that its end is at lest on the end of the top
     * stack.get
     *
     * @param childViewState the view state of the child
     * @param childHeight the height of this child
     */
    private void clampPositionToTopStackEnd(StackScrollState.ViewState childViewState,
            int childHeight) {
        childViewState.yTranslation = Math.max(childViewState.yTranslation,
                mCollapsedSize - childHeight);
    }

    private int getMaxAllowedChildHeight(View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            return row.getIntrinsicHeight();
        } else if (child instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) child;
            return expandableView.getActualHeight();
        }
        return child == null? mCollapsedSize : child.getHeight();
    }

    private void updateStateForChildTransitioningInBottom(StackScrollAlgorithmState algorithmState,
            float transitioningPositionStart, float bottomPeakStart, float currentYPosition,
            StackScrollState.ViewState childViewState, int childHeight) {

        // This is the transitioning element on top of bottom stack, calculate how far we are in.
        algorithmState.partialInBottom = 1.0f - (
                (transitioningPositionStart - currentYPosition) / (childHeight +
                        mPaddingBetweenElements));

        // the offset starting at the transitionPosition of the bottom stack
        float offset = mBottomStackIndentationFunctor.getValue(algorithmState.partialInBottom);
        algorithmState.itemsInBottomStack += algorithmState.partialInBottom;
        int newHeight = childHeight;
        if (childHeight > mCollapsedSize && mIsSmallScreen) {
            newHeight = (int) Math.max(Math.min(transitioningPositionStart + offset -
                    mPaddingBetweenElements - currentYPosition, childHeight), mCollapsedSize);
            childViewState.height = newHeight;
        }
        childViewState.yTranslation = transitioningPositionStart + offset - newHeight
                - mPaddingBetweenElements;

        // We want at least to be at the end of the top stack when collapsing
        clampPositionToTopStackEnd(childViewState, newHeight);
        childViewState.location = StackScrollState.ViewState.LOCATION_MAIN_AREA;
    }

    private void updateStateForChildFullyInBottomStack(StackScrollAlgorithmState algorithmState,
            float transitioningPositionStart, StackScrollState.ViewState childViewState,
            int childHeight) {

        float currentYPosition;
        algorithmState.itemsInBottomStack += 1.0f;
        if (algorithmState.itemsInBottomStack < MAX_ITEMS_IN_BOTTOM_STACK) {
            // We are visually entering the bottom stack
            currentYPosition = transitioningPositionStart
                    + mBottomStackIndentationFunctor.getValue(algorithmState.itemsInBottomStack)
                    - mPaddingBetweenElements;
            childViewState.location = StackScrollState.ViewState.LOCATION_BOTTOM_STACK_PEEKING;
        } else {
            // we are fully inside the stack
            if (algorithmState.itemsInBottomStack > MAX_ITEMS_IN_BOTTOM_STACK + 2) {
                childViewState.alpha = 0.0f;
            } else if (algorithmState.itemsInBottomStack
                    > MAX_ITEMS_IN_BOTTOM_STACK + 1) {
                childViewState.alpha = 1.0f - algorithmState.partialInBottom;
            }
            childViewState.location = StackScrollState.ViewState.LOCATION_BOTTOM_STACK_HIDDEN;
            currentYPosition = mInnerHeight;
        }
        childViewState.yTranslation = currentYPosition - childHeight;
        clampPositionToTopStackEnd(childViewState, childHeight);
    }

    private void updateStateForTopStackChild(StackScrollAlgorithmState algorithmState,
            int numberOfElementsCompletelyIn, int i, int childHeight,
            StackScrollState.ViewState childViewState, float scrollOffset) {


        // First we calculate the index relative to the current stack window of size at most
        // {@link #MAX_ITEMS_IN_TOP_STACK}
        int paddedIndex = i - 1
                - Math.max(numberOfElementsCompletelyIn - MAX_ITEMS_IN_TOP_STACK, 0);
        if (paddedIndex >= 0) {

            // We are currently visually entering the top stack
            float distanceToStack = (childHeight + mPaddingBetweenElements)
                    - algorithmState.scrolledPixelsTop;
            if (i == algorithmState.lastTopStackIndex
                    && distanceToStack > (mTopStackTotalSize + mPaddingBetweenElements)) {

                // Child is currently translating into stack but not yet inside slow down zone.
                // Handle it like the regular scrollview.
                childViewState.yTranslation = scrollOffset;
            } else {
                // Apply stacking logic.
                float numItemsBefore;
                if (i == algorithmState.lastTopStackIndex) {
                    numItemsBefore = 1.0f
                            - (distanceToStack / (mTopStackTotalSize + mPaddingBetweenElements));
                } else {
                    numItemsBefore = algorithmState.itemsInTopStack - i;
                }
                // The end position of the current child
                float currentChildEndY = mCollapsedSize + mTopStackTotalSize
                        - mTopStackIndentationFunctor.getValue(numItemsBefore);
                childViewState.yTranslation = currentChildEndY - childHeight;
            }
            childViewState.location = StackScrollState.ViewState.LOCATION_TOP_STACK_PEEKING;
        } else {
            if (paddedIndex == -1) {
                childViewState.alpha = 1.0f - algorithmState.partialInTop;
            } else {
                // We are hidden behind the top card and faded out, so we can hide ourselves.
                childViewState.alpha = 0.0f;
            }
            childViewState.yTranslation = mCollapsedSize - childHeight;
            childViewState.location = StackScrollState.ViewState.LOCATION_TOP_STACK_HIDDEN;
        }


    }

    /**
     * Find the number of items in the top stack and update the result state if needed.
     *
     * @param resultState The result state to update if a height change of an child occurs
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     */
    private void findNumberOfItemsInTopStackAndUpdateState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {

        // The y Position if the element would be in a regular scrollView
        float yPositionInScrollView = 0.0f;
        int childCount = algorithmState.visibleChildren.size();

        // find the number of elements in the top stack.
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            int childHeight = getMaxAllowedChildHeight(child);
            float yPositionInScrollViewAfterElement = yPositionInScrollView
                    + childHeight
                    + mPaddingBetweenElements;
            if (yPositionInScrollView < algorithmState.scrollY) {
                if (i == 0 && algorithmState.scrollY <= mCollapsedSize) {

                    // The starting position of the bottom stack peek
                    int bottomPeekStart = mInnerHeight - mBottomStackPeekSize -
                            mCollapseSecondCardPadding;
                    // Collapse and expand the first child while the shade is being expanded
                    float maxHeight = mIsExpansionChanging && child == mFirstChildWhileExpanding
                            ? mFirstChildMaxHeight
                            : childHeight;
                    childViewState.height = (int) Math.max(Math.min(bottomPeekStart, maxHeight),
                            mCollapsedSize);
                    algorithmState.itemsInTopStack = 1.0f;

                } else if (yPositionInScrollViewAfterElement < algorithmState.scrollY) {
                    // According to the regular scroll view we are fully off screen
                    algorithmState.itemsInTopStack += 1.0f;
                    if (i == 0) {
                        childViewState.height = mCollapsedSize;
                    }
                } else {
                    // According to the regular scroll view we are partially off screen

                    // How much did we scroll into this child
                    algorithmState.scrolledPixelsTop = algorithmState.scrollY
                            - yPositionInScrollView;
                    algorithmState.partialInTop = (algorithmState.scrolledPixelsTop) / (childHeight
                            + mPaddingBetweenElements);

                    // Our element can be expanded, so this can get negative
                    algorithmState.partialInTop = Math.max(0.0f, algorithmState.partialInTop);
                    algorithmState.itemsInTopStack += algorithmState.partialInTop;

                    if (i == 0) {
                        // If it is expanded we have to collapse it to a new size
                        float newSize = yPositionInScrollViewAfterElement
                                - mPaddingBetweenElements
                                - algorithmState.scrollY + mCollapsedSize;
                        newSize = Math.max(mCollapsedSize, newSize);
                        algorithmState.itemsInTopStack = 1.0f;
                        childViewState.height = (int) newSize;
                    }
                    algorithmState.lastTopStackIndex = i;
                    break;
                }
            } else {
                algorithmState.lastTopStackIndex = i - 1;
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
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            StackScrollState.ViewState childViewState = resultState.getViewStateForView(child);
            if (i < algorithmState.itemsInTopStack) {
                float stackIndex = algorithmState.itemsInTopStack - i;
                stackIndex = Math.min(stackIndex, MAX_ITEMS_IN_TOP_STACK + 2);
                if (i == 0 && algorithmState.itemsInTopStack < 2.0f) {

                    // We only have the top item and an additional item in the top stack,
                    // Interpolate the index from 0 to 2 while the second item is
                    // translating in.
                    stackIndex -= 1.0f;
                    if (algorithmState.scrollY > mCollapsedSize) {

                        // Since there is a shadow treshhold, we cant just interpolate from 0 to
                        // 2 but we interpolate from 0.1f to 2.0f when scrolled in. The jump in
                        // height will not be noticable since we have padding in between.
                        stackIndex = 0.1f + stackIndex * 1.9f;
                    }
                }
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

    public void setLayoutHeight(int layoutHeight) {
        this.mLayoutHeight = layoutHeight;
        updateInnerHeight();
    }

    public void setTopPadding(int topPadding) {
        mTopPadding = topPadding;
        updateInnerHeight();
    }

    private void updateInnerHeight() {
        mInnerHeight = mLayoutHeight - mTopPadding;
    }


    /**
     * Update whether the device is very small, i.e. Notifications can be in both the top and the
     * bottom stack at the same time
     *
     * @param panelHeight The normal height of the panel when it's open
     */
    public void updateIsSmallScreen(int panelHeight) {
        mIsSmallScreen = panelHeight <
                mCollapsedSize  /* top stack */
                + mBottomStackSlowDownLength + mBottomStackPeekSize /* bottom stack */
                + mMaxNotificationHeight; /* max notification height */
    }

    public void onExpansionStarted(StackScrollState currentState) {
        mIsExpansionChanging = true;
        mExpandedOnStart = mIsExpanded;
        ViewGroup hostView = currentState.getHostView();
        updateFirstChildHeightWhileExpanding(hostView);
    }

    private void updateFirstChildHeightWhileExpanding(ViewGroup hostView) {
        mFirstChildWhileExpanding = (ExpandableView) findFirstVisibleChild(hostView);
        if (mFirstChildWhileExpanding != null) {
            if (mExpandedOnStart) {

                // We are collapsing the shade, so the first child can get as most as high as the
                // current height.
                mFirstChildMaxHeight = mFirstChildWhileExpanding.getActualHeight();
            } else {

                // We are expanding the shade, expand it to its full height.
                if (!isMaxSizeInitialized(mFirstChildWhileExpanding)) {

                    // This child was not layouted yet, wait for a layout pass
                    mFirstChildWhileExpanding
                            .addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                                @Override
                                public void onLayoutChange(View v, int left, int top, int right,
                                        int bottom, int oldLeft, int oldTop, int oldRight,
                                        int oldBottom) {
                                    if (mFirstChildWhileExpanding != null) {
                                        mFirstChildMaxHeight = getMaxAllowedChildHeight(
                                                mFirstChildWhileExpanding);
                                    } else {
                                        mFirstChildMaxHeight = 0;
                                    }
                                    v.removeOnLayoutChangeListener(this);
                                }
                            });
                } else {
                    mFirstChildMaxHeight = getMaxAllowedChildHeight(mFirstChildWhileExpanding);
                }
            }
        } else {
            mFirstChildMaxHeight = 0;
        }
    }

    private boolean isMaxSizeInitialized(ExpandableView child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            return row.isShowingLayoutLayouted();
        }
        return child == null || child.getWidth() != 0;
    }

    private View findFirstVisibleChild(ViewGroup container) {
        int childCount = container.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                return child;
            }
        }
        return null;
    }

    public void onExpansionStopped() {
        mIsExpansionChanging = false;
        mFirstChildWhileExpanding = null;
    }

    public void setIsExpanded(boolean isExpanded) {
        this.mIsExpanded = isExpanded;
    }

    public void notifyChildrenChanged(ViewGroup hostView) {
        if (mIsExpansionChanging) {
            updateFirstChildHeightWhileExpanding(hostView);
        }
    }

    public void setDimmed(boolean dimmed) {
        updatePadding(dimmed);
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
         * The number of pixels the last child in the top stack has scrolled in to the stack
         */
        public float scrolledPixelsTop;

        /**
         * The last item index which is in the top stack.
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

        /**
         * The children from the host view which are not gone.
         */
        public final ArrayList<ExpandableView> visibleChildren = new ArrayList<ExpandableView>();
    }

}
