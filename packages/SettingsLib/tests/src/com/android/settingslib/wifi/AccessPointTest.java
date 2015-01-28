/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;

import com.android.settingslib.BaseTest;
import com.android.settingslib.wifi.AccessPoint.AccessPointListener;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

// TODO: Add some coverage
public class AccessPointTest extends BaseTest {

    private static final String TEST_SSID = "TestSsid";
    private static final int NETWORK_ID = 0;

    private AccessPointListener mAccessPointListener;
    private AccessPoint mAccessPoint;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mAccessPointListener = Mockito.mock(AccessPointListener.class);

        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.networkId = NETWORK_ID;
        wifiConfig.SSID = TEST_SSID;

        mAccessPoint = new AccessPoint(mContext, wifiConfig);
        mAccessPoint.setListener(mAccessPointListener);
    }

    public void testOnLevelChanged() {
        ScanResult result = new ScanResult();
        result.capabilities = "";
        result.SSID = TEST_SSID;

        // Give it a level.
        result.level = WifiTrackerTest.levelToRssi(1);
        mAccessPoint.update(result);
        verifyOnLevelChangedCallback(1);

        // Give it a better level.
        result.level = WifiTrackerTest.levelToRssi(2);
        mAccessPoint.update(result);
        verifyOnLevelChangedCallback(1);
    }

    public void testOnAccessPointChangedCallback() {
        WifiInfo wifiInfo = Mockito.mock(WifiInfo.class);
        Mockito.when(wifiInfo.getNetworkId()).thenReturn(NETWORK_ID);

        mAccessPoint.update(wifiInfo, null);
        verifyOnAccessPointsCallback(1);

        mAccessPoint.update(null, null);
        verifyOnAccessPointsCallback(2);

        ScanResult result = new ScanResult();
        result.capabilities = "";
        result.SSID = TEST_SSID;
        mAccessPoint.update(result);
        verifyOnAccessPointsCallback(3);
    }

    private void verifyOnLevelChangedCallback(int num) {
        ArgumentCaptor<AccessPoint> accessPoint = ArgumentCaptor.forClass(AccessPoint.class);
        Mockito.verify(mAccessPointListener, Mockito.atLeast(num))
                .onLevelChanged(accessPoint.capture());
        assertEquals(mAccessPoint, accessPoint.getValue());
    }

    private void verifyOnAccessPointsCallback(int num) {
        ArgumentCaptor<AccessPoint> accessPoint = ArgumentCaptor.forClass(AccessPoint.class);
        Mockito.verify(mAccessPointListener, Mockito.atLeast(num))
                .onAccessPointChanged(accessPoint.capture());
        assertEquals(mAccessPoint, accessPoint.getValue());
    }

}
