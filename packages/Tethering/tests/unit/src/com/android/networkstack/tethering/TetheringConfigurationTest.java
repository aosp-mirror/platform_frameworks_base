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

package com.android.networkstack.tethering;

import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.provider.DeviceConfig;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Iterator;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringConfigurationTest {
    private final SharedLog mLog = new SharedLog("TetheringConfigurationTest");

    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final String PROVISIONING_NO_UI_APP_NAME = "no_ui_app";
    private static final String PROVISIONING_APP_RESPONSE = "app_response";
    @Mock private Context mContext;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private Resources mResources;
    @Mock private Resources mResourcesForSubId;
    private Context mMockContext;
    private boolean mHasTelephonyManager;
    private boolean mEnableLegacyDhcpServer;
    private MockitoSession mMockingSession;

    private class MockTetheringConfiguration extends TetheringConfiguration {
        MockTetheringConfiguration(Context ctx, SharedLog log, int id) {
            super(ctx, log, id);
        }

        @Override
        protected Resources getResourcesForSubIdWrapper(Context ctx, int subId) {
            return mResourcesForSubId;
        }
    }

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.TELEPHONY_SERVICE.equals(name)) {
                return mHasTelephonyManager ? mTelephonyManager : null;
            }
            return super.getSystemService(name);
        }
    }

    @Before
    public void setUp() throws Exception {
        // TODO: use a dependencies class instead of mock statics.
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        doReturn(null).when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY),
                eq(TetheringConfiguration.TETHER_ENABLE_LEGACY_DHCP_SERVER)));

        when(mResources.getStringArray(R.array.config_tether_dhcp_range)).thenReturn(
                new String[0]);
        when(mResources.getInteger(R.integer.config_tether_offload_poll_interval)).thenReturn(
                TetheringConfiguration.DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);
        when(mResources.getStringArray(R.array.config_tether_usb_regexs)).thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_wifi_regexs))
                .thenReturn(new String[]{ "test_wlan\\d" });
        when(mResources.getStringArray(R.array.config_tether_bluetooth_regexs)).thenReturn(
                new String[0]);
        when(mResources.getIntArray(R.array.config_tether_upstream_types)).thenReturn(new int[0]);
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(new String[0]);
        when(mResources.getBoolean(R.bool.config_tether_enable_legacy_dhcp_server)).thenReturn(
                false);
        initializeBpfOffloadConfiguration(true, null /* unset */);

        mHasTelephonyManager = true;
        mMockContext = new MockContext(mContext);
        mEnableLegacyDhcpServer = false;
    }

    @After
    public void tearDown() throws Exception {
        mMockingSession.finishMocking();
    }

    private TetheringConfiguration getTetheringConfiguration(int... legacyTetherUpstreamTypes) {
        when(mResources.getIntArray(R.array.config_tether_upstream_types)).thenReturn(
                legacyTetherUpstreamTypes);
        return new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
    }

    @Test
    public void testNoTelephonyManagerMeansNoDun() {
        mHasTelephonyManager = false;
        final TetheringConfiguration cfg = getTetheringConfiguration(
                new int[]{TYPE_MOBILE_DUN, TYPE_WIFI});
        assertFalse(cfg.isDunRequired);
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
    }

    @Test
    public void testDunFromTelephonyManagerMeansDun() {
        when(mTelephonyManager.isTetheringApnRequired()).thenReturn(true);

        final TetheringConfiguration cfgWifi = getTetheringConfiguration(TYPE_WIFI);
        final TetheringConfiguration cfgMobileWifiHipri = getTetheringConfiguration(
                TYPE_MOBILE, TYPE_WIFI, TYPE_MOBILE_HIPRI);
        final TetheringConfiguration cfgWifiDun = getTetheringConfiguration(
                TYPE_WIFI, TYPE_MOBILE_DUN);
        final TetheringConfiguration cfgMobileWifiHipriDun = getTetheringConfiguration(
                TYPE_MOBILE, TYPE_WIFI, TYPE_MOBILE_HIPRI, TYPE_MOBILE_DUN);

        for (TetheringConfiguration cfg : Arrays.asList(cfgWifi, cfgMobileWifiHipri,
                cfgWifiDun, cfgMobileWifiHipriDun)) {
            String msg = "config=" + cfg.toString();
            assertTrue(msg, cfg.isDunRequired);
            assertTrue(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
            assertFalse(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
            assertFalse(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
            // Just to prove we haven't clobbered Wi-Fi:
            assertTrue(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
        }
    }

    @Test
    public void testDunNotRequiredFromTelephonyManagerMeansNoDun() {
        when(mTelephonyManager.isTetheringApnRequired()).thenReturn(false);

        final TetheringConfiguration cfgWifi = getTetheringConfiguration(TYPE_WIFI);
        final TetheringConfiguration cfgMobileWifiHipri = getTetheringConfiguration(
                TYPE_MOBILE, TYPE_WIFI, TYPE_MOBILE_HIPRI);
        final TetheringConfiguration cfgWifiDun = getTetheringConfiguration(
                TYPE_WIFI, TYPE_MOBILE_DUN);
        final TetheringConfiguration cfgWifiMobile = getTetheringConfiguration(
                TYPE_WIFI, TYPE_MOBILE);
        final TetheringConfiguration cfgWifiHipri = getTetheringConfiguration(
                TYPE_WIFI, TYPE_MOBILE_HIPRI);
        final TetheringConfiguration cfgMobileWifiHipriDun = getTetheringConfiguration(
                TYPE_MOBILE, TYPE_WIFI, TYPE_MOBILE_HIPRI, TYPE_MOBILE_DUN);

        String msg;
        // TYPE_MOBILE_DUN should be present in none of the combinations.
        // TYPE_WIFI should not be affected.
        for (TetheringConfiguration cfg : Arrays.asList(cfgWifi, cfgMobileWifiHipri, cfgWifiDun,
                cfgWifiMobile, cfgWifiHipri, cfgMobileWifiHipriDun)) {
            msg = "config=" + cfg.toString();
            assertFalse(msg, cfg.isDunRequired);
            assertFalse(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
            assertTrue(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
        }

        for (TetheringConfiguration cfg : Arrays.asList(cfgWifi, cfgMobileWifiHipri, cfgWifiDun,
                cfgMobileWifiHipriDun)) {
            msg = "config=" + cfg.toString();
            assertTrue(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
            assertTrue(msg, cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
        }
        msg = "config=" + cfgWifiMobile.toString();
        assertTrue(msg, cfgWifiMobile.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertFalse(msg, cfgWifiMobile.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
        msg = "config=" + cfgWifiHipri.toString();
        assertFalse(msg, cfgWifiHipri.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertTrue(msg, cfgWifiHipri.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));

    }

    @Test
    public void testNoDefinedUpstreamTypesAddsEthernet() {
        when(mResources.getIntArray(R.array.config_tether_upstream_types)).thenReturn(new int[]{});
        when(mTelephonyManager.isTetheringApnRequired()).thenReturn(false);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        final Iterator<Integer> upstreamIterator = cfg.preferredUpstreamIfaceTypes.iterator();
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_ETHERNET, upstreamIterator.next().intValue());
        // The following is because the code always adds some kind of mobile
        // upstream, be it DUN or, in this case where DUN is NOT required,
        // make sure there is at least one of MOBILE or HIPRI. With the empty
        // list of the configuration in this test, it will always add both
        // MOBILE and HIPRI, in that order.
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_MOBILE, upstreamIterator.next().intValue());
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_MOBILE_HIPRI, upstreamIterator.next().intValue());
        assertFalse(upstreamIterator.hasNext());
    }

    @Test
    public void testDefinedUpstreamTypesSansEthernetAddsEthernet() {
        when(mResources.getIntArray(R.array.config_tether_upstream_types)).thenReturn(
                new int[]{TYPE_WIFI, TYPE_MOBILE_HIPRI});
        when(mTelephonyManager.isTetheringApnRequired()).thenReturn(false);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        final Iterator<Integer> upstreamIterator = cfg.preferredUpstreamIfaceTypes.iterator();
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_ETHERNET, upstreamIterator.next().intValue());
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_WIFI, upstreamIterator.next().intValue());
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_MOBILE_HIPRI, upstreamIterator.next().intValue());
        assertFalse(upstreamIterator.hasNext());
    }

    @Test
    public void testDefinedUpstreamTypesWithEthernetDoesNotAddEthernet() {
        when(mResources.getIntArray(R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_WIFI, TYPE_ETHERNET, TYPE_MOBILE_HIPRI});
        when(mTelephonyManager.isTetheringApnRequired()).thenReturn(false);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        final Iterator<Integer> upstreamIterator = cfg.preferredUpstreamIfaceTypes.iterator();
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_WIFI, upstreamIterator.next().intValue());
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_ETHERNET, upstreamIterator.next().intValue());
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_MOBILE_HIPRI, upstreamIterator.next().intValue());
        assertFalse(upstreamIterator.hasNext());
    }

    private void initializeBpfOffloadConfiguration(
            final boolean fromRes, final String fromDevConfig) {
        when(mResources.getBoolean(R.bool.config_tether_enable_bpf_offload)).thenReturn(fromRes);
        doReturn(fromDevConfig).when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY),
                eq(TetheringConfiguration.OVERRIDE_TETHER_ENABLE_BPF_OFFLOAD)));
    }

    @Test
    public void testBpfOffloadEnabledByResource() {
        initializeBpfOffloadConfiguration(true, null /* unset */);
        final TetheringConfiguration enableByRes =
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(enableByRes.isBpfOffloadEnabled());
    }

    @Test
    public void testBpfOffloadEnabledByDeviceConfigOverride() {
        for (boolean res : new boolean[]{true, false}) {
            initializeBpfOffloadConfiguration(res, "true");
            final TetheringConfiguration enableByDevConOverride =
                    new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
            assertTrue(enableByDevConOverride.isBpfOffloadEnabled());
        }
    }

    @Test
    public void testBpfOffloadDisabledByResource() {
        initializeBpfOffloadConfiguration(false, null /* unset */);
        final TetheringConfiguration disableByRes =
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(disableByRes.isBpfOffloadEnabled());
    }

    @Test
    public void testBpfOffloadDisabledByDeviceConfigOverride() {
        for (boolean res : new boolean[]{true, false}) {
            initializeBpfOffloadConfiguration(res, "false");
            final TetheringConfiguration disableByDevConOverride =
                    new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
            assertFalse(disableByDevConOverride.isBpfOffloadEnabled());
        }
    }

    @Test
    public void testNewDhcpServerDisabled() {
        when(mResources.getBoolean(R.bool.config_tether_enable_legacy_dhcp_server)).thenReturn(
                true);
        doReturn("false").when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY),
                eq(TetheringConfiguration.TETHER_ENABLE_LEGACY_DHCP_SERVER)));

        final TetheringConfiguration enableByRes =
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(enableByRes.enableLegacyDhcpServer);

        when(mResources.getBoolean(R.bool.config_tether_enable_legacy_dhcp_server)).thenReturn(
                false);
        doReturn("true").when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY),
                eq(TetheringConfiguration.TETHER_ENABLE_LEGACY_DHCP_SERVER)));

        final TetheringConfiguration enableByDevConfig =
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(enableByDevConfig.enableLegacyDhcpServer);
    }

    @Test
    public void testNewDhcpServerEnabled() {
        when(mResources.getBoolean(R.bool.config_tether_enable_legacy_dhcp_server)).thenReturn(
                false);
        doReturn("false").when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY),
                eq(TetheringConfiguration.TETHER_ENABLE_LEGACY_DHCP_SERVER)));

        final TetheringConfiguration cfg =
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);

        assertFalse(cfg.enableLegacyDhcpServer);
    }

    @Test
    public void testOffloadIntervalByResource() {
        final TetheringConfiguration intervalByDefault =
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertEquals(TetheringConfiguration.DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS,
                intervalByDefault.getOffloadPollInterval());

        final int[] testOverrides = {0, 3000, -1};
        for (final int override : testOverrides) {
            when(mResources.getInteger(R.integer.config_tether_offload_poll_interval)).thenReturn(
                    override);
            final TetheringConfiguration overrideByRes =
                    new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
            assertEquals(override, overrideByRes.getOffloadPollInterval());
        }
    }

    @Test
    public void testGetResourcesBySubId() {
        setUpResourceForSubId();
        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(cfg.provisioningApp.length == 0);
        final int anyValidSubId = 1;
        final MockTetheringConfiguration mockCfg =
                new MockTetheringConfiguration(mMockContext, mLog, anyValidSubId);
        assertEquals(mockCfg.provisioningApp[0], PROVISIONING_APP_NAME[0]);
        assertEquals(mockCfg.provisioningApp[1], PROVISIONING_APP_NAME[1]);
        assertEquals(mockCfg.provisioningAppNoUi, PROVISIONING_NO_UI_APP_NAME);
        assertEquals(mockCfg.provisioningResponse, PROVISIONING_APP_RESPONSE);
    }

    private void setUpResourceForSubId() {
        when(mResourcesForSubId.getStringArray(
                R.array.config_tether_dhcp_range)).thenReturn(new String[0]);
        when(mResourcesForSubId.getStringArray(
                R.array.config_tether_usb_regexs)).thenReturn(new String[0]);
        when(mResourcesForSubId.getStringArray(
                R.array.config_tether_wifi_regexs)).thenReturn(new String[]{ "test_wlan\\d" });
        when(mResourcesForSubId.getStringArray(
                R.array.config_tether_bluetooth_regexs)).thenReturn(new String[0]);
        when(mResourcesForSubId.getIntArray(R.array.config_tether_upstream_types)).thenReturn(
                new int[0]);
        when(mResourcesForSubId.getStringArray(
                R.array.config_mobile_hotspot_provision_app)).thenReturn(PROVISIONING_APP_NAME);
        when(mResourcesForSubId.getString(R.string.config_mobile_hotspot_provision_app_no_ui))
                .thenReturn(PROVISIONING_NO_UI_APP_NAME);
        when(mResourcesForSubId.getString(
                R.string.config_mobile_hotspot_provision_response)).thenReturn(
                PROVISIONING_APP_RESPONSE);
    }
}
