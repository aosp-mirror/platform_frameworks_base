/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.PowerManager;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DozeServiceHostTest extends SysuiTestCase {

    private DozeServiceHost mDozeServiceHost;

    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private ScrimController mScrimController;
    @Mock private DozeScrimController mDozeScrimController;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private StatusBarStateControllerImpl mStatusBarStateController;
    @Mock private BatteryController mBatteryController;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private AssistManager mAssistManager;
    @Mock private DozeLog mDozeLog;
    @Mock private PulseExpansionHandler mPulseExpansionHandler;
    @Mock private NotificationWakeUpCoordinator mNotificationWakeUpCoordinator;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private PowerManager mPowerManager;
    @Mock private WakefulnessLifecycle mWakefullnessLifecycle;
    @Mock private StatusBar mStatusBar;
    @Mock private NotificationIconAreaController mNotificationIconAreaController;
    @Mock private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private NotificationPanelViewController mNotificationPanel;
    @Mock private View mAmbientIndicationContainer;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private LockscreenLockIconController mLockscreenLockIconController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDozeServiceHost = new DozeServiceHost(mDozeLog, mPowerManager, mWakefullnessLifecycle,
                mStatusBarStateController, mDeviceProvisionedController, mHeadsUpManager,
                mBatteryController, mScrimController, () -> mBiometricUnlockController,
                mKeyguardViewMediator, () -> mAssistManager, mDozeScrimController,
                mKeyguardUpdateMonitor, mVisualStabilityManager, mPulseExpansionHandler,
                mNotificationShadeWindowController, mNotificationWakeUpCoordinator,
                mLockscreenLockIconController);

        mDozeServiceHost.initialize(mStatusBar, mNotificationIconAreaController,
                mStatusBarKeyguardViewManager, mNotificationShadeWindowViewController,
                mNotificationPanel, mAmbientIndicationContainer);
    }

    @Test
    public void testStartStopDozing() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);

        assertFalse(mDozeServiceHost.getDozingRequested());

        mDozeServiceHost.startDozing();
        verify(mStatusBarStateController).setIsDozing(eq(true));
        verify(mStatusBar).updateIsKeyguard();

        mDozeServiceHost.stopDozing();
        verify(mStatusBarStateController).setIsDozing(eq(false));
    }


    @Test
    public void testPulseWhileDozing_updatesScrimController() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        mStatusBar.showKeyguardImpl();

        // Keep track of callback to be able to stop the pulse
//        DozeHost.PulseCallback[] pulseCallback = new DozeHost.PulseCallback[1];
//        doAnswer(invocation -> {
//            pulseCallback[0] = invocation.getArgument(0);
//            return null;
//        }).when(mDozeScrimController).pulse(any(), anyInt());

        // Starting a pulse should change the scrim controller to the pulsing state
        mDozeServiceHost.pulseWhileDozing(new DozeHost.PulseCallback() {
            @Override
            public void onPulseStarted() {
            }

            @Override
            public void onPulseFinished() {
            }
        }, DozeLog.PULSE_REASON_NOTIFICATION);

        ArgumentCaptor<DozeHost.PulseCallback> pulseCallbackArgumentCaptor =
                ArgumentCaptor.forClass(DozeHost.PulseCallback.class);

        verify(mDozeScrimController).pulse(
                pulseCallbackArgumentCaptor.capture(), eq(DozeLog.PULSE_REASON_NOTIFICATION));
        verify(mStatusBar).updateScrimController();
        reset(mStatusBar);

        pulseCallbackArgumentCaptor.getValue().onPulseFinished();
        assertFalse(mDozeScrimController.isPulsing());
        verify(mStatusBar).updateScrimController();
    }

    @Test
    public void testPulseWhileDozing_notifyAuthInterrupt() {
        HashSet<Integer> reasonsWantingAuth = new HashSet<>(
                Collections.singletonList(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN));
        HashSet<Integer> reasonsSkippingAuth = new HashSet<>(
                Arrays.asList(DozeLog.PULSE_REASON_INTENT,
                        DozeLog.PULSE_REASON_NOTIFICATION,
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION,
                        DozeLog.REASON_SENSOR_PICKUP,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        DozeLog.PULSE_REASON_SENSOR_LONG_PRESS,
                        DozeLog.PULSE_REASON_DOCKING,
                        DozeLog.REASON_SENSOR_WAKE_UP,
                        DozeLog.REASON_SENSOR_TAP));
        HashSet<Integer> reasonsThatDontPulse = new HashSet<>(
                Arrays.asList(DozeLog.REASON_SENSOR_PICKUP,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        DozeLog.REASON_SENSOR_TAP));

        doAnswer(invocation -> {
            DozeHost.PulseCallback callback = invocation.getArgument(0);
            callback.onPulseStarted();
            return null;
        }).when(mDozeScrimController).pulse(any(), anyInt());

        mDozeServiceHost.mWakeLockScreenPerformsAuth = true;
        for (int i = 0; i < DozeLog.TOTAL_REASONS; i++) {
            reset(mKeyguardUpdateMonitor);
            mDozeServiceHost.pulseWhileDozing(mock(DozeHost.PulseCallback.class), i);
            if (reasonsWantingAuth.contains(i)) {
                verify(mKeyguardUpdateMonitor).onAuthInterruptDetected(eq(true));
            } else if (reasonsSkippingAuth.contains(i) || reasonsThatDontPulse.contains(i)) {
                verify(mKeyguardUpdateMonitor, never()).onAuthInterruptDetected(eq(true));
            } else {
                throw new AssertionError("Reason " + i + " isn't specified as wanting or skipping"
                        + " passive auth. Please consider how this pulse reason should behave.");
            }
        }
    }
}
