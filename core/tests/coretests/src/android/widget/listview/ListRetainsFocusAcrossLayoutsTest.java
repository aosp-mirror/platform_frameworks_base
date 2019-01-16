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

package android.widget.listview;

import android.test.ActivityInstrumentationTestCase;
import android.view.KeyEvent;

import androidx.test.filters.MediumTest;

public class ListRetainsFocusAcrossLayoutsTest extends ActivityInstrumentationTestCase<ListItemFocusablesClose> {

    public ListRetainsFocusAcrossLayoutsTest() {
        super("com.android.frameworks.coretests", ListItemFocusablesClose.class);
    }

    private void requestLayoutOnList() {
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                getActivity().getListView().requestLayout();
            }
        });
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("top button at position 0 should be focused",
                getActivity().getChildOfItem(0, 0).isFocused());
    }

    @MediumTest
    public void testBottomButtonRetainsFocusAfterLayout() throws Exception {

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertTrue("bottom botton at position 0 should be focused",
                getActivity().getChildOfItem(0, 2).isFocused());

        requestLayoutOnList();
        getInstrumentation().waitForIdleSync();

        assertTrue("bottom botton at position 0 should be focused after layout",
                getActivity().getChildOfItem(0, 2).isFocused());
    }

    @MediumTest
    public void testTopButtonOfSecondPositionRetainsFocusAfterLayout() {
        sendRepeatedKeys(2, KeyEvent.KEYCODE_DPAD_DOWN);

        assertTrue("top botton at position 1 should be focused",
                getActivity().getChildOfItem(1, 0).isFocused());

        requestLayoutOnList();
        getInstrumentation().waitForIdleSync();

        assertTrue("top botton at position 1 should be focused after layout",
                getActivity().getChildOfItem(1, 0).isFocused());

    }

    @MediumTest
    public void testBottomButtonOfSecondPositionRetainsFocusAfterLayout() {
        sendRepeatedKeys(3, KeyEvent.KEYCODE_DPAD_DOWN);

        assertTrue("bottom botton at position 1 should be focused",
                getActivity().getChildOfItem(1, 2).isFocused());

        requestLayoutOnList();
        getInstrumentation().waitForIdleSync();

        assertTrue("bottom botton at position 1 should be focused after layout",
                getActivity().getChildOfItem(1, 2).isFocused());
    }
}
