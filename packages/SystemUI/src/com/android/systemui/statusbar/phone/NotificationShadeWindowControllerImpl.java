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

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_BEHAVIOR_CONTROLLED;

import static com.android.systemui.DejankUtils.whitelistIpcs;
import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;

import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import com.google.android.collect.Lists;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Encapsulates all logic for the notification shade window state management.
 */
@SysUISingleton
public class NotificationShadeWindowControllerImpl implements NotificationShadeWindowController,
        Dumpable, ConfigurationListener {

    private static final String TAG = "NotificationShadeWindowController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IActivityManager mActivityManager;
    private final DozeParameters mDozeParameters;
    private final LayoutParams mLpChanged;
    private final boolean mKeyguardScreenRotation;
    private final long mLockScreenDisplayTimeout;
    private final float mKeyguardRefreshRate;
    private final KeyguardViewMediator mKeyguardViewMediator;
    private final KeyguardBypassController mKeyguardBypassController;
    private ViewGroup mNotificationShadeView;
    private LayoutParams mLp;
    private boolean mHasTopUi;
    private boolean mHasTopUiChanged;
    private float mScreenBrightnessDoze;
    private final State mCurrentState = new State();
    private OtherwisedCollapsedListener mListener;
    private ForcePluginOpenListener mForcePluginOpenListener;
    private Consumer<Integer> mScrimsVisibilityListener;
    private final ArrayList<WeakReference<StatusBarWindowCallback>>
            mCallbacks = Lists.newArrayList();

    private final SysuiColorExtractor mColorExtractor;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private float mFaceAuthDisplayBrightness = LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    @Inject
    public NotificationShadeWindowControllerImpl(Context context, WindowManager windowManager,
            IActivityManager activityManager, DozeParameters dozeParameters,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            KeyguardViewMediator keyguardViewMediator,
            KeyguardBypassController keyguardBypassController,
            SysuiColorExtractor colorExtractor,
            DumpManager dumpManager,
            KeyguardStateController keyguardStateController,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController) {
        mContext = context;
        mWindowManager = windowManager;
        mActivityManager = activityManager;
        mKeyguardScreenRotation = keyguardStateController.isKeyguardScreenRotationAllowed();
        mDozeParameters = dozeParameters;
        mScreenBrightnessDoze = mDozeParameters.getScreenBrightnessDoze();
        mLpChanged = new LayoutParams();
        mKeyguardViewMediator = keyguardViewMediator;
        mKeyguardBypassController = keyguardBypassController;
        mColorExtractor = colorExtractor;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        dumpManager.registerDumpable(getClass().getName(), this);

        mLockScreenDisplayTimeout = context.getResources()
                .getInteger(R.integer.config_lockScreenDisplayTimeout);
        ((SysuiStatusBarStateController) statusBarStateController)
                .addCallback(mStateListener,
                        SysuiStatusBarStateController.RANK_STATUS_BAR_WINDOW_CONTROLLER);
        configurationController.addCallback(this);

        Display.Mode[] supportedModes = context.getDisplay().getSupportedModes();
        Display.Mode currentMode = context.getDisplay().getMode();
        // Running on the highest frame rate available can be expensive.
        // Let's specify a preferred refresh rate, and allow higher FPS only when we
        // know that we're not falsing (because we unlocked.)
        mKeyguardRefreshRate = context.getResources()
                .getInteger(R.integer.config_keyguardRefreshRate);
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
        mCallbacks.add(new WeakReference<StatusBarWindowCallback>(callback));
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
        mLp.softInputMode = LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mLp.setTitle("NotificationShade");
        mLp.packageName = mContext.getPackageName();
        mLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        // We use BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE here, however, there is special logic in
        // window manager which disables the transient show behavior.
        // TODO: Clean this up once that behavior moves into the Shell.
        mLp.privateFlags |= PRIVATE_FLAG_BEHAVIOR_CONTROLLED;
        mLp.insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

        mWindowManager.addView(mNotificationShadeView, mLp);
        mLpChanged.copyFrom(mLp);
        onThemeChanged();

        // Make the state consistent with KeyguardViewMediator#setupLocked during initialization.
        if (mKeyguardViewMediator.isShowingAndNotOccluded()) {
            setKeyguardShowing(true);
        }
    }

    @Override
    public void setNotificationShadeView(ViewGroup view) {
        mNotificationShadeView = view;
    }

    @Override
    public ViewGroup getNotificationShadeView() {
        return mNotificationShadeView;
    }

    @Override
    public void setDozeScreenBrightness(int value) {
        mScreenBrightnessDoze = value / 255f;
    }

    @Override
    public void setFaceAuthDisplayBrightness(float brightness) {
        mFaceAuthDisplayBrightness = brightness;
        apply(mCurrentState);
    }

    private void setKeyguardDark(boolean dark) {
        int vis = mNotificationShadeView.getSystemUiVisibility();
        if (dark) {
            vis = vis | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis = vis | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            vis = vis & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis = vis & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        mNotificationShadeView.setSystemUiVisibility(vis);
    }

    private void applyKeyguardFlags(State state) {
        final boolean scrimsOccludingWallpaper =
                state.mScrimsVisibility == ScrimController.OPAQUE;
        final boolean keyguardOrAod = state.mKeyguardShowing
                || (state.mDozing && mDozeParameters.getAlwaysOn());
        if ((keyguardOrAod && !state.mBackdropShowing && !scrimsOccludingWallpaper)
                || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe()) {
            // Show the wallpaper if we're on keyguard/AOD and the wallpaper is not occluded by a
            // solid backdrop or scrim. Also, show it if we are currently animating between the
            // keyguard and the surface behind the keyguard - we want to use the wallpaper as a
            // backdrop for this animation.
            mLpChanged.flags |= LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_SHOW_WALLPAPER;
        }

        if (state.mDozing) {
            mLpChanged.privateFlags |= LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        }

        if (mKeyguardRefreshRate > 0) {
            boolean bypassOnKeyguard = mKeyguardBypassController.getBypassEnabled()
                    && state.mStatusBarState == StatusBarState.KEYGUARD
                    && !state.mKeyguardFadingAway && !state.mKeyguardGoingAway;
            if (state.mDozing || bypassOnKeyguard) {
                mLpChanged.preferredMaxDisplayRefreshRate = mKeyguardRefreshRate;
            } else {
                mLpChanged.preferredMaxDisplayRefreshRate = 0;
            }
            Trace.setCounter("display_max_refresh_rate",
                    (long) mLpChanged.preferredMaxDisplayRefreshRate);
        }
    }

    private void adjustScreenOrientation(State state) {
        if (state.isKeyguardShowingAndNotOccluded() || state.mDozing) {
            if (mKeyguardScreenRotation) {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
            } else {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
            }
        } else {
            mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private void applyFocusableFlag(State state) {
        boolean panelFocusable = state.mNotificationShadeFocusable && state.mPanelExpanded;
        if (state.mBouncerShowing && (state.mKeyguardOccluded || state.mKeyguardNeedsInput)
                || ENABLE_REMOTE_INPUT && state.mRemoteInputActive
                // Make the panel focusable if we're doing the screen off animation, since the light
                // reveal scrim is drawing in the panel and should consume touch events so that they
                // don't go to the app behind.
                || mUnlockedScreenOffAnimationController.isScreenOffAnimationPlaying()) {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else if (state.isKeyguardShowingAndNotOccluded() || panelFocusable) {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
            // Make sure to remove FLAG_ALT_FOCUSABLE_IM when keyguard needs input.
            if (state.mKeyguardNeedsInput && state.isKeyguardShowingAndNotOccluded()) {
                mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                mLpChanged.flags |= LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
        } else {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }

        mLpChanged.softInputMode = LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private void applyForceShowNavigationFlag(State state) {
        if (state.mPanelExpanded || state.mBouncerShowing
                || ENABLE_REMOTE_INPUT && state.mRemoteInputActive) {
            mLpChanged.privateFlags |= LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        }
    }

    private void applyVisibility(State state) {
        boolean visible = isExpanded(state);
        if (state.mForcePluginOpen) {
            if (mListener != null) {
                mListener.setWouldOtherwiseCollapse(visible);
            }
            visible = true;
        }
        if (visible) {
            mNotificationShadeView.setVisibility(View.VISIBLE);
        } else {
            mNotificationShadeView.setVisibility(View.INVISIBLE);
        }
    }

    private boolean isExpanded(State state) {
        return !state.mForceCollapsed && (state.isKeyguardShowingAndNotOccluded()
                || state.mPanelVisible || state.mKeyguardFadingAway || state.mBouncerShowing
                || state.mHeadsUpShowing
                || state.mScrimsVisibility != ScrimController.TRANSPARENT)
                || state.mBackgroundBlurRadius > 0
                || state.mLaunchingActivity;
    }

    private void applyFitsSystemWindows(State state) {
        boolean fitsSystemWindows = !state.isKeyguardShowingAndNotOccluded();
        if (mNotificationShadeView != null
                && mNotificationShadeView.getFitsSystemWindows() != fitsSystemWindows) {
            mNotificationShadeView.setFitsSystemWindows(fitsSystemWindows);
            mNotificationShadeView.requestApplyInsets();
        }
    }

    private void applyUserActivityTimeout(State state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.mStatusBarState == StatusBarState.KEYGUARD
                && !state.mQsExpanded) {
            mLpChanged.userActivityTimeout = state.mBouncerShowing
                    ? KeyguardViewMediator.AWAKE_INTERVAL_BOUNCER_MS : mLockScreenDisplayTimeout;
        } else {
            mLpChanged.userActivityTimeout = -1;
        }
    }

    private void applyInputFeatures(State state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.mStatusBarState == StatusBarState.KEYGUARD
                && !state.mQsExpanded && !state.mForceUserActivity) {
            mLpChanged.inputFeatures |=
                    LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        } else {
            mLpChanged.inputFeatures &=
                    ~LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        }
    }

    private void applyStatusBarColorSpaceAgnosticFlag(State state) {
        if (!isExpanded(state)) {
            mLpChanged.privateFlags |= LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        } else {
            mLpChanged.privateFlags &=
                    ~LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        }
    }

    private void apply(State state) {
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
        if (mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mNotificationShadeView, mLp);
        }
        if (mHasTopUi != mHasTopUiChanged) {
            whitelistIpcs(() -> {
                try {
                    mActivityManager.setHasTopUi(mHasTopUiChanged);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call setHasTopUi", e);
                }
                mHasTopUi = mHasTopUiChanged;
            });
        }
        notifyStateChangedCallbacks();
    }

    @Override
    public void notifyStateChangedCallbacks() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            StatusBarWindowCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStateChanged(mCurrentState.mKeyguardShowing,
                        mCurrentState.mKeyguardOccluded,
                        mCurrentState.mBouncerShowing,
                        mCurrentState.mDozing);
            }
        }
    }

    private void applyModalFlag(State state) {
        if (state.mHeadsUpShowing) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
    }

    private void applyBrightness(State state) {
        if (state.mForceDozeBrightness) {
            mLpChanged.screenBrightness = mScreenBrightnessDoze;
        } else {
            mLpChanged.screenBrightness = mFaceAuthDisplayBrightness;
        }
    }

    private void applyHasTopUi(State state) {
        mHasTopUiChanged = !state.mComponentsForcingTopUi.isEmpty() || isExpanded(state);
    }

    private void applyNotTouchable(State state) {
        if (state.mNotTouchable) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    @Override
    public void setKeyguardShowing(boolean showing) {
        mCurrentState.mKeyguardShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setKeyguardOccluded(boolean occluded) {
        mCurrentState.mKeyguardOccluded = occluded;
        apply(mCurrentState);
    }

    @Override
    public void setKeyguardNeedsInput(boolean needsInput) {
        mCurrentState.mKeyguardNeedsInput = needsInput;
        apply(mCurrentState);
    }

    @Override
    public void setPanelVisible(boolean visible) {
        mCurrentState.mPanelVisible = visible;
        mCurrentState.mNotificationShadeFocusable = visible;
        apply(mCurrentState);
    }

    @Override
    public void setNotificationShadeFocusable(boolean focusable) {
        mCurrentState.mNotificationShadeFocusable = focusable;
        apply(mCurrentState);
    }

    @Override
    public void setBouncerShowing(boolean showing) {
        mCurrentState.mBouncerShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setBackdropShowing(boolean showing) {
        mCurrentState.mBackdropShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setKeyguardFadingAway(boolean keyguardFadingAway) {
        mCurrentState.mKeyguardFadingAway = keyguardFadingAway;
        apply(mCurrentState);
    }

    @Override
    public void setQsExpanded(boolean expanded) {
        mCurrentState.mQsExpanded = expanded;
        apply(mCurrentState);
    }

    @Override
    public void setForceUserActivity(boolean forceUserActivity) {
        mCurrentState.mForceUserActivity = forceUserActivity;
        apply(mCurrentState);
    }

    @Override
    public void setLaunchingActivity(boolean launching) {
        mCurrentState.mLaunchingActivity = launching;
        apply(mCurrentState);
    }

    @Override
    public boolean isLaunchingActivity() {
        return mCurrentState.mLaunchingActivity;
    }

    @Override
    public void setScrimsVisibility(int scrimsVisibility) {
        mCurrentState.mScrimsVisibility = scrimsVisibility;
        apply(mCurrentState);
        mScrimsVisibilityListener.accept(scrimsVisibility);
    }

    /**
     * Current blur level, controller by
     * {@link com.android.systemui.statusbar.NotificationShadeDepthController}.
     * @param backgroundBlurRadius Radius in pixels.
     */
    @Override
    public void setBackgroundBlurRadius(int backgroundBlurRadius) {
        if (mCurrentState.mBackgroundBlurRadius == backgroundBlurRadius) {
            return;
        }
        mCurrentState.mBackgroundBlurRadius = backgroundBlurRadius;
        apply(mCurrentState);
    }

    @Override
    public void setHeadsUpShowing(boolean showing) {
        mCurrentState.mHeadsUpShowing = showing;
        apply(mCurrentState);
    }

    @Override
    public void setWallpaperSupportsAmbientMode(boolean supportsAmbientMode) {
        mCurrentState.mWallpaperSupportsAmbientMode = supportsAmbientMode;
        apply(mCurrentState);
    }

    /**
     * @param state The {@link StatusBarStateController} of the status bar.
     */
    private void setStatusBarState(int state) {
        mCurrentState.mStatusBarState = state;
        apply(mCurrentState);
    }

    /**
     * Force the window to be collapsed, even if it should theoretically be expanded.
     * Used for when a heads-up comes in but we still need to wait for the touchable regions to
     * be computed.
     */
    @Override
    public void setForceWindowCollapsed(boolean force) {
        mCurrentState.mForceCollapsed = force;
        apply(mCurrentState);
    }

    @Override
    public void setPanelExpanded(boolean isExpanded) {
        mCurrentState.mPanelExpanded = isExpanded;
        apply(mCurrentState);
    }

    @Override
    public void onRemoteInputActive(boolean remoteInputActive) {
        mCurrentState.mRemoteInputActive = remoteInputActive;
        apply(mCurrentState);
    }

    /**
     * Set whether the screen brightness is forced to the value we use for doze mode by the status
     * bar window.
     */
    @Override
    public void setForceDozeBrightness(boolean forceDozeBrightness) {
        mCurrentState.mForceDozeBrightness = forceDozeBrightness;
        apply(mCurrentState);
    }

    @Override
    public void setDozing(boolean dozing) {
        mCurrentState.mDozing = dozing;
        apply(mCurrentState);
    }

    private final Set<Object> mForceOpenTokens = new HashSet<>();
    @Override
    public void setForcePluginOpen(boolean forceOpen, Object token) {
        if (forceOpen) {
            mForceOpenTokens.add(token);
        } else {
            mForceOpenTokens.remove(token);
        }
        final boolean previousForceOpenState = mCurrentState.mForcePluginOpen;
        mCurrentState.mForcePluginOpen = !mForceOpenTokens.isEmpty();
        if (previousForceOpenState != mCurrentState.mForcePluginOpen) {
            apply(mCurrentState);
            if (mForcePluginOpenListener != null) {
                mForcePluginOpenListener.onChange(mCurrentState.mForcePluginOpen);
            }
        }
    }

    /**
     * The forcePluginOpen state for the status bar.
     */
    @Override
    public boolean getForcePluginOpen() {
        return mCurrentState.mForcePluginOpen;
    }

    @Override
    public void setNotTouchable(boolean notTouchable) {
        mCurrentState.mNotTouchable = notTouchable;
        apply(mCurrentState);
    }

    /**
     * Whether the status bar panel is expanded or not.
     */
    @Override
    public boolean getPanelExpanded() {
        return mCurrentState.mPanelExpanded;
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG + ":");
        pw.println("  mKeyguardRefreshRate=" + mKeyguardRefreshRate);
        pw.println(mCurrentState);
        if (mNotificationShadeView != null && mNotificationShadeView.getViewRootImpl() != null) {
            mNotificationShadeView.getViewRootImpl().dump("  ", pw);
        }
    }

    @Override
    public boolean isShowingWallpaper() {
        return !mCurrentState.mBackdropShowing;
    }

    @Override
    public void onThemeChanged() {
        if (mNotificationShadeView == null) {
            return;
        }

        final boolean useDarkText = mColorExtractor.getNeutralColors().supportsDarkText();
        // Make sure we have the correct navbar/statusbar colors.
        setKeyguardDark(useDarkText);
    }

    /**
     * When keyguard will be dismissed but didn't start animation yet.
     */
    @Override
    public void setKeyguardGoingAway(boolean goingAway) {
        mCurrentState.mKeyguardGoingAway = goingAway;
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
            mCurrentState.mComponentsForcingTopUi.add(componentTag);
        } else {
            mCurrentState.mComponentsForcingTopUi.remove(componentTag);
        }
        apply(mCurrentState);
    }

    private static class State {
        boolean mKeyguardShowing;
        boolean mKeyguardOccluded;
        boolean mKeyguardNeedsInput;
        boolean mPanelVisible;
        boolean mPanelExpanded;
        boolean mNotificationShadeFocusable;
        boolean mBouncerShowing;
        boolean mKeyguardFadingAway;
        boolean mKeyguardGoingAway;
        boolean mQsExpanded;
        boolean mHeadsUpShowing;
        boolean mForceCollapsed;
        boolean mForceDozeBrightness;
        int mFaceAuthDisplayBrightness;
        boolean mForceUserActivity;
        boolean mLaunchingActivity;
        boolean mBackdropShowing;
        boolean mWallpaperSupportsAmbientMode;
        boolean mNotTouchable;
        Set<String> mComponentsForcingTopUi = new HashSet<>();

        /**
         * The {@link StatusBar} state from the status bar.
         */
        int mStatusBarState;

        boolean mRemoteInputActive;
        boolean mForcePluginOpen;
        boolean mDozing;
        int mScrimsVisibility;
        int mBackgroundBlurRadius;

        private boolean isKeyguardShowingAndNotOccluded() {
            return mKeyguardShowing && !mKeyguardOccluded;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            String newLine = "\n";
            result.append("Window State {");
            result.append(newLine);

            Field[] fields = this.getClass().getDeclaredFields();

            // Print field names paired with their values
            for (Field field : fields) {
                result.append("  ");
                try {
                    result.append(field.getName());
                    result.append(": ");
                    //requires access to private field:
                    result.append(field.get(this));
                } catch (IllegalAccessException ex) {
                }
                result.append(newLine);
            }
            result.append("}");

            return result.toString();
        }
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
    };
}
