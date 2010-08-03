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

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.listview.ListWithScreenOfNoSelectables;

public class ListWithScreenOfNoSelectablesTest extends ActivityInstrumentationTestCase2<ListWithScreenOfNoSelectables> {

    private ListView mListView;

    public ListWithScreenOfNoSelectablesTest() {
        super(ListWithScreenOfNoSelectables.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("expecting first position to be selectable",
                mListView.getAdapter().isEnabled(0));
        final int numItems = mListView.getCount();
        for (int i = 1; i < numItems; i++) {
            assertFalse("expecting item to be unselectable (index " + i +")",
                    mListView.getAdapter().isEnabled(i));
        }
        assertTrue("expecting that not all views fit on screen",
                mListView.getChildCount() < mListView.getCount());
    }


    @MediumTest
    public void testGoFromSelectedViewExistsToNoSelectedViewExists() {

        // go down until first (and only selectable) item is off screen
        View first = mListView.getChildAt(0);
        while (first.getParent() != null) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        // nothing should be selected
        assertEquals("selected position", ListView.INVALID_POSITION, mListView.getSelectedItemPosition());
        assertNull("selected view", mListView.getSelectedView());
    }

    @MediumTest
    public void testPanDownAcrossUnselectableChildrenToBottom() {
        final int lastPosition = mListView.getCount() - 1;
        final int maxDowns = 20;
        for(int count = 0; count < maxDowns && mListView.getLastVisiblePosition() <= lastPosition; count++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        assertEquals("last visible position not the last position in the list even "
                + "after " + maxDowns + " downs", lastPosition, mListView.getLastVisiblePosition());
    }

    @MediumTest
    public void testGoFromNoSelectionToSelectionExists() {
        // go down untile first (and only selectable) item is off screen
        View first = mListView.getChildAt(0);
        while (first.getParent() != null) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        // nothing should be selected
        assertEquals("selected position", ListView.INVALID_POSITION, mListView.getSelectedItemPosition());
        assertNull("selected view", mListView.getSelectedView());

        // go up once to bring the selectable back on screen
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("first visible position", 0, mListView.getFirstVisiblePosition());
        assertEquals("selected position", ListView.INVALID_POSITION, mListView.getSelectedItemPosition());


        // up once more should give it selection
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("selected position", 0, mListView.getSelectedItemPosition());

    }


}
