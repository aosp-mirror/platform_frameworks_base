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

import android.test.ActivityInstrumentationTestCase;
import android.util.InternalSelectionView;
import android.view.KeyEvent;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

/**
 * {@link android.view.FocusFinder#findNextFocus(android.view.ViewGroup, android.view.View, int)}
 * and
 * {@link android.view.View#requestFocus(int, android.graphics.Rect)}
 * work together to give a newly focused item a hint about the most interesting
 * rectangle of the previously focused view.  The view taking focus can use this
 * to set an internal selection more appropriate using this rect.
 *
 * This tests that behavior using three adjacent {@link android.util.InternalSelectionView}
 * that report interesting rects when giving up focus, and use interesting rects
 * when taking focus to best select the internal row to show as selected.
 *
 */
public class FocusChangeWithInterestingRectHintTest extends ActivityInstrumentationTestCase<AdjacentVerticalRectLists> {

    private InternalSelectionView mLeftColumn;
    private InternalSelectionView mMiddleColumn;
    private InternalSelectionView mRightColumn;

    public FocusChangeWithInterestingRectHintTest() {
        super("com.android.frameworks.coretests", AdjacentVerticalRectLists.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mLeftColumn = getActivity().getLeftColumn();
        mMiddleColumn = getActivity().getMiddleColumn();
        mRightColumn = getActivity().getRightColumn();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mLeftColumn);
        assertNotNull(mMiddleColumn);
        assertNotNull(mRightColumn);
        assertTrue(mLeftColumn.hasFocus());
        assertTrue("need at least 3 rows", mLeftColumn.getNumRows() > 2);
        assertEquals(mLeftColumn.getNumRows(), mMiddleColumn.getNumRows());
        assertEquals(mMiddleColumn.getNumRows(), mRightColumn.getNumRows());
    }


    @LargeTest
    public void testSnakeBackAndForth() {
        final int numRows = mLeftColumn.getNumRows();
        for (int row = 0; row < numRows; row++) {

            if ((row % 2) == 0) {
                assertEquals("row " + row + ": should be at left column",
                        row, mLeftColumn.getSelectedRow());

                sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
                assertTrue("row " + row + ": should be at middle column",
                        mMiddleColumn.hasFocus());
                assertEquals(row, mMiddleColumn.getSelectedRow());

                sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
                assertTrue("row " + row + ": should be at right column",
                        mRightColumn.hasFocus());
                assertEquals(row, mRightColumn.getSelectedRow());

                if (row < numRows - 1) {
                    sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
                    assertEquals(row + 1, mRightColumn.getSelectedRow());
                }
            } else {
                assertTrue("row " + row + ": should be at right column",
                        mRightColumn.hasFocus());

                sendKeys(KeyEvent.KEYCODE_DPAD_LEFT);
                assertTrue("row " + row + ": should be at middle column",
                        mMiddleColumn.hasFocus());
                assertEquals(row, mMiddleColumn.getSelectedRow());

                sendKeys(KeyEvent.KEYCODE_DPAD_LEFT);
                assertEquals("row " + row + ": should be at left column",
                        row, mLeftColumn.getSelectedRow());

                if (row < numRows - 1) {
                    sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
                    assertEquals(row + 1, mLeftColumn.getSelectedRow());
                }
           }
        }
    }

}
