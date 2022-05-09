/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND;

import static junit.framework.TestCase.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.MockitoAnnotations.initMocks;

import android.graphics.Region;
import android.os.RemoteException;
import android.view.MagnificationSpec;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for ActionReplacingCallback
 */
public class ActionReplacingCallbackTest {
    private static final int INTERACTION_ID = 0xBEEF;
    private static final int INTERROGATING_PID = 0xFEED;
    private static final int APP_WINDOW_ID = 0xACE;
    private static final int NON_ROOT_NODE_ID = 0xAAAA5555;
    private static final long INTERROGATING_TID = 0x1234FACE;

    // We expect both the replacer actions and a11y focus actions to appear
    private static final AccessibilityAction[] REQUIRED_ACTIONS_ON_ROOT_TO_SERVICE =
            {ACTION_CLICK, ACTION_EXPAND, ACTION_ACCESSIBILITY_FOCUS,
                    ACTION_CLEAR_ACCESSIBILITY_FOCUS};

    private static final Matcher<AccessibilityNodeInfo> HAS_NO_ACTIONS =
            new BaseMatcher<AccessibilityNodeInfo>() {
        @Override
        public boolean matches(Object o) {
            AccessibilityNodeInfo node = (AccessibilityNodeInfo) o;
            if (!node.getActionList().isEmpty()) return false;
            return (!node.isScrollable() && !node.isLongClickable() && !node.isClickable()
                    && !node.isContextClickable() && !node.isDismissable() && !node.isFocusable());
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Has no actions");
        }
    };

    private static final Matcher<AccessibilityNodeInfo> HAS_EXPECTED_ACTIONS_ON_ROOT =
            new BaseMatcher<AccessibilityNodeInfo>() {
                @Override
                public boolean matches(Object o) {
                    AccessibilityNodeInfo node = (AccessibilityNodeInfo) o;
                    List<AccessibilityAction> actions = node.getActionList();
                    if ((actions.size() != 4) || !actions.contains(ACTION_CLICK)
                            || !actions.contains(ACTION_EXPAND)
                            || !actions.contains(ACTION_ACCESSIBILITY_FOCUS)) {
                        return false;
                    }
                    return (!node.isScrollable() && !node.isLongClickable()
                            && !node.isLongClickable() && node.isClickable()
                            && !node.isContextClickable() && !node.isDismissable()
                            && !node.isFocusable());
                }

                @Override
                public void describeTo(Description description) {
                    description.appendText("Has only 4 actions expected on root");
                }
            };

    @Mock IAccessibilityInteractionConnectionCallback mMockServiceCallback;
    @Mock IAccessibilityInteractionConnection mMockReplacerConnection;

    @Captor private ArgumentCaptor<Integer> mInteractionIdCaptor;
    @Captor private ArgumentCaptor<AccessibilityNodeInfo> mInfoCaptor;
    @Captor private ArgumentCaptor<List<AccessibilityNodeInfo>> mInfoListCaptor;

    private ActionReplacingCallback mActionReplacingCallback;
    private int mReplacerInteractionId;

    @Before
    public void setUp() throws RemoteException {
        initMocks(this);
        mActionReplacingCallback = new ActionReplacingCallback(
                mMockServiceCallback, mMockReplacerConnection, INTERACTION_ID, INTERROGATING_PID,
                INTERROGATING_TID);
        verify(mMockReplacerConnection).findAccessibilityNodeInfoByAccessibilityId(
                eq(AccessibilityNodeInfo.ROOT_NODE_ID), (Region) anyObject(),
                mInteractionIdCaptor.capture(), eq(mActionReplacingCallback), eq(0),
                eq(INTERROGATING_PID), eq(INTERROGATING_TID), nullable(MagnificationSpec.class),
                nullable(float[].class), eq(null));
        mReplacerInteractionId = mInteractionIdCaptor.getValue().intValue();
    }

    @Test
    public void testConstructor_registersToGetRootNodeOfActionReplacer() throws RemoteException {
        assertNotEquals(INTERACTION_ID, mReplacerInteractionId);
        verifyNoMoreInteractions(mMockServiceCallback);
    }

    @Test
    public void testCallbacks_singleRootNodeThenReplacer_returnsNodeWithReplacedActions()
            throws RemoteException {
        AccessibilityNodeInfo infoFromApp = AccessibilityNodeInfo.obtain();
        infoFromApp.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID, APP_WINDOW_ID);
        infoFromApp.addAction(ACTION_CONTEXT_CLICK);
        mActionReplacingCallback.setFindAccessibilityNodeInfoResult(infoFromApp, INTERACTION_ID);
        verifyNoMoreInteractions(mMockServiceCallback);

