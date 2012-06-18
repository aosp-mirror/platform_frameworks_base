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

import static android.view.accessibility.AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Pool;
import android.util.Poolable;
import android.util.PoolableManager;
import android.util.Pools;
import android.util.SparseLongArray;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for managing accessibility interactions initiated from the system
 * and targeting the view hierarchy. A *ClientThread method is to be
 * called from the interaction connection ViewAncestor gives the system to
 * talk to it and a corresponding *UiThread method that is executed on the
 * UI thread.
 */
final class AccessibilityInteractionController {
    private static final int POOL_SIZE = 5;

    private ArrayList<AccessibilityNodeInfo> mTempAccessibilityNodeInfoList =
        new ArrayList<AccessibilityNodeInfo>();

    private final Handler mHandler;

    private final ViewRootImpl mViewRootImpl;

    private final AccessibilityNodePrefetcher mPrefetcher;

    private final long mMyLooperThreadId;

    private final int mMyProcessId;

    private final ArrayList<View> mTempArrayList = new ArrayList<View>();

    public AccessibilityInteractionController(ViewRootImpl viewRootImpl) {
        Looper looper =  viewRootImpl.mHandler.getLooper();
        mMyLooperThreadId = looper.getThread().getId();
        mMyProcessId = Process.myPid();
        mHandler = new PrivateHandler(looper);
        mViewRootImpl = viewRootImpl;
        mPrefetcher = new AccessibilityNodePrefetcher();
    }

    // Reusable poolable arguments for interacting with the view hierarchy
    // to fit more arguments than Message and to avoid sharing objects between
    // two messages since several threads can send messages concurrently.
    private final Pool<SomeArgs> mPool = Pools.synchronizedPool(Pools.finitePool(
            new PoolableManager<SomeArgs>() {
                public SomeArgs newInstance() {
                    return new SomeArgs();
                }

                public void onAcquired(SomeArgs info) {
                    /* do nothing */
                }

                public void onReleased(SomeArgs info) {
                    info.clear();
                }
            }, POOL_SIZE)
    );

    private class SomeArgs implements Poolable<SomeArgs> {
        private SomeArgs mNext;
        private boolean mIsPooled;

        public Object arg1;
        public Object arg2;
        public int argi1;
        public int argi2;
        public int argi3;

        public SomeArgs getNextPoolable() {
            return mNext;
        }

        public boolean isPooled() {
            return mIsPooled;
        }

        public void setNextPoolable(SomeArgs args) {
            mNext = args;
        }

        public void setPooled(boolean isPooled) {
            mIsPooled = isPooled;
        }

        private void clear() {
            arg1 = null;
            arg2 = null;
            argi1 = 0;
            argi2 = 0;
            argi3 = 0;
        }
    }

    private boolean isShown(View view) {
        // The first two checks are made also made by isShown() which
        // however traverses the tree up to the parent to catch that.
        // Therefore, we do some fail fast check to minimize the up
        // tree traversal.
        return (view.mAttachInfo != null
                && view.mAttachInfo.mWindowVisibility == View.VISIBLE
                && view.isShown());
    }

