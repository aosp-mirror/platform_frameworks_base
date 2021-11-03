/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static com.android.keyguard.LockIconView.ICON_LOCK;
import static com.android.keyguard.LockIconView.ICON_UNLOCK;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedStateListDrawable;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Vibrator;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.LockIconView;
import com.android.keyguard.LockIconViewController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import com.airbnb.lottie.LottieAnimationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class LockIconViewControllerTest extends SysuiTestCase {
    private static final String UNLOCKED_LABEL = "unlocked";

    private @Mock LockIconView mLockIconView;
    private @Mock AnimatedStateListDrawable mIconDrawable;
    private @Mock Context mContext;
    private @Mock Resources mResources;
    private @Mock(answer = Answers.RETURNS_DEEP_STUBS) WindowManager mWindowManager;
    private @Mock StatusBarStateController mStatusBarStateController;
    private @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private @Mock KeyguardViewController mKeyguardViewController;
    private @Mock KeyguardStateController mKeyguardStateController;
    private @Mock FalsingManager mFalsingManager;
    private @Mock AuthController mAuthController;
    private @Mock DumpManager mDumpManager;
    private @Mock AccessibilityManager mAccessibilityManager;
    private @Mock ConfigurationController mConfigurationController;
    private @Mock Vibrator mVibrator;
    private @Mock AuthRippleController mAuthRippleController;
    private @Mock LottieAnimationView mAodFp;
    private @Mock LayoutInflater mLayoutInflater;
    private FakeExecutor mDelayableExecutor = new FakeExecutor(new FakeSystemClock());

    private LockIconViewController mLockIconViewController;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<View.OnAttachStateChangeListener> mAttachCaptor =
            ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
    private View.OnAttachStateChangeListener mAttachListener;

    @Captor private ArgumentCaptor<KeyguardStateController.Callback> mKeyguardStateCaptor =
            ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
    private KeyguardStateController.Callback mKeyguardStateCallback;

    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateCaptor =
            ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Captor private ArgumentCaptor<AuthController.Callback> mAuthControllerCallbackCaptor;
    private AuthController.Callback mAuthControllerCallback;

    @Captor private ArgumentCaptor<KeyguardUpdateMonitorCallback>
            mKeyguardUpdateMonitorCallbackCaptor =
            ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
    private KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback;

    @Captor private ArgumentCaptor<PointF> mPointCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mLockIconView.getResources()).thenReturn(mResources);
        when(mLockIconView.getContext()).thenReturn(mContext);
        when(mLockIconView.findViewById(R.layout.udfps_aod_lock_icon)).thenReturn(mAodFp);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(WindowManager.class)).thenReturn(mWindowManager);
        Rect windowBounds = new Rect(0, 0, 800, 1200);
        when(mWindowManager.getCurrentWindowMetrics().getBounds()).thenReturn(windowBounds);
        when(mResources.getString(R.string.accessibility_unlock_button)).thenReturn(UNLOCKED_LABEL);
        when(mResources.getDrawable(anyInt(), any())).thenReturn(mIconDrawable);

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);

        mLockIconViewController = new LockIconViewController(
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
                mLayoutInflater
        );
    }

    @Test
    public void testIgnoreUdfpsWhenNotSupported() {
        // GIVEN Udpfs sensor is NOT available
        mLockIconViewController.init();
        captureAttachListener();

        // WHEN the view is attached
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN lottie animation should NOT be inflated
        verify(mLayoutInflater, never()).inflate(eq(R.layout.udfps_aod_lock_icon), any());
    }

    @Test
    public void testInflateUdfpsWhenSupported() {
        // GIVEN Udpfs sensor is available
        setupUdfps();
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true);

        mLockIconViewController.init();
        captureAttachListener();

        // WHEN the view is attached
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN lottie animation should be inflated
        verify(mLayoutInflater).inflate(eq(R.layout.udfps_aod_lock_icon), any());
    }

    @Test
    public void testUpdateFingerprintLocationOnInit() {
        // GIVEN fp sensor location is available pre-attached
        Pair<Integer, PointF> udfps = setupUdfps();

        // WHEN lock icon view controller is initialized and attached
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN lock icon view location is updated with the same coordinates as fpProps
        verify(mLockIconView).setCenterLocation(mPointCaptor.capture(), eq(udfps.first));
        assertEquals(udfps.second, mPointCaptor.getValue());
    }

    @Test
    public void testUpdateFingerprintLocationOnAuthenticatorsRegistered() {
        // GIVEN fp sensor location is not available pre-init
        when(mAuthController.getFingerprintSensorLocation()).thenReturn(null);
        when(mAuthController.getUdfpsProps()).thenReturn(null);
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // GIVEN fp sensor location is available post-atttached
        captureAuthControllerCallback();
        Pair<Integer, PointF> udfps = setupUdfps();

        // WHEN all authenticators are registered
        mAuthControllerCallback.onAllAuthenticatorsRegistered();
        mDelayableExecutor.runAllReady();

        // THEN lock icon view location is updated with the same coordinates as fpProps
        verify(mLockIconView).setCenterLocation(mPointCaptor.capture(), eq(udfps.first));
        assertEquals(udfps.second, mPointCaptor.getValue());
    }

    @Test
    public void testLockIconViewBackgroundEnabledWhenUdfpsIsAvailable() {
        // GIVEN Udpfs sensor location is available
        setupUdfps();

        mLockIconViewController.init();
        captureAttachListener();

        // WHEN the view is attached
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN the lock icon view background should be enabled
        verify(mLockIconView).setUseBackground(true);
    }

    @Test
    public void testLockIconViewBackgroundDisabledWhenUdfpsIsUnavailable() {
        // GIVEN Udfps sensor location is not available
        when(mAuthController.getUdfpsSensorLocation()).thenReturn(null);

        mLockIconViewController.init();
        captureAttachListener();

        // WHEN the view is attached
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN the lock icon view background should be disabled
        verify(mLockIconView).setUseBackground(false);
    }

    @Test
    public void testUnlockIconShows_biometricUnlockedTrue() {
        // GIVEN UDFPS sensor location is available
        setupUdfps();

        // GIVEN lock icon controller is initialized and view is attached
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);
        captureKeyguardUpdateMonitorCallback();

        // GIVEN user has unlocked with a biometric auth (ie: face auth)
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(true);
        reset(mLockIconView);

        // WHEN face auth's biometric running state changes
        mKeyguardUpdateMonitorCallback.onBiometricRunningStateChanged(false,
                BiometricSourceType.FACE);

        // THEN the unlock icon is shown
        verify(mLockIconView).setContentDescription(UNLOCKED_LABEL);
    }

    @Test
    public void testLockIconStartState() {
        // GIVEN lock icon state
        setupShowLockIcon();

        // WHEN lock icon controller is initialized
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN the lock icon should show
        verify(mLockIconView).updateIcon(ICON_LOCK, false);
    }

    @Test
    public void testLockIcon_updateToUnlock() {
        // GIVEN starting state for the lock icon
        setupShowLockIcon();

        // GIVEN lock icon controller is initialized and view is attached
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);
        captureKeyguardStateCallback();
        reset(mLockIconView);

        // WHEN the unlocked state changes to canDismissLockScreen=true
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        mKeyguardStateCallback.onUnlockedChanged();

        // THEN the unlock should show
        verify(mLockIconView).updateIcon(ICON_UNLOCK, false);
    }

    @Test
    public void testLockIcon_clearsIconOnAod_whenUdfpsNotEnrolled() {
        // GIVEN udfps not enrolled
        setupUdfps();
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(false);

        // GIVEN starting state for the lock icon
        setupShowLockIcon();

        // GIVEN lock icon controller is initialized and view is attached
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);
        captureStatusBarStateListener();
        reset(mLockIconView);

        // WHEN the dozing state changes
        mStatusBarStateListener.onDozingChanged(true /* isDozing */);

        // THEN the icon is cleared
        verify(mLockIconView).clearIcon();
    }

    @Test
    public void testLockIcon_updateToAodLock_whenUdfpsEnrolled() {
        // GIVEN udfps enrolled
        setupUdfps();
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true);

        // GIVEN starting state for the lock icon
        setupShowLockIcon();

        // GIVEN lock icon controller is initialized and view is attached
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);
        captureStatusBarStateListener();
        reset(mLockIconView);

        // WHEN the dozing state changes
        mStatusBarStateListener.onDozingChanged(true /* isDozing */);

        // THEN the AOD lock icon should show
        verify(mLockIconView).updateIcon(ICON_LOCK, true);
    }

    private Pair<Integer, PointF> setupUdfps() {
        final PointF udfpsLocation = new PointF(50, 75);
        final int radius = 33;
        final FingerprintSensorPropertiesInternal fpProps =
                new FingerprintSensorPropertiesInternal(
                        /* sensorId */ 0,
                        /* strength */ 0,
                        /* max enrollments per user */ 5,
                        /* component info */ new ArrayList<>(),
                        /* sensorType */ 3,
                        /* resetLockoutRequiresHwToken */ false,
                        List.of(new SensorLocationInternal("" /* displayId */,
                                (int) udfpsLocation.x, (int) udfpsLocation.y, radius)));
        when(mAuthController.getUdfpsSensorLocation()).thenReturn(udfpsLocation);
        when(mAuthController.getUdfpsProps()).thenReturn(List.of(fpProps));

        return new Pair(radius, udfpsLocation);
    }

    private void setupShowLockIcon() {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(0f);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
    }

    private void captureAuthControllerCallback() {
        verify(mAuthController).addCallback(mAuthControllerCallbackCaptor.capture());
        mAuthControllerCallback = mAuthControllerCallbackCaptor.getValue();
    }

    private void captureAttachListener() {
        verify(mLockIconView).addOnAttachStateChangeListener(mAttachCaptor.capture());
        mAttachListener = mAttachCaptor.getValue();
    }

    private void captureKeyguardStateCallback() {
        verify(mKeyguardStateController).addCallback(mKeyguardStateCaptor.capture());
        mKeyguardStateCallback = mKeyguardStateCaptor.getValue();
    }

    private void captureStatusBarStateListener() {
        verify(mStatusBarStateController).addCallback(mStatusBarStateCaptor.capture());
        mStatusBarStateListener = mStatusBarStateCaptor.getValue();
    }

    private void captureKeyguardUpdateMonitorCallback() {
        verify(mKeyguardUpdateMonitor).registerCallback(
                mKeyguardUpdateMonitorCallbackCaptor.capture());
        mKeyguardUpdateMonitorCallback = mKeyguardUpdateMonitorCallbackCaptor.getValue();
    }
}
