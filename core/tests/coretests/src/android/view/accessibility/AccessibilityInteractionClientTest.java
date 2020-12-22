/*
 * Copyright 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.MockitoAnnotations.initMocks;

import android.os.Bundle;
import android.os.RemoteException;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.util.EmptyArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for AccessibilityInteractionClient
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AccessibilityInteractionClientTest {
    private static final int MOCK_CONNECTION_ID = 0xabcd;

    private MockConnection mMockConnection = new MockConnection();
    @Mock private AccessibilityCache mMockCache;

    @Before
    public void setUp() {
        initMocks(this);
        AccessibilityInteractionClient.setCache(mMockCache);
        AccessibilityInteractionClient.addConnection(MOCK_CONNECTION_ID, mMockConnection);
    }

    /**
     * When the AccessibilityCache refreshes the nodes it contains, it gets very confused if
     * it is called to update itself during the refresh. It tries to update the node that it's
     * in the process of refreshing, which leads to AccessibilityNodeInfos in inconsistent states.
     */
    @Test
    public void findA11yNodeInfoByA11yId_whenBypassingCache_doesntTouchCache() {
        final int windowId = 0x1234;
        final long accessibilityNodeId = 0x4321L;
        AccessibilityNodeInfo nodeFromConnection = AccessibilityNodeInfo.obtain();
        nodeFromConnection.setSourceNodeId(accessibilityNodeId, windowId);
        mMockConnection.mInfosToReturn = Arrays.asList(nodeFromConnection);
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        AccessibilityNodeInfo node = client.findAccessibilityNodeInfoByAccessibilityId(
                MOCK_CONNECTION_ID, windowId, accessibilityNodeId, true, 0, null);
        assertEquals("Node got lost along the way", nodeFromConnection, node);

        verifyZeroInteractions(mMockCache);
    }

    private static class MockConnection extends AccessibilityServiceConnectionImpl {
        List<AccessibilityNodeInfo> mInfosToReturn;

        @Override
        public String[] findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
                long accessibilityNodeId, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags, long threadId,
                Bundle arguments) {
            try {
                callback.setFindAccessibilityNodeInfosResult(mInfosToReturn, interactionId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
            return EmptyArray.STRING;
        }
    }
}
