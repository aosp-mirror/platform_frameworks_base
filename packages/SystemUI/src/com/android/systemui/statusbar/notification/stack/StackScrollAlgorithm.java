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

package com.android.systemui.statusbar.notification.stack;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.FooterView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The Algorithm of the {@link com.android.systemui.statusbar.notification.stack
 * .NotificationStackScrollLayout} which can be queried for {@link com.android.systemui.statusbar
 * .stack.StackScrollState}
 */
public class StackScrollAlgorithm {

    static final boolean ANCHOR_SCROLLING = false;

    private static final String LOG_TAG = "StackScrollAlgorithm";
    private final ViewGroup mHostView;

    private int mPaddingBetweenElements;
    private int mIncreasedPaddingBetweenElements;
    private int mGapHeight;
    private int mCollapsedSize;

    private StackScrollAlgorithmState mTempAlgorithmState = new StackScrollAlgorithmState();
    private boolean mIsExpanded;
    private boolean mClipNotificationScrollToTop;
    private int mStatusBarHeight;
    private float mHeadsUpInset;
    private int mPinnedZTranslationExtra;

    public StackScrollAlgorithm(Context context, ViewGroup hostView) {
        mHostView = hostView;
        initView(context);
    }

    public void initView(Context context) {
        initConstants(context);
    }

    private void initConstants(Context context) {
        Resources res = context.getResources();
        mPaddingBetweenElements = res.getDimensionPixelSize(
                R.dimen.notification_divider_height);
        mIncreasedPaddingBetweenElements =
                res.getDimensionPixelSize(R.dimen.notification_divider_height_increased);
        mCollapsedSize = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mStatusBarHeight = res.getDimensionPixelSize(R.dimen.status_bar_height);
        mClipNotificationScrollToTop = res.getBoolean(R.bool.config_clipNotificationScrollToTop);
        mHeadsUpInset = mStatusBarHeight + res.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mPinnedZTranslationExtra = res.getDimensionPixelSize(
                R.dimen.heads_up_pinned_elevation);
        mGapHeight = res.getDimensionPixelSize(R.dimen.notification_section_divider_height);
    }

    /**
     * Updates the state of all children in the hostview based on this algorithm.
     */
    public void resetViewStates(AmbientState ambientState) {
        // The state of the local variables are saved in an algorithmState to easily subdivide it
        // into multiple phases.
        StackScrollAlgorithmState algorithmState = mTempAlgorithmState;

        // First we reset the view states to their default values.
        resetChildViewStates();

        initAlgorithmState(mHostView, algorithmState, ambientState);

        updatePositionsForState(algorithmState, ambientState);

        updateZValuesForState(algorithmState, ambientState);

        updateHeadsUpStates(algorithmState, ambientState);
        updatePulsingStates(algorithmState, ambientState);

        updateDimmedActivatedHideSensitive(ambientState, algorithmState);
        updateClipping(algorithmState, ambientState);
        updateSpeedBumpState(algorithmState, ambientState);
        updateShelfState(ambientState);
        getNotificationChildrenStates(algorithmState, ambientState);
    }

