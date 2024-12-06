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

package com.android.systemui.qs.tiles.dialog;

import static android.net.wifi.WifiManager.EXTRA_WIFI_STATE;
import static android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.wifi.WifiManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WifiStateWorkerTest extends SysuiTestCase {

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private Intent mIntent;

    private WifiStateWorker mWifiStateWorker;
    private FakeExecutor mBackgroundExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        when(mWifiManager.setWifiEnabled(anyBoolean())).thenReturn(true);
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_ENABLED);
        when(mIntent.getAction()).thenReturn(WIFI_STATE_CHANGED_ACTION);
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_ENABLED);

        mWifiStateWorker = new WifiStateWorker(mBroadcastDispatcher, mBackgroundExecutor,
                mWifiManager);
        mBackgroundExecutor.runAllReady();
    }

    @Test
    public void constructor_shouldGetWifiState() {
        verify(mWifiManager).getWifiState();
    }

    @Test
    public void setWifiEnabled_wifiManagerIsNull_shouldNotSetWifiEnabled() {
        mWifiStateWorker = new WifiStateWorker(mBroadcastDispatcher, mBackgroundExecutor,
                null /* wifiManager */);

        mWifiStateWorker.setWifiEnabled(true);
        mBackgroundExecutor.runAllReady();

        verify(mWifiManager, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void setWifiEnabled_enabledIsTrue_shouldSetWifiEnabled() {
        mWifiStateWorker.setWifiEnabled(true);
        mBackgroundExecutor.runAllReady();

        verify(mWifiManager).setWifiEnabled(true);
    }

    @Test
    public void setWifiEnabled_enabledIsFalse_shouldSetWifiDisabled() {
        mWifiStateWorker.setWifiEnabled(false);
        mBackgroundExecutor.runAllReady();

        verify(mWifiManager).setWifiEnabled(false);
    }

    @Test
    public void getWifiState_receiveWifiStateDisabling_getWifiStateDisabling() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_DISABLING);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_DISABLING);
    }

    @Test
    public void getWifiState_receiveWifiStateDisabled_getWifiStateDisabled() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_DISABLED);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_DISABLED);
    }

    @Test
    public void getWifiState_receiveWifiStateEnabling_getWifiStateEnabling() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_ENABLING);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_ENABLING);
    }

    @Test
    public void getWifiState_receiveWifiStateEnabled_getWifiStateEnabled() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_ENABLED);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_ENABLED);
    }

    @Test
    public void getWifiState_receiveWifiStateUnknown_ignoreTheIntent() {
        // Update the Wi-Fi state to WIFI_STATE_DISABLED
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_DISABLED);
        mWifiStateWorker.onReceive(mContext, mIntent);
        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_DISABLED);

        // Receiver WIFI_STATE_UNKNOWN
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_UNKNOWN);
        mWifiStateWorker.onReceive(mContext, mIntent);

        // Ignore the intent and keep the Wi-Fi state to WIFI_STATE_DISABLED
        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_DISABLED);

        // Update the Wi-Fi state to WIFI_STATE_ENABLED
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_ENABLED);
        mWifiStateWorker.onReceive(mContext, mIntent);
        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_ENABLED);

        // Receiver WIFI_STATE_UNKNOWN change
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_UNKNOWN);
        mWifiStateWorker.onReceive(mContext, mIntent);

        // Ignore the intent and keep the Wi-Fi state to WIFI_STATE_ENABLED
        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_ENABLED);
    }

    @Test
    public void isWifiEnabled_receiveWifiStateDisabling_returnFalse() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_DISABLING);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.isWifiEnabled()).isFalse();
    }

    @Test
    public void isWifiEnabled_receiveWifiStateDisabled_returnFalse() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_DISABLED);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.isWifiEnabled()).isFalse();
    }

    @Test
    public void isWifiEnabled_receiveWifiStateEnabling_returnTrue() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_ENABLING);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.isWifiEnabled()).isTrue();
    }

    @Test
    public void isWifiEnabled_receiveWifiStateEnabled_returnTrue() {
        when(mIntent.getIntExtra(eq(EXTRA_WIFI_STATE), anyInt())).thenReturn(WIFI_STATE_ENABLED);
        mWifiStateWorker.onReceive(mContext, mIntent);

        assertThat(mWifiStateWorker.isWifiEnabled()).isTrue();
    }
}
