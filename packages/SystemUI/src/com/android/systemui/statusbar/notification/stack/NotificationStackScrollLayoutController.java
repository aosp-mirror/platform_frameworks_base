/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import android.graphics.PointF;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;

import java.util.function.BiConsumer;

import javax.inject.Inject;

/**
 * Controller for {@link NotificationStackScrollLayout}.
 */
@StatusBarComponent.StatusBarScope
public class NotificationStackScrollLayoutController {
    private NotificationStackScrollLayout mView;

    @Inject
    public NotificationStackScrollLayoutController() {
    }

    public void attach(NotificationStackScrollLayout view) {
        mView = view;
        mView.setController(this);
    }

    public void addOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mView.addOnExpandedHeightChangedListener(listener);
    }

    public void removeOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mView.removeOnExpandedHeightChangedListener(listener);
    }

    public void addOnLayoutChangeListener(View.OnLayoutChangeListener listener) {
        mView.addOnLayoutChangeListener(listener);
    }

    public void removeOnLayoutChangeListener(View.OnLayoutChangeListener listener) {
        mView.removeOnLayoutChangeListener(listener);
    }

    public void setHeadsUpAppearanceController(HeadsUpAppearanceController controller) {
        mView.setHeadsUpAppearanceController(controller);
    }

    public void requestLayout() {
        mView.requestLayout();
    }

    public Display getDisplay() {
        return mView.getDisplay();
    }

    public WindowInsets getRootWindowInsets() {
        return mView.getRootWindowInsets();
    }

    public int getRight() {
        return mView.getRight();
    }

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
    }

    public float getLeft() {
        return  mView.getLeft();
    }

    public float getTranslationX() {
        return mView.getTranslationX();
    }

    public void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener listener) {
        mView.setOnHeightChangedListener(listener);
    }

    public void setOverscrollTopChangedListener(
            NotificationStackScrollLayout.OnOverscrollTopChangedListener listener) {
        mView.setOverscrollTopChangedListener(listener);
    }

    public void setOnEmptySpaceClickListener(
            NotificationStackScrollLayout.OnEmptySpaceClickListener listener) {
        mView.setOnEmptySpaceClickListener(listener);
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow expandableNotificationRow) {
        mView.setTrackingHeadsUp(expandableNotificationRow);
    }

    public void wakeUpFromPulse() {
        mView.wakeUpFromPulse();
    }

    public boolean isPulseExpanding() {
        return mView.isPulseExpanding();
    }

    public void setOnPulseHeightChangedListener(Runnable listener) {
        mView.setOnPulseHeightChangedListener(listener);
    }

    public void setDozeAmount(float amount) {
        mView.setDozeAmount(amount);
    }

    public float getWakeUpHeight() {
        return mView.getWakeUpHeight();
    }

    public void setHideAmount(float linearAmount, float amount) {
        mView.setHideAmount(linearAmount, amount);
    }

    public void notifyHideAnimationStart(boolean hide) {
        mView.notifyHideAnimationStart(hide);
    }

    public float setPulseHeight(float height) {
        return mView.setPulseHeight(height);
    }

    public void getLocationOnScreen(int[] outLocation) {
        mView.getLocationOnScreen(outLocation);
    }

    public ExpandableView getChildAtRawPosition(float x, float y) {
        return mView.getChildAtRawPosition(x, y);
    }

    public FrameLayout.LayoutParams getLayoutParams() {
        return (FrameLayout.LayoutParams) mView.getLayoutParams();
    }

    public void setLayoutParams(FrameLayout.LayoutParams lp) {
        mView.setLayoutParams(lp);
    }

    public void setIsFullWidth(boolean isFullWidth) {
        mView.setIsFullWidth(isFullWidth);
    }

    public boolean isAddOrRemoveAnimationPending() {
        return mView.isAddOrRemoveAnimationPending();
    }

    public int getVisibleNotificationCount() {
        return mView.getVisibleNotificationCount();
    }

    public int getIntrinsicContentHeight() {
        return mView.getIntrinsicContentHeight();
    }

    public void setIntrinsicPadding(int intrinsicPadding) {
        mView.setIntrinsicPadding(intrinsicPadding);
    }

    public int getHeight() {
        return mView.getHeight();
    }

    public int getChildCount() {
        return mView.getChildCount();
    }

    public ExpandableView getChildAt(int i) {
        return (ExpandableView) mView.getChildAt(i);
    }

    public void goToFullShade(long delay) {
        mView.goToFullShade(delay);
    }

    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators) {
        mView.setOverScrollAmount(amount, onTop, animate, cancelAnimators);
    }

    public void setOverScrollAmount(float amount, boolean onTop, boolean animate) {
        mView.setOverScrollAmount(amount, onTop, animate);
    }

    public void setOverScrolledPixels(float numPixels, boolean onTop, boolean animate) {
        mView.setOverScrolledPixels(numPixels, onTop, animate);
    }

    public void resetScrollPosition() {
        mView.resetScrollPosition();
    }

    public void setShouldShowShelfOnly(boolean shouldShowShelfOnly) {
        mView.setShouldShowShelfOnly(shouldShowShelfOnly);
    }

    public void cancelLongPress() {
        mView.cancelLongPress();
    }

    public float getX() {
        return mView.getX();
    }

    public boolean isBelowLastNotification(float x, float y) {
        return mView.isBelowLastNotification(x, y);
    }

    public float getWidth() {
        return mView.getWidth();
    }

    public float getOpeningHeight() {
        return mView.getOpeningHeight();
    }

    public float getBottomMostNotificationBottom() {
        return mView.getBottomMostNotificationBottom();
    }

    public void checkSnoozeLeavebehind() {
        mView.checkSnoozeLeavebehind();
    }

    public void setQsExpanded(boolean expanded) {
        mView.setQsExpanded(expanded);
    }

    public void setScrollingEnabled(boolean enabled) {
        mView.setScrollingEnabled(enabled);
    }

    public void setQsExpansionFraction(float expansionFraction) {
        mView.setQsExpansionFraction(expansionFraction);
    }

    public float calculateAppearFractionBypass() {
        return mView.calculateAppearFractionBypass();
    }

    public void updateTopPadding(float qsHeight, boolean animate) {
        mView.updateTopPadding(qsHeight, animate);
    }

    public void resetCheckSnoozeLeavebehind() {
        mView.resetCheckSnoozeLeavebehind();
    }

    public boolean isScrolledToBottom() {
        return mView.isScrolledToBottom();
    }

    public int getNotGoneChildCount() {
        return mView.getNotGoneChildCount();
    }

    public float getIntrinsicPadding() {
        return mView.getIntrinsicPadding();
    }

    public float getLayoutMinHeight() {
        return mView.getLayoutMinHeight();
    }

    public int getEmptyBottomMargin() {
        return mView.getEmptyBottomMargin();
    }

    public float getTopPaddingOverflow() {
        return mView.getTopPaddingOverflow();
    }

    public int getTopPadding() {
        return mView.getTopPadding();
    }

    public float getEmptyShadeViewHeight() {
        return mView.getEmptyShadeViewHeight();
    }

    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    public float getCurrentOverScrollAmount(boolean top) {
        return mView.getCurrentOverScrollAmount(top);
    }

    public float getCurrentOverScrolledPixels(boolean top) {
        return mView.getCurrentOverScrolledPixels(top);
    }

    public float calculateAppearFraction(float height) {
        return mView.calculateAppearFraction(height);
    }

    public void onExpansionStarted() {
        mView.onExpansionStarted();
    }

    public void onExpansionStopped() {
        mView.onExpansionStopped();
    }

    public void onPanelTrackingStarted() {
        mView.onPanelTrackingStarted();
    }

    public void onPanelTrackingStopped() {
        mView.onPanelTrackingStopped();
    }

    public void setHeadsUpBoundaries(int height, int bottomBarHeight) {
        mView.setHeadsUpBoundaries(height, bottomBarHeight);
    }

    public void setUnlockHintRunning(boolean running) {
        mView.setUnlockHintRunning(running);
    }

    public float getPeekHeight() {
        return mView.getPeekHeight();
    }

    public boolean isFooterViewNotGone() {
        return mView.isFooterViewNotGone();
    }

    public boolean isFooterViewContentVisible() {
        return mView.isFooterViewContentVisible();
    }

    public int getFooterViewHeightWithPadding() {
        return mView.getFooterViewHeightWithPadding();
    }

    public void updateEmptyShadeView(boolean visible) {
        mView.updateEmptyShadeView(visible);
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mView.setHeadsUpAnimatingAway(headsUpAnimatingAway);
    }

    public HeadsUpTouchHelper.Callback getHeadsUpCallback() {
        return mView.getHeadsUpCallback();
    }

    public void forceNoOverlappingRendering(boolean force) {
        mView.forceNoOverlappingRendering(force);
    }

    public void setTranslationX(float translation) {
        mView.setTranslationX(translation);
    }

    public void setExpandingVelocity(float velocity) {
        mView.setExpandingVelocity(velocity);
    }

    public void setExpandedHeight(float expandedHeight) {
        mView.setExpandedHeight(expandedHeight);
    }

    public void setQsContainer(ViewGroup view) {
        mView.setQsContainer(view);
    }

    public void setAnimationsEnabled(boolean enabled) {
        mView.setAnimationsEnabled(enabled);
    }

    public void setDozing(boolean dozing, boolean animate, PointF wakeUpTouchLocation) {
        mView.setDozing(dozing, animate, wakeUpTouchLocation);
    }

    public void setPulsing(boolean pulsing, boolean animatePulse) {
        mView.setPulsing(pulsing, animatePulse);
    }

    public boolean hasActiveClearableNotifications(
            @NotificationStackScrollLayout.SelectedRows int selection) {
        return mView.hasActiveClearableNotifications(selection);
    }

    public RemoteInputController.Delegate createDelegate() {
        return mView.createDelegate();
    }

    public void updateSectionBoundaries(String reason) {
        mView.updateSectionBoundaries(reason);
    }

    public void updateSpeedBumpIndex() {
        mView.updateSpeedBumpIndex();
    }

    public void updateFooter() {
        mView.updateFooter();
    }

    public void updateIconAreaViews() {
        mView.updateIconAreaViews();
    }

    public void onUpdateRowStates() {
        mView.onUpdateRowStates();
    }

    public boolean hasPulsingNotifications() {
        return mView.hasPulsingNotifications();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mView.getActivatedChild();
    }

    public void setActivatedChild(ActivatableNotificationView view) {
        mView.setActivatedChild(view);
    }

    public void runAfterAnimationFinished(Runnable r) {
        mView.runAfterAnimationFinished(r);
    }

    public void setNotificationPanelController(
            NotificationPanelViewController notificationPanelViewController) {
        mView.setNotificationPanelController(notificationPanelViewController);
    }

    public void setIconAreaController(NotificationIconAreaController controller) {
        mView.setIconAreaController(controller);
    }

    public void setStatusBar(StatusBar statusBar) {
        mView.setStatusBar(statusBar);
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mView.setGroupManager(groupManager);
    }

    public void setShelfController(NotificationShelfController notificationShelfController) {
        mView.setShelfController(notificationShelfController);
    }

    public void setScrimController(ScrimController scrimController) {
        mView.setScrimController(scrimController);
    }

    public ExpandableView getFirstChildNotGone() {
        return mView.getFirstChildNotGone();
    }

    public void setInHeadsUpPinnedMode(boolean inPinnedMode) {
        mView.setInHeadsUpPinnedMode(inPinnedMode);
    }

    public void generateHeadsUpAnimation(NotificationEntry entry, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(entry, isHeadsUp);
    }

    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(row, isHeadsUp);
    }

    public void setMaxTopPadding(int padding) {
        mView.setMaxTopPadding(padding);
    }

    public int getTransientViewCount() {
        return mView.getTransientViewCount();
    }

    public View getTransientView(int i) {
        return mView.getTransientView(i);
    }

    public int getPositionInLinearLayout(ExpandableView row) {
        return mView.getPositionInLinearLayout(row);
    }

    public NotificationStackScrollLayout getView() {
        return mView;
    }

    public float calculateGapHeight(ExpandableView previousView, ExpandableView child, int count) {
        return mView.calculateGapHeight(previousView, child, count);
    }
}
