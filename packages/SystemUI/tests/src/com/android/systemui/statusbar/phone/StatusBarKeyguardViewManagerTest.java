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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.FaceAuthScreenBrightnessController;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest extends SysuiTestCase {

    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private ViewGroup mContainer;
    @Mock
    private NotificationPanelViewController mNotificationPanelView;
    @Mock
    private BiometricUnlockController mBiometrucUnlockController;
    @Mock
    private DismissCallbackRegistry mDismissCallbackRegistry;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private View mNotificationContainer;
    @Mock
    private KeyguardBypassController mBypassController;
    @Mock
    private FaceAuthScreenBrightnessController mFaceAuthScreenBrightnessController;
    @Mock
    private KeyguardBouncer.Factory mKeyguardBouncerFactory;
    @Mock
    private KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    @Mock
    private KeyguardBouncer mBouncer;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock
    private KeyguardMessageArea mKeyguardMessageArea;

    private WakefulnessLifecycle mWakefulnessLifecycle;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardBouncerFactory.create(
                any(ViewGroup.class),
                any(KeyguardBouncer.BouncerExpansionCallback.class)))
                .thenReturn(mBouncer);

        when(mContainer.findViewById(anyInt())).thenReturn(mKeyguardMessageArea);
        mWakefulnessLifecycle = new WakefulnessLifecycle(getContext(), null);
        mStatusBarKeyguardViewManager = new StatusBarKeyguardViewManager(
                getContext(),
                mViewMediatorCallback,
                mLockPatternUtils,
                mStatusBarStateController,
                mock(ConfigurationController.class),
                mock(KeyguardUpdateMonitor.class),
                mock(NavigationModeController.class),
                mock(DockManager.class),
                mock(NotificationShadeWindowController.class),
                mKeyguardStateController,
                Optional.of(mFaceAuthScreenBrightnessController),
                mock(NotificationMediaManager.class),
                mKeyguardBouncerFactory,
                mWakefulnessLifecycle,
                mUnlockedScreenOffAnimationController,
                mKeyguardMessageAreaFactory);
        mStatusBarKeyguardViewManager.registerStatusBar(mStatusBar, mContainer,
                mNotificationPanelView, mBiometrucUnlockController,
                mNotificationContainer, mBypassController);
        mStatusBarKeyguardViewManager.show(null);
    }

    @Test
    public void dismissWithAction_AfterKeyguardGoneSetToFalse() {
        OnDismissAction action = () -> false;
        Runnable cancelAction = () -> {};
        mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                false /* afterKeyguardGone */);
        verify(mBouncer).showWithDismissAction(eq(action), eq(cancelAction));
    }

    @Test
    public void showBouncer_onlyWhenShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mBouncer.isSecure()).thenReturn(true);
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_showsTheBouncer() {
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer).show(anyBoolean(), eq(true));
    }

    @Test
    public void onPanelExpansionChanged_neverHidesFullscreenBouncer() {
        // TODO: StatusBar should not be here, mBouncer.isFullscreenBouncer() should do the same.
        when(mStatusBar.isFullScreenUserSwitcherState()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */,
                true /* tracking */);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_VISIBLE));
    }

    @Test
    public void onPanelExpansionChanged_neverHidesScrimmedBouncer() {
        when(mBouncer.isShowing()).thenReturn(true);
        when(mBouncer.isScrimmed()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */,
                true /* tracking */);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_VISIBLE));
    }

    @Test
    public void onPanelExpansionChanged_neverShowsDuringHintAnimation() {
        when(mNotificationPanelView.isUnlockHintRunning()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */,
                true /* tracking */);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_HIDDEN));
    }

    @Test
    public void onPanelExpansionChanged_propagatesToBouncer() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */,
                true /* tracking */);
        verify(mBouncer).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_showsBouncerWhenSwiping() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */,
                true /* tracking */);
        verify(mBouncer).show(eq(false), eq(false));

        // But not when it's already visible
        reset(mBouncer);
        when(mBouncer.isShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */, true /* tracking */);
        verify(mBouncer, never()).show(eq(false), eq(false));

        // Or animating away
        reset(mBouncer);
        when(mBouncer.isAnimatingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */, true /* tracking */);
        verify(mBouncer, never()).show(eq(false), eq(false));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenOccluded() {
        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animate */);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(0.5f /* expansion */,
                true /* tracking */);
        verify(mBouncer, never()).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenWakeAndUnlock() {
        when(mBiometrucUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(KeyguardBouncer.EXPANSION_VISIBLE,
                false /* tracking */);
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenLaunchingApp() {
        when(mStatusBar.isInLaunchTransition()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(KeyguardBouncer.EXPANSION_VISIBLE,
                false /* tracking */);
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void setOccluded_animatesPanelExpansion_onlyIfBouncerHidden() {
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mStatusBar).animateKeyguardUnoccluding();

        when(mBouncer.isShowing()).thenReturn(true);
        clearInvocations(mStatusBar);
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mStatusBar, never()).animateKeyguardUnoccluding();
    }

    @Test
    public void testHiding_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hideBouncer(true);
        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    public void testHiding_doesntCancelWhenShowing() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action).onDismiss();
        verify(cancelAction, never()).run();
    }

    @Test
    public void testUpdateResources_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateResources();

        verify(mBouncer).updateResources();
    }

    @Test
    public void updateKeyguardPosition_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateKeyguardPosition(1.0f);

        verify(mBouncer).updateKeyguardPosition(1.0f);
    }
}
