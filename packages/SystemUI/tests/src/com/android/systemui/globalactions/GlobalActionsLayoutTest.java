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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.MultiListLayout;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for {@link ListGridLayout}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GlobalActionsLayoutTest extends SysuiTestCase {

    private TestLayout mLayout;
    private TestAdapter mAdapter;

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

    private class TestLayout extends GlobalActionsLayout {
        ArrayList<View> mSeparatedViews = new ArrayList<>();
        ArrayList<View> mListViews = new ArrayList<>();
        boolean mSeparatedViewVisible = false;

        TestLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected boolean shouldReverseListItems() {
            return false;
        }

        @Override
        public float getAnimationOffsetX() {
            return 0;
        }

        @Override
        public float getAnimationOffsetY() {
            return 0;
        }

        @Override
        protected void addToListView(View v, boolean reverse) {
            if (reverse) {
                mListViews.add(0, v);
            } else {
                mListViews.add(v);
            }
        }

        @Override
        protected void addToSeparatedView(View v, boolean reverse) {
            if (reverse) {
                mSeparatedViews.add(0, v);
            } else {
                mSeparatedViews.add(v);
            }
        }

        @Override
        protected void setSeparatedViewVisibility(boolean visible) {
            mSeparatedViewVisible = visible;
        }
    }

    @Before
    public void setUp() throws Exception {
        mLayout = spy(new TestLayout(mContext, null));
        mAdapter = spy(new TestAdapter());
        mLayout.setAdapter(mAdapter);
    }

    @Test(expected = IllegalStateException.class)
    public void testOnUpdateList_noAdapter() {
        mLayout.setAdapter(null);
        mLayout.updateList();
    }

    @Test
    public void testOnUpdateList_noItems() {
        doReturn(0).when(mAdapter).countSeparatedItems();
        doReturn(0).when(mAdapter).countListItems();
        mLayout.updateList();

        assertEquals(0, mLayout.mSeparatedViews.size());
        assertEquals(0, mLayout.mListViews.size());

        assertEquals(false, mLayout.mSeparatedViewVisible);
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

        mLayout.updateList();

        assertEquals(1, mLayout.mSeparatedViews.size());
        assertEquals(1, mLayout.mListViews.size());
        assertEquals(view1, mLayout.mSeparatedViews.get(0));
        assertEquals(view2, mLayout.mListViews.get(0));
    }


    @Test
    public void testOnUpdateList_twoSeparatedItems() {
        doReturn(2).when(mAdapter).countSeparatedItems();
        doReturn(0).when(mAdapter).countListItems();
        View view1 = new View(mContext, null);
        View view2 = new View(mContext, null);

        doReturn(view1).when(mAdapter).getView(eq(0), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(0);
        doReturn(view2).when(mAdapter).getView(eq(1), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(1);

        mLayout.updateList();

        assertEquals(2, mLayout.mSeparatedViews.size());
        assertEquals(0, mLayout.mListViews.size());

        assertEquals(view1, mLayout.mSeparatedViews.get(0));
        assertEquals(view2, mLayout.mSeparatedViews.get(1));

        // if separated view has items in it, should be made visible
        assertEquals(true, mLayout.mSeparatedViewVisible);
    }

    @Test
    public void testOnUpdateList_twoSeparatedItems_reverse() {
        doReturn(2).when(mAdapter).countSeparatedItems();
        doReturn(0).when(mAdapter).countListItems();
        doReturn(true).when(mLayout).shouldReverseListItems();
        View view1 = new View(mContext, null);
        View view2 = new View(mContext, null);

        doReturn(view1).when(mAdapter).getView(eq(0), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(0);

        doReturn(view2).when(mAdapter).getView(eq(1), any(), any());
        doReturn(true).when(mAdapter).shouldBeSeparated(1);

        mLayout.updateList();

        assertEquals(2, mLayout.mSeparatedViews.size());
        assertEquals(0, mLayout.mListViews.size());

        // separated view items are not reversed in current implementation, and this is intentional!
        assertEquals(view1, mLayout.mSeparatedViews.get(0));
        assertEquals(view2, mLayout.mSeparatedViews.get(1));
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

        mLayout.updateList();

        assertEquals(0, mLayout.mSeparatedViews.size());
        assertEquals(4, mLayout.mListViews.size());
        assertEquals(view1, mLayout.mListViews.get(0));
        assertEquals(view2, mLayout.mListViews.get(1));
        assertEquals(view3, mLayout.mListViews.get(2));
        assertEquals(view4, mLayout.mListViews.get(3));
    }

    @Test
    public void testOnUpdateList_fourInList_reverse() {
        doReturn(0).when(mAdapter).countSeparatedItems();
        doReturn(4).when(mAdapter).countListItems();
        doReturn(true).when(mLayout).shouldReverseListItems();
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

        mLayout.updateList();

        assertEquals(0, mLayout.mSeparatedViews.size());
        assertEquals(4, mLayout.mListViews.size());
        assertEquals(view1, mLayout.mListViews.get(3));
        assertEquals(view2, mLayout.mListViews.get(2));
        assertEquals(view3, mLayout.mListViews.get(1));
        assertEquals(view4, mLayout.mListViews.get(0));
    }
}
