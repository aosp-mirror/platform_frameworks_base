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
import android.widget.listview.ListBottomGravityMany;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

/**
 * Touch tests for a list where all of the items do not fit on the screen, and the list 
 * stacks from the bottom.
 */
public class ListTouchBottomGravityManyTest extends ActivityInstrumentationTestCase<ListBottomGravityMany> {
    private ListBottomGravityMany mActivity;
    private ListView mListView;

    public ListTouchBottomGravityManyTest() {
        super("com.android.frameworks.coretests", ListBottomGravityMany.class);
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
        
        // Last item should be selected
        assertEquals(mListView.getAdapter().getCount() - 1, mListView.getSelectedItemPosition());
    }
    
    @LargeTest
    public void testPullDown() {     
        int originalCount = mListView.getChildCount();
        
        TouchUtils.scrollToTop(this, mListView);
        
        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());
        
        View firstChild = mListView.getChildAt(0);
        
        assertEquals("Item zero not the first child in the list", 0, firstChild.getId());
        
        assertEquals("Item zero not at the top of the list", mListView.getListPaddingTop(),
                firstChild.getTop());
        
        assertTrue(String.format("Too many children created: %d expected no more than %d", 
                mListView.getChildCount(), originalCount + 1), 
                mListView.getChildCount() <= originalCount + 1);
    }
    
    @MediumTest
    public void testPushUp() {
        TouchUtils.scrollToBottom(this, mListView);

        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());

        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);

        assertEquals("List is not scrolled to the bottom", mListView.getAdapter().getCount() - 1,
                lastChild.getId());

        assertEquals("Last item is not touching the bottom edge", 
                mListView.getHeight() - mListView.getListPaddingBottom(), lastChild.getBottom());
    }
    
    @MediumTest
    public void testNoScroll() {
        View firstChild = mListView.getChildAt(0);
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        int lastTop = lastChild.getTop();
        
        TouchUtils.dragViewBy(this, firstChild, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0, ViewConfiguration.getTouchSlop());
        
        View newLastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        assertEquals("View scrolled too early", lastTop, newLastChild.getTop());
        assertEquals("Wrong view in last position", mListView.getAdapter().getCount() - 1, 
                newLastChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testShortScroll() {
        View firstChild = mListView.getChildAt(0);
        if (firstChild.getTop() < this.mListView.getListPaddingTop()) {
            firstChild = mListView.getChildAt(1);
        }
            
        View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        int lastTop = lastChild.getTop();
        
        TouchUtils.dragViewBy(this, firstChild, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0, ViewConfiguration.getTouchSlop() + 1 + 10);
        
        View newLastChild = mListView.getChildAt(mListView.getChildCount() - 1);
        
        assertEquals("View scrolled to wrong position", lastTop, newLastChild.getTop() - 10);
        assertEquals("Wrong view in last position", mListView.getAdapter().getCount() - 1,
                newLastChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testLongScroll() {
        View firstChild = mListView.getChildAt(0);
        if (firstChild.getTop() < mListView.getListPaddingTop()) {
            firstChild = mListView.getChildAt(1);
        }

        int firstTop = firstChild.getTop();

        int distance = TouchUtils.dragViewBy(this, firstChild, 
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 
                (int)(mActivity.getWindowManager().getDefaultDisplay().getHeight() * 0.75f));
        
        assertEquals("View scrolled to wrong position", firstTop
                + (distance - ViewConfiguration.getTouchSlop() - 1), firstChild.getTop());
    } 

}
