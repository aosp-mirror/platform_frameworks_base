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
import android.widget.TextView;
import android.view.KeyEvent;
import android.widget.listview.ListWithOnItemSelectedAction;

public class ListWithOnItemSelectedActionTest extends ActivityInstrumentationTestCase<ListWithOnItemSelectedAction> {
    private ListView mListView;

    public ListWithOnItemSelectedActionTest() {
        super("com.android.frameworks.coretests", ListWithOnItemSelectedAction.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mListView = getActivity().getListView();
    }

    private String getValueOfSelectedTextView() {
        return ((TextView) mListView.getSelectedView()).getText().toString();
    }

    @MediumTest
    public void testPreconditions() {
        assertEquals(0, mListView.getSelectedItemPosition());
        assertEquals("header text field should be echoing contents of selected item",
                getValueOfSelectedTextView(),
                getActivity().getHeaderValue());
    }

    @MediumTest
    public void testHeaderEchoesSelectionAfterMove() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertEquals(1, mListView.getSelectedItemPosition());
        assertEquals("header text field should be echoing contents of selected item",
                getValueOfSelectedTextView(),
                getActivity().getHeaderValue());
    }
}
