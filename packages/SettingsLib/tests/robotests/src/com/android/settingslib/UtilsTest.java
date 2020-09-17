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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.text.TextUtils;

import org.junit.Before;
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

import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {UtilsTest.ShadowSecure.class, UtilsTest.ShadowLocationManager.class})
public class UtilsTest {
    private static final double[] TEST_PERCENTAGES = {0, 0.4, 0.5, 0.6, 49, 49.3, 49.8, 50, 100};
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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        when(mContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mLocationManager);
        ShadowSecure.reset();
        mAudioManager = mContext.getSystemService(AudioManager.class);
    }

    @Test
    public void testUpdateLocationEnabled() {
        int currentUserId = ActivityManager.getCurrentUser();
        Utils.updateLocationEnabled(mContext, true, currentUserId,
                Settings.Secure.LOCATION_CHANGER_QUICK_SETTINGS);

        assertThat(Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCATION_CHANGER, Settings.Secure.LOCATION_CHANGER_UNKNOWN))
                .isEqualTo(Settings.Secure.LOCATION_CHANGER_QUICK_SETTINGS);
    }

    @Test
    public void testFormatPercentage_RoundTrue_RoundUpIfPossible() {
        final String[] expectedPercentages = {PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_1,
                PERCENTAGE_1, PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_50, PERCENTAGE_50,
                PERCENTAGE_100};

        for (int i = 0, size = TEST_PERCENTAGES.length; i < size; i++) {
            final String percentage = Utils.formatPercentage(TEST_PERCENTAGES[i], true);
            assertThat(percentage).isEqualTo(expectedPercentages[i]);
        }
    }

    @Test
    public void testFormatPercentage_RoundFalse_NoRound() {
        final String[] expectedPercentages = {PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_0,
                PERCENTAGE_0, PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_50,
                PERCENTAGE_100};

        for (int i = 0, size = TEST_PERCENTAGES.length; i < size; i++) {
            final String percentage = Utils.formatPercentage(TEST_PERCENTAGES[i], false);
            assertThat(percentage).isEqualTo(expectedPercentages[i]);
        }
    }

    @Test
    public void testGetDefaultStorageManagerDaysToRetain_storageManagerDaysToRetainUsesResources() {
        Resources resources = mock(Resources.class);
        when(resources.getInteger(
                eq(
                        com.android
                                .internal
                                .R
                                .integer
                                .config_storageManagerDaystoRetainDefault)))
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

    @Implements(value = Settings.Secure.class)
    public static class ShadowSecure extends ShadowSettings.ShadowSecure {
        private static Map<String, Integer> map = new HashMap<>();

        @Implementation
        public static boolean putIntForUser(ContentResolver cr, String name, int value,
                int userHandle) {
            map.put(name, value);
            return true;
        }

        @Implementation
        public static int getIntForUser(ContentResolver cr, String name, int def, int userHandle) {
            if (map.containsKey(name)) {
                return map.get(name);
            } else {
                return def;
            }
        }

        public static void reset() {
            map.clear();
        }
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
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_IN_SERVICE);

        assertThat(Utils.isInService(mServiceState)).isTrue();
    }

    @Test
    public void isInService_voiceOutOfServiceDataInService_returnTrue() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.getRegistrationState()).thenReturn(
                NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN);

        assertThat(Utils.isInService(mServiceState)).isTrue();
    }

    @Test
    public void isInService_voiceOutOfServiceDataInServiceOnIwLan_returnFalse() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.getRegistrationState()).thenReturn(
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        when(mServiceState.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void isInService_voiceOutOfServiceDataOutOfService_returnFalse() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(
                ServiceState.STATE_OUT_OF_SERVICE);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void isInService_ServiceStatePowerOff_returnFalse() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_POWER_OFF);

        assertThat(Utils.isInService(mServiceState)).isFalse();
    }

    @Test
    public void getCombinedServiceState_servicestateNull_returnOutOfService() {
        assertThat(Utils.getCombinedServiceState(null)).isEqualTo(
                ServiceState.STATE_OUT_OF_SERVICE);
    }

    @Test
    public void getCombinedServiceState_ServiceStatePowerOff_returnPowerOff() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_POWER_OFF);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_POWER_OFF);
    }

    @Test
    public void getCombinedServiceState_voiceInService_returnInService() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_IN_SERVICE);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_IN_SERVICE);
    }

    @Test
    public void getCombinedServiceState_voiceOutOfServiceDataInService_returnInService() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.getRegistrationState()).thenReturn(
                NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_IN_SERVICE);
    }

    @Test
    public void getCombinedServiceState_voiceOutOfServiceDataInServiceOnIwLan_returnOutOfService() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN)).thenReturn(mNetworkRegistrationInfo);
        when(mNetworkRegistrationInfo.getRegistrationState()).thenReturn(
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_OUT_OF_SERVICE);
    }

    @Test
    public void getCombinedServiceState_voiceOutOfServiceDataOutOfService_returnOutOfService() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getDataRegistrationState()).thenReturn(
                ServiceState.STATE_OUT_OF_SERVICE);

        assertThat(Utils.getCombinedServiceState(mServiceState)).isEqualTo(
                ServiceState.STATE_OUT_OF_SERVICE);
    }

    @Test
    public void getBatteryStatus_statusIsFull_returnFullString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 100);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent)).isEqualTo(
                resources.getString(R.string.battery_info_status_full));
    }

    @Test
    public void getBatteryStatus_batteryLevelIs100_returnFullString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_FULL);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent)).isEqualTo(
                resources.getString(R.string.battery_info_status_full));
    }

    @Test
    public void getBatteryStatus_batteryLevel99_returnChargingString() {
        final Intent intent = new Intent().putExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING);
        final Resources resources = mContext.getResources();

        assertThat(Utils.getBatteryStatus(mContext, intent)).isEqualTo(
                resources.getString(R.string.battery_info_status_charging));
    }
}
