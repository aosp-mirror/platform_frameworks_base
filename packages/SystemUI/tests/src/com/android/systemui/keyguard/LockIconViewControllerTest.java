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

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PointF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Vibrator;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.LockIconView;
import com.android.keyguard.LockIconViewController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import com.airbnb.lottie.LottieAnimationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    private @Mock LockIconView mLockIconView;
    private @Mock Context mContext;
    private @Mock Resources mResources;
    private @Mock DisplayMetrics mDisplayMetrics;
    private @Mock StatusBarStateController mStatusBarStateController;
    private @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private @Mock KeyguardViewController mKeyguardViewController;
    private @Mock KeyguardStateController mKeyguardStateController;
    private @Mock FalsingManager mFalsingManager;
    private @Mock AuthController mAuthController;
    private @Mock DumpManager mDumpManager;
    private @Mock AccessibilityManager mAccessibilityManager;
    private @Mock ConfigurationController mConfigurationController;
    private @Mock DelayableExecutor mDelayableExecutor;
    private @Mock Vibrator mVibrator;
    private @Mock AuthRippleController mAuthRippleController;
    private @Mock LottieAnimationView mAodFp;

    private LockIconViewController mLockIconViewController;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<View.OnAttachStateChangeListener> mAttachCaptor =
            ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
    private View.OnAttachStateChangeListener mAttachListener;

    @Captor private ArgumentCaptor<AuthController.Callback> mAuthControllerCallbackCaptor;
    private AuthController.Callback mAuthControllerCallback;

    @Captor private ArgumentCaptor<PointF> mPointCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mLockIconView.getResources()).thenReturn(mResources);
        when(mLockIconView.getContext()).thenReturn(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        when(mLockIconView.findViewById(anyInt())).thenReturn(mAodFp);

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
                mVibrator
        );
    }

    @Test
    public void testUpdateFingerprintLocationOnInit() {
        // GIVEN fp sensor location is available pre-attached
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
                        (int) udfpsLocation.x, (int) udfpsLocation.y, radius);
        when(mAuthController.getUdfpsSensorLocation()).thenReturn(udfpsLocation);
        when(mAuthController.getUdfpsProps()).thenReturn(List.of(fpProps));

        // WHEN lock icon view controller is initialized and attached
        mLockIconViewController.init();
        captureAttachListener();
        mAttachListener.onViewAttachedToWindow(mLockIconView);

        // THEN lock icon view location is updated with the same coordinates as fpProps
        verify(mLockIconView).setCenterLocation(mPointCaptor.capture(), eq(radius));
        assertEquals(udfpsLocation, mPointCaptor.getValue());
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
                        (int) udfpsLocation.x, (int) udfpsLocation.y, radius);
        when(mAuthController.getUdfpsSensorLocation()).thenReturn(udfpsLocation);
        when(mAuthController.getUdfpsProps()).thenReturn(List.of(fpProps));

        // WHEN all authenticators are registered
        mAuthControllerCallback.onAllAuthenticatorsRegistered();

        // THEN lock icon view location is updated with the same coordinates as fpProps
        verify(mLockIconView).setCenterLocation(mPointCaptor.capture(), eq(radius));
        assertEquals(udfpsLocation, mPointCaptor.getValue());
    }

    private void captureAuthControllerCallback() {
        verify(mAuthController).addCallback(mAuthControllerCallbackCaptor.capture());
        mAuthControllerCallback = mAuthControllerCallbackCaptor.getValue();
    }

    private void captureAttachListener() {
        verify(mLockIconView).addOnAttachStateChangeListener(mAttachCaptor.capture());
        mAttachListener = mAttachCaptor.getValue();
    }
}
