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
import android.test.suitebuilder.annotation.Suppress;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.listview.ListOfItemsShorterThanScreen;

public class ListOfItemsShorterThanScreenTest
        extends ActivityInstrumentationTestCase<ListOfItemsShorterThanScreen> {
    private ListView mListView;
    private ListOfItemsShorterThanScreen mActivity;


    public ListOfItemsShorterThanScreenTest() {
        super("com.android.frameworks.coretests", ListOfItemsShorterThanScreen.class);
    }

    protected void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertEquals(0, mListView.getSelectedItemPosition());
        assertTrue(mListView.getChildAt(0).isSelected());
        assertEquals(mListView.getListPaddingTop(), mListView.getSelectedView().getTop());
    }

    @MediumTest
    public void testMoveDownToOnScreenNextItem() {
        final View next = mListView.getChildAt(1);
        assertFalse(next.isSelected());
        final int secondPosition = mListView.getSelectedView().getBottom();
        assertEquals("second item should be positioned item height pixels from top.",
                secondPosition,
                next.getTop());

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, mListView.getSelectedItemPosition());
        assertTrue(next.isSelected());
        assertEquals("next selected item shouldn't have moved",
                secondPosition,
                next.getTop());
    }

    @MediumTest
    public void testMoveUpToOnScreenItem() {
        // move down one, then back up
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(0, mListView.getSelectedItemPosition());
    }

    @MediumTest
    @Suppress // Failing.
    public void testMoveDownToItemRequiringScrolling() {
        final int lastOnScreenItemIndex = mListView.getChildCount() - 1;
        final View lastItem = mListView.getChildAt(lastOnScreenItemIndex);
        assertTrue("last item should be partially off screen",
                lastItem.getBottom() > mListView.getBottom());
        assertEquals(mListView.getListPaddingTop(), mListView.getSelectedView().getTop());

        for (int i = 0; i < lastOnScreenItemIndex; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        assertEquals(lastOnScreenItemIndex, mListView.getSelectedItemPosition());
        assertEquals(
                getTopOfBottomFadingEdge(),
                mListView.getSelectedView().getBottom());

        // there should be a peeking view

        // the current view isn't the last anymore...
        assertEquals(mListView.getSelectedView(), mListView.getChildAt(mListView.getChildCount() - 2));

        // peeking view is now last
        final TextView view = (TextView) mListView.getChildAt(mListView.getChildCount() - 1);
        assertEquals(mActivity.getValueAtPosition(lastOnScreenItemIndex + 1), view.getText());
        assertFalse(view.isSelected());
    }

    @MediumTest
    @Suppress // Failing.
    public void testMoveUpToItemRequiringScrolling() {
        // go down to one past last item, then back up to the second item.  this will
        // require scrolling to get it back on screen, and will need a peeking edge

        int numItemsOnScreen = mListView.getChildCount();
        for (int i = 0; i < numItemsOnScreen; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        for (int i = 0; i < numItemsOnScreen - 1; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        }

        assertEquals(1, mListView.getSelectedItemPosition());
        assertEquals("top should be just below vertical fading edge",
                mListView.getVerticalFadingEdgeLength() + mListView.getListPaddingTop(),
                mListView.getSelectedView().getTop());
    }

    @MediumTest
    public void testPressUpWhenAlreadyAtTop() {
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);

        assertEquals(0, mListView.getSelectedItemPosition());
    }

    @MediumTest
    public void testPressDownWhenAlreadyAtBottom() {
        final int lastItemPosition = mListView.getAdapter().getCount() - 1;
        for (int i = 0; i < lastItemPosition; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals(lastItemPosition, mListView.getSelectedItemPosition());

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(lastItemPosition, mListView.getSelectedItemPosition());
    }

    @MediumTest
    public void testNoVerticalFadingEdgeWhenMovingToBottom() {
        final int lastItemPosition = mListView.getAdapter().getCount() - 1;
        for (int i = 0; i < lastItemPosition; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals(lastItemPosition, mListView.getSelectedItemPosition());

        assertEquals("bottom of last item should be just above padding; no fading edge.",
                mListView.getHeight() - mListView.getListPaddingBottom(),
                mListView.getSelectedView().getBottom());

    }

    // the top of the bottom fading edge takes into account the list padding at the bottom,
    // and the fading edge size
    private int getTopOfBottomFadingEdge() {
        return mListView.getHeight() - (mListView.getVerticalFadingEdgeLength() + mListView.getListPaddingBottom());
    }


}
