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

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.TypedArray;
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
import android.os.Vibrator;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
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

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    // Unit under test
    private UdfpsController mUdfpsController;

    // Dependencies
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
    private StatusBar mStatusBar;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
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
    private Vibrator mVibrator;
    @Mock
    private UdfpsHapticsSimulator mUdfpsHapticsSimulator;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private Handler mHandler;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private SystemClock mSystemClock;

    private FakeExecutor mFgExecutor;

    // Stuff for configuring mocks
    @Mock
    private UdfpsView mUdfpsView;
    @Mock
    private UdfpsEnrollView mEnrollView;
    @Mock
    private UdfpsKeyguardView mKeyguardView;
    @Mock
    private UdfpsKeyguardViewController mUdfpsKeyguardViewController;
    @Mock
    private TypedArray mBrightnessValues;
    @Mock
    private TypedArray mBrightnessBacklight;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor private ArgumentCaptor<UdfpsView.OnTouchListener> mTouchListenerCaptor;
    @Captor private ArgumentCaptor<Runnable> mOnIlluminatedRunnableCaptor;
    @Captor private ArgumentCaptor<ScreenLifecycle.Observer> mScreenObserverCaptor;
    private ScreenLifecycle.Observer mScreenObserver;

    @Captor private ArgumentCaptor<UdfpsAnimationViewController> mAnimViewControllerCaptor;

    @Before
    public void setUp() {
        setUpResources();
        mExecution = new FakeExecution();

        when(mLayoutInflater.inflate(R.layout.udfps_view, null, false))
                .thenReturn(mUdfpsView);
        when(mLayoutInflater.inflate(R.layout.udfps_enroll_view, null))
                .thenReturn(mEnrollView); // for showOverlay REASON_ENROLL_ENROLLING
        when(mLayoutInflater.inflate(R.layout.udfps_keyguard_view, null))
                .thenReturn(mKeyguardView); // for showOverlay REASON_AUTH_FPM_KEYGUARD
        when(mEnrollView.getContext()).thenReturn(mContext);
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
        mUdfpsController = new UdfpsController(
                mContext,
                mExecution,
                mLayoutInflater,
                mFingerprintManager,
                mWindowManager,
                mStatusBarStateController,
                mFgExecutor,
                mStatusBar,
                mStatusBarKeyguardViewManager,
                mDumpManager,
                mKeyguardUpdateMonitor,
                mKeyguardViewMediator,
                mFalsingManager,
                mPowerManager,
                mAccessibilityManager,
                mLockscreenShadeTransitionController,
                mScreenLifecycle,
                mVibrator,
                mUdfpsHapticsSimulator,
                Optional.of(mHbmProvider),
                mKeyguardStateController,
                mKeyguardBypassController,
                mDisplayManager,
                mHandler,
                mConfigurationController,
                mSystemClock);
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();
        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());
        mScreenObserver = mScreenObserverCaptor.getValue();

        assertEquals(TEST_UDFPS_SENSOR_ID, mUdfpsController.mSensorProps.sensorId);
    }

    private void setUpResources() {
        when(mBrightnessValues.length()).thenReturn(2);
        when(mBrightnessValues.getFloat(0, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(1f);
        when(mBrightnessValues.getFloat(1, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(2f);
        when(mBrightnessBacklight.length()).thenReturn(2);
        when(mBrightnessBacklight.getFloat(0, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(1f);
        when(mBrightnessBacklight.getFloat(1, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(2f);
    }

    @Test
    public void dozeTimeTick() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
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
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        downEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void onActionMove_dozing_setDeviceEntryIntent() throws RemoteException {
        // GIVEN the current animation is UdfpsKeyguardViewController and device IS dozing
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN device entry intent is never to true b/c device was dozing on touch
        verify(mKeyguardBypassController, never()).setUserHasDeviceEntryIntent(true);
    }

    @Test
    public void onActionMove_onKeyguard_setDeviceEntryIntent() throws RemoteException {
        // GIVEN the current animation is UdfpsKeyguardViewController and device isn't dozing
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN device entry intent is set to true
        verify(mKeyguardBypassController).setUserHasDeviceEntryIntent(true);
    }

    @Test
    public void onActionMove_onEnrollment_neverSetDeviceEntryIntent() throws RemoteException {
        // GIVEN the current animation is UdfpsEnrollViewController
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(
                mock(UdfpsEnrollViewController.class));

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN device entry intent is never set
        verify(mKeyguardBypassController, never()).setUserHasDeviceEntryIntent(anyBoolean());
    }

    @Test
    public void onActionMoveTouch_whenCanDismissLockScreen_entersDevice() throws RemoteException {
        // GIVEN can dismiss lock screen and the current animation is an UdfpsKeyguardViewController
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_MOVE is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void onMultipleTouch_whenCanDismissLockScreen_entersDeviceOnce() throws RemoteException {
        // GIVEN can dismiss lock screen and the current animation is an UdfpsKeyguardViewController
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);
        when(mUdfpsView.getAnimationViewController()).thenReturn(mUdfpsKeyguardViewController);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN multiple touches are received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN notify keyguard authenticate to dismiss the keyguard
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void showUdfpsOverlay_addsViewToWindow() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mWindowManager).addView(eq(mUdfpsView), any());
    }

    @Test
    public void hideUdfpsOverlay_removesViewFromWindow() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
        mFgExecutor.runAllReady();
        verify(mWindowManager).removeView(eq(mUdfpsView));
    }

    @Test
    public void testSubscribesToOrientationChangesWhenShowingOverlay() throws Exception {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).registerDisplayListener(any(), eq(mHandler));

        mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).unregisterDisplayListener(any());
    }

    @Test
    public void fingerDown() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();
        // THEN FingerprintManager is notified about onPointerDown
        verify(mFingerprintManager).onPointerDown(eq(mUdfpsController.mSensorProps.sensorId), eq(0),
                eq(0), eq(0f), eq(0f));
        // AND illumination begins
        verify(mUdfpsView).startIllumination(mOnIlluminatedRunnableCaptor.capture());
        // AND onIlluminatedRunnable notifies FingerprintManager about onUiReady
        mOnIlluminatedRunnableCaptor.getValue().run();
        verify(mFingerprintManager).onUiReady(eq(mUdfpsController.mSensorProps.sensorId));
    }

    @Test
    public void aodInterrupt() throws RemoteException {
        // GIVEN that the overlay is showing and screen is on
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        // WHEN fingerprint is requested because of AOD interrupt
        mUdfpsController.onAodInterrupt(0, 0, 2f, 3f);
        // THEN illumination begins
        // AND onIlluminatedRunnable that notifies FingerprintManager is set
        verify(mUdfpsView).startIllumination(mOnIlluminatedRunnableCaptor.capture());
        mOnIlluminatedRunnableCaptor.getValue().run();
        verify(mFingerprintManager).onPointerDown(eq(mUdfpsController.mSensorProps.sensorId), eq(0),
                eq(0), eq(3f) /* minor */, eq(2f) /* major */);
    }

    @Test
    public void cancelAodInterrupt() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
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
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
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
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOff();
        mFgExecutor.runAllReady();

        // WHEN aod interrupt is received
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);

        // THEN no illumination because screen is off
        verify(mUdfpsView, never()).startIllumination(any());
    }

    @Test
    public void playHapticOnTouchUdfpsArea() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN click haptic is played
        verify(mVibrator).vibrate(
                anyInt(),
                anyString(),
                eq(mUdfpsController.EFFECT_CLICK),
                eq("udfps-onStart"),
                eq(UdfpsController.VIBRATION_SONIFICATION_ATTRIBUTES));
    }
}
