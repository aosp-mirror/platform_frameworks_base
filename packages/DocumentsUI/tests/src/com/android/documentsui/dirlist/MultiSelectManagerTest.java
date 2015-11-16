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

package com.android.documentsui.dirlist;

import android.support.v7.widget.RecyclerView;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;

import com.android.documentsui.TestInputEvent;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;

import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
public class MultiSelectManagerTest extends AndroidTestCase {

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
    private TestSelectionEnvironment mEnv;

    public void setUp() throws Exception {
        mAdapter = new TestAdapter(items);
        mCallback = new TestCallback();
        mEnv = new TestSelectionEnvironment();
        mManager = new MultiSelectManager(mAdapter, mEnv, MultiSelectManager.MODE_MULTIPLE);
        mManager.addCallback(mCallback);
    }

    public void testMouseClick_StartsSelectionMode() {
        click(7);
        assertSelection(7);
    }

    public void testMouseClick_NotifiesSelectionChanged() {
        click(7);
        mCallback.assertSelectionChanged();
    }

    public void testMouseClick_ShiftClickExtendsSelection() {
        longPress(7);
        shiftClick(11);
        assertRangeSelection(7, 11);
    }

    public void testMouseClick_NoPosition_ClearsSelection() {
        longPress(7);
        click(11);
        click(RecyclerView.NO_POSITION);
        assertSelection();
    }

    public void testSetSelectionFocusBegin() {
        mManager.setItemSelected(7, true);
        mManager.setSelectionFocusBegin(7);
        shiftClick(11);
        assertRangeSelection(7, 11);
    }

    public void testLongPress_StartsSelectionMode() {
        longPress(7);
        assertSelection(7);
    }

    public void testLongPress_SecondPressExtendsSelection() {
        longPress(7);
        longPress(99);
        assertSelection(7, 99);
    }

    public void testSingleTapUp_UnselectsSelectedItem() {
        longPress(7);
        tap(7);
        assertSelection();
    }

    public void testSingleTapUp_NoPosition_ClearsSelection() {
        longPress(7);
        tap(11);
        tap(RecyclerView.NO_POSITION);
        assertSelection();
    }

    public void testSingleTapUp_ExtendsSelection() {
        longPress(99);
        tap(7);
        tap(13);
        tap(129899);
        assertSelection(7, 99, 13, 129899);
    }

    public void testSingleTapUp_ShiftCreatesRangeSelection() {
        longPress(7);
        shiftTap(17);
        assertRangeSelection(7, 17);
    }

    public void testSingleTapUp_ShiftCreatesRangeSeletion_Backwards() {
        longPress(17);
        shiftTap(7);
        assertRangeSelection(7, 17);
    }

    public void testSingleTapUp_SecondShiftClickExtendsSelection() {
        longPress(7);
        shiftTap(11);
        shiftTap(17);
        assertRangeSelection(7, 17);
    }

    public void testSingleTapUp_MultipleContiguousRangesSelected() {
        longPress(7);
        shiftTap(11);
        tap(20);
        shiftTap(25);
        assertRangeSelected(7, 11);
        assertRangeSelected(20, 25);
        assertSelectionSize(11);
    }

    public void testSingleTapUp_ShiftReducesSelectionRange_FromPreviousShiftClick() {
        longPress(7);
        shiftTap(17);
        shiftTap(10);
        assertRangeSelection(7, 10);
    }

    public void testSingleTapUp_ShiftReducesSelectionRange_FromPreviousShiftClick_Backwards() {
        mManager.onLongPress(TestInputEvent.tap(17));
        shiftTap(7);
        shiftTap(14);
        assertRangeSelection(14, 17);
    }

    public void testSingleTapUp_ShiftReversesSelectionDirection() {
        longPress(7);
        shiftTap(17);
        shiftTap(0);
        assertRangeSelection(0, 7);
    }

    public void testSingleSelectMode() {
        mManager = new MultiSelectManager(mAdapter, mEnv, MultiSelectManager.MODE_SINGLE);
        mManager.addCallback(mCallback);
        longPress(20);
        tap(13);
        assertSelection(13);
    }

    public void testSingleSelectMode_ShiftTap() {
        mManager = new MultiSelectManager(mAdapter, mEnv, MultiSelectManager.MODE_SINGLE);
        mManager.addCallback(mCallback);
        longPress(13);
        shiftTap(20);
        assertSelection(20);
    }

    public void testSingleSelectMode_ShiftDoesNotExtendSelection() {
        mManager = new MultiSelectManager(mAdapter, mEnv, MultiSelectManager.MODE_SINGLE);
        mManager.addCallback(mCallback);
        longPress(20);
        keyToPosition(22, true);
        assertSelection(22);
    }

    public void testProvisionalSelection() {
        Selection s = mManager.getSelection();
        assertSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(1, true);
        provisional.append(2, true);
        s.setProvisionalSelection(provisional);
        assertSelection(1, 2);

        provisional.delete(1);
        provisional.append(3, true);
        s.setProvisionalSelection(provisional);
        assertSelection(2, 3);

        s.applyProvisionalSelection();
        assertSelection(2, 3);

        provisional.clear();
        provisional.append(3, true);
        provisional.append(4, true);
        s.setProvisionalSelection(provisional);
        assertSelection(2, 3, 4);

        provisional.delete(3);
        s.setProvisionalSelection(provisional);
        assertSelection(2, 3, 4);
    }

    private void longPress(int position) {
        mManager.onLongPress(TestInputEvent.tap(position));
    }

    private void tap(int position) {
        mManager.onSingleTapUp(TestInputEvent.tap(position));
    }

    private void shiftTap(int position) {
        mManager.onSingleTapUp(TestInputEvent.shiftTap(position));
    }

    private void click(int position) {
        mManager.onSingleTapUp(TestInputEvent.click(position));
    }

    private void shiftClick(int position) {
        mManager.onSingleTapUp(TestInputEvent.shiftClick(position));
    }

    private void keyToPosition(int position, boolean shift) {
        mManager.attemptChangePosition(position, shift);
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

    private static final class TestCallback implements MultiSelectManager.Callback {

        Set<Integer> ignored = new HashSet<>();
        private boolean mSelectionChanged = false;

        @Override
        public void onItemStateChanged(int position, boolean selected) {}

        @Override
        public boolean onBeforeItemStateChange(int position, boolean selected) {
            return !ignored.contains(position);
        }

        @Override
        public void onSelectionChanged() {
            mSelectionChanged = true;
        }

        void assertSelectionChanged() {
            assertTrue(mSelectionChanged);
        }
    }

    private static final class TestHolder extends RecyclerView.ViewHolder {
        // each data item is just a string in this case
        public TestHolder(View view) {
            super(view);
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
