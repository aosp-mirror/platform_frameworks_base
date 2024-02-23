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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.SystemBarUtils;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.res.R;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.shared.NotificationsImprovedHunAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * The Algorithm of the
 * {@link com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout} which can
 * be queried for {@link StackScrollAlgorithmState}
 */
public class StackScrollAlgorithm {

    public static final float START_FRACTION = 0.5f;

    private static final String TAG = "StackScrollAlgorithm";
    private static final Boolean DEBUG = false;
    private static final SourceType STACK_SCROLL_ALGO = SourceType.from("StackScrollAlgorithm");

    private final ViewGroup mHostView;
    private float mPaddingBetweenElements;
    private float mGapHeight;
    private float mGapHeightOnLockscreen;
    private int mCollapsedSize;
    private boolean mEnableNotificationClipping;

    private StackScrollAlgorithmState mTempAlgorithmState = new StackScrollAlgorithmState();
    private boolean mIsExpanded;
    private boolean mClipNotificationScrollToTop;
    @VisibleForTesting
    float mHeadsUpInset;
    @VisibleForTesting
    float mHeadsUpAppearStartAboveScreen;
    private int mPinnedZTranslationExtra;
    private float mNotificationScrimPadding;
    private int mMarginBottom;
    private float mQuickQsOffsetHeight;
    private float mSmallCornerRadius;
    private float mLargeCornerRadius;
    private int mHeadsUpAppearHeightBottom;

    public StackScrollAlgorithm(
            Context context,
            ViewGroup hostView) {
        mHostView = hostView;
        initView(context);
    }

    public void initView(Context context) {
        updateResources(context);
    }

    private void updateResources(Context context) {
        Resources res = context.getResources();
        mPaddingBetweenElements = res.getDimensionPixelSize(
                R.dimen.notification_divider_height);
        mCollapsedSize = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mEnableNotificationClipping = res.getBoolean(R.bool.notification_enable_clipping);
        mClipNotificationScrollToTop = res.getBoolean(R.bool.config_clipNotificationScrollToTop);
        int statusBarHeight = SystemBarUtils.getStatusBarHeight(context);
        mHeadsUpInset = statusBarHeight + res.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mHeadsUpAppearStartAboveScreen = res.getDimensionPixelSize(
                R.dimen.heads_up_appear_y_above_screen);
        mPinnedZTranslationExtra = res.getDimensionPixelSize(
                R.dimen.heads_up_pinned_elevation);
        mGapHeight = res.getDimensionPixelSize(R.dimen.notification_section_divider_height);
        mGapHeightOnLockscreen = res.getDimensionPixelSize(
                R.dimen.notification_section_divider_height_lockscreen);
        mNotificationScrimPadding = res.getDimensionPixelSize(R.dimen.notification_side_paddings);
        mMarginBottom = res.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom);
        mQuickQsOffsetHeight = SystemBarUtils.getQuickQsOffsetHeight(context);
        mSmallCornerRadius = res.getDimension(R.dimen.notification_corner_radius_small);
        mLargeCornerRadius = res.getDimension(R.dimen.notification_corner_radius);
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
        initAlgorithmState(algorithmState, ambientState);
        updatePositionsForState(algorithmState, ambientState);
        updateZValuesForState(algorithmState, ambientState);
        updateHeadsUpStates(algorithmState, ambientState);
        updatePulsingStates(algorithmState, ambientState);

