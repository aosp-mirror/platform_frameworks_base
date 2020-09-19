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

package com.android.systemui.classifier.brightline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.DisplayMetrics;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BrightLineFalsingManagerTest extends SysuiTestCase {


    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private ProximitySensor mProximitySensor;
    private SysuiStatusBarStateController mStatusBarStateController;

    private BrightLineFalsingManager mFalsingManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        DisplayMetrics dm = new DisplayMetrics();
        dm.xdpi = 100;
        dm.ydpi = 100;
        dm.widthPixels = 100;
        dm.heightPixels = 100;
        FalsingDataProvider falsingDataProvider = new FalsingDataProvider(dm);
        DeviceConfigProxy deviceConfigProxy = new DeviceConfigProxyFake();
        DockManager dockManager = new DockManagerFake();
        mStatusBarStateController = new StatusBarStateControllerImpl(new UiEventLoggerFake());
        mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        mFalsingManager = new BrightLineFalsingManager(falsingDataProvider,
                mKeyguardUpdateMonitor, mProximitySensor, deviceConfigProxy, dockManager,
                mStatusBarStateController);
    }

    @Test
    public void testRegisterSensor() {
        mFalsingManager.onScreenTurningOn();
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor() {
        mFalsingManager.onScreenTurningOn();
        reset(mProximitySensor);
        mFalsingManager.onScreenOff();
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor_QS() {
        mFalsingManager.onScreenTurningOn();
        reset(mProximitySensor);
        mFalsingManager.setQsExpanded(true);
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
        mFalsingManager.setQsExpanded(false);
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor_Bouncer() {
        mFalsingManager.onScreenTurningOn();
        reset(mProximitySensor);
        mFalsingManager.onBouncerShown();
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
        mFalsingManager.onBouncerHidden();
        verify(mProximitySensor).register(any(ThresholdSensor.Listener.class));
    }

    @Test
    public void testUnregisterSensor_StateTransition() {
        mFalsingManager.onScreenTurningOn();
        reset(mProximitySensor);
        mStatusBarStateController.setState(StatusBarState.SHADE);
        verify(mProximitySensor).unregister(any(ThresholdSensor.Listener.class));
    }
}
