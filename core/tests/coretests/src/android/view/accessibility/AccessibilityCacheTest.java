/*
 * Copyright 2016 The Android Open Source Project
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

package android.view.accessibility;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.SparseArray;
import android.view.Display;
import android.view.View;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.base.Throwables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class AccessibilityCacheTest {
    private static final int WINDOW_ID_1 = 0xBEEF;
    private static final int WINDOW_ID_2 = 0xFACE;
    private static final int WINDOW_ID_3 = 0xABCD;
    private static final int WINDOW_ID_4 = 0xDCBA;
    private static final int SINGLE_VIEW_ID = 0xCAFE;
    private static final int OTHER_VIEW_ID = 0xCAB2;
    private static final int PARENT_VIEW_ID = 0xFED4;
    private static final int CHILD_VIEW_ID = 0xFEED;
    private static final int OTHER_CHILD_VIEW_ID = 0xACE2;
    private static final int MOCK_CONNECTION_ID = 1;
    private static final int SECONDARY_DISPLAY_ID = Display.DEFAULT_DISPLAY + 1;
    private static final int DEFAULT_WINDOW_LAYER = 0;
    private static final int SPECIFIC_WINDOW_LAYER = 5;

    AccessibilityCache mAccessibilityCache;
    AccessibilityCache.AccessibilityNodeRefresher mAccessibilityNodeRefresher;

    @Before
    public void setUp() {
        mAccessibilityNodeRefresher = mock(AccessibilityCache.AccessibilityNodeRefresher.class);
        when(mAccessibilityNodeRefresher.refreshNode(anyObject(), anyBoolean())).thenReturn(true);
        mAccessibilityCache = new AccessibilityCache(mAccessibilityNodeRefresher);
    }

    @After
    public void tearDown() {
        // Make sure we're recycling all of our window and node infos.
        mAccessibilityCache.clear();
        AccessibilityInteractionClient.getInstance().clearCache();
    }

    @Test
    public void testEmptyCache_returnsNull() {
        assertNull(mAccessibilityCache.getNode(0, 0));
        assertNull(mAccessibilityCache.getWindowsOnAllDisplays());
        assertNull(mAccessibilityCache.getWindow(0));
    }

    @Test
    public void testEmptyCache_clearDoesntCrash() {
        mAccessibilityCache.clear();
    }

    @Test
    public void testEmptyCache_a11yEventsHaveNoEffect() {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        int[] a11yEventTypes = {
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED};
        for (int i = 0; i < a11yEventTypes.length; i++) {
            event.setEventType(a11yEventTypes[i]);
            mAccessibilityCache.onAccessibilityEvent(event);
        }
    }

    @Test
    public void addThenGetWindow_returnsEquivalentButNotSameWindow() {
        AccessibilityWindowInfo windowInfo = null, copyOfInfo = null, windowFromCache = null;
        try {
            windowInfo = AccessibilityWindowInfo.obtain();
            windowInfo.setId(WINDOW_ID_1);
            windowInfo.setDisplayId(Display.DEFAULT_DISPLAY);
            mAccessibilityCache.addWindow(windowInfo);
            // Make a copy
            copyOfInfo = AccessibilityWindowInfo.obtain(windowInfo);
            windowInfo.setId(WINDOW_ID_2); // Simulate recycling and reusing the original info.
            windowFromCache = mAccessibilityCache.getWindow(WINDOW_ID_1);
            assertEquals(copyOfInfo, windowFromCache);
        } finally {
            windowFromCache.recycle();
            windowInfo.recycle();
            copyOfInfo.recycle();
        }
    }

    @Test
    public void addWindowThenClear_noLongerInCache() {
        putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_1, Display.DEFAULT_DISPLAY,
                DEFAULT_WINDOW_LAYER);
        mAccessibilityCache.clear();
        assertNull(mAccessibilityCache.getWindow(WINDOW_ID_1));
    }

    @Test
    public void addWindowGetOtherId_returnsNull() {
        putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_1, Display.DEFAULT_DISPLAY,
                DEFAULT_WINDOW_LAYER);
        assertNull(mAccessibilityCache.getWindow(WINDOW_ID_1 + 1));
    }

    @Test
    public void addWindowThenGetWindows_returnsNull() {
        putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_1, Display.DEFAULT_DISPLAY,
                DEFAULT_WINDOW_LAYER);
        assertNull(mAccessibilityCache.getWindowsOnAllDisplays());
    }

    @Test
    public void setWindowsThenGetWindows_returnsInDecreasingLayerOrder() {
        AccessibilityWindowInfo windowInfo1 = null;
        AccessibilityWindowInfo windowInfo2 = null;
        AccessibilityWindowInfo window1Out = null;
        AccessibilityWindowInfo window2Out = null;
        List<AccessibilityWindowInfo> windowsOut = null;
        try {
            windowInfo1 = obtainAccessibilityWindowInfo(WINDOW_ID_1, SPECIFIC_WINDOW_LAYER);
            windowInfo2 = obtainAccessibilityWindowInfo(WINDOW_ID_2, windowInfo1.getLayer() + 1);
            List<AccessibilityWindowInfo> windowsIn = Arrays.asList(windowInfo1, windowInfo2);
            setWindowsByDisplay(Display.DEFAULT_DISPLAY, windowsIn);

            windowsOut = getWindowsByDisplay(Display.DEFAULT_DISPLAY);
            window1Out = mAccessibilityCache.getWindow(WINDOW_ID_1);
            window2Out = mAccessibilityCache.getWindow(WINDOW_ID_2);

            assertEquals(2, windowsOut.size());
            assertEquals(windowInfo2, windowsOut.get(0));
            assertEquals(windowInfo1, windowsOut.get(1));
            assertEquals(windowInfo1, window1Out);
            assertEquals(windowInfo2, window2Out);
        } finally {
            window1Out.recycle();
            window2Out.recycle();
            windowInfo1.recycle();
            windowInfo2.recycle();
            for (AccessibilityWindowInfo windowInfo : windowsOut) {
                windowInfo.recycle();
            }
        }
    }

    @Test
    public void setWindowsAndAddWindow_thenGetWindows_returnsInDecreasingLayerOrder() {
        AccessibilityWindowInfo windowInfo1 = null;
        AccessibilityWindowInfo windowInfo2 = null;
        AccessibilityWindowInfo window1Out = null;
        AccessibilityWindowInfo window2Out = null;
        AccessibilityWindowInfo window3Out = null;
        List<AccessibilityWindowInfo> windowsOut = null;
        try {
            windowInfo1 = obtainAccessibilityWindowInfo(WINDOW_ID_1, SPECIFIC_WINDOW_LAYER);
            windowInfo2 = obtainAccessibilityWindowInfo(WINDOW_ID_2, windowInfo1.getLayer() + 2);
            List<AccessibilityWindowInfo> windowsIn = Arrays.asList(windowInfo1, windowInfo2);
            setWindowsByDisplay(Display.DEFAULT_DISPLAY, windowsIn);

            putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_3, Display.DEFAULT_DISPLAY,
                    windowInfo1.getLayer() + 1);

            windowsOut = getWindowsByDisplay(Display.DEFAULT_DISPLAY);
            window1Out = mAccessibilityCache.getWindow(WINDOW_ID_1);
            window2Out = mAccessibilityCache.getWindow(WINDOW_ID_2);
            window3Out = mAccessibilityCache.getWindow(WINDOW_ID_3);

            assertEquals(3, windowsOut.size());
            assertEquals(windowInfo2, windowsOut.get(0));
            assertEquals(windowInfo1, windowsOut.get(2));
            assertEquals(windowInfo1, window1Out);
            assertEquals(windowInfo2, window2Out);
            assertEquals(window3Out, windowsOut.get(1));
        } finally {
            window1Out.recycle();
            window2Out.recycle();
            window3Out.recycle();
            windowInfo1.recycle();
            windowInfo2.recycle();
            for (AccessibilityWindowInfo windowInfo : windowsOut) {
                windowInfo.recycle();
            }
        }
    }

    @Test
    public void
            setWindowsAtFirstDisplay_thenAddWindowAtSecondDisplay_returnWindowLayerOrderUnchange() {
        AccessibilityWindowInfo windowInfo1 = null;
        AccessibilityWindowInfo windowInfo2 = null;
        AccessibilityWindowInfo window1Out = null;
        AccessibilityWindowInfo window2Out = null;
        List<AccessibilityWindowInfo> windowsOut = null;
        try {
            // Sets windows to default display.
            windowInfo1 = obtainAccessibilityWindowInfo(WINDOW_ID_1, SPECIFIC_WINDOW_LAYER);
            windowInfo2 = obtainAccessibilityWindowInfo(WINDOW_ID_2, windowInfo1.getLayer() + 2);
            List<AccessibilityWindowInfo> windowsIn = Arrays.asList(windowInfo1, windowInfo2);
            setWindowsByDisplay(Display.DEFAULT_DISPLAY, windowsIn);
            // Adds one window to second display.
            putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_3, SECONDARY_DISPLAY_ID,
                    windowInfo1.getLayer() + 1);

            windowsOut = getWindowsByDisplay(Display.DEFAULT_DISPLAY);
            window1Out = mAccessibilityCache.getWindow(WINDOW_ID_1);
            window2Out = mAccessibilityCache.getWindow(WINDOW_ID_2);

            assertEquals(2, windowsOut.size());
            assertEquals(windowInfo2, windowsOut.get(0));
            assertEquals(windowInfo1, windowsOut.get(1));
            assertEquals(windowInfo1, window1Out);
            assertEquals(windowInfo2, window2Out);
        } finally {
            window1Out.recycle();
            window2Out.recycle();
            windowInfo1.recycle();
            windowInfo2.recycle();
            for (AccessibilityWindowInfo windowInfo : windowsOut) {
                windowInfo.recycle();
            }
        }
    }

    @Test
    public void setWindowsAtTwoDisplays_thenGetWindows_returnsInDecreasingLayerOrder() {
        AccessibilityWindowInfo windowInfo1 = null;
        AccessibilityWindowInfo windowInfo2 = null;
        AccessibilityWindowInfo window1Out = null;
        AccessibilityWindowInfo window2Out = null;
        AccessibilityWindowInfo windowInfo3 = null;
        AccessibilityWindowInfo windowInfo4 = null;
        AccessibilityWindowInfo window3Out = null;
        AccessibilityWindowInfo window4Out = null;
        List<AccessibilityWindowInfo> windowsOut1 = null;
        List<AccessibilityWindowInfo> windowsOut2 = null;
        try {
            // Prepares all windows for default display.
            windowInfo1 = obtainAccessibilityWindowInfo(WINDOW_ID_1, SPECIFIC_WINDOW_LAYER);
            windowInfo2 = obtainAccessibilityWindowInfo(WINDOW_ID_2, windowInfo1.getLayer() + 1);
            List<AccessibilityWindowInfo> windowsIn1 = Arrays.asList(windowInfo1, windowInfo2);
            // Prepares all windows for second display.
            windowInfo3 = obtainAccessibilityWindowInfo(WINDOW_ID_3, windowInfo1.getLayer() + 2);
            windowInfo4 = obtainAccessibilityWindowInfo(WINDOW_ID_4, windowInfo1.getLayer() + 3);
            List<AccessibilityWindowInfo> windowsIn2 = Arrays.asList(windowInfo3, windowInfo4);
            // Sets all windows of all displays into A11y cache.
            SparseArray<List<AccessibilityWindowInfo>> allWindows = new SparseArray<>();
            allWindows.put(Display.DEFAULT_DISPLAY, windowsIn1);
            allWindows.put(SECONDARY_DISPLAY_ID, windowsIn2);
            mAccessibilityCache.setWindowsOnAllDisplays(allWindows);
            // Gets windows at default display.
            windowsOut1 = getWindowsByDisplay(Display.DEFAULT_DISPLAY);
            window1Out = mAccessibilityCache.getWindow(WINDOW_ID_1);
            window2Out = mAccessibilityCache.getWindow(WINDOW_ID_2);

            assertEquals(2, windowsOut1.size());
            assertEquals(windowInfo2, windowsOut1.get(0));
            assertEquals(windowInfo1, windowsOut1.get(1));
            assertEquals(windowInfo1, window1Out);
            assertEquals(windowInfo2, window2Out);
            // Gets windows at seocnd display.
            windowsOut2 = getWindowsByDisplay(SECONDARY_DISPLAY_ID);
            window3Out = mAccessibilityCache.getWindow(WINDOW_ID_3);
            window4Out = mAccessibilityCache.getWindow(WINDOW_ID_4);

            assertEquals(2, windowsOut2.size());
            assertEquals(windowInfo4, windowsOut2.get(0));
            assertEquals(windowInfo3, windowsOut2.get(1));
            assertEquals(windowInfo3, window3Out);
            assertEquals(windowInfo4, window4Out);
        } finally {
            window1Out.recycle();
            window2Out.recycle();
            windowInfo1.recycle();
            windowInfo2.recycle();
            window3Out.recycle();
            window4Out.recycle();
            windowInfo3.recycle();
            windowInfo4.recycle();
            for (AccessibilityWindowInfo windowInfo : windowsOut1) {
                windowInfo.recycle();
            }
            for (AccessibilityWindowInfo windowInfo : windowsOut2) {
                windowInfo.recycle();
            }
        }
    }

    @Test
    public void addWindowThenStateChangedEvent_noLongerInCache() {
        putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_1, Display.DEFAULT_DISPLAY,
                DEFAULT_WINDOW_LAYER);
        mAccessibilityCache.onAccessibilityEvent(
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertNull(mAccessibilityCache.getWindow(WINDOW_ID_1));
    }

    @Test
    public void addWindowThenWindowsChangedEvent_noLongerInCache() {
        putWindowWithWindowIdAndDisplayIdInCache(WINDOW_ID_1, Display.DEFAULT_DISPLAY,
                DEFAULT_WINDOW_LAYER);
        mAccessibilityCache.onAccessibilityEvent(
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertNull(mAccessibilityCache.getWindow(WINDOW_ID_1));
    }

    @Test
    public void addThenGetNode_returnsEquivalentNode() {
        AccessibilityNodeInfo nodeInfo, nodeCopy = null, nodeFromCache = null;
        try {
            nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
            long id = nodeInfo.getSourceNodeId();
            nodeCopy = AccessibilityNodeInfo.obtain(nodeInfo);
            mAccessibilityCache.add(nodeInfo);
            nodeInfo.recycle();
            nodeFromCache = mAccessibilityCache.getNode(WINDOW_ID_1, id);
            assertEquals(nodeCopy, nodeFromCache);
        } finally {
            nodeFromCache.recycle();
            nodeCopy.recycle();
        }
    }

    @Test
    public void overwriteThenGetNode_returnsNewNode() {
        final CharSequence contentDescription1 = "foo";
        final CharSequence contentDescription2 = "bar";
        AccessibilityNodeInfo nodeInfo1 = null, nodeInfo2 = null, nodeFromCache = null;
        try {
            nodeInfo1 = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
            nodeInfo1.setContentDescription(contentDescription1);
            long id = nodeInfo1.getSourceNodeId();
            nodeInfo2 = AccessibilityNodeInfo.obtain(nodeInfo1);
            nodeInfo2.setContentDescription(contentDescription2);
            mAccessibilityCache.add(nodeInfo1);
            mAccessibilityCache.add(nodeInfo2);
            nodeFromCache = mAccessibilityCache.getNode(WINDOW_ID_1, id);
            assertEquals(nodeInfo2, nodeFromCache);
            assertEquals(contentDescription2, nodeFromCache.getContentDescription());
        } finally {
            nodeFromCache.recycle();
            nodeInfo2.recycle();
            nodeInfo1.recycle();
        }
    }

    @Test
    public void nodesInDifferentWindowWithSameId_areKeptSeparate() {
        final CharSequence contentDescription1 = "foo";
        final CharSequence contentDescription2 = "bar";
        AccessibilityNodeInfo nodeInfo1 = null, nodeInfo2 = null,
                node1FromCache = null, node2FromCache = null;
        try {
            nodeInfo1 = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
            nodeInfo1.setContentDescription(contentDescription1);
            long id = nodeInfo1.getSourceNodeId();
            nodeInfo2 = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_2);
            nodeInfo2.setContentDescription(contentDescription2);
            assertEquals(id, nodeInfo2.getSourceNodeId());
            mAccessibilityCache.add(nodeInfo1);
            mAccessibilityCache.add(nodeInfo2);
            node1FromCache = mAccessibilityCache.getNode(WINDOW_ID_1, id);
            node2FromCache = mAccessibilityCache.getNode(WINDOW_ID_2, id);
            assertEquals(nodeInfo1, node1FromCache);
            assertEquals(nodeInfo2, node2FromCache);
            assertEquals(nodeInfo1.getContentDescription(), node1FromCache.getContentDescription());
            assertEquals(nodeInfo2.getContentDescription(), node2FromCache.getContentDescription());
        } finally {
            node1FromCache.recycle();
            node2FromCache.recycle();
            nodeInfo1.recycle();
            nodeInfo2.recycle();
        }
    }

    @Test
    public void addNodeThenClear_nodeIsRemoved() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        long id = nodeInfo.getSourceNodeId();
        mAccessibilityCache.add(nodeInfo);
        nodeInfo.recycle();
        mAccessibilityCache.clear();
        assertNull(mAccessibilityCache.getNode(WINDOW_ID_1, id));
    }

    @Test
    public void windowStateChangeAndWindowsChangedEvents_clearsNode() {
        assertEventTypeClearsNode(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        assertEventTypeClearsNode(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
    }

    @Test
    public void windowsChangedWithWindowsChangeA11yFocusedEvent_dontClearCache() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = new AccessibilityEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        event.setWindowChanges(AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED);
        mAccessibilityCache.onAccessibilityEvent(event);
        AccessibilityNodeInfo cachedNode = mAccessibilityCache.getNode(WINDOW_ID_1,
                nodeInfo.getSourceNodeId());
        try {
            assertNotNull(cachedNode);
        } finally {
            nodeInfo.recycle();
        }
    }

    @Test
    public void subTreeChangeEvent_clearsNodeAndChild() {
        AccessibilityEvent event = AccessibilityEvent
                .obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        event.setSource(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));

        try {
            assertEventClearsParentAndChild(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void subTreeChangeEventFromUncachedNode_clearsNodeInCache() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(CHILD_VIEW_ID, WINDOW_ID_1);
        long id = nodeInfo.getSourceNodeId();
        mAccessibilityCache.add(nodeInfo);
        nodeInfo.recycle();

        AccessibilityEvent event = AccessibilityEvent
                .obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        event.setSource(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));

        mAccessibilityCache.onAccessibilityEvent(event);
        AccessibilityNodeInfo shouldBeNull = mAccessibilityCache.getNode(WINDOW_ID_1, id);
        if (shouldBeNull != null) {
            shouldBeNull.recycle();
        }
        assertNull(shouldBeNull);
    }

    @Test
    public void scrollEvent_clearsNodeAndChild() {
        AccessibilityEvent event = AccessibilityEvent
                .obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        event.setSource(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));
        try {
            assertEventClearsParentAndChild(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void reparentNode_clearsOldParent() {
        AccessibilityNodeInfo parentNodeInfo = getParentNode();
        AccessibilityNodeInfo childNodeInfo = getChildNode();
        long parentId = parentNodeInfo.getSourceNodeId();
        mAccessibilityCache.add(parentNodeInfo);
        mAccessibilityCache.add(childNodeInfo);

        childNodeInfo.setParent(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID + 1, WINDOW_ID_1));
        mAccessibilityCache.add(childNodeInfo);

        AccessibilityNodeInfo parentFromCache = mAccessibilityCache.getNode(WINDOW_ID_1, parentId);
        try {
            assertNull(parentFromCache);
        } finally {
            parentNodeInfo.recycle();
            childNodeInfo.recycle();
            if (parentFromCache != null) {
                parentFromCache.recycle();
            }
        }
    }

    @Test
    public void removeChildFromParent_clearsChild() {
        AccessibilityNodeInfo parentNodeInfo = getParentNode();
        AccessibilityNodeInfo childNodeInfo = getChildNode();
        long childId = childNodeInfo.getSourceNodeId();
        mAccessibilityCache.add(parentNodeInfo);
        mAccessibilityCache.add(childNodeInfo);

        AccessibilityNodeInfo parentNodeInfoWithNoChildren =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        mAccessibilityCache.add(parentNodeInfoWithNoChildren);

        AccessibilityNodeInfo childFromCache = mAccessibilityCache.getNode(WINDOW_ID_1, childId);
        try {
            assertNull(childFromCache);
        } finally {
            parentNodeInfoWithNoChildren.recycle();
            parentNodeInfo.recycle();
            childNodeInfo.recycle();
            if (childFromCache != null) {
                childFromCache.recycle();
            }
        }
    }

    @Test
    public void nodeSourceOfA11yFocusEvent_getsRefreshed() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        nodeInfo.setAccessibilityFocused(false);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        event.setSource(getMockViewWithA11yAndWindowIds(SINGLE_VIEW_ID, WINDOW_ID_1));
        mAccessibilityCache.onAccessibilityEvent(event);
        event.recycle();
        try {
            verify(mAccessibilityNodeRefresher).refreshNode(nodeInfo, true);
        } finally {
            nodeInfo.recycle();
        }
    }

    @Test
    public void nodeWithA11yFocusWhenAnotherNodeGetsFocus_getsRemoved() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        nodeInfo.setAccessibilityFocused(true);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        event.setSource(getMockViewWithA11yAndWindowIds(OTHER_VIEW_ID, WINDOW_ID_1));
        mAccessibilityCache.onAccessibilityEvent(event);
        event.recycle();
        try {
            assertNull(mAccessibilityCache.getNode(WINDOW_ID_1, SINGLE_VIEW_ID));
        } finally {
            nodeInfo.recycle();
        }
    }

    @Test
    public void nodeWithA11yFocusClearsIt_refreshes() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        nodeInfo.setAccessibilityFocused(true);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        event.setSource(getMockViewWithA11yAndWindowIds(SINGLE_VIEW_ID, WINDOW_ID_1));
        mAccessibilityCache.onAccessibilityEvent(event);
        event.recycle();
        try {
            verify(mAccessibilityNodeRefresher).refreshNode(nodeInfo, true);
        } finally {
            nodeInfo.recycle();
        }
    }

    @Test
    public void nodeSourceOfInputFocusEvent_getsRefreshed() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        nodeInfo.setFocused(false);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_FOCUSED);
        event.setSource(getMockViewWithA11yAndWindowIds(SINGLE_VIEW_ID, WINDOW_ID_1));
        mAccessibilityCache.onAccessibilityEvent(event);
        event.recycle();
        try {
            verify(mAccessibilityNodeRefresher).refreshNode(nodeInfo, true);
        } finally {
            nodeInfo.recycle();
        }
    }

    @Test
    public void nodeWithInputFocusWhenAnotherNodeGetsFocus_getsRemoved() {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        nodeInfo.setFocused(true);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_FOCUSED);
        event.setSource(getMockViewWithA11yAndWindowIds(OTHER_VIEW_ID, WINDOW_ID_1));
        mAccessibilityCache.onAccessibilityEvent(event);
        event.recycle();
        try {
            assertNull(mAccessibilityCache.getNode(WINDOW_ID_1, SINGLE_VIEW_ID));
        } finally {
            nodeInfo.recycle();
        }
    }

    @Test
    public void nodeEventSaysWasSelected_getsRefreshed() {
        assertNodeIsRefreshedWithEventType(AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
    }

    @Test
    public void nodeEventSaysHadTextChanged_getsRefreshed() {
        assertNodeIsRefreshedWithEventType(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
    }

    @Test
    public void nodeEventSaysWasClicked_getsRefreshed() {
        assertNodeIsRefreshedWithEventType(AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
    }

    @Test
    public void nodeEventSaysHadSelectionChange_getsRefreshed() {
        assertNodeIsRefreshedWithEventType(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
    }

    @Test
    public void nodeEventSaysHadTextContentChange_getsRefreshed() {
        assertNodeIsRefreshedWithEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT);
    }

    @Test
    public void nodeEventSaysHadContentDescriptionChange_getsRefreshed() {
        assertNodeIsRefreshedWithEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION);
    }

    @Test
    public void addNode_whenNodeBeingReplacedIsOwnGrandparentWithTwoChildren_doesntCrash() {
        AccessibilityNodeInfo parentNodeInfo =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        parentNodeInfo.addChild(getMockViewWithA11yAndWindowIds(CHILD_VIEW_ID, WINDOW_ID_1));
        parentNodeInfo.addChild(getMockViewWithA11yAndWindowIds(OTHER_CHILD_VIEW_ID, WINDOW_ID_1));
        AccessibilityNodeInfo childNodeInfo =
                getNodeWithA11yAndWindowId(CHILD_VIEW_ID, WINDOW_ID_1);
        childNodeInfo.setParent(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));
        childNodeInfo.addChild(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));

        AccessibilityNodeInfo replacementParentNodeInfo =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        try {
            mAccessibilityCache.add(parentNodeInfo);
            mAccessibilityCache.add(childNodeInfo);
            mAccessibilityCache.add(replacementParentNodeInfo);
        } finally {
            parentNodeInfo.recycle();
            childNodeInfo.recycle();
            replacementParentNodeInfo.recycle();
        }
    }

    @Test
    public void addNode_whenNodeBeingReplacedIsOwnGrandparentWithOneChild_doesntCrash() {
        AccessibilityNodeInfo parentNodeInfo =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        parentNodeInfo.addChild(getMockViewWithA11yAndWindowIds(CHILD_VIEW_ID, WINDOW_ID_1));
        AccessibilityNodeInfo childNodeInfo =
                getNodeWithA11yAndWindowId(CHILD_VIEW_ID, WINDOW_ID_1);
        childNodeInfo.setParent(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));
        childNodeInfo.addChild(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));

        AccessibilityNodeInfo replacementParentNodeInfo =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        try {
            mAccessibilityCache.add(parentNodeInfo);
            mAccessibilityCache.add(childNodeInfo);
            mAccessibilityCache.add(replacementParentNodeInfo);
        } finally {
            parentNodeInfo.recycle();
            childNodeInfo.recycle();
            replacementParentNodeInfo.recycle();
        }
    }

    @Test
    public void testCacheCriticalEventList_doesntLackEvents() {
        for (int i = 0; i < 32; i++) {
            int eventType = 1 << i;
            if ((eventType & AccessibilityCache.CACHE_CRITICAL_EVENTS_MASK) == 0) {
                try {
                    assertEventTypeClearsNode(eventType, false);
                    verify(mAccessibilityNodeRefresher, never())
                            .refreshNode(anyObject(), anyBoolean());
                } catch (Throwable e) {
                    throw new AssertionError(
                            "Failed for eventType: " + AccessibilityEvent.eventTypeToString(
                                    eventType),
                            e);
                }
            }
        }
    }

    @Test
    public void addA11yFocusNodeBeforeFocusClearedEvent_previousA11yFocusNodeGetsRemoved() {
        AccessibilityNodeInfo nodeInfo1 = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        nodeInfo1.setAccessibilityFocused(true);
        mAccessibilityCache.add(nodeInfo1);
        AccessibilityNodeInfo nodeInfo2 = getNodeWithA11yAndWindowId(OTHER_VIEW_ID, WINDOW_ID_1);
        nodeInfo2.setAccessibilityFocused(true);
        mAccessibilityCache.add(nodeInfo2);
        try {
            assertNull(mAccessibilityCache.getNode(WINDOW_ID_1, SINGLE_VIEW_ID));
        } finally {
            nodeInfo1.recycle();
            nodeInfo2.recycle();
        }
    }

    @Test
    public void addSameParentNodeWithDifferentChildNode_whenOriginalChildHasChild_doesntCrash() {
        AccessibilityNodeInfo parentNodeInfo = getParentNode();
        AccessibilityNodeInfo childNodeInfo = getChildNode();
        childNodeInfo.addChild(getMockViewWithA11yAndWindowIds(CHILD_VIEW_ID + 1, WINDOW_ID_1));

        AccessibilityNodeInfo replacementParentNodeInfo =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        replacementParentNodeInfo.addChild(
                getMockViewWithA11yAndWindowIds(OTHER_CHILD_VIEW_ID, WINDOW_ID_1));
        try {
            mAccessibilityCache.add(parentNodeInfo);
            mAccessibilityCache.add(childNodeInfo);
            mAccessibilityCache.add(replacementParentNodeInfo);
        } catch (IllegalStateException e) {
            fail("recycle A11yNodeInfo twice" + Throwables.getStackTraceAsString(e));
        } finally {
            parentNodeInfo.recycle();
            childNodeInfo.recycle();
            replacementParentNodeInfo.recycle();
        }
    }

    private void assertNodeIsRefreshedWithEventType(int eventType, int contentChangeTypes) {
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(SINGLE_VIEW_ID, WINDOW_ID_1);
        mAccessibilityCache.add(nodeInfo);
        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        event.setSource(getMockViewWithA11yAndWindowIds(SINGLE_VIEW_ID, WINDOW_ID_1));
        event.setContentChangeTypes(contentChangeTypes);
        mAccessibilityCache.onAccessibilityEvent(event);
        event.recycle();
        try {
            verify(mAccessibilityNodeRefresher).refreshNode(nodeInfo, true);
        } finally {
            nodeInfo.recycle();
        }
    }

    private AccessibilityWindowInfo obtainAccessibilityWindowInfo(int windowId, int layer) {
        AccessibilityWindowInfo windowInfo = AccessibilityWindowInfo.obtain();
        windowInfo.setId(windowId);
        windowInfo.setLayer(layer);
        return windowInfo;
    }

    private void putWindowWithWindowIdAndDisplayIdInCache(int windowId, int displayId, int layer) {
        AccessibilityWindowInfo windowInfo = obtainAccessibilityWindowInfo(windowId, layer);
        windowInfo.setDisplayId(displayId);
        mAccessibilityCache.addWindow(windowInfo);
        windowInfo.recycle();
    }

    private AccessibilityNodeInfo getNodeWithA11yAndWindowId(int a11yId, int windowId) {
        AccessibilityNodeInfo node =
                AccessibilityNodeInfo.obtain(getMockViewWithA11yAndWindowIds(a11yId, windowId));
        node.setConnectionId(MOCK_CONNECTION_ID);
        return node;
    }

    private View getMockViewWithA11yAndWindowIds(int a11yId, int windowId) {
        View mockView = mock(View.class);
        when(mockView.getAccessibilityViewId()).thenReturn(a11yId);
        when(mockView.getAccessibilityWindowId()).thenReturn(windowId);
        doAnswer(new Answer<AccessibilityNodeInfo>() {
            public AccessibilityNodeInfo answer(InvocationOnMock invocation) {
                return AccessibilityNodeInfo.obtain((View) invocation.getMock());
            }
        }).when(mockView).createAccessibilityNodeInfo();
        return mockView;
    }

    private void assertEventTypeClearsNode(int eventType) {
        assertEventTypeClearsNode(eventType, true);
    }

    private void assertEventTypeClearsNode(int eventType, boolean clears) {
        final int nodeId = 0xBEEF;
        AccessibilityNodeInfo nodeInfo = getNodeWithA11yAndWindowId(nodeId, WINDOW_ID_1);
        long id = nodeInfo.getSourceNodeId();
        mAccessibilityCache.add(nodeInfo);
        nodeInfo.recycle();
        mAccessibilityCache.onAccessibilityEvent(AccessibilityEvent.obtain(eventType));
        AccessibilityNodeInfo cachedNode = mAccessibilityCache.getNode(WINDOW_ID_1, id);
        try {
            if (clears) {
                assertNull(cachedNode);
            } else {
                assertNotNull(cachedNode);
            }
        } finally {
            if (cachedNode != null) {
                cachedNode.recycle();
            }
        }
    }

    private AccessibilityNodeInfo getParentNode() {
        AccessibilityNodeInfo parentNodeInfo =
                getNodeWithA11yAndWindowId(PARENT_VIEW_ID, WINDOW_ID_1);
        parentNodeInfo.addChild(getMockViewWithA11yAndWindowIds(CHILD_VIEW_ID, WINDOW_ID_1));
        return parentNodeInfo;
    }

    private AccessibilityNodeInfo getChildNode() {
        AccessibilityNodeInfo childNodeInfo =
                getNodeWithA11yAndWindowId(CHILD_VIEW_ID, WINDOW_ID_1);
        childNodeInfo.setParent(getMockViewWithA11yAndWindowIds(PARENT_VIEW_ID, WINDOW_ID_1));
        return childNodeInfo;
    }

    private void assertEventClearsParentAndChild(AccessibilityEvent event) {
        AccessibilityNodeInfo parentNodeInfo = getParentNode();
        AccessibilityNodeInfo childNodeInfo = getChildNode();
        long parentId = parentNodeInfo.getSourceNodeId();
        long childId = childNodeInfo.getSourceNodeId();
        mAccessibilityCache.add(parentNodeInfo);
        mAccessibilityCache.add(childNodeInfo);

        mAccessibilityCache.onAccessibilityEvent(event);
        parentNodeInfo.recycle();
        childNodeInfo.recycle();

        AccessibilityNodeInfo parentFromCache = mAccessibilityCache.getNode(WINDOW_ID_1, parentId);
        AccessibilityNodeInfo childFromCache = mAccessibilityCache.getNode(WINDOW_ID_1, childId);
        try {
            assertNull(parentFromCache);
            assertNull(childFromCache);
        } finally {
            if (parentFromCache != null) {
                parentFromCache.recycle();
            }
            if (childFromCache != null) {
                childFromCache.recycle();
            }
        }
    }

    private void setWindowsByDisplay(int displayId, List<AccessibilityWindowInfo> windows) {
        SparseArray<List<AccessibilityWindowInfo>> allWindows = new SparseArray<>();
        allWindows.put(displayId, windows);
        mAccessibilityCache.setWindowsOnAllDisplays(allWindows);
    }

    private List<AccessibilityWindowInfo> getWindowsByDisplay(int displayId) {
        final SparseArray<List<AccessibilityWindowInfo>> allWindows =
                mAccessibilityCache.getWindowsOnAllDisplays();

        if (allWindows != null && allWindows.size() > 0) {
            return allWindows.get(displayId);
        }
        return null;
    }
}
