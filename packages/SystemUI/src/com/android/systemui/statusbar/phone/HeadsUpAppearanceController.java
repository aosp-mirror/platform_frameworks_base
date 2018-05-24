/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.HeadsUpStatusBarView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Controls the appearance of heads up notifications in the icon area and the header itself.
 */
public class HeadsUpAppearanceController implements OnHeadsUpChangedListener,
        DarkIconDispatcher.DarkReceiver {
    public static final int CONTENT_FADE_DURATION = 110;
    public static final int CONTENT_FADE_DELAY = 100;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final NotificationStackScrollLayout mStackScroller;
    private final HeadsUpStatusBarView mHeadsUpStatusBarView;
    private final View mClockView;
    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NotificationPanelView mPanelView;
    private final Consumer<ExpandableNotificationRow>
            mSetTrackingHeadsUp = this::setTrackingHeadsUp;
    private final Runnable mUpdatePanelTranslation = this::updatePanelTranslation;
    private final BiConsumer<Float, Float> mSetExpandedHeight = this::setExpandedHeight;
    @VisibleForTesting
    float mExpandedHeight;
    @VisibleForTesting
    boolean mIsExpanded;
    @VisibleForTesting
    float mExpandFraction;
    private ExpandableNotificationRow mTrackedChild;
    private boolean mShown;
    private final View.OnLayoutChangeListener mStackScrollLayoutChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
                    -> updatePanelTranslation();
    Point mPoint;

    public HeadsUpAppearanceController(
            NotificationIconAreaController notificationIconAreaController,
            HeadsUpManagerPhone headsUpManager,
            View statusbarView) {
        this(notificationIconAreaController, headsUpManager,
                statusbarView.findViewById(R.id.heads_up_status_bar_view),
                statusbarView.findViewById(R.id.notification_stack_scroller),
                statusbarView.findViewById(R.id.notification_panel),
                statusbarView.findViewById(R.id.clock));
    }

    @VisibleForTesting
    public HeadsUpAppearanceController(
            NotificationIconAreaController notificationIconAreaController,
            HeadsUpManagerPhone headsUpManager,
            HeadsUpStatusBarView headsUpStatusBarView,
            NotificationStackScrollLayout stackScroller,
            NotificationPanelView panelView,
            View clockView) {
        mNotificationIconAreaController = notificationIconAreaController;
        mHeadsUpManager = headsUpManager;
        mHeadsUpManager.addListener(this);
        mHeadsUpStatusBarView = headsUpStatusBarView;
        headsUpStatusBarView.setOnDrawingRectChangedListener(
                () -> updateIsolatedIconLocation(true /* requireUpdate */));
        mStackScroller = stackScroller;
        mPanelView = panelView;
        panelView.addTrackingHeadsUpListener(mSetTrackingHeadsUp);
        panelView.addVerticalTranslationListener(mUpdatePanelTranslation);
        panelView.setHeadsUpAppearanceController(this);
        mStackScroller.addOnExpandedHeightListener(mSetExpandedHeight);
        mStackScroller.addOnLayoutChangeListener(mStackScrollLayoutChangeListener);
        mStackScroller.setHeadsUpAppearanceController(this);
        mClockView = clockView;
        mDarkIconDispatcher = Dependency.get(DarkIconDispatcher.class);
        mDarkIconDispatcher.addDarkReceiver(this);

        mHeadsUpStatusBarView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (shouldBeVisible()) {
                    updateTopEntry();

                    // trigger scroller to notify the latest panel translation
                    mStackScroller.requestLayout();
                }
                mHeadsUpStatusBarView.removeOnLayoutChangeListener(this);
            }
        });
    }


    public void destroy() {
        mHeadsUpManager.removeListener(this);
        mHeadsUpStatusBarView.setOnDrawingRectChangedListener(null);
        mPanelView.removeTrackingHeadsUpListener(mSetTrackingHeadsUp);
        mPanelView.removeVerticalTranslationListener(mUpdatePanelTranslation);
        mPanelView.setHeadsUpAppearanceController(null);
        mStackScroller.removeOnExpandedHeightListener(mSetExpandedHeight);
        mStackScroller.removeOnLayoutChangeListener(mStackScrollLayoutChangeListener);
        mDarkIconDispatcher.removeDarkReceiver(this);
    }

    private void updateIsolatedIconLocation(boolean requireStateUpdate) {
        mNotificationIconAreaController.setIsolatedIconLocation(
                mHeadsUpStatusBarView.getIconDrawingRect(), requireStateUpdate);
    }

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
        updateTopEntry();
        updateHeader(headsUp.getEntry());
    }

    /** To count the distance from the window right boundary to scroller right boundary. The
     * distance formula is the following:
     *     Y = screenSize - (SystemWindow's width + Scroller.getRight())
     * There are four modes MUST to be considered in Cut Out of RTL.
     * No Cut Out:
     *   Scroller + NB
     *   NB + Scroller
     *     => SystemWindow = NavigationBar's width
     *     => Y = screenSize - (SystemWindow's width + Scroller.getRight())
     * Corner Cut Out or Tall Cut Out:
     *   cut out + Scroller + NB
     *   NB + Scroller + cut out
     *     => SystemWindow = NavigationBar's width
     *     => Y = screenSize - (SystemWindow's width + Scroller.getRight())
     * Double Cut Out:
     *   cut out left + Scroller + (NB + cut out right)
     *     SystemWindow = NavigationBar's width + cut out right width
     *     => Y = screenSize - (SystemWindow's width + Scroller.getRight())
     *   (cut out left + NB) + Scroller + cut out right
     *     SystemWindow = NavigationBar's width + cut out left width
     *     => Y = screenSize - (SystemWindow's width + Scroller.getRight())
     * @return the translation X value for RTL. In theory, it should be negative. i.e. -Y
     */
    private int getRtlTranslation() {
        if (mPoint == null) {
            mPoint = new Point();
        }

        int realDisplaySize = 0;
        if (mStackScroller.getDisplay() != null) {
            mStackScroller.getDisplay().getRealSize(mPoint);
            realDisplaySize = mPoint.x;
        }

        WindowInsets windowInset = mStackScroller.getRootWindowInsets();
        DisplayCutout cutout = (windowInset != null) ? windowInset.getDisplayCutout() : null;
        int sysWinLeft = (windowInset != null) ? windowInset.getStableInsetLeft() : 0;
        int sysWinRight = (windowInset != null) ? windowInset.getStableInsetRight() : 0;
        int cutoutLeft = (cutout != null) ? cutout.getSafeInsetLeft() : 0;
        int cutoutRight = (cutout != null) ? cutout.getSafeInsetRight() : 0;
        int leftInset = Math.max(sysWinLeft, cutoutLeft);
        int rightInset = Math.max(sysWinRight, cutoutRight);

        return leftInset + mStackScroller.getRight() + rightInset - realDisplaySize;
    }

    public void updatePanelTranslation() {
        float newTranslation;
        if (mStackScroller.isLayoutRtl()) {
            newTranslation = getRtlTranslation();
        } else {
            newTranslation = mStackScroller.getLeft();
        }
        newTranslation += mStackScroller.getTranslationX();
        mHeadsUpStatusBarView.setPanelTranslation(newTranslation);
    }

    private void updateTopEntry() {
        NotificationData.Entry newEntry = null;
        if (!mIsExpanded && mHeadsUpManager.hasPinnedHeadsUp()) {
            newEntry = mHeadsUpManager.getTopEntry();
        }
        NotificationData.Entry previousEntry = mHeadsUpStatusBarView.getShowingEntry();
        mHeadsUpStatusBarView.setEntry(newEntry);
        if (newEntry != previousEntry) {
            boolean animateIsolation = false;
            if (newEntry == null) {
                // no heads up anymore, lets start the disappear animation

                setShown(false);
                animateIsolation = !mIsExpanded;
            } else if (previousEntry == null) {
                // We now have a headsUp and didn't have one before. Let's start the disappear
                // animation
                setShown(true);
                animateIsolation = !mIsExpanded;
            }
            updateIsolatedIconLocation(false /* requireUpdate */);
            mNotificationIconAreaController.showIconIsolated(newEntry == null ? null
                    : newEntry.icon, animateIsolation);
        }
    }

    private void setShown(boolean isShown) {
        if (mShown != isShown) {
            mShown = isShown;
            if (isShown) {
                mHeadsUpStatusBarView.setVisibility(View.VISIBLE);
                CrossFadeHelper.fadeIn(mHeadsUpStatusBarView, CONTENT_FADE_DURATION /* duration */,
                        CONTENT_FADE_DELAY /* delay */);
                CrossFadeHelper.fadeOut(mClockView, CONTENT_FADE_DURATION/* duration */,
                        0 /* delay */, () -> mClockView.setVisibility(View.INVISIBLE));
            } else {
                CrossFadeHelper.fadeIn(mClockView, CONTENT_FADE_DURATION /* duration */,
                        CONTENT_FADE_DELAY /* delay */);
                CrossFadeHelper.fadeOut(mHeadsUpStatusBarView, CONTENT_FADE_DURATION/* duration */,
                        0 /* delay */, () -> mHeadsUpStatusBarView.setVisibility(View.GONE));

            }
        }
    }

    @VisibleForTesting
    public boolean isShown() {
        return mShown;
    }

    /**
     * Should the headsup status bar view be visible right now? This may be different from isShown,
     * since the headsUp manager might not have notified us yet of the state change.
     *
     * @return if the heads up status bar view should be shown
     */
    public boolean shouldBeVisible() {
        return !mIsExpanded && mHeadsUpManager.hasPinnedHeadsUp();
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {
        updateTopEntry();
        updateHeader(headsUp.getEntry());
    }

    public void setExpandedHeight(float expandedHeight, float appearFraction) {
        boolean changedHeight = expandedHeight != mExpandedHeight;
        mExpandedHeight = expandedHeight;
        mExpandFraction = appearFraction;
        boolean isExpanded = expandedHeight > 0;
        if (changedHeight) {
            updateHeadsUpHeaders();
        }
        if (isExpanded != mIsExpanded) {
            mIsExpanded = isExpanded;
            updateTopEntry();
        }
    }

    /**
     * Set a headsUp to be tracked, meaning that it is currently being pulled down after being
     * in a pinned state on the top. The expand animation is different in that case and we need
     * to update the header constantly afterwards.
     *
     * @param trackedChild the tracked headsUp or null if it's not tracking anymore.
     */
    public void setTrackingHeadsUp(ExpandableNotificationRow trackedChild) {
        ExpandableNotificationRow previousTracked = mTrackedChild;
        mTrackedChild = trackedChild;
        if (previousTracked != null) {
            updateHeader(previousTracked.getEntry());
        }
    }

    private void updateHeadsUpHeaders() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            updateHeader(entry);
        });
    }

    public void updateHeader(NotificationData.Entry entry) {
        ExpandableNotificationRow row = entry.row;
        float headerVisibleAmount = 1.0f;
        if (row.isPinned() || row.isHeadsUpAnimatingAway() || row == mTrackedChild) {
            headerVisibleAmount = mExpandFraction;
        }
        row.setHeaderVisibleAmount(headerVisibleAmount);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mHeadsUpStatusBarView.onDarkChanged(area, darkIntensity, tint);
    }

    public void setPublicMode(boolean publicMode) {
        mHeadsUpStatusBarView.setPublicMode(publicMode);
        updateTopEntry();
    }

    void readFrom(HeadsUpAppearanceController oldController) {
        if (oldController != null) {
            mTrackedChild = oldController.mTrackedChild;
            mExpandedHeight = oldController.mExpandedHeight;
            mIsExpanded = oldController.mIsExpanded;
            mExpandFraction = oldController.mExpandFraction;
        }
    }
}
