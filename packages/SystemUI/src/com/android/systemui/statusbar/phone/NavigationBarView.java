/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.view.MotionEvent.ACTION_DOWN;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_BACK;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_NONE;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.annotation.StyleRes;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.support.annotation.ColorInt;
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
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.DockedStackExistsListener;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.phone.NavGesture;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.recents.RecentsOnboarding;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.NavigationBarCompat;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.TintedKeyButtonDrawable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_DISABLE_QUICK_SCRUB;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_HIDE_BACK_BUTTON;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_SHOW_OVERVIEW_BUTTON;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_OVERVIEW;

public class NavigationBarView extends FrameLayout implements PluginListener<NavGesture> {
    final static boolean DEBUG = false;
    final static String TAG = "StatusBar/NavBarView";

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED = true;

    final static boolean ALTERNATE_CAR_MODE_UI = false;

    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    boolean mVertical;
    private int mCurrentRotation = -1;

    boolean mShowMenu;
    boolean mShowAccessibilityButton;
    boolean mLongClickableAccessibilityButton;
    boolean mShowRotateButton;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;

    private @NavigationBarCompat.HitTarget int mDownHitTarget = HIT_TARGET_NONE;
    private Rect mHomeButtonBounds = new Rect();
    private Rect mBackButtonBounds = new Rect();
    private Rect mRecentsButtonBounds = new Rect();
    private int[] mTmpPosition = new int[2];

    private KeyButtonDrawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon;
    private KeyButtonDrawable mBackCarModeIcon, mBackLandCarModeIcon;
    private KeyButtonDrawable mBackAltCarModeIcon, mBackAltLandCarModeIcon;
    private KeyButtonDrawable mHomeDefaultIcon, mHomeCarModeIcon;
    private KeyButtonDrawable mRecentIcon;
    private KeyButtonDrawable mDockedIcon;
    private KeyButtonDrawable mImeIcon;
    private KeyButtonDrawable mMenuIcon;
    private KeyButtonDrawable mAccessibilityIcon;
    private TintedKeyButtonDrawable mRotateSuggestionIcon;

    private GestureHelper mGestureHelper;
    private final DeadZone mDeadZone;
    private final NavigationBarTransitions mBarTransitions;
    private final OverviewProxyService mOverviewProxyService;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private OnVerticalChangedListener mOnVerticalChangedListener;
    private boolean mLayoutTransitionsEnabled = true;
    private boolean mWakeAndUnlocking;
    private boolean mUseCarModeUi = false;
    private boolean mInCarMode = false;
    private boolean mDockedStackExists;

    private final SparseArray<ButtonDispatcher> mButtonDispatchers = new SparseArray<>();
    private Configuration mConfiguration;

    private NavigationBarInflaterView mNavigationInflaterView;
    private RecentsComponent mRecentsComponent;
    private Divider mDivider;
    private RecentsOnboarding mRecentsOnboarding;
    private NotificationPanelView mPanelView;

    private int mRotateBtnStyle = R.style.RotateButtonCCWStart90;

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

