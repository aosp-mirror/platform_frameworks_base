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

import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;
import com.android.systemui.qs.tileimpl.QSTileView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileLayoutTest extends SysuiTestCase {
    private TileLayout mTileLayout;
    private int mLayoutSizeForOneTile;

    @Before
    public void setUp() throws Exception {
        mTileLayout = new TileLayout(mContext);
        // Layout needs to leave space for the tile margins. Three times the margin size is
        // sufficient for any number of columns.
        mLayoutSizeForOneTile =
                mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal) * 3;
    }

    private QSPanel.TileRecord createTileRecord() {
        QSPanel.TileRecord tileRecord = new QSPanel.TileRecord();
        tileRecord.tile = mock(QSTile.class);
        tileRecord.tileView = spy(new QSTileView(mContext, new QSIconViewImpl(mContext)));
        return tileRecord;
    }

    @Test
    public void testAddTile_CallsSetListeningOnTile() {
        QSPanel.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, false);
    }

    @Test
    public void testSetListening_CallsSetListeningOnTile() {
        QSPanel.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        mTileLayout.setListening(true);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, true);
    }

    @Test
    public void testSetListening_SameValueIsNoOp() {
        QSPanel.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        mTileLayout.setListening(false);
        verify(tileRecord.tile, times(1)).setListening(any(), anyBoolean());
    }

    @Test
    public void testSetListening_ChangesValueForAddingFutureTiles() {
        QSPanel.TileRecord tileRecord = createTileRecord();
        mTileLayout.setListening(true);
        mTileLayout.addTile(tileRecord);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, true);
    }

    @Test
    public void testRemoveTile_CallsSetListeningFalseOnTile() {
        QSPanel.TileRecord tileRecord = createTileRecord();
        mTileLayout.setListening(true);
        mTileLayout.addTile(tileRecord);
        mTileLayout.removeTile(tileRecord);
        verify(tileRecord.tile, times(1)).setListening(mTileLayout, false);
    }

    @Test
    public void testRemoveAllViews_CallsSetListeningFalseOnAllTiles() {
        QSPanel.TileRecord tileRecord1 = createTileRecord();
        QSPanel.TileRecord tileRecord2 = createTileRecord();
        mTileLayout.setListening(true);
        mTileLayout.addTile(tileRecord1);
        mTileLayout.addTile(tileRecord2);
        mTileLayout.removeAllViews();
        verify(tileRecord1.tile, times(1)).setListening(mTileLayout, false);
        verify(tileRecord2.tile, times(1)).setListening(mTileLayout, false);
    }

    @Test
    public void testMeasureLayout_CallsLayoutOnTile() {
        QSPanel.TileRecord tileRecord = createTileRecord();
        mTileLayout.addTile(tileRecord);
        mTileLayout.measure(mLayoutSizeForOneTile, mLayoutSizeForOneTile);
        mTileLayout.layout(0, 0, mLayoutSizeForOneTile, mLayoutSizeForOneTile);
        verify(tileRecord.tileView, times(1)).layout(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testMeasureLayout_CallsLayoutOnTilesWithNeighboredBounds() {
        QSPanel.TileRecord tileRecord1 = createTileRecord();
        QSPanel.TileRecord tileRecord2 = createTileRecord();
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
        assertTrue(mContext.getResources().getInteger(R.integer.quick_settings_num_columns) > 1);

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
}
