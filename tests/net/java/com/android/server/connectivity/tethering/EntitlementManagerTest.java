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
 * limitations under the License.
 */

package com.android.server.connectivity.tethering;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.os.PersistableBundle;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.CarrierConfigManager;

import com.android.internal.R;
import com.android.server.connectivity.MockableSystemProperties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class EntitlementManagerTest {

    private static final int EVENT_EM_UPDATE = 1;
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};

    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private Context mContext;
    @Mock private ContentResolver mContent;
    @Mock private MockableSystemProperties mSystemProperties;
    @Mock private Resources mResources;
    @Mock private SharedLog mLog;

    // Like so many Android system APIs, these cannot be mocked because it is marked final.
    // We have to use the real versions.
    private final PersistableBundle mCarrierConfig = new PersistableBundle();

    private EntitlementManager mEnMgr;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContent);
        when(mResources.getStringArray(R.array.config_tether_dhcp_range))
            .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_usb_regexs))
            .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_wifi_regexs))
            .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_bluetooth_regexs))
            .thenReturn(new String[0]);
        when(mResources.getIntArray(R.array.config_tether_upstream_types))
            .thenReturn(new int[0]);
        when(mLog.forSubComponent(anyString())).thenReturn(mLog);

        mEnMgr = new EntitlementManager(mContext, mLog, mSystemProperties);
        mEnMgr.updateConfiguration(new TetheringConfiguration(mContext, mLog));
    }

    @After
    public void tearDown() throws Exception {}

    private void setupForRequiredProvisioning() {
        // Produce some acceptable looking provision app setting if requested.
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
        // Don't disable tethering provisioning unless requested.
        when(mSystemProperties.getBoolean(eq(EntitlementManager.DISABLE_PROVISIONING_SYSPROP_KEY),
                anyBoolean())).thenReturn(false);
        // Act like the CarrierConfigManager is present and ready unless told otherwise.
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
        when(mCarrierConfigManager.getConfig()).thenReturn(mCarrierConfig);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
    }

    @Test
    public void canRequireProvisioning() {
        setupForRequiredProvisioning();
        mEnMgr.updateConfiguration(new TetheringConfiguration(mContext, mLog));
        assertTrue(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigManagerMissing() {
        setupForRequiredProvisioning();
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
            .thenReturn(null);
        mEnMgr.updateConfiguration(new TetheringConfiguration(mContext, mLog));
        // Couldn't get the CarrierConfigManager, but still had a declared provisioning app.
        // Therefore provisioning still be required.
        assertTrue(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigMissing() {
        setupForRequiredProvisioning();
        when(mCarrierConfigManager.getConfig()).thenReturn(null);
        mEnMgr.updateConfiguration(new TetheringConfiguration(mContext, mLog));
        // We still have a provisioning app configured, so still require provisioning.
        assertTrue(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void provisioningNotRequiredWhenAppNotFound() {
        setupForRequiredProvisioning();
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(null);
        mEnMgr.updateConfiguration(new TetheringConfiguration(mContext, mLog));
        assertFalse(mEnMgr.isTetherProvisioningRequired());
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(new String[] {"malformedApp"});
        mEnMgr.updateConfiguration(new TetheringConfiguration(mContext, mLog));
        assertFalse(mEnMgr.isTetherProvisioningRequired());
    }

}
