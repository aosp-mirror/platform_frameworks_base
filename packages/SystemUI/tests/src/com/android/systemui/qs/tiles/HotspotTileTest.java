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

import static android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf;

import static com.android.systemui.Flags.FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.platform.test.flag.junit.FlagsParameterization;
import android.service.quicksettings.Tile;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.logging.MetricsLogger;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.flags.QsInCompose;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.HotspotController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@RunWith(ParameterizedAndroidJunit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HotspotTileTest extends SysuiTestCase {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return allCombinationsOf(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX);
    }

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();
    @Mock
    private QSHost mHost;
    @Mock
    private HotspotController mHotspotController;
    @Mock
    private DataSaverController mDataSaverController;
    @Mock
    private QsEventLogger mUiEventLogger;

    private TestableLooper mTestableLooper;
    private HotspotTile mTile;
    private QSTile.BooleanState mState = new QSTile.BooleanState();

    public HotspotTileTest(FlagsParameterization flags) {
        super();
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getUserContext()).thenReturn(mContext);
        when(mDataSaverController.isDataSaverEnabled()).thenReturn(false);

        mTile = new HotspotTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mock(MetricsLogger.class),
                mock(StatusBarStateController.class),
                mock(ActivityStarter.class),
                mock(QSLogger.class),
                mHotspotController,
                mDataSaverController
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
    public void handleUpdateState_wifiTetheringIsAllowed_stateIsNotUnavailable() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(WifiEnterpriseRestrictionUtils.class)
                .startMocking();
        when(WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(mContext)).thenReturn(true);

        mTile.handleUpdateState(mState, null);

        assertThat(mState.state).isNotEqualTo(Tile.STATE_UNAVAILABLE);
        assertThat(String.valueOf(mState.secondaryLabel))
                .isNotEqualTo(mContext.getString(com.android.wifitrackerlib.R.string.wifitrackerlib_admin_restricted_network));
        mockitoSession.finishMocking();
    }

    @Test
    public void handleUpdateState_wifiTetheringIsDisallowed_stateIsUnavailable() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(WifiEnterpriseRestrictionUtils.class)
                .startMocking();
        when(WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(mContext)).thenReturn(false);

        mTile.handleUpdateState(mState, null);

        assertThat(mState.state).isEqualTo(Tile.STATE_UNAVAILABLE);
        assertThat(String.valueOf(mState.secondaryLabel))
                .isEqualTo(mContext.getString(com.android.wifitrackerlib.R.string.wifitrackerlib_admin_restricted_network));
        mockitoSession.finishMocking();
    }

    @Test
    public void testIcon_whenDisabled_isOffState() {
        QSTile.BooleanState state = new QSTile.BooleanState();
        when(mHotspotController.isHotspotTransient()).thenReturn(false);
        when(mHotspotController.isHotspotEnabled()).thenReturn(false);

        mTile.handleUpdateState(state, /* arg= */ null);

        assertThat(state.icon)
                .isEqualTo(createExpectedIcon(R.drawable.qs_hotspot_icon_off));
    }

    @Test
    public void testIcon_whenTransient_isSearchState() {
        QSTile.BooleanState state = new QSTile.BooleanState();
        when(mHotspotController.isHotspotTransient()).thenReturn(true);
        when(mHotspotController.isHotspotEnabled()).thenReturn(true);

        mTile.handleUpdateState(state, /* arg= */ null);

        assertThat(state.icon)
                .isEqualTo(createExpectedIcon(R.drawable.qs_hotspot_icon_search));
    }

    @Test
    public void testIcon_whenEnabled_isOnState() {
        QSTile.BooleanState state = new QSTile.BooleanState();
        when(mHotspotController.isHotspotTransient()).thenReturn(false);
        when(mHotspotController.isHotspotEnabled()).thenReturn(true);

        mTile.handleUpdateState(state, /* arg= */ null);

        assertThat(state.icon)
                .isEqualTo(createExpectedIcon(R.drawable.qs_hotspot_icon_on));
    }

    private QSTile.Icon createExpectedIcon(int resId) {
        if (QsInCompose.isEnabled()) {
            return new QSTileImpl.DrawableIconWithRes(mContext.getDrawable(resId), resId);
        } else {
            return QSTileImpl.ResourceIcon.get(resId);
        }
    }
}
