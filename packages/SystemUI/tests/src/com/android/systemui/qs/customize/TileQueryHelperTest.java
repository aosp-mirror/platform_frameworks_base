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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class TileQueryHelperTest extends SysuiTestCase {
    private static final String CURRENT_TILES = "wifi,dnd,nfc";
    private static final String ONLY_STOCK_TILES = "wifi,dnd";
    private static final String WITH_OTHER_TILES = "wifi,dnd,other";
    // Note no nfc in stock tiles
    private static final String STOCK_TILES = "wifi,dnd,cell,battery";
    private static final String ALL_TILES = "wifi,dnd,nfc,cell,battery";
    private static final Set<String> FACTORY_TILES = new ArraySet<>();
    private static final String TEST_PKG = "test_pkg";
    private static final String TEST_CLS = "test_cls";
    private static final String CUSTOM_TILE = "custom(" + TEST_PKG + "/" + TEST_CLS + ")";

    static {
        FACTORY_TILES.addAll(Arrays.asList(
                new String[]{"wifi", "bt", "cell", "dnd", "inversion", "airplane", "work",
                        "rotation", "flashlight", "location", "cast", "hotspot", "user", "battery",
                        "saver", "night", "nfc"}));
        FACTORY_TILES.add(CUSTOM_TILE);
    }

    @Mock
    private TileQueryHelper.TileStateListener mListener;
    @Mock
    private QSTileHost mQSTileHost;
    @Mock
    private PackageManager mPackageManager;
    @Captor
    private ArgumentCaptor<List<TileQueryHelper.TileInfo>> mCaptor;

    private QSTile.State mState;
    private TileQueryHelper mTileQueryHelper;
    private FakeExecutor mMainExecutor;
    private FakeExecutor mBgExecutor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

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

        FakeSystemClock clock = new FakeSystemClock();
        mMainExecutor = new FakeExecutor(clock);
        mBgExecutor = new FakeExecutor(clock);
        mTileQueryHelper = new TileQueryHelper(mContext, mMainExecutor, mBgExecutor);
        mTileQueryHelper.setListener(mListener);
    }

    @Test
    public void testIsFinished_falseBeforeQuerying() {
        assertFalse(mTileQueryHelper.isFinished());
    }

    @Test
    public void testIsFinished_trueAfterQuerying() {
        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        assertTrue(mTileQueryHelper.isFinished());
    }

    @Test
    public void testQueryTiles_callsListenerTwice() {
        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        verify(mListener, times(2)).onTilesChanged(any());
    }

    @Test
    public void testQueryTiles_isFinishedFalseOnListenerCalls_thenTrueAfterCompletion() {
        doAnswer(invocation -> {
            assertFalse(mTileQueryHelper.isFinished());
            return null;
        }).when(mListener).onTilesChanged(any());

        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        assertTrue(mTileQueryHelper.isFinished());
    }

    @Test
    public void testQueryTiles_correctTilesAndOrderOnlyStockTiles() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                ONLY_STOCK_TILES);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);

        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        verify(mListener, atLeastOnce()).onTilesChanged(mCaptor.capture());
        List<String> specs = new ArrayList<>();
        for (TileQueryHelper.TileInfo t : mCaptor.getValue()) {
            specs.add(t.spec);
        }
        String tiles = TextUtils.join(",", specs);
        assertThat(tiles, is(equalTo(STOCK_TILES)));
    }

    @Test
    public void testQueryTiles_correctTilesAndOrderOtherFactoryTiles() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                CURRENT_TILES);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);

        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        verify(mListener, atLeastOnce()).onTilesChanged(mCaptor.capture());
        List<String> specs = new ArrayList<>();
        for (TileQueryHelper.TileInfo t : mCaptor.getValue()) {
            specs.add(t.spec);
        }
        String tiles = TextUtils.join(",", specs);
        assertThat(tiles, is(equalTo(ALL_TILES)));
    }

    @Test
    public void testQueryTiles_otherTileNotIncluded() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                WITH_OTHER_TILES);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);

        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        verify(mListener, atLeastOnce()).onTilesChanged(mCaptor.capture());
        List<String> specs = new ArrayList<>();
        for (TileQueryHelper.TileInfo t : mCaptor.getValue()) {
            specs.add(t.spec);
        }
        assertFalse(specs.contains("other"));
    }

    @Test
    public void testCustomTileNotCreated() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES,
                CUSTOM_TILE);
        mTileQueryHelper.queryTiles(mQSTileHost);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mQSTileHost, never()).createTile(CUSTOM_TILE);
    }

    @Test
    public void testThirdPartyTilesInactive() {
        ResolveInfo resolveInfo = new ResolveInfo();
        ServiceInfo serviceInfo = mock(ServiceInfo.class, Answers.RETURNS_MOCKS);
        resolveInfo.serviceInfo = serviceInfo;
        serviceInfo.packageName = TEST_PKG;
        serviceInfo.name = TEST_CLS;
        serviceInfo.icon = R.drawable.android;
        serviceInfo.permission = Manifest.permission.BIND_QUICK_SETTINGS_TILE;
        serviceInfo.applicationInfo = mock(ApplicationInfo.class, Answers.RETURNS_MOCKS);
        serviceInfo.applicationInfo.icon = R.drawable.android;
        List<ResolveInfo> list = new ArrayList<>();
        list.add(resolveInfo);
        when(mPackageManager.queryIntentServicesAsUser(any(), anyInt(), anyInt())).thenReturn(list);

        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES, "");
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                "");

        mTileQueryHelper.queryTiles(mQSTileHost);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        verify(mListener, atLeastOnce()).onTilesChanged(mCaptor.capture());
        List<TileQueryHelper.TileInfo> tileInfos = mCaptor.getValue();
        assertEquals(1, tileInfos.size());
        assertEquals(Tile.STATE_INACTIVE, tileInfos.get(0).state.state);
    }

    @Test
    public void testQueryTiles_nullSetting() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES, null);
        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                STOCK_TILES);
        mTileQueryHelper.queryTiles(mQSTileHost);
    }

    @Test
    public void testQueryTiles_notAvailableDestroyed_tileSpecIsSet() {
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.QS_TILES, null);

        QSTile t = mock(QSTile.class);
        when(mQSTileHost.createTile("hotspot")).thenReturn(t);

        mContext.getOrCreateTestableResources().addOverride(R.string.quick_settings_tiles_stock,
                "hotspot");

        mTileQueryHelper.queryTiles(mQSTileHost);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        InOrder verifier = inOrder(t);
        verifier.verify(t).setTileSpec("hotspot");
        verifier.verify(t).destroy();
    }
}
