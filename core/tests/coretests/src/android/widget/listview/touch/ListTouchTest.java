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

package android.widget.listview.touch;

import android.test.ActivityInstrumentationTestCase;
import android.test.TouchUtils;
import android.view.View;
import android.widget.ListView;
import android.widget.listview.ListTopGravity;

import androidx.test.filters.MediumTest;

/**
 * Touch tests for a list where all of the items fit on the screen.
 */
public class ListTouchTest extends ActivityInstrumentationTestCase<ListTopGravity> {
    private ListTopGravity mActivity;
    private ListView mListView;

    public ListTouchTest() {
        super("com.android.frameworks.coretests", ListTopGravity.class);
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
        
        // First item should be selected
        assertEquals(0, mListView.getSelectedItemPosition());
    }
    
    @MediumTest
    public void testPullDown() {
        View firstChild = mListView.getChildAt(0);
        
        TouchUtils.dragViewToBottom(this, firstChild);
        
        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());
        
        firstChild = mListView.getChildAt(0);
        
        assertEquals("Item zero not the first child in the list", 0, firstChild.getId());
        
        assertEquals("Item zero not at the top of the list", mListView.getListPaddingTop(),
                firstChild.getTop());
    }
    
    @MediumTest
    public void testPushUp() {
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        TouchUtils.dragViewToTop(this, lastChild);

        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());

        View firstChild = mListView.getChildAt(0);

        assertEquals("Item zero not the first child in the list", 0, firstChild.getId());
        
        assertEquals("Item zero not at the top of the list", mListView.getListPaddingTop(),
                firstChild.getTop());
    }

}
