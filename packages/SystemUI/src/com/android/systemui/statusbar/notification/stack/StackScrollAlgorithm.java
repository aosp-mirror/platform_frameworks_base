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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.dagger.SilentHeader;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.FooterView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Algorithm of the {@link com.android.systemui.statusbar.notification.stack
 * .NotificationStackScrollLayout} which can be queried for {@link com.android.systemui.statusbar
 * .stack.StackScrollState}
 */
public class StackScrollAlgorithm {

    public static final float START_FRACTION = 0.3f;

    private static final String LOG_TAG = "StackScrollAlgorithm";
    private final ViewGroup mHostView;

    private int mPaddingBetweenElements;
    private int mGapHeight;
    private int mCollapsedSize;

    private StackScrollAlgorithmState mTempAlgorithmState = new StackScrollAlgorithmState();
    private boolean mIsExpanded;
    private boolean mClipNotificationScrollToTop;
    private int mStatusBarHeight;
    private float mHeadsUpInset;
    private int mPinnedZTranslationExtra;
    private float mNotificationScrimPadding;

    public StackScrollAlgorithm(
            Context context,
            ViewGroup hostView) {
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
        mCollapsedSize = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mStatusBarHeight = res.getDimensionPixelSize(R.dimen.status_bar_height);
        mClipNotificationScrollToTop = res.getBoolean(R.bool.config_clipNotificationScrollToTop);
        mHeadsUpInset = mStatusBarHeight + res.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mPinnedZTranslationExtra = res.getDimensionPixelSize(
                R.dimen.heads_up_pinned_elevation);
        mGapHeight = res.getDimensionPixelSize(R.dimen.notification_section_divider_height);
        mNotificationScrimPadding = res.getDimensionPixelSize(R.dimen.notification_side_paddings);
    }

