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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.MultiListLayout;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.leak.RotationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ListGridLayout}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class GlobalActionsGridLayoutTest extends SysuiTestCase {

    private GlobalActionsGridLayout mGridLayout;
    private TestAdapter mAdapter;
    private ListGridLayout mListGrid;

    private class TestAdapter extends MultiListLayout.MultiListAdapter {
        @Override
        public void onClickItem(int index) { }

        @Override
        public boolean onLongClickItem(int index) {
            return true;
        }

        @Override
        public int countSeparatedItems() {
            return -1;
        }

        @Override
        public int countListItems() {
            return -1;
        }

        @Override
        public boolean shouldBeSeparated(int position) {
            return false;
        }

        @Override
        public int getCount() {
            return countSeparatedItems() + countListItems();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return -1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return null;
        }
    }


    @Before
    public void setUp() throws Exception {
        mGridLayout = spy((GlobalActionsGridLayout)
                LayoutInflater.from(mContext).inflate(R.layout.global_actions_grid, null));
        mAdapter = spy(new TestAdapter());
        mGridLayout.setAdapter(mAdapter);
        mListGrid = spy(mGridLayout.getListView());
        doReturn(mListGrid).when(mGridLayout).getListView();
    }

    @Test
    public void testShouldSwapRowsAndColumns() {
        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldSwapRowsAndColumns());

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldSwapRowsAndColumns());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldSwapRowsAndColumns());
    }

    @Test
    public void testShouldReverseListItems() {
        doReturn(View.LAYOUT_DIRECTION_LTR).when(mGridLayout).getLayoutDirection();

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseListItems());

        doReturn(View.LAYOUT_DIRECTION_RTL).when(mGridLayout).getLayoutDirection();

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseListItems());
    }

    @Test
    public void testShouldReverseSublists() {
        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseSublists());

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseSublists());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseSublists());
    }

    @Test
    public void testGetAnimationOffsetX() {
        doReturn(50f).when(mGridLayout).getAnimationDistance();

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(0f, mGridLayout.getAnimationOffsetX(), .01);

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(50f, mGridLayout.getAnimationOffsetX(), .01);

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(-50f, mGridLayout.getAnimationOffsetX(), .01);
    }

    @Test
    public void testGetAnimationOffsetY() {
        doReturn(50f).when(mGridLayout).getAnimationDistance();

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(50f, mGridLayout.getAnimationOffsetY(), .01);

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(0f, mGridLayout.getAnimationOffsetY(), .01);

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(0f, mGridLayout.getAnimationOffsetY(), .01);
    }

    @Test(expected = IllegalStateException.class)
    public void testOnUpdateList_noAdapter() {
        mGridLayout.setAdapter(null);
        mGridLayout.updateList();
    }

    @Test
    public void testOnUpdateList_noItems() {
        doReturn(0).when(mAdapter).countSeparatedItems();
        doReturn(0).when(mAdapter).countListItems();
        mGridLayout.updateList();

        ViewGroup separatedView = mGridLayout.getSeparatedView();
        ListGridLayout listView = mGridLayout.getListView();

        assertEquals(0, separatedView.getChildCount());
        assertEquals(View.GONE, separatedView.getVisibility());

        verify(mListGrid, times(0)).addItem(any());
    }

    @Test
    public void testOnUpdateList_resizesFirstSeparatedItem() {
        doReturn(1).when(mAdapter).countSeparatedItems();
        doReturn(0).when(mAdapter).countListItems();
        View firstView = new View(mContext, null);
        View secondView = new View(mContext, null);

        doReturn(firstView).when(mAdapter).getView(eq(0), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(0);

        mGridLayout.updateList();

        ViewGroup.LayoutParams childParams = firstView.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, childParams.width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, childParams.height);

        doReturn(2).when(mAdapter).countSeparatedItems();
        doReturn(secondView).when(mAdapter).getView(eq(1), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(1);

        mGridLayout.updateList();

        childParams = firstView.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, childParams.width);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, childParams.height);


    }

    @Test
    public void testOnUpdateList_onlySeparatedItems() {
        doReturn(1).when(mAdapter).countSeparatedItems();
        doReturn(0).when(mAdapter).countListItems();
        View testView = new View(mContext, null);
        doReturn(testView).when(mAdapter).getView(eq(0), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(0);

        mGridLayout.updateList();

        verify(mListGrid, times(0)).addItem(any());
    }

    @Test
    public void testOnUpdateList_oneSeparatedOneList() {
        doReturn(1).when(mAdapter).countSeparatedItems();
        doReturn(1).when(mAdapter).countListItems();
        View view1 = new View(mContext, null);
        View view2 = new View(mContext, null);

        doReturn(view1).when(mAdapter).getView(eq(0), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(0);

        doReturn(view2).when(mAdapter).getView(eq(1), any(), any());
        doReturn(false).when(mAdapter).shouldBeSeparated(1);

        mGridLayout.updateList();

        ViewGroup separatedView = mGridLayout.getSeparatedView();

        assertEquals(1, separatedView.getChildCount());
        assertEquals(View.VISIBLE, separatedView.getVisibility());
        assertEquals(view1, separatedView.getChildAt(0));

        verify(mListGrid, times(1)).addItem(view2);
    }

    @Test
    public void testOnUpdateList_fourInList() {
        doReturn(0).when(mAdapter).countSeparatedItems();
        doReturn(4).when(mAdapter).countListItems();
        View view1 = new View(mContext, null);
        View view2 = new View(mContext, null);
        View view3 = new View(mContext, null);
        View view4 = new View(mContext, null);

        doReturn(view1).when(mAdapter).getView(eq(0), any(), any());
        doReturn(false).when(mAdapter).shouldBeSeparated(0);

        doReturn(view2).when(mAdapter).getView(eq(1), any(), any());
        doReturn(false).when(mAdapter).shouldBeSeparated(1);

        doReturn(view3).when(mAdapter).getView(eq(2), any(), any());
        doReturn(false).when(mAdapter).shouldBeSeparated(2);

        doReturn(view4).when(mAdapter).getView(eq(3), any(), any());
        doReturn(false).when(mAdapter).shouldBeSeparated(3);

        mGridLayout.updateList();

        ViewGroup separatedView = mGridLayout.getSeparatedView();
        assertEquals(0, separatedView.getChildCount());
        assertEquals(View.GONE, separatedView.getVisibility());

        verify(mListGrid, times(1)).addItem(view1);
        verify(mListGrid, times(1)).addItem(view2);
        verify(mListGrid, times(1)).addItem(view3);
        verify(mListGrid, times(1)).addItem(view4);
    }
}
