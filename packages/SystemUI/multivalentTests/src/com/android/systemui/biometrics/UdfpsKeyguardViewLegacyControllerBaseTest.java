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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.DelayableExecutor;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class UdfpsKeyguardViewLegacyControllerBaseTest extends SysuiTestCase {
    // Dependencies
    protected @Mock UdfpsKeyguardViewLegacy mView;
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
    protected @Mock PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    protected @Mock AlternateBouncerInteractor mAlternateBouncerInteractor;
    protected @Mock UdfpsKeyguardAccessibilityDelegate mUdfpsKeyguardAccessibilityDelegate;
    protected @Mock SelectedUserInteractor mSelectedUserInteractor;
    protected @Mock KeyguardTransitionInteractor mKeyguardTransitionInteractor;

    protected FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    protected UdfpsKeyguardViewControllerLegacy mController;

    // Capture listeners so that they can be used to send events
    private @Captor ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;
    protected StatusBarStateController.StateListener mStatusBarStateListener;

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
        when(mShadeExpansionStateManager.addExpansionListener(any())).thenReturn(
                new ShadeExpansionChangeEvent(0, false, false, 0));
        mController = createUdfpsKeyguardViewController();
    }

    protected void sendStatusBarStateChanged(int statusBarState) {
        mStatusBarStateListener.onStateChanged(statusBarState);
    }

    protected void captureStatusBarStateListeners() {
        verify(mStatusBarStateController).addCallback(mStateListenerCaptor.capture());
        mStatusBarStateListener = mStateListenerCaptor.getValue();
    }

    protected void captureKeyguardStateControllerCallback() {
        verify(mKeyguardStateController).addCallback(
                mKeyguardStateControllerCallbackCaptor.capture());
        mKeyguardStateControllerCallback = mKeyguardStateControllerCallbackCaptor.getValue();
    }

    public UdfpsKeyguardViewControllerLegacy createUdfpsKeyguardViewController() {
        return createUdfpsKeyguardViewController(false);
    }

    public void captureKeyGuardViewManagerCallback() {
        verify(mStatusBarKeyguardViewManager).addCallback(
                mKeyguardViewManagerCallbackArgumentCaptor.capture());
        mKeyguardViewManagerCallback = mKeyguardViewManagerCallbackArgumentCaptor.getValue();
    }

    protected UdfpsKeyguardViewControllerLegacy createUdfpsKeyguardViewController(
            boolean useModernBouncer) {
        UdfpsKeyguardViewControllerLegacy controller = new UdfpsKeyguardViewControllerLegacy(
                mView,
                mStatusBarStateController,
                mStatusBarKeyguardViewManager,
                mKeyguardUpdateMonitor,
                mDumpManager,
                mLockscreenShadeTransitionController,
                mConfigurationController,
                mKeyguardStateController,
                mUnlockedScreenOffAnimationController,
                mDialogManager,
                mUdfpsController,
                mActivityLaunchAnimator,
                mPrimaryBouncerInteractor,
                mAlternateBouncerInteractor,
                mUdfpsKeyguardAccessibilityDelegate,
                mSelectedUserInteractor,
                mKeyguardTransitionInteractor);
        return controller;
    }
}
