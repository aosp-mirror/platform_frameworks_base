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
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemProperties;
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
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.RemoteInputController.Callback;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import com.google.android.collect.Lists;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Encapsulates all logic for the notification shade window state management.
 */
@Singleton
public class NotificationShadeWindowController implements Callback, Dumpable,
        ConfigurationListener {

    private static final String TAG = "NotificationShadeWindowController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IActivityManager mActivityManager;
    private final DozeParameters mDozeParameters;
    private final LayoutParams mLpChanged;
    private final boolean mKeyguardScreenRotation;
    private final long mLockScreenDisplayTimeout;
    private final Display.Mode mKeyguardDisplayMode;
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

    @Inject
    public NotificationShadeWindowController(Context context, WindowManager windowManager,
            IActivityManager activityManager, DozeParameters dozeParameters,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            KeyguardBypassController keyguardBypassController, SysuiColorExtractor colorExtractor,
            DumpManager dumpManager) {
        mContext = context;
        mWindowManager = windowManager;
        mActivityManager = activityManager;
        mKeyguardScreenRotation = shouldEnableKeyguardScreenRotation();
        mDozeParameters = dozeParameters;
        mScreenBrightnessDoze = mDozeParameters.getScreenBrightnessDoze();
        mLpChanged = new LayoutParams();
        mKeyguardBypassController = keyguardBypassController;
        mColorExtractor = colorExtractor;
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
        int keyguardRefreshRate = context.getResources()
                .getInteger(R.integer.config_keyguardRefreshRate);
        // Find supported display mode with the same resolution and requested refresh rate.
        mKeyguardDisplayMode = Arrays.stream(supportedModes).filter(mode ->
                (int) mode.getRefreshRate() == keyguardRefreshRate
                        && mode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                        && mode.getPhysicalHeight() == currentMode.getPhysicalHeight())
                .findFirst().orElse(null);
    }

    /**
     * Register to receive notifications about status bar window state changes.
     */
    public void registerCallback(StatusBarWindowCallback callback) {
        // Prevent adding duplicate callbacks
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                return;
            }
        }
        mCallbacks.add(new WeakReference<StatusBarWindowCallback>(callback));
    }

    /**
     * Register a listener to monitor scrims visibility
     * @param listener A listener to monitor scrims visibility
     */
    public void setScrimsVisibilityListener(Consumer<Integer> listener) {
        if (listener != null && mScrimsVisibilityListener != listener) {
            mScrimsVisibilityListener = listener;
        }
    }

    private boolean shouldEnableKeyguardScreenRotation() {
        Resources res = mContext.getResources();
        return SystemProperties.getBoolean("lockscreen.rot_override", false)
                || res.getBoolean(R.bool.config_enableLockScreenRotation);
    }

    /**
     * Adds the notification shade view to the window manager.
     */
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
    }

    public void setNotificationShadeView(ViewGroup view) {
        mNotificationShadeView = view;
    }

    public ViewGroup getNotificationShadeView() {
        return mNotificationShadeView;
    }

    public void setDozeScreenBrightness(int value) {
        mScreenBrightnessDoze = value / 255f;
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
        if (keyguardOrAod && !state.mBackdropShowing && !scrimsOccludingWallpaper) {
            mLpChanged.flags |= LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_SHOW_WALLPAPER;
        }

        if (state.mDozing) {
            mLpChanged.privateFlags |= LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        }

        if (mKeyguardDisplayMode != null) {
            boolean bypassOnKeyguard = mKeyguardBypassController.getBypassEnabled()
                    && state.mStatusBarState == StatusBarState.KEYGUARD
                    && !state.mKeyguardFadingAway && !state.mKeyguardGoingAway;
            if (state.mDozing || bypassOnKeyguard) {
                mLpChanged.preferredDisplayModeId = mKeyguardDisplayMode.getModeId();
            } else {
                mLpChanged.preferredDisplayModeId = 0;
            }
            Trace.setCounter("display_mode_id", mLpChanged.preferredDisplayModeId);
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
                || ENABLE_REMOTE_INPUT && state.mRemoteInputActive) {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else if (state.isKeyguardShowingAndNotOccluded() || panelFocusable) {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
            // Make sure to remove FLAG_ALT_FOCUSABLE_IM when keyguard needs input.
            if (state.mKeyguardNeedsInput) {
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
                || state.mBackgroundBlurRadius > 0;
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

    public void notifyStateChangedCallbacks() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            StatusBarWindowCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStateChanged(mCurrentState.mKeyguardShowing,
                        mCurrentState.mKeyguardOccluded,
                        mCurrentState.mBouncerShowing);
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
            mLpChanged.screenBrightness = LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
    }

    private void applyHasTopUi(State state) {
        mHasTopUiChanged = state.mForceHasTopUi || isExpanded(state);
    }

    private void applyNotTouchable(State state) {
        if (state.mNotTouchable) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    public void setKeyguardShowing(boolean showing) {
        mCurrentState.mKeyguardShowing = showing;
        apply(mCurrentState);
    }

    public void setKeyguardOccluded(boolean occluded) {
        mCurrentState.mKeyguardOccluded = occluded;
        apply(mCurrentState);
    }

    public void setKeyguardNeedsInput(boolean needsInput) {
        mCurrentState.mKeyguardNeedsInput = needsInput;
        apply(mCurrentState);
    }

    public void setPanelVisible(boolean visible) {
        mCurrentState.mPanelVisible = visible;
        mCurrentState.mNotificationShadeFocusable = visible;
        apply(mCurrentState);
    }

    public void setNotificationShadeFocusable(boolean focusable) {
        mCurrentState.mNotificationShadeFocusable = focusable;
        apply(mCurrentState);
    }

    public void setBouncerShowing(boolean showing) {
        mCurrentState.mBouncerShowing = showing;
        apply(mCurrentState);
    }

    public void setBackdropShowing(boolean showing) {
        mCurrentState.mBackdropShowing = showing;
        apply(mCurrentState);
    }

    public void setKeyguardFadingAway(boolean keyguardFadingAway) {
        mCurrentState.mKeyguardFadingAway = keyguardFadingAway;
        apply(mCurrentState);
    }

    public void setQsExpanded(boolean expanded) {
        mCurrentState.mQsExpanded = expanded;
        apply(mCurrentState);
    }

    public void setForceUserActivity(boolean forceUserActivity) {
        mCurrentState.mForceUserActivity = forceUserActivity;
        apply(mCurrentState);
    }

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
    public void setBackgroundBlurRadius(int backgroundBlurRadius) {
        if (mCurrentState.mBackgroundBlurRadius == backgroundBlurRadius) {
            return;
        }
        mCurrentState.mBackgroundBlurRadius = backgroundBlurRadius;
        apply(mCurrentState);
    }

    public void setHeadsUpShowing(boolean showing) {
        mCurrentState.mHeadsUpShowing = showing;
        apply(mCurrentState);
    }

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
    public void setForceWindowCollapsed(boolean force) {
        mCurrentState.mForceCollapsed = force;
        apply(mCurrentState);
    }

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
    public void setForceDozeBrightness(boolean forceDozeBrightness) {
        mCurrentState.mForceDozeBrightness = forceDozeBrightness;
        apply(mCurrentState);
    }

    public void setDozing(boolean dozing) {
        mCurrentState.mDozing = dozing;
        apply(mCurrentState);
    }

    public void setForcePluginOpen(boolean forcePluginOpen) {
        mCurrentState.mForcePluginOpen = forcePluginOpen;
        apply(mCurrentState);
        if (mForcePluginOpenListener != null) {
            mForcePluginOpenListener.onChange(forcePluginOpen);
        }
    }

    /**
     * The forcePluginOpen state for the status bar.
     */
    public boolean getForcePluginOpen() {
        return mCurrentState.mForcePluginOpen;
    }

    public void setNotTouchable(boolean notTouchable) {
        mCurrentState.mNotTouchable = notTouchable;
        apply(mCurrentState);
    }

    /**
     * Whether the status bar panel is expanded or not.
     */
    public boolean getPanelExpanded() {
        return mCurrentState.mPanelExpanded;
    }

    public void setStateListener(OtherwisedCollapsedListener listener) {
        mListener = listener;
    }

    public void setForcePluginOpenListener(ForcePluginOpenListener listener) {
        mForcePluginOpenListener = listener;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG + ":");
        pw.println("  mKeyguardDisplayMode=" + mKeyguardDisplayMode);
        pw.println(mCurrentState);
    }

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
    public void setKeyguardGoingAway(boolean goingAway) {
        mCurrentState.mKeyguardGoingAway = goingAway;
        apply(mCurrentState);
    }

    public boolean getForceHasTopUi() {
        return mCurrentState.mForceHasTopUi;
    }

    public void setForceHasTopUi(boolean forceHasTopUi) {
        mCurrentState.mForceHasTopUi = forceHasTopUi;
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
        boolean mForceUserActivity;
        boolean mBackdropShowing;
        boolean mWallpaperSupportsAmbientMode;
        boolean mNotTouchable;
        boolean mForceHasTopUi;

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

    /**
     * Custom listener to pipe data back to plugins about whether or not the status bar would be
     * collapsed if not for the plugin.
     * TODO: Find cleaner way to do this.
     */
    public interface OtherwisedCollapsedListener {
        void setWouldOtherwiseCollapse(boolean otherwiseCollapse);
    }

    /**
     * Listener to indicate forcePluginOpen has changed
     */
    public interface ForcePluginOpenListener {
        /**
         * Called when mState.forcePluginOpen is changed
         */
        void onChange(boolean forceOpen);
    }
}
