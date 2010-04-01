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
import android.util.ExpandableListScenario;
import android.util.ListUtil;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;

import junit.framework.Assert;

public class ExpandableListTester {
    private final ExpandableListView mExpandableListView;
    private final ExpandableListAdapter mAdapter;
    private final ListUtil mListUtil;

    private final ActivityInstrumentationTestCase2<? extends ExpandableListScenario>
        mActivityInstrumentation;

    Instrumentation mInstrumentation;

    public ExpandableListTester(
            ExpandableListView expandableListView,
            ActivityInstrumentationTestCase2<? extends ExpandableListScenario>
            activityInstrumentation) {
        mExpandableListView = expandableListView;
        Instrumentation instrumentation = activityInstrumentation.getInstrumentation();
        mListUtil = new ListUtil(mExpandableListView, instrumentation);
        mAdapter = mExpandableListView.getExpandableListAdapter();
        mActivityInstrumentation = activityInstrumentation;
        mInstrumentation = mActivityInstrumentation.getInstrumentation();
    }

    private void expandGroup(final int groupIndex, int flatPosition) {
        Assert.assertFalse("Group is already expanded", mExpandableListView
                .isGroupExpanded(groupIndex));
        mListUtil.arrowScrollToSelectedPosition(flatPosition);
        mInstrumentation.waitForIdleSync();
        mActivityInstrumentation.sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        mActivityInstrumentation.getInstrumentation().waitForIdleSync();
        Assert.assertTrue("Group did not expand " + groupIndex, 
                mExpandableListView.isGroupExpanded(groupIndex));
    }

    void testContextMenus() {
        // Add a position tester ContextMenu listener to the ExpandableListView
        PositionTesterContextMenuListener menuListener = new PositionTesterContextMenuListener();
        mExpandableListView.setOnCreateContextMenuListener(menuListener);

        int index = 0;

        // Scrolling on header elements should trigger an AdapterContextMenu
        for (int i=0; i<mExpandableListView.getHeaderViewsCount(); i++) {
            // Check group index in context menu
            menuListener.expectAdapterContextMenu(i);
            // Make sure the group is visible so that getChild finds it
            mListUtil.arrowScrollToSelectedPosition(index);
            View headerChild = mExpandableListView.getChildAt(index
                    - mExpandableListView.getFirstVisiblePosition());
            mExpandableListView.showContextMenuForChild(headerChild);
            mInstrumentation.waitForIdleSync();
            Assert.assertNull(menuListener.getErrorMessage(), menuListener.getErrorMessage());
            index++;
        }

        int groupCount = mAdapter.getGroupCount();
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {

            // Expand group
            expandGroup(groupIndex, index);

            // Check group index in context menu
            menuListener.expectGroupContextMenu(groupIndex);
            // Make sure the group is visible so that getChild finds it
            mListUtil.arrowScrollToSelectedPosition(index);
            View groupChild = mExpandableListView.getChildAt(index
                    - mExpandableListView.getFirstVisiblePosition());
            mExpandableListView.showContextMenuForChild(groupChild);
            mInstrumentation.waitForIdleSync();
            Assert.assertNull(menuListener.getErrorMessage(), menuListener.getErrorMessage());
            index++;

            final int childrenCount = mAdapter.getChildrenCount(groupIndex);
            for (int childIndex = 0; childIndex < childrenCount; childIndex++) {
                // Check child index in context menu
                mListUtil.arrowScrollToSelectedPosition(index);
                menuListener.expectChildContextMenu(groupIndex, childIndex);
                View child = mExpandableListView.getChildAt(index
                        - mExpandableListView.getFirstVisiblePosition());
                mExpandableListView.showContextMenuForChild(child);
                mInstrumentation.waitForIdleSync();
                Assert.assertNull(menuListener.getErrorMessage(), menuListener.getErrorMessage());
                index++;
            }
        }

        // Scrolling on footer elements should trigger an AdapterContextMenu
        for (int i=0; i<mExpandableListView.getFooterViewsCount(); i++) {
            // Check group index in context menu
            menuListener.expectAdapterContextMenu(index);
            // Make sure the group is visible so that getChild finds it
            mListUtil.arrowScrollToSelectedPosition(index);
            View footerChild = mExpandableListView.getChildAt(index
                    - mExpandableListView.getFirstVisiblePosition());
            mExpandableListView.showContextMenuForChild(footerChild);
            mInstrumentation.waitForIdleSync();
            Assert.assertNull(menuListener.getErrorMessage(), menuListener.getErrorMessage());
            index++;
        }

        // Cleanup: remove the listener we added.
        mExpandableListView.setOnCreateContextMenuListener(null);
    }

