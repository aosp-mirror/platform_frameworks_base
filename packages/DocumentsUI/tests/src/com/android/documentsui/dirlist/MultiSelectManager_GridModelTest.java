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

import static com.android.documentsui.dirlist.MultiSelectManager.GridModel.NOT_SET;

import android.graphics.Point;
import android.graphics.Rect;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.documentsui.dirlist.MultiSelectManager.GridModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SmallTest
public class MultiSelectManager_GridModelTest extends AndroidTestCase {

    private static final int VIEW_PADDING_PX = 5;
    private static final int CHILD_VIEW_EDGE_PX = 100;
    private static final int VIEWPORT_HEIGHT = 500;

    private GridModel model;
    private TestEnvironment env;
    private TestDocumentsAdapter adapter;
    private Set<String> lastSelection;
    private int viewWidth;

    // TLDR: Don't call model.{start|resize}Selection; use the local #startSelection and
    // #resizeSelection methods instead.
    //
    // The reason for this is that selection is stateful and involves operations that take the
    // current UI state (e.g scrolling) into account. This test maintains its own copy of the
    // selection bounds as control data for verifying selections. Keep this data in sync by calling
    // #startSelection and
    // #resizeSelection.
    private Point mSelectionOrigin;
    private Point mSelectionPoint;

