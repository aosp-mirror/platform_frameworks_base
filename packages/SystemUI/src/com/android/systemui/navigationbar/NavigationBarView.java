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

import static android.inputmethodservice.InputMethodService.canImeRenderGesturalNavButtons;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SEARCH_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.isGesturalMode;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.Utils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.ContextualButton;
import com.android.systemui.navigationbar.buttons.ContextualButtonGroup;
import com.android.systemui.navigationbar.buttons.DeadZone;
import com.android.systemui.navigationbar.buttons.KeyButtonDrawable;
import com.android.systemui.navigationbar.buttons.NearestTouchFrame;
import com.android.systemui.navigationbar.buttons.RotationContextButton;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.recents.Recents;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shared.rotation.FloatingRotationButton;
import com.android.systemui.shared.rotation.RotationButton.RotationButtonUpdatesCallback;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** */
public class NavigationBarView extends FrameLayout {
    final static boolean DEBUG = false;
    final static String TAG = "NavBarView";

    final static boolean ALTERNATE_CAR_MODE_UI = false;

    private Executor mBgExecutor;

    // The current view is one of mHorizontal or mVertical depending on the current configuration
    View mCurrentView = null;
    private View mVertical;
    private View mHorizontal;

    /** Indicates that navigation bar is vertical. */
    private boolean mIsVertical;
    private int mCurrentRotation = -1;

    boolean mLongClickableAccessibilityButton;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;
    private int mNavBarMode;
    private boolean mImeDrawsImeNavBar;

    private KeyButtonDrawable mBackIcon;
    private KeyButtonDrawable mHomeDefaultIcon;
    private KeyButtonDrawable mRecentIcon;
    private KeyButtonDrawable mDockedIcon;
    private Context mLightContext;
    private int mLightIconColor;
    private int mDarkIconColor;

    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    private final DeadZone mDeadZone;
    private NavigationBarTransitions mBarTransitions;
    @Nullable
    private AutoHideController mAutoHideController;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private OnVerticalChangedListener mOnVerticalChangedListener;
    private boolean mLayoutTransitionsEnabled = true;
    private boolean mWakeAndUnlocking;
    private boolean mUseCarModeUi = false;
    private boolean mInCarMode = false;
    private boolean mDockedStackExists;
    private boolean mScreenOn = true;

    private final SparseArray<ButtonDispatcher> mButtonDispatchers = new SparseArray<>();
    private final ContextualButtonGroup mContextualButtonGroup;
    private Configuration mConfiguration;
    private Configuration mTmpLastConfiguration;

    private NavigationBarInflaterView mNavigationInflaterView;
    private Optional<Recents> mRecentsOptional = Optional.empty();
    private NotificationPanelViewController mPanelView;
    private RotationContextButton mRotationContextButton;
    private FloatingRotationButton mFloatingRotationButton;
    private RotationButtonController mRotationButtonController;

    /**
     * Helper that is responsible for showing the right toast when a disallowed activity operation
     * occurred. In pinned mode, we show instructions on how to break out of this mode, whilst in
     * fully locked mode we only show that unlocking is blocked.
     */
    private ScreenPinningNotify mScreenPinningNotify;

    /**
     * {@code true} if the IME can render the back button and the IME switcher button.
     *
     * <p>The value must be used when and only when
     * {@link com.android.systemui.shared.system.QuickStepContract#isGesturalMode(int)} returns
     * {@code true}</p>
     *
     * <p>Cache the value here for better performance.</p>
     */
    private final boolean mImeCanRenderGesturalNavButtons = canImeRenderGesturalNavButtons();
    private Gefingerpoken mTouchHandler;
    private boolean mOverviewProxyEnabled;
    private boolean mShowSwipeUpUi;
    private UpdateActiveTouchRegionsCallback mUpdateActiveTouchRegionsCallback;

