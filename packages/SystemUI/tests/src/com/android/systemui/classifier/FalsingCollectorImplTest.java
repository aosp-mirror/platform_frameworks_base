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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class FalsingCollectorImplTest extends SysuiTestCase {

    private FalsingCollectorImpl mFalsingCollector;
    @Mock
    private FalsingDataProvider mFalsingDataProvider;
    private final FalsingManagerFake mFalsingManager = new FalsingManagerFake();
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private ProximitySensor mProximitySensor;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);

        mFalsingCollector = new FalsingCollectorImpl(mFalsingDataProvider, mFalsingManager,
                mKeyguardUpdateMonitor, mProximitySensor, mStatusBarStateController);
    }


    @Test
    public void testRegisterSensor() {
        mFalsingCollector.onScreenTurningOn();
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testNoProximityWhenWirelessCharging() {
        when(mFalsingDataProvider.isWirelessCharging()).thenReturn(true);
        mFalsingCollector.onScreenTurningOn();
        verify(mProximitySensor, never()).register(any(ThresholdSensor.Listener.class));
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
        mFalsingCollector.setQsExpanded(true);
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
        mFalsingCollector.setQsExpanded(false);
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
}
