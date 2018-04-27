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

import static com.android.internal.view.RotationPolicy.NATURAL_ROTATION;

import static com.android.systemui.shared.system.NavigationBarCompat.InteractionType;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.StatusBar.dumpBarTransitions;
import static com.android.systemui.OverviewProxyService.OverviewProxyListener;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.IdRes;
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
import android.graphics.drawable.AnimatedVectorDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import androidx.annotation.VisibleForTesting;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.IRotationWatcher.Stub;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
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
import com.android.internal.util.LatencyTracker;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SysUiTaskStackChangeListener;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fragment containing the NavigationBarFragment. Contains logic for what happens
 * on clicks and view states of the nav bar.
 */
public class NavigationBarFragment extends Fragment implements Callbacks {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_ROTATION = true;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";
    private static final String EXTRA_DISABLE2_STATE = "disabled2_state";

    private final static int BUTTON_FADE_IN_OUT_DURATION_MS = 100;
    private final static int NAVBAR_HIDDEN_PENDING_ICON_TIMEOUT_MS = 20000;

    private static final int NUM_ACCEPTED_ROTATION_SUGGESTIONS_FOR_INTRODUCTION = 3;

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;

    protected NavigationBarView mNavigationBarView = null;
    protected AssistManager mAssistManager;

    private int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private int mNavigationBarMode;
    private boolean mAccessibilityFeedbackEnabled;
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

    public boolean mHomeBlockedThisTouch;

    private int mLastRotationSuggestion;
    private boolean mPendingRotationSuggestion;
    private boolean mHoveringRotationSuggestion;
    private RotationLockController mRotationLockController;
    private TaskStackListenerImpl mTaskStackListener;

