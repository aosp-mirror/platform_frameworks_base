/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import static org.junit.Assert.*;

import android.support.v7.widget.RecyclerView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.documentsui.MultiSelectManager.RecyclerViewHelper;
import com.android.documentsui.MultiSelectManager.Selection;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MultiSelectManagerTest {

    private static final List<String> items;
    static {
        items = new ArrayList<String>();
        items.add("aaa");
        items.add("bbb");
        items.add("ccc");
        items.add("111");
        items.add("222");
        items.add("333");
    }

    private MultiSelectManager mManager;
    private TestAdapter mAdapter;
    private TestCallback mCallback;

    private EventHelper mEventHelper;

    @Before
    public void setUp() throws Exception {
        mAdapter = new TestAdapter(items);
        mCallback = new TestCallback();
        mEventHelper = new EventHelper();
        mManager = new MultiSelectManager(mAdapter, mEventHelper);
        mManager.addCallback(mCallback);
    }

    @Test
    public void singleTapDoesNotSelectBeforeLongPress() {
        mManager.onSingleTapUp(99);
        assertSelection();
    }

    @Test
    public void longPressStartsSelectionMode() {
        mManager.onLongPress(7);
        assertSelection(7);
    }

    @Test
    public void secondLongPressExtendsSelection() {
        mManager.onLongPress(7);
        mManager.onLongPress(99);
        assertSelection(7, 99);
    }

    @Test
    public void singleTapUnselectedLastItem() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(7);
        assertSelection();
    }

    @Test
    public void singleTapUpExtendsSelection() {
        mManager.onLongPress(99);
        mManager.onSingleTapUp(7);
        mManager.onSingleTapUp(13);
        mManager.onSingleTapUp(129899);
        assertSelection(7, 99, 13, 129899);
    }

    private void assertSelected(int... expected) {
        for (int i = 0; i < expected.length; i++) {
            Selection selection = mManager.getSelection();
            String err = String.format(
                    "Selection %s does not contain %d", selection, expected[i]);
            assertTrue(err, selection.contains(expected[i]));
        }
    }

    private void assertSelection(int... expected) {
        assertSelectionSize(expected.length);
        assertSelected(expected);
    }

    private void assertSelectionSize(int expected) {
        Selection selection = mManager.getSelection();
        assertEquals(expected, selection.size());
    }

    private static final class EventHelper implements RecyclerViewHelper {
        @Override
        public int findEventPosition(MotionEvent e) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestCallback implements MultiSelectManager.Callback {

        Set<Integer> ignored = new HashSet<>();
        private int mLastChangedPosition;
        private boolean mLastChangedSelected;

        @Override
        public void onItemStateChanged(int position, boolean selected) {
            this.mLastChangedPosition = position;
            this.mLastChangedSelected = selected;
        }

        @Override
        public boolean onBeforeItemStateChange(int position, boolean selected) {
            return !ignored.contains(position);
        }
    }

    private static final class TestHolder extends RecyclerView.ViewHolder {
        // each data item is just a string in this case
        public View view;
        public String string;
        public TestHolder(View view) {
            super(view);
            this.view = view;
        }
    }

    private static final class TestAdapter extends RecyclerView.Adapter<TestHolder> {

        private List<String> mItems;

        public TestAdapter(List<String> items) {
            mItems = items;
        }

        @Override
        public TestHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new TestHolder(Mockito.mock(ViewGroup.class));
        }

        @Override
        public void onBindViewHolder(TestHolder holder, int position) {}

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
