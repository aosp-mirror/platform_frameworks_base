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

import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ListGridLayout}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ListGridLayoutTest extends SysuiTestCase {

    private ListGridLayout mListGridLayout;

    @Before
    public void setUp() throws Exception {
        GlobalActionsGridLayout globalActions = (GlobalActionsGridLayout)
                LayoutInflater.from(mContext).inflate(R.layout.global_actions_grid, null);
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

        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(0, false, false));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(1, false, false));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(2, false, false));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(3, false, false));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(4, false, false));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(5, false, false));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(6, false, false));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(7, false, false));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(8, false, false));

        // above valid range
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(9, false, false));
    }

    @Test
    public void testGetParentView_reverseSublists() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, true, false));

        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(0, true, false));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(1, true, false));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(2, true, false));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(3, true, false));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(4, true, false));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(5, true, false));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(6, true, false));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(7, true, false));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(8, true, false));

        // above valid range
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(9, true, false));
    }

    @Test
    public void testGetParentView_swapRowsAndColumns() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, false, true));

        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(0, false, true));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(1, false, true));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(2, false, true));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(3, false, true));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(4, false, true));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(5, false, true));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(6, false, true));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(7, false, true));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(8, false, true));

        // above valid range
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(9, false, true));
    }

    @Test
    public void testGetParentView_swapRowsAndColumnsAndReverseSublists() {
        mListGridLayout.setExpectedCount(9);

        // below valid range
        assertEquals(null,
                mListGridLayout.getParentView(-1, true, true));

        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(0, true, true));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(1, true, true));
        assertEquals(mListGridLayout.getChildAt(2),
                mListGridLayout.getParentView(2, true, true));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(3, true, true));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(4, true, true));
        assertEquals(mListGridLayout.getChildAt(1),
                mListGridLayout.getParentView(5, true, true));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(6, true, true));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(7, true, true));
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(8, true, true));

        // above valid range
        assertEquals(mListGridLayout.getChildAt(0),
                mListGridLayout.getParentView(9, true, true));
    }

    @Test
    public void testRemoveAllItems() {
        ViewGroup row1 = (ViewGroup) mListGridLayout.getChildAt(0);
        ViewGroup row2 = (ViewGroup) mListGridLayout.getChildAt(1);
        ViewGroup row3 = (ViewGroup) mListGridLayout.getChildAt(2);
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
        assertEquals(0, row2.getChildCount());
        assertEquals(0, row2.getChildCount());
    }
}
