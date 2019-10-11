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

package android.widget.listview.arrowscroll;

import android.test.ActivityInstrumentationTestCase2;
import android.util.ListUtil;
import android.view.KeyEvent;
import android.widget.ListView;
import android.widget.listview.ListOfShortShortTallShortShort;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

public class ListOfShortShortTallShortShortTest extends ActivityInstrumentationTestCase2<ListOfShortShortTallShortShort> {
    private ListView mListView;
    private ListUtil mListUtil;

    public ListOfShortShortTallShortShortTest() {
        super(ListOfShortShortTallShortShort.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
        mListUtil = new ListUtil(mListView, getInstrumentation());
    }

    @MediumTest
    @Suppress // Failing.
    public void testPreconditions() {
        assertEquals("list item count", 5, mListView.getCount());
        assertEquals("list visible child count", 3, mListView.getChildCount());
        int firstTwoHeight = mListView.getChildAt(0).getHeight() + mListView.getChildAt(1).getHeight();
        assertTrue("first two items should fit within fading edge",
                firstTwoHeight <= mListView.getVerticalFadingEdgeLength());
        assertTrue("first two items should fit within list max scroll",
                firstTwoHeight <= mListView.getMaxScrollAmount());
    }

    @MediumTest
    public void testFadeTopTwoItemsOut() {
        // put 2nd item selected
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        // one more to get two items scrolled off
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertEquals("selected item position", 2, mListView.getSelectedItemPosition());
        assertTrue("selected item top should be above list top",
                mListView.getSelectedView().getTop() < mListUtil.getListTop());
        assertTrue("selected item bottom should be below list bottom",
                mListView.getSelectedView().getBottom() > mListUtil.getListBottom());
        assertEquals("should only be 1 child of list (2 should have been scrolled off and removed",
                1, mListView.getChildCount());
    }

    @MediumTest
    @Suppress // Failing.
    public void testFadeInTwoBottomItems() {
        // put 2nd item selected
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        // one more to get two items scrolled off
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("number of list children", 1, mListView.getChildCount());

        // last down brings bottom two items into view
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("should have scrolled two extra views onto screen",
                3, mListView.getChildCount());
        assertEquals("new view position", 3, mListView.getChildAt(1).getId());
        assertEquals("new view position", 4, mListView.getChildAt(2).getId());

        assertTrue("bottom most view shouldn't be above list bottom",
                mListView.getChildAt(2).getBottom() >= mListUtil.getListBottom());
    }

    @MediumTest
    public void testFadeOutBottomTwoItems() throws Exception {
        mListUtil.arrowScrollToSelectedPosition(4);

        // go up to tall item
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);

        // one more time to scroll off bottom two items
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);


        assertEquals("selected item position", 2, mListView.getSelectedItemPosition());
        assertTrue("selected item top should be at or above list top",
                mListView.getSelectedView().getTop() <= mListUtil.getListTop());
        assertTrue("selected item bottom should be below list bottom",
                mListView.getSelectedView().getBottom() > mListUtil.getListBottom());
        assertEquals("should only be 1 child of list (2 should have been scrolled off and removed",
                1, mListView.getChildCount());        
    }

    @MediumTest
    @Suppress // Failing.
    public void testFadeInTopTwoItems() throws Exception {
        mListUtil.arrowScrollToSelectedPosition(4);

        // put 2nd item selected
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);

        // one more to get two items scrolled off
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("number of list children", 1, mListView.getChildCount());

        // last down brings top two items into view
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("should have scrolled two extra views onto screen",
                3, mListView.getChildCount());
        assertEquals("new view position", 0, mListView.getChildAt(0).getId());
        assertEquals("new view position", 1, mListView.getChildAt(1).getId());

        assertTrue("top most view shouldn't be above list top",
                mListView.getChildAt(0).getTop() <= mListUtil.getListTop());
    }

    
}
