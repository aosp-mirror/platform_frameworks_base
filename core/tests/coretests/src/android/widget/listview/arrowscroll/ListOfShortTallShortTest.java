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
import android.widget.ListView;
import android.view.KeyEvent;
import android.widget.listview.ListOfShortTallShort;

public class ListOfShortTallShortTest extends ActivityInstrumentationTestCase<ListOfShortTallShort> {
    private ListView mListView;

    public ListOfShortTallShortTest() {
        super("com.android.frameworks.coretests", ListOfShortTallShort.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("second item should be taller than screen",
                mListView.getChildAt(1).getHeight() > mListView.getHeight());
    }

    @MediumTest
    public void testGoDownFromShortToTall() {
        int topBeforeMove = mListView.getChildAt(1).getTop();
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertEquals("selection should have moved to tall item below",
                1, mListView.getSelectedItemPosition());
        assertEquals("should not have scrolled; top should be the same.",
                topBeforeMove,
                mListView.getSelectedView().getTop());
    }

    @MediumTest
    public void testGoUpFromShortToTall() {
        int maxMoves = 8;
        while (mListView.getSelectedItemPosition() != 2 && maxMoves > 0) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals("couldn't get to 3rd item",
                2,
                mListView.getSelectedItemPosition());

        assertEquals("should only be two items on screen",
                2, mListView.getChildCount());
        assertEquals("selected item should be last item on screen",
                mListView.getChildAt(1), mListView.getSelectedView());

        final int bottomBeforeMove = mListView.getChildAt(0).getBottom();
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("should have moved selection to tall item above",
                1, mListView.getSelectedItemPosition());
        assertEquals("should not have scrolled, top should be the same",
                bottomBeforeMove,
                mListView.getChildAt(0).getBottom());
    }
}
