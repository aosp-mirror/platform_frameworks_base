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

package com.android.frameworktest.expandablelistview;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.ExpandableListView;

import com.android.frameworktest.expandablelistview.ExpandableListWithHeaders;
import com.android.frameworktest.util.ListUtil;

public class ExpandableListWithHeadersTest extends ActivityInstrumentationTestCase<ExpandableListWithHeaders> {
    private ExpandableListView mExpandableListView;
    private ListUtil mListUtil;
    
    public ExpandableListWithHeadersTest() {
        super("com.android.frameworktest",
                ExpandableListWithHeaders.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        mExpandableListView = getActivity().getExpandableListView();
        mListUtil = new ListUtil(mExpandableListView, getInstrumentation());
    }
    
    @MediumTest
    public void testPreconditions() {
        assertNotNull(mExpandableListView);
    }
    
    @MediumTest
    public void testExpandOnFirstPosition() {
        // Should be a header, and hence the first group should NOT have expanded
        mListUtil.arrowScrollToSelectedPosition(0);
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();
        assertFalse(mExpandableListView.isGroupExpanded(0));
    }

    @LargeTest
    public void testExpandOnFirstGroup() {
        mListUtil.arrowScrollToSelectedPosition(getActivity().getNumOfHeadersAndFooters());
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();
        assertTrue(mExpandableListView.isGroupExpanded(0));
    }
}
