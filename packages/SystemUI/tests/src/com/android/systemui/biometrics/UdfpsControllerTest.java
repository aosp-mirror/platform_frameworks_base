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

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.internal.util.FunctionalUtils.ThrowingConsumer;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricOverlayConstants;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.VibrationAttributes;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.biometrics.udfps.InteractionEvent;
import com.android.systemui.biometrics.udfps.NormalizedTouchData;
import com.android.systemui.biometrics.udfps.SinglePointerTouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessorResult;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidTestingRunner.class)
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
    private WindowManager mWindowManager;
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
    private UdfpsView mUdfpsView;
    @Mock
    private UdfpsEnrollView mEnrollView;
    @Mock
    private UdfpsBpView mBpView;
    @Mock
    private UdfpsFpmOtherView mFpmOtherView;
    @Mock
    private UdfpsKeyguardView mKeyguardView;
    private final UdfpsAnimationViewController mUdfpsKeyguardViewController =
            mock(UdfpsKeyguardViewController.class);
    @Mock
    private SystemUIDialogManager mSystemUIDialogManager;
    @Mock
    private ActivityLaunchAnimator mActivityLaunchAnimator;
    @Mock
    private AlternateUdfpsTouchProvider mAlternateTouchProvider;
    @Mock
    private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock
    private SinglePointerTouchProcessor mSinglePointerTouchProcessor;

    // Capture listeners so that they can be used to send events
    @Captor
    private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor
    private ArgumentCaptor<UdfpsView.OnTouchListener> mTouchListenerCaptor;
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

    @Before
    public void setUp() {
        Execution execution = new FakeExecution();

        when(mLayoutInflater.inflate(R.layout.udfps_view, null, false))
                .thenReturn(mUdfpsView);
        when(mLayoutInflater.inflate(R.layout.udfps_enroll_view, null))
                .thenReturn(mEnrollView); // for showOverlay REASON_ENROLL_ENROLLING
        when(mLayoutInflater.inflate(R.layout.udfps_keyguard_view, null))
                .thenReturn(mKeyguardView); // for showOverlay REASON_AUTH_FPM_KEYGUARD
        when(mLayoutInflater.inflate(R.layout.udfps_bp_view, null))
                .thenReturn(mBpView);
        when(mLayoutInflater.inflate(R.layout.udfps_fpm_other_view, null))
                .thenReturn(mFpmOtherView);
        when(mEnrollView.getContext()).thenReturn(mContext);
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);

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

        initUdfpsController(true /* hasAlternateTouchProvider */);
    }


    private void initUdfpsController(boolean hasAlternateTouchProvider) {
        initUdfpsController(mOpticalProps, hasAlternateTouchProvider);
    }

    private void initUdfpsController(FingerprintSensorPropertiesInternal sensorProps,
            boolean hasAlternateTouchProvider) {
        reset(mFingerprintManager);
        reset(mScreenLifecycle);

        final Optional<Provider<AlternateUdfpsTouchProvider>> alternateTouchProvider =
                hasAlternateTouchProvider ? Optional.of(
                        (Provider<AlternateUdfpsTouchProvider>) () -> mAlternateTouchProvider)
                        : Optional.empty();

        mUdfpsController = new UdfpsController(mContext, new FakeExecution(), mLayoutInflater,
                mFingerprintManager, mWindowManager, mStatusBarStateController, mFgExecutor,
                new ShadeExpansionStateManager(), mStatusBarKeyguardViewManager, mDumpManager,
                mKeyguardUpdateMonitor, mFeatureFlags, mFalsingManager, mPowerManager,
                mAccessibilityManager, mLockscreenShadeTransitionController, mScreenLifecycle,
                mVibrator, mUdfpsHapticsSimulator, mUdfpsShell, mKeyguardStateController,
                mDisplayManager, mHandler, mConfigurationController, mSystemClock,
                mUnlockedScreenOffAnimationController, mSystemUIDialogManager, mLatencyTracker,
                mActivityLaunchAnimator, alternateTouchProvider, mBiometricExecutor,
                mPrimaryBouncerInteractor, mSinglePointerTouchProcessor);
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();
        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());
        mScreenObserver = mScreenObserverCaptor.getValue();

        mUdfpsController.updateOverlayParams(sensorProps, new UdfpsOverlayParams());
        mUdfpsController.setUdfpsDisplayMode(mUdfpsDisplayMode);
    }

    @Test
    public void dozeTimeTick() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        mUdfpsController.dozeTimeTick();
        verify(mUdfpsView).dozeTimeTick();
    }

    @Test
    public void onActionDownTouch_whenCanDismissLockScreen_entersDevice() throws RemoteException {
        // GIVEN can dismiss lock screen and the current animation is an UdfpsKeyguardViewController
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void onActionMoveTouch_whenCanDismissLockScreen_entersDevice() throws RemoteException {
        onActionMoveTouch_whenCanDismissLockScreen_entersDevice(false /* stale */);
    }

    @Test
    public void onActionMoveTouch_whenCanDismissLockScreen_entersDevice_ignoreStale()
            throws RemoteException {
        onActionMoveTouch_whenCanDismissLockScreen_entersDevice(true /* stale */);
    }

    public void onActionMoveTouch_whenCanDismissLockScreen_entersDevice(boolean stale)
            throws RemoteException {
        // GIVEN can dismiss lock screen and the current animation is an UdfpsKeyguardViewController
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_MOVE is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        if (stale) {
            mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
            mFgExecutor.runAllReady();
        }
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricExecutor.runAllReady();
        moveEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager, stale ? never() : times(1))
                .notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void onMultipleTouch_whenCanDismissLockScreen_entersDeviceOnce() throws RemoteException {
        // GIVEN can dismiss lock screen and the current animation is an UdfpsKeyguardViewController
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN multiple touches are received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricExecutor.runAllReady();
        moveEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void hideUdfpsOverlay_resetsAltAuthBouncerWhenShowing() throws RemoteException {
        // GIVEN overlay was showing and the udfps bouncer is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        when(mStatusBarKeyguardViewManager.isShowingAlternateBouncer()).thenReturn(true);

        // WHEN the overlay is hidden
        mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
        mFgExecutor.runAllReady();

        // THEN the udfps bouncer is reset
        verify(mStatusBarKeyguardViewManager).hideAlternateBouncer(eq(true));
    }

    @Test
    public void testSubscribesToOrientationChangesWhenShowingOverlay() throws Exception {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).registerDisplayListener(any(), eq(mHandler), anyLong());

        mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).unregisterDisplayListener(any());
    }

    @Test
    public void updateOverlayParams_recreatesOverlay_ifParamsChanged() throws Exception {
        final Rect[] sensorBounds = new Rect[]{new Rect(10, 10, 20, 20), new Rect(5, 5, 25, 25)};
        final int[] displayWidth = new int[]{1080, 1440};
        final int[] displayHeight = new int[]{1920, 2560};
        final float[] scaleFactor = new float[]{1f, displayHeight[1] / (float) displayHeight[0]};
        final int[] rotation = new int[]{Surface.ROTATION_0, Surface.ROTATION_90};
        final UdfpsOverlayParams oldParams = new UdfpsOverlayParams(sensorBounds[0],
                sensorBounds[0], displayWidth[0], displayHeight[0], scaleFactor[0], rotation[0]);

        for (int i1 = 0; i1 <= 1; ++i1) {
            for (int i2 = 0; i2 <= 1; ++i2) {
                for (int i3 = 0; i3 <= 1; ++i3) {
                    for (int i4 = 0; i4 <= 1; ++i4) {
                        for (int i5 = 0; i5 <= 1; ++i5) {
                            final UdfpsOverlayParams newParams = new UdfpsOverlayParams(
                                    sensorBounds[i1], sensorBounds[i1], displayWidth[i2],
                                    displayHeight[i3], scaleFactor[i4], rotation[i5]);

                            if (newParams.equals(oldParams)) {
                                continue;
                            }

                            // Initialize the overlay with old parameters.
                            mUdfpsController.updateOverlayParams(mOpticalProps, oldParams);

                            // Show the overlay.
                            reset(mWindowManager);
                            mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID,
                                    mOpticalProps.sensorId,
                                    BiometricOverlayConstants.REASON_ENROLL_ENROLLING,
                                    mUdfpsOverlayControllerCallback);
                            mFgExecutor.runAllReady();
                            verify(mWindowManager).addView(any(), any());

                            // Update overlay parameters.
                            reset(mWindowManager);
                            mUdfpsController.updateOverlayParams(mOpticalProps, newParams);
                            mFgExecutor.runAllReady();

                            // Ensure the overlay was recreated.
                            verify(mWindowManager).removeView(any());
                            verify(mWindowManager).addView(any(), any());
                        }
                    }
                }
            }
        }
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
                        scaleFactor, rotation));

        // Show the overlay.
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mWindowManager).addView(any(), any());

        // Update overlay with the same parameters.
        mUdfpsController.updateOverlayParams(mOpticalProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, rotation));
        mFgExecutor.runAllReady();

        // Ensure the overlay was not recreated.
        verify(mWindowManager, never()).removeView(any());
    }

    private static MotionEvent obtainMotionEvent(int action, float x, float y, float minor,
            float major) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 1;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.touchMinor = minor;
        pc.touchMajor = major;
        return MotionEvent.obtain(0, 0, action, 1, new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc}, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private static class TestParams {
        public final FingerprintSensorPropertiesInternal sensorProps;
        public final boolean hasAlternateTouchProvider;

        TestParams(FingerprintSensorPropertiesInternal sensorProps,
                boolean hasAlternateTouchProvider) {
            this.sensorProps = sensorProps;
            this.hasAlternateTouchProvider = hasAlternateTouchProvider;
        }
    }

    private void runWithAllParams(ThrowingConsumer<TestParams> testParamsConsumer) {
        for (FingerprintSensorPropertiesInternal sensorProps : List.of(mOpticalProps,
                mUltrasonicProps)) {
            for (boolean hasAlternateTouchProvider : new boolean[]{false, true}) {
                initUdfpsController(sensorProps, hasAlternateTouchProvider);
                testParamsConsumer.accept(new TestParams(sensorProps, hasAlternateTouchProvider));
            }
        }
    }

    @Test
    public void onTouch_propagatesTouchInNativeOrientationAndResolution() {
        runWithAllParams(
                this::onTouch_propagatesTouchInNativeOrientationAndResolutionParameterized);
    }

    private void onTouch_propagatesTouchInNativeOrientationAndResolutionParameterized(
            TestParams testParams) throws RemoteException {
        reset(mUdfpsView);

        final Rect sensorBounds = new Rect(1000, 1900, 1080, 1920); // Bottom right corner.
        final int displayWidth = 1080;
        final int displayHeight = 1920;
        final float scaleFactor = 0.75f; // This means the native resolution is 1440x2560.
        final float touchMinor = 10f;
        final float touchMajor = 20f;

        // Expecting a touch at the very bottom right corner in native orientation and resolution.
        final int expectedX = (int) (displayWidth / scaleFactor);
        final int expectedY = (int) (displayHeight / scaleFactor);
        final float expectedMinor = touchMinor / scaleFactor;
        final float expectedMajor = touchMajor / scaleFactor;

        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // Show the overlay.
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());

        // Test ROTATION_0
        mUdfpsController.updateOverlayParams(testParams.sensorProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, Surface.ROTATION_0));
        MotionEvent event = obtainMotionEvent(ACTION_DOWN, displayWidth, displayHeight, touchMinor,
                touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, displayWidth, displayHeight, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        if (testParams.hasAlternateTouchProvider) {
            verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                    eq(expectedY), eq(expectedMinor), eq(expectedMajor));
        } else {
            verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID),
                    eq(testParams.sensorProps.sensorId), eq(expectedX), eq(expectedY),
                    eq(expectedMinor), eq(expectedMajor));
        }

        // Test ROTATION_90
        reset(mAlternateTouchProvider);
        reset(mFingerprintManager);
        mUdfpsController.updateOverlayParams(testParams.sensorProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, Surface.ROTATION_90));
        event = obtainMotionEvent(ACTION_DOWN, displayHeight, 0, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, displayHeight, 0, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        if (testParams.hasAlternateTouchProvider) {
            verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                    eq(expectedY), eq(expectedMinor), eq(expectedMajor));
        } else {
            verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID),
                    eq(testParams.sensorProps.sensorId), eq(expectedX), eq(expectedY),
                    eq(expectedMinor), eq(expectedMajor));
        }

        // Test ROTATION_270
        reset(mAlternateTouchProvider);
        reset(mFingerprintManager);
        mUdfpsController.updateOverlayParams(testParams.sensorProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, Surface.ROTATION_270));
        event = obtainMotionEvent(ACTION_DOWN, 0, displayWidth, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, 0, displayWidth, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        if (testParams.hasAlternateTouchProvider) {
            verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                    eq(expectedY), eq(expectedMinor), eq(expectedMajor));
        } else {
            verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID),
                    eq(testParams.sensorProps.sensorId), eq(expectedX), eq(expectedY),
                    eq(expectedMinor), eq(expectedMajor));
        }

        // Test ROTATION_180
        reset(mAlternateTouchProvider);
        reset(mFingerprintManager);
        mUdfpsController.updateOverlayParams(testParams.sensorProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, Surface.ROTATION_180));
        // ROTATION_180 is not supported. It should be treated like ROTATION_0.
        event = obtainMotionEvent(ACTION_DOWN, displayWidth, displayHeight, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, displayWidth, displayHeight, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricExecutor.runAllReady();
        event.recycle();
        if (testParams.hasAlternateTouchProvider) {
            verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                    eq(expectedY), eq(expectedMinor), eq(expectedMajor));
        } else {
            verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID),
                    eq(testParams.sensorProps.sensorId), eq(expectedX), eq(expectedY),
                    eq(expectedMinor), eq(expectedMajor));
        }
    }

    @Test
    public void fingerDown() {
        runWithAllParams(this::fingerDownParameterized);
    }

    private void fingerDownParameterized(TestParams testParams) throws RemoteException {
        reset(mUdfpsView, mAlternateTouchProvider, mFingerprintManager, mLatencyTracker,
                mKeyguardUpdateMonitor);

        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());

        // WHEN ACTION_DOWN is received
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();

        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricExecutor.runAllReady();
        moveEvent.recycle();

        mFgExecutor.runAllReady();

        // THEN the touch provider is notified about onPointerDown.
        if (testParams.hasAlternateTouchProvider) {
            verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(0), eq(0), eq(0f),
                    eq(0f));
            verify(mFingerprintManager, never()).onPointerDown(anyLong(), anyInt(), anyInt(),
                    anyInt(), anyFloat(), anyFloat());
            verify(mKeyguardUpdateMonitor).onUdfpsPointerDown(eq((int) TEST_REQUEST_ID));
        } else {
            verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID),
                    eq(testParams.sensorProps.sensorId), eq(0), eq(0), eq(0f), eq(0f));
            verify(mAlternateTouchProvider, never()).onPointerDown(anyInt(), anyInt(), anyInt(),
                    anyFloat(), anyFloat());
        }

        // AND display configuration begins
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            verify(mLatencyTracker).onActionStart(eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
            verify(mUdfpsView).configureDisplay(mOnDisplayConfiguredCaptor.capture());
        } else {
            verify(mLatencyTracker, never()).onActionStart(
                    eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
            verify(mUdfpsView, never()).configureDisplay(any());
        }
        verify(mLatencyTracker, never()).onActionEnd(eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));

        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            // AND onDisplayConfigured notifies FingerprintManager about onUiReady
            mOnDisplayConfiguredCaptor.getValue().run();
            mBiometricExecutor.runAllReady();
            if (testParams.hasAlternateTouchProvider) {
                InOrder inOrder = inOrder(mAlternateTouchProvider, mLatencyTracker);
                inOrder.verify(mAlternateTouchProvider).onUiReady();
                inOrder.verify(mLatencyTracker).onActionEnd(
                        eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
                verify(mFingerprintManager, never()).onUiReady(anyLong(), anyInt());
            } else {
                InOrder inOrder = inOrder(mFingerprintManager, mLatencyTracker);
                inOrder.verify(mFingerprintManager).onUiReady(eq(TEST_REQUEST_ID),
                        eq(testParams.sensorProps.sensorId));
                inOrder.verify(mLatencyTracker).onActionEnd(
                        eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
                verify(mAlternateTouchProvider, never()).onUiReady();
            }
        } else {
            verify(mFingerprintManager, never()).onUiReady(anyLong(), anyInt());
            verify(mAlternateTouchProvider, never()).onUiReady();
            verify(mLatencyTracker, never()).onActionEnd(
                    eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
        }
    }



    @Test
    public void aodInterrupt() {
        runWithAllParams(this::aodInterruptParameterized);
    }

    private void aodInterruptParameterized(TestParams testParams) throws RemoteException {
        mUdfpsController.cancelAodSendFingerUpAction();
        reset(mUdfpsView, mAlternateTouchProvider, mFingerprintManager, mKeyguardUpdateMonitor);
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);

        // GIVEN that the overlay is showing and screen is on and fp is running
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        // WHEN fingerprint is requested because of AOD interrupt
        mUdfpsController.onAodInterrupt(0, 0, 2f, 3f);
        mFgExecutor.runAllReady();
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            // THEN display configuration begins
            // AND onDisplayConfigured notifies FingerprintManager about onUiReady
            verify(mUdfpsView).configureDisplay(mOnDisplayConfiguredCaptor.capture());
            mOnDisplayConfiguredCaptor.getValue().run();
        } else {
            verify(mUdfpsView, never()).configureDisplay(mOnDisplayConfiguredCaptor.capture());
        }
        mBiometricExecutor.runAllReady();

        if (testParams.hasAlternateTouchProvider) {
            verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(0), eq(0),
                    eq(3f) /* minor */, eq(2f) /* major */);
            verify(mFingerprintManager, never()).onPointerDown(anyLong(), anyInt(), anyInt(),
                    anyInt(), anyFloat(), anyFloat());
            verify(mKeyguardUpdateMonitor).onUdfpsPointerDown(eq((int) TEST_REQUEST_ID));
        } else {
            verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID),
                    eq(testParams.sensorProps.sensorId), eq(0), eq(0), eq(3f) /* minor */,
                    eq(2f) /* major */);
            verify(mAlternateTouchProvider, never()).onPointerDown(anyLong(), anyInt(), anyInt(),
                    anyFloat(), anyFloat());
        }
    }

    @Test
    public void tryAodSendFingerUp_displayConfigurationChanges() {
        runWithAllParams(this::cancelAodInterruptParameterized);
    }

    private void cancelAodInterruptParameterized(TestParams testParams) throws RemoteException {
        reset(mUdfpsView);

        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(true);
            // WHEN it is cancelled
            mUdfpsController.tryAodSendFingerUp();
            // THEN the display is unconfigured
            verify(mUdfpsView).unconfigureDisplay();
        } else {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
            // WHEN it is cancelled
            mUdfpsController.tryAodSendFingerUp();
            // THEN the display configuration is unchanged.
            verify(mUdfpsView, never()).unconfigureDisplay();
        }
    }

    @Test
    public void onFingerUp_displayConfigurationChange() {
        runWithAllParams(this::onFingerUp_displayConfigurationParameterized);
    }

    private void onFingerUp_displayConfigurationParameterized(TestParams testParams)
            throws RemoteException {
        reset(mUdfpsView);

        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(true);

            // WHEN up-action received
            verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
            MotionEvent upEvent = MotionEvent.obtain(0, 0, ACTION_UP, 0, 0, 0);
            mTouchListenerCaptor.getValue().onTouch(mUdfpsView, upEvent);
            mBiometricExecutor.runAllReady();
            upEvent.recycle();
            mFgExecutor.runAllReady();

            // THEN the display is unconfigured
            verify(mUdfpsView).unconfigureDisplay();
        } else {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(false);

            // WHEN up-action received
            verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
            MotionEvent upEvent = MotionEvent.obtain(0, 0, ACTION_UP, 0, 0, 0);
            mTouchListenerCaptor.getValue().onTouch(mUdfpsView, upEvent);
            mBiometricExecutor.runAllReady();
            upEvent.recycle();
            mFgExecutor.runAllReady();

            // THEN the display configuration is unchanged.
            verify(mUdfpsView, never()).unconfigureDisplay();
        }
    }

    @Test
    public void onAcquiredGood_displayConfigurationChange() {
        runWithAllParams(this::onAcquiredGood_displayConfigurationParameterized);
    }

    private void onAcquiredGood_displayConfigurationParameterized(TestParams testParams)
            throws RemoteException {
        reset(mUdfpsView);

        // GIVEN overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(true);
            // WHEN ACQUIRED_GOOD received
            mOverlayController.onAcquired(testParams.sensorProps.sensorId,
                    BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD);
            mFgExecutor.runAllReady();
            // THEN the display is unconfigured
            verify(mUdfpsView).unconfigureDisplay();
        } else {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
            // WHEN ACQUIRED_GOOD received
            mOverlayController.onAcquired(testParams.sensorProps.sensorId,
                    BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD);
            mFgExecutor.runAllReady();
            // THEN the display configuration is unchanged.
            verify(mUdfpsView, never()).unconfigureDisplay();
        }
    }

    @Test
    public void aodInterruptTimeout() {
        runWithAllParams(this::aodInterruptTimeoutParameterized);
    }

    private void aodInterruptTimeoutParameterized(TestParams testParams) throws RemoteException {
        reset(mUdfpsView);

        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        mFgExecutor.runAllReady();
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(true);
        } else {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        }
        // WHEN it times out
        mFgExecutor.advanceClockToNext();
        mFgExecutor.runAllReady();
        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            // THEN the display is unconfigured.
            verify(mUdfpsView).unconfigureDisplay();
        } else {
            // THEN the display configuration is unchanged.
            verify(mUdfpsView, never()).unconfigureDisplay();
        }
    }

    @Test
    public void aodInterruptCancelTimeoutActionOnFingerUp() {
        runWithAllParams(this::aodInterruptCancelTimeoutActionOnFingerUpParameterized);
    }

    private void aodInterruptCancelTimeoutActionOnFingerUpParameterized(TestParams testParams)
            throws RemoteException {
        reset(mUdfpsView);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        mFgExecutor.runAllReady();

        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            // Configure UdfpsView to accept the ACTION_UP event
            when(mUdfpsView.isDisplayConfigured()).thenReturn(true);
        } else {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        }

        // WHEN ACTION_UP is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent upEvent = MotionEvent.obtain(0, 0, ACTION_UP, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, upEvent);
        mBiometricExecutor.runAllReady();
        upEvent.recycle();

        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);

        // WHEN ACTION_DOWN is received
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();

        // WHEN ACTION_MOVE is received
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricExecutor.runAllReady();
        moveEvent.recycle();
        mFgExecutor.runAllReady();

        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            // Configure UdfpsView to accept the finger up event
            when(mUdfpsView.isDisplayConfigured()).thenReturn(true);
        } else {
            when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        }

        // WHEN it times out
        mFgExecutor.advanceClockToNext();
        mFgExecutor.runAllReady();

        if (testParams.sensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
            // THEN the display should be unconfigured once. If the timeout action is not
            // cancelled, the display would be unconfigured twice which would cause two
            // FP attempts.
            verify(mUdfpsView, times(1)).unconfigureDisplay();
        } else {
            verify(mUdfpsView, never()).unconfigureDisplay();
        }
    }

    @Test
    public void aodInterruptScreenOff() {
        runWithAllParams(this::aodInterruptScreenOffParameterized);
    }

    private void aodInterruptScreenOffParameterized(TestParams testParams) throws RemoteException {
        reset(mUdfpsView);

        // GIVEN screen off
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOff();
        mFgExecutor.runAllReady();

        // WHEN aod interrupt is received
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);

        // THEN display doesn't get configured because it's off
        verify(mUdfpsView, never()).configureDisplay(any());
    }

    @Test
    public void aodInterrupt_fingerprintNotRunning() {
        runWithAllParams(this::aodInterrupt_fingerprintNotRunningParameterized);
    }

    private void aodInterrupt_fingerprintNotRunningParameterized(TestParams testParams)
            throws RemoteException {
        reset(mUdfpsView);

        // GIVEN showing overlay
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, testParams.sensorProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();

        // WHEN aod interrupt is received when the fingerprint service isn't running
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(false);
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);

        // THEN display doesn't get configured because it's off
        verify(mUdfpsView, never()).configureDisplay(any());
    }

    @Test
    public void playHapticOnTouchUdfpsArea_a11yTouchExplorationEnabled() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing and a11y touch exploration enabled
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_HOVER is received
        verify(mUdfpsView).setOnHoverListener(mHoverListenerCaptor.capture());
        MotionEvent enterEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 0, 0, 0);
        mHoverListenerCaptor.getValue().onHover(mUdfpsView, enterEvent);
        enterEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        mHoverListenerCaptor.getValue().onHover(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN tick haptic is played
        verify(mVibrator).vibrate(
                anyInt(),
                anyString(),
                any(),
                eq("udfps-onStart-click"),
                eq(UdfpsController.UDFPS_VIBRATION_ATTRIBUTES));

        // THEN make sure vibration attributes has so that it always will play the haptic,
        // even in battery saver mode
        assertEquals(VibrationAttributes.USAGE_COMMUNICATION_REQUEST,
                UdfpsController.UDFPS_VIBRATION_ATTRIBUTES.getUsage());
    }

    @Test
    public void noHapticOnTouchUdfpsArea_a11yTouchExplorationDisabled() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing and a11y touch exploration NOT enabled
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricExecutor.runAllReady();
        moveEvent.recycle();

        // THEN NO haptic played
        verify(mVibrator, never()).vibrate(
                anyInt(),
                anyString(),
                any(),
                anyString(),
                any());
    }

    @Test
    public void onTouch_withoutNewTouchDetection_shouldCallOldFingerprintManagerPath()
            throws RemoteException {
        // Disable new touch detection.
        when(mFeatureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)).thenReturn(false);

        // Configure UdfpsController to use FingerprintManager as opposed to AlternateTouchProvider.
        initUdfpsController(mOpticalProps, false /* hasAlternateTouchProvider */);

        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing and a11y touch exploration NOT enabled
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());

        // WHEN ACTION_DOWN is received
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();

        // AND ACTION_MOVE is received
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricExecutor.runAllReady();
        moveEvent.recycle();

        // AND ACTION_UP is received
        MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, upEvent);
        mBiometricExecutor.runAllReady();
        upEvent.recycle();

        // THEN the old FingerprintManager path is invoked.
        verify(mFingerprintManager).onPointerDown(anyLong(), anyInt(), anyInt(), anyInt(),
                anyFloat(), anyFloat());
        verify(mFingerprintManager).onPointerUp(anyLong(), anyInt());
    }

    @Test
    public void onTouch_withNewTouchDetection_shouldCallNewFingerprintManagerPath()
            throws RemoteException {
        final NormalizedTouchData touchData = new NormalizedTouchData(0, 0f, 0f, 0f, 0f, 0f, 0L,
                0L);
        final TouchProcessorResult processorResultDown = new TouchProcessorResult.ProcessedTouch(
                InteractionEvent.DOWN, 1 /* pointerId */, touchData);
        final TouchProcessorResult processorResultUp = new TouchProcessorResult.ProcessedTouch(
                InteractionEvent.UP, 1 /* pointerId */, touchData);

        // Enable new touch detection.
        when(mFeatureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)).thenReturn(true);

        // Configure UdfpsController to use FingerprintManager as opposed to AlternateTouchProvider.
        initUdfpsController(mOpticalProps, false /* hasAlternateTouchProvider */);

        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isDisplayConfigured()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing and a11y touch exploration NOT enabled
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());

        // WHEN ACTION_DOWN is received
        when(mSinglePointerTouchProcessor.processTouch(any(), anyInt(), any())).thenReturn(
                processorResultDown);
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricExecutor.runAllReady();
        downEvent.recycle();

        // AND ACTION_UP is received
        when(mSinglePointerTouchProcessor.processTouch(any(), anyInt(), any())).thenReturn(
                processorResultUp);
        MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, upEvent);
        mBiometricExecutor.runAllReady();
        upEvent.recycle();

        // THEN the new FingerprintManager path is invoked.
        verify(mFingerprintManager).onPointerDown(anyLong(), anyInt(), anyInt(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyLong(), anyLong(), anyBoolean());
        verify(mFingerprintManager).onPointerUp(anyLong(), anyInt(), anyInt(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    public void onAodInterrupt_onAcquiredGood_fingerNoLongerDown() throws RemoteException {
        // GIVEN UDFPS overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // GIVEN there's been an AoD interrupt
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        mScreenObserver.onScreenTurnedOn();
        mUdfpsController.onAodInterrupt(0, 0, 0, 0);

        // THEN finger is considered down
        assertTrue(mUdfpsController.isFingerDown());

        // WHEN udfps receives an ACQUIRED_GOOD after the display is configured
        when(mUdfpsView.isDisplayConfigured()).thenReturn(true);
        verify(mFingerprintManager).setUdfpsOverlayController(
                mUdfpsOverlayControllerCaptor.capture());
        mUdfpsOverlayControllerCaptor.getValue().onAcquired(0, FINGERPRINT_ACQUIRED_GOOD);
        mFgExecutor.runAllReady();

        // THEN is fingerDown should be FALSE
        assertFalse(mUdfpsController.isFingerDown());

    }
}
