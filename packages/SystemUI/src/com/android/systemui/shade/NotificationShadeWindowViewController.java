/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.systemui.Flags.migrateClocksToBlueprint;
import static com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED;
import static com.android.systemui.flags.Flags.TRACKPAD_GESTURE_COMMON;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.StatusBarManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AuthKeyguardMessageArea;
import com.android.keyguard.LockIconViewController;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.bouncer.ui.binder.BouncerViewBinder;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlagsClassic;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyevent.domain.interactor.SysUIKeyEventHandler;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.binder.AlternateBouncerViewBinder;
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies;
import com.android.systemui.res.R;
import com.android.systemui.shared.animation.DisableSubpixelTextTransitionListener;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationInsetsController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.DozeScrimController;
import com.android.systemui.statusbar.phone.DozeServiceHost;
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.SystemClock;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

import kotlinx.coroutines.ExperimentalCoroutinesApi;

/**
 * Controller for {@link NotificationShadeWindowView}.
 */
@SysUISingleton
public class NotificationShadeWindowViewController implements Dumpable {
    private static final String TAG = "NotifShadeWindowVC";
    private final FalsingCollector mFalsingCollector;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationShadeWindowView mView;
    private final NotificationShadeDepthController mDepthController;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final LockIconViewController mLockIconViewController;
    private final ShadeLogger mShadeLogger;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final StatusBarWindowStateController mStatusBarWindowStateController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final AmbientState mAmbientState;
    private final PulsingGestureListener mPulsingGestureListener;
    private final LockscreenHostedDreamGestureListener mLockscreenHostedDreamGestureListener;
    private final NotificationInsetsController mNotificationInsetsController;
    private final boolean mIsTrackpadCommonEnabled;
    private final FeatureFlagsClassic mFeatureFlagsClassic;
    private final SysUIKeyEventHandler mSysUIKeyEventHandler;
    private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;
    private final QuickSettingsController mQuickSettingsController;
    private final GlanceableHubContainerController
            mGlanceableHubContainerController;
    private GestureDetector mPulsingWakeupGestureHandler;
    private GestureDetector mDreamingWakeupGestureHandler;
    private View mBrightnessMirror;
    private boolean mTouchActive;
    private boolean mTouchCancelled;
    private MotionEvent mDownEvent;
    // TODO rename to mLaunchAnimationRunning
    private boolean mExpandAnimationRunning;
    /**
     *  When mExpandAnimationRunning is true and the touch dispatcher receives a down even after
     *  uptime exceeds this, the dispatcher will stop blocking touches for the launch animation,
     *  which has presumabely not completed due to an error.
     */
    private long mLaunchAnimationTimeout;
    private NotificationStackScrollLayout mStackScrollLayout;
    private PhoneStatusBarViewController mStatusBarViewController;
    private final CentralSurfaces mService;
    private final DozeServiceHost mDozeServiceHost;
    private final DozeScrimController mDozeScrimController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private DragDownHelper mDragDownHelper;
    private boolean mExpandingBelowNotch;
    private final DockManager mDockManager;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;

