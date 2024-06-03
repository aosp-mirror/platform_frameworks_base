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

package com.android.systemui.navigationbar;

import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowType;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.inputmethodservice.InputMethodService.ENABLE_HIDE_IME_CAPTION_BAR;
import static android.view.InsetsSource.FLAG_SUPPRESS_SCRIM;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.HOME_BUTTON_LONG_PRESS_DURATION_MS;
import static com.android.systemui.navigationbar.NavBarHelper.transitionMode;
import static com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import static com.android.systemui.shared.recents.utilities.Utilities.isLargeScreen;
import static com.android.systemui.shared.rotation.RotationButtonController.DEBUG_ROTATION;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.isGesturalMode;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.systemui.statusbar.phone.CentralSurfaces.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.CentralSurfaces.dumpBarTransitions;
import static com.android.systemui.util.Utils.isGesturalModeOnDefaultDisplay;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InsetsFrameProvider;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.util.LatencyTracker;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarComponent.NavigationBarScope;
import com.android.systemui.navigationbar.NavigationModeController.ModeChangedListener;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.DeadZone;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.res.R;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.shared.navigationbar.RegionSamplingHelper;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.ViewController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Contains logic for a navigation bar view.
 */
@NavigationBarScope
public class NavigationBar extends ViewController<NavigationBarView> implements Callbacks {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";
    private static final String EXTRA_DISABLE2_STATE = "disabled2_state";
    private static final String EXTRA_APPEARANCE = "appearance";
    private static final String EXTRA_BEHAVIOR = "behavior";
    private static final String EXTRA_TRANSIENT_STATE = "transient_state";

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERANCE = 200;
    private static final long AUTODIM_TIMEOUT_MS = 2250;
    private static final float QUICKSTEP_TOUCH_SLOP_RATIO_TWO_BUTTON = 3f;

    private final Context mContext;
    private final Bundle mSavedState;
    private final WindowManager mWindowManager;
    private final AccessibilityManager mAccessibilityManager;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final StatusBarStateController mStatusBarStateController;
    private final MetricsLogger mMetricsLogger;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final SysUiState mSysUiFlagsContainer;
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private final KeyguardStateController mKeyguardStateController;
    private final ShadeViewController mShadeViewController;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final OverviewProxyService mOverviewProxyService;
    private final NavigationModeController mNavigationModeController;
    private final UserTracker mUserTracker;
    private final CommandQueue mCommandQueue;
    private final Optional<Pip> mPipOptional;
    private final Optional<Recents> mRecentsOptional;
    private final DeviceConfigProxy mDeviceConfigProxy;
    private final NavigationBarTransitions mNavigationBarTransitions;
    private final Optional<BackAnimation> mBackAnimation;
    private final Handler mHandler;
    private final UiEventLogger mUiEventLogger;
    private final NavBarHelper mNavBarHelper;
    private final NotificationShadeDepthController mNotificationShadeDepthController;
    private final OnComputeInternalInsetsListener mOnComputeInternalInsetsListener;
    private final UserContextProvider mUserContextProvider;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final DisplayTracker mDisplayTracker;
    private final RegionSamplingHelper mRegionSamplingHelper;
    private final int mNavColorSampleMargin;
    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    private NavigationBarFrame mFrame;
    private MotionEvent mCurrentDownEvent;

    private @WindowVisibleState int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private @TransitionMode int mTransitionMode;
    private boolean mLongPressHomeEnabled;

    private int mDisabledFlags1;
    private int mDisabledFlags2;
    private long mLastLockToAppLongPress;

    private Locale mLocale;
    private int mLayoutDirection;

