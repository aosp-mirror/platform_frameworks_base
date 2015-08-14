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
        mManager = new MultiSelectManager(mAdapter, mEventHelper, MultiSelectManager.MODE_MULTIPLE);
        mManager.addCallback(mCallback);
    }

    @Test
    public void mouseClick_StartsSelectionMode() {
        click(7);
        assertSelection(7);
    }

    @Test
    public void mouseClick_ShiftClickExtendsSelection() {
        click(7);
        shiftClick(11);
        assertRangeSelection(7, 11);
    }

    @Test
    public void mouseClick_NoPosition_ClearsSelection() {
        mManager.onLongPress(7);
        click(11);
        click(RecyclerView.NO_POSITION);
        assertSelection();
    }

    @Test
    public void setSelectionFocusBegin() {
        mManager.setItemSelected(7, true);
        mManager.setSelectionFocusBegin(7);
        shiftClick(11);
        assertRangeSelection(7, 11);
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
    public void singleTapUp_UnselectsSelectedItem() {
        mManager.onLongPress(7);
        tap(7);
        assertSelection();
    }

    @Test
    public void singleTapUp_NoPosition_ClearsSelection() {
        mManager.onLongPress(7);
        tap(11);
        tap(RecyclerView.NO_POSITION);
        assertSelection();
    }

    @Test
    public void singleTapUp_ExtendsSelection() {
        mManager.onLongPress(99);
        tap(7);
        tap(13);
        tap(129899);
        assertSelection(7, 99, 13, 129899);
    }

    @Test
    public void singleTapUp_ShiftCreatesRangeSelection() {
        mManager.onLongPress(7);
        shiftTap(17);
        assertRangeSelection(7, 17);
    }

    @Test
    public void singleTapUp_ShiftCreatesRangeSeletion_Backwards() {
        mManager.onLongPress(17);
        shiftTap(7);
        assertRangeSelection(7, 17);
    }

    @Test
    public void singleTapUp_SecondShiftClickExtendsSelection() {
        mManager.onLongPress(7);
        shiftTap(11);
        shiftTap(17);
        assertRangeSelection(7, 17);
    }

    @Test
    public void singleTapUp_MultipleContiguousRangesSelected() {
        mManager.onLongPress(7);
        shiftTap(11);
        tap(20);
        shiftTap(25);
        assertRangeSelected(7, 11);
        assertRangeSelected(20, 25);
        assertSelectionSize(11);
    }

    @Test
    public void singleTapUp_ShiftReducesSelectionRange_FromPreviousShiftClick() {
        mManager.onLongPress(7);
        shiftTap(17);
        shiftTap(10);
        assertRangeSelection(7, 10);
    }

    @Test
    public void singleTapUp_ShiftReducesSelectionRange_FromPreviousShiftClick_Backwards() {
        mManager.onLongPress(17);
        shiftTap(7);
        shiftTap(14);
        assertRangeSelection(14, 17);
    }


    @Test
    public void singleTapUp_ShiftReversesSelectionDirection() {
        mManager.onLongPress(7);
        shiftTap(17);
        shiftTap(0);
        assertRangeSelection(0, 7);
    }

    @Test
    public void singleSelectMode() {
        mManager = new MultiSelectManager(mAdapter, mEventHelper, MultiSelectManager.MODE_SINGLE);
        mManager.addCallback(mCallback);
        tap(20);
        tap(13);
        assertSelection(13);
    }

    @Test
    public void singleSelectMode_ShiftTap() {
        mManager = new MultiSelectManager(mAdapter, mEventHelper, MultiSelectManager.MODE_SINGLE);
        mManager.addCallback(mCallback);
        tap(13);
        shiftTap(20);
        assertSelection(20);
    }

    private void tap(int position) {
        mManager.onSingleTapUp(position, 0, MotionEvent.TOOL_TYPE_MOUSE);
    }

    private void shiftTap(int position) {
        mManager.onSingleTapUp(position, KeyEvent.META_SHIFT_ON, MotionEvent.TOOL_TYPE_FINGER);
    }

    private void click(int position) {
        mManager.onSingleTapUp(position, 0, MotionEvent.TOOL_TYPE_MOUSE);
    }

    private void shiftClick(int position) {
        mManager.onSingleTapUp(position, KeyEvent.META_SHIFT_ON, MotionEvent.TOOL_TYPE_MOUSE);
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

        @Override
        public void onSelectionChanged() {}
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
