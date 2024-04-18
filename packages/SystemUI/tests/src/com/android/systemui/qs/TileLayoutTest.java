/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.qs;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.testing.TestableLooper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tileimpl.HeightOverrideable;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class TileLayoutTest extends SysuiTestCase {
    private Resources mResources;
    private int mLayoutSizeForOneTile;
    private TileLayout mTileLayout; // under test
    private Context mSpyContext;


    @Before
    public void setUp() throws Exception {
        mSpyContext = Mockito.spy(
                new ContextThemeWrapper(mContext, R.style.Theme_SystemUI_QuickSettings));
        mResources = Mockito.spy(mSpyContext.getResources());
        when(mSpyContext.getResources()).thenReturn(mResources);

        mTileLayout = new TileLayout(mSpyContext);
        // Layout needs to leave space for the tile margins. Three times the margin size is
        // sufficient for any number of columns.
        mLayoutSizeForOneTile =
                mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal) * 3;
    }

    private QSPanelControllerBase.TileRecord createTileRecord() {
        return new QSPanelControllerBase.TileRecord(
                mock(QSTile.class),
                spy(new QSTileViewImpl(mSpyContext)));
    }

    @Test
    public void testAddTile_CallsSetListeningOnTile() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, false);
    }

    @Test
    public void testAddTile_SetsRightSquishiness() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        ((HeightOverrideable) tileRecord.tileView).setSquishinessFraction(.5f);
        mTileLayout.addTile(tileRecord);
        assertEquals(1f, ((HeightOverrideable) tileRecord.tileView).getSquishinessFraction());
    }

    @Test
    public void testSetListening_CallsSetListeningOnTile() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        mTileLayout.setListening(true, null);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, true);
    }

    @Test
    public void testSetListening_SameValueIsNoOp() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        mTileLayout.setListening(false, null);
        verify(tileRecord.tile, times(1)).setListening(any(), anyBoolean());
    }

    @Test
    public void testSetListening_ChangesValueForAddingFutureTiles() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.setListening(true, null);
        mTileLayout.addTile(tileRecord);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, true);
    }

    @Test
    public void testRemoveTile_CallsSetListeningFalseOnTile() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.setListening(true, null);
        mTileLayout.addTile(tileRecord);
        mTileLayout.removeTile(tileRecord);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, false);
    }

    @Test
    public void testRemoveAllViews_CallsSetListeningFalseOnAllTiles() {
        QSPanelControllerBase.TileRecord tileRecord1 = createTileRecord();
        QSPanelControllerBase.TileRecord tileRecord2 = createTileRecord();
        mTileLayout.setListening(true, null);
        mTileLayout.addTile(tileRecord1);
        mTileLayout.addTile(tileRecord2);
        mTileLayout.removeAllViews();
        verify(tileRecord1.tile, times(1)).setListening(mTileLayout, false);
        verify(tileRecord2.tile, times(1)).setListening(mTileLayout, false);
    }

    @Test
    public void testMeasureLayout_CallsLayoutOnTile() {
        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        mTileLayout.measure(mLayoutSizeForOneTile, mLayoutSizeForOneTile);
        mTileLayout.layout(0, 0, mLayoutSizeForOneTile, mLayoutSizeForOneTile);
        verify(tileRecord.tileView, times(1)).layout(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testMeasureLayout_CallsLayoutOnTilesWithNeighboredBounds() {
        QSPanelControllerBase.TileRecord tileRecord1 = createTileRecord();
        QSPanelControllerBase.TileRecord tileRecord2 = createTileRecord();
        mTileLayout.addTile(tileRecord1);
        mTileLayout.addTile(tileRecord2);
        mTileLayout.measure(mLayoutSizeForOneTile * 2, mLayoutSizeForOneTile * 2);
        mTileLayout.layout(0, 0, mLayoutSizeForOneTile * 2, mLayoutSizeForOneTile * 2);

        // Capture the layout calls for both tiles.
        ArgumentCaptor<Integer> left1 = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> top1 = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> right1 = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> bottom1 = ArgumentCaptor.forClass(Integer.class);
        verify(tileRecord1.tileView, times(1))
                .layout(left1.capture(), top1.capture(), right1.capture(), bottom1.capture());
        ArgumentCaptor<Integer> left2 = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> top2 = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> right2 = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> bottom2 = ArgumentCaptor.forClass(Integer.class);
        verify(tileRecord2.tileView, times(1))
                .layout(left2.capture(), top2.capture(), right2.capture(), bottom2.capture());

        // We assume two tiles will always fit side-by-side.
        assertTrue(mSpyContext.getResources().getInteger(R.integer.quick_settings_num_columns) > 1);

        // left <= right, top <= bottom
        assertTrue(left1.getValue() <= right1.getValue());
        assertTrue(top1.getValue() <= bottom1.getValue());
        assertTrue(left2.getValue() <= right2.getValue());
        assertTrue(top2.getValue() <= bottom2.getValue());

        // The tiles' left and right should describe discrete ranges.
        // Agnostic of Layout direction.
        assertTrue(left1.getValue() > right2.getValue() || right1.getValue() < left2.getValue());

        // The tiles' Top and Bottom should be the same.
        assertEquals(top1.getValue().intValue(), top2.getValue().intValue());
        assertEquals(bottom1.getValue().intValue(), bottom2.getValue().intValue());
    }

    @Test
    public void testEmptyHeight() {
        mTileLayout.measure(mLayoutSizeForOneTile, mLayoutSizeForOneTile);
        assertEquals(0, mTileLayout.getMeasuredHeight());
    }

    @Test
    public void testCollectionInfo() {
        QSPanelControllerBase.TileRecord tileRecord1 = createTileRecord();
        QSPanelControllerBase.TileRecord tileRecord2 = createTileRecord();
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(mTileLayout);
        mTileLayout.addTile(tileRecord1);

        mTileLayout.onInitializeAccessibilityNodeInfo(info);
        AccessibilityNodeInfo.CollectionInfo collectionInfo = info.getCollectionInfo();
        assertEquals(1, collectionInfo.getRowCount());
        assertEquals(1, collectionInfo.getColumnCount()); // always use one column

        mTileLayout.addTile(tileRecord2);
        mTileLayout.onInitializeAccessibilityNodeInfo(info);
        collectionInfo = info.getCollectionInfo();
        assertEquals(2, collectionInfo.getRowCount());
        assertEquals(1, collectionInfo.getColumnCount()); // always use one column
    }

    @Test
    public void testSetPositionOnTiles() {
        QSPanelControllerBase.TileRecord tileRecord1 = createTileRecord();
        QSPanelControllerBase.TileRecord tileRecord2 = createTileRecord();
        mTileLayout.addTile(tileRecord1);
        mTileLayout.addTile(tileRecord2);
        mTileLayout.measure(mLayoutSizeForOneTile * 2, mLayoutSizeForOneTile * 2);
        mTileLayout.layout(0, 0, mLayoutSizeForOneTile * 2, mLayoutSizeForOneTile * 2);

        verify(tileRecord1.tileView).setPosition(0);
        verify(tileRecord2.tileView).setPosition(1);
    }

    @Test
    public void resourcesChanged_updateResources_returnsTrue() {
        when(mResources.getInteger(R.integer.quick_settings_num_columns)).thenReturn(1);
        mTileLayout.updateResources(); // setup with 1
        when(mResources.getInteger(R.integer.quick_settings_num_columns)).thenReturn(2);

        assertEquals(true, mTileLayout.updateResources());
    }

    @Test
    public void resourcesSame_updateResources_returnsFalse() {
        when(mResources.getInteger(R.integer.quick_settings_num_columns)).thenReturn(1);
        mTileLayout.updateResources(); // setup with 1

        assertEquals(false, mTileLayout.updateResources());
    }

    @Test
    public void fontScalingChanged_updateResources_cellHeightEnoughForTileContent() {
        final float originalFontScale = mContext.getResources().getConfiguration().fontScale;
        float[] testScales = {0.8f, 1.0f, 1.4f, 1.6f, 2.0f};
        for (float scale: testScales) {
            changeFontScaling_updateResources_cellHeightEnoughForTileContent(scale);
        }

        changeFontScaling(originalFontScale);
    }

    private void changeFontScaling_updateResources_cellHeightEnoughForTileContent(float scale) {
        changeFontScaling(scale);

        QSPanelControllerBase.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);

        FakeTileView tileView = new FakeTileView(mSpyContext);
        QSTile.State state = new QSTile.State();
        state.label = "TEST LABEL";
        state.secondaryLabel = "TEST SECONDARY LABEL";
        tileView.changeState(state);

        mTileLayout.updateResources();

        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        tileView.measure(spec, spec);
        assertTrue(mTileLayout.getCellHeight() >= tileView.getMeasuredHeight());

        mTileLayout.removeTile(tileRecord);
    }

    private static class FakeTileView extends QSTileViewImpl {
        FakeTileView(Context context) {
            super(context, /* collapsed= */ false);
        }

        void changeState(QSTile.State state) {
            handleStateChanged(state);
        }
    }

    private void changeFontScaling(float scale) {
        Configuration configuration =
                new Configuration(mSpyContext.getResources().getConfiguration());
        configuration.fontScale = scale;
        // updateConfiguration could help update on both resource configuration and displayMetrics
        mSpyContext.getResources().updateConfiguration(configuration, null, null);
    }
}