    private void initData(final int numChildren, int numColumns) {
        env = new TestEnvironment(numChildren, numColumns);
        adapter = new TestDocumentsAdapter(new ArrayList<String>()) {
            @Override
            public String getModelId(int position) {
                return Integer.toString(position);
            }

            @Override
            public int getItemCount() {
                return numChildren;
            }
        };

        viewWidth = VIEW_PADDING_PX + numColumns * (VIEW_PADDING_PX + CHILD_VIEW_EDGE_PX);
        model = new GridModel(env, adapter);
        model.addOnSelectionChangedListener(
                new GridModel.OnSelectionChangedListener() {
                    @Override
                    public void onSelectionChanged(Set<String> updatedSelection) {
                        lastSelection = updatedSelection;
                    }

                    @Override
                    public boolean onBeforeItemStateChange(String id, boolean nextState) {
                        return true;
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
        initData(20, 5);
        startSelection(new Point(0, 10));
        resizeSelection(new Point(1, 11));
        assertNoSelection();
        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testSelectionRightOfItems() {
        initData(20, 4);
        startSelection(new Point(viewWidth - 1, 10));
        resizeSelection(new Point(viewWidth - 2, 11));
        assertNoSelection();
        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testSelectionAboveItems() {
        initData(20, 4);
        startSelection(new Point(10, 0));
        resizeSelection(new Point(11, 1));
        assertNoSelection();
        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testSelectionBelowItems() {
        initData(5, 4);
        startSelection(new Point(10, VIEWPORT_HEIGHT - 1));
        resizeSelection(new Point(11, VIEWPORT_HEIGHT - 2));
        assertNoSelection();
        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testVerticalSelectionBetweenItems() {
        initData(20, 4);
        startSelection(new Point(106, 0));
        resizeSelection(new Point(107, 200));
        assertNoSelection();
        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testHorizontalSelectionBetweenItems() {
        initData(20, 4);
        startSelection(new Point(0, 105));
        resizeSelection(new Point(200, 106));
        assertNoSelection();
        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testGrowingAndShrinkingSelection() {
        initData(20, 4);
        startSelection(new Point(0, 0));

        resizeSelection(new Point(5, 5));
        verifySelection();

        resizeSelection(new Point(109, 109));
        verifySelection();

        resizeSelection(new Point(110, 109));
        verifySelection();

        resizeSelection(new Point(110, 110));
        verifySelection();

        resizeSelection(new Point(214, 214));
        verifySelection();

        resizeSelection(new Point(215, 214));
        verifySelection();

        resizeSelection(new Point(214, 214));
        verifySelection();

        resizeSelection(new Point(110, 110));
        verifySelection();

        resizeSelection(new Point(110, 109));
        verifySelection();

        resizeSelection(new Point(109, 109));
        verifySelection();

        resizeSelection(new Point(5, 5));
        verifySelection();

        resizeSelection(new Point(0, 0));
        verifySelection();

        assertEquals(NOT_SET, model.getPositionNearestOrigin());
    }

    public void testSelectionMovingAroundOrigin() {
        initData(16, 4);

        startSelection(new Point(210, 210));
        resizeSelection(new Point(viewWidth - 1, 0));
        verifySelection();

        resizeSelection(new Point(0, 0));
        verifySelection();

        resizeSelection(new Point(0, 420));
        verifySelection();

        resizeSelection(new Point(viewWidth - 1, 420));
        verifySelection();

        // This is manually figured and will need to be adjusted if the separator position is
        // changed.
        assertEquals(7, model.getPositionNearestOrigin());
    }

    public void testScrollingBandSelect() {
        initData(40, 4);

        startSelection(new Point(0, 0));
        resizeSelection(new Point(100, VIEWPORT_HEIGHT - 1));
        verifySelection();

        scroll(CHILD_VIEW_EDGE_PX);
        verifySelection();

        resizeSelection(new Point(200, VIEWPORT_HEIGHT - 1));
        verifySelection();

        scroll(CHILD_VIEW_EDGE_PX);
        verifySelection();

        scroll(-2 * CHILD_VIEW_EDGE_PX);
        verifySelection();

        resizeSelection(new Point(100, VIEWPORT_HEIGHT - 1));
        verifySelection();

        assertEquals(0, model.getPositionNearestOrigin());
    }

    /** Returns the current selection area as a Rect. */
    private Rect getSelectionArea() {
        // Construct a rect from the two selection points.
        Rect selectionArea = new Rect(
                mSelectionOrigin.x, mSelectionOrigin.y, mSelectionOrigin.x, mSelectionOrigin.y);
        selectionArea.union(mSelectionPoint.x, mSelectionPoint.y);
        // Rect intersection tests are exclusive of bounds, while the MSM's selection code is
        // inclusive. Expand the rect by 1 pixel in all directions to account for this.
        selectionArea.inset(-1, -1);

        return selectionArea;
    }

    /** Asserts that the selection is currently empty. */
    private void assertNoSelection() {
        assertEquals("Unexpected items " + lastSelection + " in selection " + getSelectionArea(),
                0, lastSelection.size());
    }

    /** Verifies the selection using actual bbox checks. */
    private void verifySelection() {
        Rect selectionArea = getSelectionArea();
        for (TestEnvironment.Item item: env.items) {
            if (Rect.intersects(selectionArea, item.rect)) {
                assertTrue("Expected item " + item + " was not in selection " + selectionArea,
                        lastSelection.contains(item.name));
            } else {
                assertFalse("Unexpected item " + item + " in selection" + selectionArea,
                        lastSelection.contains(item.name));
            }
        }
    }

    private void startSelection(Point p) {
        model.startSelection(p);
        mSelectionOrigin = env.createAbsolutePoint(p);
    }

    private void resizeSelection(Point p) {
        model.resizeSelection(p);
        mSelectionPoint = env.createAbsolutePoint(p);
    }

    private void scroll(int dy) {
        assertTrue(env.verticalOffset + VIEWPORT_HEIGHT + dy <= env.getTotalHeight());
        env.verticalOffset += dy;
        // Correct the cached selection point as well.
        mSelectionPoint.y += dy;
        model.onScrolled(null, 0, dy);
    }

    private static final class TestEnvironment implements MultiSelectManager.SelectionEnvironment {

        private final int mNumColumns;
        private final int mNumRows;
        private final int mNumChildren;
        private final int mSeparatorPosition;

        public int horizontalOffset = 0;
        public int verticalOffset = 0;
        private List<Item> items = new ArrayList<>();

        public TestEnvironment(int numChildren, int numColumns) {
            mNumChildren = numChildren;
            mNumColumns = numColumns;
            mSeparatorPosition = mNumColumns + 1;
            mNumRows = setupGrid();
        }

        private int setupGrid() {
            // Split the input set into folders and documents. Do this such that there is a
            // partially-populated row in the middle of the grid, to test corner cases in layout
            // code.
            int y = VIEW_PADDING_PX;
            int i = 0;
            int numRows = 0;
            while (i < mNumChildren) {
                int top = y;
                int height = CHILD_VIEW_EDGE_PX;
                int width = CHILD_VIEW_EDGE_PX;
                for (int j = 0; j < mNumColumns && i < mNumChildren; j++) {
                    int left = VIEW_PADDING_PX + (j * (width + VIEW_PADDING_PX));
                    items.add(new Item(
                            Integer.toString(i),
                            new Rect(
                                    left,
                                    top,
                                    left + width - 1,
                                    top + height - 1)));

                    // Create a partially populated row at the separator position.
                    if (++i == mSeparatorPosition) {
                        break;
                    }
                }
                y += height + VIEW_PADDING_PX;
                numRows++;
            }

            return numRows;
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
            int mod = mSeparatorPosition % mNumColumns;
            if (index == (mSeparatorPosition / mNumColumns)) {
                // The row containing the separator may be incomplete
                return mod > 0 ? mod : mNumColumns;
            }
            // Account for the partial separator row in the final row tally.
            if (index == mNumRows - 1) {
                // The last row may be incomplete
                int finalRowCount = (mNumChildren - mod) % mNumColumns;
                return finalRowCount > 0 ? finalRowCount : mNumColumns;
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
            // Account for partial rows by actually tallying up the items in hidden rows.
            int hiddenCount = 0;
            for (int i = 0; i < getFirstVisibleRowIndex(); i++) {
                hiddenCount += getNumItemsInRow(i);
            }
            return index + hiddenCount;
        }

        @Override
        public Rect getAbsoluteRectForChildViewAt(int index) {
            int adapterPosition = getAdapterPositionAt(index);
            return items.get(adapterPosition).rect;
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
        public boolean isLayoutItem(int adapterPosition) {
            return false;
        }

        @Override
        public boolean hasView(int adapterPosition) {
            return true;
        }

        public static final class Item {
            public String name;
            public Rect rect;

            public Item(String n, Rect r) {
                name = n;
                rect = r;
            }

            public String toString() {
                return name + ": " + rect;
            }
        }
    }
}