    /**
     * Updates the state of all children in the hostview based on this algorithm.
     */
    public void resetViewStates(AmbientState ambientState, int speedBumpIndex) {
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
        updateSpeedBumpState(algorithmState, speedBumpIndex);
        updateShelfState(algorithmState, ambientState);
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
            int speedBumpIndex) {
        int childCount = algorithmState.visibleChildren.size();
        int belowSpeedBump = speedBumpIndex;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState childViewState = child.getViewState();

            // The speed bump can also be gone, so equality needs to be taken when comparing
            // indices.
            childViewState.belowSpeedBump = i >= belowSpeedBump;
        }

    }

    private void updateShelfState(
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {

        NotificationShelf shelf = ambientState.getShelf();
        if (shelf != null) {
            shelf.updateState(algorithmState, ambientState);
        }
    }

    private void updateClipping(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        float drawStart = ambientState.isOnKeyguard() ? 0
                : ambientState.getStackY() - ambientState.getScrollY();
        float clipStart = ambientState.getNotificationScrimTop();
        int childCount = algorithmState.visibleChildren.size();
        boolean firstHeadsUp = true;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState state = child.getViewState();
            if (!child.mustStayOnScreen() || state.headsUpIsVisible) {
                clipStart = Math.max(drawStart, clipStart);
            }
            float newYTranslation = state.yTranslation;
            float newHeight = state.height;
            float newNotificationEnd = newYTranslation + newHeight;
            boolean isHeadsUp = (child instanceof ExpandableNotificationRow) && child.isPinned();
            if (mClipNotificationScrollToTop
                    && (!state.inShelf || (isHeadsUp && !firstHeadsUp))
                    && newYTranslation < clipStart
                    && !ambientState.isShadeOpening()) {
                // The previous view is overlapping on top, clip!
                float overlapAmount = clipStart - newYTranslation;
                state.clipTopAmount = (int) overlapAmount;
            } else {
                state.clipTopAmount = 0;
            }
            if (isHeadsUp) {
                firstHeadsUp = false;
            }
            if (!child.isTransparent()) {
                // Only update the previous values if we are not transparent,
                // otherwise we would clip to a transparent view.
                clipStart = Math.max(clipStart, isHeadsUp ? newYTranslation : newNotificationEnd);
            }
        }
    }

    /**
     * Updates the dimmed, activated and hiding sensitive states of the children.
     */
    private void updateDimmedActivatedHideSensitive(AmbientState ambientState,
            StackScrollAlgorithmState algorithmState) {
        boolean dimmed = ambientState.isDimmed();
        boolean hideSensitive = ambientState.isHideSensitive();
        View activatedChild = ambientState.getActivatedChild();
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState childViewState = child.getViewState();
            childViewState.dimmed = dimmed;
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
        state.mCurrentYPosition = -state.scrollY;
        state.mCurrentExpandedYPosition = -state.scrollY;

        //now init the visible children and update paddings
        int childCount = hostView.getChildCount();
        state.visibleChildren.clear();
        state.visibleChildren.ensureCapacity(childCount);
        int notGoneIndex = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = (ExpandableView) hostView.getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                if (v == ambientState.getShelf()) {
                    continue;
                }
                notGoneIndex = updateNotGoneIndex(state, notGoneIndex, v);
                if (v instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) v;

                    // handle the notgoneIndex for the children as well
                    List<ExpandableNotificationRow> children = row.getAttachedChildren();
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
            }
        }

        // Save the index of first view in shelf from when shade is fully
        // expanded. Consider updating these states in updateContentView instead so that we don't
        // have to recalculate in every frame.
        float currentY = -scrollY;
        if (!ambientState.isOnKeyguard()) {
            currentY += mNotificationScrimPadding;
        }
        state.firstViewInShelf = null;
        for (int i = 0; i < state.visibleChildren.size(); i++) {
            final ExpandableView view = state.visibleChildren.get(i);

            final boolean applyGapHeight = childNeedsGapHeight(
                    ambientState.getSectionProvider(), i,
                    view, getPreviousView(i, state));
            if (applyGapHeight) {
                currentY += mGapHeight;
            }

            if (ambientState.getShelf() != null) {
                final float shelfStart = ambientState.getStackEndHeight()
                        - ambientState.getShelf().getIntrinsicHeight();
                if (currentY >= shelfStart
                        && !(view instanceof FooterView)
                        && state.firstViewInShelf == null) {
                    state.firstViewInShelf = view;
                }
            }
            currentY = currentY
                    + getMaxAllowedChildHeight(view)
                    + mPaddingBetweenElements;
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

    private ExpandableView getPreviousView(int i, StackScrollAlgorithmState algorithmState) {
        return i > 0 ? algorithmState.visibleChildren.get(i - 1) : null;
    }

    /**
     * Determine the positions for the views. This is the main part of the algorithm.
     *
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     * @param ambientState   The current ambient state
     */
    private void updatePositionsForState(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        if (!ambientState.isOnKeyguard()) {
            algorithmState.mCurrentYPosition += mNotificationScrimPadding;
            algorithmState.mCurrentExpandedYPosition += mNotificationScrimPadding;
        }

        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            updateChild(i, algorithmState, ambientState);
        }
    }

    private void setLocation(ExpandableViewState expandableViewState, float currentYPosition,
            int i) {
        expandableViewState.location = ExpandableViewState.LOCATION_MAIN_AREA;
        if (currentYPosition <= 0) {
            expandableViewState.location = ExpandableViewState.LOCATION_HIDDEN_TOP;
        }
    }

    /**
     * @return Fraction to apply to view height and gap between views.
     *         Does not include shelf height even if shelf is showing.
     */
    private float getExpansionFractionWithoutShelf(
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {

        final boolean showingShelf = ambientState.getShelf() != null
                && algorithmState.firstViewInShelf != null;

        final float shelfHeight = showingShelf ? ambientState.getShelf().getIntrinsicHeight() : 0f;
        final float scrimPadding = ambientState.isOnKeyguard() ? 0 : mNotificationScrimPadding;

        final float stackHeight = ambientState.getStackHeight()  - shelfHeight - scrimPadding;
        final float stackEndHeight = ambientState.getStackEndHeight() - shelfHeight - scrimPadding;

        return stackHeight / stackEndHeight;
    }

    // TODO(b/172289889) polish shade open from HUN
    /**
     * Populates the {@link ExpandableViewState} for a single child.
     *
     * @param i                The index of the child in
     * {@link StackScrollAlgorithmState#visibleChildren}.
     * @param algorithmState   The overall output state of the algorithm.
     * @param ambientState     The input state provided to the algorithm.
     */
    protected void updateChild(
            int i,
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {

        ExpandableView view = algorithmState.visibleChildren.get(i);
        ExpandableViewState viewState = view.getViewState();
        viewState.location = ExpandableViewState.LOCATION_UNKNOWN;

        final boolean isHunGoingToShade = ambientState.isShadeExpanded()
                && view == ambientState.getTrackedHeadsUpRow();
        if (isHunGoingToShade) {
            // Keep 100% opacity for heads up notification going to shade.
        } else if (ambientState.isOnKeyguard()) {
            // Adjust alpha for wakeup to lockscreen.
            viewState.alpha = 1f - ambientState.getHideAmount();
        } else if (ambientState.isExpansionChanging()) {
            // Adjust alpha for shade open & close.
            viewState.alpha = Interpolators.getNotificationScrimAlpha(
                    ambientState.getExpansionFraction(), true /* notification */);
        }

        if (view.mustStayOnScreen() && viewState.yTranslation >= 0) {
            // Even if we're not scrolled away we're in view and we're also not in the
            // shelf. We can relax the constraints and let us scroll off the top!
            float end = viewState.yTranslation + viewState.height + ambientState.getStackY();
            viewState.headsUpIsVisible = end < ambientState.getMaxHeadsUpTranslation();
        }

        final float expansionFraction = getExpansionFractionWithoutShelf(
                algorithmState, ambientState);

        // Add gap between sections.
        final boolean applyGapHeight =
                childNeedsGapHeight(
                        ambientState.getSectionProvider(), i,
                        view, getPreviousView(i, algorithmState));
        if (applyGapHeight) {
            algorithmState.mCurrentYPosition += expansionFraction * mGapHeight;
            algorithmState.mCurrentExpandedYPosition += mGapHeight;
        }

        viewState.yTranslation = algorithmState.mCurrentYPosition;

        if (view instanceof FooterView) {
            final boolean shadeClosed = !ambientState.isShadeExpanded();
            final boolean isShelfShowing = algorithmState.firstViewInShelf != null;

            final float footerEnd = algorithmState.mCurrentExpandedYPosition
                    + view.getIntrinsicHeight();
            final boolean noSpaceForFooter = footerEnd > ambientState.getStackEndHeight();

            viewState.hidden = shadeClosed || isShelfShowing || noSpaceForFooter;

        } else if (view != ambientState.getTrackedHeadsUpRow()) {
            if (ambientState.isExpansionChanging()) {
                // Show all views. Views below the shelf will later be clipped (essentially hidden)
                // in NotificationShelf.
                viewState.hidden = false;
                viewState.inShelf = algorithmState.firstViewInShelf != null
                        && i >= algorithmState.visibleChildren.indexOf(
                                algorithmState.firstViewInShelf);
            } else if (ambientState.getShelf() != null) {
                // When pulsing (incoming notification on AOD), innerHeight is 0; clamp all
                // to shelf start, thereby hiding all notifications (except the first one, which we
                // later unhide in updatePulsingState)
                final int shelfStart = ambientState.getInnerHeight()
                        - ambientState.getShelf().getIntrinsicHeight();
                viewState.yTranslation = Math.min(viewState.yTranslation, shelfStart);
                if (viewState.yTranslation >= shelfStart) {
                    viewState.hidden = !view.isExpandAnimationRunning()
                            && !view.hasExpandingChild();
                    viewState.inShelf = true;
                    // Notifications in the shelf cannot be visible HUNs.
                    viewState.headsUpIsVisible = false;
                }
            }

            // Clip height of view right before shelf.
            viewState.height = (int) (getMaxAllowedChildHeight(view) * expansionFraction);
        }

        algorithmState.mCurrentYPosition += viewState.height
                + expansionFraction * mPaddingBetweenElements;
        algorithmState.mCurrentExpandedYPosition += view.getIntrinsicHeight()
                + mPaddingBetweenElements;

        setLocation(view.getViewState(), algorithmState.mCurrentYPosition, i);
        viewState.yTranslation += ambientState.getStackY();
    }

    /**
     * Get the gap height needed for before a view
     *
     * @param sectionProvider the sectionProvider used to understand the sections
     * @param visibleIndex the visible index of this view in the list
     * @param child the child asked about
     * @param previousChild the child right before it or null if none
     * @return the size of the gap needed or 0 if none is needed
     */
    public float getGapHeightForChild(
            SectionProvider sectionProvider,
            int visibleIndex,
            View child,
            View previousChild) {

        if (childNeedsGapHeight(sectionProvider, visibleIndex, child,
                previousChild)) {
            return mGapHeight;
        } else {
            return 0;
        }
    }

    /**
     * Does a given child need a gap, i.e spacing before a view?
     *
     * @param sectionProvider the sectionProvider used to understand the sections
     * @param visibleIndex the visible index of this view in the list
     * @param child the child asked about
     * @param previousChild the child right before it or null if none
     * @return if the child needs a gap height
     */
    private boolean childNeedsGapHeight(
            SectionProvider sectionProvider,
            int visibleIndex,
            View child,
            View previousChild) {
        return sectionProvider.beginsSection(child, previousChild)
                && visibleIndex > 0
                && !(previousChild instanceof SilentHeader)
                && !(child instanceof FooterView);
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
            if (!row.showingPulsing() || (i == 0 && ambientState.isPulseExpanding())) {
                continue;
            }
            ExpandableViewState viewState = row.getViewState();
            viewState.hidden = false;
        }
    }

    private void updateHeadsUpStates(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();

        // Move the tracked heads up into position during the appear animation, by interpolating
        // between the HUN inset (where it will appear as a HUN) and the end position in the shade
        ExpandableNotificationRow trackedHeadsUpRow = ambientState.getTrackedHeadsUpRow();
        if (trackedHeadsUpRow != null) {
            ExpandableViewState childState = trackedHeadsUpRow.getViewState();
            if (childState != null) {
                float endPosition = childState.yTranslation - ambientState.getStackTranslation();
                childState.yTranslation = MathUtils.lerp(
                        mHeadsUpInset, endPosition, ambientState.getAppearFraction());
            }
        }

        ExpandableNotificationRow topHeadsUpEntry = null;
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            if (!(row.isHeadsUp() || row.isHeadsUpAnimatingAway())) {
                continue;
            }
            ExpandableViewState childState = row.getViewState();
            if (topHeadsUpEntry == null && row.mustStayOnScreen() && !childState.headsUpIsVisible) {
                topHeadsUpEntry = row;
                childState.location = ExpandableViewState.LOCATION_FIRST_HUN;
            }
            boolean isTopEntry = topHeadsUpEntry == row;
            float unmodifiedEndLocation = childState.yTranslation + childState.height;
            if (mIsExpanded) {
                if (row.mustStayOnScreen() && !childState.headsUpIsVisible
                        && !row.showingPulsing()) {
                    // Ensure that the heads up is always visible even when scrolled off
                    clampHunToTop(ambientState, row, childState);
                    if (isTopEntry && row.isAboveShelf()) {
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
                        || unmodifiedEndLocation > topState.yTranslation + topState.height)) {
                    // Ensure that a headsUp doesn't vertically extend further than the heads-up at
                    // the top most z-position
                    childState.height = row.getIntrinsicHeight();
                    childState.yTranslation = Math.min(topState.yTranslation + topState.height
                            - childState.height, childState.yTranslation);
                }

                // heads up notification show and this row is the top entry of heads up
                // notifications. i.e. this row should be the only one row that has input field
                // To check if the row need to do translation according to scroll Y
                // heads up show full of row's content and any scroll y indicate that the
                // translationY need to move up the HUN.
                if (!mIsExpanded && isTopEntry && ambientState.getScrollY() > 0) {
                    childState.yTranslation -= ambientState.getScrollY();
                }
            }
            if (row.isHeadsUpAnimatingAway()) {
                childState.yTranslation = Math.max(childState.yTranslation, mHeadsUpInset);
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

        int topHunIndex = -1;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            if (child instanceof ActivatableNotificationView
                    && (child.isAboveShelf() || child.showingPulsing())) {
                topHunIndex = i;
                break;
            }
        }

        for (int i = childCount - 1; i >= 0; i--) {
            childrenOnTop = updateChildZValue(i, childrenOnTop,
                    algorithmState, ambientState, i == topHunIndex);
        }
    }

    protected float updateChildZValue(int i, float childrenOnTop,
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState,
            boolean shouldElevateHun) {
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
        } else if (shouldElevateHun) {
            // In case this is a new view that has never been measured before, we don't want to
            // elevate if we are currently expanded more then the notification
            int shelfHeight = ambientState.getShelf() == null ? 0 :
                    ambientState.getShelf().getIntrinsicHeight();
            float shelfStart = ambientState.getInnerHeight()
                    - shelfHeight + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            float notificationEnd = childViewState.yTranslation + child.getIntrinsicHeight()
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

        /**
         * First view in shelf.
         */
        public ExpandableView firstViewInShelf;

        /**
         * The children from the host view which are not gone.
         */
        public final ArrayList<ExpandableView> visibleChildren = new ArrayList<>();

        /**
         * Y position of the current view during updating children
         * with expansion factor applied.
         */
        private int mCurrentYPosition;

        /**
         * Y position of the current view during updating children
         * without applying the expansion factor.
         */
        private int mCurrentExpandedYPosition;
    }

    /**
     * Interface for telling the SSA when a new notification section begins (so it can add in
     * appropriate margins).
     */
    public interface SectionProvider {
        /**
         * True if this view starts a new "section" of notifications, such as the gentle
         * notifications section. False if sections are not enabled.
         */
        boolean beginsSection(@NonNull View view, @Nullable View previous);
    }
}
