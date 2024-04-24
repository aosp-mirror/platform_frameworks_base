/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BatteryControllerStartableTest extends SysuiTestCase {

    private BatteryController mBatteryController;
    private BatteryControllerStartable mBatteryControllerStartable;
    private MockitoSession mMockitoSession;
    private FakeExecutor mExecutor;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setUp() throws IllegalStateException {
        MockitoAnnotations.initMocks(this);
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .mockStatic(BatterySaverUtils.class)
                .startMocking();

        mExecutor = new FakeExecutor(new FakeSystemClock());

        mBatteryController = new BatteryControllerImpl(getContext(),
                mock(EnhancedEstimates.class),
                mock(PowerManager.class),
                mock(BroadcastDispatcher.class),
                mock(DemoModeController.class),
                mock(DumpManager.class),
                mock(BatteryControllerLogger.class),
                new Handler(Looper.getMainLooper()),
                new Handler(Looper.getMainLooper()));
        mBatteryController.init();

        mBatteryControllerStartable = new BatteryControllerStartable(mBatteryController,
                mBroadcastDispatcher, mExecutor);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    @EnableFlags(Flags.FLAG_REGISTER_BATTERY_CONTROLLER_RECEIVERS_IN_CORESTARTABLE)
    public void start_flagEnabled_registersListeners() {
        mBatteryControllerStartable.start();
        mExecutor.runAllReady();

        verify(mBroadcastDispatcher).registerReceiver(any(), any());
    }

    @Test
    @DisableFlags(Flags.FLAG_REGISTER_BATTERY_CONTROLLER_RECEIVERS_IN_CORESTARTABLE)
    public void start_flagDisabled_doesNotRegistersListeners() {
        mBatteryControllerStartable.start();
        mExecutor.runAllReady();

        verifyZeroInteractions(mBroadcastDispatcher);
    }
}
