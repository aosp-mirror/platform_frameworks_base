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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Point;
import android.graphics.Rect;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.util.SparseBooleanArray;

import com.android.documentsui.MultiSelectManager.GridModel;

import org.junit.After;
import org.junit.Test;

public class MultiSelectManager_GridModelTest {

    private static final int VIEW_PADDING_PX = 5;
    private static final int CHILD_VIEW_EDGE_PX = 100;
    private static final int VIEWPORT_HEIGHT = 500;

    private static GridModel model;
    private static TestHelper helper;
    private static SparseBooleanArray lastSelection;
    private static int viewWidth;

    private static void setUp(int numChildren, int numColumns) {
        helper = new TestHelper(numChildren, numColumns);
        viewWidth = VIEW_PADDING_PX + numColumns * (VIEW_PADDING_PX + CHILD_VIEW_EDGE_PX);
        model = new GridModel(helper);
        model.addOnSelectionChangedListener(
                new GridModel.OnSelectionChangedListener() {
                    @Override
                    public void onSelectionChanged(SparseBooleanArray updatedSelection) {
                        lastSelection = updatedSelection;
                    }
                });
    }

    @After
    public void tearDown() {
        model = null;
        helper = null;
        lastSelection = null;
    }

    @Test
    public void testSelectionLeftOfItems() {
        setUp(20, 5);
        model.startSelection(new Point(0, 10));
        model.resizeSelection(new Point(1, 11));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testSelectionRightOfItems() {
        setUp(20, 4);
        model.startSelection(new Point(viewWidth - 1, 10));
        model.resizeSelection(new Point(viewWidth - 2, 11));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testSelectionAboveItems() {
        setUp(20, 4);
        model.startSelection(new Point(10, 0));
        model.resizeSelection(new Point(11, 1));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testSelectionBelowItems() {
        setUp(5, 4);
        model.startSelection(new Point(10, VIEWPORT_HEIGHT - 1));
        model.resizeSelection(new Point(11, VIEWPORT_HEIGHT - 2));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testVerticalSelectionBetweenItems() {
        setUp(20, 4);
        model.startSelection(new Point(106, 0));
        model.resizeSelection(new Point(107, 200));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testHorizontalSelectionBetweenItems() {
        setUp(20, 4);
        model.startSelection(new Point(0, 105));
        model.resizeSelection(new Point(200, 106));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testGrowingAndShrinkingSelection() {
        setUp(20, 4);
        model.startSelection(new Point(0, 0));
        model.resizeSelection(new Point(5, 5));
        assertSelected(new int[] {0});
        model.resizeSelection(new Point(109, 109));
        assertSelected(new int[] {0});
        model.resizeSelection(new Point(110, 109));
        assertSelected(new int[] {0, 1});
        model.resizeSelection(new Point(110, 110));
        assertSelected(new int[] {0, 1, 4, 5});
        model.resizeSelection(new Point(214, 214));
        assertSelected(new int[] {0, 1, 4, 5});
        model.resizeSelection(new Point(215, 214));
        assertSelected(new int[] {0, 1, 2, 4, 5, 6});
        model.resizeSelection(new Point(214, 214));
        assertSelected(new int[] {0, 1, 4, 5});
        model.resizeSelection(new Point(110, 110));
        assertSelected(new int[] {0, 1, 4, 5});
        model.resizeSelection(new Point(110, 109));
        assertSelected(new int[] {0, 1});
        model.resizeSelection(new Point(109, 109));
        assertSelected(new int[] {0});
        model.resizeSelection(new Point(5, 5));
        assertSelected(new int[] {0});
        model.resizeSelection(new Point(0, 0));
        assertSelected(new int[0]);
        assertEquals(GridModel.NOT_SET, model.getPositionNearestOrigin());
    }

    @Test
    public void testSelectionMovingAroundOrigin() {
        setUp(16, 4);
        model.startSelection(new Point(210, 210));
        model.resizeSelection(new Point(viewWidth - 1, 0));
        assertSelected(new int[] {2, 3, 6, 7});
        model.resizeSelection(new Point(0, 0));
        assertSelected(new int[] {0, 1, 4, 5});
        model.resizeSelection(new Point(0, 420));
        assertSelected(new int[] {8, 9, 12, 13});
        model.resizeSelection(new Point(viewWidth - 1, 420));
        assertSelected(new int[] {10, 11, 14, 15});
        assertEquals(10, model.getPositionNearestOrigin());
    }

    @Test
    public void testScrollingBandSelect() {
        setUp(40, 4);
        model.startSelection(new Point(0, 0));
        model.resizeSelection(new Point(100, VIEWPORT_HEIGHT - 1));
        assertSelected(new int[] {0, 4, 8, 12, 16});
        scroll(CHILD_VIEW_EDGE_PX);
        assertSelected(new int[] {0, 4, 8, 12, 16, 20});
        model.resizeSelection(new Point(200, VIEWPORT_HEIGHT - 1));
        assertSelected(new int[] {0, 1, 4, 5, 8, 9, 12, 13, 16, 17, 20, 21});
        scroll(CHILD_VIEW_EDGE_PX);
        assertSelected(new int[] {0, 1, 4, 5, 8, 9, 12, 13, 16, 17, 20, 21, 24, 25});
        scroll(-2 * CHILD_VIEW_EDGE_PX);
        assertSelected(new int[] {0, 1, 4, 5, 8, 9, 12, 13, 16, 17});
        model.resizeSelection(new Point(100, VIEWPORT_HEIGHT - 1));
        assertSelected(new int[] {0, 4, 8, 12, 16});
        assertEquals(0, model.getPositionNearestOrigin());
    }

    private static void assertSelected(int[] selectedPositions) {
        assertEquals(selectedPositions.length, lastSelection.size());
        for (int position : selectedPositions) {
            assertTrue(lastSelection.get(position));
        }
    }

    private static void scroll(int dy) {
        assertTrue(helper.verticalOffset + VIEWPORT_HEIGHT + dy <= helper.getTotalHeight());
        helper.verticalOffset += dy;
        model.onScrolled(null, 0, dy);
    }

    private static final class TestHelper implements MultiSelectManager.BandEnvironment {

        public int horizontalOffset = 0;
        public int verticalOffset = 0;
        private final int mNumColumns;
        private final int mNumRows;
        private final int mNumChildren;

        public TestHelper(int numChildren, int numColumns) {
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
        public int getAdapterPositionAt(int index) {
            return index + mNumColumns * (getFirstVisibleRowIndex());
        }

        @Override
        public Rect getAbsoluteRectForChildViewAt(int index) {
            int adapterPosition = getAdapterPositionAt(index);
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
    }
}
