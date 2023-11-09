/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib;

import static com.android.settingslib.Utils.STORAGE_MANAGER_ENABLED_PROPERTY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.usb.flags.Flags;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.text.TextUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {UtilsTest.ShadowLocationManager.class})
public class UtilsTest {
    private static final double[] TEST_PERCENTAGES = {0, 0.4, 0.5, 0.6, 49, 49.3, 49.8, 50, 100};
    private static final String TAG = "UtilsTest";
    private static final String PERCENTAGE_0 = "0%";
    private static final String PERCENTAGE_1 = "1%";
    private static final String PERCENTAGE_49 = "49%";
    private static final String PERCENTAGE_50 = "50%";
    private static final String PERCENTAGE_100 = "100%";

    private AudioManager mAudioManager;
    private Context mContext;
    @Mock
    private LocationManager mLocationManager;
    @Mock
    private ServiceState mServiceState;
    @Mock
    private NetworkRegistrationInfo mNetworkRegistrationInfo;
    @Mock
    private UsbPort mUsbPort;
    @Mock
    private UsbManager mUsbManager;
    @Mock
    private UsbPortStatus mUsbPortStatus;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        when(mContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mLocationManager);
        when(mContext.getSystemService(UsbManager.class)).thenReturn(mUsbManager);
        ShadowSettings.ShadowSecure.reset();
        mAudioManager = mContext.getSystemService(AudioManager.class);
    }

    @After
    public void reset() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Utils.INCOMPATIBLE_CHARGER_WARNING_DISABLED, 0);
    }

    @Test
    public void testUpdateLocationEnabled() {
        int currentUserId = ActivityManager.getCurrentUser();
        Utils.updateLocationEnabled(mContext, true, currentUserId,
                Settings.Secure.LOCATION_CHANGER_QUICK_SETTINGS);

        assertThat(Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCATION_CHANGER,
                Settings.Secure.LOCATION_CHANGER_UNKNOWN)).isEqualTo(
                Settings.Secure.LOCATION_CHANGER_QUICK_SETTINGS);
    }

    @Test
    public void testFormatPercentage_RoundTrue_RoundUpIfPossible() {
        final String[] expectedPercentages =
                {PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_1, PERCENTAGE_1, PERCENTAGE_49,
                        PERCENTAGE_49, PERCENTAGE_50, PERCENTAGE_50, PERCENTAGE_100};

        for (int i = 0, size = TEST_PERCENTAGES.length; i < size; i++) {
            final String percentage = Utils.formatPercentage(TEST_PERCENTAGES[i], true);
            assertThat(percentage).isEqualTo(expectedPercentages[i]);
        }
    }

    @Test
    public void testFormatPercentage_RoundFalse_NoRound() {
        final String[] expectedPercentages =
                {PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_49,
                        PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_50, PERCENTAGE_100};

        for (int i = 0, size = TEST_PERCENTAGES.length; i < size; i++) {
            final String percentage = Utils.formatPercentage(TEST_PERCENTAGES[i], false);
            assertThat(percentage).isEqualTo(expectedPercentages[i]);
        }
    }

    @Test
    public void testGetDefaultStorageManagerDaysToRetain_storageManagerDaysToRetainUsesResources() {
        Resources resources = mock(Resources.class);
        when(resources.getInteger(
                eq(com.android.internal.R.integer.config_storageManagerDaystoRetainDefault)))
                .thenReturn(60);
        assertThat(Utils.getDefaultStorageManagerDaysToRetain(resources)).isEqualTo(60);
    }

    @Test
    public void testIsStorageManagerEnabled_UsesSystemProperties() {
        SystemProperties.set(STORAGE_MANAGER_ENABLED_PROPERTY, "true");
        assertThat(Utils.isStorageManagerEnabled(mContext)).isTrue();
    }

    private static ArgumentMatcher<Intent> actionMatches(String expected) {
        return intent -> TextUtils.equals(expected, intent.getAction());
    }

    @Implements(value = LocationManager.class)
    public static class ShadowLocationManager {

        @Implementation
        public void setLocationEnabledForUser(boolean enabled, UserHandle userHandle) {
            // Do nothing
        }
    }

    @Test
    public void isAudioModeOngoingCall_modeInCommunication_returnTrue() {
        mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        assertThat(Utils.isAudioModeOngoingCall(mContext)).isTrue();
    }

    @Test
    public void isAudioModeOngoingCall_modeInCall_returnTrue() {
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        assertThat(Utils.isAudioModeOngoingCall(mContext)).isTrue();
    }

    @Test
    public void isAudioModeOngoingCall_modeRingtone_returnTrue() {
        mAudioManager.setMode(AudioManager.MODE_RINGTONE);

        assertThat(Utils.isAudioModeOngoingCall(mContext)).isTrue();
    }

    @Test
    public void isAudioModeOngoingCall_modeNormal_returnFalse() {
        mAudioManager.setMode(AudioManager.MODE_NORMAL);

        assertThat(Utils.isAudioModeOngoingCall(mContext)).isFalse();
    }

    @Test
    public void isInService_servicestateNull_returnFalse() {
        assertThat(Utils.isInService(null)).isFalse();
    }

    @Test
    public void isInService_voiceInService_returnTrue() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_IN_SERVICE);

        assertThat(Utils.isInService(mServiceState)).isTrue();
    }

    @Test
    public void isInService_voiceOutOfServiceDataInService_returnTrue() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.isInService()).thenReturn(true);

        assertThat(Utils.isInService(mServiceState)).isTrue();
    }

    @Test
    public void isInService_voiceOutOfServiceDataInServiceOnIwLan_returnFalse() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN)).thenReturn(mNetworkRegistrationInfo);
        when(mServiceState.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        when(mNetworkRegistrationInfo.isInService()).thenReturn(true);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void isInService_voiceOutOfServiceDataNull_returnFalse() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN)).thenReturn(null);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void isInService_voiceOutOfServiceDataOutOfService_returnFalse() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.isInService()).thenReturn(false);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void isInService_ServiceStatePowerOff_returnFalse() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_POWER_OFF);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void getCombinedServiceState_servicestateNull_returnOutOfService() {
        assertThat(Utils.getCombinedServiceState(null)).isEqualTo(
                ServiceState.STATE_OUT_OF_SERVICE);
    }

    @Test
    public void getCombinedServiceState_ServiceStatePowerOff_returnPowerOff() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_POWER_OFF);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_POWER_OFF);
    }

    @Test
    public void getCombinedServiceState_voiceInService_returnInService() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_IN_SERVICE);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_IN_SERVICE);
    }

    @Test
    public void getCombinedServiceState_voiceOutOfServiceDataInService_returnInService() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.isInService()).thenReturn(true);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_IN_SERVICE);
    }

    @Test
    public void getCombinedServiceState_voiceOutOfServiceDataInServiceOnIwLan_returnOutOfService() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.isInService()).thenReturn(true);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_OUT_OF_SERVICE);
    }

    @Test
    public void getCombinedServiceState_voiceOutOfServiceDataOutOfService_returnOutOfService() {
        when(mServiceState.getVoiceRegState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(
                ServiceState.STATE_OUT_OF_SERVICE);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_OUT_OF_SERVICE);
    }

    @Test
    public void getBatteryStatus_statusIsFull_returnFullString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 100).putExtra(
                BatteryManager.EXTRA_SCALE, 100);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ false)).isEqualTo(
                resources.getString(R.string.battery_info_status_full));
    }

    @Test
    public void getBatteryStatus_statusIsFullAndUseCompactStatus_returnFullyChargedString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 100).putExtra(
                BatteryManager.EXTRA_SCALE, 100);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ true)).isEqualTo(
                resources.getString(R.string.battery_info_status_full_charged));
    }

    @Test
    public void getBatteryStatus_batteryLevelIs100_returnFullString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_FULL);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ false)).isEqualTo(
                resources.getString(R.string.battery_info_status_full));
    }

    @Test
    public void getBatteryStatus_batteryLevelIs100AndUseCompactStatus_returnFullyString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_FULL);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ true)).isEqualTo(
                resources.getString(R.string.battery_info_status_full_charged));
    }

    @Test
    public void getBatteryStatus_batteryLevel99_returnChargingString() {
        final Intent intent = new Intent();
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ false)).isEqualTo(
                resources.getString(R.string.battery_info_status_charging));
    }

    @Test
    public void getBatteryStatus_chargingDock_returnDockChargingString() {
        final Intent intent = new Intent();
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_DOCK);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ false)).isEqualTo(
                resources.getString(R.string.battery_info_status_charging_dock));
    }

    @Test
    public void getBatteryStatus_chargingWireless_returnWirelessChargingString() {
        final Intent intent = new Intent();
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ false)).isEqualTo(
                resources.getString(R.string.battery_info_status_charging_wireless));
    }

    @Test
    public void getBatteryStatus_chargingAndUseCompactStatus_returnCompactString() {
        final Intent intent = new Intent();
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ true)).isEqualTo(
                resources.getString(R.string.battery_info_status_charging));
    }

    @Test
    public void getBatteryStatus_chargingWirelessAndUseCompactStatus_returnCompactString() {
        final Intent intent = new Intent();
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent, /* compactStatus= */ true)).isEqualTo(
                resources.getString(R.string.battery_info_status_charging));
    }

    @Test
    public void containsIncompatibleChargers_nullPorts_returnFalse() {
        when(mUsbManager.getPorts()).thenReturn(null);
        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_emptyPorts_returnFalse() {
        when(mUsbManager.getPorts()).thenReturn(new ArrayList<>());
        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_nullPortStatus_returnFalse() {
        final List<UsbPort> usbPorts = new ArrayList<>();
        usbPorts.add(mUsbPort);
        when(mUsbManager.getPorts()).thenReturn(usbPorts);
        when(mUsbPort.getStatus()).thenReturn(null);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_complianeWarningOther_returnTrue_flagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING);
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_INPUT_POWER_LIMITED_WARNING);
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_OTHER);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isTrue();
    }

    @Test
    public void containsIncompatibleChargers_complianeWarningPower_returnFalse_flagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING);
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_INPUT_POWER_LIMITED_WARNING);
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_INPUT_POWER_LIMITED);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_complianeWarningOther_returnFalse_flagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_INPUT_POWER_LIMITED_WARNING);
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_OTHER);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_complianeWarningPower_returnTrue_flagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_INPUT_POWER_LIMITED_WARNING);
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_INPUT_POWER_LIMITED);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isTrue();
    }

    @Test
    public void containsIncompatibleChargers_complianeWarningDebug_returnTrue() {
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY);
        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isTrue();
    }

    @Test
    public void containsIncompatibleChargers_unexpectedWarningType_returnFalse() {
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_BC_1_2);
        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_emptyComplianceWarnings_returnFalse() {
        setupIncompatibleCharging();
        when(mUsbPortStatus.getComplianceWarnings()).thenReturn(new int[1]);
        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_notSupportComplianceWarnings_returnFalse() {
        setupIncompatibleCharging();
        when(mUsbPort.supportsComplianceWarnings()).thenReturn(false);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_usbNotConnected_returnFalse() {
        setupIncompatibleCharging();
        when(mUsbPortStatus.isConnected()).thenReturn(false);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    @Test
    public void containsIncompatibleChargers_disableWarning_returnFalse() {
        setupIncompatibleCharging();
        Settings.Secure.putInt(mContext.getContentResolver(),
                Utils.INCOMPATIBLE_CHARGER_WARNING_DISABLED, 1);

        assertThat(Utils.containsIncompatibleChargers(mContext, TAG)).isFalse();
    }

    private void setupIncompatibleCharging() {
        setupIncompatibleCharging(UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY);
    }

    private void setupIncompatibleCharging(int complianceWarningType) {
        final List<UsbPort> usbPorts = new ArrayList<>();
        usbPorts.add(mUsbPort);
        when(mUsbManager.getPorts()).thenReturn(usbPorts);
        when(mUsbPort.getStatus()).thenReturn(mUsbPortStatus);
        when(mUsbPort.supportsComplianceWarnings()).thenReturn(true);
        when(mUsbPortStatus.isConnected()).thenReturn(true);
        when(mUsbPortStatus.getComplianceWarnings()).thenReturn(new int[]{complianceWarningType});
    }
}
