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

import android.graphics.Point;
import android.graphics.Rect;
import android.support.v7.widget.RecyclerView.AdapterDataObserver;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.view.View;

import com.android.documentsui.dirlist.MultiSelectManager.GridModel;

import java.util.Set;

@SmallTest
public class MultiSelectManager_GridModelTest extends AndroidTestCase {

    private static final int VIEW_PADDING_PX = 5;
    private static final int CHILD_VIEW_EDGE_PX = 100;
    private static final int VIEWPORT_HEIGHT = 500;

    private static GridModel model;
    private static TestEnvironment env;
    private static Set<String> lastSelection;
    private static int viewWidth;

    private static void setUp(int numChildren, int numColumns) {
        env = new TestEnvironment(numChildren, numColumns);
        viewWidth = VIEW_PADDING_PX + numColumns * (VIEW_PADDING_PX + CHILD_VIEW_EDGE_PX);
        model = new GridModel(env);
        model.addOnSelectionChangedListener(
                new GridModel.OnSelectionChangedListener() {
                    @Override
                    public void onSelectionChanged(Set<String> updatedSelection) {
                        lastSelection = updatedSelection;
                    }
                });
    }

    @Override
    public void tearDown() {
        model = null;
        env = null;
        lastSelection = null;
    }

    public void testSelectionLeftOfItems() {
        setUp(20, 5);
        model.startSelection(new Point(0, 10));
        model.resizeSelection(new Point(1, 11));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testSelectionRightOfItems() {
        setUp(20, 4);
        model.startSelection(new Point(viewWidth - 1, 10));
        model.resizeSelection(new Point(viewWidth - 2, 11));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testSelectionAboveItems() {
        setUp(20, 4);
        model.startSelection(new Point(10, 0));
        model.resizeSelection(new Point(11, 1));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testSelectionBelowItems() {
        setUp(5, 4);
        model.startSelection(new Point(10, VIEWPORT_HEIGHT - 1));
        model.resizeSelection(new Point(11, VIEWPORT_HEIGHT - 2));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testVerticalSelectionBetweenItems() {
        setUp(20, 4);
        model.startSelection(new Point(106, 0));
        model.resizeSelection(new Point(107, 200));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testHorizontalSelectionBetweenItems() {
        setUp(20, 4);
        model.startSelection(new Point(0, 105));
        model.resizeSelection(new Point(200, 106));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testGrowingAndShrinkingSelection() {
        setUp(20, 4);
        model.startSelection(new Point(0, 0));
        model.resizeSelection(new Point(5, 5));
        assertSelected(0);
        model.resizeSelection(new Point(109, 109));
        assertSelected(0);
        model.resizeSelection(new Point(110, 109));
        assertSelected(0, 1);
        model.resizeSelection(new Point(110, 110));
        assertSelected(0, 1, 4, 5);
        model.resizeSelection(new Point(214, 214));
        assertSelected(0, 1, 4, 5);
        model.resizeSelection(new Point(215, 214));
        assertSelected(0, 1, 2, 4, 5, 6);
        model.resizeSelection(new Point(214, 214));
        assertSelected(0, 1, 4, 5);
        model.resizeSelection(new Point(110, 110));
        assertSelected(0, 1, 4, 5);
        model.resizeSelection(new Point(110, 109));
        assertSelected(0, 1);
        model.resizeSelection(new Point(109, 109));
        assertSelected(0);
        model.resizeSelection(new Point(5, 5));
        assertSelected(0);
        model.resizeSelection(new Point(0, 0));
        assertSelected();
        assertEquals(null, model.getItemNearestOrigin());
    }

    public void testSelectionMovingAroundOrigin() {
        setUp(16, 4);
        model.startSelection(new Point(210, 210));
        model.resizeSelection(new Point(viewWidth - 1, 0));
        assertSelected(2, 3, 6, 7);
        model.resizeSelection(new Point(0, 0));
        assertSelected(0, 1, 4, 5);
        model.resizeSelection(new Point(0, 420));
        assertSelected(8, 9, 12, 13);
        model.resizeSelection(new Point(viewWidth - 1, 420));
        assertSelected(10, 11, 14, 15);
        assertEquals("10", model.getItemNearestOrigin());
    }

    public void testScrollingBandSelect() {
        setUp(40, 4);
        model.startSelection(new Point(0, 0));
        model.resizeSelection(new Point(100, VIEWPORT_HEIGHT - 1));
        assertSelected(0, 4, 8, 12, 16);
        scroll(CHILD_VIEW_EDGE_PX);
        assertSelected(0, 4, 8, 12, 16, 20);
        model.resizeSelection(new Point(200, VIEWPORT_HEIGHT - 1));
        assertSelected(0, 1, 4, 5, 8, 9, 12, 13, 16, 17, 20, 21);
        scroll(CHILD_VIEW_EDGE_PX);
        assertSelected(0, 1, 4, 5, 8, 9, 12, 13, 16, 17, 20, 21, 24, 25);
        scroll(-2 * CHILD_VIEW_EDGE_PX);
        assertSelected(0, 1, 4, 5, 8, 9, 12, 13, 16, 17);
        model.resizeSelection(new Point(100, VIEWPORT_HEIGHT - 1));
        assertSelected(0, 4, 8, 12, 16);
        assertEquals("0", model.getItemNearestOrigin());
    }

    private static void assertSelected(int... selectedPositions) {
        assertEquals(selectedPositions.length, lastSelection.size());
        for (int position : selectedPositions) {
            assertTrue(lastSelection.contains(Integer.toString(position)));
        }
    }

    private static void scroll(int dy) {
        assertTrue(env.verticalOffset + VIEWPORT_HEIGHT + dy <= env.getTotalHeight());
        env.verticalOffset += dy;
        model.onScrolled(null, 0, dy);
    }

    private static final class TestEnvironment implements MultiSelectManager.SelectionEnvironment {

        public int horizontalOffset = 0;
        public int verticalOffset = 0;
        private final int mNumColumns;
        private final int mNumRows;
        private final int mNumChildren;

        public TestEnvironment(int numChildren, int numColumns) {
            mNumChildren = numChildren;
            mNumColumns = numColumns;
            mNumRows = (int) Math.ceil((double) numChildren / mNumColumns);
        }

        private int getTotalHeight() {
            return CHILD_VIEW_EDGE_PX * mNumRows + VIEW_PADDING_PX * (mNumRows + 1);
        }

        private int getFirstVisibleRowIndex() {
            return verticalOffset / (CHILD_VIEW_EDGE_PX + VIEW_PADDING_PX);
        }

        private int getLastVisibleRowIndex() {
            int lastVisibleRowUncapped =
                    (VIEWPORT_HEIGHT + verticalOffset - 1) / (CHILD_VIEW_EDGE_PX + VIEW_PADDING_PX);
            return Math.min(lastVisibleRowUncapped, mNumRows - 1);
        }

        private int getNumItemsInRow(int index) {
            assertTrue(index >= 0 && index < mNumRows);
            if (index == mNumRows - 1 && mNumChildren % mNumColumns != 0) {
                return mNumChildren % mNumColumns;
            }

            return mNumColumns;
        }

        @Override
        public void addOnScrollListener(OnScrollListener listener) {}

        @Override
        public void removeOnScrollListener(OnScrollListener listener) {}

        @Override
        public Point createAbsolutePoint(Point relativePoint) {
            return new Point(
                    relativePoint.x + horizontalOffset, relativePoint.y + verticalOffset);
        }

        @Override
        public int getVisibleChildCount() {
            int childCount = 0;
            for (int i = getFirstVisibleRowIndex(); i <= getLastVisibleRowIndex(); i++) {
                childCount += getNumItemsInRow(i);
            }
            return childCount;
        }

        @Override
        public Rect getAbsoluteRectForChildViewAt(int index) {
            int adapterPosition = (getFirstVisibleRowIndex() * mNumColumns) + index;
            int rowIndex = adapterPosition / mNumColumns;
            int columnIndex = adapterPosition % mNumColumns;

            Rect rect = new Rect();
            rect.top = VIEW_PADDING_PX + rowIndex * (CHILD_VIEW_EDGE_PX + VIEW_PADDING_PX);
            rect.bottom = rect.top + CHILD_VIEW_EDGE_PX - 1;
            rect.left = VIEW_PADDING_PX + columnIndex * (CHILD_VIEW_EDGE_PX + VIEW_PADDING_PX);
            rect.right = rect.left + CHILD_VIEW_EDGE_PX - 1;
            return rect;
        }

        @Override
        public int getChildCount() {
            return mNumChildren;
        }

        @Override
        public int getColumnCount() {
            return mNumColumns;
        }

        @Override
        public int getRowCount() {
            return mNumRows;
        }

        @Override
        public void showBand(Rect rect) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void hideBand() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void scrollBy(int dy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getHeight() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invalidateView() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runAtNextFrame(Runnable r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeCallback(Runnable r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getAdapterPositionForChildView(View view) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void focusItem(int i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModelIdFromAdapterPosition(int position) {
            return Integer.toString(position);
        }

        @Override
        public String getModelIdAt(int index) {
            int firstVisibleChildIndex = getFirstVisibleRowIndex() * mNumColumns;
            return Integer.toString(firstVisibleChildIndex + index);
        }

        @Override
        public String getModelIdForChildView(View view) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getItemCount() {
            return mNumChildren;
        }

        @Override
        public void notifyItemChanged(String id, String selectionChangedMarker) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerDataObserver(AdapterDataObserver observer) {
            throw new UnsupportedOperationException();
        }
    }
}
