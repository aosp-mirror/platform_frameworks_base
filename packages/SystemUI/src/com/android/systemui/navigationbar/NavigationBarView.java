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

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SEARCH_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.isGesturalMode;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.util.Utils.isGesturalModeOnDefaultDisplay;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowInsets;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.RotationButton.RotationButtonUpdatesCallback;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.ContextualButton;
import com.android.systemui.navigationbar.buttons.ContextualButtonGroup;
import com.android.systemui.navigationbar.buttons.DeadZone;
import com.android.systemui.navigationbar.buttons.KeyButtonDrawable;
import com.android.systemui.navigationbar.buttons.NearestTouchFrame;
import com.android.systemui.navigationbar.buttons.RotationContextButton;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.navigationbar.gestural.FloatingRotationButton;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.shared.navigationbar.RegionSamplingHelper;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class NavigationBarView extends FrameLayout implements
        NavigationModeController.ModeChangedListener {
    final static boolean DEBUG = false;
    final static String TAG = "StatusBar/NavBarView";

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED = true;

    final static boolean ALTERNATE_CAR_MODE_UI = false;
    private final RegionSamplingHelper mRegionSamplingHelper;
    private final int mNavColorSampleMargin;
    private final SysUiState mSysUiFlagContainer;

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

    private final Region mTmpRegion = new Region();
    private final int[] mTmpPosition = new int[2];
    private Rect mTmpBounds = new Rect();
    private Map<View, Rect> mButtonFullTouchableRegions = new HashMap<>();

    private KeyButtonDrawable mBackIcon;
    private KeyButtonDrawable mHomeDefaultIcon;
    private KeyButtonDrawable mRecentIcon;
    private KeyButtonDrawable mDockedIcon;
    private Context mLightContext;
    private int mLightIconColor;
    private int mDarkIconColor;

    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    private final DeadZone mDeadZone;
    private boolean mDeadZoneConsuming = false;
    private final NavigationBarTransitions mBarTransitions;
    private final OverviewProxyService mOverviewProxyService;
    private AutoHideController mAutoHideController;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private OnVerticalChangedListener mOnVerticalChangedListener;
    private boolean mLayoutTransitionsEnabled = true;
    private boolean mWakeAndUnlocking;
    private boolean mUseCarModeUi = false;
    private boolean mInCarMode = false;
    private boolean mDockedStackExists;
    private boolean mImeVisible;
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
    private NavigationBarOverlayController mNavBarOverlayController;

    /**
     * Helper that is responsible for showing the right toast when a disallowed activity operation
     * occurred. In pinned mode, we show instructions on how to break out of this mode, whilst in
     * fully locked mode we only show that unlocking is blocked.
     */
    private ScreenPinningNotify mScreenPinningNotify;
    private Rect mSamplingBounds = new Rect();
    /**
     * When quickswitching between apps of different orientations, we draw a secondary home handle
     * in the position of the first app's orientation. This rect represents the region of that
     * home handle so we can apply the correct light/dark luma on that.
     * @see {@link NavigationBar#mOrientationHandle}
     */
    @Nullable
    private Rect mOrientedHandleSamplingRegion;

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

    private final OnComputeInternalInsetsListener mOnComputeInternalInsetsListener = info -> {
        // When the nav bar is in 2-button or 3-button mode, or when IME is visible in fully
        // gestural mode, the entire nav bar should be touchable.
        if (!mEdgeBackGestureHandler.isHandlingGestures()) {
            info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
            return;
        }

        // When in gestural and the IME is showing, don't use the nearest region since it will take
        // gesture space away from the IME
        info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(getButtonLocations(false /* includeFloatingButtons */,
                false /* inScreen */, false /* useNearestRegion */));
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

    private final Consumer<Boolean> mNavbarOverlayVisibilityChangeCallback = (visible) -> {
        if (visible) {
            mAutoHideController.touchAutoHide();
        }
        notifyActiveTouchRegions();
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
        mNavBarMode = Dependency.get(NavigationModeController.class).addListener(this);

        mSysUiFlagContainer = Dependency.get(SysUiState.class);
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
        mFloatingRotationButton = new FloatingRotationButton(context);
        mRotationButtonController = new RotationButtonController(mLightContext,
                mLightIconColor, mDarkIconColor);
        updateRotationButton();

        mOverviewProxyService = Dependency.get(OverviewProxyService.class);

        mConfiguration = new Configuration();
        mTmpLastConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mScreenPinningNotify = new ScreenPinningNotify(mContext);
        mBarTransitions = new NavigationBarTransitions(this, Dependency.get(CommandQueue.class));

        mButtonDispatchers.put(R.id.back, new ButtonDispatcher(R.id.back));
        mButtonDispatchers.put(R.id.home, new ButtonDispatcher(R.id.home));
        mButtonDispatchers.put(R.id.home_handle, new ButtonDispatcher(R.id.home_handle));
        mButtonDispatchers.put(R.id.recent_apps, new ButtonDispatcher(R.id.recent_apps));
        mButtonDispatchers.put(R.id.ime_switcher, imeSwitcherButton);
        mButtonDispatchers.put(R.id.accessibility_button, accessibilityButton);
        mButtonDispatchers.put(R.id.menu_container, mContextualButtonGroup);
        mDeadZone = new DeadZone(this);

        mNavColorSampleMargin = getResources()
                        .getDimensionPixelSize(R.dimen.navigation_handle_sample_horizontal_margin);
        mEdgeBackGestureHandler = Dependency.get(EdgeBackGestureHandler.Factory.class)
                .create(mContext);
        mEdgeBackGestureHandler.setStateChangeCallback(this::updateStates);
        Executor backgroundExecutor = Dependency.get(Dependency.BACKGROUND_EXECUTOR);
        mRegionSamplingHelper = new RegionSamplingHelper(this,
                new RegionSamplingHelper.SamplingCallback() {
                    @Override
                    public void onRegionDarknessChanged(boolean isRegionDark) {
                        getLightTransitionsController().setIconsDark(!isRegionDark ,
                                true /* animate */);
                    }

                    @Override
                    public Rect getSampledRegion(View sampledView) {
                        if (mOrientedHandleSamplingRegion != null) {
                            return mOrientedHandleSamplingRegion;
                        }

                        updateSamplingRect();
                        return mSamplingBounds;
                    }

                    @Override
                    public boolean isSamplingEnabled() {
                        return isGesturalModeOnDefaultDisplay(getContext(), mNavBarMode);
                    }
                }, backgroundExecutor);

        mNavBarOverlayController = Dependency.get(NavigationBarOverlayController.class);
        if (mNavBarOverlayController.isNavigationBarOverlayEnabled()) {
            mNavBarOverlayController.init(mNavbarOverlayVisibilityChangeCallback,
                    mEdgeBackGestureHandler::updateNavigationBarOverlayExcludeRegion);
        }
    }

    public void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
    }

    public NavigationBarTransitions getBarTransitions() {
        return mBarTransitions;
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

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isGesturalMode(mNavBarMode) && mImeVisible
                && event.getAction() == MotionEvent.ACTION_DOWN) {
            SysUiStatsLog.write(SysUiStatsLog.IME_TOUCH_REPORTED,
                    (int) event.getX(), (int) event.getY());
        }
        return shouldDeadZoneConsumeTouchEvents(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        shouldDeadZoneConsumeTouchEvents(event);
        return super.onTouchEvent(event);
    }

    /**
     * If we're blurring the shade window.
     */
    public void setWindowHasBlurs(boolean hasBlurs) {
        mRegionSamplingHelper.setWindowHasBlurs(hasBlurs);
    }

    void onTransientStateChanged(boolean isTransient) {
        mEdgeBackGestureHandler.onNavBarTransientStateChanged(isTransient);

        // The visibility of the navigation bar buttons is dependent on the transient state of
        // the navigation bar.
        if (mNavBarOverlayController.isNavigationBarOverlayEnabled()) {
            mNavBarOverlayController.setButtonState(isTransient, /* force */ false);
        }
    }

    void onBarTransition(int newMode) {
        if (newMode == MODE_OPAQUE) {
            // If the nav bar background is opaque, stop auto tinting since we know the icons are
            // showing over a dark background
            mRegionSamplingHelper.stop();
            getLightTransitionsController().setIconsDark(false /* dark */, true /* animate */);
        } else {
            mRegionSamplingHelper.start(mSamplingBounds);
        }
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
                    setSlippery(true);
                    mDeadZoneConsuming = true;
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    // When a gesture started in the deadzone is finished, restore slippery state
                    updateSlippery();
                    mDeadZoneConsuming = false;
                    break;
            }
            return true;
        }
        return false;
    }

    public void abortCurrentGesture() {
        getHomeButton().abortCurrentGesture();
    }

    public View getCurrentView() {
        return mCurrentView;
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

    public boolean isQuickStepSwipeUpEnabled() {
        return mOverviewProxyService.shouldShowSwipeUpUI() && isOverviewEnabled();
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
    private void updateRotationButton() {
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
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        KeyButtonDrawable drawable = quickStepEnabled
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
        float targetY = !mOverviewProxyService.shouldShowSwipeUpUI() && !mIsVertical && useAltBack
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
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        return quickStepEnabled ? quickStepIcon : icon;
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon) {
        return KeyButtonDrawable.create(mLightContext, mLightIconColor, mDarkIconColor, icon,
                true /* hasShadow */, null /* ovalBackgroundColor */);
    }

    /** To be called when screen lock/unlock state changes */
    public void onScreenStateChanged(boolean isScreenOn) {
        mScreenOn = isScreenOn;
        if (isScreenOn) {
            if (isGesturalModeOnDefaultDisplay(getContext(), mNavBarMode)) {
                mRegionSamplingHelper.start(mSamplingBounds);
            }
        } else {
            mRegionSamplingHelper.stop();
        }
    }

    public void setWindowVisible(boolean visible) {
        mRegionSamplingHelper.setWindowVisible(visible);
        mRotationButtonController.onNavigationBarWindowVisibilityChange(visible);
    }

    public void setBehavior(@Behavior int behavior) {
        mRotationButtonController.onBehaviorChanged(behavior);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        reloadNavIcons();

        super.setLayoutDirection(layoutDirection);
    }

    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        final boolean newBackAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean oldBackAlt =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if (newBackAlt != oldBackAlt) {
            onImeVisibilityChanged(newBackAlt);
        }

        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }
        mNavigationIconHints = hints;
        updateNavButtonIcons();
    }

    private void onImeVisibilityChanged(boolean visible) {
        if (!visible) {
            mTransitionListener.onBackAltCleared();
        }
        mImeVisible = visible;
        mRotationButtonController.getRotationButton().setCanShowRotationButton(!mImeVisible);
        if (mNavBarOverlayController.isNavigationBarOverlayEnabled()) {
            mNavBarOverlayController.setCanShow(!mImeVisible);
        }
    }

    public void setDisabledFlags(int disabledFlags) {
        if (mDisabledFlags == disabledFlags) return;

        final boolean overviewEnabledBefore = isOverviewEnabled();
        mDisabledFlags = disabledFlags;

        // Update icons if overview was just enabled to ensure the correct icons are present
        if (!overviewEnabledBefore && isOverviewEnabled()) {
            reloadNavIcons();
        }

        updateNavButtonIcons();
        updateSlippery();
        updateDisabledSystemUiStateFlags();
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
        mContextualButtonGroup.setButtonVisibility(R.id.ime_switcher,
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);

        mBarTransitions.reapplyDarkIntensity();

        boolean disableHome = isGesturalMode(mNavBarMode)
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        // Always disable recents when alternate car mode UI is active and for secondary displays.
        boolean disableRecent = isRecentsButtonDisabled();

        // Disable the home handle if both hone and recents are disabled
        boolean disableHomeHandle = disableRecent
                && ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        boolean disableBack = !useAltBack && (mEdgeBackGestureHandler.isHandlingGestures()
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0));

        // When screen pinning, don't hide back and home when connected service or back and
        // recents buttons when disconnected from launcher service in screen pinning mode,
        // as they are used for exiting.
        final boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        if (mOverviewProxyService.isEnabled()) {
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

    public void updateDisabledSystemUiStateFlags() {
        int displayId = mContext.getDisplayId();

        mSysUiFlagContainer.setFlag(SYSUI_STATE_SCREEN_PINNING,
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

    public void updateStates() {
        final boolean showSwipeUpUI = mOverviewProxyService.shouldShowSwipeUpUI();

        if (mNavigationInflaterView != null) {
            // Reinflate the navbar if needed, no-op unless the swipe up state changes
            mNavigationInflaterView.onLikelyDefaultLayoutChange();
        }

        updateSlippery();
        reloadNavIcons();
        updateNavButtonIcons();
        WindowManagerWrapper.getInstance().setNavBarVirtualKeyHapticFeedbackEnabled(!showSwipeUpUI);
        getHomeButton().setAccessibilityDelegate(
                showSwipeUpUI ? mQuickStepAccessibilityDelegate : null);
    }

    /**
     * Updates the {@link WindowManager.LayoutParams.FLAG_SLIPPERY} state dependent on if swipe up
     * is enabled, or the notifications is fully opened without being in an animated state. If
     * slippery is enabled, touch events will leave the nav bar window and enter into the fullscreen
     * app/home window, if not nav bar will receive a cancelled touch event once gesture leaves bar.
     */
    public void updateSlippery() {
        setSlippery(!isQuickStepSwipeUpEnabled() ||
                (mPanelView != null && mPanelView.isFullyExpanded() && !mPanelView.isCollapsing()));
    }

    private void setSlippery(boolean slippery) {
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

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
        mBarTransitions.onNavigationModeChanged(mNavBarMode);
        mEdgeBackGestureHandler.onNavigationModeChanged(mNavBarMode);
        updateRotationButton();

        if (isGesturalMode(mNavBarMode)) {
            mRegionSamplingHelper.start(mSamplingBounds);
        } else {
            mRegionSamplingHelper.stop();
        }
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

    private void updateSamplingRect() {
        mSamplingBounds.setEmpty();
        // TODO: Extend this to 2/3 button layout as well
        View view = getHomeHandle().getCurrentView();

        if (view != null) {
            int[] pos = new int[2];
            view.getLocationOnScreen(pos);
            Point displaySize = new Point();
            view.getContext().getDisplay().getRealSize(displaySize);
            final Rect samplingBounds = new Rect(pos[0] - mNavColorSampleMargin,
                    displaySize.y - getNavBarHeight(),
                    pos[0] + view.getWidth() + mNavColorSampleMargin,
                    displaySize.y);
            mSamplingBounds.set(samplingBounds);
        }
    }

    void setOrientedHandleSamplingRegion(Rect orientedHandleSamplingRegion) {
        mOrientedHandleSamplingRegion = orientedHandleSamplingRegion;
        mRegionSamplingHelper.updateSamplingRect();
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
        mOverviewProxyService.onActiveNavBarRegionChanges(
                getButtonLocations(true /* includeFloatingButtons */, true /* inScreen */,
                        true /* useNearestRegion */));
    }

    private void updateButtonTouchRegionCache() {
        FrameLayout navBarLayout = mIsVertical
                ? mNavigationInflaterView.mVertical
                : mNavigationInflaterView.mHorizontal;
        mButtonFullTouchableRegions = ((NearestTouchFrame) navBarLayout
                .findViewById(R.id.nav_buttons)).getFullTouchableChildRegions();
    }

    /**
     * @param includeFloatingButtons Whether to include the floating rotation and overlay button in
     *                               the region for all the buttons
     * @param inScreenSpace Whether to return values in screen space or window space
     * @param useNearestRegion Whether to use the nearest region instead of the actual button bounds
     * @return
     */
    private Region getButtonLocations(boolean includeFloatingButtons, boolean inScreenSpace,
            boolean useNearestRegion) {
        if (useNearestRegion && !inScreenSpace) {
            // We currently don't support getting the nearest region in anything but screen space
            useNearestRegion = false;
        }
        mTmpRegion.setEmpty();
        updateButtonTouchRegionCache();
        updateButtonLocation(getBackButton(), inScreenSpace, useNearestRegion);
        updateButtonLocation(getHomeButton(), inScreenSpace, useNearestRegion);
        updateButtonLocation(getRecentsButton(), inScreenSpace, useNearestRegion);
        updateButtonLocation(getImeSwitchButton(), inScreenSpace, useNearestRegion);
        updateButtonLocation(getAccessibilityButton(), inScreenSpace, useNearestRegion);
        if (includeFloatingButtons && mFloatingRotationButton.isVisible()) {
            // Note: this button is floating so the nearest region doesn't apply
            updateButtonLocation(mFloatingRotationButton.getCurrentView(), inScreenSpace);
        } else {
            updateButtonLocation(getRotateSuggestionButton(), inScreenSpace, useNearestRegion);
        }
        if (includeFloatingButtons && mNavBarOverlayController.isNavigationBarOverlayEnabled()
                && mNavBarOverlayController.isVisible()) {
            // Note: this button is floating so the nearest region doesn't apply
            updateButtonLocation(mNavBarOverlayController.getCurrentView(), inScreenSpace);
        }
        return mTmpRegion;
    }

    private void updateButtonLocation(ButtonDispatcher button, boolean inScreenSpace,
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
        if (useNearestRegion && mButtonFullTouchableRegions.containsKey(view)) {
            mTmpRegion.op(mButtonFullTouchableRegions.get(view), Op.UNION);
            return;
        }
        updateButtonLocation(view, inScreenSpace);
    }

    private void updateButtonLocation(View view, boolean inScreenSpace) {
        if (inScreenSpace) {
            view.getBoundsOnScreen(mTmpBounds);
        } else {
            view.getLocationInWindow(mTmpPosition);
            mTmpBounds.set(mTmpPosition[0], mTmpPosition[1],
                    mTmpPosition[0] + view.getWidth(),
                    mTmpPosition[1] + view.getHeight());
        }
        mTmpRegion.op(mTmpBounds, Op.UNION);
    }

    private void updateOrientationViews() {
        mHorizontal = findViewById(R.id.horizontal);
        mVertical = findViewById(R.id.vertical);

        updateCurrentView();
    }

    boolean needsReorient(int rotation) {
        return mCurrentRotation != rotation;
    }

    private void updateCurrentView() {
        resetViews();
        mCurrentView = mIsVertical ? mVertical : mHorizontal;
        mCurrentView.setVisibility(View.VISIBLE);
        mNavigationInflaterView.setVertical(mIsVertical);
        mCurrentRotation = getContextDisplay().getRotation();
        mNavigationInflaterView.setAlternativeOrder(mCurrentRotation == Surface.ROTATION_90);
        mNavigationInflaterView.updateButtonDispatchersCurrentView();
        updateLayoutTransitionsEnabled();
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
        mDeadZone.onConfigurationChanged(mCurrentRotation);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mCurrentRotation);
        }

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

    private int getNavBarHeight() {
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
        mConfiguration.updateFrom(newConfig);
        boolean uiCarModeChanged = updateCarMode();
        updateIcons(mTmpLastConfiguration);
        updateRecentsIcon();
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
        onNavigationModeChanged(mNavBarMode);
        if (mRotationButtonController != null) {
            mRotationButtonController.registerListeners();
        }
        if (mNavBarOverlayController.isNavigationBarOverlayEnabled()) {
            mNavBarOverlayController.registerListeners();
        }

        getViewTreeObserver().addOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
        updateNavButtonIcons();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(NavigationModeController.class).removeListener(this);
        for (int i = 0; i < mButtonDispatchers.size(); ++i) {
            mButtonDispatchers.valueAt(i).onDestroy();
        }
        if (mRotationButtonController != null) {
            mRotationButtonController.unregisterListeners();
        }

        if (mNavBarOverlayController.isNavigationBarOverlayEnabled()) {
            mNavBarOverlayController.unregisterListeners();
        }

        mEdgeBackGestureHandler.onNavBarDetached();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(
                mOnComputeInternalInsetsListener);
    }

    public void dump(PrintWriter pw) {
        final Rect r = new Rect();
        final Point size = new Point();
        getContextDisplay().getRealSize(size);

        pw.println("NavigationBarView:");
        pw.println(String.format("      this: " + StatusBar.viewInfo(this)
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

        pw.println("      mOrientedHandleSamplingRegion: " + mOrientedHandleSamplingRegion);
        pw.println("    mScreenOn: " + mScreenOn);


        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "rota", getRotateSuggestionButton());
        dumpButton(pw, "a11y", getAccessibilityButton());
        dumpButton(pw, "ime", getImeSwitchButton());

        if (mNavigationInflaterView != null) {
            mNavigationInflaterView.dump(pw);
        }
        mBarTransitions.dump(pw);
        mContextualButtonGroup.dump(pw);
        mRegionSamplingHelper.dump(pw);
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

    void registerDockedListener(LegacySplitScreen legacySplitScreen) {
        legacySplitScreen.registerInSplitScreenListener(mDockedListener);
    }

    void registerPipExclusionBoundsChangeListener(Pip pip) {
        pip.setPipExclusionBoundsChangeListener(mPipListener);
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

    void setNavigationBarLumaSamplingEnabled(boolean enable) {
        if (enable) {
            mRegionSamplingHelper.start(mSamplingBounds);
        } else {
            mRegionSamplingHelper.stop();
        }
    }
}
