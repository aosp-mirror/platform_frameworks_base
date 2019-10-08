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
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.listview.ListLastItemPartiallyVisible;

import androidx.test.filters.MediumTest;

public class ListLastItemPartiallyVisibleTest extends ActivityInstrumentationTestCase<ListLastItemPartiallyVisible> {
    private ListView mListView;
    private int mListBottom;


    public ListLastItemPartiallyVisibleTest() {
        super("com.android.frameworks.coretests", ListLastItemPartiallyVisible.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
        mListBottom = mListView.getHeight() - mListView.getPaddingBottom();
    }

    @MediumTest
    public void testPreconditions() {
        assertEquals("number of elements visible should be the same as number of items " +
                "in adapter", mListView.getCount(), mListView.getChildCount());

        final View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        assertTrue("last item should be partially off screen",
                lastChild.getBottom() > mListBottom);
        assertEquals("selected position", 0, mListView.getSelectedItemPosition());
    }

    // reproduce bug 998094
    @MediumTest
    public void testMovingDownToFullyVisibleNoScroll() {
        final View firstChild = mListView.getChildAt(0);
        final int firstBottom = firstChild.getBottom();
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("shouldn't have scrolled: bottom of first child changed.",
                firstBottom, firstChild.getBottom());
    }

    // reproduce bug 998094
    @MediumTest
    public void testMovingUpToFullyVisibleNoScroll() {
        int numMovesToLast = mListView.getCount() - 1;
        for (int i = 0; i < numMovesToLast; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals("should have moved to last position",
                mListView.getChildCount() - 1, mListView.getSelectedItemPosition());

        final View lastChild = mListView.getSelectedView();
        final int lastTop = lastChild.getTop();
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("shouldn't have scrolled: top of last child changed.",
                lastTop, lastChild.getTop());
    }

}
