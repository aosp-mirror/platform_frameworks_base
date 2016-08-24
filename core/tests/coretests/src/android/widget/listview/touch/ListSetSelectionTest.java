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
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.TouchUtils;
import android.test.suitebuilder.annotation.Suppress;
import android.view.View;
import android.widget.ListView;

import android.widget.listview.ListSimple;

/**
 * Tests setting the selection in touch mode
 */
@Suppress // Flaky.
public class ListSetSelectionTest extends ActivityInstrumentationTestCase<ListSimple> {
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
    
    @LargeTest
    public void testSetSelection() {
        TouchUtils.dragQuarterScreenDown(this);
        TouchUtils.dragQuarterScreenUp(this);
        
        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());
        
        final int targetPosition = mListView.getAdapter().getCount() / 2;
        
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mListView.setSelection(targetPosition);
            }
        });
        getInstrumentation().waitForIdleSync();
        
        boolean found = false;
        int childCount = mListView.getChildCount();
        for (int i=0; i<childCount; i++) {
            View child = mListView.getChildAt(i);
            if (child.getId() == targetPosition) {
                found = true;
                break;
            }
        }
        assertTrue("Selected item not visible in list", found);
    }
 
    @LargeTest
    public void testSetSelectionFromTop() {
        TouchUtils.dragQuarterScreenDown(this);
        TouchUtils.dragQuarterScreenUp(this);
        
        // Nothing should be selected
        assertEquals("Selection still available after touch", -1, 
                mListView.getSelectedItemPosition());
        
        final int targetPosition = mListView.getAdapter().getCount() / 2;
        
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mListView.setSelectionFromTop(targetPosition, 100);
            }
        });
        getInstrumentation().waitForIdleSync();
        
        View target = null;
        boolean found = false;
        int childCount = mListView.getChildCount();
        for (int i=0; i<childCount; i++) {
            View child = mListView.getChildAt(i);
            if (child.getId() == targetPosition) {
                target = child;
                found = true;
                break;
            }
        }
        assertTrue("Selected item not visible in list", found);
        
        if (target != null) {
            assertEquals("Selection not at correct location", 100 + mListView.getPaddingTop(), 
                    target.getTop());
        }
    }

    @LargeTest
    public void testSetSelection0() {
        TouchUtils.dragQuarterScreenDown(this);
        TouchUtils.dragQuarterScreenDown(this);
        TouchUtils.dragQuarterScreenDown(this);

        // Nothing should be selected
        assertEquals("Selection still available after touch", -1,
                mListView.getSelectedItemPosition());

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mListView.setSelection(0);
            }
        });
        getInstrumentation().waitForIdleSync();

        boolean found = false;
        int childCount = mListView.getChildCount();
        for (int i=0; i<childCount; i++) {
            View child = mListView.getChildAt(i);
            if (child.getId() == 0 && i == 0) {
                found = true;
                break;
            }
        }
        assertTrue("Selected item not visible in list", found);
    }
}