    private void resetChildViewStates() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) mHostView.getChildAt(i);
            child.resetViewState();
        }
    }

    private void getNotificationChildrenStates(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = algorithmState.visibleChildren.get(i);
            if (v instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) v;
                row.updateChildrenStates(ambientState);
            }
        }
    }

    private void updateSpeedBumpState(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        int belowSpeedBump = ambientState.getSpeedBumpIndex();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState childViewState = child.getViewState();

            // The speed bump can also be gone, so equality needs to be taken when comparing
            // indices.
            childViewState.belowSpeedBump = i >= belowSpeedBump;
        }

    }

    private void updateShelfState(AmbientState ambientState) {
        NotificationShelf shelf = ambientState.getShelf();
        if (shelf != null) {
            shelf.updateState(ambientState);
        }
    }

    private void updateClipping(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        float drawStart = !ambientState.isOnKeyguard() ? ambientState.getTopPadding()
                + ambientState.getStackTranslation() + ambientState.getExpandAnimationTopChange()
                : 0;
        float previousNotificationEnd = 0;
        float previousNotificationStart = 0;
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState state = child.getViewState();
            if (!child.mustStayOnScreen() || state.headsUpIsVisible) {
                previousNotificationEnd = Math.max(drawStart, previousNotificationEnd);
                previousNotificationStart = Math.max(drawStart, previousNotificationStart);
            }
            float newYTranslation = state.yTranslation;
            float newHeight = state.height;
            float newNotificationEnd = newYTranslation + newHeight;
            boolean isHeadsUp = (child instanceof ExpandableNotificationRow)
                    && ((ExpandableNotificationRow) child).isPinned();
            if (mClipNotificationScrollToTop
                    && !state.inShelf && newYTranslation < previousNotificationEnd
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
        if (!(v instanceof ExpandableNotificationRow)) {
            return false;
        }
        ExpandableNotificationRow row = (ExpandableNotificationRow) v;
        if (row.isBlockingHelperShowingAndTranslationFinished()) {
            return true;
        }
        if (row.areGutsExposed() || !row.getEntry().hasFinishedInitialization()) {
            return false;
        }
        return row.canViewBeDismissed();
    }

    /**
     * Updates the dimmed, activated and hiding sensitive states of the children.
     */
    private void updateDimmedActivatedHideSensitive(AmbientState ambientState,
            StackScrollAlgorithmState algorithmState) {
        boolean dimmed = ambientState.isDimmed();
        boolean dark = ambientState.isFullyDark();
        boolean hideSensitive = ambientState.isHideSensitive();
        View activatedChild = ambientState.getActivatedChild();
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState childViewState = child.getViewState();
            childViewState.dimmed = dimmed;
            childViewState.dark = dark;
            childViewState.hideSensitive = hideSensitive;
            boolean isActivatedChild = activatedChild == child;
            if (dimmed && isActivatedChild) {
                childViewState.zTranslation += 2.0f * ambientState.getZDistanceBetweenElements();
            }
        }
    }

    /**
     * Initialize the algorithm state like updating the visible children.
     */
    private void initAlgorithmState(ViewGroup hostView, StackScrollAlgorithmState state,
            AmbientState ambientState) {
        float bottomOverScroll = ambientState.getOverScrollAmount(false /* onTop */);

        int scrollY = ambientState.getScrollY();

        // Due to the overScroller, the stackscroller can have negative scroll state. This is
        // already accounted for by the top padding and doesn't need an additional adaption
        scrollY = Math.max(0, scrollY);
        state.scrollY = (int) (scrollY + bottomOverScroll);

        if (ANCHOR_SCROLLING) {
            state.anchorViewY = (int) (ambientState.getAnchorViewY() - bottomOverScroll);
        }

        //now init the visible children and update paddings
        int childCount = hostView.getChildCount();
        state.visibleChildren.clear();
        state.visibleChildren.ensureCapacity(childCount);
        state.paddingMap.clear();
        int notGoneIndex = 0;
        ExpandableView lastView = null;
        int firstHiddenIndex = ambientState.isDark()
                ? (ambientState.hasPulsingNotifications() ? 1 : 0)
                : childCount;

        // The goal here is to fill the padding map, by iterating over how much padding each child
        // needs. The map is thereby reused, by first filling it with the padding amount and when
        // iterating over it again, it's filled with the actual resolved value.

        for (int i = 0; i < childCount; i++) {
            if (ANCHOR_SCROLLING) {
                if (i == ambientState.getAnchorViewIndex()) {
                    state.anchorViewIndex = state.visibleChildren.size();
                }
            }
            ExpandableView v = (ExpandableView) hostView.getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                if (v == ambientState.getShelf()) {
                    continue;
                }
                if (i >= firstHiddenIndex) {
                    // we need normal padding now, to be in sync with what the stack calculates
                    lastView = null;
                }
                notGoneIndex = updateNotGoneIndex(state, notGoneIndex, v);
                float increasedPadding = v.getIncreasedPaddingAmount();
                if (increasedPadding != 0.0f) {
                    state.paddingMap.put(v, increasedPadding);
                    if (lastView != null) {
                        Float prevValue = state.paddingMap.get(lastView);
                        float newValue = getPaddingForValue(increasedPadding);
                        if (prevValue != null) {
                            float prevPadding = getPaddingForValue(prevValue);
                            if (increasedPadding > 0) {
                                newValue = NotificationUtils.interpolate(
                                        prevPadding,
                                        newValue,
                                        increasedPadding);
                            } else if (prevValue > 0) {
                                newValue = NotificationUtils.interpolate(
                                        newValue,
                                        prevPadding,
                                        prevValue);
                            }
                        }
                        state.paddingMap.put(lastView, newValue);
                    }
                } else if (lastView != null) {

                    // Let's now resolve the value to an actual padding
                    float newValue = getPaddingForValue(state.paddingMap.get(lastView));
                    state.paddingMap.put(lastView, newValue);
                }
                if (v instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) v;

                    // handle the notgoneIndex for the children as well
                    List<ExpandableNotificationRow> children = row.getNotificationChildren();
                    if (row.isSummaryWithChildren() && children != null) {
                        for (ExpandableNotificationRow childRow : children) {
                            if (childRow.getVisibility() != View.GONE) {
                                ExpandableViewState childState = childRow.getViewState();
                                childState.notGoneIndex = notGoneIndex;
                                notGoneIndex++;
                            }
                        }
                    }
                }
                lastView = v;
            }
        }
        ExpandableNotificationRow expandingNotification = ambientState.getExpandingNotification();
        state.indexOfExpandingNotification = expandingNotification != null
                ? expandingNotification.isChildInGroup()
                ? state.visibleChildren.indexOf(expandingNotification.getNotificationParent())
                : state.visibleChildren.indexOf(expandingNotification)
                : -1;
    }

    private float getPaddingForValue(Float increasedPadding) {
        if (increasedPadding == null) {
            return mPaddingBetweenElements;
        } else if (increasedPadding >= 0.0f) {
            return NotificationUtils.interpolate(
                    mPaddingBetweenElements,
                    mIncreasedPaddingBetweenElements,
                    increasedPadding);
        } else {
            return NotificationUtils.interpolate(
                    0,
                    mPaddingBetweenElements,
                    1.0f + increasedPadding);
        }
    }

    private int updateNotGoneIndex(StackScrollAlgorithmState state, int notGoneIndex,
            ExpandableView v) {
        ExpandableViewState viewState = v.getViewState();
        viewState.notGoneIndex = notGoneIndex;
        state.visibleChildren.add(v);
        notGoneIndex++;
        return notGoneIndex;
    }

    /**
     * Determine the positions for the views. This is the main part of the algorithm.
     *
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     * @param ambientState   The current ambient state
     */
    private void updatePositionsForState(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {

        if (ANCHOR_SCROLLING) {
            float currentYPosition = algorithmState.anchorViewY;
            int childCount = algorithmState.visibleChildren.size();
            for (int i = algorithmState.anchorViewIndex; i < childCount; i++) {
                if (i > algorithmState.anchorViewIndex && ambientState.beginsNewSection(i)) {
                    currentYPosition += mGapHeight;
                }
                currentYPosition = updateChild(i, algorithmState, ambientState, currentYPosition,
                        false /* reverse */);
            }
            currentYPosition = algorithmState.anchorViewY;
            for (int i = algorithmState.anchorViewIndex - 1; i >= 0; i--) {
                if (ambientState.beginsNewSection(i + 1)) {
                    currentYPosition -= mGapHeight;
                }
                currentYPosition = updateChild(i, algorithmState, ambientState, currentYPosition,
                        true /* reverse */);
            }
        } else {
            // The y coordinate of the current child.
            float currentYPosition = -algorithmState.scrollY;
            int childCount = algorithmState.visibleChildren.size();
            for (int i = 0; i < childCount; i++) {
                if (ambientState.beginsNewSection(i)) {
                    currentYPosition += mGapHeight;
                }
                currentYPosition = updateChild(i, algorithmState, ambientState, currentYPosition,
                        false /* reverse */);
            }
        }
    }

    /**
     * Populates the {@link ExpandableViewState} for a single child.
     *
     * @param i                The index of the child in
     * {@link StackScrollAlgorithmState#visibleChildren}.
     * @param algorithmState   The overall output state of the algorithm.
     * @param ambientState     The input state provided to the algorithm.
     * @param currentYPosition The Y position of the current pass of the algorithm.  For a forward
     *                         pass, this should be the top of the child; for a reverse pass, the
     *                         bottom of the child.
     * @param reverse          Whether we're laying out children in the reverse direction (Y
     *                         positions
     *                         decreasing) instead of the forward direction (Y positions
     *                         increasing).
     * @return The Y position after laying out the child.  This will be the {@code currentYPosition}
     * for the next call to this method, after adjusting for any gaps between children.
     */
    protected float updateChild(
            int i,
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState,
            float currentYPosition,
            boolean reverse) {
        ExpandableView child = algorithmState.visibleChildren.get(i);
        ExpandableViewState childViewState = child.getViewState();
        childViewState.location = ExpandableViewState.LOCATION_UNKNOWN;
        int paddingAfterChild = getPaddingAfterChild(algorithmState, child);
        int childHeight = getMaxAllowedChildHeight(child);
        if (reverse) {
            childViewState.yTranslation = currentYPosition - (childHeight + paddingAfterChild);
            if (currentYPosition <= 0) {
                childViewState.location = ExpandableViewState.LOCATION_HIDDEN_TOP;
            }
        } else {
            childViewState.yTranslation = currentYPosition;
        }
        boolean isFooterView = child instanceof FooterView;
        boolean isEmptyShadeView = child instanceof EmptyShadeView;

        childViewState.location = ExpandableViewState.LOCATION_MAIN_AREA;
        float inset = ambientState.getTopPadding() + ambientState.getStackTranslation();
        if (i <= algorithmState.getIndexOfExpandingNotification()) {
            inset += ambientState.getExpandAnimationTopChange();
        }
        if (child.mustStayOnScreen() && childViewState.yTranslation >= 0) {
            // Even if we're not scrolled away we're in view and we're also not in the
            // shelf. We can relax the constraints and let us scroll off the top!
            float end = childViewState.yTranslation + childViewState.height + inset;
            childViewState.headsUpIsVisible = end < ambientState.getMaxHeadsUpTranslation();
        }
        if (isFooterView) {
            childViewState.yTranslation = Math.min(childViewState.yTranslation,
                    ambientState.getInnerHeight() - childHeight);
        } else if (isEmptyShadeView) {
            childViewState.yTranslation = ambientState.getInnerHeight() - childHeight
                    + ambientState.getStackTranslation() * 0.25f;
        } else {
            clampPositionToShelf(child, childViewState, ambientState);
        }

        if (reverse) {
            currentYPosition = childViewState.yTranslation;
        } else {
            currentYPosition = childViewState.yTranslation + childHeight + paddingAfterChild;
            if (currentYPosition <= 0) {
                childViewState.location = ExpandableViewState.LOCATION_HIDDEN_TOP;
            }
        }
        if (childViewState.location == ExpandableViewState.LOCATION_UNKNOWN) {
            Log.wtf(LOG_TAG, "Failed to assign location for child " + i);
        }

        childViewState.yTranslation += inset;
        return currentYPosition;
    }

    protected int getPaddingAfterChild(StackScrollAlgorithmState algorithmState,
            ExpandableView child) {
        return algorithmState.getPaddingAfterChild(child);
    }

    private void updatePulsingStates(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            if (!row.isAmbientPulsing() || (i == 0 && ambientState.isPulseExpanding())) {
                continue;
            }
            ExpandableViewState viewState = row.getViewState();
            viewState.hidden = false;
        }
    }

    private void updateHeadsUpStates(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
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
            ExpandableViewState childState = row.getViewState();
            if (topHeadsUpEntry == null && row.mustStayOnScreen() && !childState.headsUpIsVisible) {
                topHeadsUpEntry = row;
                childState.location = ExpandableViewState.LOCATION_FIRST_HUN;
            }
            boolean isTopEntry = topHeadsUpEntry == row;
            float unmodifiedEndLocation = childState.yTranslation + childState.height;
            if (mIsExpanded) {
                if (row.mustStayOnScreen() && !childState.headsUpIsVisible) {
                    // Ensure that the heads up is always visible even when scrolled off
                    clampHunToTop(ambientState, row, childState);
                    if (i == 0 && ambientState.isAboveShelf(row)) {
                        // the first hun can't get off screen.
                        clampHunToMaxTranslation(ambientState, row, childState);
                        childState.hidden = false;
                    }
                }
            }
            if (row.isPinned()) {
                childState.yTranslation = Math.max(childState.yTranslation, mHeadsUpInset);
                childState.height = Math.max(row.getIntrinsicHeight(), childState.height);
                childState.hidden = false;
                ExpandableViewState topState =
                        topHeadsUpEntry == null ? null : topHeadsUpEntry.getViewState();
                if (topState != null && !isTopEntry && (!mIsExpanded
                        || unmodifiedEndLocation < topState.yTranslation + topState.height)) {
                    // Ensure that a headsUp doesn't vertically extend further than the heads-up at
                    // the top most z-position
                    childState.height = row.getIntrinsicHeight();
                    childState.yTranslation = topState.yTranslation + topState.height
                            - childState.height;
                }

                // heads up notification show and this row is the top entry of heads up
                // notifications. i.e. this row should be the only one row that has input field
                // To check if the row need to do translation according to scroll Y
                // heads up show full of row's content and any scroll y indicate that the
                // translationY need to move up the HUN.
                // TODO: fix this check for anchor scrolling.
                if (!mIsExpanded && isTopEntry && ambientState.getScrollY() > 0) {
                    childState.yTranslation -= ambientState.getScrollY();
                }
            }
            if (row.isHeadsUpAnimatingAway()) {
                childState.hidden = false;
            }
        }
    }

    private void clampHunToTop(AmbientState ambientState, ExpandableNotificationRow row,
            ExpandableViewState childState) {
        float newTranslation = Math.max(ambientState.getTopPadding()
                + ambientState.getStackTranslation(), childState.yTranslation);
        childState.height = (int) Math.max(childState.height - (newTranslation
                - childState.yTranslation), row.getCollapsedHeight());
        childState.yTranslation = newTranslation;
    }

    private void clampHunToMaxTranslation(AmbientState ambientState, ExpandableNotificationRow row,
            ExpandableViewState childState) {
        float newTranslation;
        float maxHeadsUpTranslation = ambientState.getMaxHeadsUpTranslation();
        float maxShelfPosition = ambientState.getInnerHeight() + ambientState.getTopPadding()
                + ambientState.getStackTranslation();
        maxHeadsUpTranslation = Math.min(maxHeadsUpTranslation, maxShelfPosition);
        float bottomPosition = maxHeadsUpTranslation - row.getCollapsedHeight();
        newTranslation = Math.min(childState.yTranslation, bottomPosition);
        childState.height = (int) Math.min(childState.height, maxHeadsUpTranslation
                - newTranslation);
        childState.yTranslation = newTranslation;
    }

    /**
     * Clamp the height of the child down such that its end is at most on the beginning of
     * the shelf.
     *
     * @param childViewState the view state of the child
     * @param ambientState   the ambient state
     */
    private void clampPositionToShelf(ExpandableView child,
            ExpandableViewState childViewState,
            AmbientState ambientState) {
        if (ambientState.getShelf() == null) {
            return;
        }

        int shelfStart = ambientState.getInnerHeight()
                - ambientState.getShelf().getIntrinsicHeight();
        if (ambientState.isAppearing() && !child.isAboveShelf()) {
            // Don't show none heads-up notifications while in appearing phase.
            childViewState.yTranslation = Math.max(childViewState.yTranslation, shelfStart);
        }
        childViewState.yTranslation = Math.min(childViewState.yTranslation, shelfStart);
        if (childViewState.yTranslation >= shelfStart) {
            childViewState.hidden = !child.isExpandAnimationRunning() && !child.hasExpandingChild();
            childViewState.inShelf = true;
            childViewState.headsUpIsVisible = false;
        }
    }

    protected int getMaxAllowedChildHeight(View child) {
        if (child instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) child;
            return expandableView.getIntrinsicHeight();
        }
        return child == null ? mCollapsedSize : child.getHeight();
    }

    /**
     * Calculate the Z positions for all children based on the number of items in both stacks and
     * save it in the resultState
     *
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     * @param ambientState   The ambient state of the algorithm
     */
    private void updateZValuesForState(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        float childrenOnTop = 0.0f;
        for (int i = childCount - 1; i >= 0; i--) {
            childrenOnTop = updateChildZValue(i, childrenOnTop,
                    algorithmState, ambientState);
        }
    }

    protected float updateChildZValue(int i, float childrenOnTop,
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        ExpandableView child = algorithmState.visibleChildren.get(i);
        ExpandableViewState childViewState = child.getViewState();
        int zDistanceBetweenElements = ambientState.getZDistanceBetweenElements();
        float baseZ = ambientState.getBaseZHeight();
        if (child.mustStayOnScreen() && !childViewState.headsUpIsVisible
                && !ambientState.isDozingAndNotPulsing(child)
                && childViewState.yTranslation < ambientState.getTopPadding()
                + ambientState.getStackTranslation()) {
            if (childrenOnTop != 0.0f) {
                childrenOnTop++;
            } else {
                float overlap = ambientState.getTopPadding()
                        + ambientState.getStackTranslation() - childViewState.yTranslation;
                childrenOnTop += Math.min(1.0f, overlap / childViewState.height);
            }
            childViewState.zTranslation = baseZ
                    + childrenOnTop * zDistanceBetweenElements;
        } else if (i == 0 && ambientState.isAboveShelf(child)) {
            // In case this is a new view that has never been measured before, we don't want to
            // elevate if we are currently expanded more then the notification
            int shelfHeight = ambientState.getShelf() == null ? 0 :
                    ambientState.getShelf().getIntrinsicHeight();
            float shelfStart = ambientState.getInnerHeight()
                    - shelfHeight + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            float notificationEnd = childViewState.yTranslation + child.getPinnedHeadsUpHeight()
                    + mPaddingBetweenElements;
            if (shelfStart > notificationEnd) {
                childViewState.zTranslation = baseZ;
            } else {
                float factor = (notificationEnd - shelfStart) / shelfHeight;
                factor = Math.min(factor, 1.0f);
                childViewState.zTranslation = baseZ + factor * zDistanceBetweenElements;
            }
        } else {
            childViewState.zTranslation = baseZ;
        }

        // We need to scrim the notification more from its surrounding content when we are pinned,
        // and we therefore elevate it higher.
        // We can use the headerVisibleAmount for this, since the value nicely goes from 0 to 1 when
        // expanding after which we have a normal elevation again.
        childViewState.zTranslation += (1.0f - child.getHeaderVisibleAmount())
                * mPinnedZTranslationExtra;
        return childrenOnTop;
    }

    public void setIsExpanded(boolean isExpanded) {
        this.mIsExpanded = isExpanded;
    }

    public class StackScrollAlgorithmState {

        /**
         * The scroll position of the algorithm (absolute scrolling).
         */
        public int scrollY;

        /** The index of the anchor view (anchor scrolling). */
        public int anchorViewIndex;

        /**
         * The Y position, relative to the top of the screen, of the anchor view (anchor scrolling).
         */
        public int anchorViewY;

        /**
         * The children from the host view which are not gone.
         */
        public final ArrayList<ExpandableView> visibleChildren = new ArrayList<ExpandableView>();

        /**
         * The padding after each child measured in pixels.
         */
        public final HashMap<ExpandableView, Float> paddingMap = new HashMap<>();
        private int indexOfExpandingNotification;

        public int getPaddingAfterChild(ExpandableView child) {
            Float padding = paddingMap.get(child);
            if (padding == null) {
                // Should only happen for the last view
                return mPaddingBetweenElements;
            }
            return (int) padding.floatValue();
        }

        public int getIndexOfExpandingNotification() {
            return indexOfExpandingNotification;
        }
    }

}
