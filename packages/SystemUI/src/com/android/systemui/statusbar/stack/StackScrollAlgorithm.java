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
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.NotificationUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The Algorithm of the {@link com.android.systemui.statusbar.stack
 * .NotificationStackScrollLayout} which can be queried for {@link com.android.systemui.statusbar
 * .stack.StackScrollState}
 */
public class StackScrollAlgorithm {

    private static final String LOG_TAG = "StackScrollAlgorithm";

    private static final int MAX_ITEMS_IN_BOTTOM_STACK = 3;

    private int mPaddingBetweenElements;
    private int mIncreasedPaddingBetweenElements;
    private int mCollapsedSize;
    private int mBottomStackPeekSize;
    private int mZDistanceBetweenElements;
    private int mZBasicHeight;

    private StackIndentationFunctor mBottomStackIndentationFunctor;

    private StackScrollAlgorithmState mTempAlgorithmState = new StackScrollAlgorithmState();
    private boolean mIsExpanded;
    private int mBottomStackSlowDownLength;

    public StackScrollAlgorithm(Context context) {
        initView(context);
    }

    public void initView(Context context) {
        initConstants(context);
    }

    public int getBottomStackSlowDownLength() {
        return mBottomStackSlowDownLength + mPaddingBetweenElements;
    }