    public void findAccessibilityNodeInfoByAccessibilityIdClientThread(
            long accessibilityNodeId, int windowLeft, int windowTop, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_ACCESSIBILITY_ID;
        message.arg1 = flags;

        SomeArgs args = mPool.acquire();
        args.argi1 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi2 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi3 = interactionId;
        args.arg1 = callback;

        SomeArgs moreArgs = mPool.acquire();
        moreArgs.argi1 = windowLeft;
        moreArgs.argi2 = windowTop;
        args.arg2 = moreArgs;

        message.obj = args;

        // If the interrogation is performed by the same thread as the main UI
        // thread in this process, set the message as a static reference so
        // after this call completes the same thread but in the interrogating
        // client can handle the message to generate the result.
        if (interrogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId) {
            AccessibilityInteractionClient.getInstanceForThread(
                    interrogatingTid).setSameThreadMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void findAccessibilityNodeInfoByAccessibilityIdUiThread(Message message) {
        final int flags = message.arg1;

        SomeArgs args = (SomeArgs) message.obj;
        final int accessibilityViewId = args.argi1;
        final int virtualDescendantId = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;

        SomeArgs moreArgs = (SomeArgs) args.arg2;
        mViewRootImpl.mAttachInfo.mActualWindowLeft = moreArgs.argi1;
        mViewRootImpl.mAttachInfo.mActualWindowTop = moreArgs.argi2;

        mPool.release(moreArgs);
        mPool.release(args);

        List<AccessibilityNodeInfo> infos = mTempAccessibilityNodeInfoList;
        infos.clear();
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mIncludeNotImportantViews =
                (flags & INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            View root = null;
            if (accessibilityViewId == AccessibilityNodeInfo.UNDEFINED) {
                root = mViewRootImpl.mView;
            } else {
                root = findViewByAccessibilityId(accessibilityViewId);
            }
            if (root != null && isShown(root)) {
                mPrefetcher.prefetchAccessibilityNodeInfos(root, virtualDescendantId, flags, infos);
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mIncludeNotImportantViews = false;
                callback.setFindAccessibilityNodeInfosResult(infos, interactionId);
                infos.clear();
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void findAccessibilityNodeInfoByViewIdClientThread(long accessibilityNodeId,
            int viewId, int windowLeft, int windowTop, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_VIEW_ID;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = mPool.acquire();
        args.argi1 = viewId;
        args.argi2 = interactionId;
        args.arg1 = callback;

        SomeArgs moreArgs = mPool.acquire();
        moreArgs.argi1 = windowLeft;
        moreArgs.argi2 = windowTop;
        args.arg2 = moreArgs;

        message.obj = args;

        // If the interrogation is performed by the same thread as the main UI
        // thread in this process, set the message as a static reference so
        // after this call completes the same thread but in the interrogating
        // client can handle the message to generate the result.
        if (interrogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId) {
            AccessibilityInteractionClient.getInstanceForThread(
                    interrogatingTid).setSameThreadMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void findAccessibilityNodeInfoByViewIdUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int viewId = args.argi1;
        final int interactionId = args.argi2;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;

        SomeArgs moreArgs = (SomeArgs) args.arg2;
        mViewRootImpl.mAttachInfo.mActualWindowLeft = moreArgs.argi1;
        mViewRootImpl.mAttachInfo.mActualWindowTop = moreArgs.argi2;

        mPool.release(moreArgs);
        mPool.release(args);

        AccessibilityNodeInfo info = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mIncludeNotImportantViews =
                (flags & INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            View root = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                root = findViewByAccessibilityId(accessibilityViewId);
            } else {
                root = mViewRootImpl.mView;
            }
            if (root != null) {
                View target = root.findViewById(viewId);
                if (target != null && isShown(target)) {
                    info = target.createAccessibilityNodeInfo();
                }
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mIncludeNotImportantViews = false;
                callback.setFindAccessibilityNodeInfoResult(info, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void findAccessibilityNodeInfosByTextClientThread(long accessibilityNodeId,
            String text, int windowLeft, int windowTop, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_TEXT;
        message.arg1 = flags;

        SomeArgs args = mPool.acquire();
        args.arg1 = text;
        args.argi1 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi2 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi3 = interactionId;

        SomeArgs moreArgs = mPool.acquire();
        moreArgs.arg1 = callback;
        moreArgs.argi1 = windowLeft;
        moreArgs.argi2 = windowTop;
        args.arg2 = moreArgs;

        message.obj = args;

        // If the interrogation is performed by the same thread as the main UI
        // thread in this process, set the message as a static reference so
        // after this call completes the same thread but in the interrogating
        // client can handle the message to generate the result.
        if (interrogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId) {
            AccessibilityInteractionClient.getInstanceForThread(
                    interrogatingTid).setSameThreadMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void findAccessibilityNodeInfosByTextUiThread(Message message) {
        final int flags = message.arg1;

        SomeArgs args = (SomeArgs) message.obj;
        final String text = (String) args.arg1;
        final int accessibilityViewId = args.argi1;
        final int virtualDescendantId = args.argi2;
        final int interactionId = args.argi3;

        SomeArgs moreArgs = (SomeArgs) args.arg2;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) moreArgs.arg1;
        mViewRootImpl.mAttachInfo.mActualWindowLeft = moreArgs.argi1;
        mViewRootImpl.mAttachInfo.mActualWindowTop = moreArgs.argi2;

        mPool.release(moreArgs);
        mPool.release(args);

        List<AccessibilityNodeInfo> infos = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mIncludeNotImportantViews =
                (flags & INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            View root = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                root = findViewByAccessibilityId(accessibilityViewId);
            } else {
                root = mViewRootImpl.mView;
            }
            if (root != null && isShown(root)) {
                AccessibilityNodeProvider provider = root.getAccessibilityNodeProvider();
                if (provider != null) {
                    infos = provider.findAccessibilityNodeInfosByText(text,
                            virtualDescendantId);
                } else if (virtualDescendantId == AccessibilityNodeInfo.UNDEFINED) {
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
                                                AccessibilityNodeInfo.UNDEFINED);
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
            try {
                mViewRootImpl.mAttachInfo.mIncludeNotImportantViews = false;
                callback.setFindAccessibilityNodeInfosResult(infos, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void findFocusClientThread(long accessibilityNodeId, int focusType, int windowLeft,
            int windowTop, int interactionId, IAccessibilityInteractionConnectionCallback callback,
            int flags, int interogatingPid, long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_FOCUS;
        message.arg1 = flags;
        message.arg2 = focusType;

        SomeArgs args = mPool.acquire();
        args.argi1 = interactionId;
        args.argi2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi3 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.arg1 = callback;

        SomeArgs moreArgs = mPool.acquire();
        moreArgs.argi1 = windowLeft;
        moreArgs.argi2 = windowTop;
        args.arg2 = moreArgs;

        message.obj = args;

        // If the interrogation is performed by the same thread as the main UI
        // thread in this process, set the message as a static reference so
        // after this call completes the same thread but in the interrogating
        // client can handle the message to generate the result.
        if (interogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId) {
            AccessibilityInteractionClient.getInstanceForThread(
                    interrogatingTid).setSameThreadMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
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

        SomeArgs moreArgs = (SomeArgs) args.arg2;
        mViewRootImpl.mAttachInfo.mActualWindowLeft = moreArgs.argi1;
        mViewRootImpl.mAttachInfo.mActualWindowTop = moreArgs.argi2;

        mPool.release(moreArgs);
        mPool.release(args);

        AccessibilityNodeInfo focused = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mIncludeNotImportantViews =
                (flags & INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            View root = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                root = findViewByAccessibilityId(accessibilityViewId);
            } else {
                root = mViewRootImpl.mView;
            }
            if (root != null && isShown(root)) {
                switch (focusType) {
                    case AccessibilityNodeInfo.FOCUS_ACCESSIBILITY: {
                        View host = mViewRootImpl.mAccessibilityFocusedHost;
                        // If there is no accessibility focus host or it is not a descendant
                        // of the root from which to start the search, then the search failed.
                        if (host == null || !ViewRootImpl.isViewDescendantOf(host, root)) {
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
                        } else if (virtualDescendantId == View.NO_ID) {
                            focused = host.createAccessibilityNodeInfo();
                        }
                    } break;
                    case AccessibilityNodeInfo.FOCUS_INPUT: {
                        // Input focus cannot go to virtual views.
                        View target = root.findFocus();
                        if (target != null && isShown(target)) {
                            focused = target.createAccessibilityNodeInfo();
                        }
                    } break;
                    default:
                        throw new IllegalArgumentException("Unknown focus type: " + focusType);
                }
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mIncludeNotImportantViews = false;
                callback.setFindAccessibilityNodeInfoResult(focused, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void focusSearchClientThread(long accessibilityNodeId, int direction, int windowLeft,
            int windowTop, int interactionId, IAccessibilityInteractionConnectionCallback callback,
            int flags, int interogatingPid, long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FOCUS_SEARCH;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = mPool.acquire();
        args.argi1 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi2 = direction;
        args.argi3 = interactionId;
        args.arg1 = callback;

        SomeArgs moreArgs = mPool.acquire();
        moreArgs.argi1 = windowLeft;
        moreArgs.argi2 = windowTop;
        args.arg2 = moreArgs;

        message.obj = args;

        // If the interrogation is performed by the same thread as the main UI
        // thread in this process, set the message as a static reference so
        // after this call completes the same thread but in the interrogating
        // client can handle the message to generate the result.
        if (interogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId) {
            AccessibilityInteractionClient.getInstanceForThread(
                    interrogatingTid).setSameThreadMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void focusSearchUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int virtualDescendantId = args.argi1;
        final int direction = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;

        SomeArgs moreArgs = (SomeArgs) args.arg2;
        mViewRootImpl.mAttachInfo.mActualWindowLeft = moreArgs.argi1;
        mViewRootImpl.mAttachInfo.mActualWindowTop = moreArgs.argi2;

        mPool.release(moreArgs);
        mPool.release(args);

        AccessibilityNodeInfo next = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mIncludeNotImportantViews =
                (flags & INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            View root = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                root = findViewByAccessibilityId(accessibilityViewId);
            } else {
                root = mViewRootImpl.mView;
            }
            if (root != null && isShown(root)) {
                if ((direction & View.FOCUS_ACCESSIBILITY) ==  View.FOCUS_ACCESSIBILITY) {
                    AccessibilityNodeProvider provider = root.getAccessibilityNodeProvider();
                    if (provider != null) {
                        next = provider.accessibilityFocusSearch(direction, virtualDescendantId);
                        if (next != null) {
                            return;
                        }
                    }
                    View nextView = root.focusSearch(direction);
                    while (nextView != null) {
                        // If the focus search reached a node with a provider
                        // we delegate to the provider to find the next one.
                        // If the provider does not return a virtual view to
                        // take accessibility focus we try the next view found
                        // by the focus search algorithm.
                        provider = nextView.getAccessibilityNodeProvider();
                        if (provider != null) {
                            next = provider.accessibilityFocusSearch(direction, View.NO_ID);
                            if (next != null) {
                                break;
                            }
                            nextView = nextView.focusSearch(direction);
                        } else {
                            next = nextView.createAccessibilityNodeInfo();
                            break;
                        }
                    }
                } else {
                    View nextView = root.focusSearch(direction);
                    if (nextView != null) {
                        next = nextView.createAccessibilityNodeInfo();
                    }
                }
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mIncludeNotImportantViews = false;
                callback.setFindAccessibilityNodeInfoResult(next, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void performAccessibilityActionClientThread(long accessibilityNodeId, int action,
            Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interogatingPid,
            long interrogatingTid) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_PERFORM_ACCESSIBILITY_ACTION;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = mPool.acquire();
        args.argi1 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi2 = action;
        args.argi3 = interactionId;
        args.arg1 = callback;
        args.arg2 = arguments;

        message.obj = args;

        // If the interrogation is performed by the same thread as the main UI
        // thread in this process, set the message as a static reference so
        // after this call completes the same thread but in the interrogating
        // client can handle the message to generate the result.
        if (interogatingPid == mMyProcessId && interrogatingTid == mMyLooperThreadId) {
            AccessibilityInteractionClient.getInstanceForThread(
                    interrogatingTid).setSameThreadMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void perfromAccessibilityActionUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int virtualDescendantId = args.argi1;
        final int action = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        Bundle arguments = (Bundle) args.arg2;

        mPool.release(args);

        boolean succeeded = false;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mIncludeNotImportantViews =
                (flags & INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            View target = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                target = findViewByAccessibilityId(accessibilityViewId);
            } else {
                target = mViewRootImpl.mView;
            }
            if (target != null && isShown(target)) {
                AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
                if (provider != null) {
                    succeeded = provider.performAction(virtualDescendantId, action,
                            arguments);
                } else if (virtualDescendantId == View.NO_ID) {
                    succeeded = target.performAccessibilityAction(action, arguments);
                }
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mIncludeNotImportantViews = false;
                callback.setPerformAccessibilityActionResult(succeeded, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    private View findViewByAccessibilityId(int accessibilityId) {
        View root = mViewRootImpl.mView;
        if (root == null) {
            return null;
        }
        View foundView = root.findViewByAccessibilityId(accessibilityId);
        if (foundView != null && !isShown(foundView)) {
            return null;
        }
        return foundView;
    }

    /**
     * This class encapsulates a prefetching strategy for the accessibility APIs for
     * querying window content. It is responsible to prefetch a batch of
     * AccessibilityNodeInfos in addition to the one for a requested node.
     */
    private class AccessibilityNodePrefetcher {

        private static final int MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE = 50;

        private final ArrayList<View> mTempViewList = new ArrayList<View>();

        public void prefetchAccessibilityNodeInfos(View view, int virtualViewId, int prefetchFlags,
                List<AccessibilityNodeInfo> outInfos) {
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider == null) {
                AccessibilityNodeInfo root = view.createAccessibilityNodeInfo();
                if (root != null) {
                    outInfos.add(root);
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS) != 0) {
                        prefetchPredecessorsOfRealNode(view, outInfos);
                    }
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS) != 0) {
                        prefetchSiblingsOfRealNode(view, outInfos);
                    }
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS) != 0) {
                        prefetchDescendantsOfRealNode(view, outInfos);
                    }
                }
            } else {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(virtualViewId);
                if (root != null) {
                    outInfos.add(root);
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS) != 0) {
                        prefetchPredecessorsOfVirtualNode(root, view, provider, outInfos);
                    }
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS) != 0) {
                        prefetchSiblingsOfVirtualNode(root, view, provider, outInfos);
                    }
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS) != 0) {
                        prefetchDescendantsOfVirtualNode(root, provider, outInfos);
                    }
                }
            }
        }

        private void prefetchPredecessorsOfRealNode(View view,
                List<AccessibilityNodeInfo> outInfos) {
            ViewParent parent = view.getParentForAccessibility();
            while (parent instanceof View
                    && outInfos.size() < MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
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
            ViewParent parent = current.getParentForAccessibility();
            if (parent instanceof ViewGroup) {
                ViewGroup parentGroup = (ViewGroup) parent;
                ArrayList<View> children = mTempViewList;
                children.clear();
                try {
                    parentGroup.addChildrenForAccessibility(children);
                    final int childCount = children.size();
                    for (int i = 0; i < childCount; i++) {
                        if (outInfos.size() >= MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
                            return;
                        }
                        View child = children.get(i);
                        if (child.getAccessibilityViewId() != current.getAccessibilityViewId()
                                &&  isShown(child)) {
                            AccessibilityNodeInfo info = null;
                            AccessibilityNodeProvider provider =
                                child.getAccessibilityNodeProvider();
                            if (provider == null) {
                                info = child.createAccessibilityNodeInfo();
                            } else {
                                info = provider.createAccessibilityNodeInfo(
                                        AccessibilityNodeInfo.UNDEFINED);
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
            if (!(root instanceof ViewGroup)) {
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
                    if (outInfos.size() >= MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
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
                                   AccessibilityNodeInfo.UNDEFINED);
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
            if (outInfos.size() < MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
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
            long parentNodeId = root.getParentNodeId();
            int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(parentNodeId);
            while (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                if (outInfos.size() >= MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
                    return;
                }
                final int virtualDescendantId =
                    AccessibilityNodeInfo.getVirtualDescendantId(parentNodeId);
                if (virtualDescendantId != AccessibilityNodeInfo.UNDEFINED
                        || accessibilityViewId == providerHost.getAccessibilityViewId()) {
                    AccessibilityNodeInfo parent = provider.createAccessibilityNodeInfo(
                            virtualDescendantId);
                    if (parent != null) {
                        outInfos.add(parent);
                    }
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
            if (parentVirtualDescendantId != AccessibilityNodeInfo.UNDEFINED
                    || parentAccessibilityViewId == providerHost.getAccessibilityViewId()) {
                AccessibilityNodeInfo parent =
                    provider.createAccessibilityNodeInfo(parentVirtualDescendantId);
                if (parent != null) {
                    SparseLongArray childNodeIds = parent.getChildNodeIds();
                    final int childCount = childNodeIds.size();
                    for (int i = 0; i < childCount; i++) {
                        if (outInfos.size() >= MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
                            return;
                        }
                        final long childNodeId = childNodeIds.get(i);
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
            SparseLongArray childNodeIds = root.getChildNodeIds();
            final int initialOutInfosSize = outInfos.size();
            final int childCount = childNodeIds.size();
            for (int i = 0; i < childCount; i++) {
                if (outInfos.size() >= MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
                    return;
                }
                final long childNodeId = childNodeIds.get(i);
                AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(
                        AccessibilityNodeInfo.getVirtualDescendantId(childNodeId));
                if (child != null) {
                    outInfos.add(child);
                }
            }
            if (outInfos.size() < MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE) {
                final int addedChildCount = outInfos.size() - initialOutInfosSize;
                for (int i = 0; i < addedChildCount; i++) {
                    AccessibilityNodeInfo child = outInfos.get(initialOutInfosSize + i);
                    prefetchDescendantsOfVirtualNode(child, provider, outInfos);
                }
            }
        }
    }

    private class PrivateHandler extends Handler {
        private final static int MSG_PERFORM_ACCESSIBILITY_ACTION = 1;
        private final static int MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_ACCESSIBILITY_ID = 2;
        private final static int MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_VIEW_ID = 3;
        private final static int MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_TEXT = 4;
        private final static int MSG_FIND_FOCUS = 5;
        private final static int MSG_FOCUS_SEARCH = 6;

        public PrivateHandler(Looper looper) {
            super(looper);
        }

        @Override
        public String getMessageName(Message message) {
            final int type = message.what;
            switch (type) {
                case MSG_PERFORM_ACCESSIBILITY_ACTION:
                    return "MSG_PERFORM_ACCESSIBILITY_ACTION";
                case MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_ACCESSIBILITY_ID:
                    return "MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_ACCESSIBILITY_ID";
                case MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_VIEW_ID:
                    return "MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_VIEW_ID";
                case MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_TEXT:
                    return "MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_TEXT";
                case MSG_FIND_FOCUS:
                    return "MSG_FIND_FOCUS";
                case MSG_FOCUS_SEARCH:
                    return "MSG_FOCUS_SEARCH";
                default:
                    throw new IllegalArgumentException("Unknown message type: " + type);
            }
        }

        @Override
        public void handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_ACCESSIBILITY_ID: {
                    findAccessibilityNodeInfoByAccessibilityIdUiThread(message);
                } break;
                case MSG_PERFORM_ACCESSIBILITY_ACTION: {
                    perfromAccessibilityActionUiThread(message);
                } break;
                case MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_VIEW_ID: {
                    findAccessibilityNodeInfoByViewIdUiThread(message);
                } break;
                case MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_TEXT: {
                    findAccessibilityNodeInfosByTextUiThread(message);
                } break;
                case MSG_FIND_FOCUS: {
                    findFocusUiThread(message);
                } break;
                case MSG_FOCUS_SEARCH: {
                    focusSearchUiThread(message);
                } break;
                default:
                    throw new IllegalArgumentException("Unknown message type: " + type);
            }
        }
    }
}
