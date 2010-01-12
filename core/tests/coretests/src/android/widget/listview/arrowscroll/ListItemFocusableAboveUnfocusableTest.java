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
import android.widget.listview.ListItemFocusableAboveUnfocusable;

public class ListItemFocusableAboveUnfocusableTest extends ActivityInstrumentationTestCase<ListItemFocusableAboveUnfocusable> {
    private ListView mListView;

    public ListItemFocusableAboveUnfocusableTest() {
        super("com.android.frameworks.coretests", ListItemFocusableAboveUnfocusable.class);
    }

    protected void setUp() throws Exception {
        super.setUp();

        mListView = getActivity().getListView();
    }


    @MediumTest
    public void testPreconditions() {
        assertEquals("selected position", 0, mListView.getSelectedItemPosition());
        assertTrue(mListView.getChildAt(0).isFocused());
        assertFalse(mListView.getChildAt(1).isFocusable());
    }

    @MediumTest
    public void testMovingToUnFocusableTakesFocusAway() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertFalse("focused item should have lost focus",
                mListView.getChildAt(0).isFocused());
        assertEquals("selected position", 1, mListView.getSelectedItemPosition());
    }

}
