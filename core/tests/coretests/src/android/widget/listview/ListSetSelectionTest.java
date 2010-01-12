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

package android.widget.listview;

import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.ListView;

/**
 * Basic tests of setting & clearing the selection
 */
public class ListSetSelectionTest extends ActivityInstrumentationTestCase2<ListSimple> {
    private ListSimple mActivity;
    private ListView mListView;

    public ListSetSelectionTest() {
        super("com.android.frameworks.coretests", ListSimple.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mListView);
    }
    
    /** Confirm that we can set the selection to each specific position */
    @MediumTest
    @UiThreadTest
    public void testSetSelection() {
        // Set the selection to each position
        int childCount = mListView.getChildCount();
        for (int i=0; i<childCount; i++) {
            mListView.setSelection(i);
            assertEquals("Set selection", i, mListView.getSelectedItemPosition());
        }
    }
    
    /** Confirm that you cannot unset the selection using the same API */
    @MediumTest
    @UiThreadTest
    public void testClearSelection() {
        // Set the selection to first position
        mListView.setSelection(0);
        assertEquals("Set selection", 0, mListView.getSelectedItemPosition());

        // Clear the selection
        mListView.setSelection(ListView.INVALID_POSITION);
        assertEquals("Set selection", 0, mListView.getSelectedItemPosition());
    }
}