    private void initConstants(Context context) {
        mPaddingBetweenElements = Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.notification_divider_height));
        mIncreasedPaddingBetweenElements = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_divider_height_increased);
        mCollapsedSize = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_min_height);
        mBottomStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_peek_amount);
        mZDistanceBetweenElements = Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.z_distance_between_notifications));
        mZBasicHeight = (MAX_ITEMS_IN_BOTTOM_STACK + 1) * mZDistanceBetweenElements;
        mBottomStackSlowDownLength = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_slow_down_length);
        mBottomStackIndentationFunctor = new PiecewiseLinearIndentationFunctor(
                MAX_ITEMS_IN_BOTTOM_STACK,
                mBottomStackPeekSize,
                getBottomStackSlowDownLength(),
                0.5f);
    }

    public void getStackScrollState(AmbientState ambientState, StackScrollState resultState) {
        // The state of the local variables are saved in an algorithmState to easily subdivide it
        // into multiple phases.
        StackScrollAlgorithmState algorithmState = mTempAlgorithmState;

        // First we reset the view states to their default values.
        resultState.resetViewStates();

        initAlgorithmState(resultState, algorithmState, ambientState);

        updatePositionsForState(resultState, algorithmState, ambientState);

        updateZValuesForState(resultState, algorithmState, ambientState);

        updateHeadsUpStates(resultState, algorithmState, ambientState);

        handleDraggedViews(ambientState, resultState, algorithmState);
        updateDimmedActivatedHideSensitive(ambientState, resultState, algorithmState);
        updateClipping(resultState, algorithmState, ambientState);
        updateSpeedBumpState(resultState, algorithmState, ambientState.getSpeedBumpIndex());
        getNotificationChildrenStates(resultState, algorithmState);
    }

    private void getNotificationChildrenStates(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = algorithmState.visibleChildren.get(i);
            if (v instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) v;
                row.getChildrenStates(resultState);
            }
        }
    }

    private void updateSpeedBumpState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, int speedBumpIndex) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            StackViewState childViewState = resultState.getViewStateForView(child);

            // The speed bump can also be gone, so equality needs to be taken when comparing
            // indices.
            childViewState.belowSpeedBump = speedBumpIndex != -1 && i >= speedBumpIndex;
        }
    }

    private void updateClipping(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, AmbientState ambientState) {
        float drawStart = ambientState.getTopPadding() + ambientState.getStackTranslation();
        float previousNotificationEnd = 0;
        float previousNotificationStart = 0;
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            StackViewState state = resultState.getViewStateForView(child);
            if (!child.mustStayOnScreen()) {
                previousNotificationEnd = Math.max(drawStart, previousNotificationEnd);
                previousNotificationStart = Math.max(drawStart, previousNotificationStart);
            }
            float newYTranslation = state.yTranslation;
            float newHeight = state.height;
            float newNotificationEnd = newYTranslation + newHeight;
            boolean isHeadsUp = (child instanceof ExpandableNotificationRow)
                    && ((ExpandableNotificationRow) child).isPinned();
            if (newYTranslation < previousNotificationEnd
                    && (!isHeadsUp || ambientState.isShadeExpanded())) {
                // The previous view is overlapping on top, clip!
                float overlapAmount = previousNotificationEnd - newYTranslation;
                state.clipTopAmount = (int) overlapAmount;
            } else {
                state.clipTopAmount = 0;
            }

            if (!child.isTransparent()) {
                // Only update the previous values if we are not transparent,
                // otherwise we would clip to a transparent view.
                previousNotificationEnd = newNotificationEnd;
                previousNotificationStart = newYTranslation;
            }
        }
    }

    public static boolean canChildBeDismissed(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            if (row.areGutsExposed()) {
                return false;
            }
        }
        final View veto = v.findViewById(R.id.veto);
        return (veto != null && veto.getVisibility() != View.GONE);
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
            StackViewState childViewState = resultState.getViewStateForView(child);
            childViewState.dimmed = dimmed;
            childViewState.dark = dark;
            childViewState.hideSensitive = hideSensitive;
            boolean isActivatedChild = activatedChild == child;
            if (dimmed && isActivatedChild) {
                childViewState.zTranslation += 2.0f * mZDistanceBetweenElements;
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
                    StackViewState viewState = resultState.getViewStateForView(
                            nextChild);
                    // The child below the dragged one must be fully visible
                    if (ambientState.isShadeExpanded()) {
                        viewState.shadowAlpha = 1;
                        viewState.hidden = false;
                    }
                }

                // Lets set the alpha to the one it currently has, as its currently being dragged
                StackViewState viewState = resultState.getViewStateForView(draggedView);
                // The dragged child should keep the set alpha
                viewState.alpha = draggedView.getAlpha();
            }
        }
    }

    /**
     * Initialize the algorithm state like updating the visible children.
     */
    private void initAlgorithmState(StackScrollState resultState, StackScrollAlgorithmState state,
            AmbientState ambientState) {
        state.itemsInBottomStack = 0.0f;
        state.partialInBottom = 0.0f;
        float bottomOverScroll = ambientState.getOverScrollAmount(false /* onTop */);

        int scrollY = ambientState.getScrollY();

        // Due to the overScroller, the stackscroller can have negative scroll state. This is
        // already accounted for by the top padding and doesn't need an additional adaption
        scrollY = Math.max(0, scrollY);
        state.scrollY = (int) (scrollY + bottomOverScroll);

        //now init the visible children and update paddings
        ViewGroup hostView = resultState.getHostView();
        int childCount = hostView.getChildCount();
        state.visibleChildren.clear();
        state.visibleChildren.ensureCapacity(childCount);
        state.increasedPaddingMap.clear();
        int notGoneIndex = 0;
        ExpandableView lastView = null;
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = (ExpandableView) hostView.getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                notGoneIndex = updateNotGoneIndex(resultState, state, notGoneIndex, v);
                float increasedPadding = v.getIncreasedPaddingAmount();
                if (increasedPadding != 0.0f) {
                    state.increasedPaddingMap.put(v, increasedPadding);
                    if (lastView != null) {
                        Float prevValue = state.increasedPaddingMap.get(lastView);
                        float newValue = prevValue != null
                                ? Math.max(prevValue, increasedPadding)
                                : increasedPadding;
                        state.increasedPaddingMap.put(lastView, newValue);
                    }
                }
                if (v instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) v;

                    // handle the notgoneIndex for the children as well
                    List<ExpandableNotificationRow> children =
                            row.getNotificationChildren();
                    if (row.isSummaryWithChildren() && children != null) {
                        for (ExpandableNotificationRow childRow : children) {
                            if (childRow.getVisibility() != View.GONE) {
                                StackViewState childState
                                        = resultState.getViewStateForView(childRow);
                                childState.notGoneIndex = notGoneIndex;
                                notGoneIndex++;
                            }
                        }
                    }
                }
                lastView = v;
            }
        }
    }

    private int updateNotGoneIndex(StackScrollState resultState,
            StackScrollAlgorithmState state, int notGoneIndex,
            ExpandableView v) {
        StackViewState viewState = resultState.getViewStateForView(v);
        viewState.notGoneIndex = notGoneIndex;
        state.visibleChildren.add(v);
        notGoneIndex++;
        return notGoneIndex;
    }

    /**
     * Determine the positions for the views. This is the main part of the algorithm.
     *
     * @param resultState The result state to update if a change to the properties of a child occurs
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     * @param ambientState The current ambient state
     */
    private void updatePositionsForState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, AmbientState ambientState) {

        // The starting position of the bottom stack peek
        float bottomPeekStart = ambientState.getInnerHeight() - mBottomStackPeekSize;

        // The position where the bottom stack starts.
        float bottomStackStart = bottomPeekStart - mBottomStackSlowDownLength;

        // The y coordinate of the current child.
        float currentYPosition = -algorithmState.scrollY;

        int childCount = algorithmState.visibleChildren.size();
        int paddingAfterChild;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            StackViewState childViewState = resultState.getViewStateForView(child);
            childViewState.location = StackViewState.LOCATION_UNKNOWN;
            paddingAfterChild = getPaddingAfterChild(algorithmState, child);
            int childHeight = getMaxAllowedChildHeight(child);
            int collapsedHeight = child.getCollapsedHeight();
            childViewState.yTranslation = currentYPosition;
            if (i == 0) {
                updateFirstChildHeight(child, childViewState, childHeight, ambientState);
            }

            // The y position after this element
            float nextYPosition = currentYPosition + childHeight +
                    paddingAfterChild;
            if (nextYPosition >= bottomStackStart) {
                // Case 1:
                // We are in the bottom stack.
                if (currentYPosition >= bottomStackStart) {
                    // According to the regular scroll view we are fully translated out of the
                    // bottom of the screen so we are fully in the bottom stack
                    updateStateForChildFullyInBottomStack(algorithmState,
                            bottomStackStart, childViewState, collapsedHeight, ambientState, child);
                } else {
                    // According to the regular scroll view we are currently translating out of /
                    // into the bottom of the screen
                    updateStateForChildTransitioningInBottom(algorithmState,
                            bottomStackStart, child, currentYPosition,
                            childViewState, childHeight);
                }
            } else {
                // Case 2:
                // We are in the regular scroll area.
                childViewState.location = StackViewState.LOCATION_MAIN_AREA;
                clampPositionToBottomStackStart(childViewState, childViewState.height, childHeight,
                        ambientState);
            }

            if (i == 0 && ambientState.getScrollY() <= 0) {
                // The first card can get into the bottom stack if it's the only one
                // on the lockscreen which pushes it up. Let's make sure that doesn't happen and
                // it stays at the top
                childViewState.yTranslation = Math.max(0, childViewState.yTranslation);
            }
            currentYPosition = childViewState.yTranslation + childHeight + paddingAfterChild;
            if (currentYPosition <= 0) {
                childViewState.location = StackViewState.LOCATION_HIDDEN_TOP;
            }
            if (childViewState.location == StackViewState.LOCATION_UNKNOWN) {
                Log.wtf(LOG_TAG, "Failed to assign location for child " + i);
            }

            childViewState.yTranslation += ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
        }
    }

    private int getPaddingAfterChild(StackScrollAlgorithmState algorithmState,
            ExpandableView child) {
        Float paddingValue = algorithmState.increasedPaddingMap.get(child);
        return paddingValue == null
                ? mPaddingBetweenElements
                : (int) NotificationUtils.interpolate(mPaddingBetweenElements,
                        mIncreasedPaddingBetweenElements,
                        paddingValue);
    }

    private void updateHeadsUpStates(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        ExpandableNotificationRow topHeadsUpEntry = null;
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                break;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            if (!row.isHeadsUp()) {
                break;
            }
            StackViewState childState = resultState.getViewStateForView(row);
            if (topHeadsUpEntry == null) {
                topHeadsUpEntry = row;
                childState.location = StackViewState.LOCATION_FIRST_HUN;
            }
            boolean isTopEntry = topHeadsUpEntry == row;
            float unmodifiedEndLocation = childState.yTranslation + childState.height;
            if (mIsExpanded) {
                // Ensure that the heads up is always visible even when scrolled off
                clampHunToTop(ambientState, row, childState);
                clampHunToMaxTranslation(ambientState, row, childState);
            }
            if (row.isPinned()) {
                childState.yTranslation = Math.max(childState.yTranslation, 0);
                childState.height = Math.max(row.getIntrinsicHeight(), childState.height);
                StackViewState topState = resultState.getViewStateForView(topHeadsUpEntry);
                if (!isTopEntry && (!mIsExpanded
                        || unmodifiedEndLocation < topState.yTranslation + topState.height)) {
                    // Ensure that a headsUp doesn't vertically extend further than the heads-up at
                    // the top most z-position
                    childState.height = row.getIntrinsicHeight();
                    childState.yTranslation = topState.yTranslation + topState.height
                            - childState.height;
                }
            }
        }
    }

    private void clampHunToTop(AmbientState ambientState, ExpandableNotificationRow row,
            StackViewState childState) {
        float newTranslation = Math.max(ambientState.getTopPadding()
                + ambientState.getStackTranslation(), childState.yTranslation);
        childState.height = (int) Math.max(childState.height - (newTranslation
                - childState.yTranslation), row.getCollapsedHeight());
        childState.yTranslation = newTranslation;
    }

    private void clampHunToMaxTranslation(AmbientState ambientState, ExpandableNotificationRow row,
            StackViewState childState) {
        float newTranslation;
        float bottomPosition = ambientState.getMaxHeadsUpTranslation() - row.getCollapsedHeight();
        newTranslation = Math.min(childState.yTranslation, bottomPosition);
        childState.height = (int) Math.max(childState.height
                - (childState.yTranslation - newTranslation), row.getCollapsedHeight());
        childState.yTranslation = newTranslation;
    }

    /**
     * Clamp the yTranslation of the child down such that its end is at most on the beginning of
     * the bottom stack.
     *
     * @param childViewState the view state of the child
     * @param childHeight the height of this child
     * @param minHeight the minumum Height of the View
     */
    private void clampPositionToBottomStackStart(StackViewState childViewState,
            int childHeight, int minHeight, AmbientState ambientState) {

        int bottomStackStart = ambientState.getInnerHeight()
                - mBottomStackPeekSize - mBottomStackSlowDownLength;
        int childStart = bottomStackStart - childHeight;
        if (childStart < childViewState.yTranslation) {
            float newHeight = bottomStackStart - childViewState.yTranslation;
            if (newHeight < minHeight) {
                newHeight = minHeight;
                childViewState.yTranslation = bottomStackStart - minHeight;
            }
            childViewState.height = (int) newHeight;
        }
    }

    private int getMaxAllowedChildHeight(View child) {
        if (child instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) child;
            return expandableView.getIntrinsicHeight();
        }
        return child == null? mCollapsedSize : child.getHeight();
    }

    private void updateStateForChildTransitioningInBottom(StackScrollAlgorithmState algorithmState,
            float transitioningPositionStart, ExpandableView child, float currentYPosition,
            StackViewState childViewState, int childHeight) {

        // This is the transitioning element on top of bottom stack, calculate how far we are in.
        algorithmState.partialInBottom = 1.0f - (
                (transitioningPositionStart - currentYPosition) / (childHeight +
                        getPaddingAfterChild(algorithmState, child)));

        // the offset starting at the transitionPosition of the bottom stack
        float offset = mBottomStackIndentationFunctor.getValue(algorithmState.partialInBottom);
        algorithmState.itemsInBottomStack += algorithmState.partialInBottom;
        int newHeight = childHeight;
        if (childHeight > child.getCollapsedHeight()) {
            newHeight = (int) Math.max(Math.min(transitioningPositionStart + offset -
                    getPaddingAfterChild(algorithmState, child) - currentYPosition, childHeight),
                    child.getCollapsedHeight());
            childViewState.height = newHeight;
        }
        childViewState.yTranslation = transitioningPositionStart + offset - newHeight
                - getPaddingAfterChild(algorithmState, child);
        childViewState.location = StackViewState.LOCATION_MAIN_AREA;
    }

    private void updateStateForChildFullyInBottomStack(StackScrollAlgorithmState algorithmState,
            float transitioningPositionStart, StackViewState childViewState,
            int collapsedHeight, AmbientState ambientState, ExpandableView child) {
        float currentYPosition;
        algorithmState.itemsInBottomStack += 1.0f;
        if (algorithmState.itemsInBottomStack < MAX_ITEMS_IN_BOTTOM_STACK) {
            // We are visually entering the bottom stack
            currentYPosition = transitioningPositionStart
                    + mBottomStackIndentationFunctor.getValue(algorithmState.itemsInBottomStack)
                    - getPaddingAfterChild(algorithmState, child);
            childViewState.location = StackViewState.LOCATION_BOTTOM_STACK_PEEKING;
        } else {
            // we are fully inside the stack
            if (algorithmState.itemsInBottomStack > MAX_ITEMS_IN_BOTTOM_STACK + 2) {
                childViewState.hidden = true;
                childViewState.shadowAlpha = 0.0f;
            } else if (algorithmState.itemsInBottomStack
                    > MAX_ITEMS_IN_BOTTOM_STACK + 1) {
                childViewState.shadowAlpha = 1.0f - algorithmState.partialInBottom;
            }
            childViewState.location = StackViewState.LOCATION_BOTTOM_STACK_HIDDEN;
            currentYPosition = ambientState.getInnerHeight();
        }
        childViewState.height = collapsedHeight;
        childViewState.yTranslation = currentYPosition - collapsedHeight;
    }


    /**
     * Update the height of the first child i.e clamp it to the bottom stack
     *
     * @param child the child to update
     * @param childViewState the viewstate of the child
     * @param childHeight the height of the child
     * @param ambientState The ambient state of the algorithm
     */
    private void updateFirstChildHeight(ExpandableView child, StackViewState childViewState,
            int childHeight, AmbientState ambientState) {

            // The starting position of the bottom stack peek
            int bottomPeekStart = ambientState.getInnerHeight() - mBottomStackPeekSize -
                    mBottomStackSlowDownLength + ambientState.getScrollY();
            // Collapse and expand the first child while the shade is being expanded
        childViewState.height = (int) Math.max(Math.min(bottomPeekStart, (float) childHeight),
                    child.getCollapsedHeight());
    }

    /**
     * Calculate the Z positions for all children based on the number of items in both stacks and
     * save it in the resultState
     *  @param resultState The result state to update the zTranslation values
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     * @param ambientState The ambient state of the algorithm
     */
    private void updateZValuesForState(StackScrollState resultState,
            StackScrollAlgorithmState algorithmState, AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        float childrenOnTop = 0.0f;
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            StackViewState childViewState = resultState.getViewStateForView(child);
            if (i > (childCount - 1 - algorithmState.itemsInBottomStack)) {
                // We are in the bottom stack
                float numItemsAbove = i - (childCount - 1 - algorithmState.itemsInBottomStack);
                float zSubtraction;
                if (numItemsAbove <= 1.0f) {
                    float factor = 0.2f;
                    // Lets fade in slower to the threshold to make the shadow fade in look nicer
                    if (numItemsAbove <= factor) {
                        zSubtraction = FakeShadowView.SHADOW_SIBLING_TRESHOLD
                                * numItemsAbove * (1.0f / factor);
                    } else {
                        zSubtraction = FakeShadowView.SHADOW_SIBLING_TRESHOLD
                                + (numItemsAbove - factor) * (1.0f / (1.0f - factor))
                                        * (mZDistanceBetweenElements
                                                - FakeShadowView.SHADOW_SIBLING_TRESHOLD);
                    }
                } else {
                    zSubtraction = numItemsAbove * mZDistanceBetweenElements;
                }
                childViewState.zTranslation = mZBasicHeight - zSubtraction;
            } else if (child.mustStayOnScreen()
                    && childViewState.yTranslation < ambientState.getTopPadding()
                    + ambientState.getStackTranslation()) {
                if (childrenOnTop != 0.0f) {
                    childrenOnTop++;
                } else {
                    float overlap = ambientState.getTopPadding()
                            + ambientState.getStackTranslation() - childViewState.yTranslation;
                    childrenOnTop += Math.min(1.0f, overlap / childViewState.height);
                }
                childViewState.zTranslation = mZBasicHeight
                        + childrenOnTop * mZDistanceBetweenElements;
            } else {
                childViewState.zTranslation = mZBasicHeight;
            }
        }
    }

    private boolean isMaxSizeInitialized(ExpandableView child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            return row.isMaxExpandHeightInitialized();
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

    public void setIsExpanded(boolean isExpanded) {
        this.mIsExpanded = isExpanded;
    }

    class StackScrollAlgorithmState {

        /**
         * The scroll position of the algorithm
         */
        public int scrollY;

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

        /**
         * The children from the host that need an increased padding after them. A value of 0 means
         * no increased padding, a value of 1 means full padding.
         */
        public final HashMap<ExpandableView, Float> increasedPaddingMap = new HashMap<>();
    }

}
