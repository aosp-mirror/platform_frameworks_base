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

package android.widget.listview;

import android.graphics.Rect;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.view.View;
import android.view.KeyEvent;
import android.widget.ListView;
import android.widget.listview.ListOfThinItems;

public class ListItemRequestRectAboveThinFirstItemTest
        extends ActivityInstrumentationTestCase<ListOfThinItems> {
    private ListView mListView;

    public ListItemRequestRectAboveThinFirstItemTest() {
        super("com.android.frameworks.coretests", ListOfThinItems.class);
    }

    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
    }

    @MediumTest
    @Suppress // Failing.
    public void testPreconditions() {

        assertTrue("first child needs to be within fading edge height",
                mListView.getChildAt(0).getBottom() < mListView.getVerticalFadingEdgeLength());
        assertTrue("should be at least two visible children",
                mListView.getChildCount() >= 2);
    }

    // reproduce bug 998501: when first item fits within fading edge,
    // having the second item call requestRectangleOnScreen with a rect above
    // the bounds of the list, it was scrolling too far
    @MediumTest
    public void testSecondItemRequestRectAboveTop() throws Exception {

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("selected position", 1, mListView.getSelectedItemPosition());

        final View second = mListView.getChildAt(1);
        final Rect rect = new Rect();
        second.getDrawingRect(rect);
        rect.offset(0, -2 * second.getBottom());

        getActivity().requestRectangleOnScreen(1, rect);
        getInstrumentation().waitForIdleSync();        

        assertEquals("top of first item",
                mListView.getListPaddingTop(), mListView.getChildAt(0).getTop());

    }

    // same thing, but at bottom
    @LargeTest
    public void testSecondToLastItemRequestRectBelowBottom() throws Exception {

        final int secondToLastPos = mListView.getCount() - 2;

        while (mListView.getSelectedItemPosition() < secondToLastPos) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals("selected position", secondToLastPos,
                mListView.getSelectedItemPosition());

        final View secondToLast = mListView.getSelectedView();
        final Rect rect = new Rect();
        secondToLast.getDrawingRect(rect);
        rect.offset(0,
                2 * (mListView.getBottom() - secondToLast.getTop()));

        final int secondToLastIndex = mListView.getChildCount() - 2;
        getActivity().requestRectangleOnScreen(secondToLastIndex, rect);
        getInstrumentation().waitForIdleSync();

        int listBottom = mListView.getHeight() - mListView.getPaddingBottom();
        assertEquals("bottom of last item should be at bottom of list",
                listBottom,
                mListView.getChildAt(mListView.getChildCount() - 1).getBottom());
    }



}
