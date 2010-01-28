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

package android.widget.gridview.touch;

import android.content.Context;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.TouchUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridView;

import android.widget.gridview.GridVerticalSpacingStackFromBottom;

public class GridTouchVerticalSpacingStackFromBottomTest extends ActivityInstrumentationTestCase<GridVerticalSpacingStackFromBottom> {
    private GridVerticalSpacingStackFromBottom mActivity;
    private GridView mGridView;
    private ViewConfiguration mViewConfig;

    public GridTouchVerticalSpacingStackFromBottomTest() {
        super("com.android.frameworks.coretests", GridVerticalSpacingStackFromBottom.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mGridView = getActivity().getGridView();
        final Context context = mActivity.getApplicationContext();
        mViewConfig = ViewConfiguration.get(context);
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mGridView);

        // Last item should be selected
        assertEquals(mGridView.getAdapter().getCount() - 1, mGridView.getSelectedItemPosition());

    }

    @MediumTest
    public void testNoScroll() {
        View firstChild = mGridView.getChildAt(0);
        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        int lastTop = lastChild.getTop();
        
        TouchUtils.dragViewBy(this, firstChild, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0,
                ViewConfiguration.getTouchSlop());
        
        View newLastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        assertEquals("View scrolled too early", lastTop, newLastChild.getTop());
        assertEquals("Wrong view in last position", mGridView.getAdapter().getCount() - 1, 
                newLastChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testShortScroll() {
        View firstChild = mGridView.getChildAt(0);
        if (firstChild.getTop() < this.mGridView.getListPaddingTop()) {
            firstChild = mGridView.getChildAt(1);
        }
            
        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        int lastTop = lastChild.getTop();
        
        TouchUtils.dragViewBy(this, firstChild, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0,
                mViewConfig.getScaledTouchSlop() + 1 + 10);
        
        View newLastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        assertEquals("View scrolled to wrong position", lastTop, newLastChild.getTop() - 10);
        assertEquals("Wrong view in last position", mGridView.getAdapter().getCount() - 1,
                newLastChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testLongScroll() {
        View firstChild = mGridView.getChildAt(0);
        if (firstChild.getTop() < mGridView.getListPaddingTop()) {
            firstChild = mGridView.getChildAt(1);
        }

        int firstTop = firstChild.getTop();

        int distance = TouchUtils.dragViewBy(this, firstChild, 
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 
                (int) (mActivity.getWindowManager().getDefaultDisplay().getHeight() * 0.75f));
        
        assertEquals("View scrolled to wrong position", firstTop
                + (distance - mViewConfig.getScaledTouchSlop() - 1), firstChild.getTop());
    } 
    
    @LargeTest
    public void testManyScrolls() {
        int originalCount = mGridView.getChildCount();
        
        View firstChild;
        int firstId = Integer.MIN_VALUE;
        int firstTop = Integer.MIN_VALUE; 
        int prevId;
        int prevTop; 
        do {
            prevId = firstId;
            prevTop = firstTop;
            TouchUtils.dragQuarterScreenDown(this);
            assertTrue(String.format("Too many children created: %d expected no more than %d", 
                    mGridView.getChildCount(), originalCount + 4), 
                    mGridView.getChildCount() <= originalCount + 4);
            firstChild = mGridView.getChildAt(0);
            firstId = firstChild.getId();
            firstTop = firstChild.getTop(); 
        } while ((prevId != firstId) || (prevTop != firstTop));
        
        
        firstChild = mGridView.getChildAt(0);
        assertEquals("View scrolled to wrong position", 0, firstChild.getId());
        
        firstId = Integer.MIN_VALUE;
        firstTop = Integer.MIN_VALUE; 
        do {
            prevId = firstId;
            prevTop = firstTop;
            TouchUtils.dragQuarterScreenUp(this);
            assertTrue(String.format("Too many children created: %d expected no more than %d", 
                    mGridView.getChildCount(), originalCount + 4), 
                    mGridView.getChildCount() <= originalCount + 4);
            firstChild = mGridView.getChildAt(0);
            firstId = firstChild.getId();
            firstTop = firstChild.getTop(); 
        } while ((prevId != firstId) || (prevTop != firstTop));
        
        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        assertEquals("Grid is not scrolled to the bottom", mGridView.getAdapter().getCount() - 1,
                lastChild.getId());
    } 
}