    private int expandAGroup() {
        final int groupIndex = 2;
        final int headerCount = mExpandableListView.getHeaderViewsCount();
        Assert.assertTrue("Not enough groups", groupIndex < mAdapter.getGroupCount());
        expandGroup(groupIndex, groupIndex + headerCount);
        return groupIndex;
    }

    // This method assumes that NO group is expanded when called
    void testConvertionBetweenFlatAndPackedOnGroups() {
        final int headerCount = mExpandableListView.getHeaderViewsCount();

        for (int i=0; i<headerCount; i++) {
            Assert.assertEquals("Non NULL position for header item",
                    ExpandableListView.PACKED_POSITION_VALUE_NULL,
                    mExpandableListView.getExpandableListPosition(i));
        }

        // Test all (non expanded) groups
        final int groupCount = mAdapter.getGroupCount();
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            int expectedFlatPosition = headerCount + groupIndex;
            long packedPositionForGroup = ExpandableListView.getPackedPositionForGroup(groupIndex);
            Assert.assertEquals("Group not found at flat position " + expectedFlatPosition,
                    packedPositionForGroup,
                    mExpandableListView.getExpandableListPosition(expectedFlatPosition));

            Assert.assertEquals("Wrong flat position for group " + groupIndex,
                    expectedFlatPosition,
                    mExpandableListView.getFlatListPosition(packedPositionForGroup));
        }

        for (int i=0; i<mExpandableListView.getFooterViewsCount(); i++) {
            Assert.assertEquals("Non NULL position for header item",
                    ExpandableListView.PACKED_POSITION_VALUE_NULL,
                    mExpandableListView.getExpandableListPosition(headerCount + groupCount + i));
        }
    }

    // This method assumes that NO group is expanded when called
    void testConvertionBetweenFlatAndPackedOnChildren() {
        // Test with an expanded group
        final int headerCount = mExpandableListView.getHeaderViewsCount();
        final int groupIndex = expandAGroup();

        final int childrenCount = mAdapter.getChildrenCount(groupIndex);
        for (int childIndex = 0; childIndex < childrenCount; childIndex++) {
            int expectedFlatPosition = headerCount + groupIndex + 1 + childIndex;
            long childPos = ExpandableListView.getPackedPositionForChild(groupIndex, childIndex);

            Assert.assertEquals("Wrong flat position for child ",
                    childPos,
                    mExpandableListView.getExpandableListPosition(expectedFlatPosition));

            Assert.assertEquals("Wrong flat position for child ",
                    expectedFlatPosition,
                    mExpandableListView.getFlatListPosition(childPos));
        }
    }

    // This method assumes that NO group is expanded when called
    void testSelectedPositionOnGroups() {
        int index = 0;

        // Scrolling on header elements should not give a valid selected position.
        for (int i=0; i<mExpandableListView.getHeaderViewsCount(); i++) {
            mListUtil.arrowScrollToSelectedPosition(index);
            Assert.assertEquals("Header item is selected",
                    ExpandableListView.PACKED_POSITION_VALUE_NULL,
                    mExpandableListView.getSelectedPosition());
            index++;
        }

        // Check selection on group items
        final int groupCount = mAdapter.getGroupCount();
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            mListUtil.arrowScrollToSelectedPosition(index);
            Assert.assertEquals("Group item is not selected",
                    ExpandableListView.getPackedPositionForGroup(groupIndex),
                    mExpandableListView.getSelectedPosition());
            index++;
        }

        // Scrolling on footer elements should not give a valid selected position.
        for (int i=0; i<mExpandableListView.getFooterViewsCount(); i++) {
            mListUtil.arrowScrollToSelectedPosition(index);
            Assert.assertEquals("Footer item is selected",
                    ExpandableListView.PACKED_POSITION_VALUE_NULL,
                    mExpandableListView.getSelectedPosition());
            index++;
        }
    }

    // This method assumes that NO group is expanded when called
    void testSelectedPositionOnChildren() {
        // Test with an expanded group
        final int headerCount = mExpandableListView.getHeaderViewsCount();
        final int groupIndex = expandAGroup();

        final int childrenCount = mAdapter.getChildrenCount(groupIndex);
        for (int childIndex = 0; childIndex < childrenCount; childIndex++) {
            int childFlatPosition = headerCount + groupIndex + 1 + childIndex;
            mListUtil.arrowScrollToSelectedPosition(childFlatPosition);
            Assert.assertEquals("Group item is not selected",
                    ExpandableListView.getPackedPositionForChild(groupIndex, childIndex),
                    mExpandableListView.getSelectedPosition());
        }
    }
}
