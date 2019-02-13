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

package com.android.server.connectivity.tethering;

import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.provider.Settings.Global.TETHER_ENABLE_LEGACY_DHCP_SERVER;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.server.connectivity.tethering.TetheringConfiguration.DUN_NOT_REQUIRED;
import static com.android.server.connectivity.tethering.TetheringConfiguration.DUN_REQUIRED;
import static com.android.server.connectivity.tethering.TetheringConfiguration.DUN_UNSPECIFIED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Iterator;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringConfigurationTest {
    private final SharedLog mLog = new SharedLog("TetheringConfigurationTest");

    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    @Mock private Context mContext;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private Resources mResources;
    @Mock private Resources mResourcesForSubId;
    private MockContentResolver mContentResolver;
    private Context mMockContext;
    private boolean mHasTelephonyManager;

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
        public Resources getResources() { return mResources; }

        @Override
        public Object getSystemService(String name) {
            if (Context.TELEPHONY_SERVICE.equals(name)) {
                return mHasTelephonyManager ? mTelephonyManager : null;
            }
            return super.getSystemService(name);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_dhcp_range))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_usb_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_wifi_regexs))
                .thenReturn(new String[]{ "test_wlan\\d" });
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mResources.getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app))
                .thenReturn(new String[0]);
        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mMockContext = new MockContext(mContext);
    }

    @Test
    public void testDunFromTelephonyManagerMeansDun() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_MOBILE, TYPE_WIFI, TYPE_MOBILE_HIPRI});
        mHasTelephonyManager = true;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_REQUIRED);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(cfg.isDunRequired);
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
    }

    @Test
    public void testDunNotRequiredFromTelephonyManagerMeansNoDun() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_MOBILE_DUN, TYPE_WIFI});
        mHasTelephonyManager = true;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_NOT_REQUIRED);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(cfg.isDunRequired);
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
    }

    @Test
    public void testDunFromUpstreamConfigMeansDun() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_MOBILE_DUN, TYPE_WIFI});
        mHasTelephonyManager = false;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_UNSPECIFIED);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(cfg.isDunRequired);
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
        // Check that we have not added new cellular interface types
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
    }

    @Test
    public void testNoDefinedUpstreamTypesAddsEthernet() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{});
        mHasTelephonyManager = false;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_UNSPECIFIED);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        final Iterator<Integer> upstreamIterator = cfg.preferredUpstreamIfaceTypes.iterator();
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_ETHERNET, upstreamIterator.next().intValue());
        // The following is because the code always adds some kind of mobile
        // upstream, be it DUN or, in this case where we use DUN_UNSPECIFIED,
        // both vanilla and hipri mobile types.
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_MOBILE, upstreamIterator.next().intValue());
        assertTrue(upstreamIterator.hasNext());
        assertEquals(TYPE_MOBILE_HIPRI, upstreamIterator.next().intValue());
        assertFalse(upstreamIterator.hasNext());
    }

    @Test
    public void testDefinedUpstreamTypesSansEthernetAddsEthernet() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_WIFI, TYPE_MOBILE_HIPRI});
        mHasTelephonyManager = false;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_UNSPECIFIED);

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
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_WIFI, TYPE_ETHERNET, TYPE_MOBILE_HIPRI});
        mHasTelephonyManager = false;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_UNSPECIFIED);

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

    @Test
    public void testNewDhcpServerDisabled() {
        Settings.Global.putInt(mContentResolver, TETHER_ENABLE_LEGACY_DHCP_SERVER, 1);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertTrue(cfg.enableLegacyDhcpServer);
    }

    @Test
    public void testNewDhcpServerEnabled() {
        Settings.Global.putInt(mContentResolver, TETHER_ENABLE_LEGACY_DHCP_SERVER, 0);

        final TetheringConfiguration cfg = new TetheringConfiguration(
                mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(cfg.enableLegacyDhcpServer);
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
    }

    private void setUpResourceForSubId() {
        when(mResourcesForSubId.getStringArray(
                com.android.internal.R.array.config_tether_dhcp_range)).thenReturn(new String[0]);
        when(mResourcesForSubId.getStringArray(
                com.android.internal.R.array.config_tether_usb_regexs)).thenReturn(new String[0]);
        when(mResourcesForSubId.getStringArray(
                com.android.internal.R.array.config_tether_wifi_regexs))
                .thenReturn(new String[]{ "test_wlan\\d" });
        when(mResourcesForSubId.getStringArray(
                com.android.internal.R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResourcesForSubId.getIntArray(
                com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mResourcesForSubId.getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
    }

}
