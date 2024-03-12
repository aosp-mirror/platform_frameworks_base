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

import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_BEHAVIOR_CONTROLLED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_OPTIMIZE_MEASURE;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.DumpsysTableLogger;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlags;
import com.android.systemui.scene.ui.view.WindowRootViewComponent;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import dagger.Lazy;

import java.io.PrintWriter;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Encapsulates all logic for the notification shade window state management.
 */
@SysUISingleton
public class NotificationShadeWindowControllerImpl implements NotificationShadeWindowController,
        Dumpable, ConfigurationListener {

    private static final String TAG = "NotificationShadeWindowController";
    private static final int MAX_STATE_CHANGES_BUFFER_SIZE = 100;

    private final Context mContext;
    private final WindowRootViewComponent.Factory mWindowRootViewComponentFactory;
    private final WindowManager mWindowManager;
    private final IActivityManager mActivityManager;
    private final DozeParameters mDozeParameters;
    private final KeyguardStateController mKeyguardStateController;
    private final ShadeWindowLogger mLogger;
    private final LayoutParams mLpChanged;
    private final long mLockScreenDisplayTimeout;
    private final float mKeyguardPreferredRefreshRate; // takes precedence over max
    private final float mKeyguardMaxRefreshRate;
    private final KeyguardViewMediator mKeyguardViewMediator;
    private final KeyguardBypassController mKeyguardBypassController;
    private final Executor mBackgroundExecutor;
    private final AuthController mAuthController;
    private final Lazy<SelectedUserInteractor> mUserInteractor;
    private final Lazy<ShadeInteractor> mShadeInteractorLazy;
    private final SceneContainerFlags mSceneContainerFlags;
    private ViewGroup mWindowRootView;
    private LayoutParams mLp;
    private boolean mHasTopUi;
    private boolean mHasTopUiChanged;
    private float mScreenBrightnessDoze;
    private final NotificationShadeWindowState mCurrentState = new NotificationShadeWindowState();
    private OtherwisedCollapsedListener mListener;
    private ForcePluginOpenListener mForcePluginOpenListener;
    private Consumer<Integer> mScrimsVisibilityListener;
    private final ArrayList<WeakReference<StatusBarWindowCallback>>
            mCallbacks = new ArrayList<>();

    private final SysuiColorExtractor mColorExtractor;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    /**
     * Layout params would be aggregated and dispatched all at once if this is > 0.
     *
     * @see #batchApplyWindowLayoutParams(Runnable)
     */
    private int mDeferWindowLayoutParams;
    private boolean mLastKeyguardRotationAllowed;

    private final NotificationShadeWindowState.Buffer mStateBuffer =
            new NotificationShadeWindowState.Buffer(MAX_STATE_CHANGES_BUFFER_SIZE);

    @Inject
    public NotificationShadeWindowControllerImpl(
            Context context,
            WindowRootViewComponent.Factory windowRootViewComponentFactory,
            WindowManager windowManager,
            IActivityManager activityManager,
            DozeParameters dozeParameters,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            KeyguardViewMediator keyguardViewMediator,
            KeyguardBypassController keyguardBypassController,
            @Main Executor mainExecutor,
            @Background Executor backgroundExecutor,
            SysuiColorExtractor colorExtractor,
            DumpManager dumpManager,
            KeyguardStateController keyguardStateController,
            ScreenOffAnimationController screenOffAnimationController,
            AuthController authController,
            Lazy<ShadeInteractor> shadeInteractorLazy,
            ShadeWindowLogger logger,
            Lazy<SelectedUserInteractor> userInteractor,
            UserTracker userTracker,
            SceneContainerFlags sceneContainerFlags) {
        mContext = context;
        mWindowRootViewComponentFactory = windowRootViewComponentFactory;
        mWindowManager = windowManager;
        mActivityManager = activityManager;
        mDozeParameters = dozeParameters;
        mKeyguardStateController = keyguardStateController;
        mLogger = logger;
        mScreenBrightnessDoze = mDozeParameters.getScreenBrightnessDoze();
        mLpChanged = new LayoutParams();
        mKeyguardViewMediator = keyguardViewMediator;
        mKeyguardBypassController = keyguardBypassController;
        mBackgroundExecutor = backgroundExecutor;
        mColorExtractor = colorExtractor;
        mScreenOffAnimationController = screenOffAnimationController;
        dumpManager.registerDumpable(this);
        mAuthController = authController;
        mUserInteractor = userInteractor;
        mSceneContainerFlags = sceneContainerFlags;
        mLastKeyguardRotationAllowed = mKeyguardStateController.isKeyguardScreenRotationAllowed();
        mLockScreenDisplayTimeout = context.getResources()
                .getInteger(R.integer.config_lockScreenDisplayTimeout);
        mShadeInteractorLazy = shadeInteractorLazy;
        ((SysuiStatusBarStateController) statusBarStateController)
                .addCallback(mStateListener,
                        SysuiStatusBarStateController.RANK_STATUS_BAR_WINDOW_CONTROLLER);
        configurationController.addCallback(this);
        if (android.multiuser.Flags.useAllCpusDuringUserSwitch()) {
            userTracker.addCallback(mUserTrackerCallback, mainExecutor);
        }
        float desiredPreferredRefreshRate = context.getResources()
                .getInteger(R.integer.config_keyguardRefreshRate);
        float actualPreferredRefreshRate = -1;
        if (desiredPreferredRefreshRate > -1) {
            for (Display.Mode displayMode : context.getDisplay().getSupportedModes()) {
                if (Math.abs(displayMode.getRefreshRate() - desiredPreferredRefreshRate) <= .1) {
                    actualPreferredRefreshRate = displayMode.getRefreshRate();
                    break;
                }
            }
        }

        mKeyguardPreferredRefreshRate = actualPreferredRefreshRate;

        // Running on the highest frame rate available can be expensive.
        // Let's specify a preferred refresh rate, and allow higher FPS only when we
        // know that we're not falsing (because we unlocked.)
        mKeyguardMaxRefreshRate = context.getResources()
                .getInteger(R.integer.config_keyguardMaxRefreshRate);
    }

    /**
     * Register to receive notifications about status bar window state changes.
     */
    @Override
    public void registerCallback(StatusBarWindowCallback callback) {
        // Prevent adding duplicate callbacks
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                return;
            }
        }
        mCallbacks.add(new WeakReference<>(callback));
    }

    @Override
    public void unregisterCallback(StatusBarWindowCallback callback) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                mCallbacks.remove(i);
                return;
            }
        }
    }

    @VisibleForTesting
    void onShadeOrQsExpanded(Boolean isExpanded) {
        if (mCurrentState.shadeOrQsExpanded != isExpanded) {
            mCurrentState.shadeOrQsExpanded = isExpanded;
            apply(mCurrentState);
        }
    }

    /**
     * Register a listener to monitor scrims visibility
     * @param listener A listener to monitor scrims visibility
     */
    @Override
    public void setScrimsVisibilityListener(Consumer<Integer> listener) {
        if (listener != null && mScrimsVisibilityListener != listener) {
            mScrimsVisibilityListener = listener;
        }
    }

    /**
     * Adds the notification shade view to the window manager.
     */
    @Override
    public void attach() {
        // Now that the notification shade encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        mLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_NOTIFICATION_SHADE,
                LayoutParams.FLAG_NOT_FOCUSABLE
                        | LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | LayoutParams.FLAG_SPLIT_TOUCH
                        | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        mLp.token = new Binder();
        mLp.gravity = Gravity.TOP;
        mLp.setFitInsetsTypes(0 /* types */);
        mLp.setTitle("NotificationShade");
        mLp.packageName = mContext.getPackageName();
        mLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mLp.privateFlags |= PRIVATE_FLAG_OPTIMIZE_MEASURE;

        // We use BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE here, however, there is special logic in
        // window manager which disables the transient show behavior.
        // TODO: Clean this up once that behavior moves into the Shell.
        mLp.privateFlags |= PRIVATE_FLAG_BEHAVIOR_CONTROLLED;
        mLp.insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

        if (mSceneContainerFlags.isEnabled()) {
            // This prevents the appearance and disappearance of the software keyboard (also known
            // as the "IME") from scrolling/panning the window to make room for the keyboard.
            //
            // The scene container logic does its own adjustment and animation when the IME appears
            // or disappears.
            mLp.softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        }

        mWindowManager.addView(mWindowRootView, mLp);

        mLpChanged.copyFrom(mLp);
        onThemeChanged();

        // Make the state consistent with KeyguardViewMediator#setupLocked during initialization.
        if (mKeyguardViewMediator.isShowingAndNotOccluded()) {
            setKeyguardShowing(true);
        }
    }

    @Override
    public void fetchWindowRootView() {
        WindowRootViewComponent component = mWindowRootViewComponentFactory.create();
        mWindowRootView = component.getWindowRootView();
        collectFlow(
                mWindowRootView,
                mShadeInteractorLazy.get().isAnyExpanded(),
                this::onShadeOrQsExpanded
        );
        collectFlow(
                mWindowRootView,
                mShadeInteractorLazy.get().isQsExpanded(),
                this::onQsExpansionChanged
        );
    }

    @Override
    public ViewGroup getWindowRootView() {
        return mWindowRootView;
    }

    @Override
    public void setDozeScreenBrightness(int value) {
        mScreenBrightnessDoze = value / 255f;
    }

    private void setKeyguardDark(boolean dark) {
        int vis = mWindowRootView.getSystemUiVisibility();
        if (dark) {
            vis = vis | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis = vis | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            vis = vis & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis = vis & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        mWindowRootView.setSystemUiVisibility(vis);
    }

    private void applyKeyguardFlags(NotificationShadeWindowState state) {
        final boolean keyguardOrAod = state.keyguardShowing
                || (state.dozing && mDozeParameters.getAlwaysOn());
        if ((keyguardOrAod && !state.mediaBackdropShowing && !state.lightRevealScrimOpaque)
                || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehind()) {
            // Show the wallpaper if we're on keyguard/AOD and the wallpaper is not occluded by a
            // solid backdrop. Also, show it if we are currently animating between the
            // keyguard and the surface behind the keyguard - we want to use the wallpaper as a
            // backdrop for this animation.
            mLpChanged.flags |= LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_SHOW_WALLPAPER;
        }

        if (state.dozing) {
            mLpChanged.privateFlags |= LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        }

        if (mKeyguardPreferredRefreshRate > 0) {
            boolean onKeyguard = state.statusBarState == StatusBarState.KEYGUARD
                    && !state.keyguardFadingAway && !state.keyguardGoingAway;
            if (onKeyguard
                    && mAuthController.isUdfpsEnrolled(mUserInteractor.get().getSelectedUserId())) {
                // Requests the max refresh rate (ie: for smooth display). Note: By setting
                // the preferred refresh rates below, the refresh rate will not override the max
                // refresh rate in settings (ie: if smooth display is OFF).
                // Both max and min display refresh rate must be set to take effect:
                mLpChanged.preferredMaxDisplayRefreshRate = mKeyguardPreferredRefreshRate;
                mLpChanged.preferredMinDisplayRefreshRate = mKeyguardPreferredRefreshRate;
            } else {
                mLpChanged.preferredMaxDisplayRefreshRate = 0;
                mLpChanged.preferredMinDisplayRefreshRate = 0;
            }
            Trace.setCounter("display_set_preferred_refresh_rate",
                    (long) mLpChanged.preferredMaxDisplayRefreshRate);
        } else if (mKeyguardMaxRefreshRate > 0) {
            boolean bypassOnKeyguard = mKeyguardBypassController.getBypassEnabled()
                    && state.statusBarState == StatusBarState.KEYGUARD
                    && !state.keyguardFadingAway && !state.keyguardGoingAway;
            if (state.dozing || bypassOnKeyguard) {
                mLpChanged.preferredMaxDisplayRefreshRate = mKeyguardMaxRefreshRate;
            } else {
                mLpChanged.preferredMaxDisplayRefreshRate = 0;
            }
            Trace.setCounter("display_max_refresh_rate",
                    (long) mLpChanged.preferredMaxDisplayRefreshRate);
        }

        if (state.bouncerShowing && !isDebuggable()) {
            mLpChanged.flags |= LayoutParams.FLAG_SECURE;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_SECURE;
        }
    }

    protected boolean isDebuggable() {
        return Build.IS_DEBUGGABLE;
    }

    private void adjustScreenOrientation(NotificationShadeWindowState state) {
        if (state.bouncerShowing || state.isKeyguardShowingAndNotOccluded() || state.dozing) {
            if (mKeyguardStateController.isKeyguardScreenRotationAllowed()) {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
            } else {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
            }
        } else {
            mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private void applyFocusableFlag(NotificationShadeWindowState state) {
        boolean panelFocusable = state.notificationShadeFocusable && state.shadeOrQsExpanded;
        if (state.bouncerShowing && (state.keyguardOccluded || state.keyguardNeedsInput)
                || ENABLE_REMOTE_INPUT && state.remoteInputActive
                // Make the panel focusable if we're doing the screen off animation, since the light
                // reveal scrim is drawing in the panel and should consume touch events so that they
                // don't go to the app behind.
                || mScreenOffAnimationController.shouldIgnoreKeyguardTouches()) {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else if (state.isKeyguardShowingAndNotOccluded() || panelFocusable) {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
            // Make sure to remove FLAG_ALT_FOCUSABLE_IM when keyguard needs input.
            if (state.keyguardNeedsInput && state.isKeyguardShowingAndNotOccluded()) {
                mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                mLpChanged.flags |= LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
        } else {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
    }

    private void applyForceShowNavigationFlag(NotificationShadeWindowState state) {
        if (state.shadeOrQsExpanded || state.bouncerShowing
                || ENABLE_REMOTE_INPUT && state.remoteInputActive) {
            mLpChanged.forciblyShownTypes |= WindowInsets.Type.navigationBars();
        } else {
            mLpChanged.forciblyShownTypes &= ~WindowInsets.Type.navigationBars();
        }
    }

    private void applyVisibility(NotificationShadeWindowState state) {
        boolean visible = isExpanded(state);
        mLogger.logApplyVisibility(visible);
        if (state.forcePluginOpen) {
            if (mListener != null) {
                mListener.setWouldOtherwiseCollapse(visible);
            }
            visible = true;
            mLogger.d("Visibility forced to be true");
        }
        if (mWindowRootView != null) {
            if (visible) {
                mWindowRootView.setVisibility(View.VISIBLE);
            } else {
                mWindowRootView.setVisibility(View.INVISIBLE);
            }
        }
    }

    private boolean isExpanded(NotificationShadeWindowState state) {
        boolean isExpanded = !state.forceWindowCollapsed && (state.isKeyguardShowingAndNotOccluded()
                || state.panelVisible || state.keyguardFadingAway || state.bouncerShowing
                || state.headsUpNotificationShowing
                || state.scrimsVisibility != ScrimController.TRANSPARENT)
                || state.backgroundBlurRadius > 0
                || state.launchingActivityFromNotification;
        mLogger.logIsExpanded(isExpanded, state.forceWindowCollapsed,
                state.isKeyguardShowingAndNotOccluded(), state.panelVisible,
                state.keyguardFadingAway, state.bouncerShowing, state.headsUpNotificationShowing,
                state.scrimsVisibility != ScrimController.TRANSPARENT,
                state.backgroundBlurRadius > 0, state.launchingActivityFromNotification);
        return isExpanded;
    }

    private void applyFitsSystemWindows(NotificationShadeWindowState state) {
        boolean fitsSystemWindows = !state.isKeyguardShowingAndNotOccluded();
        if (mWindowRootView != null
                && mWindowRootView.getFitsSystemWindows() != fitsSystemWindows) {
            mWindowRootView.setFitsSystemWindows(fitsSystemWindows);
            mWindowRootView.requestApplyInsets();
        }
    }

    private void applyUserActivityTimeout(NotificationShadeWindowState state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.statusBarState == StatusBarState.KEYGUARD
                && !state.qsExpanded) {
            mLpChanged.userActivityTimeout = state.bouncerShowing
                    ? KeyguardViewMediator.AWAKE_INTERVAL_BOUNCER_MS : mLockScreenDisplayTimeout;
        } else {
            mLpChanged.userActivityTimeout = -1;
        }
    }

    private void applyInputFeatures(NotificationShadeWindowState state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.statusBarState == StatusBarState.KEYGUARD
                && !state.qsExpanded && !state.forceUserActivity) {
            mLpChanged.inputFeatures |=
                    LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        } else {
            mLpChanged.inputFeatures &=
                    ~LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        }
    }

    private void applyStatusBarColorSpaceAgnosticFlag(NotificationShadeWindowState state) {
        if (!isExpanded(state)) {
            mLpChanged.privateFlags |= LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        } else {
            mLpChanged.privateFlags &=
                    ~LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        }
    }

    private void applyWindowLayoutParams() {
        if (mDeferWindowLayoutParams == 0 && mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            Trace.beginSection("updateViewLayout");
            mWindowManager.updateViewLayout(mWindowRootView, mLp);
            Trace.endSection();
        }
    }

    @Override
    public void batchApplyWindowLayoutParams(Runnable scope) {
        mDeferWindowLayoutParams++;
        scope.run();
        mDeferWindowLayoutParams--;
        applyWindowLayoutParams();
    }

    private void apply(NotificationShadeWindowState state) {
        logState(state);
        applyKeyguardFlags(state);
        applyFocusableFlag(state);
        applyForceShowNavigationFlag(state);
        adjustScreenOrientation(state);
        applyVisibility(state);
        applyUserActivityTimeout(state);
        applyInputFeatures(state);
        applyFitsSystemWindows(state);
        applyModalFlag(state);
        applyBrightness(state);
        applyHasTopUi(state);
        applyNotTouchable(state);
        applyStatusBarColorSpaceAgnosticFlag(state);
        applyWindowLayoutParams();

        if (mHasTopUi != mHasTopUiChanged) {
            mHasTopUi = mHasTopUiChanged;
            mBackgroundExecutor.execute(() -> {
                try {
                    mActivityManager.setHasTopUi(mHasTopUiChanged);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call setHasTopUi", e);
                }

            });
        }
        notifyStateChangedCallbacks();
    }

    private void logState(NotificationShadeWindowState state) {
        mStateBuffer.insert(
                state.keyguardShowing,
                state.keyguardOccluded,
                state.keyguardNeedsInput,
                state.panelVisible,
                state.shadeOrQsExpanded,
                state.notificationShadeFocusable,
                state.bouncerShowing,
                state.keyguardFadingAway,
                state.keyguardGoingAway,
                state.qsExpanded,
                state.headsUpNotificationShowing,
                state.lightRevealScrimOpaque,
                state.isSwitchingUsers,
                state.forceWindowCollapsed,
                state.forceDozeBrightness,
                state.forceUserActivity,
                state.launchingActivityFromNotification,
                state.mediaBackdropShowing,
                state.windowNotTouchable,
                state.componentsForcingTopUi,
                state.forceOpenTokens,
                state.statusBarState,
                state.remoteInputActive,
                state.forcePluginOpen,
                state.dozing,
                state.scrimsVisibility,
                state.backgroundBlurRadius
        );
    }

    @Override
    public void notifyStateChangedCallbacks() {
        // Copy callbacks to separate ArrayList to avoid concurrent modification
        List<StatusBarWindowCallback> activeCallbacks = mCallbacks.stream()
                .map(Reference::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        for (StatusBarWindowCallback cb : activeCallbacks) {
            cb.onStateChanged(mCurrentState.keyguardShowing,
                    mCurrentState.keyguardOccluded,
                    mCurrentState.keyguardGoingAway,
                    mCurrentState.bouncerShowing,
                    mCurrentState.dozing,
                    mCurrentState.shadeOrQsExpanded,
                    mCurrentState.dreaming);
        }
    }

    private void applyModalFlag(NotificationShadeWindowState state) {
        if (state.headsUpNotificationShowing) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
    }

    private void applyBrightness(NotificationShadeWindowState state) {
        if (state.forceDozeBrightness) {
            mLpChanged.screenBrightness = mScreenBrightnessDoze;
        } else {
            mLpChanged.screenBrightness = LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
    }

    private void applyHasTopUi(NotificationShadeWindowState state) {
        mHasTopUiChanged = !state.componentsForcingTopUi.isEmpty() || isExpanded(state)
                || state.isSwitchingUsers;
    }

    private void applyNotTouchable(NotificationShadeWindowState state) {
        if (state.windowNotTouchable) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    @Override
    public void setTouchExclusionRegion(Region region) {
        try {
            final IWindowSession session = WindowManagerGlobal.getWindowSession();
            session.updateTapExcludeRegion(
                    IWindow.Stub.asInterface(getWindowRootView().getWindowToken()),
                    region);
        } catch (RemoteException e) {
            Log.e(TAG, "could not update the tap exclusion region:" + e);
        }
    }


    @Override
    public void setKeyguardShowing(boolean showing) {
        mCurrentState.keyguardShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setKeyguardOccluded(boolean occluded) {
        mCurrentState.keyguardOccluded = occluded;
        apply(mCurrentState);
    }

    @Override
    public void setKeyguardNeedsInput(boolean needsInput) {
        mCurrentState.keyguardNeedsInput = needsInput;
        apply(mCurrentState);
    }

    @Override
    public void setPanelVisible(boolean visible) {
        if (mCurrentState.panelVisible == visible
                && mCurrentState.notificationShadeFocusable == visible) {
            return;
        }
        mLogger.logShadeVisibleAndFocusable(visible);
        mCurrentState.panelVisible = visible;
        mCurrentState.notificationShadeFocusable = visible;
        apply(mCurrentState);
    }

    @Override
    public void setNotificationShadeFocusable(boolean focusable) {
        mLogger.logShadeFocusable(focusable);
        mCurrentState.notificationShadeFocusable = focusable;
        apply(mCurrentState);
    }

    @Override
    public void setBouncerShowing(boolean showing) {
        mCurrentState.bouncerShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setBackdropShowing(boolean showing) {
        mCurrentState.mediaBackdropShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setKeyguardFadingAway(boolean keyguardFadingAway) {
        mCurrentState.keyguardFadingAway = keyguardFadingAway;
        apply(mCurrentState);
    }

    private void onQsExpansionChanged(Boolean expanded) {
        mCurrentState.qsExpanded = expanded;
        apply(mCurrentState);
    }

    @Override
    public void setForceUserActivity(boolean forceUserActivity) {
        mCurrentState.forceUserActivity = forceUserActivity;
        apply(mCurrentState);
    }

    @Override
    public void setLaunchingActivity(boolean launching) {
        mCurrentState.launchingActivityFromNotification = launching;
        apply(mCurrentState);
    }

    @Override
    public boolean isLaunchingActivity() {
        return mCurrentState.launchingActivityFromNotification;
    }

    @Override
    public void setScrimsVisibility(int scrimsVisibility) {
        if (scrimsVisibility == mCurrentState.scrimsVisibility) {
            return;
        }
        boolean wasExpanded = isExpanded(mCurrentState);
        mCurrentState.scrimsVisibility = scrimsVisibility;
        if (wasExpanded != isExpanded(mCurrentState)) {
            apply(mCurrentState);
        }
        mScrimsVisibilityListener.accept(scrimsVisibility);
    }

    /**
     * Current blur level, controller by
     * {@link com.android.systemui.statusbar.NotificationShadeDepthController}.
     * @param backgroundBlurRadius Radius in pixels.
     */
    @Override
    public void setBackgroundBlurRadius(int backgroundBlurRadius) {
        if (mCurrentState.backgroundBlurRadius == backgroundBlurRadius) {
            return;
        }
        mCurrentState.backgroundBlurRadius = backgroundBlurRadius;
        apply(mCurrentState);
    }

    @Override
    public void setHeadsUpShowing(boolean showing) {
        mCurrentState.headsUpNotificationShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setLightRevealScrimOpaque(boolean opaque) {
        if (mCurrentState.lightRevealScrimOpaque == opaque) {
            return;
        }
        mCurrentState.lightRevealScrimOpaque = opaque;
        apply(mCurrentState);
    }

    /**
     * @param state The {@link StatusBarStateController} of the status bar.
     */
    private void setStatusBarState(int state) {
        mCurrentState.statusBarState = state;
        apply(mCurrentState);
    }

    /**
     * Force the window to be collapsed, even if it should theoretically be expanded.
     * Used for when a heads-up comes in but we still need to wait for the touchable regions to
     * be computed.
     */
    @Override
    public void setForceWindowCollapsed(boolean force) {
        mCurrentState.forceWindowCollapsed = force;
        apply(mCurrentState);
    }

    @Override
    public void onRemoteInputActive(boolean remoteInputActive) {
        mCurrentState.remoteInputActive = remoteInputActive;
        apply(mCurrentState);
    }

    /**
     * Set whether the screen brightness is forced to the value we use for doze mode by the status
     * bar window.
     */
    @Override
    public void setForceDozeBrightness(boolean forceDozeBrightness) {
        if (mCurrentState.forceDozeBrightness == forceDozeBrightness) {
            return;
        }
        mCurrentState.forceDozeBrightness = forceDozeBrightness;
        apply(mCurrentState);
    }

    @Override
    public void setDozing(boolean dozing) {
        mCurrentState.dozing = dozing;
        apply(mCurrentState);
    }

    @Override
    public void setDreaming(boolean dreaming) {
        mCurrentState.dreaming = dreaming;
        apply(mCurrentState);
    }

    @Override
    public void setForcePluginOpen(boolean forceOpen, Object token) {
        if (forceOpen) {
            mCurrentState.forceOpenTokens.add(token);
        } else {
            mCurrentState.forceOpenTokens.remove(token);
        }
        final boolean previousForceOpenState = mCurrentState.forcePluginOpen;
        mCurrentState.forcePluginOpen = !mCurrentState.forceOpenTokens.isEmpty();
        if (previousForceOpenState != mCurrentState.forcePluginOpen) {
            apply(mCurrentState);
            if (mForcePluginOpenListener != null) {
                mForcePluginOpenListener.onChange(mCurrentState.forcePluginOpen);
            }
        }
    }

    /**
     * The forcePluginOpen state for the status bar.
     */
    @Override
    public boolean getForcePluginOpen() {
        return mCurrentState.forcePluginOpen;
    }

    @Override
    public void setNotTouchable(boolean notTouchable) {
        mCurrentState.windowNotTouchable = notTouchable;
        apply(mCurrentState);
    }

    /**
     * Whether the status bar panel is expanded or not.
     */
    @Override
    public boolean getPanelExpanded() {
        return mCurrentState.shadeOrQsExpanded;
    }

    @Override
    public void setStateListener(OtherwisedCollapsedListener listener) {
        mListener = listener;
    }

    @Override
    public void setForcePluginOpenListener(ForcePluginOpenListener listener) {
        mForcePluginOpenListener = listener;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG + ":");
        pw.println("  mKeyguardMaxRefreshRate=" + mKeyguardMaxRefreshRate);
        pw.println("  mKeyguardPreferredRefreshRate=" + mKeyguardPreferredRefreshRate);
        pw.println("  mDeferWindowLayoutParams=" + mDeferWindowLayoutParams);
        pw.println(mCurrentState);
        if (mWindowRootView != null && mWindowRootView.getViewRootImpl() != null) {
            Trace.beginSection("mWindowRootView.dump()");
            mWindowRootView.getViewRootImpl().dump("  ", pw);
            Trace.endSection();
        }
        Trace.beginSection("Table<State>");
        new DumpsysTableLogger(
                TAG,
                NotificationShadeWindowState.TABLE_HEADERS,
                mStateBuffer.toList()
        ).printTableData(pw);
        Trace.endSection();
    }

    @Override
    public boolean isShowingWallpaper() {
        return !mCurrentState.mediaBackdropShowing;
    }

    @Override
    public void onThemeChanged() {
        if (mWindowRootView == null) {
            return;
        }

        final boolean useDarkText = mColorExtractor.getNeutralColors().supportsDarkText();
        // Make sure we have the correct navbar/statusbar colors.
        setKeyguardDark(useDarkText);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        final boolean newScreenRotationAllowed = mKeyguardStateController
                .isKeyguardScreenRotationAllowed();

        if (mLastKeyguardRotationAllowed != newScreenRotationAllowed) {
            apply(mCurrentState);
            mLastKeyguardRotationAllowed = newScreenRotationAllowed;
        }
    }

    /**
     * When keyguard will be dismissed but didn't start animation yet.
     */
    @Override
    public void setKeyguardGoingAway(boolean goingAway) {
        mCurrentState.keyguardGoingAway = goingAway;
        apply(mCurrentState);
    }

    /**
     * SystemUI may need top-ui to avoid jank when performing animations.  After the
     * animation is performed, the component should remove itself from the list of features that
     * are forcing SystemUI to be top-ui.
     */
    @Override
    public void setRequestTopUi(boolean requestTopUi, String componentTag) {
        if (requestTopUi) {
            mCurrentState.componentsForcingTopUi.add(componentTag);
        } else {
            mCurrentState.componentsForcingTopUi.remove(componentTag);
        }
        apply(mCurrentState);
    }

    private final StateListener mStateListener = new StateListener() {
        @Override
        public void onStateChanged(int newState) {
            setStatusBarState(newState);
        }

        @Override
        public void onDozingChanged(boolean isDozing) {
            setDozing(isDozing);
        }

        @Override
        public void onDreamingChanged(boolean isDreaming) {
            setDreaming(isDreaming);
        }
    };

    private final UserTracker.Callback mUserTrackerCallback = new UserTracker.Callback() {
        @Override
        public void onBeforeUserSwitching(int newUser) {
            setIsSwitchingUsers(true);
        }

        @Override
        public void onUserChanged(int newUser, Context userContext) {
            setIsSwitchingUsers(false);
        }

        private void setIsSwitchingUsers(boolean isSwitchingUsers) {
            if (mCurrentState.isSwitchingUsers == isSwitchingUsers) {
                return;
            }
            mCurrentState.isSwitchingUsers = isSwitchingUsers;
            apply(mCurrentState);
        }
    };
}
