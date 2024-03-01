/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsets.Type.navigationBars;

import static com.android.systemui.Flags.predictiveBackAnimateBouncer;
import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN;
import static com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;
import static com.android.systemui.util.kotlin.JavaAdapterKt.combineFlows;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowInsetsController;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.AuthKeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.TrustGrantFlags;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.bouncer.ui.BouncerView;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.KeyguardWmStateRefactor;
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardSurfaceBehindInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.domain.interactor.WindowManagerLockscreenVisibilityInteractor;
import com.android.systemui.keyguard.shared.model.DismissAction;
import com.android.systemui.keyguard.shared.model.KeyguardDone;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.TaskbarDelegate;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionListener;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeLockscreenInteractor;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.unfold.FoldAodAnimationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.ExperimentalCoroutinesApi;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewController}.
 */
@ExperimentalCoroutinesApi @SysUISingleton
public class StatusBarKeyguardViewManager implements RemoteInputController.Callback,
        StatusBarStateController.StateListener, ConfigurationController.ConfigurationListener,
        ShadeExpansionListener, NavigationModeController.ModeChangedListener,
        KeyguardViewController, FoldAodAnimationController.FoldAodAnimationStatus {

    // When hiding the Keyguard with timing supplied from WindowManager, better be early than late.
    private static final long HIDE_TIMING_CORRECTION_MS = - 16 * 3;

    // Delay for showing the navigation bar when the bouncer appears. This should be kept in sync
    // with the appear animations of the PIN/pattern/password views.
    private static final long NAV_BAR_SHOW_DELAY_BOUNCER = 320;

    // The duration to fade the nav bar content in/out when the device starts to sleep
    private static final long NAV_BAR_CONTENT_FADE_DURATION = 125;

    // Duration of the Keyguard dismissal animation in case the user is currently locked. This is to
    // make everything a bit slower to bridge a gap until the user is unlocked and home screen has
    // dranw its first frame.
    private static final long KEYGUARD_DISMISS_DURATION_LOCKED = 2000;

    private static final String TAG = "StatusBarKeyguardViewManager";
    private static final boolean DEBUG = false;

    protected final Context mContext;
    private final ConfigurationController mConfigurationController;
    private final NavigationModeController mNavigationModeController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    private final DreamOverlayStateController mDreamOverlayStateController;
    @Nullable
    private final FoldAodAnimationController mFoldAodAnimationController;
    KeyguardMessageAreaController<AuthKeyguardMessageArea> mKeyguardMessageAreaController;
    private final PrimaryBouncerCallbackInteractor mPrimaryBouncerCallbackInteractor;
    private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;
    private final BouncerView mPrimaryBouncerView;
    private final Lazy<ShadeController> mShadeController;

    // Local cache of expansion events, to avoid duplicates
    private float mFraction = -1f;
    private boolean mTracking = false;
    private boolean mBouncerShowingOverDream;

    private final PrimaryBouncerExpansionCallback mExpansionCallback =
            new PrimaryBouncerExpansionCallback() {
            private boolean mPrimaryBouncerAnimating;

            @Override
            public void onFullyShown() {
                mPrimaryBouncerAnimating = false;
                updateStates();
            }

            @Override
            public void onStartingToHide() {
                mPrimaryBouncerAnimating = true;
                updateStates();
            }

            @Override
            public void onStartingToShow() {
                mPrimaryBouncerAnimating = true;
                updateStates();
            }

            @Override
            public void onFullyHidden() {
                mPrimaryBouncerAnimating = false;
                updateStates();
            }

            @Override
            public void onExpansionChanged(float expansion) {
                if (mPrimaryBouncerAnimating) {
                    mCentralSurfaces.setPrimaryBouncerHiddenFraction(expansion);
                }
            }

            @Override
            public void onVisibilityChanged(boolean isVisible) {
                mBouncerShowingOverDream =
                        isVisible && mDreamOverlayStateController.isOverlayActive();

                if (!isVisible) {
                    mCentralSurfaces.setPrimaryBouncerHiddenFraction(EXPANSION_HIDDEN);
                }

                /* Register predictive back callback when keyguard becomes visible, and unregister
                when it's hidden. */
                if (isVisible) {
                    registerBackCallback();
                } else {
                    unregisterBackCallback();
                }
            }
    };

    private final OnBackAnimationCallback mOnBackInvokedCallback = new OnBackAnimationCallback() {
        @Override
        public void onBackInvoked() {
            if (DEBUG) {
                Log.d(TAG, "onBackInvokedCallback() called, invoking onBackPressed()");
            }
            onBackPressed();
            if (shouldPlayBackAnimation() && mPrimaryBouncerView.getDelegate() != null) {
                mPrimaryBouncerView.getDelegate().getBackCallback().onBackInvoked();
            }
        }

        @Override
        public void onBackProgressed(BackEvent event) {
            if (shouldPlayBackAnimation() && mPrimaryBouncerView.getDelegate() != null) {
                mPrimaryBouncerView.getDelegate().getBackCallback().onBackProgressed(event);
            }
        }

        @Override
        public void onBackCancelled() {
            if (shouldPlayBackAnimation() && mPrimaryBouncerView.getDelegate() != null) {
                mPrimaryBouncerView.getDelegate().getBackCallback().onBackCancelled();
            }
        }

        @Override
        public void onBackStarted(BackEvent event) {
            if (shouldPlayBackAnimation() && mPrimaryBouncerView.getDelegate() != null) {
                mPrimaryBouncerView.getDelegate().getBackCallback().onBackStarted(event);
            }
        }
    };
    private boolean mIsBackCallbackRegistered = false;

    private final DockManager.DockEventListener mDockEventListener =
            new DockManager.DockEventListener() {
                @Override
                public void onEvent(int event) {
                    boolean isDocked = mDockManager.isDocked();
            if (isDocked == mIsDocked) {
                return;
            }
            mIsDocked = isDocked;
            updateStates();
        }
    };

    protected LockPatternUtils mLockPatternUtils;
    protected ViewMediatorCallback mViewMediatorCallback;
    @Nullable protected CentralSurfaces mCentralSurfaces;
    private ShadeLockscreenInteractor mShadeLockscreenInteractor;
    private BiometricUnlockController mBiometricUnlockController;
    private boolean mCentralSurfacesRegistered;

    private View mNotificationContainer;

    protected boolean mRemoteInputActive;
    private boolean mGlobalActionsVisible = false;
    private boolean mLastGlobalActionsVisible = false;
    private boolean mDozing;
    private boolean mPulsing;
    private boolean mGesturalNav;
    private boolean mIsDocked;
    private boolean mScreenOffAnimationPlaying;

    protected boolean mFirstUpdate = true;
    protected boolean mLastShowing;
    protected boolean mLastOccluded;
    private boolean mLastPrimaryBouncerShowing;
    private boolean mLastPrimaryBouncerIsOrWillBeShowing;
    private boolean mLastBouncerDismissible;
    protected boolean mLastRemoteInputActive;
    private boolean mLastDozing;
    private boolean mLastGesturalNav;
    private boolean mLastIsDocked;
    private boolean mLastPulsing;
    private int mLastBiometricMode;
    private boolean mLastScreenOffAnimationPlaying;
    private float mQsExpansion;

    private FeatureFlags mFlags;

    final Set<KeyguardViewManagerCallback> mCallbacks = new HashSet<>();
    private boolean mIsBackAnimationEnabled;
    private final UdfpsOverlayInteractor mUdfpsOverlayInteractor;
    private final ActivityStarter mActivityStarter;

    private OnDismissAction mAfterKeyguardGoneAction;
    private Runnable mKeyguardGoneCancelAction;
    private boolean mDismissActionWillAnimateOnKeyguard;
    private final ArrayList<Runnable> mAfterKeyguardGoneRunnables = new ArrayList<>();

    // Dismiss action to be launched when we stop dozing or the keyguard is gone.
    private DismissWithActionRequest mPendingWakeupAction;
    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final DockManager mDockManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateManager;
    private final LatencyTracker mLatencyTracker;
    private final KeyguardSecurityModel mKeyguardSecurityModel;
    private final SelectedUserInteractor mSelectedUserInteractor;
    @Nullable private KeyguardBypassController mBypassController;
    @Nullable private OccludingAppBiometricUI mOccludingAppBiometricUI;

    @Nullable private TaskbarDelegate mTaskbarDelegate;
    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTrustGrantedForCurrentUser(
                        boolean dismissKeyguard,
                        boolean newlyUnlocked,
                        @NonNull TrustGrantFlags flags,
                        @Nullable String message
                ) {
                    updateAlternateBouncerShowing(mAlternateBouncerInteractor.maybeHide());
                }

        @Override
        public void onEmergencyCallAction() {
            // Since we won't get a setOccluded call we have to reset the view manually such that
            // the bouncer goes away.
            if (mKeyguardStateController.isOccluded()) {
                reset(true /* hideBouncerWhenShowing */);
            }
        }
    };
    private Lazy<WindowManagerLockscreenVisibilityInteractor> mWmLockscreenVisibilityInteractor;
    private Lazy<KeyguardSurfaceBehindInteractor> mSurfaceBehindInteractor;
    private Lazy<KeyguardDismissActionInteractor> mKeyguardDismissActionInteractor;

    private final JavaAdapter mJavaAdapter;

    @Inject
    public StatusBarKeyguardViewManager(
            Context context,
            ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils,
            SysuiStatusBarStateController sysuiStatusBarStateController,
            ConfigurationController configurationController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DreamOverlayStateController dreamOverlayStateController,
            NavigationModeController navigationModeController,
            DockManager dockManager,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            KeyguardMessageAreaController.Factory keyguardMessageAreaFactory,
            Optional<SysUIUnfoldComponent> sysUIUnfoldComponent,
            Lazy<ShadeController> shadeController,
            LatencyTracker latencyTracker,
            KeyguardSecurityModel keyguardSecurityModel,
            FeatureFlags featureFlags,
            PrimaryBouncerCallbackInteractor primaryBouncerCallbackInteractor,
            PrimaryBouncerInteractor primaryBouncerInteractor,
            BouncerView primaryBouncerView,
            AlternateBouncerInteractor alternateBouncerInteractor,
            UdfpsOverlayInteractor udfpsOverlayInteractor,
            ActivityStarter activityStarter,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            @Main CoroutineDispatcher mainDispatcher,
            Lazy<WindowManagerLockscreenVisibilityInteractor> wmLockscreenVisibilityInteractor,
            Lazy<KeyguardDismissActionInteractor> keyguardDismissActionInteractorLazy,
            SelectedUserInteractor selectedUserInteractor,
            Lazy<KeyguardSurfaceBehindInteractor> surfaceBehindInteractor,
            JavaAdapter javaAdapter
    ) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mConfigurationController = configurationController;
        mNavigationModeController = navigationModeController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mDreamOverlayStateController = dreamOverlayStateController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardUpdateManager = keyguardUpdateMonitor;
        mStatusBarStateController = sysuiStatusBarStateController;
        mDockManager = dockManager;
        mKeyguardMessageAreaFactory = keyguardMessageAreaFactory;
        mShadeController = shadeController;
        mLatencyTracker = latencyTracker;
        mKeyguardSecurityModel = keyguardSecurityModel;
        mFlags = featureFlags;
        mPrimaryBouncerCallbackInteractor = primaryBouncerCallbackInteractor;
        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mPrimaryBouncerView = primaryBouncerView;
        mFoldAodAnimationController = sysUIUnfoldComponent
                .map(SysUIUnfoldComponent::getFoldAodAnimationController).orElse(null);
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mIsBackAnimationEnabled = predictiveBackAnimateBouncer();
        mUdfpsOverlayInteractor = udfpsOverlayInteractor;
        mActivityStarter = activityStarter;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mMainDispatcher = mainDispatcher;
        mWmLockscreenVisibilityInteractor = wmLockscreenVisibilityInteractor;
        mKeyguardDismissActionInteractor = keyguardDismissActionInteractorLazy;
        mSelectedUserInteractor = selectedUserInteractor;
        mSurfaceBehindInteractor = surfaceBehindInteractor;
        mJavaAdapter = javaAdapter;
    }

    KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    CoroutineDispatcher mMainDispatcher;

    @Override
    public void registerCentralSurfaces(CentralSurfaces centralSurfaces,
            ShadeLockscreenInteractor shadeLockscreenInteractor,
            ShadeExpansionStateManager shadeExpansionStateManager,
            BiometricUnlockController biometricUnlockController,
            View notificationContainer,
            KeyguardBypassController bypassController) {
        mCentralSurfaces = centralSurfaces;
        mBiometricUnlockController = biometricUnlockController;

        mPrimaryBouncerCallbackInteractor.addBouncerExpansionCallback(mExpansionCallback);
        mShadeLockscreenInteractor = shadeLockscreenInteractor;
        if (shadeExpansionStateManager != null) {
            ShadeExpansionChangeEvent currentState =
                    shadeExpansionStateManager.addExpansionListener(this);
            onPanelExpansionChanged(currentState);
        }
        mBypassController = bypassController;
        mNotificationContainer = notificationContainer;
        if (!DeviceEntryUdfpsRefactor.isEnabled()) {
            mKeyguardMessageAreaController = mKeyguardMessageAreaFactory.create(
                    centralSurfaces.getKeyguardMessageArea());
        }

        mCentralSurfacesRegistered = true;

        registerListeners();
    }


    /**
     * Sets the given OccludingAppBiometricUI to null if it's the current auth interceptor. Else,
     * does nothing.
     */
    public void removeOccludingAppBiometricUI(@NonNull OccludingAppBiometricUI biometricUI) {
        if (Objects.equals(mOccludingAppBiometricUI, biometricUI)) {
            mOccludingAppBiometricUI = null;
        }
    }

    /**
     * Sets a new OccludingAppBiometricUI.
     */
    public void setOccludingAppBiometricUI(@NonNull OccludingAppBiometricUI biometricUI) {
        if (!Objects.equals(mOccludingAppBiometricUI, biometricUI)) {
            mOccludingAppBiometricUI = biometricUI;
        }
    }

    private void registerListeners() {
        mKeyguardUpdateManager.registerCallback(mUpdateMonitorCallback);
        mStatusBarStateController.addCallback(this);
        mConfigurationController.addCallback(this);
        mGesturalNav = QuickStepContract.isGesturalMode(
                mNavigationModeController.addListener(this));
        if (mFoldAodAnimationController != null) {
            mFoldAodAnimationController.addCallback(this);
        }
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
            mIsDocked = mDockManager.isDocked();
        }

        if (KeyguardWmStateRefactor.isEnabled()) {
            // Show the keyguard views whenever we've told WM that the lockscreen is visible.
            mJavaAdapter.alwaysCollectFlow(
                    combineFlows(
                            mWmLockscreenVisibilityInteractor.get().getLockscreenVisibility(),
                            mSurfaceBehindInteractor.get().isAnimatingSurface(),
                            (lockscreenVis, animatingSurface) ->
                                    // TODO(b/322546110): Waiting until we're not animating the
                                    // surface is a workaround to avoid jank. We should actually
                                    // fix the source of the jank, and then hide the keyguard
                                    // view without waiting for the animation to end.
                                    lockscreenVis || animatingSurface
                    ),
                    this::consumeShowStatusBarKeyguardView);
        }
    }

    private void consumeShowStatusBarKeyguardView(boolean show) {
        if (show != mLastShowing) {
            if (show) {
                show(null);
            } else {
                hide(0, 0);
            }
        }
    }

    /** Register a callback, to be invoked by the Predictive Back system. */
    private void registerBackCallback() {
        if (!mIsBackCallbackRegistered) {
            ViewRootImpl viewRoot = getViewRootImpl();
            if (viewRoot != null) {
                viewRoot.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, mOnBackInvokedCallback);
                mIsBackCallbackRegistered = true;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "view root was null, could not register back callback");
                }
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "prevented registering back callback twice");
            }
        }
    }

    /** Unregister the callback formerly registered with the Predictive Back system. */
    private void unregisterBackCallback() {
        if (mIsBackCallbackRegistered) {
            ViewRootImpl viewRoot = getViewRootImpl();
            if (viewRoot != null) {
                viewRoot.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        mOnBackInvokedCallback);
                mIsBackCallbackRegistered = false;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "view root was null, could not unregister back callback");
                }
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "prevented unregistering back callback twice");
            }
        }
    }

    private boolean shouldPlayBackAnimation() {
        // Suppress back animation when bouncer shouldn't be dismissed on back invocation.
        return !needsFullscreenBouncer() && mIsBackAnimationEnabled;
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        hideBouncer(true /* destroyView */);
    }

    private boolean beginShowingBouncer(ShadeExpansionChangeEvent event) {
        // Avoid having the shade and the bouncer open at the same time over a dream.
        final boolean hideBouncerOverDream =
                mDreamOverlayStateController.isOverlayActive()
                        && (mShadeLockscreenInteractor.isExpanded()
                        || mShadeLockscreenInteractor.isExpandingOrCollapsing());

        final boolean isUserTrackingStarted =
                event.getFraction() != EXPANSION_HIDDEN && event.getTracking();

        return mKeyguardStateController.isShowing()
                && !primaryBouncerIsOrWillBeShowing()
                && !mKeyguardStateController.isKeyguardGoingAway()
                && isUserTrackingStarted
                && !hideBouncerOverDream
                && !mKeyguardStateController.isOccluded()
                && !mKeyguardStateController.canDismissLockScreen()
                && !bouncerIsAnimatingAway()
                && !(mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED);
    }

    @Override
    public void onPanelExpansionChanged(ShadeExpansionChangeEvent event) {
        float fraction = event.getFraction();
        boolean tracking = event.getTracking();

        if (mFraction == fraction && mTracking == tracking) {
            // Ignore duplicate events, as they will cause confusion with bouncer expansion
            return;
        }
        mFraction = fraction;
        mTracking = tracking;

        /*
         * The bouncer may have received a call to show(), or the following will infer it from
         * device state and touch handling. The bouncer MUST have been notified that it is about to
         * show if any subsequent events are to be handled.
         */
        if (beginShowingBouncer(event)) {
            mPrimaryBouncerInteractor.show(/* isScrimmed= */false);
        }

        if (!primaryBouncerIsOrWillBeShowing()) {
            return;
        }

        if (mKeyguardStateController.isShowing()) {
            mPrimaryBouncerInteractor.setPanelExpansion(fraction);
        } else {
            mPrimaryBouncerInteractor.setPanelExpansion(EXPANSION_HIDDEN);
        }
    }

    /**
     * Update the global actions visibility state in order to show the navBar when active.
     */
    public void setGlobalActionsVisible(boolean isVisible) {
        mGlobalActionsVisible = isVisible;
        updateStates();
    }

    public void setTaskbarDelegate(TaskbarDelegate taskbarDelegate) {
        mTaskbarDelegate = taskbarDelegate;
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    @Override
    public void show(Bundle options) {
        Trace.beginSection("StatusBarKeyguardViewManager#show");
        mNotificationShadeWindowController.setKeyguardShowing(true);
        mKeyguardStateController.notifyKeyguardState(true,
                mKeyguardStateController.isOccluded());
        reset(true /* hideBouncerWhenShowing */);
        SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
        Trace.endSection();
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link #needsFullscreenBouncer()}.
     */
    protected void showBouncerOrKeyguard(boolean hideBouncerWhenShowing) {
        if (needsFullscreenBouncer() && !mDozing) {
            // The keyguard might be showing (already). So we need to hide it.
            if (!primaryBouncerIsShowing()) {
                mCentralSurfaces.hideKeyguard();
                mPrimaryBouncerInteractor.show(true);
            } else {
                Log.e(TAG, "Attempted to show the sim bouncer when it is already showing.");
            }
        } else {
            mCentralSurfaces.showKeyguard();
            if (hideBouncerWhenShowing) {
                hideBouncer(false /* destroyView */);
            }
        }
        updateStates();
    }

    /**
     *
     * If possible, shows the alternate bouncer. Else, shows the primary (pin/pattern/password)
     * bouncer.
     * @param scrimmed true when the primary bouncer should show scrimmed,
     *                 false when the user will be dragging it and translation should be deferred
     *                 {@see KeyguardBouncer#show(boolean, boolean)}
     */
    public void showBouncer(boolean scrimmed) {
        if (!mAlternateBouncerInteractor.show()) {
            showPrimaryBouncer(scrimmed);
        } else {
            updateAlternateBouncerShowing(mAlternateBouncerInteractor.isVisibleState());
        }
    }

    /**
     * Hides the input bouncer (pin/password/pattern).
     */
    @VisibleForTesting
    void hideBouncer(boolean destroyView) {
        mPrimaryBouncerInteractor.hide();
        if (mKeyguardStateController.isShowing()) {
            // If we were showing the bouncer and then aborting, we need to also clear out any
            // potential actions unless we actually unlocked.
            cancelPostAuthActions();
        }
        cancelPendingWakeupAction();
    }

    /**
     * Shows the primary bouncer - the pin/pattern/password challenge on the lock screen.
     *
     * @param scrimmed true when the bouncer should show scrimmed, false when the user will be
     * dragging it and translation should be deferred {@see KeyguardBouncer#show(boolean, boolean)}
     */
    public void showPrimaryBouncer(boolean scrimmed) {
        hideAlternateBouncer(false);
        if (mKeyguardStateController.isShowing() && !isBouncerShowing()) {
            mPrimaryBouncerInteractor.show(scrimmed);
        }
        updateStates();
    }

    public void dismissWithAction(OnDismissAction r, Runnable cancelAction,
            boolean afterKeyguardGone) {
        dismissWithAction(r, cancelAction, afterKeyguardGone, null /* message */);
    }

    public void dismissWithAction(OnDismissAction r, Runnable cancelAction,
            boolean afterKeyguardGone, String message) {
        if (mFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT)) {
            if (r == null) {
                return;
            }
            Trace.beginSection("StatusBarKeyguardViewManager#interactorDismissWithAction");
            if (afterKeyguardGone) {
                mKeyguardDismissActionInteractor.get().setDismissAction(
                        new DismissAction.RunAfterKeyguardGone(
                                () -> {
                                    r.onDismiss();
                                    return null;
                                },
                                (cancelAction != null) ? cancelAction : () -> {},
                                message == null ? "" : message,
                                r.willRunAnimationOnKeyguard()
                        )
                );
            } else {
                mKeyguardDismissActionInteractor.get().setDismissAction(
                        new DismissAction.RunImmediately(
                                () -> {
                                    if (r.onDismiss()) {
                                        return KeyguardDone.LATER;
                                    } else {
                                        return KeyguardDone.IMMEDIATE;
                                    }
                                },
                                (cancelAction != null) ? cancelAction : () -> {},
                                message == null ? "" : message,
                                r.willRunAnimationOnKeyguard()
                        )
                );
            }

            showBouncer(true);
            Trace.endSection();
            return;
        }

        if (mKeyguardStateController.isShowing()) {
            try {
                Trace.beginSection("StatusBarKeyguardViewManager#dismissWithAction");
                cancelPendingWakeupAction();
                // If we're dozing, this needs to be delayed until after we wake up - unless we're
                // wake-and-unlocking, because there dozing will last until the end of the
                // transition.
                if (mDozing && !isWakeAndUnlocking()) {
                    mPendingWakeupAction = new DismissWithActionRequest(
                            r, cancelAction, afterKeyguardGone, message);
                    return;
                }

                if (!mFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT)) {
                    mAfterKeyguardGoneAction = r;
                    mKeyguardGoneCancelAction = cancelAction;
                    mDismissActionWillAnimateOnKeyguard = r != null
                            && r.willRunAnimationOnKeyguard();
                }

                // If there is an alternate auth interceptor (like the UDFPS), show that one
                // instead of the bouncer.
                if (mAlternateBouncerInteractor.canShowAlternateBouncerForFingerprint()) {
                    if (!afterKeyguardGone) {
                        mPrimaryBouncerInteractor.setDismissAction(mAfterKeyguardGoneAction,
                                mKeyguardGoneCancelAction);
                        mAfterKeyguardGoneAction = null;
                        mKeyguardGoneCancelAction = null;
                    }

                    updateAlternateBouncerShowing(mAlternateBouncerInteractor.show());
                    setKeyguardMessage(message, null, null);
                    return;
                }

                mViewMediatorCallback.setCustomMessage(message);
                if (afterKeyguardGone) {
                    // we'll handle the dismiss action after keyguard is gone, so just show the
                    // bouncer
                    mPrimaryBouncerInteractor.show(/* isScrimmed= */true);
                } else {
                    // after authentication success, run dismiss action with the option to defer
                    // hiding the keyguard based on the return value of the OnDismissAction
                    mPrimaryBouncerInteractor.setDismissAction(
                            mAfterKeyguardGoneAction, mKeyguardGoneCancelAction);
                    mPrimaryBouncerInteractor.show(/* isScrimmed= */true);
                    // bouncer will handle the dismiss action, so we no longer need to track it here
                    mAfterKeyguardGoneAction = null;
                    mKeyguardGoneCancelAction = null;
                }
            } finally {
                Trace.endSection();
            }
        }
        updateStates();
    }

    private boolean isWakeAndUnlocking() {
        int mode = mBiometricUnlockController.getMode();
        return mode == MODE_WAKE_AND_UNLOCK || mode == MODE_WAKE_AND_UNLOCK_PULSING;
    }

    /**
     * Adds a {@param runnable} to be executed after Keyguard is gone.
     */
    public void addAfterKeyguardGoneRunnable(Runnable runnable) {
        if (mFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT)) {
            if (runnable != null) {
                mKeyguardDismissActionInteractor.get().runAfterKeyguardGone(runnable);
            }
            return;
        }
        mAfterKeyguardGoneRunnables.add(runnable);
    }

    @Override
    public void reset(boolean hideBouncerWhenShowing) {
        if (mKeyguardStateController.isShowing() && !bouncerIsAnimatingAway()) {
            final boolean isOccluded = mKeyguardStateController.isOccluded();
            // Hide quick settings.
            mShadeLockscreenInteractor.resetViews(/* animate= */ !isOccluded);
            // Hide bouncer and quick-quick settings.
            if (isOccluded && !mDozing) {
                mCentralSurfaces.hideKeyguard();
                if (hideBouncerWhenShowing || needsFullscreenBouncer()) {
                    hideBouncer(false /* destroyView */);
                }
            } else {
                showBouncerOrKeyguard(hideBouncerWhenShowing);
            }
            if (hideBouncerWhenShowing) {
                hideAlternateBouncer(true);
            }
            mKeyguardUpdateManager.sendKeyguardReset();
            updateStates();
        }
    }

    @Override
    public void hideAlternateBouncer(boolean updateScrim) {
        updateAlternateBouncerShowing(mAlternateBouncerInteractor.hide() && updateScrim);
    }

    private void updateAlternateBouncerShowing(boolean updateScrim) {
        if (!mCentralSurfacesRegistered) {
            // if CentralSurfaces hasn't been registered yet, then the controllers below haven't
            // been initialized yet so there's no need to attempt to forward them events.
            return;
        }

        final boolean isShowingAlternateBouncer = mAlternateBouncerInteractor.isVisibleState();
        if (mKeyguardMessageAreaController != null) {
            DeviceEntryUdfpsRefactor.assertInLegacyMode();
            mKeyguardMessageAreaController.setIsVisible(isShowingAlternateBouncer);
            mKeyguardMessageAreaController.setMessage("");
        }
        mBypassController.setAltBouncerShowing(isShowingAlternateBouncer);
        mKeyguardUpdateManager.setAlternateBouncerShowing(isShowingAlternateBouncer);

        if (updateScrim) {
            mCentralSurfaces.updateScrimController();
        }
    }

    private void setRootViewAnimationDisabled(boolean disabled) {
        ViewGroup windowRootView = mNotificationShadeWindowController.getWindowRootView();
        if (windowRootView != null) {
            WindowInsetsController insetsController = windowRootView.getWindowInsetsController();
            if (insetsController != null) {
                insetsController.setAnimationsDisabled(disabled);
            }
        }
    }

    @Override
    public void onStartedWakingUp() {
        setRootViewAnimationDisabled(false);
        NavigationBarView navBarView = mCentralSurfaces.getNavigationBarView();
        if (navBarView != null) {
            navBarView.forEachView(view ->
                    view.animate()
                            .alpha(1f)
                            .setDuration(NAV_BAR_CONTENT_FADE_DURATION)
                            .start());
        }
    }

    @Override
    public void onStartedGoingToSleep() {
        setRootViewAnimationDisabled(true);
        NavigationBarView navBarView = mCentralSurfaces.getNavigationBarView();
        if (navBarView != null) {
            navBarView.forEachView(view ->
                    view.animate()
                            .alpha(0f)
                            .setDuration(NAV_BAR_CONTENT_FADE_DURATION)
                            .start());
        }
    }

    @Override
    public void onFinishedGoingToSleep() {
        mPrimaryBouncerInteractor.hide();
    }

    @Override
    public void onRemoteInputActive(boolean active) {
        mRemoteInputActive = active;
        updateStates();
    }

    private void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            mDozing = dozing;
            if (dozing || needsFullscreenBouncer()
                    || mKeyguardStateController.isOccluded()) {
                reset(dozing /* hideBouncerWhenShowing */);
            }
            updateStates();

            if (!dozing) {
                launchPendingWakeupAction();
            }
        }
    }

    /**
     * If {@link CentralSurfaces} is pulsing.
     */
    public void setPulsing(boolean pulsing) {
        if (mPulsing != pulsing) {
            mPulsing = pulsing;
            updateStates();
        }
    }

    @Override
    public void setNeedsInput(boolean needsInput) {
        mNotificationShadeWindowController.setKeyguardNeedsInput(needsInput);
    }

    @Override
    public boolean isUnlockWithWallpaper() {
        return mNotificationShadeWindowController.isShowingWallpaper();
    }

    @Override
    public boolean isBouncerShowingOverDream() {
        return mBouncerShowingOverDream;
    }

    @Override
    public void setOccluded(boolean occluded, boolean animate) {
        final boolean wasOccluded = mKeyguardStateController.isOccluded();
        final boolean isOccluding = !wasOccluded && occluded;
        final boolean isUnOccluding = wasOccluded  && !occluded;
        mKeyguardStateController.notifyKeyguardState(
                mKeyguardStateController.isShowing(), occluded);
        updateStates();
        final boolean isShowing = mKeyguardStateController.isShowing();
        final boolean isOccluded = mKeyguardStateController.isOccluded();

        if (isShowing && isOccluding) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__OCCLUDED);
            if (mCentralSurfaces.isLaunchingActivityOverLockscreen()) {
                // When isLaunchingActivityOverLockscreen() is true, we know for sure that the post
                // collapse runnables will be run.
                mShadeController.get().addPostCollapseAction(() -> {
                    mNotificationShadeWindowController.setKeyguardOccluded(isOccluded);
                    reset(true /* hideBouncerWhenShowing */);
                });
                return;
            }
        } else if (isShowing && isUnOccluding) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
        }
        mNotificationShadeWindowController.setKeyguardOccluded(isOccluded);

        // setDozing(false) will call reset once we stop dozing. Also, if we're going away, there's
        // no need to reset the keyguard views as we'll be gone shortly. Resetting now could cause
        // unexpected visible behavior if the keyguard is still visible as we're animating unlocked.
        if (!mDozing && !mKeyguardStateController.isKeyguardGoingAway()) {
            // If Keyguard is reshown, don't hide the bouncer as it might just have been requested
            // by a FLAG_DISMISS_KEYGUARD_ACTIVITY.
            reset(isOccluding /* hideBouncerWhenShowing*/);
        }
    }

    @Override
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (primaryBouncerIsShowing()) {
            mPrimaryBouncerInteractor.startDisappearAnimation(finishRunnable);
            mShadeLockscreenInteractor.startBouncerPreHideAnimation();

            // We update the state (which will show the keyguard) only if an animation will run on
            // the keyguard. If there is no animation, we wait before updating the state so that we
            // go directly from bouncer to launcher/app.
            if (mFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT)) {
                if (mKeyguardDismissActionInteractor.get().runDismissAnimationOnKeyguard()) {
                    updateStates();
                }
            } else if (mDismissActionWillAnimateOnKeyguard) {
                updateStates();
            }
        } else if (finishRunnable != null) {
            finishRunnable.run();
        }
        mShadeLockscreenInteractor.blockExpansionForCurrentTouch();
    }

    @Override
    public void blockPanelExpansionFromCurrentTouch() {
        mShadeLockscreenInteractor.blockExpansionForCurrentTouch();
    }

    @Override
    public void hide(long startTime, long fadeoutDuration) {
        Trace.beginSection("StatusBarKeyguardViewManager#hide");
        mKeyguardStateController.notifyKeyguardState(false,
                mKeyguardStateController.isOccluded());
        launchPendingWakeupAction();

        if (mKeyguardUpdateManager.needsSlowUnlockTransition()) {
            fadeoutDuration = KEYGUARD_DISMISS_DURATION_LOCKED;
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        long delay = Math.max(0, startTime + HIDE_TIMING_CORRECTION_MS - uptimeMillis);

        if (mKeyguardStateController.isFlingingToDismissKeyguard()) {
            final boolean wasFlingingToDismissKeyguard =
                    mKeyguardStateController.isFlingingToDismissKeyguard();
            mCentralSurfaces.fadeKeyguardAfterLaunchTransition(new Runnable() {
                @Override
                public void run() {
                    mNotificationShadeWindowController.setKeyguardShowing(false);
                    mNotificationShadeWindowController.setKeyguardFadingAway(true);
                    hideBouncer(true /* destroyView */);
                    updateStates();
                }
            }, /* endRunnable */ new Runnable() {
                @Override
                public void run() {
                    mCentralSurfaces.hideKeyguard();
                    mNotificationShadeWindowController.setKeyguardFadingAway(false);

                    if (wasFlingingToDismissKeyguard) {
                        mCentralSurfaces.finishKeyguardFadingAway();
                    }

                    mViewMediatorCallback.keyguardGone();
                    executeAfterKeyguardGoneAction();
                }
            }, /* cancelRunnable */ new Runnable() {
                @Override
                public void run() {
                    mNotificationShadeWindowController.setKeyguardFadingAway(false);
                    if (wasFlingingToDismissKeyguard) {
                        mCentralSurfaces.finishKeyguardFadingAway();
                    }
                    cancelPostAuthActions();
                }
            });
        } else {
            executeAfterKeyguardGoneAction();
            mCentralSurfaces.setKeyguardFadingAway(startTime, delay, fadeoutDuration);
            mBiometricUnlockController.startKeyguardFadingAway();
            hideBouncer(true /* destroyView */);

            boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
            if (!staying) {
                mNotificationShadeWindowController.setKeyguardFadingAway(true);
                wakeAndUnlockDejank();
                mCentralSurfaces.hideKeyguard();
                // hide() will happen asynchronously and might arrive after the scrims
                // were already hidden, this means that the transition callback won't
                // be triggered anymore and StatusBarWindowController will be forever in
                // the fadingAway state.
                mCentralSurfaces.updateScrimController();
            } else {
                mCentralSurfaces.hideKeyguard();
                mCentralSurfaces.finishKeyguardFadingAway();
                mBiometricUnlockController.finishKeyguardFadingAway();
            }

            updateStates();
            mNotificationShadeWindowController.setKeyguardShowing(false);
            mViewMediatorCallback.keyguardGone();
        }
        SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__HIDDEN);
        Trace.endSection();
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        boolean gesturalNav = QuickStepContract.isGesturalMode(mode);
        if (gesturalNav != mGesturalNav) {
            mGesturalNav = gesturalNav;
            updateStates();
        }
    }

    public void onThemeChanged() {
        updateResources();
    }

    public void onKeyguardFadedAway() {
        mNotificationContainer.postDelayed(() -> mNotificationShadeWindowController
                        .setKeyguardFadingAway(false), 100);
        mShadeLockscreenInteractor.resetViewGroupFade();
        mCentralSurfaces.finishKeyguardFadingAway();
        mBiometricUnlockController.finishKeyguardFadingAway();
    }

    private void wakeAndUnlockDejank() {
        if (mBiometricUnlockController.isWakeAndUnlock() && mLatencyTracker.isEnabled()) {
            BiometricSourceType type = mBiometricUnlockController.getBiometricType();
            mLatencyTracker.onActionEnd(type == BiometricSourceType.FACE
                            ? LatencyTracker.ACTION_FACE_WAKE_AND_UNLOCK
                            : LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK);
        }
    }

    private void executeAfterKeyguardGoneAction() {
        if (mFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT)) {
            return;
        }
        if (mAfterKeyguardGoneAction != null) {
            mAfterKeyguardGoneAction.onDismiss();
            mAfterKeyguardGoneAction = null;
        }
        mKeyguardGoneCancelAction = null;
        mDismissActionWillAnimateOnKeyguard = false;
        for (int i = 0; i < mAfterKeyguardGoneRunnables.size(); i++) {
            mAfterKeyguardGoneRunnables.get(i).run();
        }
        mAfterKeyguardGoneRunnables.clear();
    }

    @Override
    public void dismissAndCollapse() {
        mActivityStarter.executeRunnableDismissingKeyguard(
                /* runnable= */ null,
                /* cancelAction= */ null,
                /* dismissShade= */ true,
                /* afterKeyguardGone= */ false,
                /* deferred= */ true
        );
    }

    /**
     * WARNING: This method might cause Binder calls.
     */
    public boolean isSecure() {
        return mKeyguardSecurityModel.getSecurityMode(
                mSelectedUserInteractor.getSelectedUserId())
                != KeyguardSecurityModel.SecurityMode.None;
    }

    /**
     * Returns whether a back invocation can be handled, which depends on whether the keyguard
     * is currently showing (which itself is derived from multiple states).
     *
     * @return whether a back press can be handled right now.
     */
    public boolean canHandleBackPressed() {
        return primaryBouncerIsShowing();
    }

    /**
     * Notifies this manager that the back button has been pressed.
     */
    public void onBackPressed() {
        if (!canHandleBackPressed()) {
            return;
        }

        mCentralSurfaces.endAffordanceLaunch();
        // The second condition is for SIM card locked bouncer
        if (primaryBouncerIsScrimmed() && !needsFullscreenBouncer()) {
            hideBouncer(false);
            updateStates();
        } else {
            /* Non-scrimmed bouncers have a special animation tied to the expansion
             * of the notification panel. We decide whether to kick this animation off
             * by computing the hideImmediately boolean.
             */
            boolean hideImmediately = mCentralSurfaces.shouldKeyguardHideImmediately();
            reset(hideImmediately);
            if (hideImmediately) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            } else {
                mShadeLockscreenInteractor.expandToNotifications();
            }
        }
        return;
    }

    @Override
    public boolean isBouncerShowing() {
        return primaryBouncerIsShowing() || mAlternateBouncerInteractor.isVisibleState();
    }

    @Override
    public boolean primaryBouncerIsOrWillBeShowing() {
        return isBouncerShowing() || isPrimaryBouncerInTransit();
    }

    public boolean isFullscreenBouncer() {
        return mPrimaryBouncerView.getDelegate() != null
                && mPrimaryBouncerView.getDelegate().isFullScreenBouncer();
    }

    /**
     * Clear out any potential actions that were saved to run when the device is unlocked
     */
    public void cancelPostAuthActions() {
        if (primaryBouncerIsOrWillBeShowing()) {
            return; // allow the primary bouncer to trigger saved actions
        }
        mAfterKeyguardGoneAction = null;
        mDismissActionWillAnimateOnKeyguard = false;
        if (mKeyguardGoneCancelAction != null) {
            mKeyguardGoneCancelAction.run();
            mKeyguardGoneCancelAction = null;
        }
    }

    private long getNavBarShowDelay() {
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            return mKeyguardStateController.getKeyguardFadingAwayDelay();
        } else if (isBouncerShowing()) {
            return NAV_BAR_SHOW_DELAY_BOUNCER;
        } else {
            // No longer dozing, or remote input is active. No delay.
            return 0;
        }
    }

    private Runnable mMakeNavigationBarVisibleRunnable = new Runnable() {
        @Override
        public void run() {
            NavigationBarView view = mCentralSurfaces.getNavigationBarView();
            if (view != null) {
                view.setVisibility(View.VISIBLE);
            }
            mNotificationShadeWindowController.getWindowRootView().getWindowInsetsController()
                    .show(navigationBars());
        }
    };

    protected void updateStates() {
        if (!mCentralSurfacesRegistered) {
            return;
        }
        boolean showing = mKeyguardStateController.isShowing();
        boolean occluded = mKeyguardStateController.isOccluded();
        boolean primaryBouncerShowing = primaryBouncerIsShowing();
        boolean primaryBouncerIsOrWillBeShowing = primaryBouncerIsOrWillBeShowing();
        boolean primaryBouncerDismissible = !isFullscreenBouncer();
        boolean remoteInputActive = mRemoteInputActive;

        if ((primaryBouncerDismissible || !showing || remoteInputActive)
                != (mLastBouncerDismissible || !mLastShowing || mLastRemoteInputActive)
                || mFirstUpdate) {
            if (primaryBouncerDismissible || !showing || remoteInputActive) {
                mPrimaryBouncerInteractor.setBackButtonEnabled(true);
            } else {
                mPrimaryBouncerInteractor.setBackButtonEnabled(false);
            }
        }

        boolean navBarVisible = isNavBarVisible();
        boolean lastNavBarVisible = getLastNavBarVisible();
        if (navBarVisible != lastNavBarVisible || mFirstUpdate) {
            updateNavigationBarVisibility(navBarVisible);
        }

        boolean isPrimaryBouncerShowingChanged =
            primaryBouncerShowing != mLastPrimaryBouncerShowing;
        mLastPrimaryBouncerShowing = primaryBouncerShowing;

        if (isPrimaryBouncerShowingChanged || mFirstUpdate) {
            mNotificationShadeWindowController.setBouncerShowing(primaryBouncerShowing);
            mCentralSurfaces.setBouncerShowing(primaryBouncerShowing);
        }
        if (primaryBouncerIsOrWillBeShowing != mLastPrimaryBouncerIsOrWillBeShowing || mFirstUpdate
                || isPrimaryBouncerShowingChanged) {
            mKeyguardUpdateManager.sendPrimaryBouncerChanged(primaryBouncerIsOrWillBeShowing,
                    primaryBouncerShowing);
        }

        mFirstUpdate = false;
        mLastShowing = showing;
        mLastGlobalActionsVisible = mGlobalActionsVisible;
        mLastOccluded = occluded;
        mLastPrimaryBouncerIsOrWillBeShowing = primaryBouncerIsOrWillBeShowing;
        mLastBouncerDismissible = primaryBouncerDismissible;
        mLastRemoteInputActive = remoteInputActive;
        mLastDozing = mDozing;
        mLastPulsing = mPulsing;
        mLastScreenOffAnimationPlaying = mScreenOffAnimationPlaying;
        mLastBiometricMode = mBiometricUnlockController.getMode();
        mLastGesturalNav = mGesturalNav;
        mLastIsDocked = mIsDocked;
        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();
    }

    /**
     * Updates the visibility of the nav bar window (which will cause insets changes).
     */
    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if (mCentralSurfaces.getNavigationBarView() != null
                || (mTaskbarDelegate != null && mTaskbarDelegate.isInitialized())) {
            if (navBarVisible) {
                long delay = getNavBarShowDelay();
                if (delay == 0) {
                    mMakeNavigationBarVisibleRunnable.run();
                } else {
                    mNotificationContainer.postOnAnimationDelayed(mMakeNavigationBarVisibleRunnable,
                            delay);
                }
            } else {
                mNotificationContainer.removeCallbacks(mMakeNavigationBarVisibleRunnable);
                mNotificationShadeWindowController.getWindowRootView().getWindowInsetsController()
                        .hide(navigationBars());
            }
        }
    }

    /**
     * @return Whether the navigation bar should be made visible based on the current state.
     */
    public boolean isNavBarVisible() {
        boolean isWakeAndUnlockPulsing = mBiometricUnlockController != null
                && mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardVisible = mKeyguardStateController.isVisible();
        boolean hideWhileDozing = mDozing && !isWakeAndUnlockPulsing;
        boolean keyguardWithGestureNav = (keyguardVisible && !mDozing && !mScreenOffAnimationPlaying
                || mPulsing && !mIsDocked)
                && mGesturalNav;
        return (!keyguardVisible && !hideWhileDozing && !mScreenOffAnimationPlaying
                || primaryBouncerIsShowing()
                || mRemoteInputActive
                || keyguardWithGestureNav
                || mGlobalActionsVisible);
    }

    /**
     * @return Whether the navigation bar was made visible based on the last known state.
     */
    protected boolean getLastNavBarVisible() {
        boolean keyguardShowing = mLastShowing && !mLastOccluded;
        boolean hideWhileDozing = mLastDozing && mLastBiometricMode != MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardWithGestureNav = (keyguardShowing && !mLastDozing
                && !mLastScreenOffAnimationPlaying || mLastPulsing && !mLastIsDocked)
                && mLastGesturalNav;
        return (!keyguardShowing && !hideWhileDozing && !mLastScreenOffAnimationPlaying
                || mLastPrimaryBouncerShowing || mLastRemoteInputActive || keyguardWithGestureNav
                || mLastGlobalActionsVisible);
    }

    public boolean shouldDismissOnMenuPressed() {
        return mPrimaryBouncerView.getDelegate() != null
                && mPrimaryBouncerView.getDelegate().shouldDismissOnMenuPressed();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mPrimaryBouncerView.getDelegate() != null
                && mPrimaryBouncerView.getDelegate().interceptMediaKey(event);
    }

    /**
     * @return true if the pre IME back event should be handled
     */
    public boolean dispatchBackKeyEventPreIme() {
        return mPrimaryBouncerView.getDelegate() != null
                && mPrimaryBouncerView.getDelegate().dispatchBackKeyEventPreIme();
    }

    public void readyForKeyguardDone() {
        mViewMediatorCallback.readyForKeyguardDone();
    }

    @Override
    public boolean shouldDisableWindowAnimationsForUnlock() {
        return false;
    }

    @Override
    public boolean shouldSubtleWindowAnimationsForUnlock() {
        return false;
    }

    @Override
    public boolean isGoingToNotificationShade() {
        return mStatusBarStateController.leaveOpenOnKeyguardHide();
    }

    public boolean isSecure(int userId) {
        return isSecure() || mLockPatternUtils.isSecure(userId);
    }

    @Override
    public void keyguardGoingAway() {
        mCentralSurfaces.keyguardGoingAway();
    }

    @Override
    public void setKeyguardGoingAwayState(boolean isKeyguardGoingAway) {
        mNotificationShadeWindowController.setKeyguardGoingAway(isKeyguardGoingAway);
    }

    @Override
    public void onCancelClicked() {
        // No-op
    }

    /**
     * Notifies that the user has authenticated by other means than using the bouncer, for example,
     * fingerprint and the keyguard should immediately dismiss.
     */
    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        mPrimaryBouncerInteractor.notifyKeyguardAuthenticatedBiometrics(strongAuth);

        if (mAlternateBouncerInteractor.isVisibleState()) {
            hideAlternateBouncer(false);
            executeAfterKeyguardGoneAction();
        }
    }

    /** Display security message to relevant KeyguardMessageArea. */
    public void setKeyguardMessage(String message, ColorStateList colorState,
            BiometricSourceType biometricSourceType) {
        if (mAlternateBouncerInteractor.isVisibleState()) {
            if (mKeyguardMessageAreaController != null) {
                DeviceEntryUdfpsRefactor.assertInLegacyMode();
                mKeyguardMessageAreaController.setMessage(message, biometricSourceType);
            }
        } else {
            mPrimaryBouncerInteractor.showMessage(message, colorState);
        }
    }

    @Override
    public ViewRootImpl getViewRootImpl() {
        ViewGroup viewGroup = mNotificationShadeWindowController.getWindowRootView();
        if (viewGroup != null) {
            return viewGroup.getViewRootImpl();
        } else {
            if (DEBUG) {
                Log.d(TAG, "ViewGroup was null, cannot get ViewRootImpl");
            }
            return null;
        }
    }

    public void launchPendingWakeupAction() {
        DismissWithActionRequest request = mPendingWakeupAction;
        mPendingWakeupAction = null;
        if (request != null) {
            if (mKeyguardStateController.isShowing()) {
                dismissWithAction(request.dismissAction, request.cancelAction,
                        request.afterKeyguardGone, request.message);
            } else if (request.dismissAction != null) {
                request.dismissAction.onDismiss();
            }
        }
    }

    public void cancelPendingWakeupAction() {
        DismissWithActionRequest request = mPendingWakeupAction;
        mPendingWakeupAction = null;
        if (request != null && request.cancelAction != null) {
            request.cancelAction.run();
        }
    }

    /**
     * Whether the primary bouncer requires scrimming.
     */
    public boolean primaryBouncerNeedsScrimming() {
        // When a dream overlay is active, scrimming will cause any expansion to immediately expand.
        return (mKeyguardStateController.isOccluded()
                && !mDreamOverlayStateController.isOverlayActive())
                || primaryBouncerWillDismissWithAction()
                || (primaryBouncerIsShowing() && primaryBouncerIsScrimmed())
                || isFullscreenBouncer();
    }

    /**
     * Apply keyguard configuration from the currently active resources. This can be called when the
     * device configuration changes, to re-apply some resources that are qualified on the device
     * configuration.
     */
    public void updateResources() {
        mPrimaryBouncerInteractor.updateResources();
    }

    public void dump(PrintWriter pw) {
        pw.println("StatusBarKeyguardViewManager:");
        pw.println("  mRemoteInputActive: " + mRemoteInputActive);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mAfterKeyguardGoneAction: " + mAfterKeyguardGoneAction);
        pw.println("  mAfterKeyguardGoneRunnables: " + mAfterKeyguardGoneRunnables);
        pw.println("  mPendingWakeupAction: " + mPendingWakeupAction);
        pw.println("  isBouncerShowing(): " + isBouncerShowing());
        pw.println("  bouncerIsOrWillBeShowing(): " + primaryBouncerIsOrWillBeShowing());
        pw.println("  Registered KeyguardViewManagerCallbacks:");
        pw.println(" refactorKeyguardDismissIntent enabled:"
                + mFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT));
        for (KeyguardViewManagerCallback callback : mCallbacks) {
            pw.println("      " + callback);
        }

        if (mOccludingAppBiometricUI != null) {
            pw.println("mOccludingAppBiometricUI:");
            mOccludingAppBiometricUI.dump(pw);
        }
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    @Override
    public void onFoldToAodAnimationChanged() {
        if (mFoldAodAnimationController != null) {
            mScreenOffAnimationPlaying = mFoldAodAnimationController.shouldPlayAnimation();
        }
    }

    /**
     * Add a callback to listen for changes
     */
    public void addCallback(KeyguardViewManagerCallback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Removes callback to stop receiving updates
     */
    public void removeCallback(KeyguardViewManagerCallback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Whether qs is currently expanded.
     */
    public float getQsExpansion() {
        return mQsExpansion;
    }

    /**
     * Update qs expansion.
     */
    public void setQsExpansion(float qsExpansion) {
        mQsExpansion = qsExpansion;
        for (KeyguardViewManagerCallback callback : mCallbacks) {
            callback.onQSExpansionChanged(mQsExpansion);
        }
    }

    /**
     * An opportunity for the AlternateBouncer to handle the touch instead of sending
     * the touch to NPVC child views.
     * @return true if the alternate bouncer should consime the touch and prevent it from
     * going to its child views
     */
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (shouldInterceptTouchEvent(event)
                && !mUdfpsOverlayInteractor.isTouchWithinUdfpsArea(event)) {
            onTouch(event);
        }
        return shouldInterceptTouchEvent(event);
    }

    /**
     * Whether the touch should be intercepted by the AlternateBouncer before going to the
     * notification shade's child views.
     */
    public boolean shouldInterceptTouchEvent(MotionEvent event) {
        if (DeviceEntryUdfpsRefactor.isEnabled()) {
            return false;
        }
        return mAlternateBouncerInteractor.isVisibleState();
    }

    /**
     * For any touches on the NPVC, show the primary bouncer if the alternate bouncer is currently
     * showing.
     */
    public boolean onTouch(MotionEvent event) {
        if (DeviceEntryUdfpsRefactor.isEnabled()) {
            return false;
        }

        boolean handleTouch = shouldInterceptTouchEvent(event);
        if (handleTouch) {
            final boolean actionDown = event.getActionMasked() == MotionEvent.ACTION_DOWN;
            final boolean actionDownThenUp = mAlternateBouncerInteractor.getReceivedDownTouch()
                    && event.getActionMasked() == MotionEvent.ACTION_UP;
            final boolean udfpsOverlayWillForwardEventsOutsideNotificationShade =
                    mKeyguardUpdateManager.isUdfpsEnrolled();
            final boolean actionOutsideShouldDismissAlternateBouncer =
                    event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                    && !udfpsOverlayWillForwardEventsOutsideNotificationShade;
            if (actionDown) {
                mAlternateBouncerInteractor.setReceivedDownTouch(true);
            } else if ((actionDownThenUp || actionOutsideShouldDismissAlternateBouncer)
                    && mAlternateBouncerInteractor.hasAlternateBouncerShownWithMinTime()) {
                showPrimaryBouncer(true);
            }
        }

        // Forward NPVC touches to callbacks in case they want to respond to touches
        for (KeyguardViewManagerCallback callback: mCallbacks) {
            callback.onTouch(event);
        }

        return handleTouch;
    }

    /** Update keyguard position based on a tapped X coordinate. */
    public void updateKeyguardPosition(float x) {
        mPrimaryBouncerInteractor.setKeyguardPosition(x);
    }

    private static class DismissWithActionRequest {
        final OnDismissAction dismissAction;
        final Runnable cancelAction;
        final boolean afterKeyguardGone;
        final String message;

        DismissWithActionRequest(OnDismissAction dismissAction, Runnable cancelAction,
                boolean afterKeyguardGone, String message) {
            this.dismissAction = dismissAction;
            this.cancelAction = cancelAction;
            this.afterKeyguardGone = afterKeyguardGone;
            this.message = message;
        }
    }

    /**
     * Request to authenticate using the fingerprint sensor.  If the fingerprint sensor is udfps,
     * uses the color provided by udfpsColor for the fingerprint icon.
     */
    public void requestFp(boolean request, int udfpsColor) {
        mKeyguardUpdateManager.requestFingerprintAuthOnOccludingApp(request);
        if (mOccludingAppBiometricUI != null) {
            mOccludingAppBiometricUI.requestUdfps(request, udfpsColor);
        }
    }

    /**
     * Returns if bouncer expansion is between 0 and 1 non-inclusive.
     */
    public boolean isPrimaryBouncerInTransit() {
        return mPrimaryBouncerInteractor.isInTransit();
    }

    /**
     * Returns if bouncer is showing
     */
    public boolean primaryBouncerIsShowing() {
        return mPrimaryBouncerInteractor.isFullyShowing();
    }

    /**
     * Returns if bouncer is scrimmed
     */
    public boolean primaryBouncerIsScrimmed() {
        return mPrimaryBouncerInteractor.isScrimmed();
    }

    /**
     * Returns if bouncer is animating away
     */
    public boolean bouncerIsAnimatingAway() {
        return mPrimaryBouncerInteractor.isAnimatingAway();
    }

    /**
     * Returns if bouncer will dismiss with action
     */
    public boolean primaryBouncerWillDismissWithAction() {
        return mPrimaryBouncerInteractor.willDismissWithAction();
    }

    /**
     * Returns if bouncer needs fullscreen bouncer. i.e. sim pin security method
     */
    public boolean needsFullscreenBouncer() {
        KeyguardSecurityModel.SecurityMode mode = mKeyguardSecurityModel.getSecurityMode(
                mSelectedUserInteractor.getSelectedUserId());
        return mode == KeyguardSecurityModel.SecurityMode.SimPin
                || mode == KeyguardSecurityModel.SecurityMode.SimPuk;
    }

    /**
     * Delegate used to send show and hide events to an alternate authentication method instead of
     * the regular pin/pattern/password bouncer.
     */
    public interface OccludingAppBiometricUI {
        /**
         * Use when an app occluding the keyguard would like to give the user ability to
         * unlock the device using udfps.
         *
         * @param color of the udfps icon. should have proper contrast with its background. only
         *              used if requestUdfps = true
         */
        void requestUdfps(boolean requestUdfps, int color);

        /**
         * print information for the alternate bouncer registered
         */
        void dump(PrintWriter pw);
    }

    /**
     * Callback for KeyguardViewManager state changes.
     */
    public interface KeyguardViewManagerCallback {
        /**
         * Set the amount qs is expanded. For example, swipe down from the top of the
         * lock screen to start the full QS expansion.
         */
        default void onQSExpansionChanged(float qsExpansion) { }

        /**
         * Forward touch events to callbacks
         */
        default void onTouch(MotionEvent event) { }
    }
}