        updateDimmedAndHideSensitive(ambientState, algorithmState);
        updateClipping(algorithmState, ambientState);
        updateSpeedBumpState(algorithmState, speedBumpIndex);
        updateShelfState(algorithmState, ambientState);
        updateAlphaState(algorithmState, ambientState);
        getNotificationChildrenStates(algorithmState);
    }

    private void updateAlphaState(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        for (ExpandableView view : algorithmState.visibleChildren) {
            final ViewState viewState = view.getViewState();
            final boolean isHunGoingToShade = ambientState.isShadeExpanded()
                    && view == ambientState.getTrackedHeadsUpRow();

            if (isHunGoingToShade) {
                // Keep 100% opacity for heads up notification going to shade.
                viewState.setAlpha(1f);
            } else if (ambientState.isOnKeyguard()) {
                // Adjust alpha for wakeup to lockscreen.
                if (view.isHeadsUpState()) {
                    // Pulsing HUN should be visible on AOD and stay visible during 
                    // AOD=>lockscreen transition
                    viewState.setAlpha(1f - ambientState.getHideAmount());
                } else {
                    // Normal notifications are hidden on AOD and should fade in during 
                    // AOD=>lockscreen transition
                    viewState.setAlpha(1f - ambientState.getDozeAmount());
                }
            } else if (ambientState.isExpansionChanging()) {
                // Adjust alpha for shade open & close.
                float expansion = ambientState.getExpansionFraction();
                if (ambientState.isBouncerInTransit()) {
                    viewState.setAlpha(
                            BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansion));
                } else if (view instanceof FooterView) {
                    viewState.setAlpha(interpolateFooterAlpha(ambientState));
                } else {
                    viewState.setAlpha(interpolateNotificationContentAlpha(ambientState));
                }
            }

            // For EmptyShadeView if on keyguard, we need to control the alpha to create
            // a nice transition when the user is dragging down the notification panel.
            if (view instanceof EmptyShadeView && ambientState.isOnKeyguard()) {
                final float fractionToShade = ambientState.getFractionToShade();
                viewState.setAlpha(ShadeInterpolation.getContentAlpha(fractionToShade));
            }

            NotificationShelf shelf = ambientState.getShelf();
            if (shelf != null) {
                final ViewState shelfState = shelf.getViewState();

                // After the shelf has updated its yTranslation, explicitly set alpha=0 for view
                // below shelf to skip rendering them in the hardware layer. We do not set them
                // invisible because that runs invalidate & onDraw when these views return onscreen,
                // which is more expensive.
                if (shelfState.hidden) {
                    // When the shelf is hidden, it won't clip views, so we don't hide rows
                    continue;
                }

                final float shelfTop = shelfState.getYTranslation();
                final float viewTop = viewState.getYTranslation();
                if (viewTop >= shelfTop) {
                    viewState.setAlpha(0);
                }
            }
        }
    }

    private float interpolateFooterAlpha(AmbientState ambientState) {
        float expansion = ambientState.getExpansionFraction();
        if (ambientState.isSmallScreen()) {
            return ShadeInterpolation.getContentAlpha(expansion);
        }
        LargeScreenShadeInterpolator interpolator = ambientState.getLargeScreenShadeInterpolator();
        return interpolator.getNotificationFooterAlpha(expansion);
    }

    private float interpolateNotificationContentAlpha(AmbientState ambientState) {
        float expansion = ambientState.getExpansionFraction();
        if (ambientState.isSmallScreen()) {
            return ShadeInterpolation.getContentAlpha(expansion);
        }
        LargeScreenShadeInterpolator interpolator = ambientState.getLargeScreenShadeInterpolator();
        return interpolator.getNotificationContentAlpha(expansion);
    }

    /**
     * How expanded or collapsed notifications are when pulling down the shade.
     *
     * @param ambientState Current ambient state.
     * @return 0 when fully collapsed, 1 when expanded.
     */
    public float getNotificationSquishinessFraction(AmbientState ambientState) {
        return getExpansionFractionWithoutShelf(mTempAlgorithmState, ambientState);
    }

    public void setHeadsUpAppearHeightBottom(int headsUpAppearHeightBottom) {
        mHeadsUpAppearHeightBottom = headsUpAppearHeightBottom;
    }

    /**
     * If the QuickSettings is showing full screen, we want to animate the HeadsUp Notifications
     * from the bottom of the screen.
     *
     * @param ambientState Current ambient state.
     * @param viewState The state of the HUN that is being queried to appear from the bottom.
     *
     * @return true if the HeadsUp Notifications should appear from the bottom
     */
    public boolean shouldHunAppearFromBottom(AmbientState ambientState,
            ExpandableViewState viewState) {
        return viewState.getYTranslation() + viewState.height
                >= ambientState.getMaxHeadsUpTranslation();
    }

    public static void log(String s) {
        if (DEBUG) {
            android.util.Log.i(TAG, s);
        }
    }

    public static void logView(View view, String s) {
        String viewString = "";
        if (view instanceof ExpandableNotificationRow row) {
            if (row.getEntry() == null) {
                viewString = "ExpandableNotificationRow has null NotificationEntry";
            } else {
                viewString = row.getEntry().getSbn().getId() + "";
            }
        } else if (view == null) {
            viewString = "View is null";
        } else if (view instanceof SectionHeaderView) {
            viewString = "SectionHeaderView";
        } else if (view instanceof FooterView) {
            viewString = "FooterView";
        } else if (view instanceof MediaContainerView) {
            viewString = "MediaContainerView";
        } else if (view instanceof EmptyShadeView) {
            viewString = "EmptyShadeView";
        } else {
            viewString = view.toString();
        }
        log(viewString + " " + s);
    }

    private void resetChildViewStates() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) mHostView.getChildAt(i);
            child.resetViewState();
        }
    }

    private void getNotificationChildrenStates(StackScrollAlgorithmState algorithmState) {
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = algorithmState.visibleChildren.get(i);
            if (v instanceof ExpandableNotificationRow row) {
                row.updateChildrenStates();
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
        if (shelf == null) {
            return;
        }

        shelf.updateState(algorithmState, ambientState);
    }

    private void updateClipping(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        float drawStart = ambientState.isOnKeyguard() ? 0
                : ambientState.getStackY() - ambientState.getScrollY();
        float clipStart = 0;
        int childCount = algorithmState.visibleChildren.size();
        boolean firstHeadsUp = true;
        float firstHeadsUpEnd = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState state = child.getViewState();
            if (!child.mustStayOnScreen() || state.headsUpIsVisible) {
                clipStart = Math.max(drawStart, clipStart);
            }
            float newYTranslation = state.getYTranslation();
            float newHeight = state.height;
            float newNotificationEnd = newYTranslation + newHeight;
            boolean isHeadsUp = (child instanceof ExpandableNotificationRow) && child.isPinned();
            if (mClipNotificationScrollToTop
                    && !firstHeadsUp
                    && (isHeadsUp || child.isHeadsUpAnimatingAway())
                    && newNotificationEnd > firstHeadsUpEnd
                    && !ambientState.isShadeExpanded()) {
                // The bottom of this view is peeking out from under the previous view.
                // Clip the part that is peeking out.
                float overlapAmount = newNotificationEnd - firstHeadsUpEnd;
                state.clipBottomAmount = mEnableNotificationClipping ? (int) overlapAmount : 0;
            } else {
                state.clipBottomAmount = 0;
            }
            if (firstHeadsUp) {
                firstHeadsUpEnd = newNotificationEnd;
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

    /** Updates the dimmed and hiding sensitive states of the children. */
    private void updateDimmedAndHideSensitive(AmbientState ambientState,
            StackScrollAlgorithmState algorithmState) {
        boolean dimmed = ambientState.isDimmed();
        boolean hideSensitive = ambientState.isHideSensitive();
        int childCount = algorithmState.visibleChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = algorithmState.visibleChildren.get(i);
            ExpandableViewState childViewState = child.getViewState();
            childViewState.dimmed = dimmed;
            childViewState.hideSensitive = hideSensitive;
        }
    }

    /**
     * Initialize the algorithm state like updating the visible children.
     */
    private void initAlgorithmState(StackScrollAlgorithmState state, AmbientState ambientState) {
        state.scrollY = ambientState.getScrollY();
        state.mCurrentYPosition = -state.scrollY;
        state.mCurrentExpandedYPosition = -state.scrollY;

        //now init the visible children and update paddings
        int childCount = mHostView.getChildCount();
        state.visibleChildren.clear();
        state.visibleChildren.ensureCapacity(childCount);
        int notGoneIndex = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableView v = (ExpandableView) mHostView.getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                if (v == ambientState.getShelf()) {
                    continue;
                }
                notGoneIndex = updateNotGoneIndex(state, notGoneIndex, v);
                if (v instanceof ExpandableNotificationRow row) {

                    // handle the notGoneIndex for the children as well
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
        float currentY = -ambientState.getScrollY();
        if (!ambientState.isOnKeyguard()
                || (ambientState.isBypassEnabled() && ambientState.isPulseExpanding())) {
            // add top padding at the start as long as we're not on the lock screen
            currentY += mNotificationScrimPadding;
        }
        state.firstViewInShelf = null;
        for (int i = 0; i < state.visibleChildren.size(); i++) {
            final ExpandableView view = state.visibleChildren.get(i);

            final boolean applyGapHeight = childNeedsGapHeight(
                    ambientState.getSectionProvider(), i,
                    view, getPreviousView(i, state));
            if (applyGapHeight) {
                currentY += getGapForLocation(
                        ambientState.getFractionToShade(), ambientState.isOnKeyguard());
            }

            if (ambientState.getShelf() != null) {
                final float shelfStart = ambientState.getStackEndHeight()
                        - ambientState.getShelf().getIntrinsicHeight()
                        - mPaddingBetweenElements;
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
     * Update the position of QS Frame.
     */
    public void updateQSFrameTop(int qsHeight) {
        // Intentionally empty for sub-classes in other device form factors to override
    }

    /**
     * Determine the positions for the views. This is the main part of the algorithm.
     *
     * @param algorithmState The state in which the current pass of the algorithm is currently in
     * @param ambientState   The current ambient state
     */
    protected void updatePositionsForState(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        if (!ambientState.isOnKeyguard()
                || (ambientState.isBypassEnabled() && ambientState.isPulseExpanding())) {
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
     * Does not include shelf height even if shelf is showing.
     */
    protected float getExpansionFractionWithoutShelf(
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {

        final boolean showingShelf = ambientState.getShelf() != null
                && algorithmState.firstViewInShelf != null;

        final float shelfHeight = showingShelf ? ambientState.getShelf().getIntrinsicHeight() : 0f;
        final float scrimPadding = ambientState.isOnKeyguard()
                && (!ambientState.isBypassEnabled() || !ambientState.isPulseExpanding())
                ? 0 : mNotificationScrimPadding;

        final float stackHeight = ambientState.getStackHeight() - shelfHeight - scrimPadding;
        final float stackEndHeight = ambientState.getStackEndHeight() - shelfHeight - scrimPadding;
        if (stackEndHeight == 0f) {
            // This should not happen, since even when the shade is empty we show EmptyShadeView
            // but check just in case, so we don't return infinity or NaN.
            return 0f;
        }
        return stackHeight / stackEndHeight;
    }

    private boolean hasNonClearableNotifs(StackScrollAlgorithmState algorithmState) {
        for (int i = 0; i < algorithmState.visibleChildren.size(); i++) {
            View child = algorithmState.visibleChildren.get(i);
            if (!(child instanceof ExpandableNotificationRow row)) {
                continue;
            }
            if (!row.canViewBeCleared()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    void maybeUpdateHeadsUpIsVisible(
            ExpandableViewState viewState,
            boolean isShadeExpanded,
            boolean mustStayOnScreen,
            boolean topVisible,
            float viewEnd,
            float hunMax) {
        if (isShadeExpanded && mustStayOnScreen && topVisible) {
            viewState.headsUpIsVisible = viewEnd < hunMax;
        }
    }

    // TODO(b/172289889) polish shade open from HUN

    /**
     * Populates the {@link ExpandableViewState} for a single child.
     *
     * @param i              The index of the child in
     *                       {@link StackScrollAlgorithmState#visibleChildren}.
     * @param algorithmState The overall output state of the algorithm.
     * @param ambientState   The input state provided to the algorithm.
     */
    protected void updateChild(
            int i,
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {

        ExpandableView view = algorithmState.visibleChildren.get(i);
        ExpandableViewState viewState = view.getViewState();
        viewState.location = ExpandableViewState.LOCATION_UNKNOWN;

        float expansionFraction = getExpansionFractionWithoutShelf(
                algorithmState, ambientState);

        // Add gap between sections.
        final boolean applyGapHeight =
                childNeedsGapHeight(
                        ambientState.getSectionProvider(), i,
                        view, getPreviousView(i, algorithmState));
        if (applyGapHeight) {
            final float gap = getGapForLocation(
                    ambientState.getFractionToShade(), ambientState.isOnKeyguard());
            algorithmState.mCurrentYPosition += expansionFraction * gap;
            algorithmState.mCurrentExpandedYPosition += gap;
        }

        // Must set viewState.yTranslation _before_ use.
        // Incoming views have yTranslation=0 by default.
        viewState.setYTranslation(algorithmState.mCurrentYPosition);

        float viewEnd = viewState.getYTranslation() + viewState.height + ambientState.getStackY();
        maybeUpdateHeadsUpIsVisible(viewState, ambientState.isShadeExpanded(),
                view.mustStayOnScreen(),
                /* topVisible= */ viewState.getYTranslation() >= mNotificationScrimPadding,
                viewEnd, /* hunMax */ ambientState.getMaxHeadsUpTranslation()
        );
        if (view instanceof FooterView) {
            if (FooterViewRefactor.isEnabled()) {
                final float footerEnd = algorithmState.mCurrentExpandedYPosition
                        + view.getIntrinsicHeight();
                final boolean noSpaceForFooter = footerEnd > ambientState.getStackEndHeight();
                // TODO(b/293167744): May be able to keep only noSpaceForFooter here if we add an
                //  emission when clearAllNotifications is called, and then use that in the footer
                //  visibility flow.
                ((FooterView.FooterViewState) viewState).hideContent =
                        noSpaceForFooter || (ambientState.isClearAllInProgress()
                                && !hasNonClearableNotifs(algorithmState));

            } else {
                final boolean shadeClosed = !ambientState.isShadeExpanded();
                final boolean isShelfShowing = algorithmState.firstViewInShelf != null;
                if (shadeClosed) {
                    viewState.hidden = true;
                } else {
                    final float footerEnd = algorithmState.mCurrentExpandedYPosition
                            + view.getIntrinsicHeight();
                    final boolean noSpaceForFooter = footerEnd > ambientState.getStackEndHeight();
                    ((FooterView.FooterViewState) viewState).hideContent =
                            isShelfShowing || noSpaceForFooter
                                    || (ambientState.isClearAllInProgress()
                                    && !hasNonClearableNotifs(algorithmState));
                }
            }
        } else {
            if (view instanceof EmptyShadeView) {
                float fullHeight = ambientState.getLayoutMaxHeight() + mMarginBottom
                        - ambientState.getStackY();
                viewState.setYTranslation((fullHeight - getMaxAllowedChildHeight(view)) / 2f);
            } else if (view != ambientState.getTrackedHeadsUpRow()) {
                if (ambientState.isExpansionChanging()) {
                    // We later update shelf state, then hide views below the shelf.
                    viewState.hidden = false;
                    viewState.inShelf = algorithmState.firstViewInShelf != null
                            && i >= algorithmState.visibleChildren.indexOf(
                            algorithmState.firstViewInShelf);
                } else if (ambientState.getShelf() != null) {
                    // When pulsing (incoming notification on AOD), innerHeight is 0; clamp all
                    // to shelf start, thereby hiding all notifications (except the first one, which
                    // we later unhide in updatePulsingState)
                    // TODO(b/192348384): merge InnerHeight with StackHeight
                    // Note: Bypass pulse looks different, but when it is not expanding, we need
                    //  to use the innerHeight which doesn't update continuously, otherwise we show
                    //  more notifications than we should during this special transitional states.
                    boolean bypassPulseNotExpanding = ambientState.isBypassEnabled()
                            && ambientState.isOnKeyguard() && !ambientState.isPulseExpanding();
                    final float stackBottom = !ambientState.isShadeExpanded()
                            || ambientState.getDozeAmount() == 1f
                            || bypassPulseNotExpanding
                            ? ambientState.getInnerHeight()
                            : ambientState.getStackHeight();
                    final float shelfStart = stackBottom
                            - ambientState.getShelf().getIntrinsicHeight()
                            - mPaddingBetweenElements;
                    updateViewWithShelf(view, viewState, shelfStart);
                }
            }
            viewState.height = getMaxAllowedChildHeight(view);
            if (!view.isPinned() && !view.isHeadsUpAnimatingAway()
                    && !ambientState.isPulsingRow(view)) {
                // The expansion fraction should not affect HUNs or pulsing notifications.
                viewState.height *= expansionFraction;
            }
        }

        algorithmState.mCurrentYPosition +=
                expansionFraction * (getMaxAllowedChildHeight(view) + mPaddingBetweenElements);
        algorithmState.mCurrentExpandedYPosition += view.getIntrinsicHeight()
                + mPaddingBetweenElements;

        setLocation(view.getViewState(), algorithmState.mCurrentYPosition, i);
        viewState.setYTranslation(viewState.getYTranslation() + ambientState.getStackY());
    }

    @VisibleForTesting
    void updateViewWithShelf(ExpandableView view, ExpandableViewState viewState, float shelfStart) {
        viewState.setYTranslation(Math.min(viewState.getYTranslation(), shelfStart));
        if (viewState.getYTranslation() >= shelfStart) {
            viewState.hidden = !view.isExpandAnimationRunning()
                    && !view.hasExpandingChild();
            viewState.inShelf = true;
            // Notifications in the shelf cannot be visible HUNs.
            viewState.headsUpIsVisible = false;
        }
    }

    /**
     * Get the gap height needed for before a view
     *
     * @param sectionProvider the sectionProvider used to understand the sections
     * @param visibleIndex    the visible index of this view in the list
     * @param child           the child asked about
     * @param previousChild   the child right before it or null if none
     * @return the size of the gap needed or 0 if none is needed
     */
    public float getGapHeightForChild(
            SectionProvider sectionProvider,
            int visibleIndex,
            View child,
            View previousChild,
            float fractionToShade,
            boolean onKeyguard) {

        if (childNeedsGapHeight(sectionProvider, visibleIndex, child,
                previousChild)) {
            return getGapForLocation(fractionToShade, onKeyguard);
        } else {
            return 0;
        }
    }

    @VisibleForTesting
    float getGapForLocation(float fractionToShade, boolean onKeyguard) {
        if (fractionToShade > 0f) {
            return MathUtils.lerp(mGapHeightOnLockscreen, mGapHeight, fractionToShade);
        }
        if (onKeyguard) {
            return mGapHeightOnLockscreen;
        }
        return mGapHeight;
    }

    /**
     * Does a given child need a gap, i.e spacing before a view?
     *
     * @param sectionProvider the sectionProvider used to understand the sections
     * @param visibleIndex    the visible index of this view in the list
     * @param child           the child asked about
     * @param previousChild   the child right before it or null if none
     * @return if the child needs a gap height
     */
    private boolean childNeedsGapHeight(
            SectionProvider sectionProvider,
            int visibleIndex,
            View child,
            View previousChild) {
        return sectionProvider.beginsSection(child, previousChild)
                && visibleIndex > 0
                && !(previousChild instanceof SectionHeaderView)
                && !(child instanceof FooterView);
    }

    @VisibleForTesting
    void updatePulsingStates(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();
        ExpandableNotificationRow pulsingRow = null;
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            if (!(child instanceof ExpandableNotificationRow row)) {
                continue;
            }
            if (!row.showingPulsing() || (i == 0 && ambientState.isPulseExpanding())) {
                continue;
            }
            ExpandableViewState viewState = row.getViewState();
            viewState.hidden = false;
            pulsingRow = row;
        }

        // Set AmbientState#pulsingRow to the current pulsing row when on AOD.
        // Set AmbientState#pulsingRow=null when on lockscreen, since AmbientState#pulsingRow
        // is only used for skipping the unfurl animation for (the notification that was already
        // showing at full height on AOD) during the AOD=>lockscreen transition, where
        // dozeAmount=[1f, 0f). We also need to reset the pulsingRow once it is no longer used
        // because it will interfere with future unfurling animations - for example, during the
        // LS=>AOD animation, the pulsingRow may stay at full height when it should squish with the
        // rest of the stack.
        if (ambientState.getDozeAmount() == 0.0f || ambientState.getDozeAmount() == 1.0f) {
            ambientState.setPulsingRow(pulsingRow);
        }
    }

    private void updateHeadsUpStates(StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int childCount = algorithmState.visibleChildren.size();

        // Move the tracked heads up into position during the appear animation, by interpolating
        // between the HUN inset (where it will appear as a HUN) and the end position in the shade
        float headsUpTranslation = mHeadsUpInset - ambientState.getStackTopMargin();
        ExpandableNotificationRow trackedHeadsUpRow = ambientState.getTrackedHeadsUpRow();
        if (trackedHeadsUpRow != null) {
            ExpandableViewState childState = trackedHeadsUpRow.getViewState();
            if (childState != null) {
                float endPos = childState.getYTranslation() - ambientState.getStackTranslation();
                childState.setYTranslation(MathUtils.lerp(
                        headsUpTranslation, endPos, ambientState.getAppearFraction()));
            }
        }

        ExpandableNotificationRow topHeadsUpEntry = null;
        for (int i = 0; i < childCount; i++) {
            View child = algorithmState.visibleChildren.get(i);
            if (!(child instanceof ExpandableNotificationRow row)) {
                continue;
            }
            if (!(row.isHeadsUp() || row.isHeadsUpAnimatingAway())) {
                continue;
            }
            ExpandableViewState childState = row.getViewState();
            if (topHeadsUpEntry == null && row.mustStayOnScreen() && !childState.headsUpIsVisible) {
                topHeadsUpEntry = row;
                childState.location = ExpandableViewState.LOCATION_FIRST_HUN;
            }
            boolean isTopEntry = topHeadsUpEntry == row;
            float unmodifiedEndLocation = childState.getYTranslation() + childState.height;
            if (mIsExpanded) {
                if (shouldHunBeVisibleWhenScrolled(row.mustStayOnScreen(),
                        childState.headsUpIsVisible, row.showingPulsing(),
                        ambientState.isOnKeyguard(), row.getEntry().isStickyAndNotDemoted())) {
                    // Ensure that the heads up is always visible even when scrolled off.
                    // NSSL y starts at top of screen in non-split-shade, but below the qs offset
                    // in split shade, so we only need to inset by the scrim padding in split shade.
                    final float clampInset = ambientState.getUseSplitShade()
                            ? mNotificationScrimPadding : mQuickQsOffsetHeight;
                    clampHunToTop(clampInset, ambientState.getStackTranslation(),
                            row.getCollapsedHeight(), childState);
                    if (isTopEntry && row.isAboveShelf()) {
                        // the first hun can't get off screen.
                        clampHunToMaxTranslation(ambientState, row, childState);
                        childState.hidden = false;
                    }
                }
            }
            if (row.isPinned()) {
                // Make sure row yTranslation is at at least the HUN yTranslation,
                // which accounts for AmbientState.stackTopMargin in split-shade.
                // Once we start opening the shade, we keep the previously calculated translation.
                childState.setYTranslation(
                        Math.max(childState.getYTranslation(), headsUpTranslation));
                childState.height = Math.max(row.getIntrinsicHeight(), childState.height);
                childState.hidden = false;
                ExpandableViewState topState =
                        topHeadsUpEntry == null ? null : topHeadsUpEntry.getViewState();
                if (topState != null && !isTopEntry && (!mIsExpanded
                        || unmodifiedEndLocation > topState.getYTranslation() + topState.height)) {
                    // Ensure that a headsUp doesn't vertically extend further than the heads-up at
                    // the top most z-position
                    childState.height = row.getIntrinsicHeight();
                }

                // heads up notification show and this row is the top entry of heads up
                // notifications. i.e. this row should be the only one row that has input field
                // To check if the row need to do translation according to scroll Y
                // heads up show full of row's content and any scroll y indicate that the
                // translationY need to move up the HUN.
                if (!mIsExpanded && isTopEntry && ambientState.getScrollY() > 0) {
                    childState.setYTranslation(
                            childState.getYTranslation() - ambientState.getScrollY());
                }
            }
            if (row.isHeadsUpAnimatingAway()) {
                if (NotificationsImprovedHunAnimation.isEnabled() && !ambientState.isDozing()) {
                    if (shouldHunAppearFromBottom(ambientState, childState)) {
                        // move to the bottom of the screen
                        childState.setYTranslation(
                                mHeadsUpAppearHeightBottom + mHeadsUpAppearStartAboveScreen);
                    } else {
                        // move to the top of the screen
                        childState.setYTranslation(-ambientState.getStackTopMargin()
                                - mHeadsUpAppearStartAboveScreen);
                    }
                } else {
                    // Make sure row yTranslation is at maximum the HUN yTranslation,
                    // which accounts for AmbientState.stackTopMargin in split-shade.
                    childState.setYTranslation(
                            Math.max(childState.getYTranslation(), headsUpTranslation));
                }
                // keep it visible for the animation
                childState.hidden = false;
            }
        }
    }

    @VisibleForTesting
    boolean shouldHunBeVisibleWhenScrolled(boolean mustStayOnScreen, boolean headsUpIsVisible,
            boolean showingPulsing, boolean isOnKeyguard, boolean headsUpOnKeyguard) {
        return mustStayOnScreen && !headsUpIsVisible
                && !showingPulsing
                && (!isOnKeyguard || headsUpOnKeyguard);
    }

    /**
     * When shade is open and we are scrolled to the bottom of notifications,
     * clamp incoming HUN in its collapsed form, right below qs offset.
     * Transition pinned collapsed HUN to full height when scrolling back up.
     */
    @VisibleForTesting
    void clampHunToTop(float clampInset, float stackTranslation, float collapsedHeight,
            ExpandableViewState viewState) {

        final float newTranslation = Math.max(clampInset + stackTranslation,
                viewState.getYTranslation());

        // Transition from collapsed pinned state to fully expanded state
        // when the pinned HUN approaches its actual location (when scrolling back to top).
        final float distToRealY = newTranslation - viewState.getYTranslation();
        viewState.height = (int) Math.max(viewState.height - distToRealY, collapsedHeight);
        viewState.setYTranslation(newTranslation);
    }

    // Pin HUN to bottom of expanded QS
    // while the rest of notifications are scrolled offscreen.
    private void clampHunToMaxTranslation(AmbientState ambientState, ExpandableNotificationRow row,
            ExpandableViewState childState) {
        float maxHeadsUpTranslation = ambientState.getMaxHeadsUpTranslation();
        final float maxShelfPosition = ambientState.getInnerHeight() + ambientState.getTopPadding()
                + ambientState.getStackTranslation();
        maxHeadsUpTranslation = Math.min(maxHeadsUpTranslation, maxShelfPosition);

        final float bottomPosition = maxHeadsUpTranslation - row.getCollapsedHeight();
        final float newTranslation = Math.min(childState.getYTranslation(), bottomPosition);
        childState.height = (int) Math.min(childState.height, maxHeadsUpTranslation
                - newTranslation);
        childState.setYTranslation(newTranslation);

        // Animate pinned HUN bottom corners to and from original roundness.
        final float originalCornerRadius =
                row.isLastInSection() ? 1f : (mSmallCornerRadius / mLargeCornerRadius);
        final float bottomValue = computeCornerRoundnessForPinnedHun(mHostView.getHeight(),
                ambientState.getStackY(), getMaxAllowedChildHeight(row), originalCornerRadius);
        row.requestBottomRoundness(bottomValue, STACK_SCROLL_ALGO);
        row.addOnDetachResetRoundness(STACK_SCROLL_ALGO);
    }

    @VisibleForTesting
    float computeCornerRoundnessForPinnedHun(float hostViewHeight, float stackY,
            float viewMaxHeight, float originalCornerRadius) {

        // Compute y where corner roundness should be in its original unpinned state.
        // We use view max height because the pinned collapsed HUN expands to max height
        // when it becomes unpinned.
        final float originalRoundnessY = hostViewHeight - viewMaxHeight;

        final float distToOriginalRoundness = Math.max(0f, stackY - originalRoundnessY);
        final float progressToPinnedRoundness = Math.min(1f,
                distToOriginalRoundness / viewMaxHeight);

        return MathUtils.lerp(originalCornerRadius, 1f, progressToPinnedRoundness);
    }

    protected int getMaxAllowedChildHeight(View child) {
        if (child instanceof ExpandableView expandableView) {
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

    /**
     * Calculate and update the Z positions for a given child. We currently only give shadows to
     * HUNs to distinguish a HUN from its surroundings.
     *
     * @param isTopHun      Whether the child is a top HUN. A top HUN means a HUN that shows on the
     *                      vertically top of screen. Top HUNs should have drop shadows
     * @param childrenOnTop It is greater than 0 when there's an existing HUN that is elevated
     * @return childrenOnTop The decimal part represents the fraction of the elevated HUN's height
     * that overlaps with QQS Panel. The integer part represents the count of
     * previous HUNs whose Z positions are greater than 0.
     */
    protected float updateChildZValue(int i, float childrenOnTop,
            StackScrollAlgorithmState algorithmState,
            AmbientState ambientState,
            boolean isTopHun) {
        ExpandableView child = algorithmState.visibleChildren.get(i);
        ExpandableViewState childViewState = child.getViewState();
        float baseZ = ambientState.getBaseZHeight();

        if (child.mustStayOnScreen() && !childViewState.headsUpIsVisible
                && !ambientState.isDozingAndNotPulsing(child)
                && childViewState.getYTranslation() < ambientState.getTopPadding()
                + ambientState.getStackTranslation()) {

            if (childrenOnTop != 0.0f) {
                // To elevate the later HUN over previous HUN when multiple HUNs exist
                childrenOnTop++;
            } else {
                // Handles HUN shadow when Shade is opened, and AmbientState.mScrollY > 0
                // Calculate the HUN's z-value based on its overlapping fraction with QQS Panel.
                // When scrolling down shade to make HUN back to in-position in Notification Panel,
                // The overlapping fraction goes to 0, and shadows hides gradually.
                float overlap = ambientState.getTopPadding()
                        + ambientState.getStackTranslation() - childViewState.getYTranslation();
                // To prevent over-shadow during HUN entry
                childrenOnTop += Math.min(
                        1.0f,
                        overlap / childViewState.height
                );
            }
            childViewState.setZTranslation(baseZ
                    + childrenOnTop * mPinnedZTranslationExtra);
        } else if (isTopHun) {
            // In case this is a new view that has never been measured before, we don't want to
            // elevate if we are currently expanded more than the notification
            int shelfHeight = ambientState.getShelf() == null ? 0 :
                    ambientState.getShelf().getIntrinsicHeight();
            float shelfStart = ambientState.getInnerHeight()
                    - shelfHeight + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            float notificationEnd = childViewState.getYTranslation() + child.getIntrinsicHeight()
                    + mPaddingBetweenElements;
            if (shelfStart > notificationEnd) {
                // When the notification doesn't overlap with Notification Shelf, there's no shadow
                childViewState.setZTranslation(baseZ);
            } else {
                // Give shadow to the notification if it overlaps with Notification Shelf
                float factor = (notificationEnd - shelfStart) / shelfHeight;
                if (Float.isNaN(factor)) { // Avoid problems when the above is 0/0.
                    factor = 1.0f;
                }
                factor = Math.min(factor, 1.0f);
                childViewState.setZTranslation(baseZ + factor * mPinnedZTranslationExtra);
            }
        } else {
            childViewState.setZTranslation(baseZ);
        }

        // While HUN is showing and Shade is closed: headerVisibleAmount stays 0, shadow stays.
        // During HUN-to-Shade (eg. dragging down HUN to open Shade): headerVisibleAmount goes
        // gradually from 0 to 1, shadow hides gradually.
        // Header visibility is a deprecated concept, we are using headerVisibleAmount only because
        // this value nicely goes from 0 to 1 during the HUN-to-Shade process.

        childViewState.setZTranslation(childViewState.getZTranslation()
                + (1.0f - child.getHeaderVisibleAmount()) * mPinnedZTranslationExtra);
        return childrenOnTop;
    }

    public void setIsExpanded(boolean isExpanded) {
        this.mIsExpanded = isExpanded;
    }

    public static class StackScrollAlgorithmState {

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
        private float mCurrentYPosition;

        /**
         * Y position of the current view during updating children
         * without applying the expansion factor.
         */
        private float mCurrentExpandedYPosition;
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

    /**
     * Interface for telling the StackScrollAlgorithm information about the bypass state
     */
    public interface BypassController {
        /**
         * True if bypass is enabled.  Note that this is always false if face auth is not enabled.
         */
        boolean isBypassEnabled();
    }
}
