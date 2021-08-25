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

package com.android.systemui.idle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Looper;
import android.os.PowerManager;
import android.testing.AndroidTestingRunner;
import android.view.Choreographer;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.sensors.AsyncSensorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class IdleHostViewControllerTest extends SysuiTestCase {
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private PowerManager mPowerManager;
    @Mock private AsyncSensorManager mSensorManager;
    @Mock private IdleHostView mIdleHostView;
    @Mock private InputMonitorFactory mInputMonitorFactory;
    @Mock private DelayableExecutor mDelayableExecutor;
    @Mock private Resources mResources;
    @Mock private Looper mLooper;
    @Mock private Provider<View> mViewProvider;
    @Mock private Choreographer mChoreographer;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private Sensor mSensor;
    @Mock private DreamHelper mDreamHelper;
    @Mock private InputMonitorCompat mInputMonitor;
    @Mock private InputChannelCompat.InputEventReceiver mInputEventReceiver;

    private final long mTimestamp = Instant.now().toEpochMilli();
    private KeyguardStateController.Callback mKeyguardStateCallback;
    private StatusBarStateController.StateListener mStatusBarStateListener;
    private IdleHostViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mResources.getBoolean(R.bool.config_enableIdleMode)).thenReturn(true);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(mSensor);
        when(mInputMonitorFactory.getInputMonitor("IdleHostViewController"))
                .thenReturn(mInputMonitor);
        when(mInputMonitor.getInputReceiver(any(), any(), any())).thenReturn(mInputEventReceiver);

        mController = new IdleHostViewController(mContext,
                mBroadcastDispatcher, mPowerManager, mSensorManager, mIdleHostView,
                mInputMonitorFactory, mDelayableExecutor, mResources, mLooper, mViewProvider,
                mChoreographer, mKeyguardStateController, mStatusBarStateController, mDreamHelper);
        mController.init();
        mController.onViewAttached();

        // Captures keyguard state controller callback.
        ArgumentCaptor<KeyguardStateController.Callback> keyguardStateCallbackCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
        verify(mKeyguardStateController).addCallback(keyguardStateCallbackCaptor.capture());
        mKeyguardStateCallback = keyguardStateCallbackCaptor.getValue();

        // Captures status bar state listener.
        ArgumentCaptor<StatusBarStateController.StateListener> statusBarStateListenerCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(statusBarStateListenerCaptor.capture());
        mStatusBarStateListener = statusBarStateListenerCaptor.getValue();
    }

    @Test
    public void testTimeoutToIdleMode() {
        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Regular ambient lighting.
        final SensorEvent sensorEvent = new SensorEvent(mSensor, 3, mTimestamp,
                new float[]{90});
        mController.onSensorChanged(sensorEvent);

        // Times out.
        ArgumentCaptor<Runnable> callbackCapture = ArgumentCaptor.forClass(Runnable.class);
        verify(mDelayableExecutor).executeDelayed(callbackCapture.capture(), anyLong());
        callbackCapture.getValue().run();

        // Verifies start dreaming (idle mode).
        verify(mDreamHelper).startDreaming(any());
    }

    @Test
    public void testTimeoutToLowLightMode() {
        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Captures dream broadcast receiver;
        ArgumentCaptor<BroadcastReceiver> dreamBroadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mBroadcastDispatcher)
                .registerReceiver(dreamBroadcastReceiverCaptor.capture(), any());
        final BroadcastReceiver dreamBroadcastReceiver = dreamBroadcastReceiverCaptor.getValue();

        // Low ambient lighting.
        final SensorEvent sensorEvent = new SensorEvent(mSensor, 3, mTimestamp,
                new float[]{5});
        mController.onSensorChanged(sensorEvent);

        // Verifies it goes to sleep because of low light.
        verify(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());

        mStatusBarStateListener.onDozingChanged(true /*isDozing*/);
        dreamBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_DREAMING_STARTED));

        // User wakes up the device.
        mStatusBarStateListener.onDozingChanged(false /*isDozing*/);
        dreamBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_DREAMING_STOPPED));

        // Clears power manager invocations to make sure the below dozing was triggered by the
        // timeout.
        clearInvocations(mPowerManager);

        // Times out.
        ArgumentCaptor<Runnable> callbackCapture = ArgumentCaptor.forClass(Runnable.class);
        verify(mDelayableExecutor, atLeastOnce()).executeDelayed(callbackCapture.capture(),
                anyLong());
        callbackCapture.getValue().run();

        // Verifies go to sleep (low light mode).
        verify(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testTransitionBetweenIdleAndLowLightMode() {
        // Regular ambient lighting.
        final SensorEvent sensorEventRegularLight = new SensorEvent(mSensor, 3, mTimestamp,
                new float[]{90});
        mController.onSensorChanged(sensorEventRegularLight);

        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Times out.
        ArgumentCaptor<Runnable> callbackCapture = ArgumentCaptor.forClass(Runnable.class);
        verify(mDelayableExecutor).executeDelayed(callbackCapture.capture(), anyLong());
        callbackCapture.getValue().run();

        // Verifies in idle mode (dreaming).
        verify(mDreamHelper).startDreaming(any());
        clearInvocations(mDreamHelper);

        // Ambient lighting becomes dim.
        final SensorEvent sensorEventLowLight = new SensorEvent(mSensor, 3, mTimestamp,
                new float[]{5});
        mController.onSensorChanged(sensorEventLowLight);

        // Verifies in low light mode (dozing).
        verify(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());

        // Ambient lighting becomes bright again.
        mController.onSensorChanged(sensorEventRegularLight);

        // Verifies in idle mode (dreaming).
        verify(mDreamHelper).startDreaming(any());
    }

    @Test
    public void testStartDozingWhenLowLight() {
        // Regular ambient lighting.
        final SensorEvent sensorEventRegularLight = new SensorEvent(mSensor, 3, mTimestamp,
                new float[]{90});
        mController.onSensorChanged(sensorEventRegularLight);

        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Verifies it doesn't go to sleep yet.
        verify(mPowerManager, never()).goToSleep(anyLong(), anyInt(), anyInt());

        // Ambient lighting becomes dim.
        final SensorEvent sensorEventLowLight = new SensorEvent(mSensor, 3, mTimestamp,
                new float[]{5});
        mController.onSensorChanged(sensorEventLowLight);

        // Verifies it goes to sleep.
        verify(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testInputEventReceiverLifecycle() {
        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Should register input event receiver.
        verify(mInputMonitor).getInputReceiver(any(), any(), any());

        // Keyguard dismissed.
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Should dispose input event receiver.
        verify(mInputEventReceiver).dispose();
    }
}
