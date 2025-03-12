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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.biometrics.udfps.SinglePointerTouchProcessor;
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel;
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.camera.CameraGestureHelper;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.data.repository.FakePowerRepository;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.power.shared.model.WakeSleepReason;
import com.android.systemui.power.shared.model.WakefulnessState;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;

import dagger.Lazy;

import javax.inject.Provider;

import kotlinx.coroutines.CoroutineScope;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class UdfpsControllerTest extends SysuiTestCase {

    private static final long TEST_REQUEST_ID = 70;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    // Unit under test
    private UdfpsController mUdfpsController;
    // Dependencies
    private FakeExecutor mBiometricExecutor;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private ViewCaptureAwareWindowManager mWindowManager;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private IUdfpsOverlayControllerCallback mUdfpsOverlayControllerCallback;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    @Mock
    private VibratorHelper mVibrator;
    @Mock
    private UdfpsHapticsSimulator mUdfpsHapticsSimulator;
    @Mock
    private UdfpsShell mUdfpsShell;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private Handler mHandler;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private SystemClock mSystemClock;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock
    private LatencyTracker mLatencyTracker;
    private FakeExecutor mFgExecutor;
    @Mock
    private UdfpsDisplayMode mUdfpsDisplayMode;
    @Mock
    private FeatureFlags mFeatureFlags;
    // Stuff for configuring mocks
    @Mock
    private SystemUIDialogManager mSystemUIDialogManager;
    @Mock
    private ActivityTransitionAnimator mActivityTransitionAnimator;
    @Mock
    private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock
    private ShadeInteractor mShadeInteractor;
    @Mock
    private SinglePointerTouchProcessor mSinglePointerTouchProcessor;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock
    private UdfpsOverlayInteractor mUdfpsOverlayInteractor;
    @Mock
    private UdfpsKeyguardAccessibilityDelegate mUdfpsKeyguardAccessibilityDelegate;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;

    // Capture listeners so that they can be used to send events
    @Captor
    private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor
    private ArgumentCaptor<View> mViewCaptor;
    @Captor
    private ArgumentCaptor<View.OnHoverListener> mHoverListenerCaptor;
    @Captor
    private ArgumentCaptor<Runnable> mOnDisplayConfiguredCaptor;
    @Captor
    private ArgumentCaptor<ScreenLifecycle.Observer> mScreenObserverCaptor;
    @Captor
    private ArgumentCaptor<UdfpsController.UdfpsOverlayController> mUdfpsOverlayControllerCaptor;
    private ScreenLifecycle.Observer mScreenObserver;
    private FingerprintSensorPropertiesInternal mOpticalProps;
    private FingerprintSensorPropertiesInternal mUltrasonicProps;
    private PowerInteractor mPowerInteractor;
    private FakePowerRepository mPowerRepository;
    @Mock
    private InputManager mInputManager;
    @Mock
    private ViewRootImpl mViewRootImpl;
    @Mock
    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    @Mock
    private Lazy<DeviceEntryUdfpsTouchOverlayViewModel> mDeviceEntryUdfpsTouchOverlayViewModel;
    @Mock
    private Lazy<DefaultUdfpsTouchOverlayViewModel> mDefaultUdfpsTouchOverlayViewModel;
    @Mock
    private Provider<CameraGestureHelper> mCameraGestureHelper;

    @Before
    public void setUp() {
        mPowerRepository = new FakePowerRepository();
        mPowerInteractor = new PowerInteractor(
                mPowerRepository,
                mock(FalsingCollector.class),
                mock(ScreenOffAnimationController.class),
                mStatusBarStateController,
                mCameraGestureHelper
        );
        mPowerRepository.updateWakefulness(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.OTHER,
                /* powerButtonLaunchGestureTriggered */ false
        );
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.bool.config_ignoreUdfpsVote, false);

        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        when(mSessionTracker.getSessionId(anyInt())).thenReturn(
                (new InstanceIdSequence(1 << 20)).newInstanceId());

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        mOpticalProps = new FingerprintSensorPropertiesInternal(1 /* sensorId */,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */);

        mUltrasonicProps = new FingerprintSensorPropertiesInternal(2 /* sensorId */,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC,
                true /* resetLockoutRequiresHardwareAuthToken */);

        mFgExecutor = new FakeExecutor(new FakeSystemClock());

        // Create a fake background executor.
        mBiometricExecutor = new FakeExecutor(new FakeSystemClock());

        initUdfpsController(mOpticalProps);
    }

    private void initUdfpsController(FingerprintSensorPropertiesInternal sensorProps) {
        reset(mFingerprintManager);
        reset(mScreenLifecycle);

        mUdfpsController = new UdfpsController(
                mContext,
                new FakeExecution(),
                mLayoutInflater,
                mFingerprintManager,
                mWindowManager,
                mStatusBarStateController,
                mFgExecutor,
                mStatusBarKeyguardViewManager,
                mDumpManager,
                mKeyguardUpdateMonitor,
                mFalsingManager,
                mPowerManager,
                mAccessibilityManager,
                mScreenLifecycle,
                mVibrator,
                mUdfpsHapticsSimulator,
                mUdfpsShell,
                mKeyguardStateController,
                mDisplayManager,
                mHandler,
                mConfigurationController,
                mSystemClock,
                mUnlockedScreenOffAnimationController,
                mSystemUIDialogManager,
                mLatencyTracker,
                mActivityTransitionAnimator,
                mBiometricExecutor,
                mPrimaryBouncerInteractor,
                mShadeInteractor,
                mSinglePointerTouchProcessor,
                mSessionTracker,
                mAlternateBouncerInteractor,
                mInputManager,
                mock(DeviceEntryFaceAuthInteractor.class),
                mUdfpsKeyguardAccessibilityDelegate,
                mSelectedUserInteractor,
                mKeyguardTransitionInteractor,
                mDeviceEntryUdfpsTouchOverlayViewModel,
                mDefaultUdfpsTouchOverlayViewModel,
                mUdfpsOverlayInteractor,
                mPowerInteractor,
                mock(CoroutineScope.class)
        );
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();
        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());
        mScreenObserver = mScreenObserverCaptor.getValue();

        mUdfpsController.updateOverlayParams(sensorProps, new UdfpsOverlayParams());
        mUdfpsController.setUdfpsDisplayMode(mUdfpsDisplayMode);
    }

    @Test
    public void hideUdfpsOverlay_resetsAltAuthBouncerWhenShowing() throws RemoteException {
        // GIVEN overlay was showing and the udfps bouncer is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // WHEN the overlay is hidden
        mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
        mFgExecutor.runAllReady();

        // THEN the udfps bouncer is reset
        verify(mStatusBarKeyguardViewManager).hideAlternateBouncer(eq(true));
    }

    @Test
    public void showUdfpsOverlay_callsListener() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mFingerprintManager).onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                TEST_REQUEST_ID, mOpticalProps.sensorId);
    }

    @Test
    public void showUdfpsOverlay_invokedTwice_doesNotNotifyListenerSecondTime() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mFingerprintManager).onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                TEST_REQUEST_ID, mOpticalProps.sensorId);

        reset(mFingerprintManager);

        // Second attempt should do nothing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mFingerprintManager, never()).onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                TEST_REQUEST_ID, mOpticalProps.sensorId);
    }

    @Test
    public void testSubscribesToOrientationChangesWhenShowingOverlay() throws Exception {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).registerDisplayListener(any(), eq(mHandler), anyLong());

        mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).unregisterDisplayListener(any());
    }

    @Test
    public void updateOverlayParams_doesNothing_ifParamsDidntChange() throws Exception {
        final Rect sensorBounds = new Rect(10, 10, 20, 20);
        final int displayWidth = 1080;
        final int displayHeight = 1920;
        final float scaleFactor = 1f;
        final int rotation = Surface.ROTATION_0;

        // Initialize the overlay.
        mUdfpsController.updateOverlayParams(mOpticalProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, rotation, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL));

        // Show the overlay.
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // Update overlay with the same parameters.
        mUdfpsController.updateOverlayParams(mOpticalProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, rotation, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL));
        mFgExecutor.runAllReady();

        // Ensure the overlay was not recreated.
        verify(mWindowManager, never()).removeView(any());
    }
}
