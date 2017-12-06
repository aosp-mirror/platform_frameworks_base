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

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.StatusBar.dumpBarTransitions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.Fragment;
import android.app.IActivityManager;
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
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.IRotationWatcher.Stub;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.keyguard.LatencyTracker;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.recents.Recents;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Fragment containing the NavigationBarFragment. Contains logic for what happens
 * on clicks and view states of the nav bar.
 */
public class NavigationBarFragment extends Fragment implements Callbacks {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;

    protected NavigationBarView mNavigationBarView = null;
    protected AssistManager mAssistManager;

    private int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private int mNavigationBarMode;
    private AccessibilityManager mAccessibilityManager;
    private MagnificationContentObserver mMagnificationObserver;
    private ContentResolver mContentResolver;

    private int mDisabledFlags1;
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

    public boolean mHomeBlockedThisTouch;

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
        }
        mAssistManager = Dependency.get(AssistManager.class);

        try {
            WindowManagerGlobal.getWindowManagerService()
                    .watchRotation(mRotationWatcher, getContext().getDisplay().getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mCommandQueue.removeCallbacks(this);
        Dependency.get(AccessibilityManagerWrapper.class).removeCallback(
                mAccessibilityListener);
        mContentResolver.unregisterContentObserver(mMagnificationObserver);
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .removeRotationWatcher(mRotationWatcher);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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

        mNavigationBarView.setDisabledFlags(mDisabledFlags1);
        mNavigationBarView.setComponents(mRecents, mDivider);
        mNavigationBarView.setOnVerticalChangedListener(this::onVerticalChanged);
        mNavigationBarView.setOnTouchListener(this::onNavigationTouch);
        if (savedInstanceState != null) {
            mNavigationBarView.getLightTransitionsController().restoreState(savedInstanceState);
        }

        prepareNavigationBarView();
        checkNavBarModes();

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        notifyNavigationBarScreenOn();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mNavigationBarView.getLightTransitionsController().destroy(getContext());
        getContext().unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_DISABLE_STATE, mDisabledFlags1);
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
        if ((backDisposition == InputMethodService.BACK_DISPOSITION_WILL_DISMISS) || imeShown) {
            hints |= NAVIGATION_HINT_BACK_ALT;
        } else {
            hints &= ~NAVIGATION_HINT_BACK_ALT;
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
        }
    }

    // Injected from StatusBar at creation.
    public void setCurrentSysuiVisibility(int systemUiVisibility) {
        mSystemUiVisibility = systemUiVisibility;
        mNavigationBarMode = mStatusBar.computeBarMode(0, mSystemUiVisibility,
                View.NAVIGATION_BAR_TRANSIENT, View.NAVIGATION_BAR_TRANSLUCENT,
                View.NAVIGATION_BAR_TRANSPARENT);
        checkNavBarModes();
        mStatusBar.touchAutoHide();
        mLightBarController.onNavigationVisibilityChanged(mSystemUiVisibility, 0 /* mask */,
                true /* nbModeChanged */, mNavigationBarMode);
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
                    mNavigationBarMode = nbMode;
                    checkNavBarModes();
                }
                mStatusBar.touchAutoHide();
            }
        }

        mLightBarController.onNavigationVisibilityChanged(vis, mask, nbModeChanged,
                mNavigationBarMode);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        // All navigation bar flags are in state1.
        int masked = state1 & (StatusBarManager.DISABLE_HOME
                | StatusBarManager.DISABLE_RECENT
                | StatusBarManager.DISABLE_BACK
                | StatusBarManager.DISABLE_SEARCH);
        if (masked != mDisabledFlags1) {
            mDisabledFlags1 = masked;
            if (mNavigationBarView != null) mNavigationBarView.setDisabledFlags(state1);
        }
    }

    // ----- Internal stuffz -----

    private void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }
    }

    private boolean shouldDisableNavbarGestures() {
        return !mStatusBar.isDeviceProvisioned()
                || (mDisabledFlags1 & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private void repositionNavigationBar() {
        if (mNavigationBarView == null || !mNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout((View) mNavigationBarView.getParent(),
                ((View) mNavigationBarView.getParent()).getLayoutParams());
    }

    private void notifyNavigationBarScreenOn() {
        mNavigationBarView.notifyScreenOn();
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
        backButton.setOnLongClickListener(this::onLongPressBackRecents);

        ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        homeButton.setOnTouchListener(this::onHomeTouch);
        homeButton.setOnLongClickListener(this::onHomeLongClick);

        ButtonDispatcher accessibilityButton = mNavigationBarView.getAccessibilityButton();
        accessibilityButton.setOnClickListener(this::onAccessibilityClick);
        accessibilityButton.setOnLongClickListener(this::onAccessibilityLongClick);
        updateAccessibilityServicesState(mAccessibilityManager);
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
        mStatusBar.checkUserAutohide(v, event);
        return false;
    }

    @VisibleForTesting
    boolean onHomeLongClick(View v) {
        if (shouldDisableNavbarGestures()) {
            return false;
        }
        MetricsLogger.action(getContext(), MetricsEvent.ACTION_ASSIST_LONG_PRESS);
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

    /**
     * This handles long-press of both back and recents.  They are
     * handled together to capture them both being long-pressed
     * at the same time to exit screen pinning (lock task).
     *
     * When accessibility mode is on, only a long-press from recents
     * is required to exit.
     *
     * In all other circumstances we try to pass through long-press events
     * for Back, so that apps can still use it.  Which can be from two things.
     * 1) Not currently in screen pinning (lock task).
     * 2) Back is long-pressed without recents.
     */
    private boolean onLongPressBackRecents(View v) {
        try {
            boolean sendBackLongPress = false;
            IActivityManager activityManager = ActivityManagerNative.getDefault();
            boolean touchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();
            boolean inLockTaskMode = activityManager.isInLockTaskMode();
            if (inLockTaskMode && !touchExplorationEnabled) {
                long time = System.currentTimeMillis();
                // If we recently long-pressed the other button then they were
                // long-pressed 'together'
                if ((time - mLastLockToAppLongPress) < LOCK_TO_APP_GESTURE_TOLERENCE) {
                    activityManager.stopLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.setDisabledFlags(mDisabledFlags1, true);
                    return true;
                } else if ((v.getId() == R.id.back)
                        && !mNavigationBarView.getRecentsButton().getCurrentView().isPressed()) {
                    // If we aren't pressing recents right now then they presses
                    // won't be together, so send the standard long-press action.
                    sendBackLongPress = true;
                }
                mLastLockToAppLongPress = time;
            } else {
                // If this is back still need to handle sending the long-press event.
                if (v.getId() == R.id.back) {
                    sendBackLongPress = true;
                } else if (touchExplorationEnabled && inLockTaskMode) {
                    // When in accessibility mode a long press that is recents (not back)
                    // should stop lock task.
                    activityManager.stopLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.setDisabledFlags(mDisabledFlags1, true);
                    return true;
                } else if (v.getId() == R.id.recent_apps) {
                    return onLongPressRecents();
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
        if (mRecents == null || !ActivityManager.supportsMultiWindow(getContext())
                || !mDivider.getView().getSnapAlgorithm().isSplitScreenFeasible()
                || Recents.getConfiguration().isLowRamDevice) {
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
        }

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

    private final Stub mRotationWatcher = new Stub() {
        @Override
        public void onRotationChanged(int rotation) throws RemoteException {
            // We need this to be scheduled as early as possible to beat the redrawing of
            // window in response to the orientation change.
            Handler h = getView().getHandler();
            Message msg = Message.obtain(h, () -> {
                if (mNavigationBarView != null
                        && mNavigationBarView.needsReorient(rotation)) {
                    repositionNavigationBar();
                }
            });
            msg.setAsynchronous(true);
            h.sendMessageAtFrontOfQueue(msg);
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
        }
    };

    public static View create(Context context, FragmentListener listener) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle("NavigationBar");
        lp.windowAnimations = 0;

        View navigationBarView = LayoutInflater.from(context).inflate(
                R.layout.navigation_bar_window, null);

        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + navigationBarView);
        if (navigationBarView == null) return null;

        context.getSystemService(WindowManager.class).addView(navigationBarView, lp);
        FragmentHostManager fragmentHost = FragmentHostManager.get(navigationBarView);
        NavigationBarFragment fragment = new NavigationBarFragment();
        fragmentHost.getFragmentManager().beginTransaction()
                .replace(R.id.navigation_bar_frame, fragment, TAG)
                .commit();
        fragmentHost.addTagListener(TAG, listener);
        return navigationBarView;
    }
}
