/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget.focus;

import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.InternalSelectionView;
import android.view.KeyEvent;
import android.widget.ListView;


/**
 * TODO: extract base test case that launches {@link ListOfInternalSelectionViews} with
 * bundle params.
 */
public class ScrollingThroughListOfFocusablesTest extends InstrumentationTestCase {

    Rect mTempRect = new Rect();

    private ListOfInternalSelectionViews mActivity;
    private ListView mListView;

    private int mNumItems = 4;
    private int mNumRowsPerItem = 5;
    private double mScreenHeightFactor = 5 /4;

    @Override
    protected void setUp() throws Exception {
        mActivity = launchActivity(
                "com.android.frameworks.coretests",
                ListOfInternalSelectionViews.class,
                ListOfInternalSelectionViews.getBundleFor(
                    mNumItems,      // 4 items
                    mNumRowsPerItem,      // 5 internally selectable rows per item
                    mScreenHeightFactor)); // each item is 5 / 4 screen height tall
        mListView = mActivity.getListView();
        // Make sure we have some fading edge regardless of ListView style.
        mListView.setVerticalFadingEdgeEnabled(true);
        mListView.setFadingEdgeLength(10);
        ensureNotInTouchMode();

        // focus the listview
        mActivity.runOnUiThread(() -> mListView.requestFocus());
        getInstrumentation().waitForIdleSync();
    }

    @Override
    protected void tearDown() throws Exception {
        mActivity.finish();
        super.tearDown();
    }

    @MediumTest
    public void testPreconditions() throws Exception {
        assertNotNull(mActivity);
        assertNotNull(mListView);
        assertEquals(mNumItems, mActivity.getNumItems());
        assertEquals(mNumRowsPerItem, mActivity.getNumRowsPerItem());
    }

    @MediumTest
    public void testScrollingDownInFirstItem() throws Exception {

        for (int i = 0; i < mNumRowsPerItem; i++) {
            assertEquals(0, mListView.getSelectedItemPosition());
            
            InternalSelectionView view = mActivity.getSelectedView();

            assertInternallySelectedRowOnScreen(view, i);

            // move to next row
            if (i < mNumRowsPerItem - 1) {
                sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
                getInstrumentation().waitForIdleSync();
            }
        }

        {
            assertEquals(0, mListView.getSelectedItemPosition());
            InternalSelectionView view = (InternalSelectionView)
                    mListView.getSelectedView();

            // 1 pixel tolerance in case height / 4 is not an even number
            final int bottomFadingEdgeTop =
                mListView.getBottom() - mListView.getVerticalFadingEdgeLength();
            assertTrue("bottom of view should be just above fading edge",
                    view.getBottom() == bottomFadingEdgeTop);
        }

        // make sure fading edge is the expected view
        {
            assertEquals("should be a second view visible due to the fading edge",
                            2, mListView.getChildCount());
            InternalSelectionView peekingChild = (InternalSelectionView)
                    mListView.getChildAt(1);
            assertNotNull(peekingChild);
            assertEquals("wrong value for peeking list item",
                    mActivity.getLabelForPosition(1), peekingChild.getLabel());
        }
    }

    @MediumTest
    public void testScrollingToSecondItem() throws Exception {

        for (int i = 0; i < mNumRowsPerItem; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
            getInstrumentation().waitForIdleSync();
        }

        assertEquals("should have moved to second item",
                1, mListView.getSelectedItemPosition());
    }

    @LargeTest
    public void testNoFadingEdgeAtBottomOfLastItem() {

        // move down to last item
        for (int i = 0; i < mNumItems; i++) {
            for (int j = 0; j < mNumRowsPerItem; j++) {
                if (i < mNumItems - 1 || j < mNumRowsPerItem - 1) {
                    sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
                    getInstrumentation().waitForIdleSync();
                }
            }
        }

        assertEquals(mNumItems - 1, mListView.getSelectedItemPosition());
        InternalSelectionView view = mActivity.getSelectedView();
        assertEquals(mNumRowsPerItem - 1, view.getSelectedRow());

        view.getRectForRow(mTempRect, mNumRowsPerItem - 1);
        mListView.offsetDescendantRectToMyCoords(view, mTempRect);

        assertTrue("bottom of last row of last item should be at " +
                "the bottom of the list view (no fading edge)",
                mListView.getBottom() - mListView.getVerticalFadingEdgeLength() < mTempRect.bottom);
    }

    @LargeTest
    public void testNavigatingUpThroughInternalSelection() throws Exception {

        // get to bottom of second item
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < mNumRowsPerItem; j++) {
                if (i < 1 || j < mNumRowsPerItem - 1) {
                    sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
                    getInstrumentation().waitForIdleSync();
                }
            }
        }


        // (make sure we are at last row of second item)
        {
            assertEquals(1, mListView.getSelectedItemPosition());
            InternalSelectionView view = mActivity.getSelectedView();
            assertEquals(mNumRowsPerItem - 1, view.getSelectedRow());
        }

        // go back up to the top of the second item
        for (int i = mNumRowsPerItem - 1; i >= 0; i--) {
            assertEquals(1, mListView.getSelectedItemPosition());
            InternalSelectionView view = mActivity.getSelectedView();

            assertInternallySelectedRowOnScreen(view, i);

            // move up to next row
            if (i > 0) {
                sendKeys(KeyEvent.KEYCODE_DPAD_UP);
                getInstrumentation().waitForIdleSync();
            }
        }

        // now we are at top row, should have caused scrolling, and fading edge...
        {
            assertEquals(1, mListView.getSelectedItemPosition());
            InternalSelectionView view = mActivity.getSelectedView();
            assertEquals(0, view.getSelectedRow());

            view.getDrawingRect(mTempRect);
            mListView.offsetDescendantRectToMyCoords(view, mTempRect);
            assertEquals("top of selected row should be just below top vertical fading edge",
                    mListView.getVerticalFadingEdgeLength(),
                    view.getTop());
        }

        // make sure fading edge is the view we expect
        {
            final InternalSelectionView view =
                    (InternalSelectionView) mListView.getChildAt(0);
            assertEquals(mActivity.getLabelForPosition(0), view.getLabel());
        }


    }

    /**
     * @param internalFocused The view to check
     * @param row
     */
    private void assertInternallySelectedRowOnScreen(
            InternalSelectionView internalFocused,
            int row) {
        assertEquals("expecting selected row",
                row, internalFocused.getSelectedRow());

        internalFocused.getRectForRow(mTempRect, row);
        mListView.offsetDescendantRectToMyCoords(internalFocused, mTempRect);

        assertTrue("top of row " + row + " should be on sreen",
                mTempRect.top >= 0);
        assertTrue("bottom of row " + row + " should be on sreen",
                mTempRect.bottom < mActivity.getScreenHeight());
    }

    private void ensureNotInTouchMode() {
        // If in touch mode inject a DPAD down event to exit that mode.
        if (mListView.isInTouchMode()) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
            getInstrumentation().waitForIdleSync();
        }
    }
}
