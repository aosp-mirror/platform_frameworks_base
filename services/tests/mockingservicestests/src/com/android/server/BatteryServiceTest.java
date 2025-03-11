/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.content.Context;
import android.hardware.health.HealthInfo;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.am.BatteryStatsService;
import com.android.server.flags.Flags;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class BatteryServiceTest {

    private static final int CURRENT_BATTERY_VOLTAGE = 3000;
    private static final int VOLTAGE_LESS_THEN_ONE_PERCENT = 3029;
    private static final int VOLTAGE_MORE_THEN_ONE_PERCENT = 3030;
    private static final int CURRENT_BATTERY_TEMP = 300;
    private static final int TEMP_LESS_THEN_ONE_DEGREE_CELSIUS = 305;
    private static final int TEMP_MORE_THEN_ONE_DEGREE_CELSIUS = 310;
    private static final int CURRENT_BATTERY_HEALTH = 2;
    private static final int UPDATED_BATTERY_HEALTH = 3;
    private static final int CURRENT_CHARGE_COUNTER = 4680000;
    private static final int UPDATED_CHARGE_COUNTER = 4218000;
    private static final int CURRENT_MAX_CHARGING_CURRENT = 298125;
    private static final int UPDATED_MAX_CHARGING_CURRENT = 398125;
    private static final int HANDLER_IDLE_TIME_MS = 5000;
    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(SystemProperties.class)
            .mockStatic(ActivityManager.class)
            .mockStatic(BatteryStatsService.class)
            .build();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock
    private Context mContextMock;
    @Mock
    private LightsManager mLightsManagerMock;
    @Mock
    private ActivityManagerInternal mActivityManagerInternalMock;
    @Mock
    private IBatteryStats mIBatteryStatsMock;

    private BatteryService mBatteryService;
    private String mSystemUiPackage;

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSystemUiPackage = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().getString(R.string.config_systemUi);

        when(mLightsManagerMock.getLight(anyInt())).thenReturn(mock(LogicalLight.class));
        when(mActivityManagerInternalMock.isSystemReady()).thenReturn(true);
        when(mContextMock.getResources()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getResources());
        ExtendedMockito.when(BatteryStatsService.getService()).thenReturn(mIBatteryStatsMock);

        doNothing().when(mIBatteryStatsMock).setBatteryState(anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyLong());
        doNothing().when(() -> SystemProperties.set(anyString(), anyString()));
        doNothing().when(() -> ActivityManager.broadcastStickyIntent(any(),
                eq(new String[]{mSystemUiPackage}), eq(AppOpsManager.OP_NONE),
                eq(BatteryService.BATTERY_CHANGED_OPTIONS), eq(UserHandle.USER_ALL)));

        addLocalServiceMock(LightsManager.class, mLightsManagerMock);
        addLocalServiceMock(ActivityManagerInternal.class, mActivityManagerInternalMock);

        createBatteryService();
    }

    @Test
    public void createBatteryService_withNullLooper_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> new BatteryService(mContextMock));
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void onlyVoltageUpdated_lessThenOnePercent_broadcastNotSent() {
        mBatteryService.update(createHealthInfo(VOLTAGE_LESS_THEN_ONE_PERCENT, CURRENT_BATTERY_TEMP,
                CURRENT_CHARGE_COUNTER, CURRENT_BATTERY_HEALTH, CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(0);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void onlyVoltageUpdated_beforeTwentySeconds_broadcastNotSent() {
        mBatteryService.update(
                createHealthInfo(VOLTAGE_MORE_THEN_ONE_PERCENT, CURRENT_BATTERY_TEMP,
                        CURRENT_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH,
                        CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(0);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void voltageUpdated_withUpdateInChargingCurrent_broadcastSent() {
        mBatteryService.mLastBroadcastVoltageUpdateTime = SystemClock.elapsedRealtime() - 20000;
        long lastChargingCurrentUpdateTime =
                mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime;
        mBatteryService.update(createHealthInfo(VOLTAGE_MORE_THEN_ONE_PERCENT, CURRENT_BATTERY_TEMP,
                CURRENT_CHARGE_COUNTER, CURRENT_BATTERY_HEALTH, UPDATED_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        assertTrue(lastChargingCurrentUpdateTime
                < mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime);
        verifyNumberOfTimesBroadcastSent(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void onlyTempUpdated_lessThenOneDegreeCelsius_broadcastNotSent() {
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, TEMP_LESS_THEN_ONE_DEGREE_CELSIUS,
                        CURRENT_CHARGE_COUNTER, CURRENT_BATTERY_HEALTH,
                        CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(0);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void tempUpdated_broadcastSent() {
        long lastVoltageUpdateTime = mBatteryService.mLastBroadcastVoltageUpdateTime;
        long lastChargingCurrentUpdateTime =
                mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime;
        mBatteryService.update(
                createHealthInfo(VOLTAGE_LESS_THEN_ONE_PERCENT, TEMP_MORE_THEN_ONE_DEGREE_CELSIUS,
                        UPDATED_CHARGE_COUNTER, CURRENT_BATTERY_HEALTH,
                        UPDATED_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        assertTrue(lastVoltageUpdateTime < mBatteryService.mLastBroadcastVoltageUpdateTime);
        assertTrue(lastChargingCurrentUpdateTime
                < mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime);
        verifyNumberOfTimesBroadcastSent(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void batteryHealthUpdated_withOtherExtrasConstant_broadcastSent() {
        long lastVoltageUpdateTime = mBatteryService.mLastBroadcastVoltageUpdateTime;
        long lastChargingCurrentUpdateTime =
                mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime;
        mBatteryService.update(
                createHealthInfo(VOLTAGE_LESS_THEN_ONE_PERCENT, CURRENT_BATTERY_TEMP,
                        CURRENT_CHARGE_COUNTER,
                        UPDATED_BATTERY_HEALTH, UPDATED_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(1);

        // updating counter just after the health update does not triggers broadcast.
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, CURRENT_BATTERY_TEMP,
                        UPDATED_CHARGE_COUNTER,
                        UPDATED_BATTERY_HEALTH, CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        assertTrue(lastVoltageUpdateTime < mBatteryService.mLastBroadcastVoltageUpdateTime);
        assertTrue(lastChargingCurrentUpdateTime
                < mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime);
        verifyNumberOfTimesBroadcastSent(1);
    }

    @Test
    @DisableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void voltageUpdated_lessThanOnePercent_flagDisabled_broadcastSent() {
        mBatteryService.update(createHealthInfo(VOLTAGE_LESS_THEN_ONE_PERCENT, CURRENT_BATTERY_TEMP,
                CURRENT_CHARGE_COUNTER, CURRENT_BATTERY_HEALTH, CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void onlyChargeCounterUpdated_broadcastNotSent() {
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, CURRENT_BATTERY_TEMP,
                        UPDATED_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH, CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(0);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void chargeCounterUpdated_tempUpdatedLessThanOneDegree_broadcastNotSent() {
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, TEMP_LESS_THEN_ONE_DEGREE_CELSIUS,
                        UPDATED_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH, CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(0);
    }

    @Test
    @DisableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void onlyChargeCounterUpdated_broadcastSent() {
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, CURRENT_BATTERY_TEMP,
                        UPDATED_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH, CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void onlyMaxChargingCurrentUpdated_beforeFiveSeconds_broadcastNotSent() {
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, CURRENT_BATTERY_TEMP,
                        CURRENT_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH,
                        UPDATED_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        verifyNumberOfTimesBroadcastSent(0);
    }

    @Test
    @EnableFlags(Flags.FLAG_RATE_LIMIT_BATTERY_CHANGED_BROADCAST)
    public void maxChargingCurrentUpdated_afterFiveSeconds_broadcastSent() {
        mBatteryService.mLastBroadcastMaxChargingCurrentUpdateTime =
                SystemClock.elapsedRealtime() - 5000;
        long lastVoltageUpdateTime = mBatteryService.mLastBroadcastVoltageUpdateTime;
        mBatteryService.update(
                createHealthInfo(VOLTAGE_MORE_THEN_ONE_PERCENT, CURRENT_BATTERY_TEMP,
                        CURRENT_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH,
                        UPDATED_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();

        assertTrue(lastVoltageUpdateTime < mBatteryService.mLastBroadcastVoltageUpdateTime);
        verifyNumberOfTimesBroadcastSent(1);
    }

    private HealthInfo createHealthInfo(
            int batteryVoltage,
            int batteryTemperature,
            int batteryChargeCounter,
            int batteryHealth,
            int maxChargingCurrent) {
        HealthInfo h = new HealthInfo();
        h.batteryVoltageMillivolts = batteryVoltage;
        h.batteryTemperatureTenthsCelsius = batteryTemperature;
        h.batteryChargeCounterUah = batteryChargeCounter;
        h.batteryStatus = 5;
        h.batteryHealth = batteryHealth;
        h.batteryPresent = true;
        h.batteryLevel = 100;
        h.maxChargingCurrentMicroamps = maxChargingCurrent;
        h.batteryCurrentAverageMicroamps = -2812;
        h.batteryCurrentMicroamps = 298125;
        h.maxChargingVoltageMicrovolts = 3000;
        h.batteryCycleCount = 50;
        h.chargingState = 4;
        h.batteryCapacityLevel = 100;
        return h;
    }

    // Creates a new battery service objects and sets the initial values.
    private void createBatteryService() throws InterruptedException {
        final HandlerThread handlerThread = new HandlerThread("BatteryServiceTest");
        handlerThread.start();

        mBatteryService = new BatteryService(mContextMock, handlerThread.getLooper());

        // trigger the update to set the initial values.
        mBatteryService.update(
                createHealthInfo(CURRENT_BATTERY_VOLTAGE, CURRENT_BATTERY_TEMP,
                        CURRENT_CHARGE_COUNTER,
                        CURRENT_BATTERY_HEALTH,
                        CURRENT_MAX_CHARGING_CURRENT));

        waitForHandlerToExecute();
    }

    private void waitForHandlerToExecute() {
        final CountDownLatch latch = new CountDownLatch(1);
        mBatteryService.getHandlerForTest().post(latch::countDown);
        boolean isExecutionComplete = false;

        try {
            isExecutionComplete = latch.await(HANDLER_IDLE_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("Handler interrupted before executing the message " + e);
        }

        assertTrue("Timed out while waiting for Handler to execute.", isExecutionComplete);
    }

    private void verifyNumberOfTimesBroadcastSent(int numberOfTimes) {
        // Increase the numberOfTimes by 1 as one broadcast was sent initially during the test
        // setUp.
        verify(() -> ActivityManager.broadcastStickyIntent(any(),
                        eq(new String[]{mSystemUiPackage}), eq(AppOpsManager.OP_NONE),
                        eq(BatteryService.BATTERY_CHANGED_OPTIONS), eq(UserHandle.USER_ALL)),
                times(++numberOfTimes));
    }
}
