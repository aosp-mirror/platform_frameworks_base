/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget.listview.focus;

import android.test.ActivityInstrumentationTestCase;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.listview.ListButtonsDiagonalAcrossItems;

import androidx.test.filters.MediumTest;

/**
 * Test that ListView will override default behavior of focus searching to
 * make sure going right and left doesn't change selection
 */
public class ListButtonsDiagonalAcrossItemsTest extends ActivityInstrumentationTestCase<ListButtonsDiagonalAcrossItems> {

    private Button mLeftButton;
    private Button mCenterButton;
    private Button mRightButton;
    private ListView mListView;

    public ListButtonsDiagonalAcrossItemsTest() {
        super("com.android.frameworks.coretests", ListButtonsDiagonalAcrossItems.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mLeftButton = getActivity().getLeftButton();
        mCenterButton = getActivity().getCenterButton();
        mRightButton = getActivity().getRightButton();

        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        final ListView lv = mListView;
        assertEquals("num children", 3, lv.getChildCount());

        assertEquals("selected position", 0, lv.getSelectedItemPosition());
        assertTrue("left button focused", mLeftButton.isFocused());

        assertTrue("left left of center",
                mLeftButton.getRight()
                        < mCenterButton.getLeft());

        assertTrue("center left of right",
                mCenterButton.getRight()
                        < mRightButton.getLeft());

        assertEquals("focus search right from left button should be center button",
            mCenterButton,
            FocusFinder.getInstance().findNextFocus(mListView, mLeftButton, View.FOCUS_RIGHT));
        assertEquals("focus search right from center button should be right button",
            mRightButton,
            FocusFinder.getInstance().findNextFocus(mListView, mCenterButton, View.FOCUS_RIGHT));
        assertEquals("focus search left from centr button should be left button",
            mLeftButton,
            FocusFinder.getInstance().findNextFocus(mListView, mCenterButton, View.FOCUS_LEFT));
    }

    @MediumTest
    public void testGoingRightDoesNotChangeSelection() {
        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);

        assertEquals("selected position shouldn't have changed",
                0,
                mListView.getSelectedItemPosition());
        assertTrue("left should still be focused", mLeftButton.isFocused());
    }

    @MediumTest
    public void testGoingLeftDoesNotChangeSelection() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("list view postion", 1, mListView.getSelectedItemPosition());
        assertTrue("mCenterButton.isFocused()", mCenterButton.isFocused());

        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("selected position shouldn't have changed",
                1,
                mListView.getSelectedItemPosition());
        assertTrue("center should still be focused", mCenterButton.isFocused());
    }
    
}
