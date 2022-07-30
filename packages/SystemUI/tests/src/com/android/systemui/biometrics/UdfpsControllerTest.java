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

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;

import static junit.framework.Assert.assertEquals;

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
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
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

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class UdfpsControllerTest extends SysuiTestCase {

    // Use this for inputs going into SystemUI. Use UdfpsController.mUdfpsSensorId for things
    // leaving SystemUI.
    private static final int TEST_UDFPS_SENSOR_ID = 1;
    private static final long TEST_REQUEST_ID = 70;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    // Unit under test
    private UdfpsController mUdfpsController;

    // Dependencies
    private FakeExecutor mBiometricsExecutor;
    private Execution mExecution;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private UdfpsHbmProvider mHbmProvider;
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

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor private ArgumentCaptor<UdfpsView.OnTouchListener> mTouchListenerCaptor;
    @Captor private ArgumentCaptor<View.OnHoverListener> mHoverListenerCaptor;
    @Captor private ArgumentCaptor<Runnable> mOnIlluminatedRunnableCaptor;
    @Captor private ArgumentCaptor<ScreenLifecycle.Observer> mScreenObserverCaptor;
    private ScreenLifecycle.Observer mScreenObserver;

    @Before
    public void setUp() {
        mExecution = new FakeExecution();

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
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        final List<FingerprintSensorPropertiesInternal> props = new ArrayList<>();

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        props.add(new FingerprintSensorPropertiesInternal(TEST_UDFPS_SENSOR_ID,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */));
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(props);
        mFgExecutor = new FakeExecutor(new FakeSystemClock());

        // Create a fake background executor.
        mBiometricsExecutor = new FakeExecutor(new FakeSystemClock());

        mUdfpsController = new UdfpsController(
                mContext,
                mExecution,
                mLayoutInflater,
                mFingerprintManager,
                mWindowManager,
                mStatusBarStateController,
                mFgExecutor,
                new PanelExpansionStateManager(),
                mStatusBarKeyguardViewManager,
                mDumpManager,
                mKeyguardUpdateMonitor,
                mFalsingManager,
                mPowerManager,
                mAccessibilityManager,
                mLockscreenShadeTransitionController,
                mScreenLifecycle,
                mVibrator,
                mUdfpsHapticsSimulator,
                mUdfpsShell,
                Optional.of(mHbmProvider),
                mKeyguardStateController,
                mDisplayManager,
                mHandler,
                mConfigurationController,
                mSystemClock,
                mUnlockedScreenOffAnimationController,
                mSystemUIDialogManager,
                mLatencyTracker,
                mActivityLaunchAnimator,
                Optional.of(mAlternateTouchProvider),
                mBiometricsExecutor);
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();
        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());
        mScreenObserver = mScreenObserverCaptor.getValue();
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID, new UdfpsOverlayParams());
    }

    @Test
    public void dozeTimeTick() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
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
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricsExecutor.runAllReady();
        downEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void onActionMoveTouch_whenCanDismissLockScreen_entersDevice()
            throws RemoteException {
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
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_MOVE is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        if (stale) {
            mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
            mFgExecutor.runAllReady();
        }
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricsExecutor.runAllReady();
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
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN multiple touches are received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricsExecutor.runAllReady();
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricsExecutor.runAllReady();
        moveEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void hideUdfpsOverlay_resetsAltAuthBouncerWhenShowing() throws RemoteException {
        // GIVEN overlay was showing and the udfps bouncer is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        when(mStatusBarKeyguardViewManager.isShowingAlternateAuth()).thenReturn(true);

        // WHEN the overlay is hidden
        mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
        mFgExecutor.runAllReady();

        // THEN the udfps bouncer is reset
        verify(mStatusBarKeyguardViewManager).resetAlternateAuth(eq(true));
    }

    @Test
    public void testSubscribesToOrientationChangesWhenShowingOverlay() throws Exception {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).registerDisplayListener(any(), eq(mHandler), anyLong());

        mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
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
                displayWidth[0], displayHeight[0], scaleFactor[0], rotation[0]);

        for (int i1 = 0; i1 <= 1; ++i1)
        for (int i2 = 0; i2 <= 1; ++i2)
        for (int i3 = 0; i3 <= 1; ++i3)
        for (int i4 = 0; i4 <= 1; ++i4)
        for (int i5 = 0; i5 <= 1; ++i5) {
            final UdfpsOverlayParams newParams = new UdfpsOverlayParams(sensorBounds[i1],
                    displayWidth[i2], displayHeight[i3], scaleFactor[i4], rotation[i5]);

            if (newParams.equals(oldParams)) {
                continue;
            }

            // Initialize the overlay with old parameters.
            mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID, oldParams);

            // Show the overlay.
            reset(mWindowManager);
            mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                    BiometricOverlayConstants.REASON_ENROLL_ENROLLING,
                    mUdfpsOverlayControllerCallback);
            mFgExecutor.runAllReady();
            verify(mWindowManager).addView(any(), any());

            // Update overlay parameters.
            reset(mWindowManager);
            mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID, newParams);
            mFgExecutor.runAllReady();

            // Ensure the overlay was recreated.
            verify(mWindowManager).removeView(any());
            verify(mWindowManager).addView(any(), any());
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
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID,
                new UdfpsOverlayParams(sensorBounds, displayWidth, displayHeight, scaleFactor,
                        rotation));

        // Show the overlay.
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mWindowManager).addView(any(), any());

        // Update overlay with the same parameters.
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID,
                new UdfpsOverlayParams(sensorBounds, displayWidth, displayHeight, scaleFactor,
                        rotation));
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

    @Test
    public void onTouch_propagatesTouchInNativeOrientationAndResolution() throws RemoteException {
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
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // Show the overlay.
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());

        // Test ROTATION_0
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID,
                new UdfpsOverlayParams(sensorBounds, displayWidth, displayHeight, scaleFactor,
                        Surface.ROTATION_0));
        MotionEvent event = obtainMotionEvent(ACTION_DOWN, displayWidth, displayHeight, touchMinor,
                touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, displayWidth, displayHeight, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                eq(expectedY), eq(expectedMinor), eq(expectedMajor));

        // Test ROTATION_90
        reset(mAlternateTouchProvider);
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID,
                new UdfpsOverlayParams(sensorBounds, displayWidth, displayHeight, scaleFactor,
                        Surface.ROTATION_90));
        event = obtainMotionEvent(ACTION_DOWN, displayHeight, 0, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, displayHeight, 0, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                eq(expectedY), eq(expectedMinor), eq(expectedMajor));

        // Test ROTATION_270
        reset(mAlternateTouchProvider);
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID,
                new UdfpsOverlayParams(sensorBounds, displayWidth, displayHeight, scaleFactor,
                        Surface.ROTATION_270));
        event = obtainMotionEvent(ACTION_DOWN, 0, displayWidth, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, 0, displayWidth, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                eq(expectedY), eq(expectedMinor), eq(expectedMajor));

        // Test ROTATION_180
        reset(mAlternateTouchProvider);
        mUdfpsController.updateOverlayParams(TEST_UDFPS_SENSOR_ID,
                new UdfpsOverlayParams(sensorBounds, displayWidth, displayHeight, scaleFactor,
                        Surface.ROTATION_180));
        // ROTATION_180 is not supported. It should be treated like ROTATION_0.
        event = obtainMotionEvent(ACTION_DOWN, displayWidth, displayHeight, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        event = obtainMotionEvent(ACTION_MOVE, displayWidth, displayHeight, touchMinor, touchMajor);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        mBiometricsExecutor.runAllReady();
        event.recycle();
        verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(expectedX),
                eq(expectedY), eq(expectedMinor), eq(expectedMajor));
    }

    @Test
    public void fingerDown() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricsExecutor.runAllReady();
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);

        // FIX THIS TEST
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricsExecutor.runAllReady();
        moveEvent.recycle();
        mFgExecutor.runAllReady();
        // THEN FingerprintManager is notified about onPointerDown
        verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID), eq(0), eq(0), eq(0f),
                eq(0f));
        verify(mFingerprintManager, never()).onPointerDown(anyLong(), anyInt(), anyInt(), anyInt(),
                anyFloat(), anyFloat());
        verify(mLatencyTracker).onActionStart(eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
        // AND illumination begins
        verify(mUdfpsView).startIllumination(mOnIlluminatedRunnableCaptor.capture());
        verify(mLatencyTracker, never()).onActionEnd(eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
        verify(mKeyguardUpdateMonitor).onUdfpsPointerDown(eq((int) TEST_REQUEST_ID));
        // AND onIlluminatedRunnable notifies FingerprintManager about onUiReady
        mOnIlluminatedRunnableCaptor.getValue().run();
        mBiometricsExecutor.runAllReady();
        InOrder inOrder = inOrder(mAlternateTouchProvider, mLatencyTracker);
        inOrder.verify(mAlternateTouchProvider).onUiReady();
        inOrder.verify(mLatencyTracker).onActionEnd(eq(LatencyTracker.ACTION_UDFPS_ILLUMINATE));
    }

    @Test
    public void aodInterrupt() throws RemoteException {
        // GIVEN that the overlay is showing and screen is on and fp is running
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        // WHEN fingerprint is requested because of AOD interrupt
        mUdfpsController.onAodInterrupt(0, 0, 2f, 3f);
        mFgExecutor.runAllReady();
        // THEN illumination begins
        // AND onIlluminatedRunnable that notifies FingerprintManager is set
        verify(mUdfpsView).startIllumination(mOnIlluminatedRunnableCaptor.capture());
        mOnIlluminatedRunnableCaptor.getValue().run();
        mBiometricsExecutor.runAllReady();
        verify(mAlternateTouchProvider).onPointerDown(eq(TEST_REQUEST_ID),
                eq(0), eq(0), eq(3f) /* minor */, eq(2f) /* major */);
        verify(mFingerprintManager, never()).onPointerDown(anyLong(), anyInt(), anyInt(), anyInt(),
                anyFloat(), anyFloat());
        verify(mKeyguardUpdateMonitor).onUdfpsPointerDown(eq((int) TEST_REQUEST_ID));
    }

    @Test
    public void cancelAodInterrupt() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        when(mUdfpsView.isIlluminationRequested()).thenReturn(true);
        // WHEN it is cancelled
        mUdfpsController.onCancelUdfps();
        // THEN the illumination is hidden
        verify(mUdfpsView).stopIllumination();
    }

    @Test
    public void aodInterruptTimeout() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        mFgExecutor.runAllReady();
        when(mUdfpsView.isIlluminationRequested()).thenReturn(true);
        // WHEN it times out
        mFgExecutor.advanceClockToNext();
        mFgExecutor.runAllReady();
        // THEN the illumination is hidden
        verify(mUdfpsView).stopIllumination();
    }

    @Test
    public void aodInterruptScreenOff() throws RemoteException {
        // GIVEN screen off
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOff();
        mFgExecutor.runAllReady();

        // WHEN aod interrupt is received
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);

        // THEN no illumination because screen is off
        verify(mUdfpsView, never()).startIllumination(any());
    }

    @Test
    public void aodInterrupt_fingerprintNotRunning() throws RemoteException {
        // GIVEN showing overlay
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD,
                mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();

        // WHEN aod interrupt is received when the fingerprint service isn't running
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(false);
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);

        // THEN no illumination because screen is off
        verify(mUdfpsView, never()).startIllumination(any());
    }

    @Test
    public void playHapticOnTouchUdfpsArea_a11yTouchExplorationEnabled() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing and a11y touch exploration enabled
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
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
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing and a11y touch exploration NOT enabled
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, TEST_UDFPS_SENSOR_ID,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        mBiometricsExecutor.runAllReady();
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mBiometricsExecutor.runAllReady();
        moveEvent.recycle();

        // THEN NO haptic played
        verify(mVibrator, never()).vibrate(
                anyInt(),
                anyString(),
                any(),
                anyString(),
                any());
    }
}
