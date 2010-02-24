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

package android.widget.expandablelistview;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.ExpandableListScenario;
import android.util.ListUtil;
import android.util.ExpandableListScenario.MyGroup;
import android.view.KeyEvent;
import android.view.View;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;

import java.util.List;

public class ExpandableListBasicTest extends ActivityInstrumentationTestCase2<ExpandableListSimple> {
    private ExpandableListScenario mActivity;
    private ExpandableListView mListView;
    private ExpandableListAdapter mAdapter;
    private ListUtil mListUtil;
    
    public ExpandableListBasicTest() {
        super(ExpandableListSimple.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        mActivity = getActivity();
        mListView = mActivity.getExpandableListView();
        mAdapter = mListView.getExpandableListAdapter();
        mListUtil = new ListUtil(mListView, getInstrumentation());
    }
    
    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mListView);
    }
    
    private int expandGroup(int numChildren, boolean atLeastOneChild) {
        final int groupPos = mActivity.findGroupWithNumChildren(numChildren, atLeastOneChild);
        
        assertTrue("Could not find group to expand", groupPos >= 0);
        assertFalse("Group is already expanded", mListView.isGroupExpanded(groupPos));
        mListUtil.arrowScrollToSelectedPosition(groupPos);
        getInstrumentation().waitForIdleSync();
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();
        assertTrue("Group did not expand", mListView.isGroupExpanded(groupPos));
        
        return groupPos;
    }

    @MediumTest
    public void testExpandGroup() {
        expandGroup(-1, true);
    }
    
    @MediumTest
    public void testCollapseGroup() {
        final int groupPos = expandGroup(-1, true);
        
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();
        assertFalse("Group did not collapse", mListView.isGroupExpanded(groupPos));
    }
    
    @MediumTest
    public void testExpandedGroupMovement() {
        // Expand the first group
        mListUtil.arrowScrollToSelectedPosition(0);
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();

        // Ensure it expanded
        assertTrue("Group did not expand", mListView.isGroupExpanded(0));
        
        // Wait until that's all good
        getInstrumentation().waitForIdleSync();
        
        // Make sure it expanded
        assertTrue("Group did not expand", mListView.isGroupExpanded(0));
        
        // Insert a collapsed group in front of the one just expanded
        List<MyGroup> groups = mActivity.getGroups();
        MyGroup insertedGroup = new MyGroup(1);
        groups.add(0, insertedGroup);
        
        // Notify data change
        assertTrue("Adapter is not an instance of the base adapter",
                mAdapter instanceof BaseExpandableListAdapter);
        final BaseExpandableListAdapter adapter = (BaseExpandableListAdapter) mAdapter;
     
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
        getInstrumentation().waitForIdleSync();
        
        // Make sure the right group is expanded
        assertTrue("The expanded state didn't stay with the proper group",
                mListView.isGroupExpanded(1));
        assertFalse("The expanded state was given to the inserted group",
                mListView.isGroupExpanded(0));
    }

    // Static utility method, shared by different ExpandableListView scenario.
    static void checkGroupAndChildPositions(ExpandableListView elv,
            ActivityInstrumentationTestCase2<? extends ExpandableListScenario> activityInstrumentation) {
        // Add a position tester ContextMenu listener to the ExpandableListView
        PositionTesterContextMenuListener menuListener = new PositionTesterContextMenuListener();
        elv.setOnCreateContextMenuListener(menuListener);

        ListUtil listUtil = new ListUtil(elv, activityInstrumentation.getInstrumentation());
        ExpandableListAdapter adapter = elv.getExpandableListAdapter();
        Instrumentation instrumentation = activityInstrumentation.getInstrumentation();

        int index = elv.getHeaderViewsCount();
        int groupCount = adapter.getGroupCount();
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {

            // Expand group
            assertFalse("Group is already expanded", elv.isGroupExpanded(groupIndex));
            listUtil.arrowScrollToSelectedPosition(index);
            instrumentation.waitForIdleSync();
            activityInstrumentation.sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
            activityInstrumentation.getInstrumentation().waitForIdleSync();
            assertTrue("Group did not expand " + groupIndex, elv.isGroupExpanded(groupIndex));

            // Check group index in context menu
            menuListener.expectGroupContextMenu(groupIndex);
            // Make sure the group is visible so that getChild finds it
            listUtil.arrowScrollToSelectedPosition(index);
            View groupChild = elv.getChildAt(index - elv.getFirstVisiblePosition());
            elv.showContextMenuForChild(groupChild);
            index++;

            final int childrenCount = adapter.getChildrenCount(groupIndex);
            for (int childIndex = 0; childIndex < childrenCount; childIndex++) {
                // Check child index in context menu
                listUtil.arrowScrollToSelectedPosition(index);
                menuListener.expectChildContextMenu(groupIndex, childIndex);
                View child = elv.getChildAt(index - elv.getFirstVisiblePosition());
                elv.showContextMenuForChild(child);
                index++;
            }
        }

        // Cleanup: remove the listener we added.
        elv.setOnCreateContextMenuListener(null);
    }

    @MediumTest
    public void testGroupChildPositions() {
        checkGroupAndChildPositions(mListView, this);
    }
}
