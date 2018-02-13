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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
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
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.DockedStackExistsListener;
import com.android.systemui.Interpolators;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.phone.NavGesture;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.recents.SwipeUpOnboarding;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.TintedKeyButtonDrawable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

public class NavigationBarView extends FrameLayout implements PluginListener<NavGesture> {
    final static boolean DEBUG = false;
    final static String TAG = "StatusBar/NavBarView";

    final static int BUTTON_FADE_IN_OUT_DURATION_MS = 100;

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

    private KeyButtonDrawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon;
    private KeyButtonDrawable mBackCarModeIcon, mBackLandCarModeIcon;
    private KeyButtonDrawable mBackAltCarModeIcon, mBackAltLandCarModeIcon;
    private KeyButtonDrawable mHomeDefaultIcon, mHomeCarModeIcon;
    private KeyButtonDrawable mRecentIcon;
    private KeyButtonDrawable mDockedIcon;
    private KeyButtonDrawable mImeIcon;
    private KeyButtonDrawable mMenuIcon;
    private KeyButtonDrawable mAccessibilityIcon;
    private KeyButtonDrawable mRotateSuggestionIcon;

    private GestureHelper mGestureHelper;
    private DeadZone mDeadZone;
    private final NavigationBarTransitions mBarTransitions;
    private final OverviewProxyService mOverviewProxyService;
    private boolean mRecentsAnimationStarted;

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
    private SwipeUpOnboarding mSwipeUpOnboarding;
    private NotificationPanelView mPanelView;

