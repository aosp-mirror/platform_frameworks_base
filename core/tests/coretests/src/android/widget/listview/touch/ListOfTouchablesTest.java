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
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ListView;
import android.widget.listview.ListOfTouchables;

import androidx.test.filters.MediumTest;

/**
 * Touch tests for a list where all of the items fit on the screen.
 */
public class ListOfTouchablesTest extends ActivityInstrumentationTestCase<ListOfTouchables> {
    private ListOfTouchables mActivity;
    private ListView mListView;

    public ListOfTouchablesTest() {
        super("com.android.frameworks.coretests", ListOfTouchables.class);
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
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testShortScroll() {
        View firstChild = mListView.getChildAt(0);
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        int firstTop = firstChild.getTop();
        
        TouchUtils.dragViewBy(this, lastChild, Gravity.TOP | Gravity.LEFT,
                0, -(ViewConfiguration.getTouchSlop() + 1 + 10));
        
        View newFirstChild = mListView.getChildAt(0);
        
        assertEquals("View scrolled too early", firstTop, newFirstChild.getTop() + 10);
        assertEquals("Wrong view in first position", 0, newFirstChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testLongScroll() {
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        int lastTop = lastChild.getTop();
        
        int distance = TouchUtils.dragViewToY(this, lastChild, 
                Gravity.TOP | Gravity.LEFT, mListView.getTop());
        
        assertEquals("View scrolled to wrong position", 
                lastTop - (distance - ViewConfiguration.getTouchSlop() - 1), lastChild.getTop());
    } 

}
