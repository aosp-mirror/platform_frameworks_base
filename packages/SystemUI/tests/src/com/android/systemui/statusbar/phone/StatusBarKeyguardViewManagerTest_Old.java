/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.systemui.flags.Flags.MODERN_BOUNCER;
import static com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN;
import static com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.EXPANSION_VISIBLE;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
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
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.data.BouncerView;
import com.android.systemui.keyguard.data.BouncerViewDelegate;
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerCallbackInteractor;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;

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

/**
 * StatusBarKeyguardViewManager Test with deprecated KeyguardBouncer.java.
 * TODO: Delete when deleting {@link KeyguardBouncer}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest_Old extends SysuiTestCase {
    private static final ShadeExpansionChangeEvent EXPANSION_EVENT =
            expansionEvent(/* fraction= */ 0.5f, /* expanded= */ false, /* tracking= */ true);

    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ViewGroup mContainer;
    @Mock private NotificationPanelViewController mNotificationPanelView;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private View mNotificationContainer;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private KeyguardBouncer.Factory mKeyguardBouncerFactory;
    @Mock private KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    @Mock private KeyguardMessageAreaController mKeyguardMessageAreaController;
    @Mock private KeyguardBouncer mPrimaryBouncer;
    @Mock private KeyguardMessageArea mKeyguardMessageArea;
    @Mock private ShadeController mShadeController;
    @Mock private SysUIUnfoldComponent mSysUiUnfoldComponent;
    @Mock private DreamOverlayStateController mDreamOverlayStateController;
    @Mock private LatencyTracker mLatencyTracker;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock private PrimaryBouncerCallbackInteractor mPrimaryBouncerCallbackInteractor;
    @Mock private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock private BouncerView mBouncerView;
    @Mock private BouncerViewDelegate mBouncerViewDelegate;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback
            mBouncerExpansionCallback;
    private FakeKeyguardStateController mKeyguardStateController =
            spy(new FakeKeyguardStateController());

    @Mock private ViewRootImpl mViewRootImpl;
    @Mock private WindowOnBackInvokedDispatcher mOnBackInvokedDispatcher;
    @Captor
    private ArgumentCaptor<OnBackInvokedCallback> mOnBackInvokedCallback;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardBouncerFactory.create(
                any(ViewGroup.class),
                any(PrimaryBouncerExpansionCallback.class)))
                .thenReturn(mPrimaryBouncer);
        when(mCentralSurfaces.getBouncerContainer()).thenReturn(mContainer);
        when(mContainer.findViewById(anyInt())).thenReturn(mKeyguardMessageArea);
        when(mKeyguardMessageAreaFactory.create(any(KeyguardMessageArea.class)))
                .thenReturn(mKeyguardMessageAreaController);
        when(mBouncerView.getDelegate()).thenReturn(mBouncerViewDelegate);

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
                        mKeyguardBouncerFactory,
                        mKeyguardMessageAreaFactory,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mFeatureFlags,
                        mPrimaryBouncerCallbackInteractor,
                        mPrimaryBouncerInteractor,
                        mBouncerView,
                        mAlternateBouncerInteractor) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };
        when(mViewRootImpl.getOnBackInvokedDispatcher())
                .thenReturn(mOnBackInvokedDispatcher);
        mStatusBarKeyguardViewManager.registerCentralSurfaces(
                mCentralSurfaces,
                mNotificationPanelView,
                new ShadeExpansionStateManager(),
                mBiometricUnlockController,
                mNotificationContainer,
                mBypassController);
        mStatusBarKeyguardViewManager.show(null);
        ArgumentCaptor<PrimaryBouncerExpansionCallback> callbackArgumentCaptor =
                ArgumentCaptor.forClass(PrimaryBouncerExpansionCallback.class);
        verify(mKeyguardBouncerFactory).create(any(ViewGroup.class),
                callbackArgumentCaptor.capture());
        mBouncerExpansionCallback = callbackArgumentCaptor.getValue();
    }

    @Test
    public void dismissWithAction_AfterKeyguardGoneSetToFalse() {
        OnDismissAction action = () -> false;
        Runnable cancelAction = () -> {};
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, false /* afterKeyguardGone */);
        verify(mPrimaryBouncer).showWithDismissAction(eq(action), eq(cancelAction));
    }

    @Test
    public void showBouncer_onlyWhenShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */);
        verify(mPrimaryBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mPrimaryBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mPrimaryBouncer.isSecure()).thenReturn(true);
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */);
        verify(mPrimaryBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mPrimaryBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_showsTheBouncer() {
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */);
        verify(mPrimaryBouncer).show(anyBoolean(), eq(true));
    }

    @Test
    public void onPanelExpansionChanged_neverShowsDuringHintAnimation() {
        when(mNotificationPanelView.isUnlockHintRunning()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_propagatesToBouncerOnlyIfShowing() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer, never()).setExpansion(eq(0.5f));

        when(mPrimaryBouncer.isShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(/* fraction= */ 0.6f, /* expanded= */ false, /* tracking= */ true));
        verify(mPrimaryBouncer).setExpansion(eq(0.6f));
    }

    @Test
    public void onPanelExpansionChanged_duplicateEventsAreIgnored() {
        when(mPrimaryBouncer.isShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer).setExpansion(eq(0.5f));

        reset(mPrimaryBouncer);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer, never()).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_hideBouncer_afterKeyguardHidden() {
        mStatusBarKeyguardViewManager.hide(0, 0);
        when(mPrimaryBouncer.inTransit()).thenReturn(true);

        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer).setExpansion(eq(EXPANSION_HIDDEN));
    }

    @Test
    public void onPanelExpansionChanged_showsBouncerWhenSwiping() {
        mKeyguardStateController.setCanDismissLockScreen(false);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer).show(eq(false), eq(false));

        // But not when it's already visible
        reset(mPrimaryBouncer);
        when(mPrimaryBouncer.isShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer, never()).show(eq(false), eq(false));

        // Or animating away
        reset(mPrimaryBouncer);
        when(mPrimaryBouncer.isAnimatingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncer, never()).show(eq(false), eq(false));
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
        verify(mPrimaryBouncer, never()).setExpansion(anyFloat());
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
        verify(mPrimaryBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenOccluded() {
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncer, never()).setExpansion(anyFloat());
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
        verify(mPrimaryBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenShadeLocked() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void setOccluded_animatesPanelExpansion_onlyIfBouncerHidden() {
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mCentralSurfaces).animateKeyguardUnoccluding();

        when(mPrimaryBouncer.isShowing()).thenReturn(true);
        clearInvocations(mCentralSurfaces);
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mCentralSurfaces, never()).animateKeyguardUnoccluding();
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

        when(mPrimaryBouncer.isShowing()).thenReturn(false);
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

        when(mPrimaryBouncer.isShowing()).thenReturn(false);
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
    public void testBouncerIsOrWillBeShowing_whenBouncerIsInTransit() {
        when(mPrimaryBouncer.isShowing()).thenReturn(false);
        when(mPrimaryBouncer.inTransit()).thenReturn(true);

        assertTrue(
                "Is or will be showing should be true when bouncer is in transit",
                mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing());
    }

    @Test
    public void testUpdateResources_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateResources();

        verify(mPrimaryBouncer).updateResources();
    }

    @Test
    public void updateKeyguardPosition_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateKeyguardPosition(1.0f);

        verify(mPrimaryBouncer).updateKeyguardPosition(1.0f);
    }

    @Test
    public void testIsBouncerInTransit() {
        when(mPrimaryBouncer.inTransit()).thenReturn(true);
        Truth.assertThat(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).isTrue();
        when(mPrimaryBouncer.inTransit()).thenReturn(false);
        Truth.assertThat(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).isFalse();
        mPrimaryBouncer = null;
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
                mOnBackInvokedCallback.capture());

        /* verify that the same callback is unregistered when the bouncer becomes invisible */
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mOnBackInvokedDispatcher).unregisterOnBackInvokedCallback(
                eq(mOnBackInvokedCallback.getValue()));
    }

    @Test
    public void testPredictiveBackCallback_invocationHidesBouncer() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                mOnBackInvokedCallback.capture());

        when(mPrimaryBouncer.isShowing()).thenReturn(true);
        when(mCentralSurfaces.shouldKeyguardHideImmediately()).thenReturn(true);
        /* invoke the back callback directly */
        mOnBackInvokedCallback.getValue().onBackInvoked();

        /* verify that the bouncer will be hidden as a result of the invocation */
        verify(mCentralSurfaces).setBouncerShowing(eq(false));
    }

    @Test
    public void testReportBouncerOnDreamWhenVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mCentralSurfaces).setBouncerShowingOverDream(false);
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mCentralSurfaces).setBouncerShowingOverDream(true);
    }

    @Test
    public void testReportBouncerOnDreamWhenNotVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mCentralSurfaces).setBouncerShowingOverDream(false);
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mCentralSurfaces).setBouncerShowingOverDream(false);
    }

    @Test
    public void flag_off_DoesNotCallBouncerInteractor() {
        when(mFeatureFlags.isEnabled(MODERN_BOUNCER)).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(false);
        verify(mPrimaryBouncerInteractor, never()).hide();
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
                        mKeyguardBouncerFactory,
                        mKeyguardMessageAreaFactory,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mFeatureFlags,
                        mPrimaryBouncerCallbackInteractor,
                        mPrimaryBouncerInteractor,
                        mBouncerView,
                        mAlternateBouncerInteractor) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };

        // the following call before registering centralSurfaces should NOT throw a NPE:
        mStatusBarKeyguardViewManager.hideAlternateBouncer(true);
    }
}
