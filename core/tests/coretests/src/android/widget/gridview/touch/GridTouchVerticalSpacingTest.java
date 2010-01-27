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

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.TouchUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridView;

import android.widget.gridview.GridVerticalSpacing;

public class GridTouchVerticalSpacingTest extends ActivityInstrumentationTestCase<GridVerticalSpacing> {
    private GridVerticalSpacing mActivity;
    private GridView mGridView;

    public GridTouchVerticalSpacingTest() {
        super("com.android.frameworks.coretests", GridVerticalSpacing.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mGridView = getActivity().getGridView();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mGridView);

        assertEquals(0, mGridView.getSelectedItemPosition());
    }

    @MediumTest
    public void testNoScroll() {
        View firstChild = mGridView.getChildAt(0);
        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        int firstTop = firstChild.getTop();
        
        TouchUtils.dragViewBy(this, lastChild, Gravity.TOP | Gravity.LEFT, 
                0, -(ViewConfiguration.getTouchSlop()));
        
        View newFirstChild = mGridView.getChildAt(0);
        
        assertEquals("View scrolled too early", firstTop, newFirstChild.getTop());
        assertEquals("Wrong view in first position", 0, newFirstChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testShortScroll() {
        View firstChild = mGridView.getChildAt(0);
        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        int firstTop = firstChild.getTop();
        
        TouchUtils.dragViewBy(this, lastChild, Gravity.TOP | Gravity.LEFT,
                0, -(ViewConfiguration.getTouchSlop() + 1 + 10));
        
        View newFirstChild = mGridView.getChildAt(0);
        
        assertEquals("View scrolled to wrong position", firstTop, newFirstChild.getTop() + 10);
        assertEquals("Wrong view in first position", 0, newFirstChild.getId());
    }
    
    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @LargeTest
    public void testLongScroll() {
        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);
        
        int lastTop = lastChild.getTop();
        
        int distance = TouchUtils.dragViewToY(this, lastChild, Gravity.TOP | Gravity.LEFT,
                mGridView.getTop());
        
        assertEquals("View scrolled to wrong position", 
                lastTop - (distance - ViewConfiguration.getTouchSlop() - 1), lastChild.getTop());
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
        
        firstId = Integer.MIN_VALUE;
        firstTop = Integer.MIN_VALUE; 
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
        assertEquals("Grid is not scrolled to the top", 0, firstChild.getId());
    } 
}