    private final Runnable mRemoveRotationProposal = () -> setRotateSuggestionButtonState(false);
    private final Runnable mCancelPendingRotationProposal =
            () -> mPendingRotationSuggestion = false;
    private Animator mRotateHideAnimator;
    private ViewRippler mViewRippler = new ViewRippler();

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();
        }

        @Override
        public void onQuickStepStarted() {
            // Use navbar dragging as a signal to hide the rotate button
            setRotateSuggestionButtonState(false);
        }

        @Override
        public void onInteractionFlagsChanged(@InteractionType int flags) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();
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

        try {
            WindowManagerGlobal.getWindowManagerService()
                    .watchRotation(mRotationWatcher, getContext().getDisplay().getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mRotationLockController = Dependency.get(RotationLockController.class);

        // Reset user rotation pref to match that of the WindowManager if starting in locked mode
        // This will automatically happen when switching from auto-rotate to locked mode
        if (mRotationLockController.isRotationLocked()) {
            final int winRotation = mWindowManager.getDefaultDisplay().getRotation();
            mRotationLockController.setRotationLockedAtAngle(true, winRotation);
        }

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl();
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);
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

        // Unregister the task stack listener
        ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskStackListener);
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
        mNavigationBarView.setComponents(mRecents, mDivider, mStatusBar.getPanel());
        mNavigationBarView.setOnVerticalChangedListener(this::onVerticalChanged);
        mNavigationBarView.setOnTouchListener(this::onNavigationTouch);
        if (savedInstanceState != null) {
            mNavigationBarView.getLightTransitionsController().restoreState(savedInstanceState);
        }

        prepareNavigationBarView();
        checkNavBarModes();

        setDisabled2Flags(mDisabledFlags2);

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        notifyNavigationBarScreenOn();
        mOverviewProxyService.addCallback(mOverviewProxyListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mNavigationBarView.getLightTransitionsController().destroy(getContext());
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

            // If the navbar is visible, show the rotate button if there's a pending suggestion
            if (state == WINDOW_STATE_SHOWING && mPendingRotationSuggestion) {
                showAndLogRotationSuggestion();
            }
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        final int winRotation = mWindowManager.getDefaultDisplay().getRotation();
        final boolean rotateSuggestionsDisabled = hasDisable2RotateSuggestionFlag(mDisabledFlags2);
        if (DEBUG_ROTATION) {
            Log.v(TAG, "onRotationProposal proposedRotation=" + Surface.rotationToString(rotation)
                    + ", winRotation=" + Surface.rotationToString(winRotation)
                    + ", isValid=" + isValid + ", mNavBarWindowState="
                    + StatusBarManager.windowStateToString(mNavigationBarWindowState)
                    + ", rotateSuggestionsDisabled=" + rotateSuggestionsDisabled
                    + ", isRotateButtonVisible=" + (mNavigationBarView == null ? "null" :
                        mNavigationBarView.isRotateButtonVisible()));
        }

        // Respect the disabled flag, no need for action as flag change callback will handle hiding
        if (rotateSuggestionsDisabled) return;

        // This method will be called on rotation suggestion changes even if the proposed rotation
        // is not valid for the top app. Use invalid rotation choices as a signal to remove the
        // rotate button if shown.
        if (!isValid) {
            setRotateSuggestionButtonState(false);
            return;
        }

        // If window rotation matches suggested rotation, remove any current suggestions
        if (rotation == winRotation) {
            getView().removeCallbacks(mRemoveRotationProposal);
            setRotateSuggestionButtonState(false);
            return;
        }

        // Prepare to show the navbar icon by updating the icon style to change anim params
        mLastRotationSuggestion = rotation; // Remember rotation for click
        if (mNavigationBarView != null) {
            final boolean rotationCCW = isRotationAnimationCCW(winRotation, rotation);
            int style;
            if (winRotation == Surface.ROTATION_0 || winRotation == Surface.ROTATION_180) {
                style = rotationCCW ? R.style.RotateButtonCCWStart90 :
                        R.style.RotateButtonCWStart90;
            } else { // 90 or 270
                style = rotationCCW ? R.style.RotateButtonCCWStart0 :
                        R.style.RotateButtonCWStart0;
            }
            mNavigationBarView.updateRotateSuggestionButtonStyle(style, true);
        }

        if (mNavigationBarWindowState != WINDOW_STATE_SHOWING) {
            // If the navbar isn't shown, flag the rotate icon to be shown should the navbar become
            // visible given some time limit.
            mPendingRotationSuggestion = true;
            getView().removeCallbacks(mCancelPendingRotationProposal);
            getView().postDelayed(mCancelPendingRotationProposal,
                    NAVBAR_HIDDEN_PENDING_ICON_TIMEOUT_MS);

        } else { // The navbar is visible so show the icon right away
            showAndLogRotationSuggestion();
        }
    }

    private void onRotationSuggestionsDisabled() {
        // Immediately hide the rotate button and clear any planned removal
        setRotateSuggestionButtonState(false, true);

        // This method can be called before view setup is done, ensure getView isn't null
        final View v = getView();
        if (v != null) v.removeCallbacks(mRemoveRotationProposal);
    }

    private void showAndLogRotationSuggestion() {
        setRotateSuggestionButtonState(true);
        rescheduleRotationTimeout(false);
        mMetricsLogger.visible(MetricsEvent.ROTATION_SUGGESTION_SHOWN);
    }

    private boolean isRotationAnimationCCW(int from, int to) {
        // All 180deg WM rotation animations are CCW, match that
        if (from == Surface.ROTATION_0 && to == Surface.ROTATION_90) return false;
        if (from == Surface.ROTATION_0 && to == Surface.ROTATION_180) return true; //180d so CCW
        if (from == Surface.ROTATION_0 && to == Surface.ROTATION_270) return true;
        if (from == Surface.ROTATION_90 && to == Surface.ROTATION_0) return true;
        if (from == Surface.ROTATION_90 && to == Surface.ROTATION_180) return false;
        if (from == Surface.ROTATION_90 && to == Surface.ROTATION_270) return true; //180d so CCW
        if (from == Surface.ROTATION_180 && to == Surface.ROTATION_0) return true; //180d so CCW
        if (from == Surface.ROTATION_180 && to == Surface.ROTATION_90) return true;
        if (from == Surface.ROTATION_180 && to == Surface.ROTATION_270) return false;
        if (from == Surface.ROTATION_270 && to == Surface.ROTATION_0) return false;
        if (from == Surface.ROTATION_270 && to == Surface.ROTATION_90) return true; //180d so CCW
        if (from == Surface.ROTATION_270 && to == Surface.ROTATION_180) return true;
        return false; // Default
    }

    public void setRotateSuggestionButtonState(final boolean visible) {
        setRotateSuggestionButtonState(visible, false);
    }

    public void setRotateSuggestionButtonState(final boolean visible, final boolean force) {
        if (mNavigationBarView == null) return;

        // At any point the the button can become invisible because an a11y service became active.
        // Similarly, a call to make the button visible may be rejected because an a11y service is
        // active. Must account for this.

        ButtonDispatcher rotBtn = mNavigationBarView.getRotateSuggestionButton();
        final boolean currentlyVisible = mNavigationBarView.isRotateButtonVisible();

        // Rerun a show animation to indicate change but don't rerun a hide animation
        if (!visible && !currentlyVisible) return;

        View view = rotBtn.getCurrentView();
        if (view == null) return;

        KeyButtonDrawable kbd = rotBtn.getImageDrawable();
        if (kbd == null) return;

        // The KBD and AVD is recreated every new valid suggestion because of style changes.
        AnimatedVectorDrawable animIcon = null;
        if (kbd.getDrawable(0) instanceof AnimatedVectorDrawable) {
            animIcon = (AnimatedVectorDrawable) kbd.getDrawable(0);
        }

        // Clear any pending suggestion flag as it has either been nullified or is being shown
        mPendingRotationSuggestion = false;
        if (getView() != null) getView().removeCallbacks(mCancelPendingRotationProposal);

        // Handle the visibility change and animation
        if (visible) { // Appear and change (cannot force)
            // Stop and clear any currently running hide animations
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) {
                mRotateHideAnimator.cancel();
            }
            mRotateHideAnimator = null;

            // Reset the alpha if any has changed due to hide animation
            view.setAlpha(1f);

            // Run the rotate icon's animation if it has one
            if (animIcon != null) {
                animIcon.reset();
                animIcon.start();
            }

            if (!isRotateSuggestionIntroduced()) mViewRippler.start(view);

            // Set visibility, may fail if a11y service is active.
            // If invisible, call will stop animation.
            int appliedVisibility = mNavigationBarView.setRotateButtonVisibility(true);
            if (appliedVisibility == View.VISIBLE) {
                // If the button will actually become visible and the navbar is about to hide,
                // tell the statusbar to keep it around for longer
                mStatusBar.touchAutoHide();
            }

        } else { // Hide

            mViewRippler.stop(); // Prevent any pending ripples, force hide or not

            if (force) {
                // If a hide animator is running stop it and make invisible
                if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) {
                    mRotateHideAnimator.pause();
                }
                mNavigationBarView.setRotateButtonVisibility(false);
                return;
            }

            // Don't start any new hide animations if one is running
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) return;

            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(view, "alpha",
                    0f);
            fadeOut.setDuration(BUTTON_FADE_IN_OUT_DURATION_MS);
            fadeOut.setInterpolator(Interpolators.LINEAR);
            fadeOut.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mNavigationBarView.setRotateButtonVisibility(false);
                }
            });

            mRotateHideAnimator = fadeOut;
            fadeOut.start();
        }
    }

    private void rescheduleRotationTimeout(final boolean reasonHover) {
        // May be called due to a new rotation proposal or a change in hover state
        if (reasonHover) {
            // Don't reschedule if a hide animator is running
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) return;
            // Don't reschedule if not visible
            if (!mNavigationBarView.isRotateButtonVisible()) return;
        }

        getView().removeCallbacks(mRemoveRotationProposal); // Stop any pending removal
        getView().postDelayed(mRemoveRotationProposal,
                computeRotationProposalTimeout()); // Schedule timeout
    }

    private int computeRotationProposalTimeout() {
        if (mAccessibilityFeedbackEnabled) return 20000;
        if (mHoveringRotationSuggestion) return 16000;
        return 6000;
    }

    private boolean isRotateSuggestionIntroduced() {
        ContentResolver cr = getContext().getContentResolver();
        return Settings.Secure.getInt(cr, Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED, 0)
                >= NUM_ACCEPTED_ROTATION_SUGGESTIONS_FOR_INTRODUCTION;
    }

    private void incrementNumAcceptedRotationSuggestionsIfNeeded() {
        // Get the number of accepted suggestions
        ContentResolver cr = getContext().getContentResolver();
        final int numSuggestions = Settings.Secure.getInt(cr,
                Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED, 0);

        // Increment the number of accepted suggestions only if it would change intro mode
        if (numSuggestions < NUM_ACCEPTED_ROTATION_SUGGESTIONS_FOR_INTRODUCTION) {
            Settings.Secure.putInt(cr, Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED,
                    numSuggestions + 1);
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

        final int masked2 = state2 & (StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS);
        if (masked2 != mDisabledFlags2) {
            mDisabledFlags2 = masked2;
            setDisabled2Flags(masked2);
        }
    }

    private void setDisabled2Flags(int state2) {
        // Method only called on change of disable2 flags
        final boolean rotateSuggestionsDisabled = hasDisable2RotateSuggestionFlag(state2);
        if (rotateSuggestionsDisabled) onRotationSuggestionsDisabled();
    }

    private boolean hasDisable2RotateSuggestionFlag(int disable2Flags) {
        return (disable2Flags & StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS) != 0;
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

        ButtonDispatcher rotateSuggestionButton = mNavigationBarView.getRotateSuggestionButton();
        rotateSuggestionButton.setOnClickListener(this::onRotateSuggestionClick);
        rotateSuggestionButton.setOnHoverListener(this::onRotateSuggestionHover);
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
            IActivityManager activityManager = ActivityManagerNative.getDefault();
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
        if (mRecents == null || !ActivityManager.supportsMultiWindow(getContext())
                || !mDivider.getView().getSnapAlgorithm().isSplitScreenFeasible()
                || Recents.getConfiguration().isLowRamDevice
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

        mAccessibilityFeedbackEnabled = feedbackEnabled;

        final boolean showAccessibilityButton = requestingServices >= 1;
        final boolean targetSelection = requestingServices >= 2;
        mNavigationBarView.setAccessibilityButtonState(showAccessibilityButton, targetSelection);
    }

    private void onRotateSuggestionClick(View v) {
        mMetricsLogger.action(MetricsEvent.ACTION_ROTATION_SUGGESTION_ACCEPTED);
        incrementNumAcceptedRotationSuggestionsIfNeeded();
        mRotationLockController.setRotationLockedAtAngle(true, mLastRotationSuggestion);
    }

    private boolean onRotateSuggestionHover(View v, MotionEvent event) {
        final int action = event.getActionMasked();
        mHoveringRotationSuggestion = (action == MotionEvent.ACTION_HOVER_ENTER)
                || (action == MotionEvent.ACTION_HOVER_MOVE);
        rescheduleRotationTimeout(true);
        return false; // Must return false so a11y hover events are dispatched correctly.
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
        public void onRotationChanged(final int rotation) throws RemoteException {
            // We need this to be scheduled as early as possible to beat the redrawing of
            // window in response to the orientation change.
            Handler h = getView().getHandler();
            Message msg = Message.obtain(h, () -> {

                // If the screen rotation changes while locked, potentially update lock to flow with
                // new screen rotation and hide any showing suggestions.
                if (mRotationLockController.isRotationLocked()) {
                    if (shouldOverrideUserLockPrefs(rotation)) {
                        mRotationLockController.setRotationLockedAtAngle(true, rotation);
                    }
                    setRotateSuggestionButtonState(false, true);
                }

                if (mNavigationBarView != null
                        && mNavigationBarView.needsReorient(rotation)) {
                    repositionNavigationBar();
                }
            });
            msg.setAsynchronous(true);
            h.sendMessageAtFrontOfQueue(msg);
        }

        private boolean shouldOverrideUserLockPrefs(final int rotation) {
            // Only override user prefs when returning to the natural rotation (normally portrait).
            // Don't let apps that force landscape or 180 alter user lock.
            return rotation == NATURAL_ROTATION;
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

    class TaskStackListenerImpl extends SysUiTaskStackChangeListener {
        // Invalidate any rotation suggestion on task change or activity orientation change
        // Note: all callbacks happen on main thread

        @Override
        public void onTaskStackChanged() {
            setRotateSuggestionButtonState(false);
        }

        @Override
        public void onTaskRemoved(int taskId) {
            setRotateSuggestionButtonState(false);
        }

        @Override
        public void onTaskMovedToFront(int taskId) {
            setRotateSuggestionButtonState(false);
        }

        @Override
        public void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation) {
            // Only hide the icon if the top task changes its requestedOrientation
            // Launcher can alter its requestedOrientation while it's not on top, don't hide on this
            Optional.ofNullable(ActivityManagerWrapper.getInstance())
                    .map(ActivityManagerWrapper::getRunningTask)
                    .ifPresent(a -> {
                        if (a.id == taskId) setRotateSuggestionButtonState(false);
                    });
        }
    }

    private class ViewRippler {
        private static final int RIPPLE_OFFSET_MS = 50;
        private static final int RIPPLE_INTERVAL_MS = 2000;
        private View mRoot;

        public void start(View root) {
            stop(); // Stop any pending ripple animations

            mRoot = root;

            // Schedule pending ripples, offset the 1st to avoid problems with visibility change
            mRoot.postOnAnimationDelayed(mRipple, RIPPLE_OFFSET_MS);
            mRoot.postOnAnimationDelayed(mRipple, RIPPLE_INTERVAL_MS);
            mRoot.postOnAnimationDelayed(mRipple, 2*RIPPLE_INTERVAL_MS);
        }

        public void stop() {
            if (mRoot != null) mRoot.removeCallbacks(mRipple);
        }

        private final Runnable mRipple = new Runnable() {
            @Override
            public void run() { // Cause the ripple to fire via false presses
                if (!mRoot.isAttachedToWindow()) return;
                mRoot.setPressed(true);
                mRoot.setPressed(false);
            }
        };
    }

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
        lp.accessibilityTitle = context.getString(R.string.nav_bar);
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
