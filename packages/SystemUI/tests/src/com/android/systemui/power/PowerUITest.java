/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.power;

import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN;
import static android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT;
import static android.os.HardwarePropertiesManager.TEMPERATURE_SHUTDOWN;
import static android.os.HardwarePropertiesManager.TEMPERATURE_THROTTLING;
import static android.provider.Settings.Global.SHOW_TEMPERATURE_WARNING;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.HardwarePropertiesManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;

import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.power.PowerUI.WarningsUI;
import com.android.systemui.statusbar.phone.StatusBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class PowerUITest extends SysuiTestCase {

    private static final boolean UNPLUGGED = false;
    private static final boolean POWER_SAVER_OFF = false;
    private static final int ABOVE_WARNING_BUCKET = 1;
    private static final long ONE_HOUR_MILLIS = Duration.ofHours(1).toMillis();
    public static final int BELOW_WARNING_BUCKET = -1;
    public static final long BELOW_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(2);
    public static final long ABOVE_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(4);
    private static final long ABOVE_CHARGE_CYCLE_THRESHOLD = Duration.ofHours(8).toMillis();
    private static final int OLD_BATTERY_LEVEL_NINE = 9;
    private static final int OLD_BATTERY_LEVEL_10 = 10;
    private static final int DEFAULT_OVERHEAT_ALARM_THRESHOLD = 58;
    private HardwarePropertiesManager mHardProps;
    private WarningsUI mMockWarnings;
    private PowerUI mPowerUI;
    private EnhancedEstimates mEnhancedEstimates;
    @Mock private PowerManager mPowerManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMockWarnings = mDependency.injectMockDependency(WarningsUI.class);
        mEnhancedEstimates = mDependency.injectMockDependency(EnhancedEstimates.class);
        mHardProps = mock(HardwarePropertiesManager.class);

        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.addMockSystemService(Context.HARDWARE_PROPERTIES_SERVICE, mHardProps);
        mContext.addMockSystemService(Context.POWER_SERVICE, mPowerManager);

        setUnderThreshold();
        createPowerUi();
    }

    @After
    public void tearDown() throws Exception {
        mPowerUI = null;
    }

    @Test
    public void testNoConfig_NoWarnings() {
        setOverThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
    }

    @Test
    public void testConfig_NoWarnings() {
        setUnderThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
    }

    @Test
    public void testConfig_Warnings() {
        setOverThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testSettingOverrideConfig() {
        setOverThreshold();
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testShutdownBasedThreshold() {
        int tolerance = 2;
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, -1);
        resources.addOverride(R.integer.config_warningTemperatureTolerance, tolerance);
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_SHUTDOWN))
                .thenReturn(new float[] { 55 + tolerance });

        setCurrentTemp(54); // Below threshold.
        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();

        setCurrentTemp(56); // Above threshold.
        mPowerUI.updateTemperature();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testNoConfig_noAlarms() {
        setOverThreshold();
        final Boolean overheat = false;
        final Boolean shouldBeepSound = false;
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_alarmTemperature, 55);
        resources.addOverride(R.bool.config_alarmTemperatureBeepSound, shouldBeepSound);

        mPowerUI.start();
        verify(mMockWarnings, never()).notifyHighTemperatureAlarm(overheat, shouldBeepSound);
    }

    @Test
    public void testConfig_noAlarms() {
        setUnderThreshold();
        final Boolean overheat = false;
        final Boolean shouldBeepSound = false;
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureAlarm, 1);
        resources.addOverride(R.integer.config_alarmTemperature, 58);
        resources.addOverride(R.bool.config_alarmTemperatureBeepSound, shouldBeepSound);

        mPowerUI.start();
        verify(mMockWarnings, never()).notifyHighTemperatureAlarm(overheat, shouldBeepSound);
    }

    @Test
    public void testConfig_alarms() {
        setOverThreshold();
        final Boolean overheat = true;
        final Boolean shouldBeepSound = false;
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureAlarm, 1);
        resources.addOverride(R.integer.config_alarmTemperature, 58);
        resources.addOverride(R.bool.config_alarmTemperatureBeepSound, shouldBeepSound);

        mPowerUI.start();
        verify(mMockWarnings).notifyHighTemperatureAlarm(overheat, shouldBeepSound);
    }

    @Test
    public void testHardPropsThrottlingThreshold_alarms() {
        setThrottlingThreshold(DEFAULT_OVERHEAT_ALARM_THRESHOLD);
        setOverThreshold();
        final Boolean overheat = true;
        final Boolean shouldBeepSound = false;
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureAlarm, 1);
        resources.addOverride(R.bool.config_alarmTemperatureBeepSound, shouldBeepSound);

        mPowerUI.start();
        verify(mMockWarnings).notifyHighTemperatureAlarm(overheat, shouldBeepSound);
    }

    @Test
    public void testHardPropsThrottlingThreshold_noAlarms() {
        setThrottlingThreshold(DEFAULT_OVERHEAT_ALARM_THRESHOLD);
        setUnderThreshold();
        final Boolean overheat = false;
        final Boolean shouldBeepSound = false;
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureAlarm, 1);
        resources.addOverride(R.bool.config_alarmTemperatureBeepSound, shouldBeepSound);

        mPowerUI.start();
        verify(mMockWarnings, never()).notifyHighTemperatureAlarm(overheat, shouldBeepSound);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showHybridOnly_overrideThresholdHigh_returnsNoShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold())
                .thenReturn(Duration.ofHours(1).toMillis());
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // unplugged device that would not show the non-hybrid notification but would show the
        // hybrid but the threshold has been overriden to be too low
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showHybridOnly_overrideThresholdHigh_returnsShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold())
                .thenReturn(Duration.ofHours(5).toMillis());
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // unplugged device that would not show the non-hybrid notification but would show the
        // hybrid since the threshold has been overriden to be much higher
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showHybridOnly_returnsShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // unplugged device that would not show the non-hybrid notification but would show the
        // hybrid
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showHybrid_showStandard_returnsShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.mBatteryLevel = 10;
        mPowerUI.start();

        // unplugged device that would show the non-hybrid notification and the hybrid
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showStandardOnly_returnsShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.mBatteryLevel = 10;
        mPowerUI.start();

        // unplugged device that would show the non-hybrid but not the hybrid
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_deviceHighBattery_returnsNoShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // unplugged device that would show the neither due to battery level being good
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_devicePlugged_returnsNoShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // plugged device that would show the neither due to being plugged
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(!UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
   }

    @Test
    public void testShouldShowLowBatteryWarning_deviceBatteryStatusUnknown_returnsNoShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // Unknown battery status device that would show the neither due to the battery status being
        // unknown
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        !POWER_SAVER_OFF, BatteryManager.BATTERY_STATUS_UNKNOWN);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_batterySaverEnabled_returnsNoShow() {
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        mPowerUI.start();

        // BatterySaverEnabled device that would show the neither due to battery saver
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        !POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_onlyShowsOncePerChargeCycle() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        when(mEnhancedEstimates.getEstimate())
                .thenReturn(new Estimate(BELOW_HYBRID_THRESHOLD, true));
        mPowerUI.mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;

        mPowerUI.maybeShowBatteryWarning(OLD_BATTERY_LEVEL_NINE, UNPLUGGED, UNPLUGGED,
                ABOVE_WARNING_BUCKET, ABOVE_WARNING_BUCKET);

        // reduce battery level to handle time based trigger -> level trigger interactions
        mPowerUI.mBatteryLevel = 10;
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissWhenPowerSaverEnabledLegacy() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(false);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // device that gets power saver turned on should dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, !POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldNotDismissLowBatteryWarning_dismissWhenPowerSaverEnabledHybrid() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // device that gets power saver turned on should dismiss
        boolean shouldDismiss =
            mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, !POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissWhenPlugged() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // device that gets plugged in should dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(!UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissHybridSignal_showStandardSignal_shouldShow() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // would dismiss hybrid but not non-hybrid should not dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_showHybridSignal_dismissStandardSignal_shouldShow() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // would dismiss non-hybrid but not hybrid should not dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_showBothSignal_shouldShow() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // should not dismiss when both would not dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissBothSignal_shouldDismiss() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        //should dismiss if both would dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissStandardSignal_hybridDisabled_shouldDismiss() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(false);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);

        // would dismiss non-hybrid with hybrid disabled should dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_powerSaverModeEnabled()
            throws InterruptedException {
        when(mPowerManager.isPowerSaveMode()).thenReturn(true);

        mPowerUI.start();
        mPowerUI.mReceiver.onReceive(mContext,
                new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));

        CountDownLatch latch = new CountDownLatch(1);
        ThreadUtils.postOnBackgroundThread(() -> latch.countDown());
        latch.await(5, TimeUnit.SECONDS);

        verify(mMockWarnings).dismissLowBatteryWarning();
    }

    @Test
    public void testShouldNotDismissLowBatteryWarning_powerSaverModeDisabled()
            throws InterruptedException {
        when(mPowerManager.isPowerSaveMode()).thenReturn(false);

        mPowerUI.start();
        mPowerUI.mReceiver.onReceive(mContext,
                new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));

        CountDownLatch latch = new CountDownLatch(1);
        ThreadUtils.postOnBackgroundThread(() -> latch.countDown());
        latch.await(5, TimeUnit.SECONDS);

        verify(mMockWarnings, never()).dismissLowBatteryWarning();
    }

    @Test
    public void testMaybeShowBatteryWarning_onlyQueriesEstimateOnBatteryLevelChangeOrNull() {
        mPowerUI.start();
        Estimate estimate = new Estimate(BELOW_HYBRID_THRESHOLD, true);
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        when(mEnhancedEstimates.getEstimate()).thenReturn(estimate);
        mPowerUI.mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;

        // we expect that the first time it will query even if the level is the same
        mPowerUI.mBatteryLevel = 9;
        mPowerUI.maybeShowBatteryWarning(OLD_BATTERY_LEVEL_NINE, UNPLUGGED, UNPLUGGED,
                ABOVE_WARNING_BUCKET, ABOVE_WARNING_BUCKET);
        verify(mEnhancedEstimates, times(1)).getEstimate();

        // We should NOT query again if the battery level hasn't changed
        mPowerUI.maybeShowBatteryWarning(OLD_BATTERY_LEVEL_NINE, UNPLUGGED, UNPLUGGED,
                ABOVE_WARNING_BUCKET, ABOVE_WARNING_BUCKET);
        verify(mEnhancedEstimates, times(1)).getEstimate();

        // Battery level has changed, so we should query again
        mPowerUI.maybeShowBatteryWarning(OLD_BATTERY_LEVEL_10, UNPLUGGED, UNPLUGGED,
                ABOVE_WARNING_BUCKET, ABOVE_WARNING_BUCKET);
        verify(mEnhancedEstimates, times(2)).getEstimate();
    }

    private void setCurrentTemp(float temp) {
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_CURRENT))
                .thenReturn(new float[] { temp, temp });
    }

    private void setThrottlingThreshold(float temp) {
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_THROTTLING))
                .thenReturn(new float[] { temp, temp });
    }

    private void setOverThreshold() {
        setCurrentTemp(50000);
    }

    private void setUnderThreshold() {
        setCurrentTemp(5);
    }

    private void createPowerUi() {
        mPowerUI = new PowerUI();
        mPowerUI.mContext = mContext;
        mPowerUI.mComponents = mContext.getComponents();
    }
}