    private Optional<Long> mHomeButtonLongPressDurationMs;
    private Optional<Long> mOverrideHomeButtonLongPressDurationMs = Optional.empty();
    private Optional<Float> mOverrideHomeButtonLongPressSlopMultiplier = Optional.empty();
    private boolean mHomeButtonLongPressHapticEnabled = true;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int, int) */
    private @Appearance int mAppearance;

    /** @see android.view.WindowInsetsController#setSystemBarsBehavior(int) */
    private @Behavior int mBehavior;

    private boolean mTransientShown;
    private boolean mTransientShownFromGestureOnSystemBar;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;
    private LightBarController mLightBarController;
    private final LightBarController mMainLightBarController;
    private final LightBarController.Factory mLightBarControllerFactory;
    private AutoHideController mAutoHideController;
    private final AutoHideController mMainAutoHideController;
    private final AutoHideController.Factory mAutoHideControllerFactory;
    private final Optional<TelecomManager> mTelecomManagerOptional;
    private final InputMethodManager mInputMethodManager;
    private final TaskStackChangeListeners mTaskStackChangeListeners;

    @VisibleForTesting
    public int mDisplayId;
    private boolean mIsOnDefaultDisplay;
    private boolean mHomeBlockedThisTouch;

    /**
     * When user is QuickSwitching between apps of different orientations, we'll draw a fake
     * home handle on the orientation they originally touched down to start their swipe
     * gesture to indicate to them that they can continue in that orientation without having to
     * rotate the phone
     * The secondary handle will show when we get
     * {@link OverviewProxyListener#notifyPrioritizedRotation(int)} callback with the
     * original handle hidden and we'll flip the visibilities once the
     * {@link #mTasksFrozenListener} fires
     */
    private QuickswitchOrientedNavHandle mOrientationHandle;
    private WindowManager.LayoutParams mOrientationParams;
    private int mStartingQuickSwitchRotation = -1;
    private int mCurrentRotation;
    private ViewTreeObserver.OnGlobalLayoutListener mOrientationHandleGlobalLayoutListener;
    private boolean mShowOrientedHandleForImmersiveMode;
    private final DeadZone mDeadZone;
    private boolean mImeVisible;
    private final Rect mSamplingBounds = new Rect();
    private final Binder mInsetsSourceOwner = new Binder();
    private final NavBarButtonClickLogger mNavBarButtonClickLogger;
    private final NavbarOrientationTrackingLogger mNavbarOrientationTrackingLogger;

    /**
     * When quickswitching between apps of different orientations, we draw a secondary home handle
     * in the position of the first app's orientation. This rect represents the region of that
     * home handle so we can apply the correct light/dark luma on that.
     * @see {@link NavigationBar#mOrientationHandle}
     */
    @android.annotation.Nullable
    private Rect mOrientedHandleSamplingRegion;

    @com.android.internal.annotations.VisibleForTesting
    public enum NavBarActionEvent implements UiEventLogger.UiEventEnum {

        @UiEvent(doc = "Assistant invoked via home button long press.")
        NAVBAR_ASSIST_LONGPRESS(550);

        private final int mId;

        NavBarActionEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private final AutoHideUiElement mAutoHideUiElement = new AutoHideUiElement() {
        @Override
        public void synchronizeState() {
            checkNavBarModes();
        }

        @Override
        public boolean shouldHideOnTouch() {
            return !mNotificationRemoteInputManager.isRemoteInputActive();
        }

        @Override
        public boolean isVisible() {
            return isTransientShown();
        }

        @Override
        public void hide() {
            clearTransient();
        }
    };

    private final NavBarHelper.NavbarTaskbarStateUpdater mNavbarTaskbarStateUpdater =
            new NavBarHelper.NavbarTaskbarStateUpdater() {
                @Override
                public void updateAccessibilityServicesState() {
                    updateAccessibilityStateFlags();
                }

                @Override
                public void updateAssistantAvailable(boolean available,
                        boolean longPressHomeEnabled) {
                    // TODO(b/198002034): Content observers currently can still be called back after
                    //  being unregistered, and in this case we can ignore the change if the nav bar
                    //  has been destroyed already
                    if (mView == null) {
                        return;
                    }
                    mLongPressHomeEnabled = longPressHomeEnabled;
                    updateAssistantEntrypoints(available, longPressHomeEnabled);
                }

                @Override
                public void updateWallpaperVisibility(boolean visible, int displayId) {
                    mNavigationBarTransitions.setWallpaperVisibility(visible);
                }

                @Override
                public void updateRotationWatcherState(int rotation) {
                    if (mIsOnDefaultDisplay && mView != null) {
                        mView.getRotationButtonController().onRotationWatcherChanged(rotation);
                        if (mView.needsReorient(rotation)) {
                            repositionNavigationBar(rotation);
                        }
                    }
                }
            };

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            mView.onOverviewProxyConnectionChange(
                    mOverviewProxyService.isEnabled());
            mView.setShouldShowSwipeUpUi(mOverviewProxyService.shouldShowSwipeUpUI());
            updateScreenPinningGestures();
        }

        @Override
        public void onPrioritizedRotation(@Surface.Rotation int rotation) {
            mStartingQuickSwitchRotation = rotation;
            if (rotation == -1) {
                mShowOrientedHandleForImmersiveMode = false;
            }
            orientSecondaryHomeHandle();
        }

        @Override
        public void startAssistant(Bundle bundle) {
            mAssistManagerLazy.get().startAssist(bundle);
        }

        @Override
        public void setAssistantOverridesRequested(int[] invocationTypes) {
            mAssistManagerLazy.get().setAssistantOverridesRequested(invocationTypes);
        }

        @Override
        public void animateNavBarLongPress(boolean isTouchDown, boolean shrink, long durationMs) {
            mView.getHomeHandle().animateLongPress(isTouchDown, shrink, durationMs);
        }

        @Override
        public void setOverrideHomeButtonLongPress(long duration, float slopMultiplier,
                boolean haptic) {
            Log.d(TAG, "setOverrideHomeButtonLongPress receives: " + duration + ";"
                    + slopMultiplier + ";" + haptic);
            mOverrideHomeButtonLongPressDurationMs = Optional.of(duration)
                    .filter(value -> value > 0);
            mOverrideHomeButtonLongPressSlopMultiplier = Optional.of(slopMultiplier)
                    .filter(value -> value > 0);
            mHomeButtonLongPressHapticEnabled = haptic;
            mOverrideHomeButtonLongPressDurationMs.ifPresent(aLong
                    -> Log.d(TAG, "Use duration override: " + aLong));
            mOverrideHomeButtonLongPressSlopMultiplier.ifPresent(aFloat
                    -> Log.d(TAG, "Use slop multiplier override: " + aFloat));
            if (mView != null) {
                reconfigureHomeLongClick();
            }
        }

        @Override
        public void onHomeRotationEnabled(boolean enabled) {
            mView.getRotationButtonController().setHomeRotationEnabled(enabled);
        }

        @Override
        public void onOverviewShown(boolean fromHome) {
            // If the overview has fixed orientation that may change display to natural rotation,
            // we don't want the user rotation to be reset. So after user returns to application,
            // it can keep in the original rotation.
            mView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }

        @Override
        public void onTaskbarStatusUpdated(boolean visible, boolean stashed) {
            mView.getFloatingRotationButton().onTaskbarStateChanged(visible, stashed);
        }

        @Override
        public void onToggleRecentApps() {
            // The same case as onOverviewShown but only for 3-button navigation.
            mView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }
    };

    private NavigationBarTransitions.DarkIntensityListener mOrientationHandleIntensityListener =
            new NavigationBarTransitions.DarkIntensityListener() {
                @Override
                public void onDarkIntensity(float darkIntensity) {
                    mOrientationHandle.setDarkIntensity(darkIntensity);
                }
            };

    private final Runnable mAutoDim = () -> getBarTransitions().setAutoDim(true);
    private final Runnable mEnableLayoutTransitions = () -> mView.setLayoutTransitionsEnabled(true);
    private final Runnable mOnVariableDurationHomeLongClick = () -> {
        if (onHomeLongClick(mView.getHomeButton().getCurrentView())) {
            if (mHomeButtonLongPressHapticEnabled) {
                mView.getHomeButton().getCurrentView().performHapticFeedback(
                        HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            }
        }
    };

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (properties.getKeyset().contains(HOME_BUTTON_LONG_PRESS_DURATION_MS)) {
                        mHomeButtonLongPressDurationMs = Optional.of(
                            properties.getLong(HOME_BUTTON_LONG_PRESS_DURATION_MS, 0))
                                .filter(duration -> duration != 0);
                        if (mView != null) {
                            reconfigureHomeLongClick();
                        }
                    }
                }
            };

    private final NotificationShadeDepthController.DepthListener mDepthListener =
            new NotificationShadeDepthController.DepthListener() {
                boolean mHasBlurs;

                @Override
                public void onWallpaperZoomOutChanged(float zoomOut) {
                }

                @Override
                public void onBlurRadiusChanged(int radius) {
                    boolean hasBlurs = radius != 0;
                    if (hasBlurs == mHasBlurs) {
                        return;
                    }
                    mHasBlurs = hasBlurs;
                    mRegionSamplingHelper.setWindowHasBlurs(hasBlurs);
                }
            };

    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                private void notifyScreenStateChanged(boolean isScreenOn) {
                    notifyNavigationBarScreenOn();
                    mView.onScreenStateChanged(isScreenOn);
                }

                @Override
                public void onStartedWakingUp() {
                    notifyScreenStateChanged(true);
                    if (isGesturalModeOnDefaultDisplay(getContext(), mDisplayTracker,
                            mNavBarMode)) {
                        mRegionSamplingHelper.start(mSamplingBounds);
                    }
                }

                @Override
                public void onFinishedGoingToSleep() {
                    notifyScreenStateChanged(false);
                    mRegionSamplingHelper.stop();
                }
            };

    private boolean mScreenPinningActive = false;
    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onLockTaskModeChanged(int mode) {
            mScreenPinningActive = (mode == LOCK_TASK_MODE_PINNED);
            mSysUiFlagsContainer.setFlag(SYSUI_STATE_SCREEN_PINNING, mScreenPinningActive)
                    .commitUpdate(mDisplayId);
            mView.setInScreenPinning(mScreenPinningActive);
            updateScreenPinningGestures();
        }
    };

    @Inject
    NavigationBar(
            NavigationBarView navigationBarView,
            NavigationBarFrame navigationBarFrame,
            @Nullable Bundle savedState,
            @DisplayId Context context,
            @DisplayId WindowManager windowManager,
            Lazy<AssistManager> assistManagerLazy,
            AccessibilityManager accessibilityManager,
            DeviceProvisionedController deviceProvisionedController,
            MetricsLogger metricsLogger,
            OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            StatusBarStateController statusBarStateController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            SysUiState sysUiFlagsContainer,
            UserTracker userTracker,
            CommandQueue commandQueue,
            Optional<Pip> pipOptional,
            Optional<Recents> recentsOptional,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            KeyguardStateController keyguardStateController,
            ShadeViewController shadeViewController,
            PanelExpansionInteractor panelExpansionInteractor,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationShadeDepthController notificationShadeDepthController,
            @Main Handler mainHandler,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            UiEventLogger uiEventLogger,
            NavBarHelper navBarHelper,
            LightBarController mainLightBarController,
            LightBarController.Factory lightBarControllerFactory,
            AutoHideController mainAutoHideController,
            AutoHideController.Factory autoHideControllerFactory,
            Optional<TelecomManager> telecomManagerOptional,
            InputMethodManager inputMethodManager,
            DeadZone deadZone,
            DeviceConfigProxy deviceConfigProxy,
            NavigationBarTransitions navigationBarTransitions,
            Optional<BackAnimation> backAnimation,
            UserContextProvider userContextProvider,
            WakefulnessLifecycle wakefulnessLifecycle,
            TaskStackChangeListeners taskStackChangeListeners,
            DisplayTracker displayTracker,
            NavBarButtonClickLogger navBarButtonClickLogger,
            NavbarOrientationTrackingLogger navbarOrientationTrackingLogger) {
        super(navigationBarView);
        mFrame = navigationBarFrame;
        mContext = context;
        mSavedState = savedState;
        mWindowManager = windowManager;
        mAccessibilityManager = accessibilityManager;
        mDeviceProvisionedController = deviceProvisionedController;
        mStatusBarStateController = statusBarStateController;
        mMetricsLogger = metricsLogger;
        mAssistManagerLazy = assistManagerLazy;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mSysUiFlagsContainer = sysUiFlagsContainer;
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mKeyguardStateController = keyguardStateController;
        mShadeViewController = shadeViewController;
        mPanelExpansionInteractor = panelExpansionInteractor;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mOverviewProxyService = overviewProxyService;
        mNavigationModeController = navigationModeController;
        mUserTracker = userTracker;
        mCommandQueue = commandQueue;
        mPipOptional = pipOptional;
        mRecentsOptional = recentsOptional;
        mDeadZone = deadZone;
        mDeviceConfigProxy = deviceConfigProxy;
        mNavigationBarTransitions = navigationBarTransitions;
        mBackAnimation = backAnimation;
        mHandler = mainHandler;
        mUiEventLogger = uiEventLogger;
        mNavBarHelper = navBarHelper;
        mNotificationShadeDepthController = notificationShadeDepthController;
        mMainLightBarController = mainLightBarController;
        mLightBarControllerFactory = lightBarControllerFactory;
        mMainAutoHideController = mainAutoHideController;
        mAutoHideControllerFactory = autoHideControllerFactory;
        mTelecomManagerOptional = telecomManagerOptional;
        mInputMethodManager = inputMethodManager;
        mUserContextProvider = userContextProvider;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mTaskStackChangeListeners = taskStackChangeListeners;
        mDisplayTracker = displayTracker;
        mEdgeBackGestureHandler = navBarHelper.getEdgeBackGestureHandler();
        mNavBarButtonClickLogger = navBarButtonClickLogger;
        mNavbarOrientationTrackingLogger = navbarOrientationTrackingLogger;

        mNavColorSampleMargin = getResources()
                .getDimensionPixelSize(R.dimen.navigation_handle_sample_horizontal_margin);

        mOnComputeInternalInsetsListener = info -> {
            // When the nav bar is in 2-button or 3-button mode, or when IME is visible in fully
            // gestural mode, the entire nav bar should be touchable.
            if (!mEdgeBackGestureHandler.isHandlingGestures()) {
                // We're in 2/3 button mode OR back button force-shown in SUW
                if (!mImeVisible) {
                    // IME not showing, take all touches
                    info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
                    return;
                }
                if (!mView.isImeRenderingNavButtons()) {
                    // IME showing but not drawing any buttons, take all touches
                    info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
                    return;
                }
            }

            // When in gestural and the IME is showing, don't use the nearest region since it will
            // take gesture space away from the IME
            info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(
                    getButtonLocations(false /* inScreen */, false /* useNearestRegion */));
        };

        mRegionSamplingHelper = new RegionSamplingHelper(mView,
                new RegionSamplingHelper.SamplingCallback() {
                    @Override
                    public void onRegionDarknessChanged(boolean isRegionDark) {
                        getBarTransitions().getLightTransitionsController().setIconsDark(
                                !isRegionDark, true /* animate */);
                    }

                    @Override
                    public Rect getSampledRegion(View sampledView) {
                        if (mOrientedHandleSamplingRegion != null) {
                            return mOrientedHandleSamplingRegion;
                        }

                        return calculateSamplingRect();
                    }

                    @Override
                    public boolean isSamplingEnabled() {
                        return isGesturalModeOnDefaultDisplay(getContext(), mDisplayTracker,
                                mNavBarMode);
                    }
                }, mainExecutor, bgExecutor);

        mView.setBackgroundExecutor(bgExecutor);
        mView.setEdgeBackGestureHandler(mEdgeBackGestureHandler);
        mView.setDisplayTracker(mDisplayTracker);
        mNavBarMode = mNavigationModeController.addListener(mModeChangedListener);
    }

    public NavigationBarView getView() {
        return mView;
    }

    @Override
    public void onInit() {
        // TODO: A great deal of this code should probably live in onViewAttached.
        // It should also has corresponding cleanup in onViewDetached.
        mView.setBarTransitions(mNavigationBarTransitions);
        mView.setTouchHandler(mTouchHandler);
        setNavBarMode(mNavBarMode);
        mEdgeBackGestureHandler.setStateChangeCallback(mView::updateStates);
        mEdgeBackGestureHandler.setButtonForcedVisibleChangeCallback((forceVisible) -> {
            repositionNavigationBar(mCurrentRotation);
        });
        mNavigationBarTransitions.addListener(this::onBarTransition);
        mView.updateRotationButton();

        mView.setVisibility(
                mStatusBarKeyguardViewManager.isNavBarVisible() ? View.VISIBLE : View.INVISIBLE);

        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + mView);

        mWindowManager.addView(mFrame,
                getBarLayoutParams(mContext.getResources().getConfiguration().windowConfiguration
                        .getRotation()));
        mDisplayId = mContext.getDisplayId();
        mIsOnDefaultDisplay = mDisplayId == mDisplayTracker.getDefaultDisplayId();

        // Ensure we try to get currentSysuiState from navBarHelper before command queue callbacks
        // start firing, since the latter is source of truth
        parseCurrentSysuiState();
        mCommandQueue.addCallback(this);
        mHomeButtonLongPressDurationMs = Optional.of(mDeviceConfigProxy.getLong(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                HOME_BUTTON_LONG_PRESS_DURATION_MS,
                /* defaultValue = */ 0
        )).filter(duration -> duration != 0);
        // This currently MUST be called after mHomeButtonLongPressDurationMs is initialized since
        // the registration callbacks will trigger code that uses it
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mDeviceConfigProxy.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, mHandler::post, mOnPropertiesChangedListener);

        if (mSavedState != null) {
            mDisabledFlags1 = mSavedState.getInt(EXTRA_DISABLE_STATE, 0);
            mDisabledFlags2 = mSavedState.getInt(EXTRA_DISABLE2_STATE, 0);
            mAppearance = mSavedState.getInt(EXTRA_APPEARANCE, 0);
            mBehavior = mSavedState.getInt(EXTRA_BEHAVIOR, 0);
            mTransientShown = mSavedState.getBoolean(EXTRA_TRANSIENT_STATE, false);
        }

        // Respect the latest disabled-flags.
        mCommandQueue.recomputeDisableFlags(mDisplayId, false);

        mNotificationShadeDepthController.addListener(mDepthListener);
        mTaskStackChangeListeners.registerTaskStackListener(mTaskStackListener);
    }

    public void destroyView() {
        Trace.beginSection("NavigationBar#destroyView");
        try {
            setAutoHideController(/* autoHideController */ null);
            mCommandQueue.removeCallback(this);
            Trace.beginSection("NavigationBar#removeViewImmediate");
            try {
                mWindowManager.removeViewImmediate(mView.getRootView());
            } finally {
                Trace.endSection();
            }
            mNavigationModeController.removeListener(mModeChangedListener);
            mEdgeBackGestureHandler.setStateChangeCallback(null);

            mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
            mNotificationShadeDepthController.removeListener(mDepthListener);

            mDeviceConfigProxy.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
            mTaskStackChangeListeners.unregisterTaskStackListener(mTaskStackListener);
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onViewAttached() {
        final Display display = mView.getDisplay();
        mView.setComponents(mRecentsOptional);
        if (mCentralSurfacesOptionalLazy.get().isPresent()) {
            mView.setComponents(mShadeViewController, mPanelExpansionInteractor);
        }
        mView.setDisabledFlags(mDisabledFlags1, mSysUiFlagsContainer);
        mView.setOnVerticalChangedListener(this::onVerticalChanged);
        mView.setOnTouchListener(this::onNavigationTouch);
        if (mSavedState != null) {
            getBarTransitions().getLightTransitionsController().restoreState(mSavedState);
        }
        setNavigationIconHints(mNavigationIconHints);
        setWindowVisible(isNavBarWindowVisible());
        mView.setBehavior(mBehavior);
        setNavBarMode(mNavBarMode);
        repositionNavigationBar(mCurrentRotation);
        mView.setUpdateActiveTouchRegionsCallback(
                () -> mOverviewProxyService.onActiveNavBarRegionChanges(
                        getButtonLocations(true /* inScreen */, true /* useNearestRegion */)));

        mView.getViewTreeObserver().addOnComputeInternalInsetsListener(
                mOnComputeInternalInsetsListener);

        mPipOptional.ifPresent(mView::addPipExclusionBoundsChangeListener);
        mBackAnimation.ifPresent(mView::registerBackAnimation);

        prepareNavigationBarView();
        checkNavBarModes();

        mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        notifyNavigationBarScreenOn();

        mOverviewProxyService.addCallback(mOverviewProxyListener);
        updateSystemUiStateFlags();

        // Currently there is no accelerometer sensor on non-default display.
        if (mIsOnDefaultDisplay) {
            final RotationButtonController rotationButtonController =
                    mView.getRotationButtonController();

            // Reset user rotation pref to match that of the WindowManager if starting in locked
            // mode. This will automatically happen when switching from auto-rotate to locked mode.
            if (display != null && rotationButtonController.isRotationLocked()) {
                rotationButtonController.setRotationLockedAtAngle(
                        display.getRotation(), /* caller= */ "NavigationBar#onViewAttached");
            }
        } else {
            mDisabledFlags2 |= StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS;
        }
        setDisabled2Flags(mDisabledFlags2);

        initSecondaryHomeHandleForRotation();

        // Unfortunately, we still need it because status bar needs LightBarController
        // before notifications creation. We cannot directly use getLightBarController()
        // from NavigationBarFragment directly.
        LightBarController lightBarController = mIsOnDefaultDisplay
                ? mMainLightBarController : mLightBarControllerFactory.create(mContext);
        setLightBarController(lightBarController);

        // TODO(b/118592525): to support multi-display, we start to add something which is
        //                    per-display, while others may be global. I think it's time to
        //                    add a new class maybe named DisplayDependency to solve
        //                    per-display Dependency problem.
        // Alternative: this is a good case for a Dagger subcomponent. Same with LightBarController.
        AutoHideController autoHideController = mIsOnDefaultDisplay
                ? mMainAutoHideController : mAutoHideControllerFactory.create(mContext);
        setAutoHideController(autoHideController);
        restoreAppearanceAndTransientState();
    }

    @Override
    public void onViewDetached() {
        mView.setUpdateActiveTouchRegionsCallback(null);
        getBarTransitions().destroy();
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
        mUserTracker.removeCallback(mUserChangedCallback);
        mWakefulnessLifecycle.removeObserver(mWakefulnessObserver);
        if (mOrientationHandle != null) {
            resetSecondaryHandle();
            getBarTransitions().removeDarkIntensityListener(mOrientationHandleIntensityListener);
            mWindowManager.removeView(mOrientationHandle);
            mOrientationHandle.getViewTreeObserver().removeOnGlobalLayoutListener(
                    mOrientationHandleGlobalLayoutListener);
        }
        mView.getViewTreeObserver().removeOnComputeInternalInsetsListener(
                mOnComputeInternalInsetsListener);
        mHandler.removeCallbacks(mAutoDim);
        mHandler.removeCallbacks(mOnVariableDurationHomeLongClick);
        mHandler.removeCallbacks(mEnableLayoutTransitions);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mPipOptional.ifPresent(mView::removePipExclusionBoundsChangeListener);
        mFrame = null;
        mOrientationHandle = null;
    }

    // TODO: Remove this when we update nav bar recreation
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(EXTRA_DISABLE_STATE, mDisabledFlags1);
        outState.putInt(EXTRA_DISABLE2_STATE, mDisabledFlags2);
        outState.putInt(EXTRA_APPEARANCE, mAppearance);
        outState.putInt(EXTRA_BEHAVIOR, mBehavior);
        outState.putBoolean(EXTRA_TRANSIENT_STATE, mTransientShown);
        getBarTransitions().getLightTransitionsController().saveState(outState);
    }

    /**
     * Called when a non-reloading configuration change happens and we need to update.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        final int rotation = newConfig.windowConfiguration.getRotation();
        final Locale locale = mContext.getResources().getConfiguration().locale;
        final int ld = TextUtils.getLayoutDirectionFromLocale(locale);
        if (!locale.equals(mLocale) || ld != mLayoutDirection) {
            if (DEBUG) {
                Log.v(TAG, String.format(
                        "config changed locale/LD: %s (%d) -> %s (%d)", mLocale, mLayoutDirection,
                        locale, ld));
            }
            mLocale = locale;
            mLayoutDirection = ld;
            refreshLayout(ld);
        }
        repositionNavigationBar(rotation);
        if (canShowSecondaryHandle()) {
            if (rotation != mCurrentRotation) {
                mCurrentRotation = rotation;
                orientSecondaryHomeHandle();
            }
        }
    }

    private void initSecondaryHomeHandleForRotation() {
        if (mNavBarMode != NAV_BAR_MODE_GESTURAL) {
            return;
        }

        mOrientationHandle = new QuickswitchOrientedNavHandle(mContext);
        mOrientationHandle.setId(R.id.secondary_home_handle);

        getBarTransitions().addDarkIntensityListener(mOrientationHandleIntensityListener);
        mOrientationParams = new WindowManager.LayoutParams(0, 0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        mOrientationParams.setTitle("SecondaryHomeHandle" + mContext.getDisplayId());
        mOrientationParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION
                | WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
        mWindowManager.addView(mOrientationHandle, mOrientationParams);
        mOrientationHandle.setVisibility(View.GONE);

        logNavbarOrientation("initSecondaryHomeHandleForRotation");
        mOrientationParams.setFitInsetsTypes(0 /* types*/);
        mOrientationHandleGlobalLayoutListener =
                () -> {
                    if (mStartingQuickSwitchRotation == -1) {
                        return;
                    }

                    RectF boundsOnScreen = mOrientationHandle.computeHomeHandleBounds();
                    mOrientationHandle.mapRectFromViewToScreenCoords(boundsOnScreen, true);
                    Rect boundsRounded = new Rect();
                    boundsOnScreen.roundOut(boundsRounded);
                    setOrientedHandleSamplingRegion(boundsRounded);
                };
        mOrientationHandle.getViewTreeObserver().addOnGlobalLayoutListener(
                mOrientationHandleGlobalLayoutListener);
    }

    private void orientSecondaryHomeHandle() {
        if (!canShowSecondaryHandle()) {
            return;
        }

        if (mStartingQuickSwitchRotation == -1) {
            resetSecondaryHandle();
        } else {
            int deltaRotation = deltaRotation(mCurrentRotation, mStartingQuickSwitchRotation);
            if (mStartingQuickSwitchRotation == -1 || deltaRotation == -1) {
                // Curious if starting quickswitch can change between the if check and our delta
                Log.d(TAG, "secondary nav delta rotation: " + deltaRotation
                        + " current: " + mCurrentRotation
                        + " starting: " + mStartingQuickSwitchRotation);
            }
            int height = 0;
            int width = 0;
            Rect dispSize = mWindowManager.getCurrentWindowMetrics().getBounds();
            mOrientationHandle.setDeltaRotation(deltaRotation);
            switch (deltaRotation) {
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    height = dispSize.height();
                    width = mView.getHeight();
                    break;
                case Surface.ROTATION_180:
                case Surface.ROTATION_0:
                    // TODO(b/152683657): Need to determine best UX for this
                    if (!mShowOrientedHandleForImmersiveMode) {
                        resetSecondaryHandle();
                        return;
                    }
                    width = dispSize.width();
                    height = mView.getHeight();
                    break;
            }

            mOrientationParams.gravity =
                    deltaRotation == Surface.ROTATION_0 ? Gravity.BOTTOM :
                            (deltaRotation == Surface.ROTATION_90 ? Gravity.LEFT : Gravity.RIGHT);
            mOrientationParams.height = height;
            mOrientationParams.width = width;
            mWindowManager.updateViewLayout(mOrientationHandle, mOrientationParams);
            mView.setVisibility(View.GONE);
            mOrientationHandle.setVisibility(View.VISIBLE);
            logNavbarOrientation("orientSecondaryHomeHandle");
        }
    }

    private void resetSecondaryHandle() {
        if (mOrientationHandle != null) {
            // Case where nav mode is changed w/o ever invoking a quickstep
            // mOrientedHandle is initialized lazily
            mOrientationHandle.setVisibility(View.GONE);
        }
        mView.setVisibility(View.VISIBLE);
        logNavbarOrientation("resetSecondaryHandle");
        setOrientedHandleSamplingRegion(null);
    }

    /**
     * Logging method for issues concerning Navbar/secondary handle visibility.
     */
    private void logNavbarOrientation(String methodName) {
        boolean isViewVisible = (mView != null) && (mView.getVisibility() == View.VISIBLE);
        boolean isSecondaryHandleVisible =
                (mOrientationHandle != null) && (mOrientationHandle.getVisibility()
                        == View.VISIBLE);
        mNavbarOrientationTrackingLogger.logPrimaryAndSecondaryVisibility(methodName, isViewVisible,
                mShowOrientedHandleForImmersiveMode, isSecondaryHandleVisible, mCurrentRotation,
                mStartingQuickSwitchRotation);
    }

    private void parseCurrentSysuiState() {
        NavBarHelper.CurrentSysuiState state = mNavBarHelper.getCurrentSysuiState();
        if (state.mWindowStateDisplayId == mDisplayId) {
            mNavigationBarWindowState = state.mWindowState;
        }
    }

    private void reconfigureHomeLongClick() {
        if (mView.getHomeButton().getCurrentView() == null) {
            return;
        }
        if (mHomeButtonLongPressDurationMs.isPresent()
                || mOverrideHomeButtonLongPressDurationMs.isPresent()
                || mOverrideHomeButtonLongPressSlopMultiplier.isPresent()
                || !mLongPressHomeEnabled) {
            mView.getHomeButton().getCurrentView().setLongClickable(false);
            mView.getHomeButton().getCurrentView().setHapticFeedbackEnabled(false);
            mView.getHomeButton().setOnLongClickListener(null);
        } else {
            mView.getHomeButton().getCurrentView().setLongClickable(true);
            mView.getHomeButton().getCurrentView().setHapticFeedbackEnabled(
                    mHomeButtonLongPressHapticEnabled);
            mView.getHomeButton().setOnLongClickListener(this::onHomeLongClick);
        }
    }

    private int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    public void dump(PrintWriter pw) {
        pw.println("NavigationBar (displayId=" + mDisplayId + "):");
        pw.println("  mStartingQuickSwitchRotation=" + mStartingQuickSwitchRotation);
        pw.println("  mCurrentRotation=" + mCurrentRotation);
        pw.println("  mHomeButtonLongPressDurationMs=" + mHomeButtonLongPressDurationMs);
        pw.println("  mOverrideHomeButtonLongPressDurationMs="
                + mOverrideHomeButtonLongPressDurationMs);
        pw.println("  mOverrideHomeButtonLongPressSlopMultiplier="
                + mOverrideHomeButtonLongPressSlopMultiplier);
        pw.println("  mLongPressHomeEnabled=" + mLongPressHomeEnabled);
        pw.println("  mNavigationBarWindowState="
                + windowStateToString(mNavigationBarWindowState));
        pw.println("  mTransitionMode="
                + BarTransitions.modeToString(mTransitionMode));
        pw.println("  mTransientShown=" + mTransientShown);
        pw.println("  mTransientShownFromGestureOnSystemBar="
                + mTransientShownFromGestureOnSystemBar);
        pw.println("  mScreenPinningActive=" + mScreenPinningActive);
        dumpBarTransitions(pw, "mNavigationBarView", getBarTransitions());

        pw.println("  mOrientedHandleSamplingRegion: " + mOrientedHandleSamplingRegion);
        mView.dump(pw);
        mRegionSamplingHelper.dump(pw);
        if (mAutoHideController != null) {
            mAutoHideController.dump(pw);
        }
    }

    // ----- CommandQueue Callbacks -----

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean imeShown = mNavBarHelper.isImeShown(vis);
        showImeSwitcher = imeShown && showImeSwitcher;
        int hints = Utilities.calculateBackDispositionHints(mNavigationIconHints, backDisposition,
                imeShown, showImeSwitcher);
        if (hints == mNavigationIconHints) return;

        setNavigationIconHints(hints);
        checkBarModes();
        updateSystemUiStateFlags();
    }

    @Override
    public void setWindowState(
            int displayId, @WindowType int window, @WindowVisibleState int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            updateSystemUiStateFlags();
            mShowOrientedHandleForImmersiveMode = state == WINDOW_STATE_HIDDEN;
            if (mOrientationHandle != null
                    && mStartingQuickSwitchRotation != -1) {
                orientSecondaryHomeHandle();
            }
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));
            setWindowVisible(isNavBarWindowVisible());
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        // The CommandQueue callbacks are added when the view is created to ensure we track other
        // states, but until the view is attached (at the next traversal), the view's display is
        // not valid.  Just ignore the rotation in this case.
        if (!mView.isAttachedToWindow()) return;

        final boolean rotateSuggestionsDisabled = RotationButtonController
                .hasDisable2RotateSuggestionFlag(mDisabledFlags2);
        final RotationButtonController rotationButtonController =
                mView.getRotationButtonController();
        if (DEBUG_ROTATION) {
            Log.v(TAG, "onRotationProposal proposedRotation=" + Surface.rotationToString(rotation)
                    + ", isValid=" + isValid + ", mNavBarWindowState="
                    + StatusBarManager.windowStateToString(mNavigationBarWindowState)
                    + ", rotateSuggestionsDisabled=" + rotateSuggestionsDisabled
                    + ", isRotateButtonVisible="
                    + rotationButtonController.getRotationButton().isVisible());
        }
        // Respect the disabled flag, no need for action as flag change callback will handle hiding
        if (rotateSuggestionsDisabled) return;

        rotationButtonController.onRotationProposal(rotation, isValid);
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        mView.getRotationButtonController().setRecentsAnimationRunning(running);
    }

    /** Restores the appearance and the transient saved state to {@link NavigationBar}. */
    public void restoreAppearanceAndTransientState() {
        final int transitionMode = transitionMode(mTransientShown, mAppearance);
        mTransitionMode = transitionMode;
        checkNavBarModes();
        if (mAutoHideController != null) {
            mAutoHideController.touchAutoHide();
        }
        if (mLightBarController != null) {
            mLightBarController.onNavigationBarAppearanceChanged(mAppearance,
                    true /* nbModeChanged */, transitionMode, false /* navbarColorManagedByIme */);
        }
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, @InsetsType int requestedVisibleTypes, String packageName,
            LetterboxDetails[] letterboxDetails) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean nbModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            nbModeChanged = updateTransitionMode(transitionMode(mTransientShown, appearance));
        }
        if (mLightBarController != null) {
            mLightBarController.onNavigationBarAppearanceChanged(appearance, nbModeChanged,
                    mTransitionMode, navbarColorManagedByIme);
        }
        if (mBehavior != behavior) {
            mBehavior = behavior;
            mView.setBehavior(behavior);
            updateSystemUiStateFlags();
        }
    }

    @Override
    public void showTransient(int displayId, @InsetsType int types, boolean isGestureOnSystemBar) {
        if (displayId != mDisplayId) {
            return;
        }
        if ((types & WindowInsets.Type.navigationBars()) == 0) {
            return;
        }
        if (!mTransientShown) {
            mTransientShown = true;
            mTransientShownFromGestureOnSystemBar = isGestureOnSystemBar;
            handleTransientChanged();
        }
    }

    @Override
    public void abortTransient(int displayId, @InsetsType int types) {
        if (displayId != mDisplayId) {
            return;
        }
        if ((types & WindowInsets.Type.navigationBars()) == 0) {
            return;
        }
        clearTransient();
    }

    private void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            mTransientShownFromGestureOnSystemBar = false;
            handleTransientChanged();
        }
    }

    private void handleTransientChanged() {
        mEdgeBackGestureHandler.onNavBarTransientStateChanged(mTransientShown);

        final int transitionMode = transitionMode(mTransientShown, mAppearance);
        if (updateTransitionMode(transitionMode) && mLightBarController != null) {
            mLightBarController.onNavigationBarModeChanged(transitionMode);
        }
    }

    // Returns true if the bar mode is changed.
    private boolean updateTransitionMode(int barMode) {
        if (mTransitionMode != barMode) {
            mTransitionMode = barMode;
            checkNavBarModes();
            if (mAutoHideController != null) {
                mAutoHideController.touchAutoHide();
            }
            return true;
        }
        return false;
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }
        // Navigation bar flags are in both state1 and state2.
        final int masked = state1 & (StatusBarManager.DISABLE_HOME
                | StatusBarManager.DISABLE_RECENT
                | StatusBarManager.DISABLE_BACK
                | StatusBarManager.DISABLE_SEARCH);
        if (masked != mDisabledFlags1) {
            mDisabledFlags1 = masked;
            mView.setDisabledFlags(state1, mSysUiFlagsContainer);
            updateScreenPinningGestures();
        }

        // Only default display supports rotation suggestions.
        if (mIsOnDefaultDisplay) {
            final int masked2 = state2 & (StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS);
            if (masked2 != mDisabledFlags2) {
                mDisabledFlags2 = masked2;
                setDisabled2Flags(masked2);
            }
        }
    }

    private void setDisabled2Flags(int state2) {
        // Method only called on change of disable2 flags
        mView.getRotationButtonController().onDisable2FlagChanged(state2);
    }

    // ----- Internal stuff -----

    private void refreshLayout(int layoutDirection) {
        mView.setLayoutDirection(layoutDirection);
    }

    private boolean shouldDisableNavbarGestures() {
        return !mDeviceProvisionedController.isDeviceProvisioned()
                || (mDisabledFlags1 & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private void repositionNavigationBar(int rotation) {
        if (mView == null || !mView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout(mFrame, getBarLayoutParams(rotation));
    }

    private void updateScreenPinningGestures() {
        // Change the cancel pin gesture to home and back if recents button is invisible
        ButtonDispatcher backButton = mView.getBackButton();
        ButtonDispatcher recentsButton = mView.getRecentsButton();
        if (mScreenPinningActive) {
            boolean recentsVisible = mView.isRecentsButtonVisible();
            backButton.setOnLongClickListener(recentsVisible
                    ? this::onLongPressBackRecents
                    : this::onLongPressBackHome);
            recentsButton.setOnLongClickListener(this::onLongPressBackRecents);
        } else {
            backButton.setOnLongClickListener(null);
            recentsButton.setOnLongClickListener(null);
        }
        // Note, this needs to be set after even if we're setting the listener to null
        backButton.setLongClickable(mScreenPinningActive);
        recentsButton.setLongClickable(mScreenPinningActive);
    }

    private void notifyNavigationBarScreenOn() {
        mView.updateNavButtonIcons();
    }

    private void prepareNavigationBarView() {
        mView.reorient();

        ButtonDispatcher recentsButton = mView.getRecentsButton();
        recentsButton.setOnClickListener(this::onRecentsClick);
        recentsButton.setOnTouchListener(this::onRecentsTouch);

        ButtonDispatcher homeButton = mView.getHomeButton();
        homeButton.setOnTouchListener(this::onHomeTouch);
        homeButton.setNavBarButtonClickLogger(mNavBarButtonClickLogger);

        ButtonDispatcher backButton = mView.getBackButton();
        backButton.setNavBarButtonClickLogger(mNavBarButtonClickLogger);

        reconfigureHomeLongClick();

        ButtonDispatcher accessibilityButton = mView.getAccessibilityButton();
        accessibilityButton.setOnClickListener(this::onAccessibilityClick);
        accessibilityButton.setOnLongClickListener(this::onAccessibilityLongClick);
        updateAccessibilityStateFlags();

        ButtonDispatcher imeSwitcherButton = mView.getImeSwitchButton();
        imeSwitcherButton.setOnClickListener(this::onImeSwitcherClick);

        updateScreenPinningGestures();
    }

    @VisibleForTesting
    boolean onHomeTouch(View v, MotionEvent event) {
        if (mHomeBlockedThisTouch && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        // If an incoming call is ringing, HOME is totally disabled.
        // (The user is already on the InCallUI at this point,
        // and their ONLY options are to answer or reject the call.)
        final Optional<CentralSurfaces> centralSurfacesOptional = mCentralSurfacesOptionalLazy.get();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mCurrentDownEvent != null) {
                    mCurrentDownEvent.recycle();
                }
                mCurrentDownEvent = MotionEvent.obtain(event);
                mHomeBlockedThisTouch = false;
                if (mTelecomManagerOptional.isPresent()
                        && mTelecomManagerOptional.get().isRinging()) {
                    if (mKeyguardStateController.isShowing()) {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call. " +
                                "No heads up");
                        mHomeBlockedThisTouch = true;
                        return true;
                    }
                }
                if (mLongPressHomeEnabled) {
                    if (mOverrideHomeButtonLongPressDurationMs.isPresent()) {
                        Log.d(TAG, "ACTION_DOWN Launcher override duration: "
                                + mOverrideHomeButtonLongPressDurationMs.get());
                        mHandler.postDelayed(mOnVariableDurationHomeLongClick,
                                mOverrideHomeButtonLongPressDurationMs.get());
                    } else if (mOverrideHomeButtonLongPressSlopMultiplier.isPresent()) {
                        // If override timeout doesn't exist but override touch slop exists, we use
                        // system default long press duration
                        Log.d(TAG, "ACTION_DOWN default duration: "
                                + ViewConfiguration.getLongPressTimeout());
                        mHandler.postDelayed(mOnVariableDurationHomeLongClick,
                                ViewConfiguration.getLongPressTimeout());
                    } else {
                        mHomeButtonLongPressDurationMs.ifPresent(longPressDuration -> {
                            Log.d(TAG, "ACTION_DOWN original duration: " + longPressDuration);
                            mHandler.postDelayed(mOnVariableDurationHomeLongClick,
                                    longPressDuration);
                        });
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mHandler.hasCallbacks(mOnVariableDurationHomeLongClick)) {
                    Log.v(TAG, "ACTION_MOVE no callback. Don't handle touch slop.");
                    break;
                }
                Log.v(TAG, "ACTION_MOVE handle touch slop");
                float customSlopMultiplier = mOverrideHomeButtonLongPressSlopMultiplier.orElse(1f);
                float touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
                float calculatedTouchSlop =
                        customSlopMultiplier * QUICKSTEP_TOUCH_SLOP_RATIO_TWO_BUTTON * touchSlop;
                float touchSlopSquared = calculatedTouchSlop * calculatedTouchSlop;

                float dx = event.getX() - mCurrentDownEvent.getX();
                float dy = event.getY() - mCurrentDownEvent.getY();
                double distanceSquared = (dx * dx) + (dy * dy);
                if (distanceSquared > touchSlopSquared) {
                    Log.i(TAG, "Touch slop passed. Abort.");
                    mView.abortCurrentGesture();
                    mHandler.removeCallbacks(mOnVariableDurationHomeLongClick);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mOnVariableDurationHomeLongClick);
                centralSurfacesOptional.ifPresent(CentralSurfaces::awakenDreams);
                break;
        }
        return false;
    }

    private void onVerticalChanged(boolean isVertical) {
        // This check can probably be safely removed. It only remained to reduce regression
        // risk for a broad change that removed the CentralSurfaces reference in the if block
        if (mCentralSurfacesOptionalLazy.get().isPresent()) {
            mShadeViewController.setQsScrimEnabled(!isVertical);
        }
    }

    private boolean onNavigationTouch(View v, MotionEvent event) {
        if (mAutoHideController != null) {
            mAutoHideController.checkUserAutoHide(event);
        }
        return false;
    }

    @VisibleForTesting
    boolean onHomeLongClick(View v) {
        if (!mView.isRecentsButtonVisible() && mScreenPinningActive) {
            return onLongPressBackHome(v);
        }
        if (shouldDisableNavbarGestures()) {
            return false;
        }
        mMetricsLogger.action(MetricsEvent.ACTION_ASSIST_LONG_PRESS);
        mUiEventLogger.log(NavBarActionEvent.NAVBAR_ASSIST_LONGPRESS);
        Bundle args = new Bundle();
        args.putInt(
                AssistManager.INVOCATION_TYPE_KEY,
                AssistManager.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        // If Launcher has requested to override long press home, add a delay for the ripple.
        // TODO(b/304146255): Remove this delay once we can exclude 3-button nav from screenshot.
        boolean delayAssistInvocation = mAssistManagerLazy.get().shouldOverrideAssist(
                AssistManager.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        // In practice, I think v should always be a KeyButtonView, but just being safe.
        if (delayAssistInvocation && v instanceof KeyButtonView) {
            ((KeyButtonView) v).setOnRippleInvisibleRunnable(
                    () -> mAssistManagerLazy.get().startAssist(args));
        } else {
            mAssistManagerLazy.get().startAssist(args);
        }
        mCentralSurfacesOptionalLazy.get().ifPresent(CentralSurfaces::awakenDreams);
        mView.abortCurrentGesture();
        return true;
    }

    // additional optimization when we have software system buttons - start loading the recent
    // tasks on touch down
    private boolean onRecentsTouch(View v, MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            mCommandQueue.preloadRecentApps();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mCommandQueue.cancelPreloadRecentApps();
        } else if (action == MotionEvent.ACTION_UP) {
            if (!v.isPressed()) {
                mCommandQueue.cancelPreloadRecentApps();
            }
        }
        return false;
    }

    private void onRecentsClick(View v) {
        mNavBarButtonClickLogger.logRecentsButtonClick();

        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionStart(
                    LatencyTracker.ACTION_TOGGLE_RECENTS);
        }
        mCentralSurfacesOptionalLazy.get().ifPresent(CentralSurfaces::awakenDreams);
        mCommandQueue.toggleRecentApps();
    }

    private void onImeSwitcherClick(View v) {
        mNavBarButtonClickLogger.logImeSwitcherClick();
        mInputMethodManager.showInputMethodPickerFromSystem(
                true /* showAuxiliarySubtypes */, mDisplayId);
        mUiEventLogger.log(KeyButtonView.NavBarButtonEvent.NAVBAR_IME_SWITCHER_BUTTON_TAP);
    };

    private boolean onLongPressBackHome(View v) {
        return onLongPressNavigationButtons(v, R.id.back, R.id.home);
    }

    private boolean onLongPressBackRecents(View v) {
        return onLongPressNavigationButtons(v, R.id.back, R.id.recent_apps);
    }

    /**
     * This handles long-press of both back and recents/home. Back is the common button with
     * combination of recents if it is visible or home if recents is invisible.
     * They are handled together to capture them both being long-pressed
     * at the same time to exit screen pinning (lock task).
     *
     * When accessibility mode is on, only a long-press from recents/home
     * is required to exit.
     *
     * In all other circumstances we try to pass through long-press events
     * for Back, so that apps can still use it.  Which can be from two things.
     * 1) Not currently in screen pinning (lock task).
     * 2) Back is long-pressed without recents/home.
     */
    private boolean onLongPressNavigationButtons(View v, @IdRes int btnId1, @IdRes int btnId2) {
        try {
            boolean sendBackLongPress = false;
            IActivityTaskManager activityManager = ActivityTaskManager.getService();
            boolean touchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();
            boolean inLockTaskMode = activityManager.isInLockTaskMode();
            boolean stopLockTaskMode = false;
            try {
                if (inLockTaskMode && !touchExplorationEnabled) {
                    long time = System.currentTimeMillis();

                    // If we recently long-pressed the other button then they were
                    // long-pressed 'together'
                    if ((time - mLastLockToAppLongPress) < LOCK_TO_APP_GESTURE_TOLERANCE) {
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId1) {
                        ButtonDispatcher button = btnId2 == R.id.recent_apps
                                ? mView.getRecentsButton() : mView.getHomeButton();
                        if (!button.getCurrentView().isPressed()) {
                            // If we aren't pressing recents/home right now then they presses
                            // won't be together, so send the standard long-press action.
                            sendBackLongPress = true;
                        }
                    }
                    mLastLockToAppLongPress = time;
                } else {
                    // If this is back still need to handle sending the long-press event.
                    if (v.getId() == btnId1) {
                        sendBackLongPress = true;
                    } else if (touchExplorationEnabled && inLockTaskMode) {
                        // When in accessibility mode a long press that is recents/home (not back)
                        // should stop lock task.
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId2) {
                        return btnId2 == R.id.recent_apps
                                ? false
                                : onHomeLongClick(mView.getHomeButton().getCurrentView());
                    }
                }
            } finally {
                if (stopLockTaskMode) {
                    activityManager.stopSystemLockTaskMode();
                    // When exiting refresh disabled flags.
                    mView.updateNavButtonIcons();
                }
            }

            if (sendBackLongPress) {
                KeyButtonView keyButtonView = (KeyButtonView) v;
                keyButtonView.sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                keyButtonView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                return true;
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Unable to reach activity manager", e);
        }
        return false;
    }

    private void onAccessibilityClick(View v) {
        mNavBarButtonClickLogger.logAccessibilityButtonClick();
        final Display display = v.getDisplay();
        mAccessibilityManager.notifyAccessibilityButtonClicked(
                display != null ? display.getDisplayId() : mDisplayTracker.getDefaultDisplayId());
    }

    private boolean onAccessibilityLongClick(View v) {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final String chooserClassName = AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        mContext.startActivityAsUser(intent, mUserTracker.getUserHandle());
        return true;
    }

    void updateAccessibilityStateFlags() {
        mLongPressHomeEnabled = mNavBarHelper.getLongPressHomeEnabled();
        if (mView != null) {
            long a11yFlags = mNavBarHelper.getA11yButtonState();
            boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
            boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;
            mView.setAccessibilityButtonState(clickable, longClickable);
        }
        updateSystemUiStateFlags();
    }

    public void updateSystemUiStateFlags() {
        long a11yFlags = mNavBarHelper.getA11yButtonState();
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiFlagsContainer.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isNavBarWindowVisible())
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_IME_SWITCHER_SHOWN) != 0)
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .commitUpdate(mDisplayId);
    }

    private void updateAssistantEntrypoints(boolean assistantAvailable,
            boolean longPressHomeEnabled) {
        if (mOverviewProxyService.getProxy() != null) {
            try {
                mOverviewProxyService.getProxy().onAssistantAvailable(assistantAvailable,
                        longPressHomeEnabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to send assistant availability data to launcher");
            }
        }
        reconfigureHomeLongClick();
    }

    // ----- Methods that DisplayNavigationBarController talks to -----

    /** Applies auto dimming animation on navigation bar when touched. */
    public void touchAutoDim() {
        getBarTransitions().setAutoDim(false);
        mHandler.removeCallbacks(mAutoDim);
        int state = mStatusBarStateController.getState();
        if (state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED) {
            mHandler.postDelayed(mAutoDim, AUTODIM_TIMEOUT_MS);
        }
    }

    public void setLightBarController(LightBarController lightBarController) {
        mLightBarController = lightBarController;
        if (mLightBarController != null) {
            mLightBarController.setNavigationBar(
                    getBarTransitions().getLightTransitionsController());
        }
    }

    private void setWindowVisible(boolean visible) {
        mRegionSamplingHelper.setWindowVisible(visible);
        mView.setWindowVisible(visible);
    }

    /** Sets {@link AutoHideController} to the navigation bar. */
    private void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
        if (mAutoHideController != null) {
            mAutoHideController.setNavigationBar(mAutoHideUiElement);
        }
        mView.setAutoHideController(autoHideController);
    }

    private boolean isTransientShown() {
        return mTransientShown;
    }

    private void checkBarModes() {
        // We only have status bar on default display now.
        if (mIsOnDefaultDisplay) {
            mCentralSurfacesOptionalLazy.get().ifPresent(CentralSurfaces::checkBarModes);
        } else {
            checkNavBarModes();
        }
    }

    public boolean isNavBarWindowVisible() {
        return mNavigationBarWindowState == WINDOW_STATE_SHOWING;
    }

    private boolean allowSystemGestureIgnoringBarVisibility() {
        return mBehavior != BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    /**
     * Checks current navigation bar mode and make transitions.
     */
    public void checkNavBarModes() {
        final boolean anim =
                mCentralSurfacesOptionalLazy.get().map(CentralSurfaces::isDeviceInteractive)
                        .orElse(false)
                && mNavigationBarWindowState != WINDOW_STATE_HIDDEN;
        getBarTransitions().transitionTo(mTransitionMode, anim);
    }

    public void disableAnimationsDuringHide(long delay) {
        mView.setLayoutTransitionsEnabled(false);
        mHandler.postDelayed(mEnableLayoutTransitions,
                delay + StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE);
    }

    /**
     * Performs transitions on navigation bar.
     *
     * @param barMode transition bar mode.
     * @param animate shows animations if {@code true}.
     */
    public void transitionTo(@TransitionMode int barMode, boolean animate) {
        getBarTransitions().transitionTo(barMode, animate);
    }

    public NavigationBarTransitions getBarTransitions() {
        return mNavigationBarTransitions;
    }

    public void finishBarAnimations() {
        getBarTransitions().finishAnimations();
    }

    private WindowManager.LayoutParams getBarLayoutParams(int rotation) {
        WindowManager.LayoutParams lp = getBarLayoutParamsForRotation(rotation);
        lp.paramsForRotation = new WindowManager.LayoutParams[4];
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            lp.paramsForRotation[rot] = getBarLayoutParamsForRotation(rot);
        }
        return lp;
    }

    private WindowManager.LayoutParams getBarLayoutParamsForRotation(int rotation) {
        int width = WindowManager.LayoutParams.MATCH_PARENT;
        int height = WindowManager.LayoutParams.MATCH_PARENT;
        int insetsHeight = -1;
        int gravity = Gravity.BOTTOM;
        boolean navBarCanMove = true;
        final Context userContext = mUserContextProvider.createCurrentUserContext(mContext);
        if (mWindowManager != null && mWindowManager.getCurrentWindowMetrics() != null) {
            Rect displaySize = mWindowManager.getCurrentWindowMetrics().getBounds();
            navBarCanMove = displaySize.width() != displaySize.height()
                    && userContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_navBarCanMove);
        }
        if (!navBarCanMove) {
            height = userContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_frame_height);
            insetsHeight = userContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_height);
        } else {
            switch (rotation) {
                case ROTATION_UNDEFINED:
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    height = userContext.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_frame_height);
                    insetsHeight = userContext.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_height);
                    break;
                case Surface.ROTATION_90:
                    gravity = Gravity.RIGHT;
                    width = userContext.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_width);
                    break;
                case Surface.ROTATION_270:
                    gravity = Gravity.LEFT;
                    width = userContext.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_width);
                    break;
            }
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.providedInsets = getInsetsFrameProvider(insetsHeight, userContext);

        lp.token = new Binder();
        lp.accessibilityTitle = userContext.getString(R.string.nav_bar);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC
                | WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("NavigationBar" + userContext.getDisplayId());
        lp.setFitInsetsTypes(0 /* types */);
        lp.setTrustedOverlay();
        return lp;
    }

    private InsetsFrameProvider[] getInsetsFrameProvider(int insetsHeight, Context userContext) {
        final InsetsFrameProvider navBarProvider =
                new InsetsFrameProvider(mInsetsSourceOwner, 0, WindowInsets.Type.navigationBars());
        if (!ENABLE_HIDE_IME_CAPTION_BAR) {
            navBarProvider.setInsetsSizeOverrides(new InsetsFrameProvider.InsetsSizeOverride[] {
                    new InsetsFrameProvider.InsetsSizeOverride(TYPE_INPUT_METHOD, null)
            });
        }
        if (insetsHeight != -1 && !mEdgeBackGestureHandler.isButtonForcedVisible()) {
            navBarProvider.setInsetsSize(Insets.of(0, 0, 0, insetsHeight));
        }
        final boolean needsScrim = userContext.getResources().getBoolean(
                com.android.internal.R.bool.config_navBarNeedsScrim);
        navBarProvider.setFlags(needsScrim ? 0 : FLAG_SUPPRESS_SCRIM, FLAG_SUPPRESS_SCRIM);

        final InsetsFrameProvider tappableElementProvider = new InsetsFrameProvider(
                mInsetsSourceOwner, 0, WindowInsets.Type.tappableElement());
        final boolean tapThrough = userContext.getResources().getBoolean(
                com.android.internal.R.bool.config_navBarTapThrough);
        if (tapThrough) {
            tappableElementProvider.setInsetsSize(Insets.NONE);
        }

        final int gestureHeight = userContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_gesture_height);
        final boolean handlingGesture = mEdgeBackGestureHandler.isHandlingGestures();
        final InsetsFrameProvider mandatoryGestureProvider = new InsetsFrameProvider(
                mInsetsSourceOwner, 0, WindowInsets.Type.mandatorySystemGestures());
        if (handlingGesture) {
            mandatoryGestureProvider.setInsetsSize(Insets.of(0, 0, 0, gestureHeight));
        }
        final int gestureInsetsLeft = handlingGesture
                ? mEdgeBackGestureHandler.getEdgeWidthLeft() : 0;
        final int gestureInsetsRight = handlingGesture
                ? mEdgeBackGestureHandler.getEdgeWidthRight() : 0;
        return new InsetsFrameProvider[] {
                navBarProvider,
                tappableElementProvider,
                mandatoryGestureProvider,
                new InsetsFrameProvider(mInsetsSourceOwner, 0, WindowInsets.Type.systemGestures())
                        .setSource(InsetsFrameProvider.SOURCE_DISPLAY)
                        .setInsetsSize(Insets.of(gestureInsetsLeft, 0, 0, 0))
                        .setMinimalInsetsSizeInDisplayCutoutSafe(
                                Insets.of(gestureInsetsLeft, 0, 0, 0)),
                new InsetsFrameProvider(mInsetsSourceOwner, 1, WindowInsets.Type.systemGestures())
                        .setSource(InsetsFrameProvider.SOURCE_DISPLAY)
                        .setInsetsSize(Insets.of(0, 0, gestureInsetsRight, 0))
                        .setMinimalInsetsSizeInDisplayCutoutSafe(
                                Insets.of(0, 0, gestureInsetsRight, 0))
        };
    }

    private boolean canShowSecondaryHandle() {
        return mNavBarMode == NAV_BAR_MODE_GESTURAL && mOrientationHandle != null;
    }

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    // The accessibility settings may be different for the new user
                    updateAccessibilityStateFlags();
                }
            };

    @VisibleForTesting
    int getNavigationIconHints() {
        return mNavigationIconHints;
    }

    private void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        if (!isLargeScreen(mContext)) {
            // All IME functions handled by launcher via Sysui flags for large screen
            final boolean newBackAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
            final boolean oldBackAlt =
                    (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
            if (newBackAlt != oldBackAlt) {
                mView.onImeVisibilityChanged(newBackAlt);
                mImeVisible = newBackAlt;
            }

            mView.setNavigationIconHints(hints);
        }
        if (DEBUG) {
            android.widget.Toast.makeText(mContext,
                    "Navigation icon hints = " + hints,
                    500).show();
        }
        mNavigationIconHints = hints;
    }

    /**
     * @param inScreenSpace Whether to return values in screen space or window space
     * @param useNearestRegion Whether to use the nearest region instead of the actual button bounds
     * @return
     */
    Region getButtonLocations(boolean inScreenSpace, boolean useNearestRegion) {
        if (useNearestRegion && !inScreenSpace) {
            // We currently don't support getting the nearest region in anything but screen space
            useNearestRegion = false;
        }
        Region region = new Region();
        Map<View, Rect> touchRegionCache = mView.getButtonTouchRegionCache();
        updateButtonLocation(
                region, touchRegionCache, mView.getBackButton(), inScreenSpace, useNearestRegion);
        updateButtonLocation(
                region, touchRegionCache, mView.getHomeButton(), inScreenSpace, useNearestRegion);
        updateButtonLocation(region, touchRegionCache, mView.getRecentsButton(), inScreenSpace,
                useNearestRegion);
        updateButtonLocation(region, touchRegionCache, mView.getImeSwitchButton(), inScreenSpace,
                useNearestRegion);
        updateButtonLocation(
                region, touchRegionCache, mView.getAccessibilityButton(), inScreenSpace,
                useNearestRegion);
        if (mView.getFloatingRotationButton().isVisible()) {
            // Note: this button is floating so the nearest region doesn't apply
            updateButtonLocation(
                    region, mView.getFloatingRotationButton().getCurrentView(), inScreenSpace);
        }
        return region;
    }

    private void updateButtonLocation(
            Region region,
            Map<View, Rect> touchRegionCache,
            ButtonDispatcher button,
            boolean inScreenSpace,
            boolean useNearestRegion) {
        if (button == null) {
            return;
        }
        View view = button.getCurrentView();
        if (view == null || !button.isVisible()) {
            return;
        }
        // If the button is tappable from perspective of NearestTouchFrame, then we'll
        // include the regions where the tap is valid instead of just the button layout location
        if (useNearestRegion && touchRegionCache.containsKey(view)) {
            region.op(touchRegionCache.get(view), Region.Op.UNION);
            return;
        }
        updateButtonLocation(region, view, inScreenSpace);
    }

    private void updateButtonLocation(Region region, View view, boolean inScreenSpace) {
        Rect bounds = new Rect();
        if (inScreenSpace) {
            view.getBoundsOnScreen(bounds);
        } else {
            int[] location = new int[2];
            view.getLocationInWindow(location);
            bounds.set(location[0], location[1],
                    location[0] + view.getWidth(),
                    location[1] + view.getHeight());
        }
        region.op(bounds, Region.Op.UNION);
    }

    void setOrientedHandleSamplingRegion(Rect orientedHandleSamplingRegion) {
        mOrientedHandleSamplingRegion = orientedHandleSamplingRegion;
        mRegionSamplingHelper.updateSamplingRect();
    }

    private Rect calculateSamplingRect() {
        mSamplingBounds.setEmpty();
        // TODO: Extend this to 2/3 button layout as well
        View view = mView.getHomeHandle().getCurrentView();

        if (view != null) {
            int[] pos = new int[2];
            view.getLocationOnScreen(pos);
            Point displaySize = new Point();
            view.getContext().getDisplay().getRealSize(displaySize);
            final Rect samplingBounds = new Rect(pos[0] - mNavColorSampleMargin,
                    displaySize.y - mView.getNavBarHeight(),
                    pos[0] + view.getWidth() + mNavColorSampleMargin,
                    displaySize.y);
            mSamplingBounds.set(samplingBounds);
        }

        return mSamplingBounds;
    }

    void setNavigationBarLumaSamplingEnabled(boolean enable) {
        if (enable) {
            mRegionSamplingHelper.start(mSamplingBounds);
        } else {
            mRegionSamplingHelper.stop();
        }
    }

    private void setNavBarMode(int mode) {
        mView.setNavBarMode(mode, mNavigationModeController.getImeDrawsImeNavBar());
        if (isGesturalMode(mode)) {
            mRegionSamplingHelper.start(mSamplingBounds);
        } else {
            mRegionSamplingHelper.stop();
        }
    }

    void onBarTransition(int newMode) {
        if (newMode == MODE_OPAQUE) {
            // If the nav bar background is opaque, stop auto tinting since we know the icons are
            // showing over a dark background
            mRegionSamplingHelper.stop();
            getBarTransitions().getLightTransitionsController().setIconsDark(
                    false /* dark */, true /* animate */);
        } else {
            mRegionSamplingHelper.start(mSamplingBounds);
        }
    }

    private final ModeChangedListener mModeChangedListener = new ModeChangedListener() {
        @Override
        public void onNavigationModeChanged(int mode) {
            mNavBarMode = mode;

            if (!QuickStepContract.isGesturalMode(mode)) {
                // Reset the override alpha
                if (getBarTransitions() != null) {
                    getBarTransitions().setBackgroundOverrideAlpha(1f);
                }
            }
            updateScreenPinningGestures();

            if (!canShowSecondaryHandle()) {
                resetSecondaryHandle();
            }
            setNavBarMode(mode);
            mView.setShouldShowSwipeUpUi(mOverviewProxyService.shouldShowSwipeUpUI());
        }
    };

    private final Gefingerpoken mTouchHandler = new Gefingerpoken() {
        private boolean mDeadZoneConsuming;

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (isGesturalMode(mNavBarMode) && mImeVisible
                    && ev.getAction() == MotionEvent.ACTION_DOWN) {
                SysUiStatsLog.write(SysUiStatsLog.IME_TOUCH_REPORTED,
                        (int) ev.getX(), (int) ev.getY());
            }
            return shouldDeadZoneConsumeTouchEvents(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            shouldDeadZoneConsumeTouchEvents(ev);
            return false;
        }

        private boolean shouldDeadZoneConsumeTouchEvents(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mDeadZoneConsuming = false;
            }
            if (mDeadZone.onTouchEvent(event) || mDeadZoneConsuming) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        // Allow gestures starting in the deadzone to be slippery
                        mView.setSlippery(true);
                        mDeadZoneConsuming = true;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        // When a gesture started in the deadzone is finished, restore
                        // slippery state
                        mView.updateSlippery();
                        mDeadZoneConsuming = false;
                        break;
                }
                return true;
            }
            return false;
        }
    };
}
