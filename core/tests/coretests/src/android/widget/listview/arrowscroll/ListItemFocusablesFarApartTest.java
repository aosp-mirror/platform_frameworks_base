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

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.listview.ListItemFocusablesFarApart;

public class ListItemFocusablesFarApartTest extends ActivityInstrumentationTestCase<ListItemFocusablesFarApart> {
    private ListView mListView;
    private int mListTop;
    private int mListBottom;

    public ListItemFocusablesFarApartTest() {
        super("com.android.frameworks.coretests", ListItemFocusablesFarApart.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
        mListTop = mListView.getListPaddingTop();
        mListBottom = mListView.getHeight() - mListView.getListPaddingBottom();
    }

    /**
     * Get the child of a list item.
     * @param listIndex The index of the currently visible items
     * @param index The index of the child.
     */
    public View getChildOfItem(int listIndex, int index) {
        return ((ViewGroup) mListView.getChildAt(listIndex)).getChildAt(index);

    }

    public int getTopOfChildOfItem(int listIndex, int index) {
        ViewGroup listItem = (ViewGroup) mListView.getChildAt(listIndex);
        View child = listItem.getChildAt(index);
        return child.getTop() + listItem.getTop();
    }

    public int getBottomOfChildOfItem(int listIndex, int index) {
        ViewGroup listItem = (ViewGroup) mListView.getChildAt(listIndex);
        View child = listItem.getChildAt(index);
        return child.getBottom() + listItem.getTop();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mListView);
        assertEquals("should only be one visible list item",
                1, mListView.getChildCount());
        int topOfFirstButton = getTopOfChildOfItem(0, 0);
        int topOfSecondButton = getTopOfChildOfItem(0, 2);
        assertTrue("second button should be more than max scroll away from first",
                topOfSecondButton - topOfFirstButton > mListView.getMaxScrollAmount());
    }


    @MediumTest
    public void testPanWhenNextFocusableTooFarDown() {

        int expectedTop = mListView.getChildAt(0).getTop();

        final Button topButton = (Button) getChildOfItem(0, 0);

        int counter = 0;
        while(getTopOfChildOfItem(0, 2) > mListBottom) {
            // just to make sure we never end up with an infinite loop
            if (counter > 5) fail("couldn't reach next button within " + counter + " downs");

            if (getBottomOfChildOfItem(0, 0) < mListTop) {
                assertFalse("after " + counter + " downs, top button not visible, should not have focus",
                        topButton.isFocused());
                assertFalse("after " + counter + " downs, neither top button nor botom button visible, nothng within first list " +
                        "item should have focus", mListView.getChildAt(0).hasFocus());
            } else {
                assertTrue("after " + counter + " downs, top button still visible, should have focus",
                        topButton.isFocused());
            }

            assertEquals("after " + counter + " downs, " +
                    "should have panned by max scroll amount",
                    expectedTop, mListView.getChildAt(0).getTop());

            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
            expectedTop -= mListView.getMaxScrollAmount();
            counter++;
        }

        // at this point, the second button is visible on screen.
        // it should have focus
        assertTrue("second button should have focus",
                getChildOfItem(0, 2).isFocused());
    }

}
