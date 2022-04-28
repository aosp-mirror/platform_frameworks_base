/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.connectivity;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ConnectivitySubsystemsRecoveryManagerTest {

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Spy
    Context mContext = ApplicationProvider.getApplicationContext();
    @Spy
    Handler mMainHandler = ApplicationProvider.getApplicationContext().getMainThreadHandler();
    @Mock
    PackageManager mPackageManager;

    ConnectivitySubsystemsRecoveryManager mConnectivitySubsystemsRecoveryManager;

    @Before
    public void setUp() {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)).thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
    }

    @Test
    public void startTrackingWifiRestart_hasNoWifiFeature_shouldNotCrash() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)).thenReturn(false);
        mConnectivitySubsystemsRecoveryManager =
                new ConnectivitySubsystemsRecoveryManager(mContext, mMainHandler);

        mConnectivitySubsystemsRecoveryManager.startTrackingWifiRestart();
    }

    @Test
    public void stopTrackingWifiRestart_hasNoWifiFeature_shouldNotCrash() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)).thenReturn(false);
        mConnectivitySubsystemsRecoveryManager =
                new ConnectivitySubsystemsRecoveryManager(mContext, mMainHandler);

        mConnectivitySubsystemsRecoveryManager.stopTrackingWifiRestart();
    }

    @Test
    public void startTrackingTelephonyRestart_hasNoTelephonyFeature_shouldNotCrash() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false);
        mConnectivitySubsystemsRecoveryManager =
                new ConnectivitySubsystemsRecoveryManager(mContext, mMainHandler);

        mConnectivitySubsystemsRecoveryManager.startTrackingTelephonyRestart();
    }

    @Test
    public void stopTrackingTelephonyRestart_hasNoTelephonyFeature_shouldNotCrash() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false);
        mConnectivitySubsystemsRecoveryManager =
                new ConnectivitySubsystemsRecoveryManager(mContext, mMainHandler);

        mConnectivitySubsystemsRecoveryManager.stopTrackingTelephonyRestart();
    }
}
