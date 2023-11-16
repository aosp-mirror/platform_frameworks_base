/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN;
import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_VISIBLE;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static kotlinx.coroutines.test.TestCoroutineDispatchersKt.StandardTestDispatcher;

import android.service.trust.TrustAgentService;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import androidx.test.filters.SmallTest;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.TrustGrantFlags;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.bouncer.ui.BouncerView;
import com.android.systemui.bouncer.ui.BouncerViewDelegate;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.domain.interactor.WindowManagerLockscreenVisibilityInteractor;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.TaskbarDelegate;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest extends SysuiTestCase {

    private static final ShadeExpansionChangeEvent EXPANSION_EVENT =
            expansionEvent(/* fraction= */ 0.5f, /* expanded= */ false, /* tracking= */ true);

    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ViewGroup mContainer;
    @Mock private ShadeViewController mShadeViewController;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private View mNotificationContainer;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    @Mock private KeyguardMessageAreaController mKeyguardMessageAreaController;
    @Mock private KeyguardMessageArea mKeyguardMessageArea;
    @Mock private ShadeController mShadeController;
    @Mock private SysUIUnfoldComponent mSysUiUnfoldComponent;
    @Mock private DreamOverlayStateController mDreamOverlayStateController;
    @Mock private LatencyTracker mLatencyTracker;
    private FakeFeatureFlags mFeatureFlags;
    @Mock private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock private PrimaryBouncerCallbackInteractor mPrimaryBouncerCallbackInteractor;
    @Mock private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock private UdfpsOverlayInteractor mUdfpsOverlayInteractor;
    @Mock private ActivityStarter mActivityStarter;
    @Mock private BouncerView mBouncerView;
    @Mock private BouncerViewDelegate mBouncerViewDelegate;
    @Mock private OnBackAnimationCallback mBouncerViewDelegateBackCallback;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock private WindowInsetsController mWindowInsetsController;
    @Mock private TaskbarDelegate mTaskbarDelegate;
    @Mock private StatusBarKeyguardViewManager.KeyguardViewManagerCallback mCallback;
    @Mock private SelectedUserInteractor mSelectedUserInteractor;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback
            mBouncerExpansionCallback;
    private FakeKeyguardStateController mKeyguardStateController =
            spy(new FakeKeyguardStateController());

    @Mock
    private ViewRootImpl mViewRootImpl;
    @Mock
    private WindowOnBackInvokedDispatcher mOnBackInvokedDispatcher;
    @Captor
    private ArgumentCaptor<OnBackInvokedCallback> mBackCallbackCaptor;
    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallback;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContainer.findViewById(anyInt())).thenReturn(mKeyguardMessageArea);
        when(mKeyguardMessageAreaFactory.create(any()))
                .thenReturn(mKeyguardMessageAreaController);
        when(mBouncerView.getDelegate()).thenReturn(mBouncerViewDelegate);
        when(mBouncerViewDelegate.getBackCallback()).thenReturn(mBouncerViewDelegateBackCallback);
        mFeatureFlags = new FakeFeatureFlags();
        mFeatureFlags.set(Flags.WM_ENABLE_PREDICTIVE_BACK_BOUNCER_ANIM, true);
        mFeatureFlags.set(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT, false);
        mFeatureFlags.set(Flags.KEYGUARD_WM_STATE_REFACTOR, false);
        mSetFlagsRule.disableFlags(com.android.systemui.Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR);

        when(mNotificationShadeWindowController.getWindowRootView())
                .thenReturn(mNotificationShadeWindowView);
        when(mNotificationShadeWindowView.getWindowInsetsController())
                .thenReturn(mWindowInsetsController);

        mStatusBarKeyguardViewManager =
                new StatusBarKeyguardViewManager(
                        getContext(),
                        mViewMediatorCallback,
                        mLockPatternUtils,
                        mStatusBarStateController,
                        mock(ConfigurationController.class),
                        mKeyguardUpdateMonitor,
                        mDreamOverlayStateController,
                        mock(NavigationModeController.class),
                        mock(DockManager.class),
                        mNotificationShadeWindowController,
                        mKeyguardStateController,
                        mock(NotificationMediaManager.class),
                        mKeyguardMessageAreaFactory,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mFeatureFlags,
                        mPrimaryBouncerCallbackInteractor,
                        mPrimaryBouncerInteractor,
                        mBouncerView,
                        mAlternateBouncerInteractor,
                        mUdfpsOverlayInteractor,
                        mActivityStarter,
                        mock(KeyguardTransitionInteractor.class),
                        StandardTestDispatcher(null, null),
                        () -> mock(WindowManagerLockscreenVisibilityInteractor.class),
                        () -> mock(KeyguardDismissActionInteractor.class),
                        mSelectedUserInteractor) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };
        when(mViewRootImpl.getOnBackInvokedDispatcher())
                .thenReturn(mOnBackInvokedDispatcher);
        mStatusBarKeyguardViewManager.registerCentralSurfaces(
                mCentralSurfaces,
                mShadeViewController,
                new ShadeExpansionStateManager(),
                mBiometricUnlockController,
                mNotificationContainer,
                mBypassController);
        mStatusBarKeyguardViewManager.show(null);
        ArgumentCaptor<PrimaryBouncerExpansionCallback> callbackArgumentCaptor =
                ArgumentCaptor.forClass(PrimaryBouncerExpansionCallback.class);
        verify(mPrimaryBouncerCallbackInteractor).addBouncerExpansionCallback(
                callbackArgumentCaptor.capture());
        mBouncerExpansionCallback = callbackArgumentCaptor.getValue();
    }

    @Test
    public void dismissWithAction_AfterKeyguardGoneSetToFalse() {
        OnDismissAction action = () -> false;
        Runnable cancelAction = () -> {
        };
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, false /* afterKeyguardGone */);
        verify(mPrimaryBouncerInteractor).setDismissAction(eq(action), eq(cancelAction));
        verify(mPrimaryBouncerInteractor).show(eq(true));
    }

    @Test
    public void showBouncer_onlyWhenShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */);
        verify(mPrimaryBouncerInteractor, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.Password);
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */);
        verify(mPrimaryBouncerInteractor, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_showsTheBouncer() {
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */);
        verify(mPrimaryBouncerInteractor).show(eq(true));
    }

    @Test
    public void onPanelExpansionChanged_neverShowsDuringHintAnimation() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_propagatesToBouncerOnlyIfShowing() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(eq(0.5f));

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(/* fraction= */ 0.6f, /* expanded= */ false, /* tracking= */ true));
        verify(mPrimaryBouncerInteractor).setPanelExpansion(eq(0.6f));
    }

    @Test
    public void onPanelExpansionChanged_duplicateEventsAreIgnored() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor).setPanelExpansion(eq(0.5f));

        reset(mPrimaryBouncerInteractor);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_hideBouncer_afterKeyguardHidden() {
        mStatusBarKeyguardViewManager.hide(0, 0);
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(true);

        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor).setPanelExpansion(eq(EXPANSION_HIDDEN));
    }

    @Test
    public void onPanelExpansionChanged_showsBouncerWhenSwiping() {
        mKeyguardStateController.setCanDismissLockScreen(false);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor).show(eq(false));

        // But not when it's already visible
        reset(mPrimaryBouncerInteractor);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).show(eq(false));

        // Or animating away
        reset(mPrimaryBouncerInteractor);
        when(mPrimaryBouncerInteractor.isAnimatingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).show(eq(false));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenWakeAndUnlock() {
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenDismissBouncer() {
        // Since KeyguardBouncer.EXPANSION_VISIBLE = 0 panel expansion, if the unlock is dismissing
        // the bouncer, there may be an onPanelExpansionChanged(0) call to collapse the panel
        // which would mistakenly cause the bouncer to show briefly before its visibility
        // is set to hide. Therefore, we don't want to propagate panelExpansionChanged to the
        // bouncer if the bouncer is dismissing as a result of a biometric unlock.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenOccluded() {
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenGoingAway() {
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenShowBouncer() {
        // Since KeyguardBouncer.EXPANSION_VISIBLE = 0 panel expansion, if the unlock is dismissing
        // the bouncer, there may be an onPanelExpansionChanged(0) call to collapse the panel
        // which would mistakenly cause the bouncer to show briefly before its visibility
        // is set to hide. Therefore, we don't want to propagate panelExpansionChanged to the
        // bouncer if the bouncer is dismissing as a result of a biometric unlock.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_SHOW_BOUNCER);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenShadeLocked() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void setOccluded_onKeyguardOccludedChangedCalled() {
        clearInvocations(mKeyguardStateController);
        clearInvocations(mKeyguardUpdateMonitor);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, false);

        clearInvocations(mKeyguardUpdateMonitor);
        clearInvocations(mKeyguardStateController);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);

        clearInvocations(mKeyguardUpdateMonitor);
        clearInvocations(mKeyguardStateController);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, false);
    }

    @Test
    public void setOccluded_isInLaunchTransition_onKeyguardOccludedChangedCalled() {
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);
    }

    @Test
    public void setOccluded_isLaunchingActivityOverLockscreen_onKeyguardOccludedChangedCalled() {
        when(mCentralSurfaces.isLaunchingActivityOverLockscreen()).thenReturn(true);
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);
    }

    @Test
    public void testHiding_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(true);
        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    public void testHidingBouncer_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(true);

        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    public void testHiding_doesntCancelWhenShowing() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action).onDismiss();
        verify(cancelAction, never()).run();
    }

    @Test
    public void testShowing_whenAlternateAuthShowing() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        assertTrue(
                "Is showing not accurate when alternative bouncer is visible",
                mStatusBarKeyguardViewManager.isBouncerShowing());
    }

    @Test
    public void testWillBeShowing_whenAlternateAuthShowing() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        assertTrue(
                "Is or will be showing not accurate when alternate bouncer is visible",
                mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing());
    }

    @Test
    public void testHideAlternateBouncer_onShowPrimaryBouncer() {
        reset(mAlternateBouncerInteractor);

        // GIVEN alt bouncer is showing
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // WHEN showBouncer is called
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true);

        // THEN alt bouncer should be hidden
        verify(mAlternateBouncerInteractor).hide();
    }

    @Test
    public void testBouncerIsOrWillBeShowing_whenBouncerIsInTransit() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(true);

        assertTrue(
                "Is or will be showing should be true when bouncer is in transit",
                mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing());
    }

    @Test
    public void testShowAltAuth_unlockingWithBiometricNotAllowed() {
        // GIVEN cannot use alternate bouncer
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.canShowAlternateBouncerForFingerprint()).thenReturn(false);

        // WHEN showGenericBouncer is called
        final boolean scrimmed = true;
        mStatusBarKeyguardViewManager.showBouncer(scrimmed);

        // THEN regular bouncer is shown
        verify(mPrimaryBouncerInteractor).show(eq(scrimmed));
    }

    @Test
    public void testShowAlternateBouncer_unlockingWithBiometricAllowed() {
        // GIVEN will show alternate bouncer
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.show()).thenReturn(true);

        // WHEN showGenericBouncer is called
        mStatusBarKeyguardViewManager.showBouncer(true);

        // THEN alt auth bouncer is shown
        verify(mAlternateBouncerInteractor).show();
        verify(mPrimaryBouncerInteractor, never()).show(anyBoolean());
    }

    @Test
    public void testUpdateResources_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateResources();

        verify(mPrimaryBouncerInteractor).updateResources();
    }

    @Test
    public void updateKeyguardPosition_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateKeyguardPosition(1.0f);

        verify(mPrimaryBouncerInteractor).setKeyguardPosition(1.0f);
    }

    @Test
    public void testIsBouncerInTransit() {
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(true);
        Truth.assertThat(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).isTrue();
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(false);
        Truth.assertThat(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).isFalse();
    }

    private static ShadeExpansionChangeEvent expansionEvent(
            float fraction, boolean expanded, boolean tracking) {
        return new ShadeExpansionChangeEvent(
                fraction, expanded, tracking, /* dragDownPxAmount= */ 0f);
    }

    @Test
    public void testPredictiveBackCallback_registration() {
        /* verify that a predictive back callback is registered when the bouncer becomes visible */
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mBackCallbackCaptor.capture());

        /* verify that the same callback is unregistered when the bouncer becomes invisible */
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mOnBackInvokedDispatcher).unregisterOnBackInvokedCallback(
                eq(mBackCallbackCaptor.getValue()));
    }

    @Test
    public void testPredictiveBackCallback_invocationHidesBouncer() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mBackCallbackCaptor.capture());

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        when(mCentralSurfaces.shouldKeyguardHideImmediately()).thenReturn(true);
        /* invoke the back callback directly */
        mBackCallbackCaptor.getValue().onBackInvoked();

        /* verify that the bouncer will be hidden as a result of the invocation */
        verify(mCentralSurfaces).setBouncerShowing(eq(false));
    }

    @Test
    public void testPredictiveBackCallback_noBackAnimationForFullScreenBouncer() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt()))
                .thenReturn(KeyguardSecurityModel.SecurityMode.SimPin);
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mBackCallbackCaptor.capture());
        assertTrue(mBackCallbackCaptor.getValue() instanceof OnBackAnimationCallback);

        OnBackAnimationCallback backCallback =
                (OnBackAnimationCallback) mBackCallbackCaptor.getValue();

        BackEvent event = new BackEvent(0, 0, 0, BackEvent.EDGE_LEFT);
        backCallback.onBackStarted(event);
        verify(mBouncerViewDelegateBackCallback, never()).onBackStarted(any());
    }

    @Test
    public void testPredictiveBackCallback_forwardsBackDispatches() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mBackCallbackCaptor.capture());
        assertTrue(mBackCallbackCaptor.getValue() instanceof OnBackAnimationCallback);

        OnBackAnimationCallback backCallback =
                (OnBackAnimationCallback) mBackCallbackCaptor.getValue();

        BackEvent event = new BackEvent(0, 0, 0, BackEvent.EDGE_LEFT);
        backCallback.onBackStarted(event);
        verify(mBouncerViewDelegateBackCallback).onBackStarted(eq(event));

        backCallback.onBackProgressed(event);
        verify(mBouncerViewDelegateBackCallback).onBackProgressed(eq(event));

        backCallback.onBackInvoked();
        verify(mBouncerViewDelegateBackCallback).onBackInvoked();

        backCallback.onBackCancelled();
        verify(mBouncerViewDelegateBackCallback).onBackCancelled();
    }

    @Test
    public void testReportBouncerOnDreamWhenVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        assertFalse(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(true);
        assertTrue(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
    }

    @Test
    public void testReportBouncerOnDreamWhenNotVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(false);
        assertFalse(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(false);
        assertFalse(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
    }

    @Test
    public void testHideTaskbar() {
        when(mTaskbarDelegate.isInitialized()).thenReturn(true);
        mStatusBarKeyguardViewManager.setTaskbarDelegate(mTaskbarDelegate);
        mStatusBarKeyguardViewManager.updateNavigationBarVisibility(false);
        verify(mWindowInsetsController).hide(WindowInsets.Type.navigationBars());
    }

    @Test
    public void hideAlternateBouncer_beforeCentralSurfacesRegistered() {
        mStatusBarKeyguardViewManager =
                new StatusBarKeyguardViewManager(
                        getContext(),
                        mViewMediatorCallback,
                        mLockPatternUtils,
                        mStatusBarStateController,
                        mock(ConfigurationController.class),
                        mKeyguardUpdateMonitor,
                        mDreamOverlayStateController,
                        mock(NavigationModeController.class),
                        mock(DockManager.class),
                        mock(NotificationShadeWindowController.class),
                        mKeyguardStateController,
                        mock(NotificationMediaManager.class),
                        mKeyguardMessageAreaFactory,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mFeatureFlags,
                        mPrimaryBouncerCallbackInteractor,
                        mPrimaryBouncerInteractor,
                        mBouncerView,
                        mAlternateBouncerInteractor,
                        mUdfpsOverlayInteractor,
                        mActivityStarter,
                        mock(KeyguardTransitionInteractor.class),
                        StandardTestDispatcher(null, null),
                        () -> mock(WindowManagerLockscreenVisibilityInteractor.class),
                        () -> mock(KeyguardDismissActionInteractor.class),
                        mSelectedUserInteractor) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };

        // the following call before registering centralSurfaces should NOT throw a NPE:
        mStatusBarKeyguardViewManager.hideAlternateBouncer(true);
    }

    @Test
    public void testResetHideBouncerWhenShowing_alternateBouncerHides() {
        // GIVEN the keyguard is showing
        reset(mAlternateBouncerInteractor);
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        // WHEN SBKV is reset with hideBouncerWhenShowing=true
        mStatusBarKeyguardViewManager.reset(true);

        // THEN alternate bouncer is hidden
        verify(mAlternateBouncerInteractor).hide();
    }

    @Test
    public void testResetHideBouncerWhenShowingIsFalse_alternateBouncerHides() {
        // GIVEN the keyguard is showing
        reset(mAlternateBouncerInteractor);
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        // WHEN SBKV is reset with hideBouncerWhenShowing=false
        mStatusBarKeyguardViewManager.reset(false);

        // THEN alternate bouncer is NOT hidden
        verify(mAlternateBouncerInteractor, never()).hide();
    }

    @Test
    public void testResetBouncerAnimatingAway() {
        reset(mPrimaryBouncerInteractor);
        when(mPrimaryBouncerInteractor.isAnimatingAway()).thenReturn(true);

        mStatusBarKeyguardViewManager.reset(true);

        verify(mPrimaryBouncerInteractor, never()).hide();
    }

    @Test
    public void handleDispatchTouchEvent_alternateBouncerViewFlagEnabled() {
        mStatusBarKeyguardViewManager.addCallback(mCallback);

        // GIVEN alternate bouncer view flag enabled & the alternate bouncer is visible
        mSetFlagsRule.enableFlags(com.android.systemui.Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // THEN the touch is not acted upon
        verify(mCallback, never()).onTouch(any());
    }

    @Test
    public void onInterceptTouch_alternateBouncerViewFlagEnabled() {
        // GIVEN alternate bouncer view flag enabled & the alternate bouncer is visible
        mSetFlagsRule.enableFlags(com.android.systemui.Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // THEN the touch is not intercepted
        assertFalse(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        ));
    }

    @Test
    public void handleDispatchTouchEvent_alternateBouncerNotVisible() {
        mStatusBarKeyguardViewManager.addCallback(mCallback);

        // GIVEN the alternate bouncer is visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);

        // THEN handleDispatchTouchEvent doesn't use the touches
        assertFalse(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        ));
        assertFalse(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
        ));
        assertFalse(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        ));

        // THEN the touch is not acted upon
        verify(mCallback, never()).onTouch(any());
    }

    @Test
    public void handleDispatchTouchEvent_shouldInterceptTouchAndHandleTouch() {
        mStatusBarKeyguardViewManager.addCallback(mCallback);

        // GIVEN the alternate bouncer is visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // GIVEN all touches are NOT the udfps overlay
        when(mUdfpsOverlayInteractor.isTouchWithinUdfpsArea(any())).thenReturn(false);

        // THEN handleDispatchTouchEvent eats/intercepts the touches so motion events aren't sent
        // to its child views (handleDispatchTouchEvent returns true)
        assertTrue(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        ));
        assertTrue(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
        ));
        assertTrue(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        ));

        // THEN the touch is acted upon once for each dispatchTOuchEvent call
        verify(mCallback, times(3)).onTouch(any());
    }

    @Test
    public void handleDispatchTouchEvent_shouldInterceptTouchButNotHandleTouch() {
        mStatusBarKeyguardViewManager.addCallback(mCallback);

        // GIVEN the alternate bouncer is visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // GIVEN all touches are within the udfps overlay
        when(mUdfpsOverlayInteractor.isTouchWithinUdfpsArea(any())).thenReturn(true);

        // THEN handleDispatchTouchEvent eats/intercepts the touches so motion events aren't sent
        // to its child views (handleDispatchTouchEvent returns true)
        assertTrue(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        ));
        assertTrue(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
        ));
        assertTrue(mStatusBarKeyguardViewManager.dispatchTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        ));

        // THEN the touch is NOT acted upon at the moment
        verify(mCallback, never()).onTouch(any());
    }

    @Test
    public void shouldInterceptTouch_alternateBouncerNotVisible() {
        // GIVEN the alternate bouncer is not visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);

        // THEN no motion events are intercepted
        assertFalse(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        ));
        assertFalse(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
        ));
        assertFalse(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        ));
    }

    @Test
    public void shouldInterceptTouch_alternateBouncerVisible() {
        // GIVEN the alternate bouncer is visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // THEN all motion events are intercepted
        assertTrue(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        ));
        assertTrue(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
        ));
        assertTrue(mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        ));
    }

    @Test
    public void alternateBouncerToShowPrimaryBouncer_updatesScrimControllerOnce() {
        // GIVEN the alternate bouncer has shown and calls to hide()  will result in successfully
        // hiding it
        when(mAlternateBouncerInteractor.hide()).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);

        // WHEN request to show primary bouncer
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true);

        // THEN the scrim isn't updated from StatusBarKeyguardViewManager
        verify(mCentralSurfaces, never()).updateScrimController();
    }

    @Test
    public void alternateBouncerOnTouch_actionDownThenUp_noMinTimeShown_noHideAltBouncer() {
        reset(mAlternateBouncerInteractor);

        // GIVEN the alternate bouncer has shown for a minimum amount of time
        when(mAlternateBouncerInteractor.hasAlternateBouncerShownWithMinTime()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        when(mUdfpsOverlayInteractor.isTouchWithinUdfpsArea(any())).thenReturn(false);

        // WHEN ACTION_DOWN and ACTION_UP touch event comes
        boolean touchHandledDown = mStatusBarKeyguardViewManager.onTouch(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0));
        when(mAlternateBouncerInteractor.getReceivedDownTouch()).thenReturn(true);
        boolean touchHandledUp = mStatusBarKeyguardViewManager.onTouch(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0));

        // THEN the touches are handled (doesn't let touches through to underlying views)
        assertTrue(touchHandledDown);
        assertTrue(touchHandledUp);

        // THEN alternate bouncer does NOT attempt to hide since min showing time wasn't met
        verify(mAlternateBouncerInteractor, never()).hide();
    }

    @Test
    public void alternateBouncerOnTouch_actionDownThenUp_handlesTouch_hidesAltBouncer() {
        reset(mAlternateBouncerInteractor);

        // GIVEN the alternate bouncer has shown for a minimum amount of time
        when(mAlternateBouncerInteractor.hasAlternateBouncerShownWithMinTime()).thenReturn(true);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        when(mUdfpsOverlayInteractor.isTouchWithinUdfpsArea(any())).thenReturn(false);

        // WHEN ACTION_DOWN and ACTION_UP touch event comes
        boolean touchHandledDown = mStatusBarKeyguardViewManager.onTouch(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0));
        when(mAlternateBouncerInteractor.getReceivedDownTouch()).thenReturn(true);
        boolean touchHandledUp = mStatusBarKeyguardViewManager.onTouch(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0));

        // THEN the touches are handled
        assertTrue(touchHandledDown);
        assertTrue(touchHandledUp);

        // THEN alternate bouncer attempts to hide
        verify(mAlternateBouncerInteractor).hide();
    }

    @Test
    public void alternateBouncerOnTouch_actionUp_doesNotHideAlternateBouncer() {
        reset(mAlternateBouncerInteractor);

        // GIVEN the alternate bouncer has shown for a minimum amount of time
        when(mAlternateBouncerInteractor.hasAlternateBouncerShownWithMinTime()).thenReturn(true);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        when(mUdfpsOverlayInteractor.isTouchWithinUdfpsArea(any())).thenReturn(false);

        // WHEN only ACTION_UP touch event comes
        mStatusBarKeyguardViewManager.onTouch(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0));

        // THEN the alternateBouncer doesn't hide
        verify(mAlternateBouncerInteractor, never()).hide();
    }

    @Test
    public void onTrustChanged_hideAlternateBouncerAndClearMessageArea() {
        // GIVEN keyguard update monitor callback is registered
        verify(mKeyguardUpdateMonitor).registerCallback(mKeyguardUpdateMonitorCallback.capture());

        reset(mKeyguardUpdateMonitor);
        reset(mKeyguardMessageAreaController);

        // GIVEN alternate bouncer state = not visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);

        // WHEN the device is trusted by active unlock
        mKeyguardUpdateMonitorCallback.getValue().onTrustGrantedForCurrentUser(
                true,
                true,
                new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD
                        | TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE),
                null
        );

        // THEN the false visibility state is propagated to the keyguardUpdateMonitor
        verify(mKeyguardUpdateMonitor).setAlternateBouncerShowing(eq(false));

        // THEN message area visibility updated to FALSE with empty message
        verify(mKeyguardMessageAreaController).setIsVisible(eq(false));
        verify(mKeyguardMessageAreaController).setMessage(eq(""));
    }

    @Test
    public void testShowBouncerOrKeyguard_needsFullScreen() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.SimPin);
        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(false);
        verify(mCentralSurfaces).hideKeyguard();
        verify(mPrimaryBouncerInteractor).show(true);
    }

    @Test
    public void testShowBouncerOrKeyguard_needsFullScreen_bouncerAlreadyShowing() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.SimPin);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(false);
        verify(mCentralSurfaces, never()).hideKeyguard();
        verify(mPrimaryBouncerInteractor, never()).show(true);
    }
}