    private final OnClickListener mImeSwitcherClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            mContext.getSystemService(InputMethodManager.class)
                    .showInputMethodPicker(true /* showAuxiliarySubtypes */);
        }
    };

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = getCurrentView().getWidth();
                    final int vh = getCurrentView().getHeight();

                    if (h != vh || w != vw) {
                        Log.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mDisplay = ((WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();

        mVertical = false;
        mShowMenu = false;

        mShowAccessibilityButton = false;
        mLongClickableAccessibilityButton = false;

        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mRecentsOnboarding = new RecentsOnboarding(context, mOverviewProxyService);

        mConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());
        reloadNavIcons();

        mBarTransitions = new NavigationBarTransitions(this);

        mButtonDispatchers.put(R.id.back, new ButtonDispatcher(R.id.back));
        mButtonDispatchers.put(R.id.home, new ButtonDispatcher(R.id.home));
        mButtonDispatchers.put(R.id.recent_apps, new ButtonDispatcher(R.id.recent_apps));
        mButtonDispatchers.put(R.id.menu, new ButtonDispatcher(R.id.menu));
        mButtonDispatchers.put(R.id.ime_switcher, new ButtonDispatcher(R.id.ime_switcher));
        mButtonDispatchers.put(R.id.accessibility_button,
                new ButtonDispatcher(R.id.accessibility_button));
        mButtonDispatchers.put(R.id.rotate_suggestion,
                new ButtonDispatcher(R.id.rotate_suggestion));
        mDeadZone = new DeadZone(this);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public LightBarTransitionsController getLightTransitionsController() {
        return mBarTransitions.getLightTransitionsController();
    }

    public void setComponents(RecentsComponent recentsComponent, Divider divider,
            NotificationPanelView panel) {
        mRecentsComponent = recentsComponent;
        mDivider = divider;
        mPanelView = panel;
        if (mGestureHelper instanceof NavigationBarGestureHelper) {
            ((NavigationBarGestureHelper) mGestureHelper).setComponents(
                    recentsComponent, divider, this);
        }
    }

    public void setOnVerticalChangedListener(OnVerticalChangedListener onVerticalChangedListener) {
        mOnVerticalChangedListener = onVerticalChangedListener;
        notifyVerticalChangedListener(mVertical);
    }

    public void onQuickStepStarted() {
        if (mRecentsOnboarding != null) {
            mRecentsOnboarding.onQuickStepStarted();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mDeadZone.onTouchEvent(event)) {
            // Consumed the touch event
            return true;
        }
        switch (event.getActionMasked()) {
            case ACTION_DOWN:
                int x = (int) event.getX();
                int y = (int) event.getY();
                mDownHitTarget = HIT_TARGET_NONE;
                if (mBackButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_BACK;
                } else if (mHomeButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_HOME;
                } else if (mRecentsButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_OVERVIEW;
                }
                break;
        }
        return mGestureHelper.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDeadZone.onTouchEvent(event)) {
            // Consumed the touch event
            return true;
        }
        if (mGestureHelper.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    public @NavigationBarCompat.HitTarget int getDownHitTarget() {
        return mDownHitTarget;
    }

    public void abortCurrentGesture() {
        getHomeButton().abortCurrentGesture();
    }

    private H mHandler = new H();

    public View getCurrentView() {
        return mCurrentView;
    }

    public View[] getAllViews() {
        return mRotatedViews;
    }

    public ButtonDispatcher getRecentsButton() {
        return mButtonDispatchers.get(R.id.recent_apps);
    }

    public ButtonDispatcher getMenuButton() {
        return mButtonDispatchers.get(R.id.menu);
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

    public ButtonDispatcher getRotateSuggestionButton() {
        return mButtonDispatchers.get(R.id.rotate_suggestion);
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

    public boolean isQuickScrubEnabled() {
        return SystemProperties.getBoolean("persist.quickstep.scrub.enabled", true)
                && mOverviewProxyService.isEnabled() && isOverviewEnabled()
                && ((mOverviewProxyService.getInteractionFlags() & FLAG_DISABLE_QUICK_SCRUB) == 0);
    }

    private void updateCarModeIcons(Context ctx) {
        mBackCarModeIcon = getDrawable(ctx,
                R.drawable.ic_sysbar_back_carmode, R.drawable.ic_sysbar_back_carmode);
        mBackLandCarModeIcon = mBackCarModeIcon;
        mBackAltCarModeIcon = getDrawable(ctx,
                R.drawable.ic_sysbar_back_ime_carmode, R.drawable.ic_sysbar_back_ime_carmode);
        mBackAltLandCarModeIcon = mBackAltCarModeIcon;
        mHomeCarModeIcon = getDrawable(ctx,
                R.drawable.ic_sysbar_home_carmode, R.drawable.ic_sysbar_home_carmode);
    }

    private void reloadNavIcons() {
        updateIcons(mContext, Configuration.EMPTY, mConfiguration);
    }

    private void updateIcons(Context ctx, Configuration oldConfig, Configuration newConfig) {
        if (oldConfig.orientation != newConfig.orientation
                || oldConfig.densityDpi != newConfig.densityDpi) {
            mDockedIcon = getDrawable(ctx,
                    R.drawable.ic_sysbar_docked, R.drawable.ic_sysbar_docked_dark);
            mHomeDefaultIcon = getHomeDrawable(ctx);
        }
        if (oldConfig.densityDpi != newConfig.densityDpi
                || oldConfig.getLayoutDirection() != newConfig.getLayoutDirection()) {
            mBackIcon = getBackDrawable(ctx);
            mBackLandIcon = mBackIcon;
            mBackAltIcon = getBackImeDrawable(ctx);
            mBackAltLandIcon = mBackAltIcon;
            mRecentIcon = getDrawable(ctx,
                    R.drawable.ic_sysbar_recent, R.drawable.ic_sysbar_recent_dark);
            mMenuIcon = getDrawable(ctx, R.drawable.ic_sysbar_menu, R.drawable.ic_sysbar_menu_dark);

            int dualToneDarkTheme = Utils.getThemeAttr(ctx, R.attr.darkIconTheme);
            int dualToneLightTheme = Utils.getThemeAttr(ctx, R.attr.lightIconTheme);
            Context darkContext = new ContextThemeWrapper(ctx, dualToneDarkTheme);
            Context lightContext = new ContextThemeWrapper(ctx, dualToneLightTheme);

            mAccessibilityIcon = getDrawable(darkContext, lightContext,
                    R.drawable.ic_sysbar_accessibility_button,
                    R.drawable.ic_sysbar_accessibility_button);

            mImeIcon = getDrawable(darkContext, lightContext,
                    R.drawable.ic_ime_switcher_default, R.drawable.ic_ime_switcher_default);

            updateRotateSuggestionButtonStyle(mRotateBtnStyle, false);

            if (ALTERNATE_CAR_MODE_UI) {
                updateCarModeIcons(ctx);
            }
        }
    }

    public KeyButtonDrawable getBackDrawable(Context ctx) {
        return chooseNavigationIconDrawable(ctx, R.drawable.ic_sysbar_back,
                R.drawable.ic_sysbar_back_dark, R.drawable.ic_sysbar_back_quick_step,
                R.drawable.ic_sysbar_back_quick_step_dark);
    }

    public KeyButtonDrawable getBackImeDrawable(Context ctx) {
        return chooseNavigationIconDrawable(ctx, R.drawable.ic_sysbar_back_ime,
                R.drawable.ic_sysbar_back_ime_dark, R.drawable.ic_sysbar_back_ime_quick_step,
                R.drawable.ic_sysbar_back_ime_quick_step_dark);
    }

    public KeyButtonDrawable getHomeDrawable(Context ctx) {
        return chooseNavigationIconDrawable(ctx, R.drawable.ic_sysbar_home,
                R.drawable.ic_sysbar_home_dark, R.drawable.ic_sysbar_home_quick_step,
                R.drawable.ic_sysbar_home_quick_step_dark);
    }

    private KeyButtonDrawable chooseNavigationIconDrawable(Context ctx, @DrawableRes int iconLight,
            @DrawableRes int iconDark, @DrawableRes int quickStepIconLight,
            @DrawableRes int quickStepIconDark) {
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        return quickStepEnabled
                ? getDrawable(ctx, quickStepIconLight, quickStepIconDark)
                : getDrawable(ctx, iconLight, iconDark);
    }

    private KeyButtonDrawable getDrawable(Context ctx, @DrawableRes int lightIcon,
            @DrawableRes int darkIcon) {
        return getDrawable(ctx, ctx, lightIcon, darkIcon);
    }

    private KeyButtonDrawable getDrawable(Context darkContext, Context lightContext,
            @DrawableRes int lightIcon, @DrawableRes int darkIcon) {
        return KeyButtonDrawable.create(lightContext.getDrawable(lightIcon),
                darkContext.getDrawable(darkIcon));
    }

    private TintedKeyButtonDrawable getDrawable(Context ctx, @DrawableRes int icon,
            @ColorInt int lightColor, @ColorInt int darkColor) {
        return TintedKeyButtonDrawable.create(ctx.getDrawable(icon), lightColor, darkColor);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        reloadNavIcons();

        super.setLayoutDirection(layoutDirection);
    }

    private KeyButtonDrawable getBackIconWithAlt(boolean carMode, boolean landscape) {
        return landscape
                ? carMode ? mBackAltLandCarModeIcon : mBackAltLandIcon
                : carMode ? mBackAltCarModeIcon : mBackAltIcon;
    }

    private KeyButtonDrawable getBackIcon(boolean carMode, boolean landscape) {
        return landscape
                ? carMode ? mBackLandCarModeIcon : mBackLandIcon
                : carMode ? mBackCarModeIcon : mBackIcon;
    }

    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 && !backAlt) {
            mTransitionListener.onBackAltCleared();
        }
        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }
        mNavigationIconHints = hints;
        updateNavButtonIcons();
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
    }

    public void updateNavButtonIcons() {
        // We have to replace or restore the back and home button icons when exiting or entering
        // carmode, respectively. Recents are not available in CarMode in nav bar so change
        // to recent icon is not required.
        KeyButtonDrawable backIcon
                = ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0)
                        ? getBackIconWithAlt(mUseCarModeUi, mVertical)
                        : getBackIcon(mUseCarModeUi, mVertical);
        getBackButton().setImageDrawable(backIcon);

        updateRecentsIcon();

        if (mUseCarModeUi) {
            getHomeButton().setImageDrawable(mHomeCarModeIcon);
        } else {
            getHomeButton().setImageDrawable(mHomeDefaultIcon);
        }

        // Update IME button visibility, a11y and rotate button always overrides the appearance
        final boolean showImeButton =
                !mShowAccessibilityButton &&
                        !mShowRotateButton &&
                        ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);
        getImeSwitchButton().setVisibility(showImeButton ? View.VISIBLE : View.INVISIBLE);
        getImeSwitchButton().setImageDrawable(mImeIcon);

        // Update menu button, visibility logic in method
        setMenuVisibility(mShowMenu, true);
        getMenuButton().setImageDrawable(mMenuIcon);

        // Update rotate button, visibility altered by a11y button logic
        getRotateSuggestionButton().setImageDrawable(mRotateSuggestionIcon);

        // Update a11y button, visibility logic in state method
        setAccessibilityButtonState(mShowAccessibilityButton, mLongClickableAccessibilityButton);
        getAccessibilityButton().setImageDrawable(mAccessibilityIcon);

        mBarTransitions.reapplyDarkIntensity();

        boolean disableHome = ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        // Always disable recents when alternate car mode UI is active.
        boolean disableRecent = mUseCarModeUi || !isOverviewEnabled();

        boolean disableBack = ((mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);

        // When screen pinning, don't hide back and home when connected service or back and
        // recents buttons when disconnected from launcher service in screen pinning mode,
        // as they are used for exiting.
        final boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        if (mOverviewProxyService.isEnabled()) {
            // Use interaction flags to show/hide navigation buttons but will be shown if required
            // to exit screen pinning.
            final int flags = mOverviewProxyService.getInteractionFlags();
            disableRecent |= (flags & FLAG_SHOW_OVERVIEW_BUTTON) == 0;
            if (pinningActive) {
                disableBack = disableHome = false;
            } else {
                disableBack |= (flags & FLAG_HIDE_BACK_BUTTON) != 0;
            }
        } else if (pinningActive) {
            disableBack = disableRecent = false;
        }

        ViewGroup navButtons = (ViewGroup) getCurrentView().findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
            }
        }

        getBackButton().setVisibility(disableBack      ? View.INVISIBLE : View.VISIBLE);
        getHomeButton().setVisibility(disableHome      ? View.INVISIBLE : View.VISIBLE);
        getRecentsButton().setVisibility(disableRecent ? View.INVISIBLE : View.VISIBLE);
    }

    public boolean inScreenPinning() {
        return ActivityManagerWrapper.getInstance().isScreenPinningActive();
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
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout((View) getParent(), lp);
        }
    }

    public void onNavigationButtonLongPress(View v) {
        mGestureHelper.onNavigationButtonLongPress(v);
    }

    public void onPanelExpandedChange(boolean expanded) {
        updateSlippery();
    }

    public void updateStates() {
        updateSlippery();
        reloadNavIcons();
        updateNavButtonIcons();
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
    }

    private void updateSlippery() {
        setSlippery(!isQuickStepSwipeUpEnabled() || mPanelView.isFullyExpanded());
    }

    private void setSlippery(boolean slippery) {
        boolean changed = false;
        final ViewGroup navbarView = ((ViewGroup) getParent());
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) navbarView
                .getLayoutParams();
        if (lp == null) {
            return;
        }
        if (slippery && (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) == 0) {
            lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            changed = true;
        } else if (!slippery && (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            changed = true;
        }
        if (changed) {
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(navbarView, lp);
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) return;

        mShowMenu = show;

        // Only show Menu if IME switcher, rotate and Accessibility buttons are not shown.
        final boolean shouldShow = mShowMenu &&
                !mShowAccessibilityButton &&
                !mShowRotateButton &&
                ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0);

        getMenuButton().setVisibility(shouldShow ? View.VISIBLE : View.INVISIBLE);
    }

    public void setAccessibilityButtonState(final boolean visible, final boolean longClickable) {
        mShowAccessibilityButton = visible;
        mLongClickableAccessibilityButton = longClickable;
        if (visible) {
            // Accessibility button overrides Menu, IME switcher and rotate buttons.
            setMenuVisibility(false, true);
            getImeSwitchButton().setVisibility(View.INVISIBLE);
            setRotateButtonVisibility(false);
        }

        getAccessibilityButton().setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        getAccessibilityButton().setLongClickable(longClickable);
    }

    public void updateRotateSuggestionButtonStyle(@StyleRes int style, boolean setIcon) {
        mRotateBtnStyle = style;
        final Context ctx = getContext();

        // Extract the dark and light tints
        final int dualToneDarkTheme = Utils.getThemeAttr(ctx, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(ctx, R.attr.lightIconTheme);
        Context darkContext = new ContextThemeWrapper(ctx, dualToneDarkTheme);
        Context lightContext = new ContextThemeWrapper(ctx, dualToneLightTheme);
        final int lightColor = Utils.getColorAttr(lightContext, R.attr.singleToneColor);
        final int darkColor = Utils.getColorAttr(darkContext, R.attr.singleToneColor);

        // Use the supplied style to set the icon's rotation parameters
        Context rotateContext = new ContextThemeWrapper(ctx, style);

        // Recreate the icon and set it if needed
        TintedKeyButtonDrawable priorIcon = mRotateSuggestionIcon;
        mRotateSuggestionIcon = getDrawable(rotateContext, R.drawable.ic_sysbar_rotate_button,
                lightColor, darkColor);

        // Apply any prior set dark intensity
        if (priorIcon != null && priorIcon.isDarkIntensitySet()) {
            mRotateSuggestionIcon.setDarkIntensity(priorIcon.getDarkIntensity());
        }

        if (setIcon) getRotateSuggestionButton().setImageDrawable(mRotateSuggestionIcon);
    }

    public void setRotateButtonVisibility(final boolean visible) {
        // Never show if a11y is visible
        final boolean adjVisible = visible && !mShowAccessibilityButton;
        final int vis = adjVisible ? View.VISIBLE : View.INVISIBLE;

        // No need to do anything if the request matches the current state
        if (vis == getRotateSuggestionButton().getVisibility()) return;

        getRotateSuggestionButton().setVisibility(vis);
        mShowRotateButton = visible;

        // Stop any active animations if hidden
        if (!visible) {
            Drawable d = mRotateSuggestionIcon.getDrawable(0);
            if (d instanceof AnimatedVectorDrawable) {
                AnimatedVectorDrawable avd = (AnimatedVectorDrawable) d;
                avd.clearAnimationCallbacks();
                avd.reset();
            }
        }

        // Hide/restore other button visibility, if necessary
        updateNavButtonIcons();
    }

    public boolean isRotateButtonVisible() { return mShowRotateButton; }

    @Override
    public void onFinishInflate() {
        mNavigationInflaterView = (NavigationBarInflaterView) findViewById(
                R.id.navigation_inflater);
        mNavigationInflaterView.setButtonDispatchers(mButtonDispatchers);

        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        DockedStackExistsListener.register(mDockedListener);
        updateRotatedViews();
    }

    public void onDarkIntensityChange(float intensity) {
        if (mGestureHelper != null) {
            mGestureHelper.onDarkIntensityChange(intensity);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mGestureHelper.onDraw(canvas);
        mDeadZone.onDraw(canvas);
        super.onDraw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateButtonLocationOnScreen(getBackButton(), mBackButtonBounds);
        updateButtonLocationOnScreen(getHomeButton(), mHomeButtonBounds);
        updateButtonLocationOnScreen(getRecentsButton(), mRecentsButtonBounds);
        mGestureHelper.onLayout(changed, left, top, right, bottom);
        mRecentsOnboarding.setNavBarHeight(getMeasuredHeight());
    }

    private void updateButtonLocationOnScreen(ButtonDispatcher button, Rect buttonBounds) {
        View view = button.getCurrentView();
        if (view == null) {
            buttonBounds.setEmpty();
            return;
        }
        // Temporarily reset the translation back to origin to get the position in window
        final float posX = view.getTranslationX();
        final float posY = view.getTranslationY();
        view.setTranslationX(0);
        view.setTranslationY(0);
        view.getLocationInWindow(mTmpPosition);
        buttonBounds.set(mTmpPosition[0], mTmpPosition[1],
                mTmpPosition[0] + view.getMeasuredWidth(),
                mTmpPosition[1] + view.getMeasuredHeight());
        view.setTranslationX(posX);
        view.setTranslationY(posY);
    }

    private void updateRotatedViews() {
        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);
        mRotatedViews[Surface.ROTATION_270] =
                mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);

        updateCurrentView();
    }

    public boolean needsReorient(int rotation) {
        return mCurrentRotation != rotation;
    }

    private void updateCurrentView() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        mNavigationInflaterView.setAlternativeOrder(rot == Surface.ROTATION_90);
        for (int i = 0; i < mButtonDispatchers.size(); i++) {
            mButtonDispatchers.valueAt(i).setCurrentView(mCurrentView);
        }
        updateLayoutTransitionsEnabled();
        mCurrentRotation = rot;
    }

    private void updateRecentsIcon() {
        getRecentsButton().setImageDrawable(mDockedStackExists ? mDockedIcon : mRecentIcon);
        mBarTransitions.reapplyDarkIntensity();
    }

    public boolean isVertical() {
        return mVertical;
    }

    public void reorient() {
        updateCurrentView();

        ((NavigationBarFrame) getRootView()).setDeadZone(mDeadZone);
        mDeadZone.onConfigurationChanged(mCurrentRotation);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mCurrentRotation);
        }

        // Resolve layout direction if not resolved since components changing layout direction such
        // as changing languages will recreate this view and the direction will be resolved later
        if (!isLayoutDirectionResolved()) {
            resolveLayoutDirection();
        }
        updateTaskSwitchHelper();
        updateNavButtonIcons();

        getHomeButton().setVertical(mVertical);
    }

    private void updateTaskSwitchHelper() {
        if (mGestureHelper == null) return;
        boolean isRtl = (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        mGestureHelper.setBarState(mVertical, isRtl);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Log.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
            notifyVerticalChangedListener(newVertical);
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    private void notifyVerticalChangedListener(boolean newVertical) {
        if (mOnVerticalChangedListener != null) {
            mOnVerticalChangedListener.onVerticalChanged(newVertical);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean uiCarModeChanged = updateCarMode(newConfig);
        updateTaskSwitchHelper();
        updateIcons(getContext(), mConfiguration, newConfig);
        updateRecentsIcon();
        mRecentsOnboarding.onConfigurationChanged(newConfig);
        if (uiCarModeChanged || mConfiguration.densityDpi != newConfig.densityDpi
                || mConfiguration.getLayoutDirection() != newConfig.getLayoutDirection()) {
            // If car mode or density changes, we need to reset the icons.
            updateNavButtonIcons();
        }
        mConfiguration.updateFrom(newConfig);
    }

    /**
     * If the configuration changed, update the carmode and return that it was updated.
     */
    private boolean updateCarMode(Configuration newConfig) {
        boolean uiCarModeChanged = false;
        if (newConfig != null) {
            int uiMode = newConfig.uiMode & Configuration.UI_MODE_TYPE_MASK;
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

    /*
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onLayout: %s (%d,%d,%d,%d)",
                    changed?"changed":"notchanged", left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);
    }

    // uncomment this for extra defensiveness in WORKAROUND_INVALID_LAYOUT situations: if all else
    // fails, any touch on the display will fix the layout.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.d(TAG, "onInterceptTouchEvent: " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            postCheckForInvalidLayout("touch");
        }
        return super.onInterceptTouchEvent(ev);
    }
    */


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

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
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
        requestApplyInsets();
        reorient();
        onPluginDisconnected(null); // Create default gesture helper
        Dependency.get(PluginManager.class).addPluginListener(this,
                NavGesture.class, false /* Only one */);
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(PluginManager.class).removePluginListener(this);
        if (mGestureHelper != null) {
            mGestureHelper.destroy();
        }
        setUpSwipeUpOnboarding(false);
    }

    private void setUpSwipeUpOnboarding(boolean connectedToOverviewProxy) {
        if (connectedToOverviewProxy) {
            mRecentsOnboarding.onConnectedToLauncher();
        } else {
            mRecentsOnboarding.onDisconnectedFromLauncher();
        }
    }

    @Override
    public void onPluginConnected(NavGesture plugin, Context context) {
        mGestureHelper = plugin.getGestureHelper();
        updateTaskSwitchHelper();
    }

    @Override
    public void onPluginDisconnected(NavGesture plugin) {
        NavigationBarGestureHelper defaultHelper = new NavigationBarGestureHelper(getContext());
        defaultHelper.setComponents(mRecentsComponent, mDivider, this);
        if (mGestureHelper != null) {
            mGestureHelper.destroy();
        }
        mGestureHelper = defaultHelper;
        updateTaskSwitchHelper();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + StatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(getCurrentView().getId()),
                        getCurrentView().getWidth(), getCurrentView().getHeight(),
                        visibilityToString(getCurrentView().getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mShowMenu ? "true" : "false"));

        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "menu", getMenuButton());
        dumpButton(pw, "a11y", getAccessibilityButton());

        pw.println("    }");
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        return super.onApplyWindowInsets(insets);
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

    private final Consumer<Boolean> mDockedListener = exists -> mHandler.post(() -> {
        mDockedStackExists = exists;
        updateRecentsIcon();
    });
}
