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

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowType;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.HOME_BUTTON_LONG_PRESS_DURATION_MS;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_FORCE_OPAQUE;
import static com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.StatusBar.dumpBarTransitions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IdRes;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.InsetsState.InternalInsetsType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.VisibleForTesting;

import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.navigationbar.buttons.RotationContextButton;
import com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import dagger.Lazy;

/**
 * Contains logic for a navigation bar view.
 */
public class NavigationBar implements View.OnAttachStateChangeListener,
        Callbacks, NavigationModeController.ModeChangedListener,
        AccessibilityButtonModeObserver.ModeChangedListener {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";
    private static final String EXTRA_DISABLE2_STATE = "disabled2_state";
    private static final String EXTRA_APPEARANCE = "appearance";
    private static final String EXTRA_BEHAVIOR = "behavior";
    private static final String EXTRA_TRANSIENT_STATE = "transient_state";

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;
    private static final long AUTODIM_TIMEOUT_MS = 2250;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final AccessibilityManager mAccessibilityManager;
    private final AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final StatusBarStateController mStatusBarStateController;
    private final MetricsLogger mMetricsLogger;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final SysUiState mSysUiFlagsContainer;
    private final Lazy<StatusBar> mStatusBarLazy;
    private final ShadeController mShadeController;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final OverviewProxyService mOverviewProxyService;
    private final NavigationModeController mNavigationModeController;
    private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommandQueue mCommandQueue;
    private final Optional<Pip> mPipOptional;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final Optional<Recents> mRecentsOptional;
    private final SystemActions mSystemActions;
    private final Handler mHandler;
    private final NavigationBarOverlayController mNavbarOverlayController;
    private final UiEventLogger mUiEventLogger;

    private Bundle mSavedState;
    private NavigationBarView mNavigationBarView = null;

    private @WindowVisibleState int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private @TransitionMode int mNavigationBarMode;
    private ContentResolver mContentResolver;
    private boolean mAssistantAvailable;
    private boolean mLongPressHomeEnabled;
    private boolean mAssistantTouchGestureEnabled;

    private int mDisabledFlags1;
    private int mDisabledFlags2;
    private long mLastLockToAppLongPress;

    private Locale mLocale;
    private int mLayoutDirection;

    private boolean mAllowForceNavBarHandleOpaque;
    private boolean mForceNavBarHandleOpaque;
    private Optional<Long> mHomeButtonLongPressDurationMs;
    private boolean mIsCurrentUserSetup;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int, int) */
    private @Appearance int mAppearance;

    /** @see android.view.WindowInsetsController#setSystemBarsBehavior(int) */
    private @Behavior int mBehavior;

    private boolean mTransientShown;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;
    private int mA11yBtnMode;
    private LightBarController mLightBarController;
    private AutoHideController mAutoHideController;

    @VisibleForTesting
    public int mDisplayId;
    private boolean mIsOnDefaultDisplay;
    public boolean mHomeBlockedThisTouch;

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
            return !mNotificationRemoteInputManager.getController().isRemoteInputActive();
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

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();

            // Send the assistant availability upon connection
            if (isConnected) {
                updateAssistantEntrypoints();
            }
        }

        @Override
        public void onQuickStepStarted() {
            // Use navbar dragging as a signal to hide the rotate button
            mNavigationBarView.getRotationButtonController().setRotateSuggestionButtonState(false);

            // Hide the notifications panel when quick step starts
            mShadeController.collapsePanel(true /* animate */);
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
        public void onNavBarButtonAlphaChanged(float alpha, boolean animate) {
            if (!mIsCurrentUserSetup) {
                // If the current user is not yet setup, then don't update any button alphas
                return;
            }
            if (QuickStepContract.isLegacyMode(mNavBarMode)) {
                // Don't allow the bar buttons to be affected by the alpha
                return;
            }

            ButtonDispatcher buttonDispatcher = null;
            boolean forceVisible = false;
            if (QuickStepContract.isGesturalMode(mNavBarMode)) {
                // Disallow home handle animations when in gestural
                animate = false;
                forceVisible = mAllowForceNavBarHandleOpaque && mForceNavBarHandleOpaque;
                buttonDispatcher = mNavigationBarView.getHomeHandle();
                if (getBarTransitions() != null) {
                    getBarTransitions().setBackgroundOverrideAlpha(alpha);
                }
            } else if (QuickStepContract.isSwipeUpMode(mNavBarMode)) {
                buttonDispatcher = mNavigationBarView.getBackButton();
            }
            if (buttonDispatcher != null) {
                buttonDispatcher.setVisibility(
                        (forceVisible || alpha > 0) ? View.VISIBLE : View.INVISIBLE);
                buttonDispatcher.setAlpha(forceVisible ? 1f : alpha, animate);
            }
        }

        @Override
        public void onHomeRotationEnabled(boolean enabled) {
            mNavigationBarView.getRotationButtonController().setHomeRotationEnabled(enabled);
        }

        @Override
        public void onOverviewShown(boolean fromHome) {
            // If the overview has fixed orientation that may change display to natural rotation,
            // we don't want the user rotation to be reset. So after user returns to application,
            // it can keep in the original rotation.
            mNavigationBarView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }

        @Override
        public void onToggleRecentApps() {
            // The same case as onOverviewShown but only for 3-button navigation.
            mNavigationBarView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
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

    private final Runnable mOnVariableDurationHomeLongClick = () -> {
        if (onHomeLongClick(mNavigationBarView.getHomeButton().getCurrentView())) {
            mNavigationBarView.getHomeButton().getCurrentView().performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    };

    private final ContentObserver mAssistContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateAssistantEntrypoints();
        }
    };

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (properties.getKeyset().contains(NAV_BAR_HANDLE_FORCE_OPAQUE)) {
                        mForceNavBarHandleOpaque = properties.getBoolean(
                                NAV_BAR_HANDLE_FORCE_OPAQUE, /* defaultValue = */ true);
                    }

                    if (properties.getKeyset().contains(HOME_BUTTON_LONG_PRESS_DURATION_MS)) {
                        mHomeButtonLongPressDurationMs = Optional.of(
                            properties.getLong(HOME_BUTTON_LONG_PRESS_DURATION_MS, 0)
                        ).filter(duration -> duration != 0);
                        reconfigureHomeLongClick();
                    }
                }
            };

    private final DeviceProvisionedController.DeviceProvisionedListener mUserSetupListener =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onUserSetupChanged() {
                    mIsCurrentUserSetup = mDeviceProvisionedController.isCurrentUserSetup();
                }
            };

    public NavigationBar(Context context,
            WindowManager windowManager,
            Lazy<AssistManager> assistManagerLazy,
            AccessibilityManager accessibilityManager,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            DeviceProvisionedController deviceProvisionedController,
            MetricsLogger metricsLogger,
            OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            StatusBarStateController statusBarStateController,
            SysUiState sysUiFlagsContainer,
            BroadcastDispatcher broadcastDispatcher,
            CommandQueue commandQueue,
            Optional<Pip> pipOptional,
            Optional<LegacySplitScreen> splitScreenOptional,
            Optional<Recents> recentsOptional, Lazy<StatusBar> statusBarLazy,
            ShadeController shadeController,
            NotificationRemoteInputManager notificationRemoteInputManager,
            SystemActions systemActions,
            @Main Handler mainHandler,
            NavigationBarOverlayController navbarOverlayController,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mWindowManager = windowManager;
        mAccessibilityManager = accessibilityManager;
        mAccessibilityManagerWrapper = accessibilityManagerWrapper;
        mDeviceProvisionedController = deviceProvisionedController;
        mStatusBarStateController = statusBarStateController;
        mMetricsLogger = metricsLogger;
        mAssistManagerLazy = assistManagerLazy;
        mSysUiFlagsContainer = sysUiFlagsContainer;
        mStatusBarLazy = statusBarLazy;
        mShadeController = shadeController;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mOverviewProxyService = overviewProxyService;
        mNavigationModeController = navigationModeController;
        mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;
        mBroadcastDispatcher = broadcastDispatcher;
        mCommandQueue = commandQueue;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mRecentsOptional = recentsOptional;
        mSystemActions = systemActions;
        mHandler = mainHandler;
        mNavbarOverlayController = navbarOverlayController;
        mUiEventLogger = uiEventLogger;

        mNavBarMode = mNavigationModeController.addListener(this);
        mAccessibilityButtonModeObserver.addListener(this);
        mA11yBtnMode = mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode();
    }

    public View getView() {
        return mNavigationBarView;
    }

    public View createView(Bundle savedState) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle("NavigationBar" + mContext.getDisplayId());
        lp.accessibilityTitle = mContext.getString(R.string.nav_bar);
        lp.windowAnimations = 0;
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;

        NavigationBarFrame frame = (NavigationBarFrame) LayoutInflater.from(mContext).inflate(
                R.layout.navigation_bar_window, null);
        View barView = LayoutInflater.from(frame.getContext()).inflate(
                R.layout.navigation_bar, frame);
        barView.addOnAttachStateChangeListener(this);

        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + barView);
        mContext.getSystemService(WindowManager.class).addView(frame, lp);
        mDisplayId = mContext.getDisplayId();
        mIsOnDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;

        mCommandQueue.addCallback(this);
        mAssistantAvailable = mAssistManagerLazy.get()
                .getAssistInfoForUser(UserHandle.USER_CURRENT) != null;
        mContentResolver = mContext.getContentResolver();
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSISTANT),
                false /* notifyForDescendants */, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);
        updateAssistantEntrypoints();

        if (savedState != null) {
            mDisabledFlags1 = savedState.getInt(EXTRA_DISABLE_STATE, 0);
            mDisabledFlags2 = savedState.getInt(EXTRA_DISABLE2_STATE, 0);
            mAppearance = savedState.getInt(EXTRA_APPEARANCE, 0);
            mBehavior = savedState.getInt(EXTRA_BEHAVIOR, 0);
            mTransientShown = savedState.getBoolean(EXTRA_TRANSIENT_STATE, false);
        }
        mSavedState = savedState;
        mAccessibilityManagerWrapper.addCallback(mAccessibilityListener);

        // Respect the latest disabled-flags.
        mCommandQueue.recomputeDisableFlags(mDisplayId, false);

        mAllowForceNavBarHandleOpaque = mContext.getResources().getBoolean(
                R.bool.allow_force_nav_bar_handle_opaque);
        mForceNavBarHandleOpaque = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                NAV_BAR_HANDLE_FORCE_OPAQUE,
                /* defaultValue = */ true);
        mHomeButtonLongPressDurationMs = Optional.of(DeviceConfig.getLong(
            DeviceConfig.NAMESPACE_SYSTEMUI,
            HOME_BUTTON_LONG_PRESS_DURATION_MS,
            /* defaultValue = */ 0
        )).filter(duration -> duration != 0);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, mHandler::post, mOnPropertiesChangedListener);

        mIsCurrentUserSetup = mDeviceProvisionedController.isCurrentUserSetup();
        mDeviceProvisionedController.addCallback(mUserSetupListener);

        setAccessibilityFloatingMenuModeIfNeeded();

        return barView;
    }

    public void destroyView() {
        mCommandQueue.removeCallback(this);
        mContext.getSystemService(WindowManager.class).removeViewImmediate(
                mNavigationBarView.getRootView());
        mNavigationModeController.removeListener(this);
        mAccessibilityButtonModeObserver.removeListener(this);

        mAccessibilityManagerWrapper.removeCallback(mAccessibilityListener);
        mContentResolver.unregisterContentObserver(mAssistContentObserver);
        mDeviceProvisionedController.removeCallback(mUserSetupListener);

        DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        final Display display = v.getDisplay();
        mNavigationBarView = v.findViewById(R.id.navigation_bar_view);
        mNavigationBarView.setComponents(mStatusBarLazy.get().getPanelController());
        mNavigationBarView.setDisabledFlags(mDisabledFlags1);
        mNavigationBarView.setOnVerticalChangedListener(this::onVerticalChanged);
        mNavigationBarView.setOnTouchListener(this::onNavigationTouch);
        if (mSavedState != null) {
            mNavigationBarView.getLightTransitionsController().restoreState(mSavedState);
        }
        mNavigationBarView.setNavigationIconHints(mNavigationIconHints);
        mNavigationBarView.setWindowVisible(isNavBarWindowVisible());
        mNavigationBarView.setBehavior(mBehavior);
        mSplitScreenOptional.ifPresent(mNavigationBarView::registerDockedListener);
        mPipOptional.ifPresent(mNavigationBarView::registerPipExclusionBoundsChangeListener);

        prepareNavigationBarView();
        checkNavBarModes();

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastReceiver, filter,
                Handler.getMain(), UserHandle.ALL);
        notifyNavigationBarScreenOn();

        mOverviewProxyService.addCallback(mOverviewProxyListener);
        updateSystemUiStateFlags(-1);

        // Currently there is no accelerometer sensor on non-default display.
        if (mIsOnDefaultDisplay) {
            final RotationButtonController rotationButtonController =
                    mNavigationBarView.getRotationButtonController();
            rotationButtonController.addRotationCallback(mRotationWatcher);

            // Reset user rotation pref to match that of the WindowManager if starting in locked
            // mode. This will automatically happen when switching from auto-rotate to locked mode.
            if (display != null && rotationButtonController.isRotationLocked()) {
                rotationButtonController.setRotationLockedAtAngle(display.getRotation());
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
                ? Dependency.get(LightBarController.class)
                : new LightBarController(mContext,
                        Dependency.get(DarkIconDispatcher.class),
                        Dependency.get(BatteryController.class),
                        Dependency.get(NavigationModeController.class));
        setLightBarController(lightBarController);

        // TODO(b/118592525): to support multi-display, we start to add something which is
        //                    per-display, while others may be global. I think it's time to
        //                    add a new class maybe named DisplayDependency to solve
        //                    per-display Dependency problem.
        AutoHideController autoHideController = mIsOnDefaultDisplay
                ? Dependency.get(AutoHideController.class)
                : new AutoHideController(mContext, mHandler,
                        Dependency.get(IWindowManager.class));
        setAutoHideController(autoHideController);
        restoreAppearanceAndTransientState();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().destroy();
            mNavigationBarView.getLightTransitionsController().destroy(mContext);
        }
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        if (mOrientationHandle != null) {
            resetSecondaryHandle();
            getBarTransitions().removeDarkIntensityListener(mOrientationHandleIntensityListener);
            mWindowManager.removeView(mOrientationHandle);
            mOrientationHandle.getViewTreeObserver().removeOnGlobalLayoutListener(
                    mOrientationHandleGlobalLayoutListener);
        }
        mHandler.removeCallbacks(mAutoDim);
        mNavigationBarView = null;
        mOrientationHandle = null;
    }

    // TODO: Remove this when we update nav bar recreation
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(EXTRA_DISABLE_STATE, mDisabledFlags1);
        outState.putInt(EXTRA_DISABLE2_STATE, mDisabledFlags2);
        outState.putInt(EXTRA_APPEARANCE, mAppearance);
        outState.putInt(EXTRA_BEHAVIOR, mBehavior);
        outState.putBoolean(EXTRA_TRANSIENT_STATE, mTransientShown);
        if (mNavigationBarView != null) {
            mNavigationBarView.getLightTransitionsController().saveState(outState);
        }
    }

    /**
     * Called when a non-reloading configuration change happens and we need to update.
     */
    public void onConfigurationChanged(Configuration newConfig) {
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

        repositionNavigationBar();
        if (canShowSecondaryHandle()) {
            int rotation = newConfig.windowConfiguration.getRotation();
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
        mOrientationParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
        mWindowManager.addView(mOrientationHandle, mOrientationParams);
        mOrientationHandle.setVisibility(View.GONE);
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
                    mNavigationBarView.setOrientedHandleSamplingRegion(boundsRounded);
                };
        mOrientationHandle.getViewTreeObserver().addOnGlobalLayoutListener(
                mOrientationHandleGlobalLayoutListener);
    }

    private void orientSecondaryHomeHandle() {
        if (!canShowSecondaryHandle()) {
            return;
        }

        if (mStartingQuickSwitchRotation == -1 || mSplitScreenOptional
                .map(LegacySplitScreen::isDividerVisible).orElse(false)) {
            // Hide the secondary home handle if we are in multiwindow since apps in multiwindow
            // aren't allowed to set the display orientation
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
                    width = mNavigationBarView.getHeight();
                    break;
                case Surface.ROTATION_180:
                case Surface.ROTATION_0:
                    // TODO(b/152683657): Need to determine best UX for this
                    if (!mShowOrientedHandleForImmersiveMode) {
                        resetSecondaryHandle();
                        return;
                    }
                    width = dispSize.width();
                    height = mNavigationBarView.getHeight();
                    break;
            }

            mOrientationParams.gravity =
                    deltaRotation == Surface.ROTATION_0 ? Gravity.BOTTOM :
                            (deltaRotation == Surface.ROTATION_90 ? Gravity.LEFT : Gravity.RIGHT);
            mOrientationParams.height = height;
            mOrientationParams.width = width;
            mWindowManager.updateViewLayout(mOrientationHandle, mOrientationParams);
            mNavigationBarView.setVisibility(View.GONE);
            mOrientationHandle.setVisibility(View.VISIBLE);
        }
    }

    private void resetSecondaryHandle() {
        if (mOrientationHandle != null) {
            // Case where nav mode is changed w/o ever invoking a quickstep
            // mOrientedHandle is initialized lazily
            mOrientationHandle.setVisibility(View.GONE);
        }
        if (mNavigationBarView != null) {
            mNavigationBarView.setVisibility(View.VISIBLE);
            mNavigationBarView.setOrientedHandleSamplingRegion(null);
        }
    }

    private void reconfigureHomeLongClick() {
        if (mNavigationBarView == null
                || mNavigationBarView.getHomeButton().getCurrentView() == null) {
            return;
        }
        if (mHomeButtonLongPressDurationMs.isPresent() || !mLongPressHomeEnabled) {
            mNavigationBarView.getHomeButton().getCurrentView().setLongClickable(false);
            mNavigationBarView.getHomeButton().getCurrentView().setHapticFeedbackEnabled(false);
            mNavigationBarView.getHomeButton().setOnLongClickListener(null);
        } else {
            mNavigationBarView.getHomeButton().getCurrentView().setLongClickable(true);
            mNavigationBarView.getHomeButton().getCurrentView().setHapticFeedbackEnabled(true);
            mNavigationBarView.getHomeButton().setOnLongClickListener(this::onHomeLongClick);
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
        pw.println("  mLongPressHomeEnabled=" + mLongPressHomeEnabled);
        pw.println("  mAssistantTouchGestureEnabled=" + mAssistantTouchGestureEnabled);

        if (mNavigationBarView != null) {
            pw.println("  mNavigationBarWindowState="
                    + windowStateToString(mNavigationBarWindowState));
            pw.println("  mNavigationBarMode="
                    + BarTransitions.modeToString(mNavigationBarMode));
            dumpBarTransitions(pw, "mNavigationBarView", mNavigationBarView.getBarTransitions());
            mNavigationBarView.dump(pw);
        } else {
            pw.print("  mNavigationBarView=null");
        }
    }

    // ----- CommandQueue Callbacks -----

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int hints = mNavigationIconHints;
        switch (backDisposition) {
            case InputMethodService.BACK_DISPOSITION_DEFAULT:
            case InputMethodService.BACK_DISPOSITION_WILL_NOT_DISMISS:
            case InputMethodService.BACK_DISPOSITION_WILL_DISMISS:
                if (imeShown) {
                    hints |= NAVIGATION_HINT_BACK_ALT;
                } else {
                    hints &= ~NAVIGATION_HINT_BACK_ALT;
                }
                break;
            case InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING:
                hints &= ~NAVIGATION_HINT_BACK_ALT;
                break;
        }
        if (showImeSwitcher) {
            hints |= NAVIGATION_HINT_IME_SHOWN;
        } else {
            hints &= ~NAVIGATION_HINT_IME_SHOWN;
        }
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;

        if (mNavigationBarView != null) {
            mNavigationBarView.setNavigationIconHints(hints);
        }
        checkBarModes();
        updateSystemUiStateFlags(-1);
    }

    @Override
    public void setWindowState(
            int displayId, @WindowType int window, @WindowVisibleState int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            updateSystemUiStateFlags(-1);
            mShowOrientedHandleForImmersiveMode = state == WINDOW_STATE_HIDDEN;
            if (mOrientationHandle != null
                    && mStartingQuickSwitchRotation != -1) {
                orientSecondaryHomeHandle();
            }
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));

            if (mNavigationBarView != null) {
                mNavigationBarView.setWindowVisible(isNavBarWindowVisible());
            }
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        if (mNavigationBarView == null) {
            if (RotationContextButton.DEBUG_ROTATION) {
                Log.v(TAG, "onRotationProposal proposedRotation=" +
                        Surface.rotationToString(rotation) + ", mNavigationBarView is null");
            }
            return;
        }

        final int winRotation = mNavigationBarView.getDisplay().getRotation();
        final boolean rotateSuggestionsDisabled = RotationButtonController
                .hasDisable2RotateSuggestionFlag(mDisabledFlags2);
        final RotationButtonController rotationButtonController =
                mNavigationBarView.getRotationButtonController();
        final RotationButton rotationButton = rotationButtonController.getRotationButton();

        if (RotationContextButton.DEBUG_ROTATION) {
            Log.v(TAG, "onRotationProposal proposedRotation=" + Surface.rotationToString(rotation)
                    + ", winRotation=" + Surface.rotationToString(winRotation)
                    + ", isValid=" + isValid + ", mNavBarWindowState="
                    + StatusBarManager.windowStateToString(mNavigationBarWindowState)
                    + ", rotateSuggestionsDisabled=" + rotateSuggestionsDisabled
                    + ", isRotateButtonVisible=" + (mNavigationBarView == null ? "null"
                    : rotationButton.isVisible()));
        }

        // Respect the disabled flag, no need for action as flag change callback will handle hiding
        if (rotateSuggestionsDisabled) return;

        rotationButtonController.onRotationProposal(rotation, winRotation, isValid);
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        if (running) {
            mNavbarOverlayController.setButtonState(/* visible */false, /* force */true);
        }
        mNavigationBarView.getRotationButtonController().setRecentsAnimationRunning(running);
    }

    /** Restores the appearance and the transient saved state to {@link NavigationBar}. */
    public void restoreAppearanceAndTransientState() {
        final int barMode = barMode(mTransientShown, mAppearance);
        mNavigationBarMode = barMode;
        checkNavBarModes();
        if (mAutoHideController != null) {
            mAutoHideController.touchAutoHide();
        }
        if (mLightBarController != null) {
            mLightBarController.onNavigationBarAppearanceChanged(mAppearance,
                    true /* nbModeChanged */, barMode, false /* navbarColorManagedByIme */);
        }
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, boolean isFullscreen) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean nbModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            if (getView() == null) {
                return;
            }
            nbModeChanged = updateBarMode(barMode(mTransientShown, appearance));
        }
        if (mLightBarController != null) {
            mLightBarController.onNavigationBarAppearanceChanged(appearance, nbModeChanged,
                    mNavigationBarMode, navbarColorManagedByIme);
        }
        if (mBehavior != behavior) {
            mBehavior = behavior;
            if (mNavigationBarView != null) {
                mNavigationBarView.setBehavior(behavior);
            }
            updateSystemUiStateFlags(-1);
        }
    }

    @Override
    public void showTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        if (!mTransientShown) {
            mTransientShown = true;
            handleTransientChanged();
        }
    }

    @Override
    public void abortTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        clearTransient();
    }

    private void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            handleTransientChanged();
        }
    }

    private void handleTransientChanged() {
        if (getView() == null) {
            return;
        }
        if (mNavigationBarView != null) {
            mNavigationBarView.onTransientStateChanged(mTransientShown);
        }
        final int barMode = barMode(mTransientShown, mAppearance);
        if (updateBarMode(barMode) && mLightBarController != null) {
            mLightBarController.onNavigationBarModeChanged(barMode);
        }
    }

    // Returns true if the bar mode is changed.
    private boolean updateBarMode(int barMode) {
        if (mNavigationBarMode != barMode) {
            if (mNavigationBarMode == MODE_TRANSPARENT
                    || mNavigationBarMode == MODE_LIGHTS_OUT_TRANSPARENT) {
                mNavigationBarView.hideRecentsOnboarding();
            }
            mNavigationBarMode = barMode;
            checkNavBarModes();
            if (mAutoHideController != null) {
                mAutoHideController.touchAutoHide();
            }
            return true;
        }
        return false;
    }

    private static @TransitionMode int barMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_NAVIGATION_BARS;
        if (isTransient) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
            return MODE_LIGHTS_OUT;
        } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((appearance & APPEARANCE_OPAQUE_NAVIGATION_BARS) != 0) {
            return MODE_OPAQUE;
        } else if ((appearance & APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS) != 0) {
            return MODE_SEMI_TRANSPARENT;
        } else {
            return MODE_TRANSPARENT;
        }
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
            if (mNavigationBarView != null) {
                mNavigationBarView.setDisabledFlags(state1);
            }
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
        if (mNavigationBarView != null) {
            mNavigationBarView.getRotationButtonController().onDisable2FlagChanged(state2);
        }
    }

    // ----- Internal stuff -----

    private void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }
    }

    private boolean shouldDisableNavbarGestures() {
        return !mDeviceProvisionedController.isDeviceProvisioned()
                || (mDisabledFlags1 & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private void repositionNavigationBar() {
        if (mNavigationBarView == null || !mNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout((View) mNavigationBarView.getParent(),
                ((View) mNavigationBarView.getParent()).getLayoutParams());
    }

    private void updateScreenPinningGestures() {
        if (mNavigationBarView == null) {
            return;
        }

        // Change the cancel pin gesture to home and back if recents button is invisible
        boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        if (pinningActive) {
            boolean recentsVisible = mNavigationBarView.isRecentsButtonVisible();
            backButton.setOnLongClickListener(recentsVisible
                    ? this::onLongPressBackRecents
                    : this::onLongPressBackHome);
            recentsButton.setOnLongClickListener(this::onLongPressBackRecents);
        } else {
            backButton.setOnLongClickListener(null);
            recentsButton.setOnLongClickListener(null);
        }
        // Note, this needs to be set after even if we're setting the listener to null
        backButton.setLongClickable(pinningActive);
        recentsButton.setLongClickable(pinningActive);
    }

    private void notifyNavigationBarScreenOn() {
        mNavigationBarView.updateNavButtonIcons();
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();

        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        recentsButton.setOnClickListener(this::onRecentsClick);
        recentsButton.setOnTouchListener(this::onRecentsTouch);

        ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        homeButton.setOnTouchListener(this::onHomeTouch);

        reconfigureHomeLongClick();

        ButtonDispatcher accessibilityButton = mNavigationBarView.getAccessibilityButton();
        accessibilityButton.setOnClickListener(this::onAccessibilityClick);
        accessibilityButton.setOnLongClickListener(this::onAccessibilityLongClick);
        updateAccessibilityServicesState(mAccessibilityManager);

        ButtonDispatcher imeSwitcherButton = mNavigationBarView.getImeSwitchButton();
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
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mHomeBlockedThisTouch = false;
                TelecomManager telecomManager =
                        mContext.getSystemService(TelecomManager.class);
                if (telecomManager != null && telecomManager.isRinging()) {
                    if (mStatusBarLazy.get().isKeyguardShowing()) {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call. " +
                                "No heads up");
                        mHomeBlockedThisTouch = true;
                        return true;
                    }
                }
                if (mLongPressHomeEnabled) {
                    mHomeButtonLongPressDurationMs.ifPresent(longPressDuration -> {
                        mHandler.postDelayed(mOnVariableDurationHomeLongClick, longPressDuration);
                    });
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mOnVariableDurationHomeLongClick);
                mStatusBarLazy.get().awakenDreams();
                break;
        }
        return false;
    }

    private void onVerticalChanged(boolean isVertical) {
        mStatusBarLazy.get().setQsScrimEnabled(!isVertical);
    }

    private boolean onNavigationTouch(View v, MotionEvent event) {
        if (mAutoHideController != null) {
            mAutoHideController.checkUserAutoHide(event);
        }
        return false;
    }

    @VisibleForTesting
    boolean onHomeLongClick(View v) {
        if (!mNavigationBarView.isRecentsButtonVisible()
                && ActivityManagerWrapper.getInstance().isScreenPinningActive()) {
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
        mAssistManagerLazy.get().startAssist(args);
        mStatusBarLazy.get().awakenDreams();

        if (mNavigationBarView != null) {
            mNavigationBarView.abortCurrentGesture();
        }
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
        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionStart(
                    LatencyTracker.ACTION_TOGGLE_RECENTS);
        }
        mStatusBarLazy.get().awakenDreams();
        mCommandQueue.toggleRecentApps();
    }

    private void onImeSwitcherClick(View v) {
        mContext.getSystemService(InputMethodManager.class).showInputMethodPickerFromSystem(
                true /* showAuxiliarySubtypes */, mDisplayId);
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
                    if ((time - mLastLockToAppLongPress) < LOCK_TO_APP_GESTURE_TOLERENCE) {
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId1) {
                        ButtonDispatcher button = btnId2 == R.id.recent_apps
                                ? mNavigationBarView.getRecentsButton()
                                : mNavigationBarView.getHomeButton();
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
                                ? onLongPressRecents()
                                : onHomeLongClick(
                                        mNavigationBarView.getHomeButton().getCurrentView());
                    }
                }
            } finally {
                if (stopLockTaskMode) {
                    activityManager.stopSystemLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.updateNavButtonIcons();
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

    private boolean onLongPressRecents() {
        if (mRecentsOptional.isPresent() || !ActivityTaskManager.supportsMultiWindow(mContext)
                || ActivityManager.isLowRamDeviceStatic()
                // If we are connected to the overview service, then disable the recents button
                || mOverviewProxyService.getProxy() != null
                || !mSplitScreenOptional.map(splitScreen ->
                splitScreen.getDividerView().getSnapAlgorithm().isSplitScreenFeasible())
                .orElse(false)) {
            return false;
        }

        return mStatusBarLazy.get().toggleSplitScreenMode(MetricsEvent.ACTION_WINDOW_DOCK_LONGPRESS,
                MetricsEvent.ACTION_WINDOW_UNDOCK_LONGPRESS);
    }

    private void onAccessibilityClick(View v) {
        final Display display = v.getDisplay();
        mAccessibilityManager.notifyAccessibilityButtonClicked(
                display != null ? display.getDisplayId() : DEFAULT_DISPLAY);
    }

    private boolean onAccessibilityLongClick(View v) {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final String chooserClassName = AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        return true;
    }

    void updateAccessibilityServicesState(AccessibilityManager accessibilityManager) {
        if (mNavigationBarView == null) {
            return;
        }
        boolean[] feedbackEnabled = new boolean[1];
        int a11yFlags = getA11yButtonState(feedbackEnabled);

        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;
        mNavigationBarView.setAccessibilityButtonState(clickable, longClickable);

        updateSystemUiStateFlags(a11yFlags);
    }

    private void setAccessibilityFloatingMenuModeIfNeeded() {
        if (QuickStepContract.isGesturalMode(mNavBarMode)) {
            Settings.Secure.putInt(mContentResolver, Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                    ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
        }
    }

    public void updateSystemUiStateFlags(int a11yFlags) {
        if (a11yFlags < 0) {
            a11yFlags = getA11yButtonState(null);
        }
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiFlagsContainer.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isNavBarWindowVisible())
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .commitUpdate(mDisplayId);
        registerAction(clickable, SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON);
        registerAction(longClickable, SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER);
    }

    private void registerAction(boolean register, int actionId) {
        if (register) {
            mSystemActions.register(actionId);
        } else {
            mSystemActions.unregister(actionId);
        }
    }

    /**
     * Returns the system UI flags corresponding the the current accessibility button state
     *
     * @param outFeedbackEnabled if non-null, sets it to true if accessibility feedback is enabled.
     */
    public int getA11yButtonState(@Nullable boolean[] outFeedbackEnabled) {
        boolean feedbackEnabled = false;
        // AccessibilityManagerService resolves services for the current user since the local
        // AccessibilityManager is created from a Context with the INTERACT_ACROSS_USERS permission
        final List<AccessibilityServiceInfo> services =
                mAccessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        final List<String> a11yButtonTargets =
                mAccessibilityManager.getAccessibilityShortcutTargets(
                        AccessibilityManager.ACCESSIBILITY_BUTTON);
        final int requestingServices = a11yButtonTargets.size();
        for (int i = services.size() - 1; i >= 0; --i) {
            AccessibilityServiceInfo info = services.get(i);
            if (info.feedbackType != 0 && info.feedbackType !=
                    AccessibilityServiceInfo.FEEDBACK_GENERIC) {
                feedbackEnabled = true;
            }
        }

        if (outFeedbackEnabled != null) {
            outFeedbackEnabled[0] = feedbackEnabled;
        }

        // If accessibility button is floating menu mode, click and long click state should be
        // disabled.
        if (mA11yBtnMode == ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU) {
            return 0;
        }

        return (requestingServices >= 1 ? SYSUI_STATE_A11Y_BUTTON_CLICKABLE : 0)
                | (requestingServices >= 2 ? SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE : 0);
    }

    private void updateAssistantEntrypoints() {
        mAssistantAvailable = mAssistManagerLazy.get()
                .getAssistInfoForUser(UserHandle.USER_CURRENT) != null;
        mLongPressHomeEnabled = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED, 1) != 0;
        mAssistantTouchGestureEnabled = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED, 1) != 0;
        if (mOverviewProxyService.getProxy() != null) {
            try {
                mOverviewProxyService.getProxy().onAssistantAvailable(mAssistantAvailable
                        && mAssistantTouchGestureEnabled
                        && QuickStepContract.isGesturalMode(mNavBarMode));
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
                    mNavigationBarView.getLightTransitionsController());
        }
    }

    /** Sets {@link AutoHideController} to the navigation bar. */
    public void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
        if (mAutoHideController != null) {
            mAutoHideController.setNavigationBar(mAutoHideUiElement);
        }
        mNavigationBarView.setAutoHideController(autoHideController);
    }

    private boolean isTransientShown() {
        return mTransientShown;
    }

    private void checkBarModes() {
        // We only have status bar on default display now.
        if (mIsOnDefaultDisplay) {
            mStatusBarLazy.get().checkBarModes();
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
        final boolean anim = mStatusBarLazy.get().isDeviceInteractive()
                && mNavigationBarWindowState != WINDOW_STATE_HIDDEN;
        mNavigationBarView.getBarTransitions().transitionTo(mNavigationBarMode, anim);
    }

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
        setAccessibilityFloatingMenuModeIfNeeded();

        if (!canShowSecondaryHandle()) {
            resetSecondaryHandle();
        }
    }

    @Override
    public void onAccessibilityButtonModeChanged(int mode) {
        mA11yBtnMode = mode;
        updateAccessibilityServicesState(mAccessibilityManager);
    }

    public void disableAnimationsDuringHide(long delay) {
        mNavigationBarView.setLayoutTransitionsEnabled(false);
        mNavigationBarView.postDelayed(() -> mNavigationBarView.setLayoutTransitionsEnabled(true),
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
        if (mNavigationBarView != null) {
            return mNavigationBarView.getBarTransitions();
        }
        return null;
    }

    public void finishBarAnimations() {
        mNavigationBarView.getBarTransitions().finishAnimations();
    }

    private final AccessibilityServicesStateChangeListener mAccessibilityListener =
            this::updateAccessibilityServicesState;

    private boolean canShowSecondaryHandle() {
        return mNavBarMode == NAV_BAR_MODE_GESTURAL && mOrientationHandle != null;
    }

    private final Consumer<Integer> mRotationWatcher = rotation -> {
        if (mNavigationBarView != null
                && mNavigationBarView.needsReorient(rotation)) {
            repositionNavigationBar();
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mNavigationBarView == null) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)) {
                notifyNavigationBarScreenOn();
                mNavigationBarView.onScreenStateChanged(Intent.ACTION_SCREEN_ON.equals(action));
            }
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                // The accessibility settings may be different for the new user
                updateAccessibilityServicesState(mAccessibilityManager);
            }
        }
    };

    @VisibleForTesting
    int getNavigationIconHints() {
        return mNavigationIconHints;
    }
}
