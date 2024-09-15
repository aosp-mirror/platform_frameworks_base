/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static junit.framework.Assert.assertEquals;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ListGridLayout}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ListGridLayoutTest extends SysuiTestCase {

    private ListGridLayout mListGridLayout;

    @Before
    public void setUp() throws Exception {
        GlobalActionsGridLayout globalActions = LayoutInflater.from(mContext)
                .inflate(R.layout.global_actions_grid, null)
                .requireViewById(R.id.global_actions_view);
        mListGridLayout = globalActions.getListView();
    }

    @Test
    public void testInflation() {
        assertEquals(3, mListGridLayout.getChildCount());
    }

    @Test
    public void testGetRowCount() {
        // above expected range
        mListGridLayout.setExpectedCount(99);
        assertEquals(3, mListGridLayout.getRowCount());

        mListGridLayout.setExpectedCount(9);
        assertEquals(3, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(8);
        assertEquals(3, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(7);
        assertEquals(3, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(6);
        assertEquals(2, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(5);
        assertEquals(2, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(4);
        assertEquals(2, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(3);
        assertEquals(1, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(2);
        assertEquals(1, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(1);
        assertEquals(1, mListGridLayout.getRowCount());
        mListGridLayout.setExpectedCount(0);
        assertEquals(0, mListGridLayout.getRowCount());

        // below expected range
        mListGridLayout.setExpectedCount(-1);
        assertEquals(0, mListGridLayout.getRowCount());
    }

    @Test
    public void testGetColumnCount() {
        // above expected range
        mListGridLayout.setExpectedCount(99);

        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(9);
        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(8);
        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(7);
        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(6);
        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(5);
        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(4);
        assertEquals(2, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(3);
        assertEquals(3, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(2);
        assertEquals(2, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(1);
        assertEquals(1, mListGridLayout.getColumnCount());
        mListGridLayout.setExpectedCount(0);
        assertEquals(0, mListGridLayout.getColumnCount());

        // below expected range
        mListGridLayout.setExpectedCount(-1);
        assertEquals(0, mListGridLayout.getColumnCount());
    }

    @Test
    public void testGetParentView_default() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, false, false));

        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(0, false, false));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(1, false, false));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(2, false, false));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(3, false, false));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(4, false, false));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(5, false, false));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(6, false, false));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(7, false, false));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(8, false, false));

        // above valid range
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(9, false, false));
    }

    @Test
    public void testGetParentView_reverseSublists() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, true, false));

        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(0, true, false));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(1, true, false));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(2, true, false));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(3, true, false));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(4, true, false));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(5, true, false));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(6, true, false));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(7, true, false));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(8, true, false));

        // above valid range
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(9, true, false));
    }

    @Test
    public void testGetParentView_swapRowsAndColumns() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, false, true));

        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(0, false, true));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(1, false, true));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(2, false, true));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(3, false, true));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(4, false, true));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(5, false, true));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(6, false, true));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(7, false, true));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(8, false, true));

        // above valid range
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(9, false, true));
    }

    @Test
    public void testGetParentView_swapRowsAndColumnsAndReverseSublists() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, true, true));

        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(0, true, true));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(1, true, true));
        assertEquals(mListGridLayout.getSublist(2),
                mListGridLayout.getParentView(2, true, true));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(3, true, true));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(4, true, true));
        assertEquals(mListGridLayout.getSublist(1),
                mListGridLayout.getParentView(5, true, true));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(6, true, true));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(7, true, true));
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(8, true, true));

        // above valid range
        assertEquals(mListGridLayout.getSublist(0),
                mListGridLayout.getParentView(9, true, true));
    }

    @Test
    public void testRemoveAllItems() {
        ViewGroup row1 = mListGridLayout.getSublist(0);
        row1.setVisibility(View.VISIBLE);
        ViewGroup row2 = mListGridLayout.getSublist(1);
        row2.setVisibility(View.VISIBLE);
        ViewGroup row3 = mListGridLayout.getSublist(2);
        row3.setVisibility(View.VISIBLE);
        View item1 = new View(mContext, null);
        View item2 = new View(mContext, null);
        View item3 = new View(mContext, null);

        row1.addView(item1);
        row2.addView(item2);
        row3.addView(item3);

        assertEquals(1, row1.getChildCount());
        assertEquals(1, row2.getChildCount());
        assertEquals(1, row3.getChildCount());

        mListGridLayout.removeAllItems();

        assertEquals(0, row1.getChildCount());
        assertEquals(View.GONE, row1.getVisibility());
        assertEquals(0, row2.getChildCount());
        assertEquals(View.GONE, row2.getVisibility());
        assertEquals(0, row3.getChildCount());
        assertEquals(View.GONE, row3.getVisibility());
    }

    @Test
    public void testAddItem() {
        mListGridLayout.setExpectedCount(4);

        View item1 = new View(mContext, null);
        View item2 = new View(mContext, null);
        View item3 = new View(mContext, null);
        View item4 = new View(mContext, null);

        mListGridLayout.addItem(item1);
        mListGridLayout.addItem(item2);
        mListGridLayout.addItem(item3);
        mListGridLayout.addItem(item4);
        assertEquals(2, mListGridLayout.getSublist(0).getChildCount());
        assertEquals(2, mListGridLayout.getSublist(1).getChildCount());
        assertEquals(0, mListGridLayout.getSublist(2).getChildCount());

        mListGridLayout.removeAllItems();
        mListGridLayout.addItem(item1);

        assertEquals(1, mListGridLayout.getSublist(0).getChildCount());
        assertEquals(0, mListGridLayout.getSublist(1).getChildCount());
        assertEquals(0, mListGridLayout.getSublist(2).getChildCount());
    }

    @Test
    public void testAddItem_reverseItems() {
        mListGridLayout.setExpectedCount(3);

        View item1 = new View(mContext, null);
        View item2 = new View(mContext, null);
        View item3 = new View(mContext, null);

        mListGridLayout.addItem(item1);
        mListGridLayout.addItem(item2);
        mListGridLayout.addItem(item3);

        ViewGroup sublist = mListGridLayout.getSublist(0);

        assertEquals(item1, sublist.getChildAt(0));
        assertEquals(item2, sublist.getChildAt(1));
        assertEquals(item3, sublist.getChildAt(2));


        mListGridLayout.removeAllItems();
        mListGridLayout.setReverseItems(true);

        mListGridLayout.addItem(item1);
        mListGridLayout.addItem(item2);
        mListGridLayout.addItem(item3);

        assertEquals(item3, sublist.getChildAt(0));
        assertEquals(item2, sublist.getChildAt(1));
        assertEquals(item1, sublist.getChildAt(2));
    }
}
