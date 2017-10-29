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

package android.widget.listview.arrowscroll;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.ListView;
import android.widget.listview.ListWithNoFadingEdge;

public class ListWithNoFadingEdgeTest extends ActivityInstrumentationTestCase<ListWithNoFadingEdge> {

    private ListView mListView;

    public ListWithNoFadingEdgeTest() {
        super("com.android.frameworks.coretests", ListWithNoFadingEdge.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mListView);
        assertEquals("listview vertical fading edge", 0, mListView.getVerticalFadingEdgeLength());
        assertTrue("expecting that not all views fit on screen",
                mListView.getChildCount() < mListView.getCount());
    }

    @MediumTest
    public void testScrollDownToBottom() {
        getActivity().runOnUiThread(() -> mListView.requestFocus());
        getInstrumentation().waitForIdleSync();
        final int numItems = mListView.getCount();

        for (int i = 0; i < numItems; i++) {
            assertEquals("selected position", i, mListView.getSelectedItemPosition());
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals("selected position", numItems - 1, mListView.getSelectedItemPosition());
    }

    @LargeTest
    public void testScrollFromBottomToTop() {
        getActivity().runOnUiThread(() -> mListView.requestFocus());
        getInstrumentation().waitForIdleSync();
        final int numItems = mListView.getCount();

        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                mListView.setSelection(numItems - 1);
            }
        });
        getInstrumentation().waitForIdleSync();

        for (int i = numItems - 1; i >=0; i--) {
            assertEquals(i, mListView.getSelectedItemPosition());
            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        }

        assertEquals("selected position", 0, mListView.getSelectedItemPosition());
    }

}
