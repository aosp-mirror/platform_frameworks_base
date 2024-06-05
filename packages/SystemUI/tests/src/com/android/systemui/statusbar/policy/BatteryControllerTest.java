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
import static com.android.settingslib.fuelgauge.BatterySaverLogging.SAVER_ENABLED_QS;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticInOrder;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BatteryControllerTest extends SysuiTestCase {

    @Mock private PowerManager mPowerManager;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private DemoModeController mDemoModeController;
    @Mock private Expandable mExpandable;
    @Mock private UsbPort mUsbPort;
    @Mock private UsbManager mUsbManager;
    @Mock private UsbPortStatus mUsbPortStatus;
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
                mock(DumpManager.class),
                mock(BatteryControllerLogger.class),
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
        mBatteryController.setPowerSaveMode(true, mExpandable);
        mBatteryController.setPowerSaveMode(false, mExpandable);

        StaticInOrder inOrder = inOrder(staticMockMarker(BatterySaverUtils.class));
        inOrder.verify(() -> BatterySaverUtils.setPowerSaveMode(getContext(), true, true,
                SAVER_ENABLED_QS));
        inOrder.verify(() -> BatterySaverUtils.setPowerSaveMode(getContext(), false, true,
                SAVER_ENABLED_QS));
    }

    @Test
    public void testSaveViewReferenceWhenSettingPowerSaveMode() {
        mBatteryController.setPowerSaveMode(false, mExpandable);

        Assert.assertNull(mBatteryController.getLastPowerSaverStartExpandable());

        mBatteryController.setPowerSaveMode(true, mExpandable);

        Assert.assertSame(mExpandable, mBatteryController.getLastPowerSaverStartExpandable().get());
    }

    @Test
    public void testClearViewReference() {
        mBatteryController.setPowerSaveMode(true, mExpandable);
        mBatteryController.clearLastPowerSaverStartExpandable();

        Assert.assertNull(mBatteryController.getLastPowerSaverStartExpandable());
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

    @Test
    public void batteryStateChanged_withChargingSourceDock_isChargingSourceDockTrue() {
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_DOCK);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isChargingSourceDock());
    }

    @Test
    public void batteryStateChanged_withChargingSourceNotDock_isChargingSourceDockFalse() {
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertFalse(mBatteryController.isChargingSourceDock());
    }

    @Test
    public void batteryStateChanged_chargingStatusNotLongLife_outputsFalse() {
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_CHARGING_STATUS,
                BatteryManager.CHARGING_POLICY_DEFAULT);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertFalse(mBatteryController.isBatteryDefender());
    }

    @Test
    public void batteryStateChanged_chargingStatusLongLife_outputsTrue() {
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_CHARGING_STATUS,
                BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isBatteryDefender());
    }

    @Test
    public void batteryStateChanged_noChargingStatusGiven_outputsFalse() {
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertFalse(mBatteryController.isBatteryDefender());
    }

    @Test
    public void complianceChanged_complianceIncompatible_outputsTrue() {
        mContext.addMockSystemService(UsbManager.class, mUsbManager);
        setupIncompatibleCharging();
        Intent intent = new Intent(UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isIncompatibleCharging());
    }

    @Test
    public void complianceChanged_emptyComplianceWarnings_outputsFalse() {
        mContext.addMockSystemService(UsbManager.class, mUsbManager);
        setupIncompatibleCharging();
        when(mUsbPortStatus.getComplianceWarnings()).thenReturn(new int[1]);
        Intent intent = new Intent(UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertFalse(mBatteryController.isIncompatibleCharging());
    }

    @Test
    public void callbackRemovedWhileDispatching_doesntCrash() {
        final AtomicBoolean remove = new AtomicBoolean(false);
        BatteryStateChangeCallback callback = new BatteryStateChangeCallback() {
            @Override
            public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                if (remove.get()) {
                    mBatteryController.removeCallback(this);
                }
            }
        };
        mBatteryController.addCallback(callback);
        // Add another callback so the iteration continues
        mBatteryController.addCallback(new BatteryStateChangeCallback() {});
        remove.set(true);
        mBatteryController.fireBatteryLevelChanged();
    }

    private void setupIncompatibleCharging() {
        final List<UsbPort> usbPorts = new ArrayList<>();
        usbPorts.add(mUsbPort);
        when(mUsbManager.getPorts()).thenReturn(usbPorts);
        when(mUsbPort.getStatus()).thenReturn(mUsbPortStatus);
        when(mUsbPort.supportsComplianceWarnings()).thenReturn(true);
        when(mUsbPortStatus.isConnected()).thenReturn(true);
        when(mUsbPortStatus.getComplianceWarnings())
                .thenReturn(new int[]{UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY});
    }
}
