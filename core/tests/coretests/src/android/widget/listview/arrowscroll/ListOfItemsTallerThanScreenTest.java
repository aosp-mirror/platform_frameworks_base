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
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.listview.ListOfItemsTallerThanScreen;

public class ListOfItemsTallerThanScreenTest
        extends ActivityInstrumentationTestCase2<ListOfItemsTallerThanScreen> {

    private ListView mListView;
    private ListOfItemsTallerThanScreen mActivity;


    protected void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
        mListView = getActivity().getListView();
    }

    public ListOfItemsTallerThanScreenTest() {
        super(ListOfItemsTallerThanScreen.class);
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mListView);
        assertEquals("should only be one visible child", 1, mListView.getChildCount());
        final int amountOffScreen = mListView.getChildAt(0).getBottom() - (mListView.getBottom() - mListView.getListPaddingBottom());
        assertTrue("must be more than max scroll off screen for this test to work",
                amountOffScreen > mListView.getMaxScrollAmount());
    }

    @MediumTest
    public void testScrollDownAcrossItem() {
        final View view = mListView.getSelectedView();
        assertTrue(view.isSelected());

        assertEquals(mListView.getListPaddingTop(),
                view.getTop());

        assertTrue("view must be taller than screen for this test to be worth anything",
                view.getBottom() > mListView.getBottom());

        // scroll down until next view is peeking ahead
        int numScrollsUntilNextViewVisible = getNumDownPressesToScrollDownAcrossSelected();

        for (int i = 0; i < numScrollsUntilNextViewVisible; i++) {
            assertEquals("after " + i + " down scrolls across tall item",
                    mListView.getListPaddingTop() - mListView.getMaxScrollAmount() * i,
                    view.getTop());
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        // at this point, next view should be on screen peeking ahead, but we haven't given
        // it selection yet
        assertEquals("child count", 2, mListView.getChildCount());
        assertEquals("selected position", 0, mListView.getSelectedItemPosition());
        assertTrue("same view should be selected", view.isSelected());
        final View peekingView = mListView.getChildAt(1);
        assertEquals(view.getBottom(), peekingView.getTop());
    }

    @MediumTest
    public void testScrollDownToNextItem() {
        final int numPresses = getNumDownPressesToScrollDownAcrossSelected();
        assertEquals(1, mListView.getChildCount());

        for (int i = 0; i < numPresses; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        // remember top of peeking child
        final int topOfPeekingNext = mListView.getChildAt(1).getTop();

        // next view is peeking, now press one more time
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        // old view should not have selection
        assertFalse(mListView.getChildAt(0).isSelected());

        // next view should now have selection, and be scrolled another a third of the list
        // height
        assertEquals(2, mListView.getChildCount());
        final View next = mListView.getChildAt(1);
        assertTrue("has selection", next.isSelected());
        assertEquals(topOfPeekingNext - (mListView.getMaxScrollAmount()), next.getTop());
    }

    @MediumTest
    public void testScrollFirstItemOffScreen() {
        int numDownsToGetFirstItemOffScreen =
                (mListView.getSelectedView().getHeight() / mListView.getMaxScrollAmount()) + 1;

        for (int i = 0; i < numDownsToGetFirstItemOffScreen; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        getInstrumentation().waitForIdleSync();

        assertEquals("should be at next item",
                1, mListView.getSelectedItemPosition());

        final int listTop = mListView.getTop() + mListView.getListPaddingTop();
        assertTrue("top of selected view should be above top of list",
                mListView.getSelectedView().getTop() < listTop);

        assertEquals("off screen item shouldn't be a child of list view",
                1, mListView.getChildCount());
    }

    @MediumTest
    public void testScrollDownToLastItem() {
        final int numItems = mListView.getAdapter().getCount();

        int maxDowns = 20;
        while (mListView.getSelectedItemPosition() < (numItems - 1)) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
            if (--maxDowns <= 0) {
                fail("couldn't get to last item within 20 down arrows");
            }
        }
        getInstrumentation().waitForIdleSync();

        // press down enough times to get to bottom of last item
        final int numDownsLeft = getNumDownPressesToScrollDownAcrossSelected();
        assertTrue(numDownsLeft > 0);
        for (int i = 0; i < numDownsLeft; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        // one more time to get across last item
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        getInstrumentation().waitForIdleSync();

        assertEquals(numItems - 1, mListView.getSelectedItemPosition());
        final int realBottom = mListView.getHeight() - mListView.getListPaddingBottom();
        assertEquals(realBottom, mListView.getSelectedView().getBottom());

        assertEquals("views scrolled off screen should be removed from view group",
                1, mListView.getChildCount());
    }

    @MediumTest
    public void testScrollUpAcrossFirstItem() {
        final int listTop = mListView.getListPaddingTop();
        assertEquals(listTop, mListView.getSelectedView().getTop());
        final int numPresses = getNumDownPressesToScrollDownAcrossSelected();
        for (int i = 0; i < numPresses; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals(2, mListView.getChildCount());
        for (int i = 0; i < numPresses; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
            assertEquals(1, mListView.getChildCount());
        }
        assertEquals(listTop, mListView.getSelectedView().getTop());
    }

    /**
     * Assuming the selected view is overlapping the bottom edge, how many times
     * do I have to press down to get beyond it so that either:
     * a) the next view is peeking in
     * b) the selected view is the last item in the list, and we are scrolled to the bottom
     * @return
     */
    private int getNumDownPressesToScrollDownAcrossSelected() {
        View selected = mListView.getSelectedView();
        int realBottom = mListView.getBottom() - mListView.getListPaddingBottom();
        assertTrue("view should be overlapping bottom",
                selected.getBottom() > realBottom);
        assertTrue("view should be overlapping bottom",
                selected.getTop() < realBottom);

        int pixelsOffScreen = selected.getBottom() - realBottom;
        return (pixelsOffScreen / mListView.getMaxScrollAmount()) + 1;
    }

}
