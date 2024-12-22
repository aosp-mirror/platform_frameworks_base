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

package com.android.systemui.qs.tiles;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.service.quicksettings.Tile;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.server.display.feature.flags.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.extradim.ExtraDimDialogManager;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R.drawable;
import com.android.systemui.settings.UserTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ReduceBrightColorsTileTest extends SysuiTestCase {
    @Mock
    private QSHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private ReduceBrightColorsController mReduceBrightColorsController;
    @Mock
    private QsEventLogger mUiEventLogger;
    @Mock
    private ExtraDimDialogManager mExtraDimDialogManager;

    private TestableLooper mTestableLooper;
    private ReduceBrightColorsTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);

        mTile = new ReduceBrightColorsTile(
                true,
                mReduceBrightColorsController,
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mExtraDimDialogManager
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
    public void testNotActive() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(false);
        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);
        assertEquals(mTile.getState().label.toString(),
                mContext.getString(R.string.reduce_bright_colors_feature_name));
    }

    @Test
    public void testActive() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(true);
        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
        assertEquals(mTile.getState().label.toString(),
                mContext.getString(R.string.reduce_bright_colors_feature_name));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_EVEN_DIMMER)
    public void testActive_clicked_featureIsActivated() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(false);
        mTile.refreshState();
        mTestableLooper.processAllMessages();
        // Validity check
        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);

        mTile.handleClick(null /* view */);

        verify(mReduceBrightColorsController, times(1))
                .setReduceBrightColorsActivated(eq(true));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    public void testDialogueShownOnClick() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(true);
        when(mReduceBrightColorsController.isInUpgradeMode(mContext.getResources()))
                .thenReturn(true);
        mTile = new ReduceBrightColorsTile(
                true,
                mReduceBrightColorsController,
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mExtraDimDialogManager
        );

        mTile.initialize();
        mTestableLooper.processAllMessages();

        // Validity check
        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
        mTile.handleClick(null /* view */);

        verify(mExtraDimDialogManager, times(1))
                .dismissKeyguardIfNeededAndShowDialog(any());
        verify(mReduceBrightColorsController, times(0))
                .setReduceBrightColorsActivated(anyBoolean());
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    public void testDialogueShownOnLongClick() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(true);
        when(mReduceBrightColorsController.isInUpgradeMode(mContext.getResources()))
                .thenReturn(true);
        mTile = new ReduceBrightColorsTile(
                true,
                mReduceBrightColorsController,
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mExtraDimDialogManager
        );

        mTile.initialize();
        mTestableLooper.processAllMessages();

        // Validity check
        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
        mTile.handleLongClick(null /* view */);

        verify(mExtraDimDialogManager, times(1))
                .dismissKeyguardIfNeededAndShowDialog(any());
        verify(mReduceBrightColorsController, times(0))
                .setReduceBrightColorsActivated(anyBoolean());
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testIcon_whenTileEnabled_isOnState() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(true);
        mTile.refreshState();
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(drawable.qs_extra_dim_icon_on));
    }

    @Test
    public void testIcon_whenTileDisabled_isOffState() {
        when(mReduceBrightColorsController.isReduceBrightColorsActivated()).thenReturn(false);
        mTile.refreshState();
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(drawable.qs_extra_dim_icon_off));
    }

}
