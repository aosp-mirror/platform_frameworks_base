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

package com.android.server.emergency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Unit test for EmergencyAffordanceService (EAS for short) which determines when
 * should we enable Emergency Affordance feature (EA for short).
 *
 * Please refer to https://source.android.com/devices/tech/connect/emergency-affordance
 * to see the details of the feature.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class EmergencyAffordanceServiceTest {

    // Default country ISO that should enable EA. Value comes from resource
    // com.android.internal.R.array.config_emergency_iso_country_codes
    private static final String EMERGENCY_ISO_CODE = "in";
    // Randomly picked country ISO that should not enable EA.
    private static final String NON_EMERGENCY_ISO_CODE = "us";

    // Valid values for Settings.Global.EMERGENCY_AFFORDANCE_NEEDED
    private static final int OFF = 0; // which means feature disabled
    private static final int ON  = 1; // which means feature enabled

    private static final int ACTIVE_MODEM_COUNT = 2;

    @Mock private Resources mResources;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private TelephonyManager mTelephonyManager;

    private TestContext mServiceContext;
    private MockContentResolver mContentResolver;
    private OnSubscriptionsChangedListener mSubscriptionChangedListener;
    private EmergencyAffordanceService mService;

    // Testable Context that mocks resources, content resolver and system services
    private class TestContext extends BroadcastInterceptingContext {
        TestContext(Context base) {
            super(base);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.TELEPHONY_SUBSCRIPTION_SERVICE:
                    return mSubscriptionManager;
                case Context.TELEPHONY_SERVICE:
                    return mTelephonyManager;
                default:
                    return super.getSystemService(name);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doReturn(new String[] { EMERGENCY_ISO_CODE }).when(mResources)
                .getStringArray(com.android.internal.R.array.config_emergency_iso_country_codes);

        final Context context = InstrumentationRegistry.getContext();
        mServiceContext = new TestContext(context);
        mContentResolver = new MockContentResolver(mServiceContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());

        // Initialize feature off, to have constant starting
        Settings.Global.putInt(mContentResolver, Settings.Global.EMERGENCY_AFFORDANCE_NEEDED, 0);
        mService = new EmergencyAffordanceService(mServiceContext);
    }

    /**
     * Verify if the device is not voice capable, the feature should be disabled.
     */
    @Test
    public void testSettings_shouldBeOff_whenVoiceCapableIsFalse() throws Exception {
        // Given: the device is not voice capable
        // When:  setup device and boot service
        setUpDevice(false /* withVoiceCapable */, true /* withEmergencyIsoInSim */,
                true /* withEmergencyIsoInCell */);

        // Then: EA setting will should be 0
        verifyEmergencyAffordanceNeededSettings(OFF);
    }

    /**
     * Verify the voice capable device is booted up without EA-enabled cell network, with
     * no EA-enabled SIM installed, feature should be disabled.
     */
    @Test
    public void testSettings_shouldBeOff_whenWithoutEAEanbledNetworkNorSim() throws Exception {
        // Given: the device is voice capble, no EA-enable SIM, no EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false /* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // Then: EA setting will should be 0
        verifyEmergencyAffordanceNeededSettings(OFF);
    }

    /**
     * Verify the voice capable device is booted up with EA-enabled SIM installed, the
     * feature should be enabled.
     */
    @Test
    public void testSettings_shouldBeOn_whenBootUpWithEAEanbledSim() throws Exception {
        // Given: the device is voice capble, with EA-enable SIM, no EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, true /* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // Then: EA setting will immediately update to 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    /**
     * Verify the voice capable device is booted up with EA-enabled Cell network, the
     * feature should be enabled.
     */
    @Test
    public void testSettings_shouldBeOn_whenBootUpWithEAEanbledCell() throws Exception {
        // Given: the device is voice capble, with EA-enable SIM, with EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false /* withEmergencyIsoInSim */,
                true /* withEmergencyIsoInCell */);

        // Then: EA setting will immediately update to 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    /**
     * Verify when device boot up with no EA-enabled SIM, but later install one,
     * feature should be enabled.
     */
    @Test
    public void testSettings_shouldBeOn_whenSubscriptionInfoChangedWithEmergencyIso()
            throws Exception {
        // Given: the device is voice capable, boot up with no EA-enabled SIM, no EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false/* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // When: Insert EA-enabled SIM and get notified
        setUpSim(true /* withEmergencyIsoInSim */);
        mSubscriptionChangedListener.onSubscriptionsChanged();

        // Then: EA Setting will update to 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    /**
     * Verify when feature was on, device re-insert with no EA-enabled SIMs,
     * feature should be disabled.
     */
    @Test
    public void testSettings_shouldBeOff_whenSubscriptionInfoChangedWithoutEmergencyIso()
            throws Exception {
        // Given: the device is voice capable, no EA-enabled Cell, with EA-enabled SIM
        setUpDevice(true /* withVoiceCapable */, true /* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // When: All SIMs are replaced with EA-disabled ones.
        setUpSim(false /* withEmergencyIsoInSim */);
        mSubscriptionChangedListener.onSubscriptionsChanged();

        // Then: EA Setting will update to 0
        verifyEmergencyAffordanceNeededSettings(OFF);
    }

    /**
     * Verify when device boot up with no EA-enabled Cell, but later move into one,
     * feature should be enabled.
     */
    @Test
    public void testSettings_shouldBeOn_whenCountryIsoChangedWithEmergencyIso()
            throws Exception {
        // Given: the device is voice capable, boot up with no EA-enabled SIM, no EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false/* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // When: device locale change to EA-enabled Cell and get notified
        resetCell(true /* withEmergencyIsoInSim */);
        sendBroadcastNetworkCountryChanged(EMERGENCY_COUNTRY_ISO);

        // Then: EA Setting will update to 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    /**
     * Verify when device boot up with  EA-enabled Cell, but later move out of it,
     * feature should be enabled.
     */
    @Test
    public void testSettings_shouldBeOff_whenCountryIsoChangedWithoutEmergencyIso()
            throws Exception {
        // Given: the device is voice capable, boot up with no EA-enabled SIM, with EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false/* withEmergencyIsoInSim */,
                true /* withEmergencyIsoInCell */);

        // When: device locale change to no EA-enabled Cell and get notified
        resetCell(false /* withEmergencyIsoInSim */);
        sendBroadcastNetworkCountryChanged(NON_EMERGENCY_COUNTRY_ISO);

        // Then: EA Setting will update to 0
        verifyEmergencyAffordanceNeededSettings(OFF);
    }
    /**
     * Verify if device is not in EA-enabled Mobile Network without EA-enable SIM(s) installed,
     * when receive SubscriptionInfo change, the feature should not be enabled.
     */
    @Test
    public void testSettings_shouldBeOff_whenNoEmergencyIsoInCellNorSim() throws Exception {
        // Given: the device is voice capable, no EA-enabled Cell, no EA-enabled SIM
        setUpDevice(true /* withVoiceCapable */, false /* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // When: Subscription changed event received
        mSubscriptionChangedListener.onSubscriptionsChanged();

        // Then: EA Settings should be 0
        verifyEmergencyAffordanceNeededSettings(OFF);
    }

    /**
     * Verify while feature was on, when device receive empty country iso change, while APM is
     * enabled, feature status should keep on.
     */
    @Test
    public void testSettings_shouldOn_whenReceiveEmptyISOWithAPMEnabled() throws Exception {
        // Given: the device is voice capable,  no EA-enabled SIM, with EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false /* withEmergencyIsoInSim */,
                true /* withEmergencyIsoInCell */);

        // Then: EA Settings will update to 1
        verifyEmergencyAffordanceNeededSettings(ON);

        // When: Airplane mode is enabled, and we receive EMPTY ISO in locale change
        setAirplaneMode(true);
        sendBroadcastNetworkCountryChanged(EMPTY_COUNTRY_ISO);

        // Then: EA Settings will keep to 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    /**
     * Verify while feature was on, when device receive empty country iso change, while APM is
     * disabled, feature should be disabled.
     */
    @Test
    public void testSettings_shouldOff_whenReceiveEmptyISOWithAPMDisabled() throws Exception {
        // Given: the device is voice capable,  no EA-enabled SIM, with EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false /* withEmergencyIsoInSim */,
                true /* withEmergencyIsoInCell */);

        // Then: EA Settings will update to 1
        verifyEmergencyAffordanceNeededSettings(ON);

        // When: Airplane mode is disabled, and we receive valid empty ISO in locale change
        setUpCell(false /* withEmergencyIsoInCell */);
        setAirplaneMode(false);
        sendBroadcastNetworkCountryChanged(EMPTY_COUNTRY_ISO);

        // Then: EA Settings will keep to 0
        verifyEmergencyAffordanceNeededSettings(OFF);
    }

    /**
     * Verify when airplane mode is turn on and off in cell network with EA-enabled ISO,
     * feature should keep enabled.
     */
    @Test
    public void testSettings_shouldBeOn_whenAirplaneModeOnOffWithEmergencyIsoInCell()
            throws Exception {
        // Given: the device is voice capable,  no EA-enabled SIM, with EA-enabled Cell
        setUpDevice(true /* withVoiceCapable */, false /* withEmergencyIsoInSim */,
                true /* withEmergencyIsoInCell */);

        // When: Device receive locale change with EA-enabled iso
        sendBroadcastNetworkCountryChanged(EMERGENCY_COUNTRY_ISO);

        // When: Airplane mode is disabled
        setAirplaneMode(false);

        // Then: EA Settings will keep with 1
        verifyEmergencyAffordanceNeededSettings(ON);

        // When: Airplane mode is enabled
        setAirplaneMode(true);

        // Then: EA Settings is still 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    /**
     * Verify when airplane mode is turn on and off with EA-enabled ISO in SIM,
     * feature should keep enabled.
     */
    @Test
    public void testSettings_shouldBeOn_whenAirplaneModeOnOffWithEmergencyIsoInSim()
            throws Exception {
        // Given: the device is voice capable, no EA-enabled Cell Network, with EA-enabled SIM
        setUpDevice(true /* withVoiceCapable */, true /* withEmergencyIsoInSim */,
                false /* withEmergencyIsoInCell */);

        // When: Airplane mode is disabled
        setAirplaneMode(false);

        // Then: EA Settings will keep with 1
        verifyEmergencyAffordanceNeededSettings(ON);

        // When: Airplane mode is enabled
        setAirplaneMode(true);

        // Then: EA Settings is still 1
        verifyEmergencyAffordanceNeededSettings(ON);
    }

    // EAS reads voice capable during boot up and cache it. To test non voice capable device,
    // we can not put this in setUp
    private void setUpDevice(boolean withVoiceCapable, boolean withEmergencyIsoInSim,
            boolean withEmergencyIsoInCell) throws Exception {
        setUpVoiceCapable(withVoiceCapable);

        setUpSim(withEmergencyIsoInSim);

        setUpCell(withEmergencyIsoInCell);

        // bypass onStart which is used to publish binder service and need sepolicy policy update
        // mService.onStart();

        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        if (!withVoiceCapable) {
            return;
        }

        captureSubscriptionChangeListener();
    }

    private void setUpVoiceCapable(boolean voiceCapable) {
        doReturn(voiceCapable).when(mTelephonyManager).isVoiceCapable();
    }

    private static final Supplier<String> EMPTY_COUNTRY_ISO = () -> "";
    private static final Supplier<String> EMERGENCY_COUNTRY_ISO = () -> EMERGENCY_ISO_CODE;
    private static final Supplier<String> NON_EMERGENCY_COUNTRY_ISO = () -> NON_EMERGENCY_ISO_CODE;
    private void sendBroadcastNetworkCountryChanged(Supplier<String> countryIso) {
        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, countryIso.get());
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, 0);
        mServiceContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void setUpSim(boolean withEmergencyIsoInSim) {
        List<SubscriptionInfo> subInfos = getSubscriptionInfoList(withEmergencyIsoInSim);
        doReturn(subInfos).when(mSubscriptionManager).getActiveSubscriptionInfoList();
    }

    private void setUpCell(boolean withEmergencyIsoInCell) {
        doReturn(ACTIVE_MODEM_COUNT).when(mTelephonyManager).getActiveModemCount();
        doReturn(NON_EMERGENCY_ISO_CODE).when(mTelephonyManager).getNetworkCountryIso(0);
        doReturn(withEmergencyIsoInCell ? EMERGENCY_ISO_CODE : NON_EMERGENCY_ISO_CODE)
                .when(mTelephonyManager).getNetworkCountryIso(1);
    }

    private void resetCell(boolean withEmergencyIsoInCell) {
        doReturn(withEmergencyIsoInCell ? EMERGENCY_ISO_CODE : NON_EMERGENCY_ISO_CODE)
                .when(mTelephonyManager).getNetworkCountryIso(1);
    }

    private void captureSubscriptionChangeListener() {
        final ArgumentCaptor<OnSubscriptionsChangedListener> subChangedListenerCaptor =
                ArgumentCaptor.forClass(OnSubscriptionsChangedListener.class);
        verify(mSubscriptionManager).addOnSubscriptionsChangedListener(
                subChangedListenerCaptor.capture());
        mSubscriptionChangedListener = subChangedListenerCaptor.getValue();
    }

    private void setAirplaneMode(boolean enabled) {
        // Change the system settings
        Settings.Global.putInt(mContentResolver, Settings.Global.AIRPLANE_MODE_ON,
                enabled ? 1 : 0);

        // Post the intent
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mServiceContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private List<SubscriptionInfo> getSubscriptionInfoList(boolean withEmergencyIso) {
        List<SubscriptionInfo> subInfos = new ArrayList<>(2);

        // Test with Multiple SIMs. SIM1 is a non-EA SIM
        // Only country iso is valuable, all other info are filled with dummy values
        SubscriptionInfo subInfo = new SubscriptionInfo(1, "890126042XXXXXXXXXXX", 0, "T-mobile",
                "T-mobile", 0, 255, "12345", 0, null,
                "310", "226", NON_EMERGENCY_ISO_CODE, false, null, null);
        subInfos.add(subInfo);

        // SIM2 can configured to be non-EA or EA SIM according parameter withEmergencyIso
        SubscriptionInfo subInfo2 = new SubscriptionInfo(1, "890126042XXXXXXXXXXX", 0, "Airtel",
                "Aritel", 0, 255, "12345", 0, null, "310", "226",
                withEmergencyIso ? EMERGENCY_ISO_CODE : NON_EMERGENCY_ISO_CODE,
                false, null, null);
        subInfos.add(subInfo2);

        return subInfos;
    }

    // EAS has handler thread to perform heavy work, while FakeSettingProvider does not support
    // ContentObserver. To make sure consistent result, we use a simple sleep & retry to wait for
    // real work finished before verify result.
    private static final int TIME_DELAY_BEFORE_VERIFY_IN_MS = 50;
    private static final int RETRIES_BEFORE_VERIFY = 20;
    private void verifyEmergencyAffordanceNeededSettings(int expected) throws Exception {
        try {
            int ct = 0;
            int actual = -1;
            while (ct++ < RETRIES_BEFORE_VERIFY
                    && (actual = Settings.Global.getInt(mContentResolver,
                    Settings.Global.EMERGENCY_AFFORDANCE_NEEDED)) != expected) {
                Thread.sleep(TIME_DELAY_BEFORE_VERIFY_IN_MS);
            }
            assertEquals(expected, actual);
        } catch (Settings.SettingNotFoundException e) {
            fail("SettingNotFoundException thrown for Settings.Global.EMERGENCY_AFFORDANCE_NEEDED");
        }
    }
}
