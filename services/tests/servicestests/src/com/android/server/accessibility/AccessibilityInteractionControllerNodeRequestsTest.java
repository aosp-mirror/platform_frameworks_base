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
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS;
import static android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE;
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
    @Captor
    private ArgumentCaptor<List<AccessibilityNodeInfo>> mFindInfosCaptor;
    @Captor private ArgumentCaptor<List<AccessibilityNodeInfo>> mPrefetchInfoListCaptor;

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private static final int MOCK_CLIENT_1_THREAD_AND_PROCESS_ID = 1;
    private static final int MOCK_CLIENT_2_THREAD_AND_PROCESS_ID = 2;

    private static final String ROOT_FRAME_LAYOUT_DESCRIPTION = "rootFrameLayout";
    private static final String TEXT_VIEW_1_DESCRIPTION = "textView1";
    private static final String TEXT_VIEW_2_DESCRIPTION = "textView2";
    private static final String CHILD_FRAME_DESCRIPTION = "childFrameLayout";
    private static final String TEXT_VIEW_3_DESCRIPTION = "textView3";
    private static final String TEXT_VIEW_4_DESCRIPTION = "textView4";
    private static final String VIRTUAL_VIEW_1_DESCRIPTION = "virtual descendant 1";
    private static final String VIRTUAL_VIEW_2_DESCRIPTION = "virtual descendant 2";
    private static final String VIRTUAL_VIEW_3_DESCRIPTION = "virtual descendant 3";

    private TestFrameLayout mRootFrameLayout;
    private TestFrameLayout mChildFrameLayout;
    private TestTextView mTextView1;
    private TestTextView mTextView2;
    private TestTextView mTextView3;
    private TestTextView mTextView4;

    private boolean mSendClient1RequestForTextAfterTextPrefetched;
    private boolean mSendClient2RequestForTextAfterTextPrefetched;
    private boolean mSendRequestForTextAndIncludeUnImportantViews;
    private boolean mSendClient1RequestForRootAfterTextPrefetched;
    private boolean mSendClient2RequestForTextAfterRootPrefetched;

    private int mMockClient1InteractionId;
    private int mMockClient2InteractionId;

    @Before
    public void setUp() throws Throwable {
        MockitoAnnotations.initMocks(this);

        mInstrumentation.runOnMainSync(() -> {
            final Context context = mInstrumentation.getTargetContext();
            final ViewRootImpl viewRootImpl = new ViewRootImpl(context, context.getDisplay());

            mTextView1 = new TestTextView(context, 1, TEXT_VIEW_1_DESCRIPTION);
            mTextView2 = new TestTextView(context, 2, TEXT_VIEW_2_DESCRIPTION);
            mTextView3 = new TestTextView(context, 4, TEXT_VIEW_3_DESCRIPTION);
            mTextView4 = new TestTextView(context, 5, TEXT_VIEW_4_DESCRIPTION);

            mChildFrameLayout = new TestFrameLayout(context, 3,
                    CHILD_FRAME_DESCRIPTION, new ArrayList<>(List.of(mTextView4)));
            mRootFrameLayout = new TestFrameLayout(context, 0, ROOT_FRAME_LAYOUT_DESCRIPTION,
                    new ArrayList<>(
                            List.of(mTextView1, mTextView2, mChildFrameLayout, mTextView3)));

            mRootFrameLayout.addView(mTextView1);
            mRootFrameLayout.addView(mTextView2);
            mChildFrameLayout.addView(mTextView4);
            mRootFrameLayout.addView(mChildFrameLayout);
            mRootFrameLayout.addView(mTextView3);

            // Layout
            //                        mRootFrameLayout
            //               /         |        |              \
            //       mTextView1 mTextView2 mChildFrameLayout  mTextView3
            //                                    |
            //                                mTextView4

            // The controller retrieves views through this manager, and registration happens on
            // when attached to a window, which we don't have. We can simply reference
            // RootFrameLayout with ROOT_NODE_ID.
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mTextView1, mTextView1.getAccessibilityViewId());
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mTextView2, mTextView2.getAccessibilityViewId());
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mTextView3, mTextView3.getAccessibilityViewId());
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mChildFrameLayout, mChildFrameLayout.getAccessibilityViewId());
            AccessibilityNodeIdManager.getInstance().registerViewWithId(
                    mTextView4, mTextView4.getAccessibilityViewId());
            try {
                viewRootImpl.setView(mRootFrameLayout, new WindowManager.LayoutParams(), null);

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
        AccessibilityNodeIdManager.getInstance().unregisterViewWithId(
                mTextView3.getAccessibilityViewId());
        AccessibilityNodeIdManager.getInstance().unregisterViewWithId(
                mTextView4.getAccessibilityViewId());
        AccessibilityNodeIdManager.getInstance().unregisterViewWithId(
                mChildFrameLayout.getAccessibilityViewId());
    }

    /**
     * Tests a basic request for the root node with prefetch flag
     * {@link AccessibilityNodeInfo#FLAG_PREFETCH_DESCENDANTS_HYBRID}
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRootView_withOneClient_shouldReturnRootNodeAndPrefetchDescendants()
            throws RemoteException {
        // Request for our RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);
        mInstrumentation.waitForIdleSync();

        // Verify we get RootFrameLayout.
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));
        // The descendants are RootFrameLayout's 5 descendants.
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(5, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNodes.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_3_DESCRIPTION, prefetchedNodes.get(3).getContentDescription());
        assertEquals(TEXT_VIEW_4_DESCRIPTION, prefetchedNodes.get(4).getContentDescription());
    }

    /**
     * Tests a basic request for TextView1's node with prefetch flag.
     * {@link AccessibilityNodeInfo#FLAG_PREFETCH_SIBLINGS} and
     * {@link AccessibilityNodeInfo#FLAG_PREFETCH_ANCESTORS}.
     *
     * @throws RemoteException
     */
    @Test
    public void testFindTextView_withOneClient_shouldReturnNodeAndPrefetchedSiblingsAndParent()
            throws RemoteException {
        // Request for TextView1.
        sendNodeRequestToController(AccessibilityNodeInfo.makeNodeId(
                mTextView1.getAccessibilityViewId(), AccessibilityNodeProvider.HOST_VIEW_ID),
                mMockClientCallback1, mMockClient1InteractionId,
                FLAG_PREFETCH_SIBLINGS | FLAG_PREFETCH_ANCESTORS);
        mInstrumentation.waitForIdleSync();

        // Verify we get TextView1.
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(TEXT_VIEW_1_DESCRIPTION, infoSentToService.getContentDescription());

        // Verify the prefetched sibling of TextView1 is TextView2, ChildFrameLayout, and TextView3.
        // The predecessor is RootFrameLayout.
        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(4, prefetchedNodes.size());
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNodes.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_3_DESCRIPTION, prefetchedNodes.get(3).getContentDescription());
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
        mSendClient2RequestForTextAfterRootPrefetched = true;
        mRootFrameLayout.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendClient2RequestForTextAfterRootPrefetched) {
                    mSendClient2RequestForTextAfterRootPrefetched = false;

                    // Enqueue a request when this node is found from  client 2 for TextView1.
                    sendNodeRequestToController(nodeId, mMockClientCallback2,
                            mMockClient2InteractionId,
                            FLAG_PREFETCH_SIBLINGS);
                }
            }
        });
        // Client 1 request for RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

        mInstrumentation.waitForIdleSync();

        // Verify client 1 gets RootFrameLayout.
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        // The second request is put in the queue in the RootFrameLayout's onInitializeA11yNodeInfo,
        // meaning prefetching is does not occur for first request.
        verify(mMockClientCallback1, never())
                .setPrefetchAccessibilityNodeInfoResult(anyList(), anyInt());

        // Verify client 2 gets TextView1.
        verify(mMockClientCallback2).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient2InteractionId));
        infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(TEXT_VIEW_1_DESCRIPTION, infoSentToService.getContentDescription());

        // Verify the prefetched sibling of TextView1 is TextView2 and ChildFrameLayout
        // (FLAG_PREFETCH_SIBLINGS). The parent, RootFrameLayout, is also retrieved. Since
        // predecessors takes priority over siblings, RootFrameLayout is the first node in the list.
        verify(mMockClientCallback2).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient2InteractionId));
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(4, prefetchedNodes.size());
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNodes.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_3_DESCRIPTION, prefetchedNodes.get(3).getContentDescription());
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
     * prefetch. TestTextView1 is removed from Request's 1's prefetched results, meaning the list
     * is empty.
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
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendClient1RequestForTextAfterTextPrefetched) {
                    // Prevent a loop when processing this node's second request.
                    mSendClient1RequestForTextAfterTextPrefetched = false;
                    // TextView1 is prefetched here after the RootFrameLayout is found. Now enqueue
                    // a same-client request for TextView1.
                    sendNodeRequestToController(nodeId, mMockClientCallback1,
                            ++mMockClient1InteractionId,
                            FLAG_PREFETCH_SIBLINGS | FLAG_PREFETCH_ANCESTORS);

                }
            }
        });
        // Client 1 requests RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

        // Flush out all messages.
        mInstrumentation.waitForIdleSync();

        // When TextView1 is prefetched for RootFrameLayout, we put a message on the queue in
        // TextView1's onInitializeA11yNodeInfo that requests for TextView1. The service thus gets
        // two node results for FrameLayout and TextView1.
        verify(mMockClientCallback1, times(2))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(), anyInt());

        List<AccessibilityNodeInfo> foundNodes = mFindInfoCaptor.getAllValues();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, foundNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, foundNodes.get(1).getContentDescription());

        // The controller will look at RootFrameLayout's prefetched nodes and find matching nodes in
        // pending requests. The prefetched TextView1 satisfied the second request. This is removed
        // from the first request's prefetch list, which is now empty. The second request is removed
        // from queue.
        verify(mMockClientCallback1, never())
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient1InteractionId - 1));
    }

    /**
     * Tests a series of controller same-service requests to interrupt prefetching and satisfy a
     * pending node request.
     *     Request 1: Request the root node
     *     Request 2: When TextTextView1's node is initialized as part of Request 1's prefetching,
     *     request the root node
     *
     * Request 1 prefetches TestTextView1's node, is interrupted by a pending request, and checks
     * if its prefetched nodes satisfy any pending requests. It satisfies Request 2's request for
     * the root node. Request 2 is fulfilled, so it is removed from queue and does not
     * prefetch.
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRoot_withOneClient_shouldInterruptPrefetchAndSatisfyPendingMsg()
            throws RemoteException {
        mSendClient1RequestForRootAfterTextPrefetched = true;

        mTextView1.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mRootFrameLayout.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendClient1RequestForRootAfterTextPrefetched) {
                    // Prevent a loop when processing this node's second request.
                    mSendClient1RequestForRootAfterTextPrefetched = false;
                    // TextView1 is prefetched here after the FrameLayout is found. Now enqueue a
                    // same-client request for FrameLayout.
                    sendNodeRequestToController(nodeId, mMockClientCallback1,
                            ++mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

                }
            }
        });
        // Client 1 requests RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

        // Flush out all messages.
        mInstrumentation.waitForIdleSync();

        // When TextView1 is prefetched for RootFrameLayout, we put a message on the queue in
        // TextView1's onInitializeA11yNodeInfo that requests for TextView1. The service thus gets
        // two node results for FrameLayout and TextView1.
        verify(mMockClientCallback1, times(2))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(), anyInt());

        List<AccessibilityNodeInfo> foundNodes = mFindInfoCaptor.getAllValues();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, foundNodes.get(0).getContentDescription());
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, foundNodes.get(1).getContentDescription());

        // The controller will look at RootFrameLayout's prefetched nodes and find matching nodes in
        // pending requests.  The first requested node (FrameLayout) is also checked, and this
        // satisfies the second request. The second request is removed from queue and prefetching
        // for this request never occurs.
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
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendClient2RequestForTextAfterTextPrefetched) {
                    mSendClient2RequestForTextAfterTextPrefetched = false;
                    // TextView1 is prefetched here. Now enqueue client 2's request for
                    // TextView1.
                    sendNodeRequestToController(nodeId, mMockClientCallback2,
                            mMockClient2InteractionId, FLAG_PREFETCH_SIBLINGS);
                }
            }
        });
        // Client 1 requests RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

        mInstrumentation.waitForIdleSync();

        // Verify client 1 gets RootFrameLayout.
        verify(mMockClientCallback1, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(), anyInt());
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION,
                mFindInfoCaptor.getValue().getContentDescription());

        // Verify client 1 doesn't have prefetched nodes.
        verify(mMockClientCallback1, never())
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient1InteractionId));

        // Verify client 2 gets TextView1.
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
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                if (mSendRequestForTextAndIncludeUnImportantViews) {
                    mSendRequestForTextAndIncludeUnImportantViews = false;
                    // TextView1 is prefetched here for client 1. Now enqueue a request from a
                    // different client that holds different fetch flags for TextView1.
                    sendNodeRequestToController(nodeId, mMockClientCallback2,
                            mMockClient2InteractionId,
                            FLAG_PREFETCH_SIBLINGS | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                                    | FLAG_PREFETCH_ANCESTORS);
                }
            }
        });

        // Mockito does not make copies of objects when called. It holds references, so
        // the captor would point to client 2's results after all requests are processed. Verify
        // prefetched node immediately.
        doAnswer(invocation -> {
            List<AccessibilityNodeInfo> prefetched = invocation.getArgument(0);
            assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetched.get(0).getContentDescription());
            return null;
        }).when(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(anyList(),
                eq(mMockClient1InteractionId));

        // Client 1 requests RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

        mInstrumentation.waitForIdleSync();

        // Verify client 1 gets RootFrameLayout.
        verify(mMockClientCallback1, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(),
                        eq(mMockClient1InteractionId));

        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION,
                mFindInfoCaptor.getValue().getContentDescription());

        // Verify client 1 has prefetched results. The only prefetched node is TextView1
        // (from above doAnswer).
        verify(mMockClientCallback1, times(1))
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient1InteractionId));

        // Verify client 2 gets TextView1.
        verify(mMockClientCallback2, times(1))
                .setFindAccessibilityNodeInfoResult(mFindInfoCaptor.capture(),
                        eq(mMockClient2InteractionId));
        assertEquals(TEXT_VIEW_1_DESCRIPTION,
                mFindInfoCaptor.getValue().getContentDescription());
        // Verify client 2 gets TextView1's siblings and its parent as prefetched nodes.
        verify(mMockClientCallback2, times(1))
                .setPrefetchAccessibilityNodeInfoResult(mPrefetchInfoListCaptor.capture(),
                        eq(mMockClient2InteractionId));
        List<AccessibilityNodeInfo> prefetchedNode = mPrefetchInfoListCaptor.getValue();
        assertEquals(4, prefetchedNode.size());
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, prefetchedNode.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNode.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNode.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_3_DESCRIPTION, prefetchedNode.get(3).getContentDescription());
    }

    /**
     * Tests a request for 4 nodes using depth first traversal.
     *     Request 1: Request the root node.
     *     Request 2: When TextView4 is prefetched, send a request for the root node. Depth first
     *     traversal completes here.
     * Out of the 5 descendants, the root frame's 3rd child (TextView3) should not be prefetched,
     * since this was not reached by the df-traversal.
     *
     *   Layout
     *                        mRootFrameLayout
     *                /         |        |              \
     *      mTextView1 mTextView2 mChildFrameLayout  *mTextView3*
     *                                    |
     *                                mTextView4
     * @throws RemoteException
     */
    @Test
    public void testFindRootView_depthFirstStrategy_shouldReturnRootNodeAndPrefetch4Descendants()
            throws RemoteException {
        mTextView4.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mRootFrameLayout.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);
                // This request is satisfied by first request.
                sendNodeRequestToController(nodeId, mMockClientCallback2,
                        mMockClient2InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);

            }
        });
        // Request for our RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST);
        mInstrumentation.waitForIdleSync();

        // Verify we get RootFrameLayout.
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));
        // Prefetch all the descendants besides TextView3.
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(4, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNodes.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_4_DESCRIPTION, prefetchedNodes.get(3).getContentDescription());
    }

    /**
     * Tests a request for 4 nodes using breadth first traversal.
     *     Request 1: Request the root node
     *     Request 2: When TextView3 is prefetched, send a request for the root node. Breadth first
     *     traversal completes here.
     * Out of the 5 descendants, the child frame's child (TextView4) should not be prefetched, since
     * this was not reached by the bf-traversal.
     *   Layout
     *                        mRootFrameLayout
     *                /         |        |              \
     *      mTextView1 mTextView2 mChildFrameLayout  *mTextView3*
     *                                    |
     *                                *mTextView4*
     * @throws RemoteException
     */
    @Test
    public void testFindRootView_breadthFirstStrategy_shouldReturnRootNodeAndPrefetch4Descendants()
            throws RemoteException {

        mTextView3.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mRootFrameLayout.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                // This request is satisfied by first request.
                sendNodeRequestToController(nodeId, mMockClientCallback2,
                        mMockClient2InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);
            }
        });
        // Request for our RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST);
        mInstrumentation.waitForIdleSync();

        // Verify we get RootFrameLayout.
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));
        // Prefetch all the descendants besides TextView4.
        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(4, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNodes.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_3_DESCRIPTION, prefetchedNodes.get(3).getContentDescription());
    }

    /**
     * Tests a request that should not have prefetching interrupted.
     *     Request 1: Client 1 requests the root node
     *     Request 2: When the root node is initialized in
     *     {@link TestFrameLayout#onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo)},
     *     Client 2 requests TextView1's node
     *
     * Request 1 is not interrupted during prefetch, and its prefetched node satisfies Request 2.
     *
     * @throws RemoteException
     */
    @Test
    public void testFindRootAndTextNodes_withNoInterruptStrategy_shouldSatisfySecondRequest()
            throws RemoteException {
        mRootFrameLayout.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                final long nodeId = AccessibilityNodeInfo.makeNodeId(
                        mTextView1.getAccessibilityViewId(),
                        AccessibilityNodeProvider.HOST_VIEW_ID);

                // TextView1 is prefetched here after the RootFrameLayout is found. Now enqueue a
                // same-client request for RootFrameLayout.
                sendNodeRequestToController(nodeId, mMockClientCallback2,
                        mMockClient2InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID);
                }
        });

        // Client 1 request for RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_HYBRID
                        | FLAG_PREFETCH_UNINTERRUPTIBLE);

        mInstrumentation.waitForIdleSync();

        // When the controller returns the nodes, it clears the sent list. Check immediately since
        // the captor will be cleared.
        doAnswer(invocation -> {
            List<AccessibilityNodeInfo> nodes = invocation.getArgument(0);
            assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, nodes.get(0).getContentDescription());
            assertEquals(TEXT_VIEW_2_DESCRIPTION, nodes.get(1).getContentDescription());
            assertEquals(CHILD_FRAME_DESCRIPTION, nodes.get(2).getContentDescription());
            assertEquals(TEXT_VIEW_3_DESCRIPTION, nodes.get(3).getContentDescription());
            assertEquals(TEXT_VIEW_4_DESCRIPTION, nodes.get(4).getContentDescription());
            return null;
        }).when(mMockClientCallback1).setFindAccessibilityNodeInfosResult(
                anyList(), eq(mMockClient1InteractionId));

        verify(mMockClientCallback1, never())
                .setPrefetchAccessibilityNodeInfoResult(anyList(), anyInt());

        // Verify client 2 gets TextView1.
        verify(mMockClientCallback2).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient2InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(TEXT_VIEW_1_DESCRIPTION, infoSentToService.getContentDescription());
    }

    /**
     * Tests a request for root node where a virtual hierarchy is prefetched.
     *
     *   Layout
     *                        mRootFrameLayout
     *                /         |        |              \
     *      mTextView1 mTextView2 mChildFrameLayout  *mTextView3*
     *                                    |
     *                                *mTextView4*
     *                                  |         \
     *                          virtual view 1   virtual view 2
     *                                 |
     *                             virtual view 3
     * @throws RemoteException
     */
    @Test
    public void testFindRootView_withVirtualView()
            throws RemoteException {
        mTextView4.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override
            public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
                return new AccessibilityNodeProvider() {
                    @Override
                    public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                        if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                            AccessibilityNodeInfo node = new AccessibilityNodeInfo(host);
                            node.addChild(host, 1);
                            node.addChild(host, 2);
                            node.setContentDescription(TEXT_VIEW_4_DESCRIPTION);
                            return node;
                        } else if (virtualViewId == 1) {
                            AccessibilityNodeInfo node = new AccessibilityNodeInfo(
                                    host, virtualViewId);
                            node.setParent(host);
                            node.setContentDescription(VIRTUAL_VIEW_1_DESCRIPTION);
                            node.addChild(host, 3);
                            return node;
                        } else if (virtualViewId == 2 || virtualViewId == 3) {
                            AccessibilityNodeInfo node = new AccessibilityNodeInfo(
                                    host, virtualViewId);
                            node.setParent(host);
                            node.setContentDescription(virtualViewId == 2
                                    ? VIRTUAL_VIEW_2_DESCRIPTION
                                    : VIRTUAL_VIEW_3_DESCRIPTION);
                            return node;
                        }
                        return null;
                    }
                };
            }
        });
        // Request for our RootFrameLayout.
        sendNodeRequestToController(ROOT_NODE_ID, mMockClientCallback1,
                mMockClient1InteractionId, FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST);
        mInstrumentation.waitForIdleSync();

        // Verify we get RootFrameLayout.
        verify(mMockClientCallback1).setFindAccessibilityNodeInfoResult(
                mFindInfoCaptor.capture(), eq(mMockClient1InteractionId));
        AccessibilityNodeInfo infoSentToService = mFindInfoCaptor.getValue();
        assertEquals(ROOT_FRAME_LAYOUT_DESCRIPTION, infoSentToService.getContentDescription());

        verify(mMockClientCallback1).setPrefetchAccessibilityNodeInfoResult(
                mPrefetchInfoListCaptor.capture(), eq(mMockClient1InteractionId));

        List<AccessibilityNodeInfo> prefetchedNodes = mPrefetchInfoListCaptor.getValue();
        assertEquals(8, prefetchedNodes.size());
        assertEquals(TEXT_VIEW_1_DESCRIPTION, prefetchedNodes.get(0).getContentDescription());
        assertEquals(TEXT_VIEW_2_DESCRIPTION, prefetchedNodes.get(1).getContentDescription());
        assertEquals(CHILD_FRAME_DESCRIPTION, prefetchedNodes.get(2).getContentDescription());
        assertEquals(TEXT_VIEW_4_DESCRIPTION, prefetchedNodes.get(3).getContentDescription());

        assertEquals(VIRTUAL_VIEW_1_DESCRIPTION, prefetchedNodes.get(4).getContentDescription());
        assertEquals(VIRTUAL_VIEW_3_DESCRIPTION, prefetchedNodes.get(5).getContentDescription());

        assertEquals(VIRTUAL_VIEW_2_DESCRIPTION, prefetchedNodes.get(6).getContentDescription());
        assertEquals(TEXT_VIEW_3_DESCRIPTION, prefetchedNodes.get(7).getContentDescription());


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
                processAndThreadId, null, null, null);

    }

    private class TestFrameLayout extends FrameLayout {
        private int mA11yId;
        private String mContentDescription;
        ArrayList<View> mChildren;

        TestFrameLayout(Context context, int a11yId, String contentDescription,
                ArrayList<View> children) {
            super(context);
            mA11yId = a11yId;
            mContentDescription = contentDescription;
            mChildren = children;
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
            return mA11yId;
        }

        @Override
        public void addChildrenForAccessibility(ArrayList<View> outChildren) {
            // ViewGroup#addChildrenForAccessbility sorting logic will switch these two
            for (View view : mChildren) {
                outChildren.add(view);
            }
        }

        @Override
        public boolean includeForAccessibility() {
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription(mContentDescription);
        }
    }

    private class TestTextView extends TextView {
        private int mA11yId;
        private String mContentDescription;
        TestTextView(Context context, int a11yId, String contentDescription) {
            super(context);
            mA11yId = a11yId;
            mContentDescription = contentDescription;
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
            return mA11yId;
        }

        @Override
        public boolean includeForAccessibility() {
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription(mContentDescription);
        }
    }
}