    private Animator mRotateHideAnimator;

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

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            updateSlippery();
            setDisabledFlags(mDisabledFlags, true);
            setUpSwipeUpOnboarding(isConnected);
        }

        @Override
        public void onRecentsAnimationStarted() {
            mRecentsAnimationStarted = true;
            if (mSwipeUpOnboarding != null) {
                mSwipeUpOnboarding.onRecentsAnimationStarted();
            }
        }
    };

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mDisplay = ((WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();

        mVertical = false;
        mShowMenu = false;

        mShowAccessibilityButton = false;
        mLongClickableAccessibilityButton = false;

        mConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());
        updateIcons(context, Configuration.EMPTY, mConfiguration);

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

        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mSwipeUpOnboarding = new SwipeUpOnboarding(context);
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mGestureHelper.onTouchEvent(event)) {
            return true;
        }
        return mRecentsAnimationStarted || super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mRecentsAnimationStarted = false;
        } else if (action == MotionEvent.ACTION_UP) {
            // If the overview proxy service has not started the recents animation then clean up
            // after it to ensure that the nav bar buttons still work
            if (mOverviewProxyService.getProxy() != null && !mRecentsAnimationStarted) {
                try {
                    ActivityManager.getService().cancelRecentsAnimation();
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not cancel recents animation");
                }
            }
        }
        return mGestureHelper.onInterceptTouchEvent(event) || mRecentsAnimationStarted;
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

    private void updateIcons(Context ctx, Configuration oldConfig, Configuration newConfig) {
        if (oldConfig.orientation != newConfig.orientation
                || oldConfig.densityDpi != newConfig.densityDpi) {
            mDockedIcon = getDrawable(ctx,
                    R.drawable.ic_sysbar_docked, R.drawable.ic_sysbar_docked_dark);
        }
        if (oldConfig.densityDpi != newConfig.densityDpi
                || oldConfig.getLayoutDirection() != newConfig.getLayoutDirection()) {
            mBackIcon = getDrawable(ctx, R.drawable.ic_sysbar_back, R.drawable.ic_sysbar_back_dark);
            mBackLandIcon = mBackIcon;
            mBackAltIcon = getDrawable(ctx,
                    R.drawable.ic_sysbar_back_ime, R.drawable.ic_sysbar_back_ime_dark);
            mBackAltLandIcon = mBackAltIcon;

            mHomeDefaultIcon = getDrawable(ctx,
                    R.drawable.ic_sysbar_home, R.drawable.ic_sysbar_home_dark);
            mRecentIcon = getDrawable(ctx,
                    R.drawable.ic_sysbar_recent, R.drawable.ic_sysbar_recent_dark);
            mMenuIcon = getDrawable(ctx, R.drawable.ic_sysbar_menu, R.drawable.ic_sysbar_menu_dark);
            mAccessibilityIcon = getDrawable(ctx, R.drawable.ic_sysbar_accessibility_button,
                    R.drawable.ic_sysbar_accessibility_button_dark);

            int dualToneDarkTheme = Utils.getThemeAttr(ctx, R.attr.darkIconTheme);
            int dualToneLightTheme = Utils.getThemeAttr(ctx, R.attr.lightIconTheme);
            Context darkContext = new ContextThemeWrapper(ctx, dualToneDarkTheme);
            Context lightContext = new ContextThemeWrapper(ctx, dualToneLightTheme);
            mImeIcon = getDrawable(darkContext, lightContext,
                    R.drawable.ic_ime_switcher_default, R.drawable.ic_ime_switcher_default);

            int lightColor = Utils.getColorAttr(lightContext, R.attr.singleToneColor);
            int darkColor = Utils.getColorAttr(darkContext, R.attr.singleToneColor);
            mRotateSuggestionIcon = getDrawable(ctx, R.drawable.ic_sysbar_rotate_button,
                    lightColor, darkColor);

            if (ALTERNATE_CAR_MODE_UI) {
                updateCarModeIcons(ctx);
            }
        }
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

    private KeyButtonDrawable getDrawable(Context ctx, @DrawableRes int icon,
            @ColorInt int lightColor, @ColorInt int darkColor) {
        return TintedKeyButtonDrawable.create(ctx.getDrawable(icon), lightColor, darkColor);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        // Reload all the icons
        updateIcons(getContext(), Configuration.EMPTY, mConfiguration);

        super.setLayoutDirection(layoutDirection);
    }

    public void notifyScreenOn() {
        setDisabledFlags(mDisabledFlags, true);
    }

    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
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

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;
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

        // We have to replace or restore the back and home button icons when exiting or entering
        // carmode, respectively. Recents are not available in CarMode in nav bar so change
        // to recent icon is not required.
        KeyButtonDrawable backIcon = (backAlt)
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
                ((hints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);
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

        setDisabledFlags(mDisabledFlags, true);

        mBarTransitions.reapplyDarkIntensity();
    }

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        // Always disable recents when alternate car mode UI is active.
        boolean disableRecent = mUseCarModeUi
                || ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);

        boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);

        if ((disableRecent || disableBack) && inScreenPinning()) {
            // Don't hide back and recents buttons when in screen pinning mode, as they are used for
            // exiting.
            disableBack = false;
            disableRecent = false;
        }
        if (mOverviewProxyService.getProxy() != null) {
            // When overview is connected to the launcher service, disable the recents button
            disableRecent = true;
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

    private boolean inScreenPinning() {
        try {
            return ActivityManager.getService().getLockTaskModeState()
                    == ActivityManager.LOCK_TASK_MODE_PINNED;
        } catch (RemoteException e) {
            return false;
        }
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

    public void onPanelExpandedChange(boolean expanded) {
        updateSlippery();
    }

    private void updateSlippery() {
        setSlippery(mOverviewProxyService.getProxy() != null && mPanelView.isFullyExpanded());
    }

    private void setSlippery(boolean slippery) {
        boolean changed = false;
        final ViewGroup navbarView = ((ViewGroup) getParent());
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) navbarView
                .getLayoutParams();
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
            setRotateSuggestionButtonState(false, true);
        }

        getAccessibilityButton().setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        getAccessibilityButton().setLongClickable(longClickable);
    }

    public void setRotateSuggestionButtonState(final boolean visible) {
        setRotateSuggestionButtonState(visible, false);
    }

    public void setRotateSuggestionButtonState(final boolean visible, final boolean force) {
        ButtonDispatcher rotBtn = getRotateSuggestionButton();
        final boolean currentlyVisible = mShowRotateButton;

        // Rerun a show animation to indicate change but don't rerun a hide animation
        if (!visible && !currentlyVisible) return;

        View currentView = rotBtn.getCurrentView();
        if (currentView == null) return;

        KeyButtonDrawable kbd = rotBtn.getImageDrawable();
        if (kbd == null) return;

        AnimatedVectorDrawable animIcon = null;
        if (kbd.getDrawable(0) instanceof AnimatedVectorDrawable) {
            animIcon = (AnimatedVectorDrawable) kbd.getDrawable(0);
        }

        if (visible) { // Appear and change, cannot force
            setRotateButtonVisibility(true);

            // Stop any currently running hide animations
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) {
                mRotateHideAnimator.pause();
            }

            // Reset the alpha if any has changed due to hide animation
            currentView.setAlpha(1f);

            // Run the rotate icon's animation if it has one
            if (animIcon != null) {
                animIcon.reset();
                animIcon.start();
            }

        } else { // Hide
            if (force) {
                // If a hide animator is running stop it and instantly make invisible
                if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) {
                    mRotateHideAnimator.pause();
                }
                setRotateButtonVisibility(false);
                return;
            }

            // Don't start any new hide animations if one is running
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) return;

            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(currentView, "alpha",
                    0f);
            fadeOut.setDuration(BUTTON_FADE_IN_OUT_DURATION_MS);
            fadeOut.setInterpolator(Interpolators.LINEAR);
            fadeOut.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    setRotateButtonVisibility(false);
                }
            });

            mRotateHideAnimator = fadeOut;
            fadeOut.start();
        }
    }

    private void setRotateButtonVisibility(final boolean visible) {
        // Never show if a11y is visible
        final boolean adjVisible = visible && !mShowAccessibilityButton;
        final int vis = adjVisible ? View.VISIBLE : View.INVISIBLE;

        getRotateSuggestionButton().setVisibility(vis);
        mShowRotateButton = visible;

        // Hide/restore other button visibility, if necessary
        setNavigationIconHints(mNavigationIconHints, true);
    }

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
        if (mSwipeUpOnboarding != null) {
            mSwipeUpOnboarding.setContentDarkIntensity(intensity);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mGestureHelper.onDraw(canvas);
        super.onDraw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mGestureHelper.onLayout(changed, left, top, right, bottom);
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

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        ((NavigationBarFrame) getRootView()).setDeadZone(mDeadZone);
        mDeadZone.setDisplayRotation(mCurrentRotation);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mCurrentRotation);
        }

        updateTaskSwitchHelper();
        setNavigationIconHints(mNavigationIconHints, true);

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
        mSwipeUpOnboarding.onConfigurationChanged(newConfig);
        if (uiCarModeChanged || mConfiguration.densityDpi != newConfig.densityDpi
                || mConfiguration.getLayoutDirection() != newConfig.getLayoutDirection()) {
            // If car mode or density changes, we need to reset the icons.
            setNavigationIconHints(mNavigationIconHints, true);
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
        reorient();
        onPluginDisconnected(null); // Create default gesture helper
        Dependency.get(PluginManager.class).addPluginListener(this,
                NavGesture.class, false /* Only one */);
        mOverviewProxyService.addCallback(mOverviewProxyListener);
        setUpSwipeUpOnboarding(mOverviewProxyService.getProxy() != null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(PluginManager.class).removePluginListener(this);
        if (mGestureHelper != null) {
            mGestureHelper.destroy();
        }
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
        setUpSwipeUpOnboarding(false);
    }

    private void setUpSwipeUpOnboarding(boolean connectedToOverviewProxy) {
        if (connectedToOverviewProxy) {
            mSwipeUpOnboarding.onConnectedToLauncher();
        } else {
            mSwipeUpOnboarding.onDisconnectedFromLauncher();
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
