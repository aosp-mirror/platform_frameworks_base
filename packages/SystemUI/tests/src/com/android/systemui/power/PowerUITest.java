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

import static android.provider.Settings.Global.SHOW_TEMPERATURE_WARNING;
import static android.provider.Settings.Global.SHOW_USB_TEMPERATURE_ALARM;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.Temperature;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;

import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.power.PowerUI.WarningsUI;
import com.android.systemui.statusbar.phone.StatusBar;

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
    public static final long BELOW_SEVERE_HYBRID_THRESHOLD = TimeUnit.MINUTES.toMillis(30);
    public static final long ABOVE_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(4);
    private static final long ABOVE_CHARGE_CYCLE_THRESHOLD = Duration.ofHours(8).toMillis();
    private static final int OLD_BATTERY_LEVEL_NINE = 9;
    private static final int OLD_BATTERY_LEVEL_10 = 10;
    private static final long VERY_BELOW_SEVERE_HYBRID_THRESHOLD = TimeUnit.MINUTES.toMillis(15);
    private WarningsUI mMockWarnings;
    private PowerUI mPowerUI;
    private EnhancedEstimates mEnhancedEstimates;
    @Mock private PowerManager mPowerManager;
    @Mock private IThermalService mThermalServiceMock;
    private IThermalEventListener mThermalEventUsbListener;
    private IThermalEventListener mThermalEventSkinListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMockWarnings = mDependency.injectMockDependency(WarningsUI.class);
        mEnhancedEstimates = mDependency.injectMockDependency(EnhancedEstimates.class);

        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.addMockSystemService(Context.POWER_SERVICE, mPowerManager);

        createPowerUi();
        mThermalEventSkinListener = mPowerUI.new ThermalEventSkinListener();
        mThermalEventUsbListener = mPowerUI.new ThermalEventUsbListener();
    }

    @Test
    public void testSkinWarning_throttlingCritical() throws Exception {
        mPowerUI.start();

        final Temperature temp = getCriticalStatusTemp(Temperature.TYPE_SKIN, "skin1");
        mThermalEventSkinListener.notifyThrottling(temp);

        // dismiss skin high temperature warning when throttling status is critical
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
        verify(mMockWarnings, times(1)).dismissHighTemperatureWarning();
    }

    @Test
    public void testSkinWarning_throttlingEmergency() throws Exception {
        mPowerUI.start();

        final Temperature temp = getEmergencyStatusTemp(Temperature.TYPE_SKIN, "skin2");
        mThermalEventSkinListener.notifyThrottling(temp);

        // show skin high temperature warning when throttling status is emergency
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, times(1)).showHighTemperatureWarning();
        verify(mMockWarnings, never()).dismissHighTemperatureWarning();
    }

    @Test
    public void testUsbAlarm_throttlingCritical() throws Exception {
        mPowerUI.start();

        final Temperature temp = getCriticalStatusTemp(Temperature.TYPE_USB_PORT, "usb1");
        mThermalEventUsbListener.notifyThrottling(temp);

        // not show usb high temperature alarm when throttling status is critical
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, never()).showUsbHighTemperatureAlarm();
    }

    @Test
    public void testUsbAlarm_throttlingEmergency() throws Exception {
        mPowerUI.start();

        final Temperature temp = getEmergencyStatusTemp(Temperature.TYPE_USB_PORT, "usb2");
        mThermalEventUsbListener.notifyThrottling(temp);

        // show usb high temperature alarm when throttling status is emergency
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, times(1)).showUsbHighTemperatureAlarm();
    }

    @Test
    public void testSettingOverrideConfig_enableSkinTemperatureWarning() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);

        mPowerUI.start();
        mPowerUI.registerThermalEventListener();

        TestableLooper.get(this).processAllMessages();
        verify(mThermalServiceMock, times(1))
                .registerThermalEventListenerWithType(anyObject(), eq(Temperature.TYPE_SKIN));
    }

    @Test
    public void testSettingOverrideConfig_enableUsbTemperatureAlarm() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_USB_TEMPERATURE_ALARM, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showUsbPortAlarm, 0);

        mPowerUI.start();
        mPowerUI.registerThermalEventListener();

        TestableLooper.get(this).processAllMessages();
        verify(mThermalServiceMock, times(1))
                .registerThermalEventListenerWithType(anyObject(), eq(Temperature.TYPE_USB_PORT));
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
    public void testSevereWarning_countsAsLowAndSevere_WarningOnlyShownOnce() {
        mPowerUI.start();
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        when(mEnhancedEstimates.getEstimate())
                .thenReturn(new Estimate(BELOW_SEVERE_HYBRID_THRESHOLD, true));
        mPowerUI.mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;

        // reduce battery level to handle time based trigger -> level trigger interactions
        mPowerUI.mBatteryLevel = 5;
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, BELOW_SEVERE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);

        // actually run the end to end since it handles changing the internal state.
        mPowerUI.maybeShowBatteryWarning(OLD_BATTERY_LEVEL_10, UNPLUGGED, UNPLUGGED,
                ABOVE_WARNING_BUCKET, ABOVE_WARNING_BUCKET);

        shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, VERY_BELOW_SEVERE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
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

    private Temperature getEmergencyStatusTemp(int type, String name) {
        final float value = 65;
        return new Temperature(value, type, name, Temperature.THROTTLING_EMERGENCY);
    }

    private Temperature getCriticalStatusTemp(int type, String name) {
        final float value = 60;
        return new Temperature(value, type, name, Temperature.THROTTLING_CRITICAL);
    }

    private void createPowerUi() {
        mPowerUI = new PowerUI();
        mPowerUI.mContext = mContext;
        mPowerUI.mComponents = mContext.getComponents();
        mPowerUI.mThermalService = mThermalServiceMock;
    }
}
