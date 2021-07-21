/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.view;

import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN;
import static android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_REQUESTED_KEY;
import static android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.style.AccessibilityClickableSpan;
import android.text.style.ClickableSpan;
import android.util.LongSparseArray;
import android.util.Slog;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeIdManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.AccessibilityRequestPreparer;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * Class for managing accessibility interactions initiated from the system
 * and targeting the view hierarchy. A *ClientThread method is to be
 * called from the interaction connection ViewAncestor gives the system to
 * talk to it and a corresponding *UiThread method that is executed on the
 * UI thread.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class AccessibilityInteractionController {

    private static final String LOG_TAG = "AccessibilityInteractionController";

    // Debugging flag
    private static final boolean ENFORCE_NODE_TREE_CONSISTENT = false;

    // Constants for readability
    private static final boolean IGNORE_REQUEST_PREPARERS = true;
    private static final boolean CONSIDER_REQUEST_PREPARERS = false;

    // If an app holds off accessibility for longer than this, the hold-off is canceled to prevent
    // accessibility from hanging
    private static final long REQUEST_PREPARER_TIMEOUT_MS = 500;

    // Callbacks should have the same configuration of the flags below to allow satisfying a pending
    // node request on prefetch
    private static final int FLAGS_AFFECTING_REPORTED_DATA =
            AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            | AccessibilityNodeInfo.FLAG_REPORT_VIEW_IDS;

    private final ArrayList<AccessibilityNodeInfo> mTempAccessibilityNodeInfoList =
        new ArrayList<AccessibilityNodeInfo>();

    private final Object mLock = new Object();

    private final PrivateHandler mHandler;

    private final ViewRootImpl mViewRootImpl;

    private final AccessibilityNodePrefetcher mPrefetcher;

    private final long mMyLooperThreadId;

    private final int mMyProcessId;

    private final AccessibilityManager mA11yManager;

    private final ArrayList<View> mTempArrayList = new ArrayList<View>();

    private final Point mTempPoint = new Point();
    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();
    private final Rect mTempRect2 = new Rect();
    private final RectF mTempRectF = new RectF();

    private AddNodeInfosForViewId mAddNodeInfosForViewId;

    @GuardedBy("mLock")
    private ArrayList<Message> mPendingFindNodeByIdMessages;

    @GuardedBy("mLock")
    private int mNumActiveRequestPreparers;
    @GuardedBy("mLock")
    private List<MessageHolder> mMessagesWaitingForRequestPreparer;
    @GuardedBy("mLock")
    private int mActiveRequestPreparerId;

    public AccessibilityInteractionController(ViewRootImpl viewRootImpl) {
        Looper looper =  viewRootImpl.mHandler.getLooper();
        mMyLooperThreadId = looper.getThread().getId();
        mMyProcessId = Process.myPid();
        mHandler = new PrivateHandler(looper);
        mViewRootImpl = viewRootImpl;
        mPrefetcher = new AccessibilityNodePrefetcher();
        mA11yManager = mViewRootImpl.mContext.getSystemService(AccessibilityManager.class);
        mPendingFindNodeByIdMessages = new ArrayList<>();
    }

    private void scheduleMessage(Message message, int interrogatingPid, long interrogatingTid,
            boolean ignoreRequestPreparers) {
        if (ignoreRequestPreparers
                || !holdOffMessageIfNeeded(message, interrogatingPid, interrogatingTid)) {
            // If the interrogation is performed by the same thread as the main UI
            // thread in this process, set the message as a static reference so
            // after this call completes the same thread but in the interrogating
            // client can handle the message to generate the result.
            if (interrogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId
                    && mHandler.hasAccessibilityCallback(message)) {
                AccessibilityInteractionClient.getInstanceForThread(
                        interrogatingTid).setSameThreadMessage(message);
            } else {
                // For messages without callback of interrogating client, just handle the
                // message immediately if this is UI thread.
                if (!mHandler.hasAccessibilityCallback(message)
                        && Thread.currentThread().getId() == mMyLooperThreadId) {
                    mHandler.handleMessage(message);
                } else {
                    mHandler.sendMessage(message);
                }
            }
        }
    }

    private boolean isShown(View view) {
        return (view != null) && (view.getWindowVisibility() == View.VISIBLE && view.isShown());
    }

    public void findAccessibilityNodeInfoByAccessibilityIdClientThread(
            long accessibilityNodeId, Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid, MagnificationSpec spec, Bundle arguments) {
        final Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID;
        message.arg1 = flags;

        final SomeArgs args = SomeArgs.obtain();
        args.argi1 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi2 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi3 = interactionId;
        args.arg1 = callback;
        args.arg2 = spec;
        args.arg3 = interactiveRegion;
        args.arg4 = arguments;
        message.obj = args;

        synchronized (mLock) {
            mPendingFindNodeByIdMessages.add(message);
            scheduleMessage(message, interrogatingPid, interrogatingTid,
                    CONSIDER_REQUEST_PREPARERS);
        }
    }

    /**
     * Check if this message needs to be held off while the app prepares to meet either this
     * request, or a request ahead of it.
     *
     * @param originalMessage The message to be processed
     * @param callingPid The calling process id
     * @param callingTid The calling thread id
     *
     * @return {@code true} if the message is held off and will be processed later, {@code false} if
     *         the message should be posted.
     */
    private boolean holdOffMessageIfNeeded(
            Message originalMessage, int callingPid, long callingTid) {
        synchronized (mLock) {
            // If a request is already pending, queue this request for when it's finished
            if (mNumActiveRequestPreparers != 0) {
                queueMessageToHandleOncePrepared(originalMessage, callingPid, callingTid);
                return true;
            }

            // Currently the only message that can hold things off is findByA11yId with extra data.
            if (originalMessage.what
                    != PrivateHandler.MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID) {
                return false;
            }
            SomeArgs originalMessageArgs = (SomeArgs) originalMessage.obj;
            Bundle requestArguments = (Bundle) originalMessageArgs.arg4;
            if (requestArguments == null) {
                return false;
            }

            // If nothing it registered for this view, nothing to do
            int accessibilityViewId = originalMessageArgs.argi1;
            final List<AccessibilityRequestPreparer> preparers =
                    mA11yManager.getRequestPreparersForAccessibilityId(accessibilityViewId);
            if (preparers == null) {
                return false;
            }

            // If the bundle doesn't request the extra data, nothing to do
            final String extraDataKey = requestArguments.getString(EXTRA_DATA_REQUESTED_KEY);
            if (extraDataKey == null) {
                return false;
            }

            // Send the request to the AccessibilityRequestPreparers on the UI thread
            mNumActiveRequestPreparers = preparers.size();
            for (int i = 0; i < preparers.size(); i++) {
                final Message requestPreparerMessage = mHandler.obtainMessage(
                        PrivateHandler.MSG_PREPARE_FOR_EXTRA_DATA_REQUEST);
                final SomeArgs requestPreparerArgs = SomeArgs.obtain();
                // virtualDescendentId
                requestPreparerArgs.argi1 =
                        (originalMessageArgs.argi2 == AccessibilityNodeInfo.UNDEFINED_ITEM_ID)
                        ? AccessibilityNodeProvider.HOST_VIEW_ID : originalMessageArgs.argi2;
                requestPreparerArgs.arg1 = preparers.get(i);
                requestPreparerArgs.arg2 = extraDataKey;
                requestPreparerArgs.arg3 = requestArguments;
                Message preparationFinishedMessage = mHandler.obtainMessage(
                        PrivateHandler.MSG_APP_PREPARATION_FINISHED);
                preparationFinishedMessage.arg1 = ++mActiveRequestPreparerId;
                requestPreparerArgs.arg4 = preparationFinishedMessage;

                requestPreparerMessage.obj = requestPreparerArgs;
                scheduleMessage(requestPreparerMessage, callingPid, callingTid,
                        IGNORE_REQUEST_PREPARERS);
                mHandler.obtainMessage(PrivateHandler.MSG_APP_PREPARATION_TIMEOUT);
                mHandler.sendEmptyMessageDelayed(PrivateHandler.MSG_APP_PREPARATION_TIMEOUT,
                        REQUEST_PREPARER_TIMEOUT_MS);
            }

            // Set the initial request aside
            queueMessageToHandleOncePrepared(originalMessage, callingPid, callingTid);
            return true;
        }
    }

    private void prepareForExtraDataRequestUiThread(Message message) {
        SomeArgs args = (SomeArgs) message.obj;
        final int virtualDescendantId = args.argi1;
        final AccessibilityRequestPreparer preparer = (AccessibilityRequestPreparer) args.arg1;
        final String extraDataKey = (String) args.arg2;
        final Bundle requestArguments = (Bundle) args.arg3;
        final Message preparationFinishedMessage = (Message) args.arg4;

        preparer.onPrepareExtraData(virtualDescendantId, extraDataKey,
                requestArguments, preparationFinishedMessage);
    }

    private void queueMessageToHandleOncePrepared(Message message, int interrogatingPid,
            long interrogatingTid) {
        if (mMessagesWaitingForRequestPreparer == null) {
            mMessagesWaitingForRequestPreparer = new ArrayList<>(1);
        }
        MessageHolder messageHolder =
                new MessageHolder(message, interrogatingPid, interrogatingTid);
        mMessagesWaitingForRequestPreparer.add(messageHolder);
    }

    private void requestPreparerDoneUiThread(Message message) {
        synchronized (mLock) {
            if (message.arg1 != mActiveRequestPreparerId) {
                Slog.e(LOG_TAG, "Surprising AccessibilityRequestPreparer callback (likely late)");
                return;
            }
            mNumActiveRequestPreparers--;
            if (mNumActiveRequestPreparers <= 0) {
                mHandler.removeMessages(PrivateHandler.MSG_APP_PREPARATION_TIMEOUT);
                scheduleAllMessagesWaitingForRequestPreparerLocked();
            }
        }
    }

    private void requestPreparerTimeoutUiThread() {
        synchronized (mLock) {
            Slog.e(LOG_TAG, "AccessibilityRequestPreparer timed out");
            scheduleAllMessagesWaitingForRequestPreparerLocked();
        }
    }

    @GuardedBy("mLock")
    private void scheduleAllMessagesWaitingForRequestPreparerLocked() {
        int numMessages = mMessagesWaitingForRequestPreparer.size();
        for (int i = 0; i < numMessages; i++) {
            MessageHolder request = mMessagesWaitingForRequestPreparer.get(i);
            scheduleMessage(request.mMessage, request.mInterrogatingPid,
                    request.mInterrogatingTid,
                    (i == 0) /* the app is ready for the first request */);
        }
        mMessagesWaitingForRequestPreparer.clear();
        mNumActiveRequestPreparers = 0; // Just to be safe - should be unnecessary
        mActiveRequestPreparerId = -1;
    }

    private void findAccessibilityNodeInfoByAccessibilityIdUiThread(Message message) {
        synchronized (mLock) {
            mPendingFindNodeByIdMessages.remove(message);
        }
        final int flags = message.arg1;

        SomeArgs args = (SomeArgs) message.obj;
        final int accessibilityViewId = args.argi1;
        final int virtualDescendantId = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;
        final Region interactiveRegion = (Region) args.arg3;
        final Bundle arguments = (Bundle) args.arg4;

        args.recycle();

        View requestedView = null;
        AccessibilityNodeInfo requestedNode = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            requestedView = findViewByAccessibilityId(accessibilityViewId);
            if (requestedView != null && isShown(requestedView)) {
                requestedNode = populateAccessibilityNodeInfoForView(
                        requestedView, arguments, virtualDescendantId);
            }
        } finally {
            updateInfoForViewportAndReturnFindNodeResult(
                    requestedNode == null ? null : AccessibilityNodeInfo.obtain(requestedNode),
                    callback, interactionId, spec, interactiveRegion);
        }
        ArrayList<AccessibilityNodeInfo> infos = mTempAccessibilityNodeInfoList;
        infos.clear();
        mPrefetcher.prefetchAccessibilityNodeInfos(requestedView,
                requestedNode == null ? null : AccessibilityNodeInfo.obtain(requestedNode),
                virtualDescendantId, flags, infos);
        mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
        updateInfosForViewPort(infos, spec, interactiveRegion);
        final SatisfiedFindAccessibilityNodeByAccessibilityIdRequest satisfiedRequest =
                getSatisfiedRequestInPrefetch(
                        requestedNode == null ? null : requestedNode, infos, flags);

        if (satisfiedRequest != null && satisfiedRequest.mSatisfiedRequestNode != requestedNode) {
            infos.remove(satisfiedRequest.mSatisfiedRequestNode);
        }

        returnPrefetchResult(interactionId, infos, callback);

        if (satisfiedRequest != null) {
            returnFindNodeResult(satisfiedRequest);
        }
    }

    private AccessibilityNodeInfo populateAccessibilityNodeInfoForView(
            View view, Bundle arguments, int virtualViewId) {
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        // Determine if we'll be populating extra data
        final String extraDataRequested = (arguments == null) ? null
                : arguments.getString(EXTRA_DATA_REQUESTED_KEY);
        AccessibilityNodeInfo root = null;
        if (provider == null) {
            root = view.createAccessibilityNodeInfo();
            if (root != null) {
                if (extraDataRequested != null) {
                    view.addExtraDataToAccessibilityNodeInfo(root, extraDataRequested, arguments);
                }
            }
        } else {
            root = provider.createAccessibilityNodeInfo(virtualViewId);
            if (root != null) {
                if (extraDataRequested != null) {
                    provider.addExtraDataToAccessibilityNodeInfo(
                            virtualViewId, root, extraDataRequested, arguments);
                }
            }
        }
        return root;
    }

    public void findAccessibilityNodeInfosByViewIdClientThread(long accessibilityNodeId,
            String viewId, Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBILITY_NODE_INFOS_BY_VIEW_ID;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = SomeArgs.obtain();
        args.argi1 = interactionId;
        args.arg1 = callback;
        args.arg2 = spec;
        args.arg3 = viewId;
        args.arg4 = interactiveRegion;
        message.obj = args;

        scheduleMessage(message, interrogatingPid, interrogatingTid, CONSIDER_REQUEST_PREPARERS);
    }

    private void findAccessibilityNodeInfosByViewIdUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int interactionId = args.argi1;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;
        final String viewId = (String) args.arg3;
        final Region interactiveRegion = (Region) args.arg4;
        args.recycle();

        final List<AccessibilityNodeInfo> infos = mTempAccessibilityNodeInfoList;
        infos.clear();
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null
                    || viewId == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            final View root = findViewByAccessibilityId(accessibilityViewId);
            if (root != null) {
                final int resolvedViewId = root.getContext().getResources()
                        .getIdentifier(viewId, null, null);
                if (resolvedViewId <= 0) {
                    return;
                }
                if (mAddNodeInfosForViewId == null) {
                    mAddNodeInfosForViewId = new AddNodeInfosForViewId();
                }
                mAddNodeInfosForViewId.init(resolvedViewId, infos);
                root.findViewByPredicate(mAddNodeInfosForViewId);
                mAddNodeInfosForViewId.reset();
            }
        } finally {
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
            updateInfosForViewportAndReturnFindNodeResult(
                    infos, callback, interactionId, spec, interactiveRegion);
        }
    }

    public void findAccessibilityNodeInfosByTextClientThread(long accessibilityNodeId,
            String text, Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_TEXT;
        message.arg1 = flags;

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = text;
        args.arg2 = callback;
        args.arg3 = spec;
        args.argi1 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi2 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi3 = interactionId;
        args.arg4 = interactiveRegion;
        message.obj = args;

        scheduleMessage(message, interrogatingPid, interrogatingTid, CONSIDER_REQUEST_PREPARERS);
    }

    private void findAccessibilityNodeInfosByTextUiThread(Message message) {
        final int flags = message.arg1;

        SomeArgs args = (SomeArgs) message.obj;
        final String text = (String) args.arg1;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg2;
        final MagnificationSpec spec = (MagnificationSpec) args.arg3;
        final int accessibilityViewId = args.argi1;
        final int virtualDescendantId = args.argi2;
        final int interactionId = args.argi3;
        final Region interactiveRegion = (Region) args.arg4;
        args.recycle();

        List<AccessibilityNodeInfo> infos = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            final View root = findViewByAccessibilityId(accessibilityViewId);
            if (root != null && isShown(root)) {
                AccessibilityNodeProvider provider = root.getAccessibilityNodeProvider();
                if (provider != null) {
                    infos = provider.findAccessibilityNodeInfosByText(text,
                            virtualDescendantId);
                } else if (virtualDescendantId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                    ArrayList<View> foundViews = mTempArrayList;
                    foundViews.clear();
                    root.findViewsWithText(foundViews, text, View.FIND_VIEWS_WITH_TEXT
                            | View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION
                            | View.FIND_VIEWS_WITH_ACCESSIBILITY_NODE_PROVIDERS);
                    if (!foundViews.isEmpty()) {
                        infos = mTempAccessibilityNodeInfoList;
                        infos.clear();
                        final int viewCount = foundViews.size();
                        for (int i = 0; i < viewCount; i++) {
                            View foundView = foundViews.get(i);
                            if (isShown(foundView)) {
                                provider = foundView.getAccessibilityNodeProvider();
                                if (provider != null) {
                                    List<AccessibilityNodeInfo> infosFromProvider =
                                        provider.findAccessibilityNodeInfosByText(text,
                                                AccessibilityNodeProvider.HOST_VIEW_ID);
                                    if (infosFromProvider != null) {
                                        infos.addAll(infosFromProvider);
                                    }
                                } else  {
                                    infos.add(foundView.createAccessibilityNodeInfo());
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
            updateInfosForViewportAndReturnFindNodeResult(
                    infos, callback, interactionId, spec, interactiveRegion);
        }
    }

    public void findFocusClientThread(long accessibilityNodeId, int focusType,
            Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_FOCUS;
        message.arg1 = flags;
        message.arg2 = focusType;

        SomeArgs args = SomeArgs.obtain();
        args.argi1 = interactionId;
        args.argi2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi3 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.arg1 = callback;
        args.arg2 = spec;
        args.arg3 = interactiveRegion;

        message.obj = args;

        scheduleMessage(message, interrogatingPid, interrogatingTid, CONSIDER_REQUEST_PREPARERS);
    }

    private void findFocusUiThread(Message message) {
        final int flags = message.arg1;
        final int focusType = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int interactionId = args.argi1;
        final int accessibilityViewId = args.argi2;
        final int virtualDescendantId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;
        final Region interactiveRegion = (Region) args.arg3;
        args.recycle();

        AccessibilityNodeInfo focused = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            final View root = findViewByAccessibilityId(accessibilityViewId);
            if (root != null && isShown(root)) {
                switch (focusType) {
                    case AccessibilityNodeInfo.FOCUS_ACCESSIBILITY: {
                        View host = mViewRootImpl.mAccessibilityFocusedHost;
                        // If there is no accessibility focus host or it is not a descendant
                        // of the root from which to start the search, then the search failed.
                        if (host == null || !ViewRootImpl.isViewDescendantOf(host, root)) {
                            break;
                        }
                        // The focused view not shown, we failed.
                        if (!isShown(host)) {
                            break;
                        }
                        // If the host has a provider ask this provider to search for the
                        // focus instead fetching all provider nodes to do the search here.
                        AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
                        if (provider != null) {
                            if (mViewRootImpl.mAccessibilityFocusedVirtualView != null) {
                                focused = AccessibilityNodeInfo.obtain(
                                        mViewRootImpl.mAccessibilityFocusedVirtualView);
                            }
                        } else if (virtualDescendantId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                            focused = host.createAccessibilityNodeInfo();
                        }
                    } break;
                    case AccessibilityNodeInfo.FOCUS_INPUT: {
                        View target = root.findFocus();
                        if (!isShown(target)) {
                            break;
                        }
                        AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
                        if (provider != null) {
                            focused = provider.findFocus(focusType);
                        }
                        if (focused == null) {
                            focused = target.createAccessibilityNodeInfo();
                        }
                    } break;
                    default:
                        throw new IllegalArgumentException("Unknown focus type: " + focusType);
                }
            }
        } finally {
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
            updateInfoForViewportAndReturnFindNodeResult(
                    focused, callback, interactionId, spec, interactiveRegion);
        }
    }

    public void focusSearchClientThread(long accessibilityNodeId, int direction,
            Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FOCUS_SEARCH;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = SomeArgs.obtain();
        args.argi2 = direction;
        args.argi3 = interactionId;
        args.arg1 = callback;
        args.arg2 = spec;
        args.arg3 = interactiveRegion;

        message.obj = args;

        scheduleMessage(message, interrogatingPid, interrogatingTid, CONSIDER_REQUEST_PREPARERS);
    }

    private void focusSearchUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int direction = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;
        final Region interactiveRegion = (Region) args.arg3;

        args.recycle();

        AccessibilityNodeInfo next = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            final View root = findViewByAccessibilityId(accessibilityViewId);
            if (root != null && isShown(root)) {
                View nextView = root.focusSearch(direction);
                if (nextView != null) {
                    next = nextView.createAccessibilityNodeInfo();
                }
            }
        } finally {
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
            updateInfoForViewportAndReturnFindNodeResult(
                    next, callback, interactionId, spec, interactiveRegion);
        }
    }

    public void performAccessibilityActionClientThread(long accessibilityNodeId, int action,
            Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_PERFORM_ACCESSIBILITY_ACTION;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = SomeArgs.obtain();
        args.argi1 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi2 = action;
        args.argi3 = interactionId;
        args.arg1 = callback;
        args.arg2 = arguments;

        message.obj = args;

        scheduleMessage(message, interrogatingPid, interrogatingTid, CONSIDER_REQUEST_PREPARERS);
    }

    private void performAccessibilityActionUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int virtualDescendantId = args.argi1;
        final int action = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        Bundle arguments = (Bundle) args.arg2;

        args.recycle();

        boolean succeeded = false;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null ||
                    mViewRootImpl.mStopped || mViewRootImpl.mPausedForTransition) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            final View target = findViewByAccessibilityId(accessibilityViewId);
            if (target != null && isShown(target)) {
                mA11yManager.notifyPerformingAction(action);
                if (action == R.id.accessibilityActionClickOnClickableSpan) {
                    // Handle this hidden action separately
                    succeeded = handleClickableSpanActionUiThread(
                            target, virtualDescendantId, arguments);
                } else {
                    AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
                    if (provider != null) {
                        succeeded = provider.performAction(virtualDescendantId, action,
                                arguments);
                    } else if (virtualDescendantId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                        succeeded = target.performAccessibilityAction(action, arguments);
                    }
                }
                mA11yManager.notifyPerformingAction(0);
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
                callback.setPerformAccessibilityActionResult(succeeded, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    /**
     * Finds the accessibility focused node in the root, and clears the accessibility focus.
     */
    public void clearAccessibilityFocusClientThread() {
        final Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_CLEAR_ACCESSIBILITY_FOCUS;

        // Don't care about pid and tid because there's no interrogating client for this message.
        scheduleMessage(message, 0, 0, CONSIDER_REQUEST_PREPARERS);
    }

    private void clearAccessibilityFocusUiThread() {
        if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
            return;
        }
        try {
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags =
                    AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            final View root = mViewRootImpl.mView;
            if (root != null && isShown(root)) {
                final View host = mViewRootImpl.mAccessibilityFocusedHost;
                // If there is no accessibility focus host or it is not a descendant
                // of the root from which to start the search, then the search failed.
                if (host == null || !ViewRootImpl.isViewDescendantOf(host, root)) {
                    return;
                }
                final AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
                final AccessibilityNodeInfo focusNode =
                        mViewRootImpl.mAccessibilityFocusedVirtualView;
                if (provider != null && focusNode != null) {
                    final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                            focusNode.getSourceNodeId());
                    provider.performAction(virtualNodeId,
                            AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS.getId(),
                            null);
                } else {
                    host.performAccessibilityAction(
                            AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS.getId(),
                            null);
                }
            }
        } finally {
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
        }
    }

    /**
     * Notify outside touch event to the target window.
     */
    public void notifyOutsideTouchClientThread() {
        final Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_NOTIFY_OUTSIDE_TOUCH;

        // Don't care about pid and tid because there's no interrogating client for this message.
        scheduleMessage(message, 0, 0, CONSIDER_REQUEST_PREPARERS);
    }

    private void notifyOutsideTouchUiThread() {
        if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null
                || mViewRootImpl.mStopped || mViewRootImpl.mPausedForTransition) {
            return;
        }
        final View root = mViewRootImpl.mView;
        if (root != null && isShown(root)) {
            // trigger ACTION_OUTSIDE to notify windows
            final long now = SystemClock.uptimeMillis();
            final MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE,
                    0, 0, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            mViewRootImpl.dispatchInputEvent(event);
        }
    }

    private View findViewByAccessibilityId(int accessibilityId) {
        if (accessibilityId == AccessibilityNodeInfo.ROOT_ITEM_ID) {
            return mViewRootImpl.mView;
        } else {
            return AccessibilityNodeIdManager.getInstance().findView(accessibilityId);
        }
    }

    private void adjustIsVisibleToUserIfNeeded(AccessibilityNodeInfo info,
            Region interactiveRegion) {
        if (interactiveRegion == null || info == null) {
            return;
        }
        Rect boundsInScreen = mTempRect;
        info.getBoundsInScreen(boundsInScreen);
        if (interactiveRegion.quickReject(boundsInScreen) && !shouldBypassAdjustIsVisible()) {
            info.setVisibleToUser(false);
        }
    }

    private boolean shouldBypassAdjustIsVisible() {
        final int windowType = mViewRootImpl.mOrigWindowType;
        if (windowType == TYPE_INPUT_METHOD) {
            return true;
        }
        return false;
    }

    private void adjustBoundsInScreenIfNeeded(AccessibilityNodeInfo info) {
        if (info == null || shouldBypassAdjustBoundsInScreen()) {
            return;
        }
        final Rect boundsInScreen = mTempRect;
        info.getBoundsInScreen(boundsInScreen);
        boundsInScreen.offset(mViewRootImpl.mAttachInfo.mLocationInParentDisplay.x,
                mViewRootImpl.mAttachInfo.mLocationInParentDisplay.y);
        info.setBoundsInScreen(boundsInScreen);
    }

    private boolean shouldBypassAdjustBoundsInScreen() {
        return mViewRootImpl.mAttachInfo.mLocationInParentDisplay.equals(0, 0);
    }

    private void applyScreenMatrixIfNeeded(List<AccessibilityNodeInfo> infos) {
        if (infos == null || shouldBypassApplyScreenMatrix()) {
            return;
        }
        final int infoCount = infos.size();
        for (int i = 0; i < infoCount; i++) {
            final AccessibilityNodeInfo info = infos.get(i);
            applyScreenMatrixIfNeeded(info);
        }
    }

    private void applyScreenMatrixIfNeeded(AccessibilityNodeInfo info) {
        if (info == null || shouldBypassApplyScreenMatrix()) {
            return;
        }
        final Rect boundsInScreen = mTempRect;
        final RectF transformedBounds = mTempRectF;
        final Matrix screenMatrix = mViewRootImpl.mAttachInfo.mScreenMatrixInEmbeddedHierarchy;

        info.getBoundsInScreen(boundsInScreen);
        transformedBounds.set(boundsInScreen);
        screenMatrix.mapRect(transformedBounds);
        boundsInScreen.set((int) transformedBounds.left, (int) transformedBounds.top,
                (int) transformedBounds.right, (int) transformedBounds.bottom);
        info.setBoundsInScreen(boundsInScreen);
    }

    private boolean shouldBypassApplyScreenMatrix() {
        final Matrix screenMatrix = mViewRootImpl.mAttachInfo.mScreenMatrixInEmbeddedHierarchy;
        return screenMatrix == null || screenMatrix.isIdentity();
    }

    private void associateLeashedParentIfNeeded(AccessibilityNodeInfo info) {
        if (info == null || shouldBypassAssociateLeashedParent()) {
            return;
        }
        // The node id of root node in embedded maybe not be ROOT_NODE_ID so we compare the id
        // with root view.
        if (mViewRootImpl.mView.getAccessibilityViewId()
                != AccessibilityNodeInfo.getAccessibilityViewId(info.getSourceNodeId())) {
            return;
        }
        info.setLeashedParent(mViewRootImpl.mAttachInfo.mLeashedParentToken,
                mViewRootImpl.mAttachInfo.mLeashedParentAccessibilityViewId);
    }

    private boolean shouldBypassAssociateLeashedParent() {
        return (mViewRootImpl.mAttachInfo.mLeashedParentToken == null
                && mViewRootImpl.mAttachInfo.mLeashedParentAccessibilityViewId == View.NO_ID);
    }

    private void applyAppScaleAndMagnificationSpecIfNeeded(AccessibilityNodeInfo info,
            MagnificationSpec spec) {
        if (info == null) {
            return;
        }

        final float applicationScale = mViewRootImpl.mAttachInfo.mApplicationScale;
        if (!shouldApplyAppScaleAndMagnificationSpec(applicationScale, spec)) {
            return;
        }

        Rect boundsInParent = mTempRect;
        Rect boundsInScreen = mTempRect1;

        info.getBoundsInParent(boundsInParent);
        info.getBoundsInScreen(boundsInScreen);
        if (applicationScale != 1.0f) {
            boundsInParent.scale(applicationScale);
            boundsInScreen.scale(applicationScale);
        }
        if (spec != null) {
            boundsInParent.scale(spec.scale);
            // boundsInParent must not be offset.
            boundsInScreen.scale(spec.scale);
            boundsInScreen.offset((int) spec.offsetX, (int) spec.offsetY);
        }
        info.setBoundsInParent(boundsInParent);
        info.setBoundsInScreen(boundsInScreen);

        // Scale text locations if they are present
        if (info.hasExtras()) {
            Bundle extras = info.getExtras();
            Parcelable[] textLocations =
                    extras.getParcelableArray(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
            if (textLocations != null) {
                for (int i = 0; i < textLocations.length; i++) {
                    // Unchecked cast - an app that puts other objects in this bundle with this
                    // key will crash.
                    RectF textLocation = ((RectF) textLocations[i]);
                    textLocation.scale(applicationScale);
                    if (spec != null) {
                        textLocation.scale(spec.scale);
                        textLocation.offset(spec.offsetX, spec.offsetY);
                    }
                }
            }
        }
    }

    private boolean shouldApplyAppScaleAndMagnificationSpec(float appScale,
            MagnificationSpec spec) {
        return (appScale != 1.0f || (spec != null && !spec.isNop()));
    }

    private void updateInfosForViewPort(List<AccessibilityNodeInfo> infos, MagnificationSpec spec,
                                        Region interactiveRegion) {
        for (int i = 0; i < infos.size(); i++) {
            updateInfoForViewPort(infos.get(i), spec, interactiveRegion);
        }
    }

    private void updateInfoForViewPort(AccessibilityNodeInfo info, MagnificationSpec spec,
                                       Region interactiveRegion) {
        associateLeashedParentIfNeeded(info);
        applyScreenMatrixIfNeeded(info);
        adjustBoundsInScreenIfNeeded(info);
        // To avoid applyAppScaleAndMagnificationSpecIfNeeded changing the bounds of node,
        // then impact the visibility result, we need to adjust visibility before apply scale.
        adjustIsVisibleToUserIfNeeded(info, interactiveRegion);
        applyAppScaleAndMagnificationSpecIfNeeded(info, spec);
    }

    private void updateInfosForViewportAndReturnFindNodeResult(List<AccessibilityNodeInfo> infos,
            IAccessibilityInteractionConnectionCallback callback, int interactionId,
            MagnificationSpec spec, Region interactiveRegion) {
        if (infos != null) {
            updateInfosForViewPort(infos, spec, interactiveRegion);
        }
        returnFindNodesResult(infos, callback, interactionId);
    }

    private void returnFindNodeResult(AccessibilityNodeInfo info,
                                      IAccessibilityInteractionConnectionCallback callback,
                                      int interactionId) {
        try {
            callback.setFindAccessibilityNodeInfoResult(info, interactionId);
        } catch (RemoteException re) {
            /* ignore - the other side will time out */
        }
    }

    private void returnFindNodeResult(SatisfiedFindAccessibilityNodeByAccessibilityIdRequest
            satisfiedRequest) {
        try {
            final AccessibilityNodeInfo info = satisfiedRequest.mSatisfiedRequestNode;
            final IAccessibilityInteractionConnectionCallback callback =
                    satisfiedRequest.mSatisfiedRequestCallback;
            final int interactionId = satisfiedRequest.mSatisfiedRequestInteractionId;
            callback.setFindAccessibilityNodeInfoResult(info, interactionId);
        } catch (RemoteException re) {
            /* ignore - the other side will time out */
        }
    }

    private void returnFindNodesResult(List<AccessibilityNodeInfo> infos,
            IAccessibilityInteractionConnectionCallback callback, int interactionId) {
        try {
            callback.setFindAccessibilityNodeInfosResult(infos, interactionId);
            if (infos != null) {
                infos.clear();
            }
        } catch (RemoteException re) {
            /* ignore - the other side will time out */
        }
    }

    private SatisfiedFindAccessibilityNodeByAccessibilityIdRequest getSatisfiedRequestInPrefetch(
            AccessibilityNodeInfo requestedNode, List<AccessibilityNodeInfo> infos, int flags) {
        SatisfiedFindAccessibilityNodeByAccessibilityIdRequest satisfiedRequest = null;
        synchronized (mLock) {
            for (int i = 0; i < mPendingFindNodeByIdMessages.size(); i++) {
                final Message pendingMessage = mPendingFindNodeByIdMessages.get(i);
                final int pendingFlags = pendingMessage.arg1;
                if ((pendingFlags & FLAGS_AFFECTING_REPORTED_DATA)
                        != (flags & FLAGS_AFFECTING_REPORTED_DATA)) {
                    continue;
                }
                SomeArgs args = (SomeArgs) pendingMessage.obj;
                final int accessibilityViewId = args.argi1;
                final int virtualDescendantId = args.argi2;

                final AccessibilityNodeInfo satisfiedRequestNode = nodeWithIdFromList(requestedNode,
                        infos, AccessibilityNodeInfo.makeNodeId(
                                accessibilityViewId, virtualDescendantId));

                if (satisfiedRequestNode != null) {
                    mHandler.removeMessages(
                            PrivateHandler.MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID,
                            pendingMessage.obj);
                    final IAccessibilityInteractionConnectionCallback satisfiedRequestCallback =
                            (IAccessibilityInteractionConnectionCallback) args.arg1;
                    final int satisfiedRequestInteractionId = args.argi3;
                    satisfiedRequest = new SatisfiedFindAccessibilityNodeByAccessibilityIdRequest(
                                    satisfiedRequestNode, satisfiedRequestCallback,
                                    satisfiedRequestInteractionId);
                    args.recycle();
                    break;
                }
            }
            mPendingFindNodeByIdMessages.clear();
            return satisfiedRequest;
        }
    }

    private AccessibilityNodeInfo nodeWithIdFromList(AccessibilityNodeInfo requestedNode,
            List<AccessibilityNodeInfo> infos, long nodeId) {
        if (requestedNode != null && requestedNode.getSourceNodeId() == nodeId) {
            return requestedNode;
        }
        for (int j = 0; j < infos.size(); j++) {
            AccessibilityNodeInfo info = infos.get(j);
            if (info.getSourceNodeId() == nodeId) {
                return info;
            }
        }
        return null;
    }

    private void returnPrefetchResult(int interactionId, List<AccessibilityNodeInfo> infos,
                                      IAccessibilityInteractionConnectionCallback callback) {
        if (infos.size() > 0) {
            try {
                callback.setPrefetchAccessibilityNodeInfoResult(infos, interactionId);
            } catch (RemoteException re) {
                /* ignore - other side isn't too bothered if this doesn't arrive */
            }
        }
    }

    private void updateInfoForViewportAndReturnFindNodeResult(AccessibilityNodeInfo info,
            IAccessibilityInteractionConnectionCallback callback, int interactionId,
            MagnificationSpec spec, Region interactiveRegion) {
        updateInfoForViewPort(info, spec, interactiveRegion);
        returnFindNodeResult(info, callback, interactionId);
    }

    private boolean handleClickableSpanActionUiThread(
            View view, int virtualDescendantId, Bundle arguments) {
        Parcelable span = arguments.getParcelable(ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN);
        if (!(span instanceof AccessibilityClickableSpan)) {
            return false;
        }

        // Find the original ClickableSpan if it's still on the screen
        AccessibilityNodeInfo infoWithSpan = null;
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            infoWithSpan = provider.createAccessibilityNodeInfo(virtualDescendantId);
        } else if (virtualDescendantId == AccessibilityNodeProvider.HOST_VIEW_ID) {
            infoWithSpan = view.createAccessibilityNodeInfo();
        }
        if (infoWithSpan == null) {
            return false;
        }

        // Click on the corresponding span
        ClickableSpan clickableSpan = ((AccessibilityClickableSpan) span).findClickableSpan(
                infoWithSpan.getOriginalText());
        if (clickableSpan != null) {
            clickableSpan.onClick(view);
            return true;
        }
        return false;
    }

    /**
     * This class encapsulates a prefetching strategy for the accessibility APIs for
     * querying window content. It is responsible to prefetch a batch of
     * AccessibilityNodeInfos in addition to the one for a requested node.
     */
    private class AccessibilityNodePrefetcher {

        private static final int MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE = 50;

        private final ArrayList<View> mTempViewList = new ArrayList<View>();

        public void prefetchAccessibilityNodeInfos(View view, AccessibilityNodeInfo root,
                int virtualViewId, int fetchFlags, List<AccessibilityNodeInfo> outInfos) {
            if (root == null) {
                return;
            }
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider == null) {
                if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS) != 0) {
                    prefetchPredecessorsOfRealNode(view, outInfos);
                }
                if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS) != 0) {
                    prefetchSiblingsOfRealNode(view, outInfos);
                }
                if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS) != 0) {
                    prefetchDescendantsOfRealNode(view, outInfos);
                }
            } else {
                if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS) != 0) {
                    prefetchPredecessorsOfVirtualNode(root, view, provider, outInfos);
                }
                if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS) != 0) {
                    prefetchSiblingsOfVirtualNode(root, view, provider, outInfos);
                }
                if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS) != 0) {
                    prefetchDescendantsOfVirtualNode(root, provider, outInfos);
                }
            }
            if (ENFORCE_NODE_TREE_CONSISTENT) {
                enforceNodeTreeConsistent(root, outInfos);
            }
        }

        private boolean shouldStopPrefetching(List prefetchededInfos) {
            return mHandler.hasUserInteractiveMessagesWaiting()
                    || prefetchededInfos.size() >= MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE;
        }

        private void enforceNodeTreeConsistent(
                AccessibilityNodeInfo root, List<AccessibilityNodeInfo> nodes) {
            LongSparseArray<AccessibilityNodeInfo> nodeMap =
                    new LongSparseArray<AccessibilityNodeInfo>();
            final int nodeCount = nodes.size();
            for (int i = 0; i < nodeCount; i++) {
                AccessibilityNodeInfo node = nodes.get(i);
                nodeMap.put(node.getSourceNodeId(), node);
            }

            // If the nodes are a tree it does not matter from
            // which node we start to search for the root.
            AccessibilityNodeInfo parent = root;
            while (parent != null) {
                root = parent;
                parent = nodeMap.get(parent.getParentNodeId());
            }

            // Traverse the tree and do some checks.
            AccessibilityNodeInfo accessFocus = null;
            AccessibilityNodeInfo inputFocus = null;
            HashSet<AccessibilityNodeInfo> seen = new HashSet<AccessibilityNodeInfo>();
            Queue<AccessibilityNodeInfo> fringe = new LinkedList<AccessibilityNodeInfo>();
            fringe.add(root);

            while (!fringe.isEmpty()) {
                AccessibilityNodeInfo current = fringe.poll();

                // Check for duplicates
                if (!seen.add(current)) {
                    throw new IllegalStateException("Duplicate node: "
                            + current + " in window:"
                            + mViewRootImpl.mAttachInfo.mAccessibilityWindowId);
                }

                // Check for one accessibility focus.
                if (current.isAccessibilityFocused()) {
                    if (accessFocus != null) {
                        throw new IllegalStateException("Duplicate accessibility focus:"
                                + current
                                + " in window:" + mViewRootImpl.mAttachInfo.mAccessibilityWindowId);
                    } else {
                        accessFocus = current;
                    }
                }

                // Check for one input focus.
                if (current.isFocused()) {
                    if (inputFocus != null) {
                        throw new IllegalStateException("Duplicate input focus: "
                            + current + " in window:"
                            + mViewRootImpl.mAttachInfo.mAccessibilityWindowId);
                    } else {
                        inputFocus = current;
                    }
                }

                final int childCount = current.getChildCount();
                for (int j = 0; j < childCount; j++) {
                    final long childId = current.getChildId(j);
                    final AccessibilityNodeInfo child = nodeMap.get(childId);
                    if (child != null) {
                        fringe.add(child);
                    }
                }
            }

            // Check for disconnected nodes.
            for (int j = nodeMap.size() - 1; j >= 0; j--) {
                AccessibilityNodeInfo info = nodeMap.valueAt(j);
                if (!seen.contains(info)) {
                    throw new IllegalStateException("Disconnected node: " + info);
                }
            }
        }

        private void prefetchPredecessorsOfRealNode(View view,
                List<AccessibilityNodeInfo> outInfos) {
            if (shouldStopPrefetching(outInfos)) {
                return;
            }
            ViewParent parent = view.getParentForAccessibility();
            while (parent instanceof View && !shouldStopPrefetching(outInfos)) {
                View parentView = (View) parent;
                AccessibilityNodeInfo info = parentView.createAccessibilityNodeInfo();
                if (info != null) {
                    outInfos.add(info);
                }
                parent = parent.getParentForAccessibility();
            }
        }

        private void prefetchSiblingsOfRealNode(View current,
                List<AccessibilityNodeInfo> outInfos) {
            if (shouldStopPrefetching(outInfos)) {
                return;
            }
            ViewParent parent = current.getParentForAccessibility();
            if (parent instanceof ViewGroup) {
                ViewGroup parentGroup = (ViewGroup) parent;
                ArrayList<View> children = mTempViewList;
                children.clear();
                try {
                    parentGroup.addChildrenForAccessibility(children);
                    final int childCount = children.size();
                    for (int i = 0; i < childCount; i++) {
                        if (shouldStopPrefetching(outInfos)) {
                            return;
                        }
                        View child = children.get(i);
                        if (child.getAccessibilityViewId() != current.getAccessibilityViewId()
                                && isShown(child)) {
                            AccessibilityNodeInfo info = null;
                            AccessibilityNodeProvider provider =
                                child.getAccessibilityNodeProvider();
                            if (provider == null) {
                                info = child.createAccessibilityNodeInfo();
                            } else {
                                info = provider.createAccessibilityNodeInfo(
                                        AccessibilityNodeProvider.HOST_VIEW_ID);
                            }
                            if (info != null) {
                                outInfos.add(info);
                            }
                        }
                    }
                } finally {
                    children.clear();
                }
            }
        }

        private void prefetchDescendantsOfRealNode(View root,
                List<AccessibilityNodeInfo> outInfos) {
            if (shouldStopPrefetching(outInfos) || !(root instanceof ViewGroup)) {
                return;
            }
            HashMap<View, AccessibilityNodeInfo> addedChildren =
                new HashMap<View, AccessibilityNodeInfo>();
            ArrayList<View> children = mTempViewList;
            children.clear();
            try {
                root.addChildrenForAccessibility(children);
                final int childCount = children.size();
                for (int i = 0; i < childCount; i++) {
                    if (shouldStopPrefetching(outInfos)) {
                        return;
                    }
                    View child = children.get(i);
                    if (isShown(child)) {
                        AccessibilityNodeProvider provider = child.getAccessibilityNodeProvider();
                        if (provider == null) {
                            AccessibilityNodeInfo info = child.createAccessibilityNodeInfo();
                            if (info != null) {
                                outInfos.add(info);
                                addedChildren.put(child, null);
                            }
                        } else {
                            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(
                                   AccessibilityNodeProvider.HOST_VIEW_ID);
                            if (info != null) {
                                outInfos.add(info);
                                addedChildren.put(child, info);
                            }
                        }
                    }
                }
            } finally {
                children.clear();
            }
            if (!shouldStopPrefetching(outInfos)) {
                for (Map.Entry<View, AccessibilityNodeInfo> entry : addedChildren.entrySet()) {
                    View addedChild = entry.getKey();
                    AccessibilityNodeInfo virtualRoot = entry.getValue();
                    if (virtualRoot == null) {
                        prefetchDescendantsOfRealNode(addedChild, outInfos);
                    } else {
                        AccessibilityNodeProvider provider =
                            addedChild.getAccessibilityNodeProvider();
                        prefetchDescendantsOfVirtualNode(virtualRoot, provider, outInfos);
                    }
                }
            }
        }

        private void prefetchPredecessorsOfVirtualNode(AccessibilityNodeInfo root,
                View providerHost, AccessibilityNodeProvider provider,
                List<AccessibilityNodeInfo> outInfos) {
            final int initialResultSize = outInfos.size();
            long parentNodeId = root.getParentNodeId();
            int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(parentNodeId);
            while (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED_ITEM_ID) {
                if (shouldStopPrefetching(outInfos)) {
                    return;
                }
                final int virtualDescendantId =
                    AccessibilityNodeInfo.getVirtualDescendantId(parentNodeId);
                if (virtualDescendantId != AccessibilityNodeProvider.HOST_VIEW_ID
                        || accessibilityViewId == providerHost.getAccessibilityViewId()) {
                    final AccessibilityNodeInfo parent;
                    parent = provider.createAccessibilityNodeInfo(virtualDescendantId);
                    if (parent == null) {
                        // Going up the parent relation we found a null predecessor,
                        // so remove these disconnected nodes form the result.
                        final int currentResultSize = outInfos.size();
                        for (int i = currentResultSize - 1; i >= initialResultSize; i--) {
                            outInfos.remove(i);
                        }
                        // Couldn't obtain the parent, which means we have a
                        // disconnected sub-tree. Abort prefetch immediately.
                        return;
                    }
                    outInfos.add(parent);
                    parentNodeId = parent.getParentNodeId();
                    accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                            parentNodeId);
                } else {
                    prefetchPredecessorsOfRealNode(providerHost, outInfos);
                    return;
                }
            }
        }

        private void prefetchSiblingsOfVirtualNode(AccessibilityNodeInfo current, View providerHost,
                AccessibilityNodeProvider provider, List<AccessibilityNodeInfo> outInfos) {
            final long parentNodeId = current.getParentNodeId();
            final int parentAccessibilityViewId =
                AccessibilityNodeInfo.getAccessibilityViewId(parentNodeId);
            final int parentVirtualDescendantId =
                AccessibilityNodeInfo.getVirtualDescendantId(parentNodeId);
            if (parentVirtualDescendantId != AccessibilityNodeProvider.HOST_VIEW_ID
                    || parentAccessibilityViewId == providerHost.getAccessibilityViewId()) {
                final AccessibilityNodeInfo parent =
                        provider.createAccessibilityNodeInfo(parentVirtualDescendantId);
                if (parent != null) {
                    final int childCount = parent.getChildCount();
                    for (int i = 0; i < childCount; i++) {
                        if (shouldStopPrefetching(outInfos)) {
                            return;
                        }
                        final long childNodeId = parent.getChildId(i);
                        if (childNodeId != current.getSourceNodeId()) {
                            final int childVirtualDescendantId =
                                AccessibilityNodeInfo.getVirtualDescendantId(childNodeId);
                            AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(
                                    childVirtualDescendantId);
                            if (child != null) {
                                outInfos.add(child);
                            }
                        }
                    }
                }
            } else {
                prefetchSiblingsOfRealNode(providerHost, outInfos);
            }
        }

        private void prefetchDescendantsOfVirtualNode(AccessibilityNodeInfo root,
                AccessibilityNodeProvider provider, List<AccessibilityNodeInfo> outInfos) {
            final int initialOutInfosSize = outInfos.size();
            final int childCount = root.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (shouldStopPrefetching(outInfos)) {
                    return;
                }
                final long childNodeId = root.getChildId(i);
                AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(
                        AccessibilityNodeInfo.getVirtualDescendantId(childNodeId));
                if (child != null) {
                    outInfos.add(child);
                }
            }
            if (!shouldStopPrefetching(outInfos)) {
                final int addedChildCount = outInfos.size() - initialOutInfosSize;
                for (int i = 0; i < addedChildCount; i++) {
                    AccessibilityNodeInfo child = outInfos.get(initialOutInfosSize + i);
                    prefetchDescendantsOfVirtualNode(child, provider, outInfos);
                }
            }
        }
    }

    private class PrivateHandler extends Handler {
        private static final int MSG_PERFORM_ACCESSIBILITY_ACTION = 1;
        private static final int MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID = 2;
        private static final int MSG_FIND_ACCESSIBILITY_NODE_INFOS_BY_VIEW_ID = 3;
        private static final int MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_TEXT = 4;
        private static final int MSG_FIND_FOCUS = 5;
        private static final int MSG_FOCUS_SEARCH = 6;
        private static final int MSG_PREPARE_FOR_EXTRA_DATA_REQUEST = 7;
        private static final int MSG_APP_PREPARATION_FINISHED = 8;
        private static final int MSG_APP_PREPARATION_TIMEOUT = 9;

        // Uses FIRST_NO_ACCESSIBILITY_CALLBACK_MSG for messages that don't need to call back
        // results to interrogating client.
        private static final int FIRST_NO_ACCESSIBILITY_CALLBACK_MSG = 100;
        private static final int MSG_CLEAR_ACCESSIBILITY_FOCUS =
                FIRST_NO_ACCESSIBILITY_CALLBACK_MSG + 1;
        private static final int MSG_NOTIFY_OUTSIDE_TOUCH =
                FIRST_NO_ACCESSIBILITY_CALLBACK_MSG + 2;

        public PrivateHandler(Looper looper) {
            super(looper);
        }

        @Override
        public String getMessageName(Message message) {
            final int type = message.what;
            switch (type) {
                case MSG_PERFORM_ACCESSIBILITY_ACTION:
                    return "MSG_PERFORM_ACCESSIBILITY_ACTION";
                case MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID:
                    return "MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID";
                case MSG_FIND_ACCESSIBILITY_NODE_INFOS_BY_VIEW_ID:
                    return "MSG_FIND_ACCESSIBILITY_NODE_INFOS_BY_VIEW_ID";
                case MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_TEXT:
                    return "MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_TEXT";
                case MSG_FIND_FOCUS:
                    return "MSG_FIND_FOCUS";
                case MSG_FOCUS_SEARCH:
                    return "MSG_FOCUS_SEARCH";
                case MSG_PREPARE_FOR_EXTRA_DATA_REQUEST:
                    return "MSG_PREPARE_FOR_EXTRA_DATA_REQUEST";
                case MSG_APP_PREPARATION_FINISHED:
                    return "MSG_APP_PREPARATION_FINISHED";
                case MSG_APP_PREPARATION_TIMEOUT:
                    return "MSG_APP_PREPARATION_TIMEOUT";
                case MSG_CLEAR_ACCESSIBILITY_FOCUS:
                    return "MSG_CLEAR_ACCESSIBILITY_FOCUS";
                case MSG_NOTIFY_OUTSIDE_TOUCH:
                    return "MSG_NOTIFY_OUTSIDE_TOUCH";
                default:
                    throw new IllegalArgumentException("Unknown message type: " + type);
            }
        }

        @Override
        public void handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_ACCESSIBILITY_ID: {
                    findAccessibilityNodeInfoByAccessibilityIdUiThread(message);
                } break;
                case MSG_PERFORM_ACCESSIBILITY_ACTION: {
                    performAccessibilityActionUiThread(message);
                } break;
                case MSG_FIND_ACCESSIBILITY_NODE_INFOS_BY_VIEW_ID: {
                    findAccessibilityNodeInfosByViewIdUiThread(message);
                } break;
                case MSG_FIND_ACCESSIBILITY_NODE_INFO_BY_TEXT: {
                    findAccessibilityNodeInfosByTextUiThread(message);
                } break;
                case MSG_FIND_FOCUS: {
                    findFocusUiThread(message);
                } break;
                case MSG_FOCUS_SEARCH: {
                    focusSearchUiThread(message);
                } break;
                case MSG_PREPARE_FOR_EXTRA_DATA_REQUEST: {
                    prepareForExtraDataRequestUiThread(message);
                } break;
                case MSG_APP_PREPARATION_FINISHED: {
                    requestPreparerDoneUiThread(message);
                } break;
                case MSG_APP_PREPARATION_TIMEOUT: {
                    requestPreparerTimeoutUiThread();
                } break;
                case MSG_CLEAR_ACCESSIBILITY_FOCUS: {
                    clearAccessibilityFocusUiThread();
                } break;
                case MSG_NOTIFY_OUTSIDE_TOUCH: {
                    notifyOutsideTouchUiThread();
                } break;
                default:
                    throw new IllegalArgumentException("Unknown message type: " + type);
            }
        }

        boolean hasAccessibilityCallback(Message message) {
            return message.what < FIRST_NO_ACCESSIBILITY_CALLBACK_MSG ? true : false;
        }

        boolean hasUserInteractiveMessagesWaiting() {
            return hasMessagesOrCallbacks();
        }
    }

    private final class AddNodeInfosForViewId implements Predicate<View> {
        private int mViewId = View.NO_ID;
        private List<AccessibilityNodeInfo> mInfos;

        public void init(int viewId, List<AccessibilityNodeInfo> infos) {
            mViewId = viewId;
            mInfos = infos;
        }

        public void reset() {
            mViewId = View.NO_ID;
            mInfos = null;
        }

        @Override
        public boolean test(View view) {
            if (view.getId() == mViewId && isShown(view)) {
                mInfos.add(view.createAccessibilityNodeInfo());
            }
            return false;
        }
    }

    private static final class MessageHolder {
        final Message mMessage;
        final int mInterrogatingPid;
        final long mInterrogatingTid;

        MessageHolder(Message message, int interrogatingPid, long interrogatingTid) {
            mMessage = message;
            mInterrogatingPid = interrogatingPid;
            mInterrogatingTid = interrogatingTid;
        }
    }

    private static class SatisfiedFindAccessibilityNodeByAccessibilityIdRequest {
        final AccessibilityNodeInfo mSatisfiedRequestNode;
        final IAccessibilityInteractionConnectionCallback mSatisfiedRequestCallback;
        final int mSatisfiedRequestInteractionId;

        SatisfiedFindAccessibilityNodeByAccessibilityIdRequest(
                AccessibilityNodeInfo satisfiedRequestNode,
                IAccessibilityInteractionConnectionCallback satisfiedRequestCallback,
                int satisfiedRequestInteractionId) {
            mSatisfiedRequestNode = satisfiedRequestNode;
            mSatisfiedRequestCallback = satisfiedRequestCallback;
            mSatisfiedRequestInteractionId = satisfiedRequestInteractionId;
        }
    }
}
