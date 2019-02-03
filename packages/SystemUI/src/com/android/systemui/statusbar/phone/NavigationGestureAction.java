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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_NONE;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

import com.android.systemui.recents.OverviewProxyService;

/**
 * A gesture action that would be triggered and reassigned by {@link QuickStepController}
 */
public abstract class NavigationGestureAction {
    protected final NavigationBarView mNavigationBarView;
    protected final OverviewProxyService mProxySender;

    protected int mNavigationBarPosition;
    protected boolean mDragHorizontalPositive;
    protected boolean mDragVerticalPositive;
    private boolean mIsActive;

    public NavigationGestureAction(@NonNull NavigationBarView navigationBarView,
            @NonNull OverviewProxyService service) {
        mNavigationBarView = navigationBarView;
        mProxySender = service;
    }

    /**
     * Pass event that the state of the bar (such as rotation) has changed
     * @param changed if rotation or drag positive direction (such as ltr) has changed
     * @param navBarPos position of navigation bar
     * @param dragHorPositive direction of positive horizontal drag, could change with ltr changes
     * @param dragVerPositive direction of positive vertical drag, could change with ltr changes
     */
    public void setBarState(boolean changed, int navBarPos, boolean dragHorPositive,
            boolean dragVerPositive) {
        mNavigationBarPosition = navBarPos;
        mDragHorizontalPositive = dragHorPositive;
        mDragVerticalPositive = dragVerPositive;
    }

    /**
     * Resets the state of the action. Called when touch down occurs over the Navigation Bar.
     */
    public void reset() {
        mIsActive = false;
    }

    /**
     * Start the gesture and the action will be active
     * @param event the event that caused the gesture
     */
    public void startGesture(MotionEvent event) {
        mIsActive = true;
        onGestureStart(event);
    }

    /**
     * Gesture has ended with action cancel or up and this action will not be active
     */
    public void endGesture() {
        mIsActive = false;
        onGestureEnd();
    }

    /**
     * If the action is currently active based on the gesture that triggered it. Only one action
     * can occur at a time
     * @return whether or not if this action has been triggered
     */
    public boolean isActive() {
        return mIsActive;
    }

    /**
     * @return whether or not this action can run if notification shade is shown
     */
    public boolean canRunWhenNotificationsShowing() {
        return true;
    }

    /**
     * @return whether or not this action triggers when starting a gesture from a certain hit target
     * If {@link HIT_TARGET_NONE} is specified then action does not need to be triggered by button
     */
    public int requiresTouchDownHitTarget() {
        return HIT_TARGET_NONE;
    }

    /**
     * @return whether or not to move the button that started gesture over with user input drag
     */
    public boolean allowHitTargetToMoveOverDrag() {
        return false;
    }

    /**
     * Tell if the action is able to execute. Note that {@link #isEnabled()} must be true for this
     * to be checked. The difference between this and {@link #isEnabled()} is that this dependent
     * on the state of the navigation bar
     * @return true if action can execute after gesture activates based on current states
     */
    public boolean canPerformAction() {
        return true;
    }

    /**
     * Decide if the controller should not send the current motion event to launcher via
     * {@link OverviewProxyService}
     * @return if controller should not proxy
     */
    public boolean disableProxyEvents() {
        return false;
    }

    /**
     * Tell if action is enabled. Compared to {@link #canPerformAction()} this is based on settings
     * if the action is disabled for a particular gesture. For example a back action can be enabled
     * however if there is nothing to back to then {@link #canPerformAction()} should return false.
     * In this way if the action requires {@link #allowHitTargetToMoveOverDrag()} then if enabled,
     * the button can be dragged with a large dampening factor during the gesture but will not
     * activate the action.
     * @return true if this action is enabled and can run
     */
    public abstract boolean isEnabled();

    protected void onDarkIntensityChange(float intensity) {
    }

    protected void onDraw(Canvas canvas) {
    }

    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    }

    /**
     * When gesture starts, this will run to execute the action
     * @param event the event that triggered the gesture
     */
    protected abstract void onGestureStart(MotionEvent event);

    /**
     * Channels motion move events to the action to track the user inputs
     * @param x the x position
     * @param y the y position
     */
    public void onGestureMove(int x, int y) {
    }

    /**
     * When gesture ends, this will run from action up or cancel
     */
    protected void onGestureEnd() {
    }

    protected Context getContext() {
        return mNavigationBarView.getContext();
    }

    protected boolean isNavBarVertical() {
        return mNavigationBarPosition == NAV_BAR_LEFT || mNavigationBarPosition == NAV_BAR_RIGHT;
    }

    protected boolean getGlobalBoolean(@NonNull String key) {
        return QuickStepController.getBoolGlobalSetting(getContext(), key);
    }
}
