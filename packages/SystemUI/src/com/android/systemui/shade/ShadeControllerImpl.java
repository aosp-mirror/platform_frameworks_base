/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade;

import android.content.ComponentCallbacks2;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.DejankUtils;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.dagger.ShadeTouchLog;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowController;

import dagger.Lazy;

import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** An implementation of {@link ShadeController}. */
@SysUISingleton
public final class ShadeControllerImpl implements ShadeController {

    private static final String TAG = "ShadeControllerImpl";
    private static final boolean SPEW = false;

    private final int mDisplayId;

    private final CommandQueue mCommandQueue;
    private final Executor mMainExecutor;
    private final LogBuffer mTouchLog;
    private final WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarStateController mStatusBarStateController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final StatusBarWindowController mStatusBarWindowController;
    private final DeviceProvisionedController mDeviceProvisionedController;

    private final Lazy<ShadeViewController> mShadeViewControllerLazy;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final Lazy<NotificationGutsManager> mGutsManager;

    private final ArrayList<Runnable> mPostCollapseRunnables = new ArrayList<>();

    private boolean mExpandedVisible;
    private boolean mLockscreenOrShadeVisible;

    private NotificationPresenter mPresenter;
    private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private ShadeVisibilityListener mShadeVisibilityListener;

    @Inject
    public ShadeControllerImpl(
            CommandQueue commandQueue,
            @Main Executor mainExecutor,
            @ShadeTouchLog LogBuffer touchLog,
            WindowRootViewVisibilityInteractor windowRootViewVisibilityInteractor,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBarWindowController statusBarWindowController,
            DeviceProvisionedController deviceProvisionedController,
            NotificationShadeWindowController notificationShadeWindowController,
            WindowManager windowManager,
            Lazy<ShadeViewController> shadeViewControllerLazy,
            Lazy<AssistManager> assistManagerLazy,
            Lazy<NotificationGutsManager> gutsManager
    ) {
        mCommandQueue = commandQueue;
        mMainExecutor = mainExecutor;
        mTouchLog = touchLog;
        mWindowRootViewVisibilityInteractor = windowRootViewVisibilityInteractor;
        mShadeViewControllerLazy = shadeViewControllerLazy;
        mStatusBarStateController = statusBarStateController;
        mStatusBarWindowController = statusBarWindowController;
        mDeviceProvisionedController = deviceProvisionedController;
        mGutsManager = gutsManager;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mDisplayId = windowManager.getDefaultDisplay().getDisplayId();
        mKeyguardStateController = keyguardStateController;
        mAssistManagerLazy = assistManagerLazy;
    }

    @Override
    public boolean isShadeEnabled() {
        return true;
    }

    @Override
    public void instantExpandShade() {
        // Make our window larger and the panel expanded.
        makeExpandedVisible(true /* force */);
        getShadeViewController().expand(false /* animate */);
        mCommandQueue.recomputeDisableFlags(mDisplayId, false /* animate */);
    }

    @Override
    public void animateCollapseShade(int flags, boolean force, boolean delayed,
            float speedUpFactor) {
        if (!force && mStatusBarStateController.getState() != StatusBarState.SHADE) {
            runPostCollapseRunnables();
            return;
        }
        if (SPEW) {
            Log.d(TAG,
                    "animateCollapse(): mExpandedVisible=" + mExpandedVisible + "flags=" + flags);
        }
        if (getNotificationShadeWindowView() != null
                && getShadeViewController().canBeCollapsed()
                && (flags & CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL) == 0) {
            // release focus immediately to kick off focus change transition
            mNotificationShadeWindowController.setNotificationShadeFocusable(false);

            mNotificationShadeWindowViewController.cancelExpandHelper();
            getShadeViewController().collapse(true, delayed, speedUpFactor);
        }
    }