    private boolean mIsTrackingBarGesture = false;
    private boolean mIsOcclusionTransitionRunning = false;
    private DisableSubpixelTextTransitionListener mDisableSubpixelTextTransitionListener;
    private final Consumer<TransitionStep> mLockscreenToDreamingTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };
    private final SystemClock mClock;

    @ExperimentalCoroutinesApi
    @Inject
    public NotificationShadeWindowViewController(
            LockscreenShadeTransitionController transitionController,
            FalsingCollector falsingCollector,
            SysuiStatusBarStateController statusBarStateController,
            DockManager dockManager,
            NotificationShadeDepthController depthController,
            NotificationShadeWindowView notificationShadeWindowView,
            NotificationPanelViewController notificationPanelViewController,
            ShadeExpansionStateManager shadeExpansionStateManager,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBarWindowStateController statusBarWindowStateController,
            LockIconViewController lockIconViewController,
            CentralSurfaces centralSurfaces,
            DozeServiceHost dozeServiceHost,
            DozeScrimController dozeScrimController,
            NotificationShadeWindowController controller,
            Optional<UnfoldTransitionProgressProvider> unfoldTransitionProgressProvider,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            NotificationInsetsController notificationInsetsController,
            AmbientState ambientState,
            ShadeLogger shadeLogger,
            DumpManager dumpManager,
            PulsingGestureListener pulsingGestureListener,
            LockscreenHostedDreamGestureListener lockscreenHostedDreamGestureListener,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            GlanceableHubContainerController glanceableHubContainerController,
            NotificationLaunchAnimationInteractor notificationLaunchAnimationInteractor,
            FeatureFlagsClassic featureFlagsClassic,
            SystemClock clock,
            SysUIKeyEventHandler sysUIKeyEventHandler,
            QuickSettingsController quickSettingsController,
            PrimaryBouncerInteractor primaryBouncerInteractor,
            AlternateBouncerInteractor alternateBouncerInteractor,
            Lazy<JavaAdapter> javaAdapter,
            Lazy<AlternateBouncerDependencies> alternateBouncerDependencies,
            BouncerViewBinder bouncerViewBinder) {
        mLockscreenShadeTransitionController = transitionController;
        mFalsingCollector = falsingCollector;
        mStatusBarStateController = statusBarStateController;
        mView = notificationShadeWindowView;
        mDockManager = dockManager;
        mNotificationPanelViewController = notificationPanelViewController;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mDepthController = depthController;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mStatusBarWindowStateController = statusBarWindowStateController;
        mLockIconViewController = lockIconViewController;
        mShadeLogger = shadeLogger;
        mService = centralSurfaces;
        mDozeServiceHost = dozeServiceHost;
        mDozeScrimController = dozeScrimController;
        mNotificationShadeWindowController = controller;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mAmbientState = ambientState;
        mPulsingGestureListener = pulsingGestureListener;
        mLockscreenHostedDreamGestureListener = lockscreenHostedDreamGestureListener;
        mNotificationInsetsController = notificationInsetsController;
        mGlanceableHubContainerController = glanceableHubContainerController;
        mIsTrackpadCommonEnabled = featureFlagsClassic.isEnabled(TRACKPAD_GESTURE_COMMON);
        mFeatureFlagsClassic = featureFlagsClassic;
        mSysUIKeyEventHandler = sysUIKeyEventHandler;
        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mQuickSettingsController = quickSettingsController;

        // This view is not part of the newly inflated expanded status bar.
        mBrightnessMirror = mView.findViewById(R.id.brightness_mirror_container);
        mDisableSubpixelTextTransitionListener = new DisableSubpixelTextTransitionListener(mView);
        bouncerViewBinder.bind(mView.findViewById(R.id.keyguard_bouncer_container));

        if (DeviceEntryUdfpsRefactor.isEnabled()) {
            AlternateBouncerViewBinder.bind(
                    mView.findViewById(R.id.alternate_bouncer),
                    alternateBouncerDependencies.get()
            );
            javaAdapter.get().alwaysCollectFlow(
                    alternateBouncerDependencies.get().getViewModel()
                            .getForcePluginOpen(),
                        forcePluginOpen ->
                            mNotificationShadeWindowController.setForcePluginOpen(
                                    forcePluginOpen,
                                    alternateBouncerDependencies.get()
                                            .getViewModel()
                            )
            );
        }

        collectFlow(mView, keyguardTransitionInteractor.getLockscreenToDreamingTransition(),
                mLockscreenToDreamingTransition);
        collectFlow(
                mView,
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning(),
                this::setExpandAnimationRunning);

        mClock = clock;
        if (featureFlagsClassic.isEnabled(Flags.SPLIT_SHADE_SUBPIXEL_OPTIMIZATION)) {
            unfoldTransitionProgressProvider.ifPresent(
                    progressProvider -> progressProvider.addCallback(
                            mDisableSubpixelTextTransitionListener));
        }

        lockIconViewController.setLockIconView(mView.findViewById(R.id.lock_icon_view));
        dumpManager.registerDumpable(this);
    }

    /**
     * @return Location where to place the KeyguardMessageArea
     */
    public AuthKeyguardMessageArea getKeyguardMessageArea() {
        return mView.findViewById(R.id.keyguard_message_area);
    }

    private Boolean logDownDispatch(MotionEvent ev, String msg, Boolean result) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mShadeLogger.logShadeWindowDispatch(ev, msg, result);
        }
        return result;
    }

    /**
     * Handle a touch event while dreaming by forwarding the event to the content view.
     * @param event The event to forward.
     */
    public void handleDreamTouch(MotionEvent event) {
        mView.dispatchTouchEvent(event);
    }

    /** Inflates the {@link R.layout#status_bar_expanded} layout and sets it up. */
    public void setupExpandedStatusBar() {
        mStackScrollLayout = mView.findViewById(R.id.notification_stack_scroller);
        mPulsingWakeupGestureHandler = new GestureDetector(mView.getContext(),
                mPulsingGestureListener);
        if (mFeatureFlagsClassic.isEnabled(LOCKSCREEN_WALLPAPER_DREAM_ENABLED)) {
            mDreamingWakeupGestureHandler = new GestureDetector(mView.getContext(),
                    mLockscreenHostedDreamGestureListener);
        }
        mView.setLayoutInsetsController(mNotificationInsetsController);
        mView.setInteractionEventHandler(new NotificationShadeWindowView.InteractionEventHandler() {
            boolean mUseDragDownHelperForTouch = false;
            boolean mLastInterceptWasDragDownHelper = false;

            @Override
            public Boolean handleDispatchTouchEvent(MotionEvent ev) {
                if (mStatusBarViewController == null) { // Fix for b/192490822
                    return logDownDispatch(ev,
                            "Ignoring touch while statusBarView not yet set", false);
                }
                boolean isDown = ev.getActionMasked() == MotionEvent.ACTION_DOWN;
                boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
                boolean isCancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;

                boolean expandingBelowNotch = mExpandingBelowNotch;
                if (isUp || isCancel) {
                    mExpandingBelowNotch = false;
                }

                // Reset manual touch dispatch state here but make sure the UP/CANCEL event still
                // gets delivered.
                if (!isCancel && mService.shouldIgnoreTouch()) {
                    return logDownDispatch(ev, "touch ignored by CS", false);
                }

                if (isDown) {
                    mTouchActive = true;
                    mTouchCancelled = false;
                    mDownEvent = ev;
                    if (migrateClocksToBlueprint()) {
                        mService.userActivity();
                    }
                } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mTouchActive = false;
                    mDownEvent = null;
                }
                if (mTouchCancelled) {
                    return logDownDispatch(ev, "touch cancelled", false);
                }
                if (mExpandAnimationRunning) {
                    if (isDown && mClock.uptimeMillis() > mLaunchAnimationTimeout) {
                        Log.wtf(TAG, "NSWVC: launch animation timed out");
                        setExpandAnimationRunning(false);
                    } else {
                        return logDownDispatch(ev, "expand animation running", false);
                    }
                }

                if (mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()) {
                    // If the user was sliding their finger across the lock screen,
                    // we may have been intercepting the touch and forwarding it to the
                    // UDFPS affordance via mStatusBarKeyguardViewManager.onTouch (see below).
                    // If this touch ended up unlocking the device, we want to cancel the touch
                    // immediately, so we don't cause swipe or expand animations afterwards.
                    cancelCurrentTouch();
                    return true;
                }

                if (mIsOcclusionTransitionRunning) {
                    return logDownDispatch(ev, "occlusion transition running", false);
                }

                mFalsingCollector.onTouchEvent(ev);
                mPulsingWakeupGestureHandler.onTouchEvent(ev);

                if (mGlanceableHubContainerController.onTouchEvent(ev)) {
                    return logDownDispatch(ev, "dispatched to glanceable hub container", true);
                }
                if (mDreamingWakeupGestureHandler != null
                        && mDreamingWakeupGestureHandler.onTouchEvent(ev)) {
                    return logDownDispatch(ev, "dream wakeup gesture handled", true);
                }
                if (mStatusBarKeyguardViewManager.dispatchTouchEvent(ev)) {
                    return logDownDispatch(ev, "dispatched to Keyguard", true);
                }
                if (mBrightnessMirror != null
                        && mBrightnessMirror.getVisibility() == View.VISIBLE) {
                    // Disallow new pointers while the brightness mirror is visible. This is so that
                    // you can't touch anything other than the brightness slider while the mirror is
                    // showing and the rest of the panel is transparent.
                    if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                        return logDownDispatch(ev, "disallowed new pointer", false);
                    }
                }
                if (isDown) {
                    mNotificationStackScrollLayoutController.closeControlsIfOutsideTouch(ev);
                }

                if (mStatusBarStateController.isDozing()) {
                    mDozeScrimController.extendPulse();
                }

                // In case we start outside of the view bounds (below the status bar), we need to
                // dispatch the touch manually as the view system can't accommodate for touches
                // outside of the regular view bounds.
                if (isDown && ev.getY() >= mView.getBottom()) {
                    mExpandingBelowNotch = true;
                    expandingBelowNotch = true;
                }
                if (expandingBelowNotch) {
                    return logDownDispatch(ev,
                            "expand below notch. sending touch to status bar",
                            mStatusBarViewController.sendTouchToView(ev));
                }

                if (!mIsTrackingBarGesture && isDown
                        && mNotificationPanelViewController.isFullyCollapsed()) {
                    float x = ev.getRawX();
                    float y = ev.getRawY();
                    if (mStatusBarViewController.touchIsWithinView(x, y)) {
                        if (mStatusBarWindowStateController.windowIsShowing()) {
                            mIsTrackingBarGesture = true;
                            return logDownDispatch(ev, "sending touch to status bar",
                                    mStatusBarViewController.sendTouchToView(ev));
                        } else {
                            return logDownDispatch(ev, "hidden or hiding", true);
                        }
                    }
                } else if (mIsTrackingBarGesture) {
                    final boolean sendToStatusBar = mStatusBarViewController.sendTouchToView(ev);
                    if (isUp || isCancel) {
                        mIsTrackingBarGesture = false;
                    }
                    return logDownDispatch(ev, "sending bar gesture to status bar",
                            sendToStatusBar);
                }
                return logDownDispatch(ev, "no custom touch dispatch of down event", null);
            }

            @Override
            public void dispatchTouchEventComplete() {
                mFalsingCollector.onMotionEventComplete();
            }

            @Override
            public boolean shouldInterceptTouchEvent(MotionEvent ev) {
                boolean intercepted = shouldInterceptTouchEventInternal(ev);
                if (intercepted) {
                    mUseDragDownHelperForTouch = mLastInterceptWasDragDownHelper;
                }
                return intercepted;
            }

            private boolean shouldInterceptTouchEventInternal(MotionEvent ev) {
                mLastInterceptWasDragDownHelper = false;
                // When the device starts dozing, there's a delay before the device's display state
                // changes from ON => DOZE to allow for the light reveal animation to run at
                // a higher refresh rate and to delay visual changes (ie: display blink) when
                // changing the display state. We'll call this specific state the
                // "aodDefermentState". In this state we:
                //     - don't want touches to get sent to underlying views, except the lock icon
                //     - handle the tap to wake gesture via the PulsingGestureListener
                if (mStatusBarStateController.isDozing()
                        && !mDozeServiceHost.isPulsing()
                        && !mDockManager.isDocked()
                        && !mLockIconViewController.willHandleTouchWhileDozing(ev)
                ) {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        mShadeLogger.d("NSWVC: capture all touch events in always-on");
                    }
                    return true;
                }

                if (mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(ev)) {
                    // Don't allow touches to proceed to underlying views if alternate
                    // bouncer is showing
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        mShadeLogger.d("NSWVC: alt bouncer showing");
                    }
                    return true;
                }

                boolean bouncerShowing;
                if (DeviceEntryUdfpsRefactor.isEnabled()) {
                    bouncerShowing = mPrimaryBouncerInteractor.isBouncerShowing()
                            || mAlternateBouncerInteractor.isVisibleState();
                } else {
                    bouncerShowing = mService.isBouncerShowing();
                }
                if (mNotificationPanelViewController.isFullyExpanded()
                        && !bouncerShowing
                        && !mStatusBarStateController.isDozing()) {
                    if (mDragDownHelper.isDragDownEnabled()) {
                        if (migrateClocksToBlueprint()) {
                            // When on lockscreen, if the touch originates at the top of the screen
                            // go directly to QS and not the shade
                            if (mStatusBarStateController.getState() == KEYGUARD
                                    && mQuickSettingsController.shouldQuickSettingsIntercept(
                                        ev.getX(), ev.getY(), 0)) {
                                mShadeLogger.d("NSWVC: QS intercepted");
                                return true;
                            }
                        }

                        // This handles drag down over lockscreen
                        boolean result = mDragDownHelper.onInterceptTouchEvent(ev);
                        if (migrateClocksToBlueprint()) {
                            if (result) {
                                mLastInterceptWasDragDownHelper = true;
                                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                                    mShadeLogger.d("NSWVC: drag down helper intercepted");
                                }
                            } else if (didNotificationPanelInterceptEvent(ev)) {
                                return true;
                            }
                        } else {
                            if (result) {
                                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                                    mShadeLogger.d("NSWVC: drag down helper intercepted");
                                }
                            }
                        }
                        return result;
                    } else {
                        // This else handles interactions on the full shade while unlocked
                        if (didNotificationPanelInterceptEvent(ev)) {
                            return true;
                        }
                    }
                } else if (migrateClocksToBlueprint()) {
                    // This final check handles swipes on HUNs and when Pulsing
                    if (!bouncerShowing && didNotificationPanelInterceptEvent(ev)) {
                        mShadeLogger.d("NSWVC: intercepted for HUN/PULSING");
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void didIntercept(MotionEvent ev) {
                MotionEvent cancellation = MotionEvent.obtain(ev);
                cancellation.setAction(MotionEvent.ACTION_CANCEL);
                mStackScrollLayout.onInterceptTouchEvent(cancellation);
                if (!migrateClocksToBlueprint()) {
                    mNotificationPanelViewController.handleExternalInterceptTouch(cancellation);
                }
                cancellation.recycle();
            }

            @Override
            public boolean handleTouchEvent(MotionEvent ev) {
                boolean handled = false;
                if (mStatusBarStateController.isDozing()) {
                    handled = !mDozeServiceHost.isPulsing();
                }
                if (mStatusBarKeyguardViewManager.onTouch(ev)) {
                    return true;
                }
                if (migrateClocksToBlueprint()) {
                    if (mLastInterceptWasDragDownHelper && (mDragDownHelper.isDraggingDown())) {
                        // we still want to finish our drag down gesture when locking the screen
                        handled |= mDragDownHelper.onTouchEvent(ev) || handled;
                    }
                    if (!handled && mNotificationPanelViewController.handleExternalTouch(ev)) {
                        return true;
                    }
                } else {
                    if (mDragDownHelper.isDragDownEnabled()
                            || mDragDownHelper.isDraggingDown()) {
                        // we still want to finish our drag down gesture when locking the screen
                        return mDragDownHelper.onTouchEvent(ev) || handled;
                    } else {
                        return handled;
                    }
                }
                return handled;
            }

            @Override
            public void didNotHandleTouchEvent(MotionEvent ev) {
                final int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mService.setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
                }
            }

            @Override
            public boolean interceptMediaKey(KeyEvent event) {
                return mSysUIKeyEventHandler.interceptMediaKey(event);
            }

            @Override
            public boolean dispatchKeyEventPreIme(KeyEvent event) {
                return mSysUIKeyEventHandler.dispatchKeyEventPreIme(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                return mSysUIKeyEventHandler.dispatchKeyEvent(event);
            }
        });

        mView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (child.getId() == R.id.brightness_mirror_container) {
                    mBrightnessMirror = child;
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });

        setDragDownHelper(mLockscreenShadeTransitionController.getTouchHelper());

        mDepthController.setRoot(mView);
        ShadeExpansionChangeEvent currentState =
                mShadeExpansionStateManager.addExpansionListener(mDepthController);
        mDepthController.onPanelExpansionChanged(currentState);
    }

    /**
     * Sets up the glanceable hub UI if the {@link com.android.systemui.Flags#FLAG_COMMUNAL_HUB}
     * flag is enabled.
     *
     * The layout lives in {@link R.id.communal_ui_stub}.
     */
    public void setupCommunalHubLayout() {
        collectFlow(
                mView,
                mGlanceableHubContainerController.communalAvailable(),
                isEnabled -> {
                    if (isEnabled) {
                        View communalPlaceholder = mView.findViewById(R.id.communal_ui_stub);
                        int index = mView.indexOfChild(communalPlaceholder);
                        mView.addView(
                                mGlanceableHubContainerController.initView(mView.getContext()),
                                index);
                    } else {
                        mGlanceableHubContainerController.disposeView();
                    }
                }
        );
    }

    private boolean didNotificationPanelInterceptEvent(MotionEvent ev) {
        if (migrateClocksToBlueprint()) {
            // Since NotificationStackScrollLayout is now a sibling of notification_panel, we need
            // to also ask NotificationPanelViewController directly, in order to process swipe up
            // events originating from notifications
            if (mNotificationPanelViewController.handleExternalInterceptTouch(ev)) {
                mShadeLogger.d("NSWVC: NPVC intercepted");
                return true;
            }
        }

        return false;
    }

    public NotificationShadeWindowView getView() {
        return mView;
    }

    public void cancelCurrentTouch() {
        mShadeLogger.d("NSWVC: cancelling current touch");
        if (mTouchActive) {
            final long now = mClock.uptimeMillis();
            final MotionEvent event;
            if (mIsTrackpadCommonEnabled) {
                event = MotionEvent.obtain(mDownEvent);
                event.setDownTime(now);
                event.setAction(MotionEvent.ACTION_CANCEL);
                event.setLocation(0.0f, 0.0f);
            } else {
                event = MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            }
            Log.w(TAG, "Canceling current touch event (should be very rare)");
            mView.dispatchTouchEvent(event);
            event.recycle();
            mTouchCancelled = true;
        }
        mAmbientState.setSwipingUp(false);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.print("  mExpandAnimationRunning=");
        pw.println(mExpandAnimationRunning);
        pw.print("  mTouchCancelled=");
        pw.println(mTouchCancelled);
        pw.print("  mTouchActive=");
        pw.println(mTouchActive);
    }

    @VisibleForTesting
    void setExpandAnimationRunning(boolean running) {
        if (mExpandAnimationRunning != running) {
            // TODO(b/288507023): Remove this log.
            if (ActivityTransitionAnimator.DEBUG_TRANSITION_ANIMATION) {
                Log.d(TAG, "Setting mExpandAnimationRunning=" + running);
            }
            if (running) {
                mLaunchAnimationTimeout = mClock.uptimeMillis() + 5000;
            }
            mExpandAnimationRunning = running;
            mNotificationShadeWindowController.setLaunchingActivity(mExpandAnimationRunning);
        }
    }

    public void cancelExpandHelper() {
        if (mStackScrollLayout != null) {
            mStackScrollLayout.cancelExpandHelper();
        }
    }

    public void setStatusBarViewController(PhoneStatusBarViewController statusBarViewController) {
        mStatusBarViewController = statusBarViewController;
    }

    @VisibleForTesting
    void setDragDownHelper(DragDownHelper dragDownHelper) {
        mDragDownHelper = dragDownHelper;
    }
}
