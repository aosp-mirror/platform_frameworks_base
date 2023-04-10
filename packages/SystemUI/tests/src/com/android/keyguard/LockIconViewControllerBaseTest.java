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

package com.android.keyguard;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.systemui.flags.Flags.DOZING_MIGRATION_1;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedStateListDrawable;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.doze.util.BurnInHelperKt;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class LockIconViewControllerBaseTest extends SysuiTestCase {
    protected static final String UNLOCKED_LABEL = "unlocked";
    protected static final String LOCKED_LABEL = "locked";
    protected static final int PADDING = 10;

    protected MockitoSession mStaticMockSession;

    protected @Mock LockIconView mLockIconView;
    protected @Mock AnimatedStateListDrawable mIconDrawable;
    protected @Mock Context mContext;
    protected @Mock Resources mResources;
    protected @Mock(answer = Answers.RETURNS_DEEP_STUBS) WindowManager mWindowManager;
    protected @Mock StatusBarStateController mStatusBarStateController;
    protected @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    protected @Mock KeyguardViewController mKeyguardViewController;
    protected @Mock KeyguardStateController mKeyguardStateController;
    protected @Mock FalsingManager mFalsingManager;
    protected @Mock AuthController mAuthController;
    protected @Mock DumpManager mDumpManager;
    protected @Mock AccessibilityManager mAccessibilityManager;
    protected @Mock ConfigurationController mConfigurationController;
    protected @Mock VibratorHelper mVibrator;
    protected @Mock AuthRippleController mAuthRippleController;
    protected @Mock FeatureFlags mFeatureFlags;
    protected @Mock KeyguardTransitionRepository mTransitionRepository;
    protected @Mock CommandQueue mCommandQueue;
    protected FakeExecutor mDelayableExecutor = new FakeExecutor(new FakeSystemClock());

    protected LockIconViewController mUnderTest;

    // Capture listeners so that they can be used to send events
    @Captor protected ArgumentCaptor<View.OnAttachStateChangeListener> mAttachCaptor =
            ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);

    @Captor protected ArgumentCaptor<KeyguardStateController.Callback> mKeyguardStateCaptor =
            ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
    protected KeyguardStateController.Callback mKeyguardStateCallback;

    @Captor protected ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateCaptor =
            ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
    protected StatusBarStateController.StateListener mStatusBarStateListener;

    @Captor protected ArgumentCaptor<AuthController.Callback> mAuthControllerCallbackCaptor;
    protected AuthController.Callback mAuthControllerCallback;

    @Captor protected ArgumentCaptor<KeyguardUpdateMonitorCallback>
            mKeyguardUpdateMonitorCallbackCaptor =
            ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
    protected KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback;

    @Captor protected ArgumentCaptor<Point> mPointCaptor;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = mockitoSession()
                .mockStatic(BurnInHelperKt.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);

        setupLockIconViewMocks();
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(WindowManager.class)).thenReturn(mWindowManager);
        Rect windowBounds = new Rect(0, 0, 800, 1200);
        when(mWindowManager.getCurrentWindowMetrics().getBounds()).thenReturn(windowBounds);
        when(mResources.getString(R.string.accessibility_unlock_button)).thenReturn(UNLOCKED_LABEL);
        when(mResources.getString(R.string.accessibility_lock_icon)).thenReturn(LOCKED_LABEL);
        when(mResources.getDrawable(anyInt(), any())).thenReturn(mIconDrawable);
        when(mResources.getDimensionPixelSize(R.dimen.lock_icon_padding)).thenReturn(PADDING);
        when(mAuthController.getScaleFactor()).thenReturn(1f);

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);

        mUnderTest = new LockIconViewController(
                mLockIconView,
                mStatusBarStateController,
                mKeyguardUpdateMonitor,
                mKeyguardViewController,
                mKeyguardStateController,
                mFalsingManager,
                mAuthController,
                mDumpManager,
                mAccessibilityManager,
                mConfigurationController,
                mDelayableExecutor,
                mVibrator,
                mAuthRippleController,
                mResources,
                new KeyguardTransitionInteractor(mTransitionRepository),
                new KeyguardInteractor(
                        new FakeKeyguardRepository(),
                        mCommandQueue,
                        mFeatureFlags,
                        new FakeKeyguardBouncerRepository()
                ),
                mFeatureFlags
        );
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    protected Pair<Float, Point> setupUdfps() {
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(true);
        final Point udfpsLocation = new Point(50, 75);
        final float radius = 33f;
        when(mAuthController.getUdfpsLocation()).thenReturn(udfpsLocation);
        when(mAuthController.getUdfpsRadius()).thenReturn(radius);

        return new Pair(radius, udfpsLocation);
    }

    protected void setupShowLockIcon() {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(0f);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
    }

    protected void captureAuthControllerCallback() {
        verify(mAuthController).addCallback(mAuthControllerCallbackCaptor.capture());
        mAuthControllerCallback = mAuthControllerCallbackCaptor.getValue();
    }

    protected void captureKeyguardStateCallback() {
        verify(mKeyguardStateController).addCallback(mKeyguardStateCaptor.capture());
        mKeyguardStateCallback = mKeyguardStateCaptor.getValue();
    }

    protected void captureStatusBarStateListener() {
        verify(mStatusBarStateController).addCallback(mStatusBarStateCaptor.capture());
        mStatusBarStateListener = mStatusBarStateCaptor.getValue();
    }

    protected void captureKeyguardUpdateMonitorCallback() {
        verify(mKeyguardUpdateMonitor).registerCallback(
                mKeyguardUpdateMonitorCallbackCaptor.capture());
        mKeyguardUpdateMonitorCallback = mKeyguardUpdateMonitorCallbackCaptor.getValue();
    }

    protected void setupLockIconViewMocks() {
        when(mLockIconView.getResources()).thenReturn(mResources);
        when(mLockIconView.getContext()).thenReturn(mContext);
    }

    protected void resetLockIconView() {
        reset(mLockIconView);
        setupLockIconViewMocks();
    }

    protected void init(boolean useMigrationFlag) {
        when(mFeatureFlags.isEnabled(DOZING_MIGRATION_1)).thenReturn(useMigrationFlag);
        mUnderTest.init();

        verify(mLockIconView, atLeast(1)).addOnAttachStateChangeListener(mAttachCaptor.capture());
        mAttachCaptor.getValue().onViewAttachedToWindow(mLockIconView);
    }
}
