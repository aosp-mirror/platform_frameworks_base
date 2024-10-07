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

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.service.quicksettings.Tile;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.dialog.InternetDialogManager;
import com.android.systemui.qs.tiles.dialog.WifiStateWorker;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.WifiIndicators;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class InternetTileTest extends SysuiTestCase {

    @Mock
    private QSHost mHost;
    @Mock
    private NetworkController mNetworkController;
    @Mock
    private AccessPointController mAccessPointController;
    @Mock
    private InternetDialogManager mInternetDialogManager;
    @Mock
    private WifiStateWorker mWifiStateWorker;
    @Mock
    private QsEventLogger mUiEventLogger;

    private TestableLooper mTestableLooper;
    private InternetTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getUserContext()).thenReturn(mContext);

        mTile = new InternetTile(
            mHost,
            mUiEventLogger,
            mTestableLooper.getLooper(),
            new Handler(mTestableLooper.getLooper()),
            new FalsingManagerFake(),
            mock(MetricsLogger.class),
            mock(StatusBarStateController.class),
            mock(ActivityStarter.class),
            mock(QSLogger.class),
            mNetworkController,
            mAccessPointController,
            mInternetDialogManager,
            mWifiStateWorker
        );

        mTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() {
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void setConnectivityStatus_defaultNetworkNotExists_updateTile() {
        mTile.mSignalCallback.setConnectivityStatus(
            /* noDefaultNetwork= */ true,
            /* noValidatedNetwork= */ true,
            /* noNetworksAvailable= */ true);
        mTestableLooper.processAllMessages();
        assertThat(String.valueOf(mTile.getState().secondaryLabel))
            .isEqualTo(mContext.getString(R.string.quick_settings_networks_unavailable));
        assertThat(mTile.getLastTileState()).isEqualTo(1);
    }

    @Test
    public void setConnectivityStatus_defaultNetworkExists_notUpdateTile() {
        mTile.mSignalCallback.setConnectivityStatus(
            /* noDefaultNetwork= */ false,
            /* noValidatedNetwork= */ true,
            /* noNetworksAvailable= */ true);
        mTestableLooper.processAllMessages();
        assertThat(String.valueOf(mTile.getState().secondaryLabel))
            .isNotEqualTo(mContext.getString(R.string.quick_settings_networks_unavailable));
        assertThat(String.valueOf(mTile.getState().secondaryLabel))
            .isNotEqualTo(mContext.getString(R.string.quick_settings_networks_available));
        assertThat(mTile.getLastTileState()).isEqualTo(-1);
    }

    @Test
    public void setIsAirplaneMode_APM_enabled_wifi_disabled() {
        IconState state = new IconState(true, 0, "");
        mTile.mSignalCallback.setIsAirplaneMode(state);
        mTestableLooper.processAllMessages();
        assertThat(mTile.getState().state).isEqualTo(Tile.STATE_INACTIVE);
        assertThat(mTile.getState().secondaryLabel)
            .isEqualTo(mContext.getString(R.string.status_bar_airplane));
    }

    @Test
    public void setIsAirplaneMode_APM_enabled_wifi_enabled() {
        IconState state = new IconState(false, 0, "");
        mTile.mSignalCallback.setIsAirplaneMode(state);
        mTestableLooper.processAllMessages();
        assertThat(mTile.getState().state).isEqualTo(Tile.STATE_ACTIVE);
        assertThat(mTile.getState().secondaryLabel)
            .isNotEqualTo(mContext.getString(R.string.status_bar_airplane));
    }

    @Test
    public void setIsAirplaneMode_APM_enabled_after_wifi_disconnected() {
        WifiIndicators wifiIndicators = new WifiIndicators(
            /* enabled= */ true,
            /* statusIcon= */ null,
            /* qsIcon= */ null,
            /* activityIn= */ false,
            /* activityOut= */ false,
            /* description= */ null,
            /* isTransient= */ false,
            /* statusLabel= */ null
        );
        mTile.mSignalCallback.setWifiIndicators(wifiIndicators);
        IconState state = new IconState(true, 0, "");
        mTile.mSignalCallback.setIsAirplaneMode(state);
        mTestableLooper.processAllMessages();
        assertThat(mTile.getState().icon).isEqualTo(
                QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable));
    }

    @Test
    public void secondaryClick_turnsWifiOff() {
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);

        mTile.secondaryClick(null);

        verify(mWifiStateWorker, times(1)).setWifiEnabled(eq(false));
    }

    @Test
    public void secondaryClick_turnsWifiOn() {
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(false);

        mTile.secondaryClick(null);

        verify(mWifiStateWorker, times(1)).setWifiEnabled(eq(true));
    }
}
