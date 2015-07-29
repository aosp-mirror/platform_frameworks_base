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
import android.view.KeyEvent;
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
    public void longPress_StartsSelectionMode() {
        mManager.onLongPress(7);
        assertSelection(7);
    }

    @Test
    public void longPress_SecondPressExtendsSelection() {
        mManager.onLongPress(7);
        mManager.onLongPress(99);
        assertSelection(7, 99);
    }

    @Test
    public void singleTapUp_DoesNotSelectBeforeLongPress() {
        mManager.onSingleTapUp(99, 0);
        assertSelection();
    }

    @Test
    public void singleTapUp_UnselectsSelectedItem() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(7, 0);
        assertSelection();
    }

    @Test
    public void singleTapUp_NoPositionClearsSelection() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(11, 0);
        mManager.onSingleTapUp(RecyclerView.NO_POSITION, 0);
        assertSelection();
    }

    @Test
    public void singleTapUp_ExtendsSelection() {
        mManager.onLongPress(99);
        mManager.onSingleTapUp(7, 0);
        mManager.onSingleTapUp(13, 0);
        mManager.onSingleTapUp(129899, 0);
        assertSelection(7, 99, 13, 129899);
    }

    @Test
    public void singleTapUp_ShiftCreatesRangeSelection() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(17, KeyEvent.META_SHIFT_ON);
        assertRangeSelection(7, 17);
    }

    @Test
    public void singleTapUp_ShiftCreatesRangeSeletion_Backwards() {
        mManager.onLongPress(17);
        mManager.onSingleTapUp(7, KeyEvent.META_SHIFT_ON);
        assertRangeSelection(7, 17);
    }

    @Test
    public void singleTapUp_SecondShiftClickExtendsSelection() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(11, KeyEvent.META_SHIFT_ON);
        mManager.onSingleTapUp(17, KeyEvent.META_SHIFT_ON);
        assertRangeSelection(7, 17);
    }

    @Test
    public void singleTapUp_MultipleContiguousRangesSelected() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(11, KeyEvent.META_SHIFT_ON);
        mManager.onSingleTapUp(20, 0);
        mManager.onSingleTapUp(25, KeyEvent.META_SHIFT_ON);
        assertRangeSelected(7, 11);
        assertRangeSelected(20, 25);
        assertSelectionSize(11);
    }

    @Test
    public void singleTapUp_ShiftReducesSelectionRange_FromPreviousShiftClick() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(17, KeyEvent.META_SHIFT_ON);
        mManager.onSingleTapUp(10, KeyEvent.META_SHIFT_ON);
        assertRangeSelection(7, 10);
    }

    @Test
    public void singleTapUp_ShiftReducesSelectionRange_FromPreviousShiftClick_Backwards() {
        mManager.onLongPress(17);
        mManager.onSingleTapUp(7, KeyEvent.META_SHIFT_ON);
        mManager.onSingleTapUp(14, KeyEvent.META_SHIFT_ON);
        assertRangeSelection(14, 17);
    }


    @Test
    public void singleTapUp_ShiftReversesSelectionDirection() {
        mManager.onLongPress(7);
        mManager.onSingleTapUp(17, KeyEvent.META_SHIFT_ON);
        mManager.onSingleTapUp(0, KeyEvent.META_SHIFT_ON);
        assertRangeSelection(0, 7);
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

    private void assertRangeSelected(int begin, int end) {
        for (int i = begin; i <= end; i++) {
            assertSelected(i);
        }
    }

    private void assertRangeSelection(int begin, int end) {
        assertSelectionSize(end - begin + 1);
        assertRangeSelected(begin, end);
    }

    private void assertSelectionSize(int expected) {
        Selection selection = mManager.getSelection();
        assertEquals(selection.toString(), expected, selection.size());
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