    @Override
    public void animateExpandShade() {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }
        getShadeViewController().expandToNotifications();
    }

    @Override
    public void animateExpandQs() {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }
        // Settings are not available in setup
        if (!mDeviceProvisionedController.isCurrentUserSetup()) return;

        getShadeViewController().expandToQs();
    }

    @Override
    public boolean closeShadeIfOpen() {
        if (!getShadeViewController().isFullyCollapsed()) {
            mCommandQueue.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */);
            notifyVisibilityChanged(false);
            mAssistManagerLazy.get().hideAssist();
        }
        return false;
    }

    @Override
    public boolean isKeyguard() {
        return mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
    }

    @Override
    public boolean isShadeFullyOpen() {
        return getShadeViewController().isShadeFullyExpanded();
    }

    @Override
    public boolean isExpandingOrCollapsing() {
        return getShadeViewController().isExpandingOrCollapsing();
    }
    @Override
    public void postAnimateCollapseShade() {
        mMainExecutor.execute(this::animateCollapseShade);
    }

    @Override
    public void postAnimateForceCollapseShade() {
        mMainExecutor.execute(this::animateCollapseShadeForced);
    }

    @Override
    public void postAnimateExpandQs() {
        mMainExecutor.execute(this::animateExpandQs);
    }

    @Override
    public void postOnShadeExpanded(Runnable executable) {
        getShadeViewController().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (getNotificationShadeWindowView().isVisibleToUser()) {
                            getShadeViewController().removeOnGlobalLayoutListener(this);
                            getShadeViewController().postToView(executable);
                        }
                    }
                });
    }

    @Override
    public void addPostCollapseAction(Runnable action) {
        mPostCollapseRunnables.add(action);
    }

    @Override
    public void runPostCollapseRunnables() {
        ArrayList<Runnable> clonedList = new ArrayList<>(mPostCollapseRunnables);
        mPostCollapseRunnables.clear();
        int size = clonedList.size();
        for (int i = 0; i < size; i++) {
            clonedList.get(i).run();
        }
        mStatusBarKeyguardViewManager.readyForKeyguardDone();
    }

    @Override
    public boolean collapseShade() {
        if (!getShadeViewController().isFullyCollapsed()) {
            // close the shade if it was open
            animateCollapseShadeForcedDelayed();
            notifyVisibilityChanged(false);

            return true;
        } else {
            return false;
        }
    }

    @Override
    public void collapseShade(boolean animate) {
        if (animate) {
            boolean willCollapse = collapseShade();
            if (!willCollapse) {
                runPostCollapseRunnables();
            }
        } else if (!mPresenter.isPresenterFullyCollapsed()) {
            instantCollapseShade();
            notifyVisibilityChanged(false);
        } else {
            runPostCollapseRunnables();
        }
    }

    @Override
    public void cancelExpansionAndCollapseShade() {
        if (getShadeViewController().isTracking()) {
            mNotificationShadeWindowViewController.cancelCurrentTouch();
        }
        if (getShadeViewController().isPanelExpanded()
                && mStatusBarStateController.getState() == StatusBarState.SHADE) {
            animateCollapseShade();
        }
    }

    @Override
    public void collapseOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            collapseShade();
        } else {
            mMainExecutor.execute(this::collapseShade);
        }
    }

    @Override
    public void onStatusBarTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (mExpandedVisible) {
                animateCollapseShade();
            }
        }
    }

    private void onClosingFinished() {
        runPostCollapseRunnables();
        if (!mPresenter.isPresenterFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            mNotificationShadeWindowController.setNotificationShadeFocusable(true);
        }
    }

    @Override
    public void onLaunchAnimationCancelled(boolean isLaunchForActivity) {
        if (mPresenter.isPresenterFullyCollapsed()
                && !mPresenter.isCollapsing()
                && isLaunchForActivity) {
            onClosingFinished();
        } else {
            collapseShade(true /* animate */);
        }
    }

    @Override
    public void onLaunchAnimationEnd(boolean launchIsFullScreen) {
        if (!mPresenter.isCollapsing()) {
            onClosingFinished();
        }
        if (launchIsFullScreen) {
            instantCollapseShade();
        }
    }

    @Override
    public void instantCollapseShade() {
        getShadeViewController().instantCollapse();
        runPostCollapseRunnables();
    }

    @Override
    public void makeExpandedVisible(boolean force) {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (!force && (mExpandedVisible || !mCommandQueue.panelsEnabled())) {
            return;
        }

        mExpandedVisible = true;

        // Expand the window to encompass the full screen in anticipation of the drag.
        // It's only possible to do atomically because the status bar is at the top of the screen!
        mNotificationShadeWindowController.setPanelVisible(true);

        notifyVisibilityChanged(true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, !force /* animate */);
        notifyExpandedVisibleChanged(true);
    }

    @Override
    public void makeExpandedInvisible() {
        if (SPEW) Log.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible || getNotificationShadeWindowView() == null) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        getShadeViewController().collapse(false, false, 1.0f);

        mExpandedVisible = false;
        notifyVisibilityChanged(false);

        // Update the visibility of notification shade and status bar window.
        mNotificationShadeWindowController.setPanelVisible(false);
        mStatusBarWindowController.setForceStatusBarVisible(false);

        // Close any guts that might be visible
        mGutsManager.get().closeAndSaveGuts(
                true /* removeLeavebehind */,
                true /* force */,
                true /* removeControls */,
                -1 /* x */,
                -1 /* y */,
                true /* resetMenu */);

        runPostCollapseRunnables();
        notifyExpandedVisibleChanged(false);
        mCommandQueue.recomputeDisableFlags(
                mDisplayId,
                getShadeViewController().shouldHideStatusBarIconsWhenExpanded());

        // Trimming will happen later if Keyguard is showing - doing it here might cause a jank in
        // the bouncer appear animation.
        if (!mKeyguardStateController.isShowing()) {
            WindowManagerGlobal.getInstance().trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        }
    }

    @Override
    public boolean isExpandedVisible() {
        return mExpandedVisible;
    }

    @Override
    public void setVisibilityListener(ShadeVisibilityListener listener) {
        mShadeVisibilityListener = listener;
    }

    private void notifyVisibilityChanged(boolean visible) {
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(visible);
        if (mLockscreenOrShadeVisible != visible) {
            mLockscreenOrShadeVisible = visible;
            if (visible) {
                // It would be best if this could be done as a side effect of listening to the
                // [WindowRootViewVisibilityInteractor.isLockscreenOrShadeVisible] flow inside
                // NotificationShadeWindowViewController. However, there's no guarantee that the
                // flow will emit in the same frame as when the visibility changed, and we want the
                // DejankUtils to be notified immediately, so we do it immediately here.
                DejankUtils.notifyRendererOfExpensiveFrame(
                        getNotificationShadeWindowView(), "onShadeVisibilityChanged");
            }
        }
    }

    private void notifyExpandedVisibleChanged(boolean expandedVisible) {
        mShadeVisibilityListener.expandedVisibleChanged(expandedVisible);
    }

    @Override
    public void setNotificationPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
    }

    @Override
    public void setNotificationShadeWindowViewController(
            NotificationShadeWindowViewController controller) {
        mNotificationShadeWindowViewController = controller;
    }

    private NotificationShadeWindowView getNotificationShadeWindowView() {
        return mNotificationShadeWindowViewController.getView();
    }

    private ShadeViewController getShadeViewController() {
        return mShadeViewControllerLazy.get();
    }

    @Override
    public void start() {
        TouchLogger.logTouchesTo(mTouchLog);
        getShadeViewController().setTrackingStartedListener(this::runPostCollapseRunnables);
        getShadeViewController().setOpenCloseListener(
                new OpenCloseListener() {
                    @Override
                    public void onClosingFinished() {
                        ShadeControllerImpl.this.onClosingFinished();
                    }

                    @Override
                    public void onOpenStarted() {
                        makeExpandedVisible(false);
                    }
                });
    }
}
