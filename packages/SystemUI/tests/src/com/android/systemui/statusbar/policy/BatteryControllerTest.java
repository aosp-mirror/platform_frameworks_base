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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import static android.os.BatteryManager.EXTRA_PRESENT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import com.android.dx.mockito.inline.extended.StaticInOrder;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BatteryControllerTest extends SysuiTestCase {

    @Mock private PowerManager mPowerManager;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private DemoModeController mDemoModeController;
    @Mock private View mView;
    private BatteryControllerImpl mBatteryController;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() throws IllegalStateException {
        MockitoAnnotations.initMocks(this);
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .mockStatic(BatterySaverUtils.class)
                .startMocking();

        mBatteryController = new BatteryControllerImpl(getContext(),
                mock(EnhancedEstimates.class),
                mPowerManager,
                mBroadcastDispatcher,
                mDemoModeController,
                new Handler(),
                new Handler());
        // Can throw if updateEstimate is called on the main thread
        mBatteryController.init();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testBatteryInitialized() {
        Assert.assertTrue(mBatteryController.mHasReceivedBattery);
    }

    @Test
    public void testIndependentAODBatterySaver_true() {
        PowerSaveState state = new PowerSaveState.Builder()
                .setBatterySaverEnabled(true)
                .build();
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        when(mPowerManager.getPowerSaveState(PowerManager.ServiceType.AOD)).thenReturn(state);
        when(mPowerManager.isPowerSaveMode()).thenReturn(true);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isPowerSave());
        Assert.assertTrue(mBatteryController.isAodPowerSave());
    }

    @Test
    public void testIndependentAODBatterySaver_false() {
        PowerSaveState state = new PowerSaveState.Builder()
                .setBatterySaverEnabled(false)
                .build();
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        when(mPowerManager.getPowerSaveState(PowerManager.ServiceType.AOD)).thenReturn(state);
        when(mPowerManager.isPowerSaveMode()).thenReturn(true);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isPowerSave());
        Assert.assertFalse(mBatteryController.isAodPowerSave());
    }

    @Test
    public void testBatteryPresentState_notPresent() {
        // GIVEN a battery state callback listening for changes
        BatteryStateChangeCallback cb = mock(BatteryStateChangeCallback.class);
        mBatteryController.addCallback(cb);

        // WHEN the state of the battery becomes unknown
        Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED);
        i.putExtra(EXTRA_PRESENT, false);
        mBatteryController.onReceive(getContext(), i);

        // THEN the callback is notified
        verify(cb, atLeastOnce()).onBatteryUnknownStateChanged(true);
    }

    @Test
    public void testBatteryPresentState_callbackAddedAfterStateChange() {
        // GIVEN a battery state callback
        BatteryController.BatteryStateChangeCallback cb =
                mock(BatteryController.BatteryStateChangeCallback.class);

        // GIVEN the state has changed before adding a new callback
        Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED);
        i.putExtra(EXTRA_PRESENT, false);
        mBatteryController.onReceive(getContext(), i);

        // WHEN a callback is added
        mBatteryController.addCallback(cb);

        // THEN it is informed about the battery state
        verify(cb, atLeastOnce()).onBatteryUnknownStateChanged(true);
    }

    @Test
    public void testBatteryUtilsCalledOnSetPowerSaveMode() {
        mBatteryController.setPowerSaveMode(true, mView);
        mBatteryController.setPowerSaveMode(false, mView);

        StaticInOrder inOrder = inOrder(staticMockMarker(BatterySaverUtils.class));
        inOrder.verify(() -> BatterySaverUtils.setPowerSaveMode(getContext(), true, true));
        inOrder.verify(() -> BatterySaverUtils.setPowerSaveMode(getContext(), false, true));
    }

    @Test
    public void testSaveViewReferenceWhenSettingPowerSaveMode() {
        mBatteryController.setPowerSaveMode(false, mView);

        Assert.assertNull(mBatteryController.getLastPowerSaverStartView());

        mBatteryController.setPowerSaveMode(true, mView);

        Assert.assertSame(mView, mBatteryController.getLastPowerSaverStartView().get());
    }

    @Test
    public void testClearViewReference() {
        mBatteryController.setPowerSaveMode(true, mView);
        mBatteryController.clearLastPowerSaverStartView();

        Assert.assertNull(mBatteryController.getLastPowerSaverStartView());
    }

    @Test
    public void testBatteryEstimateFetch_doesNotThrow() throws IllegalStateException {
        mBatteryController.getEstimatedTimeRemainingString(
                (String estimate) -> {
                    // don't care about the result
                });
        TestableLooper.get(this).processAllMessages();
        // Should not throw an exception
    }
}
