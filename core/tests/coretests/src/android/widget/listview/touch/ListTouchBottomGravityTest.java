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
import android.widget.listview.ListBottomGravity;

import androidx.test.filters.MediumTest;

/**
 * Touch tests for a list where all of the items fit on the screen, and the list 
 * stacks from the bottom.
 */
public class ListTouchBottomGravityTest extends ActivityInstrumentationTestCase<ListBottomGravity> {
    private ListBottomGravity mActivity;
    private ListView mListView;

    public ListTouchBottomGravityTest() {
        super("com.android.frameworks.coretests", ListBottomGravity.class);
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
        assertEquals(mListView.getAdapter().getCount() - 1, mListView.getSelectedItemPosition());
    }
    
    @MediumTest
    public void testPullDown() {
        View firstChild = mListView.getChildAt(0);
        
        TouchUtils.dragViewToBottom(this, firstChild);
        
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());
        
        assertEquals("List is not scrolled to the bottom", mListView.getAdapter().getCount() - 1,
                lastChild.getId());

        assertEquals("Last item is not touching the bottom edge", 
                mListView.getHeight() - mListView.getListPaddingBottom(), lastChild.getBottom());
    }
    
    @MediumTest
    public void testPushUp() {
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        TouchUtils.dragViewToTop(this, lastChild);

        lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());
        
        assertEquals("List is not scrolled to the bottom", mListView.getAdapter().getCount() - 1,
                lastChild.getId());

        assertEquals("Last item is not touching the bottom edge",  
                mListView.getHeight() - mListView.getListPaddingBottom(), lastChild.getBottom());
    }

}
