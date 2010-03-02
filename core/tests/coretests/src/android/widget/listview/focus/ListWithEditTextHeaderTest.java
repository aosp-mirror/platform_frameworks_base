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

import android.test.ActivityInstrumentationTestCase2;
import android.test.FlakyTest;
import android.test.TouchUtils;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.listview.ListWithEditTextHeader;

public class ListWithEditTextHeaderTest extends ActivityInstrumentationTestCase2<ListWithEditTextHeader> {
    private ListView mListView;

    public ListWithEditTextHeaderTest() {
        super(ListWithEditTextHeader.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("listview.getItemsCanFocus()", mListView.getItemsCanFocus());
        assertFalse("out of touch-mode", mListView.isInTouchMode());
        assertEquals("header view count", 1, mListView.getHeaderViewsCount());
        assertTrue("header does not have focus", mListView.getChildAt(0).isFocused());
    }

    @FlakyTest(tolerance=2)
    @LargeTest
    public void testClickingHeaderKeepsFocus() {
        TouchUtils.clickView(this, mListView.getChildAt(0));
        assertTrue("header does not have focus", mListView.getChildAt(0).isFocused());
        assertEquals("something is selected", AbsListView.INVALID_POSITION, mListView.getSelectedItemPosition());
    }

    @LargeTest
    public void testClickingHeaderWhenOtherItemHasFocusGivesHeaderFocus() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("selected position", 1, mListView.getSelectedItemPosition());
        TouchUtils.clickView(this, mListView.getChildAt(0));
        assertTrue("header does not have focus", mListView.getChildAt(0).isFocused());
        assertEquals("something is selected", AbsListView.INVALID_POSITION, mListView.getSelectedItemPosition());
    }
}