    private class NavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mHomeAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = true;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = false;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = false;
            }
        }

        public void onBackAltCleared() {
            ButtonDispatcher backButton = getBackButton();

            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            if (!mBackTransitioning && backButton.getVisibility() == VISIBLE
                    && mHomeAppearing && getHomeButton().getAlpha() == 0) {
                getBackButton().setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(backButton, "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    private final AccessibilityDelegate mQuickStepAccessibilityDelegate =
            new AccessibilityDelegate() {
                private AccessibilityAction mToggleOverviewAction;

                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    if (mToggleOverviewAction == null) {
                        mToggleOverviewAction = new AccessibilityAction(
                                R.id.action_toggle_overview, getContext().getString(
                                R.string.quick_step_accessibility_toggle_overview));
                    }
                    info.addAction(mToggleOverviewAction);
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.action_toggle_overview) {
                        mRecentsOptional.ifPresent(Recents::toggleRecentApps);
                    } else {
                        return super.performAccessibilityAction(host, action, args);
                    }
                    return true;
                }
            };

    private final RotationButtonUpdatesCallback mRotationButtonListener =
            new RotationButtonUpdatesCallback() {
                @Override
                public void onVisibilityChanged(boolean visible) {
                    if (visible && mAutoHideController != null) {
                        // If the button will actually become visible and the navbar is about
                        // to hide, tell the statusbar to keep it around for longer
                        mAutoHideController.touchAutoHide();
                    }
                    notifyActiveTouchRegions();
                }

                @Override
                public void onPositionChanged() {
                    notifyActiveTouchRegions();
                }
            };

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Context darkContext = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.darkIconTheme));
        mLightContext = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.lightIconTheme));
        mLightIconColor = Utils.getColorAttrDefaultColor(mLightContext, R.attr.singleToneColor);
        mDarkIconColor = Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);
        mIsVertical = false;
        mLongClickableAccessibilityButton = false;

        // Set up the context group of buttons
        mContextualButtonGroup = new ContextualButtonGroup(R.id.menu_container);
        final ContextualButton imeSwitcherButton = new ContextualButton(R.id.ime_switcher,
                mLightContext, R.drawable.ic_ime_switcher_default);
        final ContextualButton accessibilityButton =
                new ContextualButton(R.id.accessibility_button, mLightContext,
                        R.drawable.ic_sysbar_accessibility_button);
        mContextualButtonGroup.addButton(imeSwitcherButton);
        mContextualButtonGroup.addButton(accessibilityButton);
        mRotationContextButton = new RotationContextButton(R.id.rotate_suggestion,
                mLightContext, R.drawable.ic_sysbar_rotate_button_ccw_start_0);
        mFloatingRotationButton = new FloatingRotationButton(mContext,
                R.string.accessibility_rotate_button,
                R.layout.rotate_suggestion,
                R.id.rotate_suggestion,
                R.dimen.floating_rotation_button_min_margin,
                R.dimen.rounded_corner_content_padding,
                R.dimen.floating_rotation_button_taskbar_left_margin,
                R.dimen.floating_rotation_button_taskbar_bottom_margin,
                R.dimen.floating_rotation_button_diameter,
                R.dimen.key_button_ripple_max_width);
        mRotationButtonController = new RotationButtonController(mLightContext, mLightIconColor,
                mDarkIconColor, R.drawable.ic_sysbar_rotate_button_ccw_start_0,
                R.drawable.ic_sysbar_rotate_button_ccw_start_90,
                R.drawable.ic_sysbar_rotate_button_cw_start_0,
                R.drawable.ic_sysbar_rotate_button_cw_start_90,
                () -> mCurrentRotation);

        mConfiguration = new Configuration();
        mTmpLastConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mScreenPinningNotify = new ScreenPinningNotify(mContext);

        mButtonDispatchers.put(R.id.back, new ButtonDispatcher(R.id.back));
        mButtonDispatchers.put(R.id.home, new ButtonDispatcher(R.id.home));
        mButtonDispatchers.put(R.id.home_handle, new ButtonDispatcher(R.id.home_handle));
        mButtonDispatchers.put(R.id.recent_apps, new ButtonDispatcher(R.id.recent_apps));
        mButtonDispatchers.put(R.id.ime_switcher, imeSwitcherButton);
        mButtonDispatchers.put(R.id.accessibility_button, accessibilityButton);
        mButtonDispatchers.put(R.id.menu_container, mContextualButtonGroup);
        mDeadZone = new DeadZone(this);
    }

    public void setEdgeBackGestureHandler(EdgeBackGestureHandler edgeBackGestureHandler) {
        mEdgeBackGestureHandler = edgeBackGestureHandler;
    }

    void setBarTransitions(NavigationBarTransitions navigationBarTransitions) {
        mBarTransitions = navigationBarTransitions;
    }

    public void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
    }

    public LightBarTransitionsController getLightTransitionsController() {
        return mBarTransitions.getLightTransitionsController();
    }

    public void setComponents(Optional<Recents> recentsOptional) {
        mRecentsOptional = recentsOptional;
    }

    public void setComponents(NotificationPanelViewController panel) {
        mPanelView = panel;
        updatePanelSystemUiStateFlags();
    }

    public void setOnVerticalChangedListener(OnVerticalChangedListener onVerticalChangedListener) {
        mOnVerticalChangedListener = onVerticalChangedListener;
        notifyVerticalChangedListener(mIsVertical);
    }

    public void setBackgroundExecutor(Executor bgExecutor) {
        mBgExecutor = bgExecutor;
    }

    public void setTouchHandler(Gefingerpoken touchHandler) {
        mTouchHandler = touchHandler;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mTouchHandler.onInterceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mTouchHandler.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    public void abortCurrentGesture() {
        getHomeButton().abortCurrentGesture();
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    /**
     * Applies {@param consumer} to each of the nav bar views.
     */
    public void forEachView(Consumer<View> consumer) {
        if (mVertical != null) {
            consumer.accept(mVertical);
        }
        if (mHorizontal != null) {
            consumer.accept(mHorizontal);
        }
    }

    public RotationButtonController getRotationButtonController() {
        return mRotationButtonController;
    }

    public FloatingRotationButton getFloatingRotationButton() {
        return mFloatingRotationButton;
    }

    public ButtonDispatcher getRecentsButton() {
        return mButtonDispatchers.get(R.id.recent_apps);
    }

    public ButtonDispatcher getBackButton() {
        return mButtonDispatchers.get(R.id.back);
    }

    public ButtonDispatcher getHomeButton() {
        return mButtonDispatchers.get(R.id.home);
    }

    public ButtonDispatcher getImeSwitchButton() {
        return mButtonDispatchers.get(R.id.ime_switcher);
    }

    public ButtonDispatcher getAccessibilityButton() {
        return mButtonDispatchers.get(R.id.accessibility_button);
    }

    public RotationContextButton getRotateSuggestionButton() {
        return (RotationContextButton) mButtonDispatchers.get(R.id.rotate_suggestion);
    }

    public ButtonDispatcher getHomeHandle() {
        return mButtonDispatchers.get(R.id.home_handle);
    }

    public SparseArray<ButtonDispatcher> getButtonDispatchers() {
        return mButtonDispatchers;
    }

    public boolean isRecentsButtonVisible() {
        return getRecentsButton().getVisibility() == View.VISIBLE;
    }

    public boolean isOverviewEnabled() {
        return (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) == 0;
    }

    private boolean isQuickStepSwipeUpEnabled() {
        return mShowSwipeUpUi && isOverviewEnabled();
    }

    private void reloadNavIcons() {
        updateIcons(Configuration.EMPTY);
    }

    private void updateIcons(Configuration oldConfig) {
        final boolean orientationChange = oldConfig.orientation != mConfiguration.orientation;
        final boolean densityChange = oldConfig.densityDpi != mConfiguration.densityDpi;
        final boolean dirChange = oldConfig.getLayoutDirection() != mConfiguration.getLayoutDirection();

        if (orientationChange || densityChange) {
            mDockedIcon = getDrawable(R.drawable.ic_sysbar_docked);
            mHomeDefaultIcon = getHomeDrawable();
        }
        if (densityChange || dirChange) {
            mRecentIcon = getDrawable(R.drawable.ic_sysbar_recent);
            mContextualButtonGroup.updateIcons(mLightIconColor, mDarkIconColor);
        }
        if (orientationChange || densityChange || dirChange) {
            mBackIcon = getBackDrawable();
        }
    }

    /**
     * Updates the rotation button based on the current navigation mode.
     */
    void updateRotationButton() {
        if (isGesturalMode(mNavBarMode)) {
            mContextualButtonGroup.removeButton(R.id.rotate_suggestion);
            mButtonDispatchers.remove(R.id.rotate_suggestion);
            mRotationButtonController.setRotationButton(mFloatingRotationButton,
                    mRotationButtonListener);
        } else if (mContextualButtonGroup.getContextButton(R.id.rotate_suggestion) == null) {
            mContextualButtonGroup.addButton(mRotationContextButton);
            mButtonDispatchers.put(R.id.rotate_suggestion, mRotationContextButton);
            mRotationButtonController.setRotationButton(mRotationContextButton,
                    mRotationButtonListener);
        }
        mNavigationInflaterView.setButtonDispatchers(mButtonDispatchers);
    }

    public KeyButtonDrawable getBackDrawable() {
        KeyButtonDrawable drawable = getDrawable(getBackDrawableRes());
        orientBackButton(drawable);
        return drawable;
    }

    public @DrawableRes int getBackDrawableRes() {
        return chooseNavigationIconDrawableRes(R.drawable.ic_sysbar_back,
                R.drawable.ic_sysbar_back_quick_step);
    }

    public KeyButtonDrawable getHomeDrawable() {
        KeyButtonDrawable drawable = mShowSwipeUpUi
                ? getDrawable(R.drawable.ic_sysbar_home_quick_step)
                : getDrawable(R.drawable.ic_sysbar_home);
        orientHomeButton(drawable);
        return drawable;
    }

    private void orientBackButton(KeyButtonDrawable drawable) {
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean isRtl = mConfiguration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float degrees = useAltBack ? (isRtl ? 90 : -90) : 0;
        if (drawable.getRotation() == degrees) {
            return;
        }

        if (isGesturalMode(mNavBarMode)) {
            drawable.setRotation(degrees);
            return;
        }

        // Animate the back button's rotation to the new degrees and only in portrait move up the
        // back button to line up with the other buttons
        float targetY = !mShowSwipeUpUi && !mIsVertical && useAltBack
                ? - getResources().getDimension(R.dimen.navbar_back_button_ime_offset)
                : 0;
        ObjectAnimator navBarAnimator = ObjectAnimator.ofPropertyValuesHolder(drawable,
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_ROTATE, degrees),
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_TRANSLATE_Y, targetY));
        navBarAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        navBarAnimator.setDuration(200);
        navBarAnimator.start();
    }

    private void orientHomeButton(KeyButtonDrawable drawable) {
        drawable.setRotation(mIsVertical ? 90 : 0);
    }

    private @DrawableRes int chooseNavigationIconDrawableRes(@DrawableRes int icon,
            @DrawableRes int quickStepIcon) {
        return mShowSwipeUpUi ? quickStepIcon : icon;
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon) {
        return KeyButtonDrawable.create(mLightContext, mLightIconColor, mDarkIconColor, icon,
                true /* hasShadow */, null /* ovalBackgroundColor */);
    }

    /** To be called when screen lock/unlock state changes */
    public void onScreenStateChanged(boolean isScreenOn) {
        mScreenOn = isScreenOn;
    }

    public void setWindowVisible(boolean visible) {
        mRotationButtonController.onNavigationBarWindowVisibilityChange(visible);
    }

    public void setBehavior(@Behavior int behavior) {
        mRotationButtonController.onBehaviorChanged(Display.DEFAULT_DISPLAY, behavior);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        reloadNavIcons();

        super.setLayoutDirection(layoutDirection);
    }

    void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        mNavigationIconHints = hints;
        updateNavButtonIcons();
    }

    void onImeVisibilityChanged(boolean visible) {
        if (!visible) {
            mTransitionListener.onBackAltCleared();
        }
        mRotationButtonController.getRotationButton().setCanShowRotationButton(!visible);
    }

    void setDisabledFlags(int disabledFlags, SysUiState sysUiState) {
        if (mDisabledFlags == disabledFlags) return;

        final boolean overviewEnabledBefore = isOverviewEnabled();
        mDisabledFlags = disabledFlags;

        // Update icons if overview was just enabled to ensure the correct icons are present
        if (!overviewEnabledBefore && isOverviewEnabled()) {
            reloadNavIcons();
        }

        updateNavButtonIcons();
        updateSlippery();
        updateDisabledSystemUiStateFlags(sysUiState);
    }

    public void updateNavButtonIcons() {
        // We have to replace or restore the back and home button icons when exiting or entering
        // carmode, respectively. Recents are not available in CarMode in nav bar so change
        // to recent icon is not required.
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        KeyButtonDrawable backIcon = mBackIcon;
        orientBackButton(backIcon);
        KeyButtonDrawable homeIcon = mHomeDefaultIcon;
        if (!mUseCarModeUi) {
            orientHomeButton(homeIcon);
        }
        getHomeButton().setImageDrawable(homeIcon);
        getBackButton().setImageDrawable(backIcon);

        updateRecentsIcon();

        // Update IME button visibility, a11y and rotate button always overrides the appearance
        boolean disableImeSwitcher =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN) == 0
                || isImeRenderingNavButtons();
        mContextualButtonGroup.setButtonVisibility(R.id.ime_switcher, !disableImeSwitcher);

        mBarTransitions.reapplyDarkIntensity();

        boolean disableHome = isGesturalMode(mNavBarMode)
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        // Always disable recents when alternate car mode UI is active and for secondary displays.
        boolean disableRecent = isRecentsButtonDisabled();

        // Disable the home handle if both hone and recents are disabled
        boolean disableHomeHandle = disableRecent
                && ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        boolean disableBack = !useAltBack && (mEdgeBackGestureHandler.isHandlingGestures()
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0))
                || isImeRenderingNavButtons();

        // When screen pinning, don't hide back and home when connected service or back and
        // recents buttons when disconnected from launcher service in screen pinning mode,
        // as they are used for exiting.
        final boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        if (mOverviewProxyEnabled) {
            // Force disable recents when not in legacy mode
            disableRecent |= !QuickStepContract.isLegacyMode(mNavBarMode);
            if (pinningActive && !QuickStepContract.isGesturalMode(mNavBarMode)) {
                disableBack = disableHome = false;
            }
        } else if (pinningActive) {
            disableBack = disableRecent = false;
        }

        ViewGroup navButtons = getCurrentView().findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
            }
        }

        getBackButton().setVisibility(disableBack       ? View.INVISIBLE : View.VISIBLE);
        getHomeButton().setVisibility(disableHome       ? View.INVISIBLE : View.VISIBLE);
        getRecentsButton().setVisibility(disableRecent  ? View.INVISIBLE : View.VISIBLE);
        getHomeHandle().setVisibility(disableHomeHandle ? View.INVISIBLE : View.VISIBLE);
        notifyActiveTouchRegions();
    }

    /**
     * Returns whether the IME is currently visible and drawing the nav buttons.
     */
    boolean isImeRenderingNavButtons() {
        return mImeDrawsImeNavBar
                && mImeCanRenderGesturalNavButtons
                && (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0;
    }

    @VisibleForTesting
    boolean isRecentsButtonDisabled() {
        return mUseCarModeUi || !isOverviewEnabled()
                || getContext().getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    private Display getContextDisplay() {
        return getContext().getDisplay();
    }

    public void setLayoutTransitionsEnabled(boolean enabled) {
        mLayoutTransitionsEnabled = enabled;
        updateLayoutTransitionsEnabled();
    }

    public void setWakeAndUnlocking(boolean wakeAndUnlocking) {
        setUseFadingAnimations(wakeAndUnlocking);
        mWakeAndUnlocking = wakeAndUnlocking;
        updateLayoutTransitionsEnabled();
    }

    private void updateLayoutTransitionsEnabled() {
        boolean enabled = !mWakeAndUnlocking && mLayoutTransitionsEnabled;
        ViewGroup navButtons = (ViewGroup) getCurrentView().findViewById(R.id.nav_buttons);
        LayoutTransition lt = navButtons.getLayoutTransition();
        if (lt != null) {
            if (enabled) {
                lt.enableTransitionType(LayoutTransition.APPEARING);
                lt.enableTransitionType(LayoutTransition.DISAPPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            } else {
                lt.disableTransitionType(LayoutTransition.APPEARING);
                lt.disableTransitionType(LayoutTransition.DISAPPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            }
        }
    }

    private void setUseFadingAnimations(boolean useFadingAnimations) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) ((ViewGroup) getParent())
                .getLayoutParams();
        if (lp != null) {
            boolean old = lp.windowAnimations != 0;
            if (!old && useFadingAnimations) {
                lp.windowAnimations = R.style.Animation_NavigationBarFadeIn;
            } else if (old && !useFadingAnimations) {
                lp.windowAnimations = 0;
            } else {
                return;
            }
            WindowManager wm = getContext().getSystemService(WindowManager.class);
            wm.updateViewLayout((View) getParent(), lp);
        }
    }

    public void onStatusBarPanelStateChanged() {
        updateSlippery();
    }

    /** */
    public void updateDisabledSystemUiStateFlags(SysUiState sysUiState) {
        int displayId = mContext.getDisplayId();

        sysUiState.setFlag(SYSUI_STATE_SCREEN_PINNING,
                        ActivityManagerWrapper.getInstance().isScreenPinningActive())
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_SEARCH_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0)
                .commitUpdate(displayId);
    }

    private void updatePanelSystemUiStateFlags() {
        if (SysUiState.DEBUG) {
            Log.d(TAG, "Updating panel sysui state flags: panelView=" + mPanelView);
        }
        if (mPanelView != null) {
            mPanelView.updateSystemUiStateFlags();
        }
    }

    void onOverviewProxyConnectionChange(boolean enabled) {
        mOverviewProxyEnabled = enabled;
    }

    void setShouldShowSwipeUpUi(boolean showSwipeUpUi) {
        mShowSwipeUpUi = showSwipeUpUi;
        updateStates();
    }

    /** */
    public void updateStates() {
        if (mNavigationInflaterView != null) {
            // Reinflate the navbar if needed, no-op unless the swipe up state changes
            mNavigationInflaterView.onLikelyDefaultLayoutChange();
        }

        updateSlippery();
        reloadNavIcons();
        updateNavButtonIcons();
        mBgExecutor.execute(() -> setNavBarVirtualKeyHapticFeedbackEnabled(!mShowSwipeUpUi));
        getHomeButton().setAccessibilityDelegate(
                mShowSwipeUpUi ? mQuickStepAccessibilityDelegate : null);
    }

    /**
     * Enable or disable haptic feedback on the navigation bar buttons.
     */
    private void setNavBarVirtualKeyHapticFeedbackEnabled(boolean enabled) {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .setNavBarVirtualKeyHapticFeedbackEnabled(enabled);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to enable or disable navigation bar button haptics: ", e);
        }
    }

    /**
     * Updates the {@link WindowManager.LayoutParams.FLAG_SLIPPERY} state dependent on if swipe up
     * is enabled, or the notifications is fully opened without being in an animated state. If
     * slippery is enabled, touch events will leave the nav bar window and enter into the fullscreen
     * app/home window, if not nav bar will receive a cancelled touch event once gesture leaves bar.
     */
    void updateSlippery() {
        setSlippery(!isQuickStepSwipeUpEnabled() ||
                (mPanelView != null && mPanelView.isFullyExpanded() && !mPanelView.isCollapsing()));
    }

    void setSlippery(boolean slippery) {
        setWindowFlag(WindowManager.LayoutParams.FLAG_SLIPPERY, slippery);
    }

    private void setWindowFlag(int flags, boolean enable) {
        final ViewGroup navbarView = ((ViewGroup) getParent());
        if (navbarView == null) {
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) navbarView.getLayoutParams();
        if (lp == null || enable == ((lp.flags & flags) != 0)) {
            return;
        }
        if (enable) {
            lp.flags |= flags;
        } else {
            lp.flags &= ~flags;
        }
        WindowManager wm = getContext().getSystemService(WindowManager.class);
        wm.updateViewLayout(navbarView, lp);
    }

    void setNavBarMode(int mode, boolean imeDrawsImeNavBar) {
        mNavBarMode = mode;
        mImeDrawsImeNavBar = imeDrawsImeNavBar;
        mBarTransitions.onNavigationModeChanged(mNavBarMode);
        mEdgeBackGestureHandler.onNavigationModeChanged(mNavBarMode);
        mRotationButtonController.onNavigationModeChanged(mNavBarMode);
        updateRotationButton();
    }

    public void setAccessibilityButtonState(final boolean visible, final boolean longClickable) {
        mLongClickableAccessibilityButton = longClickable;
        getAccessibilityButton().setLongClickable(longClickable);
        mContextualButtonGroup.setButtonVisibility(R.id.accessibility_button, visible);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mNavigationInflaterView = findViewById(R.id.navigation_inflater);
        mNavigationInflaterView.setButtonDispatchers(mButtonDispatchers);

        updateOrientationViews();
        reloadNavIcons();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mDeadZone.onDraw(canvas);
        super.onDraw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        notifyActiveTouchRegions();
    }

    /**
     * Notifies the overview service of the active touch regions.
     */
    public void notifyActiveTouchRegions() {
        if (mUpdateActiveTouchRegionsCallback != null) {
            mUpdateActiveTouchRegionsCallback.update();
        }
    }

    void setUpdateActiveTouchRegionsCallback(UpdateActiveTouchRegionsCallback callback) {
        mUpdateActiveTouchRegionsCallback = callback;
        notifyActiveTouchRegions();
    }

    Map<View, Rect> getButtonTouchRegionCache() {
        FrameLayout navBarLayout = mIsVertical
                ? mNavigationInflaterView.mVertical
                : mNavigationInflaterView.mHorizontal;
        return ((NearestTouchFrame) navBarLayout
                .findViewById(R.id.nav_buttons)).getFullTouchableChildRegions();
    }

    private void updateOrientationViews() {
        mHorizontal = findViewById(R.id.horizontal);
        mVertical = findViewById(R.id.vertical);

        updateCurrentView();
    }

    boolean needsReorient(int rotation) {
        return mCurrentRotation != rotation;
    }

    private void updateCurrentRotation() {
        final int rotation = mConfiguration.windowConfiguration.getDisplayRotation();
        if (mCurrentRotation == rotation) {
            return;
        }
        mCurrentRotation = rotation;
        mNavigationInflaterView.setAlternativeOrder(mCurrentRotation == Surface.ROTATION_90);
        mDeadZone.onConfigurationChanged(mCurrentRotation);
        if (DEBUG) {
            Log.d(TAG, "updateCurrentRotation(): rot=" + mCurrentRotation);
        }
    }

    private void updateCurrentView() {
        resetViews();
        mCurrentView = mIsVertical ? mVertical : mHorizontal;
        mCurrentView.setVisibility(View.VISIBLE);
        mNavigationInflaterView.setVertical(mIsVertical);
        mNavigationInflaterView.updateButtonDispatchersCurrentView();
        updateLayoutTransitionsEnabled();
        updateCurrentRotation();
    }

    private void resetViews() {
        mHorizontal.setVisibility(View.GONE);
        mVertical.setVisibility(View.GONE);
    }

    private void updateRecentsIcon() {
        mDockedIcon.setRotation(mDockedStackExists && mIsVertical ? 90 : 0);
        getRecentsButton().setImageDrawable(mDockedStackExists ? mDockedIcon : mRecentIcon);
        mBarTransitions.reapplyDarkIntensity();
    }

    public void showPinningEnterExitToast(boolean entering) {
        if (entering) {
            mScreenPinningNotify.showPinningStartToast();
        } else {
            mScreenPinningNotify.showPinningExitToast();
        }
    }

    public void showPinningEscapeToast() {
        mScreenPinningNotify.showEscapeToast(
                mNavBarMode == NAV_BAR_MODE_GESTURAL, isRecentsButtonVisible());
    }

    public boolean isVertical() {
        return mIsVertical;
    }

    public void reorient() {
        updateCurrentView();
        ((NavigationBarFrame) getRootView()).setDeadZone(mDeadZone);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();

        // Resolve layout direction if not resolved since components changing layout direction such
        // as changing languages will recreate this view and the direction will be resolved later
        if (!isLayoutDirectionResolved()) {
            resolveLayoutDirection();
        }
        updateNavButtonIcons();

        getHomeButton().setVertical(mIsVertical);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (DEBUG) Log.d(TAG, String.format(
                "onMeasure: (%dx%d) old: (%dx%d)", w, h, getMeasuredWidth(), getMeasuredHeight()));

        final boolean newVertical = w > 0 && h > w
                && !isGesturalMode(mNavBarMode);
        if (newVertical != mIsVertical) {
            mIsVertical = newVertical;
            if (DEBUG) {
                Log.d(TAG, String.format("onMeasure: h=%d, w=%d, vert=%s", h, w,
                        mIsVertical ? "y" : "n"));
            }
            reorient();
            notifyVerticalChangedListener(newVertical);
        }

        if (isGesturalMode(mNavBarMode)) {
            // Update the nav bar background to match the height of the visible nav bar
            int height = mIsVertical
                    ? getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_height_landscape)
                    : getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_height);
            int frameHeight = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_frame_height);
            mBarTransitions.setBackgroundFrame(new Rect(0, frameHeight - height, w, h));
        } else {
            mBarTransitions.setBackgroundFrame(null);
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    int getNavBarHeight() {
        return mIsVertical
                ? getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height_landscape)
                : getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.navigation_bar_height);
    }

    private void notifyVerticalChangedListener(boolean newVertical) {
        if (mOnVerticalChangedListener != null) {
            mOnVerticalChangedListener.onVerticalChanged(newVertical);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mTmpLastConfiguration.updateFrom(mConfiguration);
        final int changes = mConfiguration.updateFrom(newConfig);
        mFloatingRotationButton.onConfigurationChanged(changes);

        boolean uiCarModeChanged = updateCarMode();
        updateIcons(mTmpLastConfiguration);
        updateRecentsIcon();
        updateCurrentRotation();
        mEdgeBackGestureHandler.onConfigurationChanged(mConfiguration);
        if (uiCarModeChanged || mTmpLastConfiguration.densityDpi != mConfiguration.densityDpi
                || mTmpLastConfiguration.getLayoutDirection() != mConfiguration.getLayoutDirection()) {
            // If car mode or density changes, we need to reset the icons.
            updateNavButtonIcons();
        }
    }

    /**
     * If the configuration changed, update the carmode and return that it was updated.
     */
    private boolean updateCarMode() {
        boolean uiCarModeChanged = false;
        if (mConfiguration != null) {
            int uiMode = mConfiguration.uiMode & Configuration.UI_MODE_TYPE_MASK;
            final boolean isCarMode = (uiMode == Configuration.UI_MODE_TYPE_CAR);

            if (isCarMode != mInCarMode) {
                mInCarMode = isCarMode;
                if (ALTERNATE_CAR_MODE_UI) {
                    mUseCarModeUi = isCarMode;
                    uiCarModeChanged = true;
                } else {
                    // Don't use car mode behavior if ALTERNATE_CAR_MODE_UI not set.
                    mUseCarModeUi = false;
                }
            }
        }
        return uiCarModeChanged;
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = getContext().getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // This needs to happen first as it can changed the enabled state which can affect whether
        // the back button is visible
        mEdgeBackGestureHandler.onNavBarAttached();
        requestApplyInsets();
        reorient();
        if (mRotationButtonController != null) {
            mRotationButtonController.registerListeners();
        }

        updateNavButtonIcons();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (int i = 0; i < mButtonDispatchers.size(); ++i) {
            mButtonDispatchers.valueAt(i).onDestroy();
        }
        if (mRotationButtonController != null) {
            mFloatingRotationButton.hide();
            mRotationButtonController.unregisterListeners();
        }

        mEdgeBackGestureHandler.onNavBarDetached();
    }

    void dump(PrintWriter pw) {
        final Rect r = new Rect();
        final Point size = new Point();
        getContextDisplay().getRealSize(size);

        pw.println("NavigationBarView:");
        pw.println(String.format("      this: " + CentralSurfaces.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s %f",
                        getResourceName(getCurrentView().getId()),
                        getCurrentView().getWidth(), getCurrentView().getHeight(),
                        visibilityToString(getCurrentView().getVisibility()),
                        getCurrentView().getAlpha()));

        pw.println(String.format("      disabled=0x%08x vertical=%s darkIntensity=%.2f",
                        mDisabledFlags,
                        mIsVertical ? "true" : "false",
                        getLightTransitionsController().getCurrentDarkIntensity()));

        pw.println("    mScreenOn: " + mScreenOn);


        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "handle", getHomeHandle());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "rota", getRotateSuggestionButton());
        dumpButton(pw, "a11y", getAccessibilityButton());
        dumpButton(pw, "ime", getImeSwitchButton());

        if (mNavigationInflaterView != null) {
            mNavigationInflaterView.dump(pw);
        }
        mBarTransitions.dump(pw);
        mContextualButtonGroup.dump(pw);
        mEdgeBackGestureHandler.dump(pw);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int leftInset = insets.getSystemWindowInsetLeft();
        int rightInset = insets.getSystemWindowInsetRight();
        setPadding(leftInset, insets.getSystemWindowInsetTop(), rightInset,
                insets.getSystemWindowInsetBottom());
        // we're passing the insets onto the gesture handler since the back arrow is only
        // conditionally added and doesn't always get all the insets.
        mEdgeBackGestureHandler.setInsets(leftInset, rightInset);

        // this allows assist handle to be drawn outside its bound so that it can align screen
        // bottom by translating its y position.
        final boolean shouldClip =
                !isGesturalMode(mNavBarMode) || insets.getSystemWindowInsetBottom() == 0;
        setClipChildren(shouldClip);
        setClipToPadding(shouldClip);

        return super.onApplyWindowInsets(insets);
    }

    void addPipExclusionBoundsChangeListener(Pip pip) {
        pip.addPipExclusionBoundsChangeListener(mPipListener);
    }

    void removePipExclusionBoundsChangeListener(Pip pip) {
        pip.removePipExclusionBoundsChangeListener(mPipListener);
    }

    void registerBackAnimation(BackAnimation backAnimation) {
        mEdgeBackGestureHandler.setBackAnimation(backAnimation);
    }

    private static void dumpButton(PrintWriter pw, String caption, ButtonDispatcher button) {
        pw.print("      " + caption + ": ");
        if (button == null) {
            pw.print("null");
        } else {
            pw.print(visibilityToString(button.getVisibility())
                    + " alpha=" + button.getAlpha()
                    );
        }
        pw.println();
    }

    public interface OnVerticalChangedListener {
        void onVerticalChanged(boolean isVertical);
    }

    private final Consumer<Boolean> mDockedListener = exists -> post(() -> {
        mDockedStackExists = exists;
        updateRecentsIcon();
    });

    private final Consumer<Rect> mPipListener = bounds -> post(() -> {
        mEdgeBackGestureHandler.setPipStashExclusionBounds(bounds);
    });


    interface UpdateActiveTouchRegionsCallback {
        void update();
    }
}
