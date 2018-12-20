/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.customize;

import static junit.framework.Assert.assertTrue;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSTileHost;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TileQueryHelperTest extends SysuiTestCase {
    private static final String CURRENT_TILES = "wifi,dnd,nfc";
    private static final String ONLY_STOCK_TILES = "wifi,dnd";
    private static final String WITH_OTHER_TILES = "wifi,dnd,other";
    // Note no nfc in stock tiles
    private static final String STOCK_TILES = "wifi,dnd,cell,battery";
    private static final String ALL_TILES = "wifi,dnd,nfc,cell,battery";
    private static final Set<String> FACTORY_TILES = new ArraySet<>();

    static {
        FACTORY_TILES.addAll(Arrays.asList(
                new String[]{"wifi", "bt", "cell", "dnd", "inversion", "airplane", "work",
                        "rotation", "flashlight", "location", "cast", "hotspot", "user", "battery",
                        "saver", "night", "nfc"}));
    }

    @Mock
    private TileQueryHelper.TileStateListener mListener;
    @Mock
    private QSTileHost mQSTileHost;
    @Mock
    private PackageManager mPackageManager;

    private QSTile.State mState;
    private TestableLooper mBGLooper;
    private TileQueryHelper mTileQueryHelper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBGLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.BG_LOOPER, mBGLooper.getLooper());
        mContext.setMockPackageManager(mPackageManager);

        mState = new QSTile.State();
        doAnswer(invocation -> {
                    String spec = (String) invocation.getArguments()[0];
                    if (FACTORY_TILES.contains(spec)) {
                        QSTile m = mock(QSTile.class);
                        when(m.isAvailable()).thenReturn(true);
                        when(m.getTileSpec()).thenReturn(spec);
                        when(m.getState()).thenReturn(mState);
                        return m;
                    } else {
                        return null;
                    }
                }
        ).when(mQSTileHost).createTile(anyString());

        mTileQueryHelper = new TileQueryHelper(mContext, mListener);
    }

    @Test
    public void testIsFinished_falseBeforeQuerying() {
        assertFalse(mTileQueryHelper.isFinished());
    }

    @Test
    public void testIsFinished_trueAfterQuerying() {
        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        assertTrue(mTileQueryHelper.isFinished());
    }

    @Test
    public void testQueryTiles_callsListenerTwice() {
        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        verify(mListener, times(2)).onTilesChanged(any());
    }

    @Test
    public void testQueryTiles_isFinishedFalseOnListenerCalls_thenTrueAfterCompletion() {
        doAnswer(invocation -> {
            assertFalse(mTileQueryHelper.isFinished());
            return null;
        }).when(mListener).onTilesChanged(any());

        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        assertTrue(mTileQueryHelper.isFinished());
    }

    @Test
    public void testQueryTiles_correctTilesAndOrderOnlyStockTiles() {
        ArgumentCaptor<List<TileQueryHelper.TileInfo>> captor = ArgumentCaptor.forClass(List.class);

        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                ONLY_STOCK_TILES);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);

        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        verify(mListener, atLeastOnce()).onTilesChanged(captor.capture());
        List<String> specs = new ArrayList<>();
        for (TileQueryHelper.TileInfo t : captor.getValue()) {
            specs.add(t.spec);
        }
        String tiles = TextUtils.join(",", specs);
        assertThat(tiles, is(equalTo(STOCK_TILES)));
    }

    @Test
    public void testQueryTiles_correctTilesAndOrderOtherFactoryTiles() {
        ArgumentCaptor<List<TileQueryHelper.TileInfo>> captor = ArgumentCaptor.forClass(List.class);

        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                CURRENT_TILES);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);

        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        verify(mListener, atLeastOnce()).onTilesChanged(captor.capture());
        List<String> specs = new ArrayList<>();
        for (TileQueryHelper.TileInfo t : captor.getValue()) {
            specs.add(t.spec);
        }
        String tiles = TextUtils.join(",", specs);
        assertThat(tiles, is(equalTo(ALL_TILES)));
    }

    @Test
    public void testQueryTiles_otherTileNotIncluded() {
        ArgumentCaptor<List<TileQueryHelper.TileInfo>> captor = ArgumentCaptor.forClass(List.class);

        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                WITH_OTHER_TILES);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);

        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        verify(mListener, atLeastOnce()).onTilesChanged(captor.capture());
        List<String> specs = new ArrayList<>();
        for (TileQueryHelper.TileInfo t : captor.getValue()) {
            specs.add(t.spec);
        }
        assertFalse(specs.contains("other"));
    }

    @Test
    public void testQueryTiles_nullSetting() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES, null);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);
        mTileQueryHelper.queryTiles(mQSTileHost);
    }
}
