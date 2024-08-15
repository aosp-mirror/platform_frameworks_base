/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.hearingaid.HearingDevicesChecker;
import com.android.systemui.accessibility.hearingaid.HearingDevicesDialogManager;
import com.android.systemui.animation.Expandable;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BluetoothController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link HearingDevicesTile}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesTileTest extends SysuiTestCase {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private QSHost mHost;
    @Mock
    private QsEventLogger mUiEventLogger;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private HearingDevicesChecker mDevicesChecker;
    @Mock
    HearingDevicesDialogManager mHearingDevicesDialogManager;
    @Mock
    BluetoothController mBluetoothController;

    private TestableLooper mTestableLooper;
    private HearingDevicesTile mTile;

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        when(mHost.getContext()).thenReturn(mContext);

        mTile = new HearingDevicesTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mHearingDevicesDialogManager,
                mDevicesChecker,
                mBluetoothController);

        mTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() {
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    @EnableFlags(Flags.FLAG_HEARING_AIDS_QS_TILE_DIALOG)
    public void isAvailable_flagEnabled_true() {
        assertThat(mTile.isAvailable()).isTrue();
    }

    @Test
    @DisableFlags(Flags.FLAG_HEARING_AIDS_QS_TILE_DIALOG)
    public void isAvailable_flagDisabled_false() {
        assertThat(mTile.isAvailable()).isFalse();
    }

    @Test
    public void longClick_expectedAction() {
        mTile.longClick(null);
        mTestableLooper.processAllMessages();

        ArgumentCaptor<Intent> IntentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(IntentCaptor.capture(),
                anyInt(), any());
        assertThat(IntentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_HEARING_DEVICES_SETTINGS);
    }

    @Test
    public void handleClick_dialogShown() {
        Expandable expandable = Expandable.fromView(new View(mContext));
        mTile.handleClick(expandable);
        mTestableLooper.processAllMessages();

        verify(mHearingDevicesDialogManager).showDialog(expandable);
    }

    @Test
    public void handleUpdateState_activeHearingDevice_stateActiveConnectedLabel() {
        when(mDevicesChecker.isAnyActiveHearingDevice()).thenReturn(true);
        when(mDevicesChecker.isAnyPairedHearingDevice()).thenReturn(true);

        BooleanState activeState = new BooleanState();
        mTile.handleUpdateState(activeState, null);

        assertThat(activeState.state).isEqualTo(Tile.STATE_ACTIVE);
        assertThat(activeState.secondaryLabel.toString()).isEqualTo(
                mContext.getString(R.string.quick_settings_hearing_devices_connected));
    }

    @Test
    public void handleUpdateState_bondedInactiveHearingDevice_stateInactiveDisconnectedLabel() {
        when(mDevicesChecker.isAnyActiveHearingDevice()).thenReturn(false);
        when(mDevicesChecker.isAnyPairedHearingDevice()).thenReturn(true);

        BooleanState disconnectedState = new BooleanState();
        mTile.handleUpdateState(disconnectedState, null);

        assertThat(disconnectedState.state).isEqualTo(Tile.STATE_INACTIVE);
        assertThat(disconnectedState.secondaryLabel.toString()).isEqualTo(
                mContext.getString(R.string.quick_settings_hearing_devices_disconnected));
    }

    @Test
    public void handleUpdateState_noHearingDevice_stateInactive() {
        when(mDevicesChecker.isAnyActiveHearingDevice()).thenReturn(false);
        when(mDevicesChecker.isAnyPairedHearingDevice()).thenReturn(false);

        BooleanState inactiveState = new BooleanState();
        mTile.handleUpdateState(inactiveState, null);

        assertThat(inactiveState.state).isEqualTo(Tile.STATE_INACTIVE);
    }
}
