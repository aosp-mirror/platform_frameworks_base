/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;

import static org.mockito.Mockito.when;

import android.os.Handler;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class QRCodeScannerTileTest extends SysuiTestCase {
    @Mock
    private QSTileHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    private UiEventLogger mUiEventLogger = new UiEventLoggerFake();
    @Mock
    private QRCodeScannerController mController;

    private TestableLooper mTestableLooper;
    private QRCodeScannerTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mHost.getContext()).thenReturn(mContext);

        mTile = new QRCodeScannerTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mController);

        mTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testNewTile() {
        assertFalse(mTile.newTileState().handlesLongClick);
    }

    @Test
    public void testTileDetails() {
        QSTile.State state = new QSTile.State();

        mTile.handleUpdateState(state, null);

        assertEquals(state.label, mContext.getString(R.string.qr_code_scanner_title));
        assertEquals(state.contentDescription, mContext.getString(R.string.qr_code_scanner_title));
        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(R.drawable.ic_qr_code_scanner));
    }

    @Test
    public void testQRCodeTileUnavailable() {
        when(mController.isAbleToOpenCameraApp()).thenReturn(false);
        QSTile.State state = new QSTile.State();
        mTile.handleUpdateState(state, null);
        assertEquals(state.state, Tile.STATE_UNAVAILABLE);
        assertEquals(state.secondaryLabel.toString(),
                     mContext.getString(R.string.qr_code_scanner_updating_secondary_label));
    }

    @Test
    public void testQRCodeTileAvailable() {
        when(mController.isAbleToOpenCameraApp()).thenReturn(true);
        QSTile.State state = new QSTile.State();
        mTile.handleUpdateState(state, null);
        assertEquals(state.state, Tile.STATE_INACTIVE);
        assertNull(state.secondaryLabel);
    }
}
