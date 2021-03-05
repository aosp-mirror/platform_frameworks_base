/*
 * Copyright (C) 2021 The Android Open Source Project
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


import static android.view.accessibility.AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS;
import static android.view.accessibility.AccessibilityNodeInfo.ROOT_NODE_ID;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.os.RemoteException;
import android.view.AccessibilityInteractionController;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeIdManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests that verify expected node and prefetched node results when finding a view by node id. We
 * send some requests to the controller via View methods to control message timing.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityInteractionControllerNodeRequestsTest {
    private AccessibilityInteractionController mAccessibilityInteractionController;
    @Mock
    private IAccessibilityInteractionConnectionCallback mMockClientCallback1;
    @Mock
    private IAccessibilityInteractionConnectionCallback mMockClientCallback2;

    @Captor
    private ArgumentCaptor<AccessibilityNodeInfo> mFindInfoCaptor;
    @Captor private ArgumentCaptor<List<AccessibilityNodeInfo>> mPrefetchInfoListCaptor;

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private static final int MOCK_CLIENT_1_THREAD_AND_PROCESS_ID = 1;
    private static final int MOCK_CLIENT_2_THREAD_AND_PROCESS_ID = 2;

    private static final String FRAME_LAYOUT_DESCRIPTION = "frameLayout";
    private static final String TEXT_VIEW_1_DESCRIPTION = "textView1";
    private static final String TEXT_VIEW_2_DESCRIPTION = "textView2";

    private TestFrameLayout mFrameLayout;
    private TestTextView mTextView1;
    private TestTextView2 mTextView2;

    private boolean mSendClient1RequestForTextAfterTextPrefetched;
    private boolean mSendClient2RequestForTextAfterTextPrefetched;
    private boolean mSendRequestForTextAndIncludeUnImportantViews;
    private int mMockClient1InteractionId;
    private int mMockClient2InteractionId;

    @Before
    public void setUp() throws Throwable {
        MockitoAnnotations.initMocks(this);

        mInstrumentation.runOnMainSync(() -> {
            final Context context = mInstrumentation.getTargetContext();
            final ViewRootImpl viewRootImpl = new ViewRootImpl(context, context.getDisplay());

            mFrameLayout = new TestFrameLayout(context);
            mTextView1 = new TestTextView(context);
            mTextView2 = new TestTextView2(context);

            mFrameLayout.addView(mTextView1);
            mFrameLayout.addView(mTextView2);

            // The controller retrieves views through this manager, and registration happens on
            // when attached to a window, which we don't have. We can simply reference FrameLayout
            // with ROOT_NODE_ID
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mTextView1, mTextView1.getAccessibilityViewId());
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mTextView2, mTextView2.getAccessibilityViewId());

            try {
                viewRootImpl.setView(mFrameLayout, new WindowManager.LayoutParams(), null);

            } catch (WindowManager.BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }

            mAccessibilityInteractionController =
                    new AccessibilityInteractionController(viewRootImpl);
        });

    }

    @After
    public void tearDown() throws Throwable {
        AccessibilityNodeIdManager.getInstance().unregisterViewWithId(
                mTextView1.getAccessibilityViewId());
        AccessibilityNodeIdManager.getInstance().unregisterViewWithId(
                mTextView2.getAccessibilityViewId());
    }

    /**
     * Tests a basic request for the root node with prefetch flag
     * {@link AccessibilityNodeInfo#FLAG_PREFETCH_DESCENDANTS}
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRootView_withOneClient_shouldReturnRootNodeAndPrefetchDescendants()
            throws RemoteException {
        // Request for our FrameLayout
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS);
        mInstrumentation.waitForIdleSync();

        // Verify we get FrameLayout
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));
        // The descendants are our two TextViews
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(2, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());

    }

    /**
     * Tests a basic request for TestTextView1's node with prefetch flag
     * {@link AccessibilityNodeInfo#FLAG_PREFETCH_SIBLINGS}
     *
     * @throws RemoteException
     */
    @Test
    public void testFindTextView_withOneClient_shouldReturnNodeAndPrefetchedSiblings()
            throws RemoteException {
        // Request for TextView1
        sendNodeRequestToController(AccessibilityNodeInfo.makeNodeId(
                mTextView1.getAccessibilityViewId(), AccessibilityNodeProvider.HOST_VIEW_ID),
                mMockClientCallback1, mMockClient1InteractionId, FLAG_PREFETCH_SIBLINGS);
        mInstrumentation.waitForIdleSync();

        // Verify we get TextView1
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(TEXT_VIEW_1_DESCRIPTION, infoSentToService.getContentDescription());

        // Verify the prefetched sibling of TextView1 is TextView2
        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));
        // TextView2 is the prefetched sibling
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(1, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
    }

    /**
     * Tests a series of controller requests to prevent prefetching.
     *     Request 1: Client 1 requests the root node
     *     Request 2: When the root node is initialized in
     *     {@link TestFrameLayout#onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo)},
     *     Client 2 requests TestTextView1's node
     *
     * Request 2 on the queue prevents prefetching for Request 1.
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRootAndTextNodes_withTwoClients_shouldPreventClient1Prefetch()
            throws RemoteException {
        mFrameLayout.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                    // Enqueue a request when this node is found from a different service for
                    // TextView1
                    sendNodeRequestToController(nodeId, mMockClientCallback2,
                            mMockClient2InteractionId, FLAG_PREFETCH_SIBLINGS);
            }
        });
        // Client 1 request for FrameLayout
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS);

        mInstrumentation.waitForIdleSync();

        // Verify client 1 gets FrameLayout
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        // The second request is put in the queue in the FrameLayout's onInitializeA11yNodeInfo,
        // meaning prefetching is interrupted and does not even begin for the first request
        verify(mMockClientCallback1, never())
                .setPrefetchAccessibilityNodeInfoResult(anyList(), anyInt());

        // Verify client 2 gets TextView1
        verify(mMockClientCallback2).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient2InteractionId));
        infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(TEXT_VIEW_1_DESCRIPTION, infoSentToService.getContentDescription());

        // Verify the prefetched sibling of TextView1 is TextView2 (FLAG_PREFETCH_SIBLINGS)
        verify(mMockClientCallback2).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient2InteractionId));
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(1, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
    }

    /**
     * Tests a series of controller same-service requests to interrupt prefetching and satisfy a
     * pending node request.
     *     Request 1: Request the root node
     *     Request 2: When TextTextView1's node is initialized as part of Request 1's prefetching,
     *     request TestTextView1's node
     *
     * Request 1 prefetches TestTextView1's node, is interrupted by a pending request, and checks
     * if its prefetched nodes satisfy any pending requests. It satisfies Request 2's request for
     * TestTextView1's node. Request 2 is fulfilled, so it is removed from queue and does not
     * prefetch.
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRootAndTextNode_withOneClient_shouldInterruptPrefetchAndSatisfyPendingMsg()
            throws RemoteException {
        mSendClient1RequestForTextAfterTextPrefetched = true;

        mTextView1.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(TEXT_VIEW_1_DESCRIPTION);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendClient1RequestForTextAfterTextPrefetched) {
                    // Prevent a loop when processing second request
                    mSendClient1RequestForTextAfterTextPrefetched = false;
                    // TextView1 is prefetched here after the FrameLayout is found. Now enqueue a
                    // same-client request for TextView1
                    sendNodeRequestToController(nodeId, mMockClientCallback1,
                            ++mMockClient1InteractionId, FLAG_PREFETCH_SIBLINGS);

                }
            }
        });
        // Client 1 requests FrameLayout
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS);

        // Flush out all messages
        mInstrumentation.waitForIdleSync();

        // When TextView1 is prefetched for FrameLayout, we put a message on the queue in
        // TextView1's onInitializeA11yNodeInfo that requests for TextView1. The service thus get
        // two node results for FrameLayout and TextView1.
        verify(mMockClientCallback1, times(2))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(), anyInt());

        List<AccessibilityNodeInfo> foundNodes = mFindInfoCaptor.getAllValues();
        assertEquals(FRAME_LAYOUT_DESCRIPTION, foundNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, foundNodes.get(1).getContentDescription());

        // The controller will look at FrameLayout's prefetched nodes and find matching nodes in
        // pending requests. The prefetched TextView1 matches the second request. The second
        // request was removed from queue and prefetching for this request never occurred.
        verify(mMockClientCallback1, times(1))
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient1InteractionId - 1));
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(1, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
    }

    /**
     * Like above, but tests a series of controller requests from different services to interrupt
     * prefetching and satisfy a pending node request.
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRootAndTextNode_withTwoClients_shouldInterruptPrefetchAndSatisfyPendingMsg()
            throws RemoteException {
        mSendClient2RequestForTextAfterTextPrefetched = true;
        mTextView1.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(TEXT_VIEW_1_DESCRIPTION);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendClient2RequestForTextAfterTextPrefetched) {
                    mSendClient2RequestForTextAfterTextPrefetched = false;
                    // TextView1 is prefetched here. Now enqueue client 2's request for
                    // TextView1
                    sendNodeRequestToController(nodeId, mMockClientCallback2,
                            mMockClient2InteractionId, FLAG_PREFETCH_SIBLINGS);
                }
            }
        });
        // Client 1 requests FrameLayout
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS);

        mInstrumentation.waitForIdleSync();

        // Verify client 1 gets FrameLayout
        verify(mMockClientCallback1, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(), anyInt());
        assertEquals(FRAME_LAYOUT_DESCRIPTION,
                mFindInfoCaptor.getValue().getContentDescription());

        // Verify client 1 has prefetched nodes
        verify(mMockClientCallback1, times(1))
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient1InteractionId));

        // Verify client 1's only prefetched node is TextView1
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(1, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());

        // Verify client 2 gets TextView1
        verify(mMockClientCallback2, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(), anyInt());

        assertEquals(TEXT_VIEW_1_DESCRIPTION, mFindInfoCaptor.getValue().getContentDescription());

        // The second request was removed from queue and prefetching for this client request never
        // occurred as it was satisfied.
        verify(mMockClientCallback2, never())
                .setPrefetchAccessibilityNodeInfoResult(anyList(), anyInt());

    }

    @Test
    public void testFindNodeById_withTwoDifferentPrefetchFlags_shouldNotSatisfyPendingRequest()
            throws RemoteException {
        mSendRequestForTextAndIncludeUnImportantViews = true;
        mTextView1.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(TEXT_VIEW_1_DESCRIPTION);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendRequestForTextAndIncludeUnImportantViews) {
                    mSendRequestForTextAndIncludeUnImportantViews = false;
                    // TextView1 is prefetched here for client 1. Now enqueue a request from a
                    // different client that holds different fetch flags for TextView1
                    sendNodeRequestToController(nodeId, mMockClientCallback2,
                            mMockClient2InteractionId,
                            FLAG_PREFETCH_SIBLINGS | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS);
                }
            }
        });

        // Mockito does not make copies of objects when called. It holds references, so
        // the captor would point to client 2's results after all requests are processed. Verify
        // prefetched node immediately
        doAnswer(invocation -> {
            List<AccessibilityNodeInfo> prefetched = invocation.getArgument(0);
            assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetched.get(0).getContentDescription());
            return null;
        }).when(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(anyList(),
                eq(mMockClient1InteractionId));

        // Client 1 requests FrameLayout
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS);

        mInstrumentation.waitForIdleSync();

        // Verify client 1 gets FrameLayout
        verify(mMockClientCallback1, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(),
                        eq(mMockClient1InteractionId));

        assertEquals(FRAME_LAYOUT_DESCRIPTION,
                mFindInfoCaptor.getValue().getContentDescription());

        // Verify client 1 has prefetched results. The only prefetched node is TextView1
        // (from above doAnswer)
        verify(mMockClientCallback1, times(1))
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient1InteractionId));

        // Verify client 2 gets TextView1
        verify(mMockClientCallback2, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(),
                        eq(mMockClient2InteractionId));
        assertEquals(TEXT_VIEW_1_DESCRIPTION,
                mFindInfoCaptor.getValue().getContentDescription());
        // Verify client 2 has TextView2 as a prefetched node
        verify(mMockClientCallback2, times(1))
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient2InteractionId));
        List<AccessibilityNodeInfo> prefetchedNode = mPrefetchInfoListCaptor.getValue();
        assertEquals(1, prefetchedNode.size());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNode.get(0).getContentDescription());
    }

    private void sendNodeRequestToController(long requestedNodeId,
            IAccessibilityInteractionConnectionCallback callback, int interactionId,
            int prefetchFlags) {
        final int processAndThreadId = callback == mMockClientCallback1
                ? MOCK_CLIENT_1_THREAD_AND_PROCESS_ID
                : MOCK_CLIENT_2_THREAD_AND_PROCESS_ID;

        mAccessibilityInteractionController.findAccessibilityNodeInfoByAccessibilityIdClientThread(
                requestedNodeId,
                null, interactionId,
                callback, prefetchFlags,
                processAndThreadId,
                processAndThreadId, null, null);

    }

    private class TestFrameLayout extends FrameLayout {

        TestFrameLayout(Context context) {
            super(context);
        }

        @Override
        public int getWindowVisibility() {
            // We aren't attached to a window so let's pretend
            return VISIBLE;
        }

        @Override
        public boolean isShown() {
            // Controller check
            return true;
        }

        @Override
        public int getAccessibilityViewId() {
            // static id doesn't reset after tests so return the same one
            return 0;
        }

        @Override
        public void addChildrenForAccessibility(ArrayList<View> outChildren) {
            // ViewGroup#addChildrenForAccessbility sorting logic will switch these two
            outChildren.add(mTextView1);
            outChildren.add(mTextView2);
        }

        @Override
        public boolean includeForAccessibility() {
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription(FRAME_LAYOUT_DESCRIPTION);
        }
    }

    private class TestTextView extends TextView {
        TestTextView(Context context) {
            super(context);
        }

        @Override
        public int getWindowVisibility() {
            return VISIBLE;
        }

        @Override
        public boolean isShown() {
            return true;
        }

        @Override
        public int getAccessibilityViewId() {
            return 1;
        }

        @Override
        public boolean includeForAccessibility() {
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription(TEXT_VIEW_1_DESCRIPTION);
        }
    }

    private class TestTextView2 extends TextView {
        TestTextView2(Context context) {
            super(context);
        }

        @Override
        public int getWindowVisibility() {
            return VISIBLE;
        }

        @Override
        public boolean isShown() {
            return true;
        }

        @Override
        public int getAccessibilityViewId() {
            return 2;
        }

        @Override
        public boolean includeForAccessibility() {
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription(TEXT_VIEW_2_DESCRIPTION);
        }
    }
}