        mActionReplacingCallback.setFindAccessibilityNodeInfosResult(getReplacerNodes(),
                mReplacerInteractionId);

        verify(mMockServiceCallback).setFindAccessibilityNodeInfoResult(mInfoCaptor.capture(),
                eq(INTERACTION_ID));
        AccessibilityNodeInfo infoSentToService = mInfoCaptor.getValue();
        assertEquals(AccessibilityNodeInfo.ROOT_NODE_ID, infoSentToService.getSourceNodeId());
        assertThat(infoSentToService, HAS_EXPECTED_ACTIONS_ON_ROOT);
    }

    @Test
    public void testCallbacks_singleNonrootNodeThenReplacer_returnsNodeWithNoActions()
            throws RemoteException {
        AccessibilityNodeInfo infoFromApp = AccessibilityNodeInfo.obtain();
        infoFromApp.setSourceNodeId(NON_ROOT_NODE_ID, APP_WINDOW_ID);
        infoFromApp.addAction(ACTION_CONTEXT_CLICK);
        mActionReplacingCallback.setFindAccessibilityNodeInfoResult(infoFromApp, INTERACTION_ID);
        verifyNoMoreInteractions(mMockServiceCallback);

        mActionReplacingCallback.setFindAccessibilityNodeInfosResult(getReplacerNodes(),
                mReplacerInteractionId);

        verify(mMockServiceCallback).setFindAccessibilityNodeInfoResult(mInfoCaptor.capture(),
                eq(INTERACTION_ID));
        AccessibilityNodeInfo infoSentToService = mInfoCaptor.getValue();
        assertEquals(NON_ROOT_NODE_ID, infoSentToService.getSourceNodeId());
        assertThat(infoSentToService, HAS_NO_ACTIONS);
    }

    @Test
    public void testCallbacks_replacerThenSingleRootNode_returnsNodeWithReplacedActions()
            throws RemoteException {
        mActionReplacingCallback.setFindAccessibilityNodeInfosResult(getReplacerNodes(),
                mReplacerInteractionId);
        verifyNoMoreInteractions(mMockServiceCallback);

        AccessibilityNodeInfo infoFromApp = AccessibilityNodeInfo.obtain();
        infoFromApp.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID, APP_WINDOW_ID);
        infoFromApp.addAction(ACTION_CONTEXT_CLICK);
        mActionReplacingCallback.setFindAccessibilityNodeInfoResult(infoFromApp, INTERACTION_ID);

        verify(mMockServiceCallback).setFindAccessibilityNodeInfoResult(mInfoCaptor.capture(),
                eq(INTERACTION_ID));
        AccessibilityNodeInfo infoSentToService = mInfoCaptor.getValue();
        assertEquals(AccessibilityNodeInfo.ROOT_NODE_ID, infoSentToService.getSourceNodeId());
        assertThat(infoSentToService, HAS_EXPECTED_ACTIONS_ON_ROOT);
    }

    @Test
    public void testCallbacks_multipleNodesThenReplacer_clearsActionsAndAddsSomeToRoot()
            throws RemoteException {
        mActionReplacingCallback
                .setFindAccessibilityNodeInfosResult(getAppNodeList(), INTERACTION_ID);
        verifyNoMoreInteractions(mMockServiceCallback);

        mActionReplacingCallback.setFindAccessibilityNodeInfosResult(getReplacerNodes(),
                mReplacerInteractionId);

        verify(mMockServiceCallback).setFindAccessibilityNodeInfosResult(mInfoListCaptor.capture(),
                eq(INTERACTION_ID));
        assertEquals(2, mInfoListCaptor.getValue().size());
        AccessibilityNodeInfo rootInfoSentToService = getNodeWithIdFromList(
                mInfoListCaptor.getValue(), AccessibilityNodeInfo.ROOT_NODE_ID);
        AccessibilityNodeInfo otherInfoSentToService = getNodeWithIdFromList(
                mInfoListCaptor.getValue(), NON_ROOT_NODE_ID);
        assertThat(rootInfoSentToService, HAS_EXPECTED_ACTIONS_ON_ROOT);
        assertThat(otherInfoSentToService, HAS_NO_ACTIONS);
    }

    @Test
    public void testCallbacks_replacerThenMultipleNodes_clearsActionsAndAddsSomeToRoot()
            throws RemoteException {
        mActionReplacingCallback.setFindAccessibilityNodeInfosResult(getReplacerNodes(),
                mReplacerInteractionId);
        verifyNoMoreInteractions(mMockServiceCallback);

        mActionReplacingCallback
                .setFindAccessibilityNodeInfosResult(getAppNodeList(), INTERACTION_ID);

        verify(mMockServiceCallback).setFindAccessibilityNodeInfosResult(mInfoListCaptor.capture(),
                eq(INTERACTION_ID));
        assertEquals(2, mInfoListCaptor.getValue().size());
        AccessibilityNodeInfo rootInfoSentToService = getNodeWithIdFromList(
                mInfoListCaptor.getValue(), AccessibilityNodeInfo.ROOT_NODE_ID);
        AccessibilityNodeInfo otherInfoSentToService = getNodeWithIdFromList(
                mInfoListCaptor.getValue(), NON_ROOT_NODE_ID);
        assertThat(rootInfoSentToService, HAS_EXPECTED_ACTIONS_ON_ROOT);
        assertThat(otherInfoSentToService, HAS_NO_ACTIONS);
    }

    @Test
    public void testConstructor_actionReplacerThrowsException_passesDataToService()
            throws RemoteException {
        doThrow(RemoteException.class).when(mMockReplacerConnection)
                .findAccessibilityNodeInfoByAccessibilityId(eq(AccessibilityNodeInfo.ROOT_NODE_ID),
                        (Region) anyObject(), anyInt(), (ActionReplacingCallback) anyObject(),
                        eq(0),  eq(INTERROGATING_PID), eq(INTERROGATING_TID),
                        (MagnificationSpec) anyObject(), nullable(float[].class), eq(null));
        ActionReplacingCallback actionReplacingCallback = new ActionReplacingCallback(
                mMockServiceCallback, mMockReplacerConnection, INTERACTION_ID, INTERROGATING_PID,
                INTERROGATING_TID);

        verifyNoMoreInteractions(mMockServiceCallback);
        AccessibilityNodeInfo infoFromApp = AccessibilityNodeInfo.obtain();
        infoFromApp.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID, APP_WINDOW_ID);
        infoFromApp.addAction(ACTION_CONTEXT_CLICK);
        infoFromApp.setContextClickable(true);
        actionReplacingCallback.setFindAccessibilityNodeInfoResult(infoFromApp, INTERACTION_ID);

        verify(mMockServiceCallback).setFindAccessibilityNodeInfoResult(mInfoCaptor.capture(),
                eq(INTERACTION_ID));
        AccessibilityNodeInfo infoSentToService = mInfoCaptor.getValue();
        assertEquals(AccessibilityNodeInfo.ROOT_NODE_ID, infoSentToService.getSourceNodeId());
        assertThat(infoSentToService, HAS_NO_ACTIONS);
    }

    @Test
    public void testSetPerformAccessibilityActionResult_actsAsPassThrough() throws RemoteException {
        mActionReplacingCallback.setPerformAccessibilityActionResult(true, INTERACTION_ID);
        verify(mMockServiceCallback).setPerformAccessibilityActionResult(true, INTERACTION_ID);
        mActionReplacingCallback.setPerformAccessibilityActionResult(false, INTERACTION_ID);
        verify(mMockServiceCallback).setPerformAccessibilityActionResult(false, INTERACTION_ID);
    }


    private List<AccessibilityNodeInfo> getReplacerNodes() {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        root.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        root.addAction(ACTION_CLICK);
        root.addAction(ACTION_EXPAND);
        root.setClickable(true);

        // Second node should have no effect
        AccessibilityNodeInfo other = AccessibilityNodeInfo.obtain();
        other.setSourceNodeId(NON_ROOT_NODE_ID,
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        other.addAction(ACTION_COLLAPSE);

        return Arrays.asList(root, other);
    }

    private AccessibilityNodeInfo getNodeWithIdFromList(
            List<AccessibilityNodeInfo> infos, long id) {
        for (AccessibilityNodeInfo info : infos) {
            if (info.getSourceNodeId() == id) {
                return info;
            }
        }
        assertTrue("Didn't find node", false);
        return null;
    }

    private List<AccessibilityNodeInfo> getAppNodeList() {
        AccessibilityNodeInfo rootInfoFromApp = AccessibilityNodeInfo.obtain();
        rootInfoFromApp.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID, APP_WINDOW_ID);
        rootInfoFromApp.addAction(ACTION_CONTEXT_CLICK);
        AccessibilityNodeInfo otherInfoFromApp = AccessibilityNodeInfo.obtain();
        otherInfoFromApp.setSourceNodeId(NON_ROOT_NODE_ID, APP_WINDOW_ID);
        otherInfoFromApp.addAction(ACTION_CLICK);
        return Arrays.asList(rootInfoFromApp, otherInfoFromApp);
    }
}
