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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest extends SysuiTestCase {

    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private KeyguardBouncer mBouncer;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private ViewGroup mContainer;
    @Mock
    private NotificationPanelView mNotificationPanelView;
    @Mock
    private BiometricUnlockController mBiometrucUnlockController;
    @Mock
    private DismissCallbackRegistry mDismissCallbackRegistry;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(StatusBarWindowController.class);
        mStatusBarKeyguardViewManager = new TestableStatusBarKeyguardViewManager(getContext(),
                mViewMediatorCallback, mLockPatternUtils);
        mStatusBarKeyguardViewManager.registerStatusBar(mStatusBar, mContainer,
                mNotificationPanelView, mBiometrucUnlockController, mDismissCallbackRegistry);
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
        mStatusBar.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mBouncer.isSecure()).thenReturn(true);
        mStatusBar.showBouncer(true /* scrimmed */);
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
        when(mBouncer.isShowingScrimmed()).thenReturn(true);
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
        when(mStatusBar.isKeyguardCurrentlySecure()).thenReturn(true);
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

    private class TestableStatusBarKeyguardViewManager extends StatusBarKeyguardViewManager {

        public TestableStatusBarKeyguardViewManager(Context context,
                ViewMediatorCallback callback,
                LockPatternUtils lockPatternUtils) {
            super(context, callback, lockPatternUtils);
        }

        @Override
        public void registerStatusBar(StatusBar statusBar, ViewGroup container,
                NotificationPanelView notificationPanelView,
                BiometricUnlockController fingerprintUnlockController,
                DismissCallbackRegistry dismissCallbackRegistry) {
            super.registerStatusBar(statusBar, container, notificationPanelView,
                    fingerprintUnlockController, dismissCallbackRegistry);
            mBouncer = StatusBarKeyguardViewManagerTest.this.mBouncer;
        }
    }
}