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

package com.android.systemui.classifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;
import com.android.systemui.util.time.FakeSystemClock;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class FalsingCollectorImplTest extends SysuiTestCase {

    private FalsingCollectorImpl mFalsingCollector;
    @Mock
    private FalsingDataProvider mFalsingDataProvider;
    private final FalsingManagerFake mFalsingManager = new FalsingManagerFake();
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private HistoryTracker mHistoryTracker;
    @Mock
    private ProximitySensor mProximitySensor;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private ShadeInteractor mShadeInteractor;
    @Mock
    private JavaAdapter mJavaAdapter;
    @Mock
    private BatteryController mBatteryController;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private CommunalInteractor mCommunalInteractor;
    @Mock
    private DeviceEntryInteractor mDeviceEntryInteractor;
    private final MutableStateFlow<Boolean> mIsDeviceEntered =
            StateFlowKt.MutableStateFlow(false);
    @Mock
    private SceneContainerOcclusionInteractor mSceneContainerOcclusionInteractor;
    private final MutableStateFlow<Boolean> mIsInvisibleDueToOcclusion =
            StateFlowKt.MutableStateFlow(false);
    private final DockManagerFake mDockManager = new DockManagerFake();
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mShadeInteractor.isQsExpanded()).thenReturn(StateFlowKt.MutableStateFlow(false));

        when(mDeviceEntryInteractor.isDeviceEntered()).thenReturn(mIsDeviceEntered);
        when(mSceneContainerOcclusionInteractor.getInvisibleDueToOcclusion()).thenReturn(
                mIsInvisibleDueToOcclusion);

        mFalsingCollector = new FalsingCollectorImpl(mFalsingDataProvider, mFalsingManager,
                mKeyguardUpdateMonitor, mHistoryTracker, mProximitySensor,
                mStatusBarStateController, mKeyguardStateController,
                () -> mShadeInteractor, mBatteryController,
                mDockManager, mFakeExecutor,
                mJavaAdapter, mFakeSystemClock, () -> mSelectedUserInteractor,
                () -> mCommunalInteractor, () -> mDeviceEntryInteractor,
                () -> mSceneContainerOcclusionInteractor
        );
        mFalsingCollector.init();
    }

    @Test
    public void testRegisterSensor() {
        mFalsingCollector.onScreenTurningOn();
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testNoProximityWhenWirelessCharging() {
        ArgumentCaptor<BatteryController.BatteryStateChangeCallback> batteryCallbackCaptor =
                ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback.class);
        verify(mBatteryController).addCallback(batteryCallbackCaptor.capture());
        batteryCallbackCaptor.getValue().onWirelessChargingChanged(true);
        verify(mProximitySensor).pause();
    }

    @Test
    public void testProximityWhenOffWirelessCharging() {
        ArgumentCaptor<BatteryController.BatteryStateChangeCallback> batteryCallbackCaptor =
                ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback.class);
        verify(mBatteryController).addCallback(batteryCallbackCaptor.capture());
        batteryCallbackCaptor.getValue().onWirelessChargingChanged(false);
        verify(mProximitySensor).resume();
    }

    @Test
    public void testNoProximityWhenDocked() {
        mDockManager.setDockEvent(DockManager.STATE_DOCKED);
        verify(mProximitySensor).pause();
    }

    @Test
    public void testProximityWhenUndocked() {
        mDockManager.setDockEvent(DockManager.STATE_NONE);
        verify(mProximitySensor).resume();
    }

    @Test
    public void testUnregisterSensor() {
        mFalsingCollector.onScreenTurningOn();
        reset(mProximitySensor);
        mFalsingCollector.onScreenOff();
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor_QS() {
        mFalsingCollector.onScreenTurningOn();
        reset(mProximitySensor);
        mFalsingCollector.onQsExpansionChanged(true);
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
        mFalsingCollector.onQsExpansionChanged(false);
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor_Bouncer() {
        mFalsingCollector.onScreenTurningOn();
        reset(mProximitySensor);
        mFalsingCollector.onBouncerShown();
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
        mFalsingCollector.onBouncerHidden();
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor_StateTransition() {
        ArgumentCaptor<StatusBarStateController.StateListener> stateListenerArgumentCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(stateListenerArgumentCaptor.capture());

        mFalsingCollector.onScreenTurningOn();
        reset(mProximitySensor);
        stateListenerArgumentCaptor.getValue().onStateChanged(StatusBarState.SHADE);
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
    }

    @Test
    @DisableSceneContainer
    public void testRegisterSensor_OccludingActivity_sceneContainerDisabled() {
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        ArgumentCaptor<StatusBarStateController.StateListener> stateListenerArgumentCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(stateListenerArgumentCaptor.capture());

        mFalsingCollector.onScreenTurningOn();
        reset(mProximitySensor);
        stateListenerArgumentCaptor.getValue().onStateChanged(StatusBarState.SHADE);
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    @EnableSceneContainer
    public void testRegisterSensor_OccludingActivity_sceneContainerEnabled() {
        mIsInvisibleDueToOcclusion.setValue(true);

        ArgumentCaptor<StatusBarStateController.StateListener> stateListenerArgumentCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(stateListenerArgumentCaptor.capture());

        mFalsingCollector.onScreenTurningOn();
        reset(mProximitySensor);
        stateListenerArgumentCaptor.getValue().onStateChanged(StatusBarState.SHADE);
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testPassThroughEnterKeyEvent() {
        KeyEvent enterDown = KeyEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER,
                0, 0, 0, 0, 0, 0, 0, "");
        KeyEvent enterUp = KeyEvent.obtain(0, 0, MotionEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0,
                0, 0, 0, 0, 0, 0, "");

        mFalsingCollector.onKeyEvent(enterDown);
        verify(mFalsingDataProvider, never()).onKeyEvent(any(KeyEvent.class));

        mFalsingCollector.onKeyEvent(enterUp);
        verify(mFalsingDataProvider, times(1)).onKeyEvent(enterUp);
    }

    @Test
    public void testAvoidAKeyEvent() {
        // Arbitrarily chose the "A" key, as it is not currently allowlisted. If this key is
        // allowlisted in the future, please choose another key that will not be collected.
        KeyEvent aKeyDown = KeyEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, KeyEvent.KEYCODE_A,
                0, 0, 0, 0, 0, 0, 0, "");
        KeyEvent aKeyUp = KeyEvent.obtain(0, 0, MotionEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0,
                0, 0, 0, 0, 0, 0, "");

        mFalsingCollector.onKeyEvent(aKeyDown);
        verify(mFalsingDataProvider, never()).onKeyEvent(any(KeyEvent.class));

        mFalsingCollector.onKeyEvent(aKeyUp);
        verify(mFalsingDataProvider, never()).onKeyEvent(any(KeyEvent.class));
    }

    @Test
    public void testPassThroughGesture() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);

        // Nothing passed initially
        mFalsingCollector.onTouchEvent(down);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        // Up event flushes the down event.
        mFalsingCollector.onTouchEvent(up);
        InOrder orderedCalls = inOrder(mFalsingDataProvider);
        // We can't simply use "eq" or similar because the collector makes a copy of "down".
        orderedCalls.verify(mFalsingDataProvider).onMotionEvent(
                argThat(argument -> argument.getActionMasked() == MotionEvent.ACTION_DOWN));
        orderedCalls.verify(mFalsingDataProvider).onMotionEvent(up);
    }

    @Test
    public void testAvoidGesture() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);

        // Nothing passed initially
        mFalsingCollector.onTouchEvent(down);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        mFalsingCollector.avoidGesture();
        // Up event would flush, but we were told to avoid.
        mFalsingCollector.onTouchEvent(up);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));
    }

    @Test
    public void testIgnoreActionOutside() {
        MotionEvent outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);

        // Nothing passed initially. The outside event will be completely ignored.
        mFalsingCollector.onTouchEvent(outside);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        // Up event flushes, and the outside event isn't passed through.
        mFalsingCollector.onTouchEvent(up);
        verify(mFalsingDataProvider).onMotionEvent(up);
    }

    @Test
    @DisableSceneContainer
    public void testAvoidUnlocked_sceneContainerDisabled() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);

        when(mKeyguardStateController.isShowing()).thenReturn(false);

        // Nothing passed initially
        mFalsingCollector.onTouchEvent(down);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        // Up event would normally flush the up event, but doesn't.
        mFalsingCollector.onTouchEvent(up);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));
    }

    @Test
    @EnableSceneContainer
    public void testAvoidUnlocked_sceneContainerEnabled() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);

        mIsDeviceEntered.setValue(true);

        // Nothing passed initially
        mFalsingCollector.onTouchEvent(down);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        // Up event would normally flush the up event, but doesn't.
        mFalsingCollector.onTouchEvent(up);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));
    }

    @Test
    public void testGestureWhenDozing() {
        // We check the FalsingManager for taps during the transition to AoD (dozing=true,
        // pulsing=false), so the FalsingCollector needs to continue to analyze events that occur
        // while the device is dozing.
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);

        when(mStatusBarStateController.isDozing()).thenReturn(true);

        // Nothing passed initially
        mFalsingCollector.onTouchEvent(down);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        // Up event flushes
        mFalsingCollector.onTouchEvent(up);
        verify(mFalsingDataProvider, times(2)).onMotionEvent(any(MotionEvent.class));
    }

    @Test
    public void testGestureWhenPulsing() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);

        when(mStatusBarStateController.isDozing()).thenReturn(true);
        when(mStatusBarStateController.isPulsing()).thenReturn(true);

        // Nothing passed initially
        mFalsingCollector.onTouchEvent(down);
        verify(mFalsingDataProvider, never()).onMotionEvent(any(MotionEvent.class));

        // Up event would flushes
        mFalsingCollector.onTouchEvent(up);
        verify(mFalsingDataProvider, times(2)).onMotionEvent(any(MotionEvent.class));
    }

    @Test
    public void testOnA11yAction() {
        mFalsingCollector.onA11yAction();
        verify(mFalsingDataProvider).onA11yAction();
    }
}
