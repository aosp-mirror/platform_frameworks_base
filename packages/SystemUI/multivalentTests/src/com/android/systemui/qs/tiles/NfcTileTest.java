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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NfcTileTest extends SysuiTestCase {

    private static final String TILES_STOCK_WITHOUT_NFC = "wifi,cell,battery,dnd,flashlight,bt";
    private static final String TILES_STOCK_WITH_NFC = "wifi,cell,battery,dnd,nfc,flashlight,bt";

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private QsEventLogger mUiEventLogger;

    private TestableLooper mTestableLooper;
    private NfcTile mNfcTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);

        mNfcTile = new NfcTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mBroadcastDispatcher
        );

        mNfcTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() {
        mNfcTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testIsAvailable_stockWithoutNfc_returnsFalse() {
        when(mMockContext.getString(R.string.quick_settings_tiles_stock)).thenReturn(
                TILES_STOCK_WITHOUT_NFC);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC)).thenReturn(true);
        assertFalse(mNfcTile.isAvailable());
    }

    @Test
    public void testIsAvailable_stockWithNfc_returnsTrue() {
        when(mMockContext.getString(R.string.quick_settings_tiles_stock)).thenReturn(
                TILES_STOCK_WITH_NFC);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC)).thenReturn(true);
        assertTrue(mNfcTile.isAvailable());
    }
}
