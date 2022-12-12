/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionListener;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

public class UdfpsKeyguardViewControllerBaseTest extends SysuiTestCase {
    // Dependencies
    protected @Mock UdfpsKeyguardView mView;
    protected @Mock Context mResourceContext;
    protected @Mock StatusBarStateController mStatusBarStateController;
    protected @Mock ShadeExpansionStateManager mShadeExpansionStateManager;
    protected @Mock StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    protected @Mock LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    protected @Mock DumpManager mDumpManager;
    protected @Mock DelayableExecutor mExecutor;
    protected @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    protected @Mock KeyguardStateController mKeyguardStateController;
    protected @Mock KeyguardViewMediator mKeyguardViewMediator;
    protected @Mock ConfigurationController mConfigurationController;
    protected @Mock UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    protected @Mock SystemUIDialogManager mDialogManager;
    protected @Mock UdfpsController mUdfpsController;
    protected @Mock ActivityLaunchAnimator mActivityLaunchAnimator;
    protected @Mock KeyguardBouncer mBouncer;
    protected @Mock PrimaryBouncerInteractor mPrimaryBouncerInteractor;

    protected FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    protected FakeSystemClock mSystemClock = new FakeSystemClock();

    protected UdfpsKeyguardViewController mController;

    // Capture listeners so that they can be used to send events
    private @Captor ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;
    protected StatusBarStateController.StateListener mStatusBarStateListener;

    private @Captor ArgumentCaptor<ShadeExpansionListener> mExpansionListenerCaptor;
    protected List<ShadeExpansionListener> mExpansionListeners;

    private @Captor ArgumentCaptor<StatusBarKeyguardViewManager.AlternateBouncer>
            mAlternateBouncerCaptor;
    protected StatusBarKeyguardViewManager.AlternateBouncer mAlternateBouncer;

    private @Captor ArgumentCaptor<KeyguardStateController.Callback>
            mKeyguardStateControllerCallbackCaptor;
    protected KeyguardStateController.Callback mKeyguardStateControllerCallback;

    private @Captor ArgumentCaptor<StatusBarKeyguardViewManager.KeyguardViewManagerCallback>
            mKeyguardViewManagerCallbackArgumentCaptor;
    protected StatusBarKeyguardViewManager.KeyguardViewManagerCallback mKeyguardViewManagerCallback;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mView.getContext()).thenReturn(mResourceContext);
        when(mResourceContext.getString(anyInt())).thenReturn("test string");
        when(mKeyguardViewMediator.isAnimatingScreenOff()).thenReturn(false);
        when(mView.getUnpausedAlpha()).thenReturn(255);
        mController = createUdfpsKeyguardViewController();
    }

    protected void sendStatusBarStateChanged(int statusBarState) {
        mStatusBarStateListener.onStateChanged(statusBarState);
    }

    protected void captureStatusBarStateListeners() {
        verify(mStatusBarStateController).addCallback(mStateListenerCaptor.capture());
        mStatusBarStateListener = mStateListenerCaptor.getValue();
    }

    protected void captureStatusBarExpansionListeners() {
        verify(mShadeExpansionStateManager, times(2))
                .addExpansionListener(mExpansionListenerCaptor.capture());
        // first (index=0) is from super class, UdfpsAnimationViewController.
        // second (index=1) is from UdfpsKeyguardViewController
        mExpansionListeners = mExpansionListenerCaptor.getAllValues();
    }

    protected void updateStatusBarExpansion(float fraction, boolean expanded) {
        ShadeExpansionChangeEvent event =
                new ShadeExpansionChangeEvent(
                        fraction, expanded, /* tracking= */ false, /* dragDownPxAmount= */ 0f);
        for (ShadeExpansionListener listener : mExpansionListeners) {
            listener.onPanelExpansionChanged(event);
        }
    }

    protected void captureAltAuthInterceptor() {
        verify(mStatusBarKeyguardViewManager).setAlternateBouncer(
                mAlternateBouncerCaptor.capture());
        mAlternateBouncer = mAlternateBouncerCaptor.getValue();
    }

    protected void captureKeyguardStateControllerCallback() {
        verify(mKeyguardStateController).addCallback(
                mKeyguardStateControllerCallbackCaptor.capture());
        mKeyguardStateControllerCallback = mKeyguardStateControllerCallbackCaptor.getValue();
    }

    public UdfpsKeyguardViewController createUdfpsKeyguardViewController() {
        return createUdfpsKeyguardViewController(false, false);
    }

    public void captureKeyGuardViewManagerCallback() {
        verify(mStatusBarKeyguardViewManager).addCallback(
                mKeyguardViewManagerCallbackArgumentCaptor.capture());
        mKeyguardViewManagerCallback = mKeyguardViewManagerCallbackArgumentCaptor.getValue();
    }

    protected UdfpsKeyguardViewController createUdfpsKeyguardViewController(
            boolean useModernBouncer, boolean useExpandedOverlay) {
        mFeatureFlags.set(Flags.MODERN_BOUNCER, useModernBouncer);
        mFeatureFlags.set(Flags.UDFPS_NEW_TOUCH_DETECTION, useExpandedOverlay);
        when(mStatusBarKeyguardViewManager.getPrimaryBouncer()).thenReturn(
                useModernBouncer ? null : mBouncer);
        UdfpsKeyguardViewController controller = new UdfpsKeyguardViewController(
                mView,
                mStatusBarStateController,
                mShadeExpansionStateManager,
                mStatusBarKeyguardViewManager,
                mKeyguardUpdateMonitor,
                mDumpManager,
                mLockscreenShadeTransitionController,
                mConfigurationController,
                mSystemClock,
                mKeyguardStateController,
                mUnlockedScreenOffAnimationController,
                mDialogManager,
                mUdfpsController,
                mActivityLaunchAnimator,
                mFeatureFlags,
                mPrimaryBouncerInteractor);
        return controller;
    }
}
