/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.windowStateToString;

import static com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import static com.android.systemui.shared.system.NavigationBarCompat.InteractionType;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.StatusBar.dumpBarTransitions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IdRes;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Fragment;
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
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.ContextualButton.ContextButtonListener;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Fragment containing the NavigationBarFragment. Contains logic for what happens
 * on clicks and view states of the nav bar.
 */
public class NavigationBarFragment extends Fragment implements Callbacks {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";
    private static final String EXTRA_DISABLE2_STATE = "disabled2_state";

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;

    private final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);

    protected NavigationBarView mNavigationBarView = null;
    protected AssistManager mAssistManager;

    private int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private int mNavigationBarMode;
    private AccessibilityManager mAccessibilityManager;
    private MagnificationContentObserver mMagnificationObserver;
    private ContentResolver mContentResolver;
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    private int mDisabledFlags1;
    private int mDisabledFlags2;
    private StatusBar mStatusBar;
    private Recents mRecents;
    private Divider mDivider;
    private WindowManager mWindowManager;
    private CommandQueue mCommandQueue;
    private long mLastLockToAppLongPress;

    private Locale mLocale;
    private int mLayoutDirection;

    private int mSystemUiVisibility;
    private LightBarController mLightBarController;

    private OverviewProxyService mOverviewProxyService;

    private boolean mIsOnDefaultDisplay = true;
    public boolean mHomeBlockedThisTouch;

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();
        }

        @Override
        public void onQuickStepStarted() {
            // Use navbar dragging as a signal to hide the rotate button
            mNavigationBarView.getRotateSuggestionButton().setRotateSuggestionButtonState(false);

            // Hide the notifications panel when quick step starts
            mStatusBar.collapsePanel(true /* animate */);
        }

        @Override
        public void onInteractionFlagsChanged(@InteractionType int flags) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();
        }

        @Override
        public void onBackButtonAlphaChanged(float alpha, boolean animate) {
            final ButtonDispatcher backButton = mNavigationBarView.getBackButton();
            if (QuickStepController.shouldhideBackButton(getContext())) {
                // If property was changed to hide/show back button, going home will trigger
                // launcher to to change the back button alpha to reflect property change
                backButton.setVisibility(View.GONE);
            } else {
                backButton.setVisibility(alpha > 0 ? View.VISIBLE : View.INVISIBLE);
                backButton.setAlpha(alpha, animate);
            }
        }
    };

    private final ContextButtonListener mRotationButtonListener = new ContextButtonListener() {
        @Override
        public void onVisibilityChanged(ContextualButton button, boolean visible) {
            if (visible) {
                // If the button will actually become visible and the navbar is about to hide,
                // tell the statusbar to keep it around for longer
                mStatusBar.touchAutoHide();
            }
        }
    };

    // ----- Fragment Lifecycle Callbacks -----

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCommandQueue = SysUiServiceProvider.getComponent(getContext(), CommandQueue.class);
        mCommandQueue.addCallbacks(this);
        mStatusBar = SysUiServiceProvider.getComponent(getContext(), StatusBar.class);
        mRecents = SysUiServiceProvider.getComponent(getContext(), Recents.class);
        mDivider = SysUiServiceProvider.getComponent(getContext(), Divider.class);
        mWindowManager = getContext().getSystemService(WindowManager.class);
        mAccessibilityManager = getContext().getSystemService(AccessibilityManager.class);
        Dependency.get(AccessibilityManagerWrapper.class).addCallback(
                mAccessibilityListener);
        mContentResolver = getContext().getContentResolver();
        mMagnificationObserver = new MagnificationContentObserver(
                getContext().getMainThreadHandler());
        mContentResolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED), false,
                mMagnificationObserver, UserHandle.USER_ALL);

        if (savedInstanceState != null) {
            mDisabledFlags1 = savedInstanceState.getInt(EXTRA_DISABLE_STATE, 0);
            mDisabledFlags2 = savedInstanceState.getInt(EXTRA_DISABLE2_STATE, 0);
        }
        mAssistManager = Dependency.get(AssistManager.class);
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mCommandQueue.removeCallbacks(this);
        Dependency.get(AccessibilityManagerWrapper.class).removeCallback(
                mAccessibilityListener);
        mContentResolver.unregisterContentObserver(mMagnificationObserver);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.navigation_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mNavigationBarView = (NavigationBarView) view;
        final Display display = view.getDisplay();
        // It may not have display when running unit test.
        if (display != null) {
            mIsOnDefaultDisplay = display.getDisplayId() == Display.DEFAULT_DISPLAY;
        }

        mNavigationBarView.setComponents(mStatusBar.getPanel());
        mNavigationBarView.setDisabledFlags(mDisabledFlags1);
        mNavigationBarView.setOnVerticalChangedListener(this::onVerticalChanged);
        mNavigationBarView.setOnTouchListener(this::onNavigationTouch);
        if (savedInstanceState != null) {
            mNavigationBarView.getLightTransitionsController().restoreState(savedInstanceState);
        }

        prepareNavigationBarView();
        checkNavBarModes();

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        notifyNavigationBarScreenOn();
        mOverviewProxyService.addCallback(mOverviewProxyListener);

        // Currently there is no accelerometer sensor on non-default display.
        if (mIsOnDefaultDisplay) {
            final RotationContextButton rotationButton =
                    mNavigationBarView.getRotateSuggestionButton();
            rotationButton.setListener(mRotationButtonListener);
            rotationButton.addRotationCallback(mRotationWatcher);

            // Reset user rotation pref to match that of the WindowManager if starting in locked
            // mode. This will automatically happen when switching from auto-rotate to locked mode.
            if (display != null && rotationButton.isRotationLocked()) {
                final int winRotation = display.getRotation();
                rotationButton.setRotationLockedAtAngle(winRotation);
            }
        } else {
            mDisabledFlags2 |= StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS;
        }
        setDisabled2Flags(mDisabledFlags2);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().destroy();
            mNavigationBarView.getLightTransitionsController().destroy(getContext());
        }
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
        getContext().unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_DISABLE_STATE, mDisabledFlags1);
        outState.putInt(EXTRA_DISABLE2_STATE, mDisabledFlags2);
        if (mNavigationBarView != null) {
            mNavigationBarView.getLightTransitionsController().saveState(outState);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final Locale locale = getContext().getResources().getConfiguration().locale;
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
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mNavigationBarView != null) {
            pw.print("  mNavigationBarWindowState=");
            pw.println(windowStateToString(mNavigationBarWindowState));
            pw.print("  mNavigationBarMode=");
            pw.println(BarTransitions.modeToString(mNavigationBarMode));
            dumpBarTransitions(pw, "mNavigationBarView", mNavigationBarView.getBarTransitions());
        }

        pw.print("  mNavigationBarView=");
        if (mNavigationBarView == null) {
            pw.println("null");
        } else {
            mNavigationBarView.dump(fd, pw, args);
        }
    }

    // ----- CommandQueue Callbacks -----

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
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
        mStatusBar.checkBarModes();
    }

    @Override
    public void topAppWindowChanged(boolean showMenu) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setMenuVisibility(showMenu);
        }
    }

    @Override
    public void setWindowState(int window, int state) {
        if (mNavigationBarView != null
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));

            mNavigationBarView.getRotateSuggestionButton()
                    .onNavigationBarWindowVisibilityChange(state == WINDOW_STATE_SHOWING);
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        final int winRotation = mNavigationBarView.getDisplay().getRotation();
        final boolean rotateSuggestionsDisabled = RotationContextButton
                .hasDisable2RotateSuggestionFlag(mDisabledFlags2);
        if (RotationContextButton.DEBUG_ROTATION) {
            Log.v(TAG, "onRotationProposal proposedRotation=" + Surface.rotationToString(rotation)
                    + ", winRotation=" + Surface.rotationToString(winRotation)
                    + ", isValid=" + isValid + ", mNavBarWindowState="
                    + StatusBarManager.windowStateToString(mNavigationBarWindowState)
                    + ", rotateSuggestionsDisabled=" + rotateSuggestionsDisabled
                    + ", isRotateButtonVisible=" + (mNavigationBarView == null ? "null" :
                        mNavigationBarView.getRotateSuggestionButton().isVisible()));
        }

        // Respect the disabled flag, no need for action as flag change callback will handle hiding
        if (rotateSuggestionsDisabled) return;

        mNavigationBarView.getRotateSuggestionButton()
                .onRotationProposal(rotation, winRotation, isValid);
    }

    // Injected from StatusBar at creation.
    public void setCurrentSysuiVisibility(int systemUiVisibility) {
        mSystemUiVisibility = systemUiVisibility;
        final int barMode = mStatusBar.computeBarMode(0, mSystemUiVisibility,
                View.NAVIGATION_BAR_TRANSIENT, View.NAVIGATION_BAR_TRANSLUCENT,
                View.NAVIGATION_BAR_TRANSPARENT);
        if (barMode != -1) {
            mNavigationBarMode = barMode;
        }
        checkNavBarModes();
        mStatusBar.touchAutoHide();

        // TODO(115978725): Support light bar controller on external nav bars.
        if (mLightBarController != null) {
            mLightBarController.onNavigationVisibilityChanged(mSystemUiVisibility, 0 /* mask */,
                    true /* nbModeChanged */, mNavigationBarMode);
        }
    }

    @Override
    public void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal & ~mask) | (vis & mask);
        final int diff = newVal ^ oldVal;
        boolean nbModeChanged = false;
        if (diff != 0) {
            mSystemUiVisibility = newVal;

            // update navigation bar mode
            final int nbMode = getView() == null
                    ? -1 : mStatusBar.computeBarMode(oldVal, newVal,
                    View.NAVIGATION_BAR_TRANSIENT, View.NAVIGATION_BAR_TRANSLUCENT,
                    View.NAVIGATION_BAR_TRANSPARENT);
            nbModeChanged = nbMode != -1;
            if (nbModeChanged) {
                if (mNavigationBarMode != nbMode) {
                    if (mNavigationBarMode == MODE_TRANSPARENT
                            || mNavigationBarMode == MODE_LIGHTS_OUT_TRANSPARENT) {
                        mNavigationBarView.hideRecentsOnboarding();
                    }
                    mNavigationBarMode = nbMode;
                    checkNavBarModes();
                }
                mStatusBar.touchAutoHide();
            }
        }

        // TODO(115978725): Support light bar controller on external nav bars.
        if (mLightBarController != null) {
            mLightBarController.onNavigationVisibilityChanged(vis, mask, nbModeChanged,
                    mNavigationBarMode);
        }
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        // Navigation bar flags are in both state1 and state2.
        final int masked = state1 & (StatusBarManager.DISABLE_HOME
                | StatusBarManager.DISABLE_RECENT
                | StatusBarManager.DISABLE_BACK
                | StatusBarManager.DISABLE_SEARCH);
        if (masked != mDisabledFlags1) {
            mDisabledFlags1 = masked;
            if (mNavigationBarView != null) mNavigationBarView.setDisabledFlags(state1);
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
            mNavigationBarView.getRotateSuggestionButton().onDisable2FlagChanged(state2);
        }
    }

    // ----- Internal stuffz -----

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
        boolean recentsVisible = mNavigationBarView.isRecentsButtonVisible();
        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        if (recentsVisible) {
            backButton.setOnLongClickListener(this::onLongPressBackRecents);
        } else {
            backButton.setOnLongClickListener(this::onLongPressBackHome);
        }
    }

    private void notifyNavigationBarScreenOn() {
        mNavigationBarView.updateNavButtonIcons();
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();

        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        recentsButton.setOnClickListener(this::onRecentsClick);
        recentsButton.setOnTouchListener(this::onRecentsTouch);
        recentsButton.setLongClickable(true);
        recentsButton.setOnLongClickListener(this::onLongPressBackRecents);

        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        backButton.setLongClickable(true);

        ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        homeButton.setOnTouchListener(this::onHomeTouch);
        homeButton.setOnLongClickListener(this::onHomeLongClick);

        ButtonDispatcher accessibilityButton = mNavigationBarView.getAccessibilityButton();
        accessibilityButton.setOnClickListener(this::onAccessibilityClick);
        accessibilityButton.setOnLongClickListener(this::onAccessibilityLongClick);
        updateAccessibilityServicesState(mAccessibilityManager);

        updateScreenPinningGestures();
    }

    private boolean onHomeTouch(View v, MotionEvent event) {
        if (mHomeBlockedThisTouch && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        // If an incoming call is ringing, HOME is totally disabled.
        // (The user is already on the InCallUI at this point,
        // and his ONLY options are to answer or reject the call.)
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mHomeBlockedThisTouch = false;
                TelecomManager telecomManager =
                        getContext().getSystemService(TelecomManager.class);
                if (telecomManager != null && telecomManager.isRinging()) {
                    if (mStatusBar.isKeyguardShowing()) {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call. " +
                                "No heads up");
                        mHomeBlockedThisTouch = true;
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mStatusBar.awakenDreams();
                break;
        }
        return false;
    }

    private void onVerticalChanged(boolean isVertical) {
        mStatusBar.setQsScrimEnabled(!isVertical);
    }

    private boolean onNavigationTouch(View v, MotionEvent event) {
        mStatusBar.checkUserAutohide(event);
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
        mNavigationBarView.onNavigationButtonLongPress(v);
        mMetricsLogger.action(MetricsEvent.ACTION_ASSIST_LONG_PRESS);
        mAssistManager.startAssist(new Bundle() /* args */);
        mStatusBar.awakenDreams();

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
        if (LatencyTracker.isEnabled(getContext())) {
            LatencyTracker.getInstance(getContext()).onActionStart(
                    LatencyTracker.ACTION_TOGGLE_RECENTS);
        }
        mStatusBar.awakenDreams();
        mCommandQueue.toggleRecentApps();
    }

    private boolean onLongPressBackHome(View v) {
        mNavigationBarView.onNavigationButtonLongPress(v);
        return onLongPressNavigationButtons(v, R.id.back, R.id.home);
    }

    private boolean onLongPressBackRecents(View v) {
        mNavigationBarView.onNavigationButtonLongPress(v);
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
            if (inLockTaskMode && !touchExplorationEnabled) {
                long time = System.currentTimeMillis();

                // If we recently long-pressed the other button then they were
                // long-pressed 'together'
                if ((time - mLastLockToAppLongPress) < LOCK_TO_APP_GESTURE_TOLERENCE) {
                    activityManager.stopSystemLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.updateNavButtonIcons();
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
                    activityManager.stopSystemLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.updateNavButtonIcons();
                    return true;
                } else if (v.getId() == btnId2) {
                    return btnId2 == R.id.recent_apps
                            ? onLongPressRecents()
                            : onHomeLongClick(mNavigationBarView.getHomeButton().getCurrentView());
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
        if (mRecents == null || !ActivityTaskManager.supportsMultiWindow(getContext())
                || !mDivider.getView().getSnapAlgorithm().isSplitScreenFeasible()
                || ActivityManager.isLowRamDeviceStatic()
                // If we are connected to the overview service, then disable the recents button
                || mOverviewProxyService.getProxy() != null) {
            return false;
        }

        return mStatusBar.toggleSplitScreenMode(MetricsEvent.ACTION_WINDOW_DOCK_LONGPRESS,
                MetricsEvent.ACTION_WINDOW_UNDOCK_LONGPRESS);
    }

    private void onAccessibilityClick(View v) {
        mAccessibilityManager.notifyAccessibilityButtonClicked();
    }

    private boolean onAccessibilityLongClick(View v) {
        Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        v.getContext().startActivityAsUser(intent, UserHandle.CURRENT);
        return true;
    }

    private void updateAccessibilityServicesState(AccessibilityManager accessibilityManager) {
        int requestingServices = 0;
        try {
            if (Settings.Secure.getIntForUser(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                    UserHandle.USER_CURRENT) == 1) {
                requestingServices++;
            }
        } catch (Settings.SettingNotFoundException e) {
        }

        boolean feedbackEnabled = false;
        // AccessibilityManagerService resolves services for the current user since the local
        // AccessibilityManager is created from a Context with the INTERACT_ACROSS_USERS permission
        final List<AccessibilityServiceInfo> services =
                accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (int i = services.size() - 1; i >= 0; --i) {
            AccessibilityServiceInfo info = services.get(i);
            if ((info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0) {
                requestingServices++;
            }

            if (info.feedbackType != 0 && info.feedbackType !=
                    AccessibilityServiceInfo.FEEDBACK_GENERIC) {
                feedbackEnabled = true;
            }
        }

        mNavigationBarView.getRotateSuggestionButton()
                .setAccessibilityFeedbackEnabled(feedbackEnabled);

        final boolean showAccessibilityButton = requestingServices >= 1;
        final boolean targetSelection = requestingServices >= 2;
        mNavigationBarView.setAccessibilityButtonState(showAccessibilityButton, targetSelection);
    }

    // ----- Methods that StatusBar talks to (should be minimized) -----

    public void setLightBarController(LightBarController lightBarController) {
        mLightBarController = lightBarController;
        mLightBarController.setNavigationBar(mNavigationBarView.getLightTransitionsController());
    }

    public boolean isSemiTransparent() {
        return mNavigationBarMode == MODE_SEMI_TRANSPARENT;
    }

    public void disableAnimationsDuringHide(long delay) {
        mNavigationBarView.setLayoutTransitionsEnabled(false);
        mNavigationBarView.postDelayed(() -> mNavigationBarView.setLayoutTransitionsEnabled(true),
                delay + StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE);
    }

    public BarTransitions getBarTransitions() {
        return mNavigationBarView.getBarTransitions();
    }

    public void checkNavBarModes() {
        mStatusBar.checkBarMode(mNavigationBarMode,
                mNavigationBarWindowState, mNavigationBarView.getBarTransitions());
    }

    public void finishBarAnimations() {
        mNavigationBarView.getBarTransitions().finishAnimations();
    }

    private final AccessibilityServicesStateChangeListener mAccessibilityListener =
            this::updateAccessibilityServicesState;

    private class MagnificationContentObserver extends ContentObserver {

        public MagnificationContentObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            NavigationBarFragment.this.updateAccessibilityServicesState(mAccessibilityManager);
        }
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
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)) {
                notifyNavigationBarScreenOn();
            }
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                // The accessibility settings may be different for the new user
                updateAccessibilityServicesState(mAccessibilityManager);
            };
        }
    };

    public static View create(Context context, FragmentListener listener) {
        final int displayId = context.getDisplay().getDisplayId();
        final boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
        final int height = isDefaultDisplay
                ? LayoutParams.MATCH_PARENT
                : context.getResources().getDimensionPixelSize(R.dimen.navigation_bar_height);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, height,
                // TODO(b/117478341): Resolve one status bar/ navigation bar assumption
                isDefaultDisplay
                        ? WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
                        : WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle("NavigationBar" + displayId);
        lp.accessibilityTitle = context.getString(R.string.nav_bar);
        lp.windowAnimations = 0;
        if (!isDefaultDisplay) {
            lp.flags |= LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
            lp.gravity = Gravity.BOTTOM;
        }

        View navigationBarView = LayoutInflater.from(context).inflate(
                R.layout.navigation_bar_window, null);

        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + navigationBarView);
        if (navigationBarView == null) return null;

        final NavigationBarFragment fragment = new NavigationBarFragment();
        navigationBarView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                final FragmentHostManager fragmentHost = FragmentHostManager.get(v);
                fragmentHost.getFragmentManager().beginTransaction()
                        .replace(R.id.navigation_bar_frame, fragment, TAG)
                        .commit();
                fragmentHost.addTagListener(TAG, listener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                FragmentHostManager.removeAndDestroy(v);
            }
        });
        context.getSystemService(WindowManager.class).addView(navigationBarView, lp);
        return navigationBarView;
    }
}
