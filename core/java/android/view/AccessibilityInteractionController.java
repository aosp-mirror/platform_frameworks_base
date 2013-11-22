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

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.SparseLongArray;
import android.view.View.AttachInfo;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.Predicate;

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

    private final ArrayList<AccessibilityNodeInfo> mTempAccessibilityNodeInfoList =
        new ArrayList<AccessibilityNodeInfo>();

    private final Handler mHandler;

    private final ViewRootImpl mViewRootImpl;

    private final AccessibilityNodePrefetcher mPrefetcher;

    private final long mMyLooperThreadId;

    private final int mMyProcessId;

    private final ArrayList<View> mTempArrayList = new ArrayList<View>();

    private final Point mTempPoint = new Point();
    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();
    private final Rect mTempRect2 = new Rect();

    private AddNodeInfosForViewId mAddNodeInfosForViewId;

    public AccessibilityInteractionController(ViewRootImpl viewRootImpl) {
        Looper looper =  viewRootImpl.mHandler.getLooper();
        mMyLooperThreadId = looper.getThread().getId();
        mMyProcessId = Process.myPid();
        mHandler = new PrivateHandler(looper);
        mViewRootImpl = viewRootImpl;
        mPrefetcher = new AccessibilityNodePrefetcher();
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
            long accessibilityNodeId, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
            long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_ACCESSIBILITY_ID;
        message.arg1 = flags;

        SomeArgs args = SomeArgs.obtain();
        args.argi1 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi2 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi3 = interactionId;
        args.arg1 = callback;
        args.arg2 = spec;
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
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;

        args.recycle();

        List<AccessibilityNodeInfo> infos = mTempAccessibilityNodeInfoList;
        infos.clear();
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
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
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
                applyAppScaleAndMagnificationSpecIfNeeded(infos, spec);
                if (spec != null) {
                    spec.recycle();
                }
                callback.setFindAccessibilityNodeInfosResult(infos, interactionId);
                infos.clear();
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void findAccessibilityNodeInfosByViewIdClientThread(long accessibilityNodeId,
            String viewId, int interactionId, IAccessibilityInteractionConnectionCallback callback,
            int flags, int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBLITY_NODE_INFOS_BY_VIEW_ID;
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);

        SomeArgs args = SomeArgs.obtain();
        args.argi1 = interactionId;
        args.arg1 = callback;
        args.arg2 = spec;
        args.arg3 = viewId;

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

    private void findAccessibilityNodeInfosByViewIdUiThread(Message message) {
        final int flags = message.arg1;
        final int accessibilityViewId = message.arg2;

        SomeArgs args = (SomeArgs) message.obj;
        final int interactionId = args.argi1;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;
        final String viewId = (String) args.arg3;

        args.recycle();

        final List<AccessibilityNodeInfo> infos = mTempAccessibilityNodeInfoList;
        infos.clear();
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            View root = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                root = findViewByAccessibilityId(accessibilityViewId);
            } else {
                root = mViewRootImpl.mView;
            }
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
            try {
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
                applyAppScaleAndMagnificationSpecIfNeeded(infos, spec);
                if (spec != null) {
                    spec.recycle();
                }
                callback.setFindAccessibilityNodeInfosResult(infos, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void findAccessibilityNodeInfosByTextClientThread(long accessibilityNodeId,
            String text, int interactionId, IAccessibilityInteractionConnectionCallback callback,
            int flags, int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
        Message message = mHandler.obtainMessage();
        message.what = PrivateHandler.MSG_FIND_ACCESSIBLITY_NODE_INFO_BY_TEXT;
        message.arg1 = flags;

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = text;
        args.arg2 = callback;
        args.arg3 = spec;
        args.argi1 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        args.argi2 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi3 = interactionId;
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
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg2;
        final MagnificationSpec spec = (MagnificationSpec) args.arg3;
        final int accessibilityViewId = args.argi1;
        final int virtualDescendantId = args.argi2;
        final int interactionId = args.argi3;
        args.recycle();

        List<AccessibilityNodeInfo> infos = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
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
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
                applyAppScaleAndMagnificationSpecIfNeeded(infos, spec);
                if (spec != null) {
                    spec.recycle();
                }
                callback.setFindAccessibilityNodeInfosResult(infos, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void findFocusClientThread(long accessibilityNodeId, int focusType, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interogatingPid,
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
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;
        args.recycle();

        AccessibilityNodeInfo focused = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
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
                        } else if (virtualDescendantId == View.NO_ID) {
                            focused = host.createAccessibilityNodeInfo();
                        }
                    } break;
                    case AccessibilityNodeInfo.FOCUS_INPUT: {
                        View target = root.findFocus();
                        if (target == null || !isShown(target)) {
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
            try {
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
                applyAppScaleAndMagnificationSpecIfNeeded(focused, spec);
                if (spec != null) {
                    spec.recycle();
                }
                callback.setFindAccessibilityNodeInfoResult(focused, interactionId);
            } catch (RemoteException re) {
                /* ignore - the other side will time out */
            }
        }
    }

    public void focusSearchClientThread(long accessibilityNodeId, int direction, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, int interogatingPid,
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
        final int direction = args.argi2;
        final int interactionId = args.argi3;
        final IAccessibilityInteractionConnectionCallback callback =
            (IAccessibilityInteractionConnectionCallback) args.arg1;
        final MagnificationSpec spec = (MagnificationSpec) args.arg2;

        args.recycle();

        AccessibilityNodeInfo next = null;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
            View root = null;
            if (accessibilityViewId != AccessibilityNodeInfo.UNDEFINED) {
                root = findViewByAccessibilityId(accessibilityViewId);
            } else {
                root = mViewRootImpl.mView;
            }
            if (root != null && isShown(root)) {
                View nextView = root.focusSearch(direction);
                if (nextView != null) {
                    next = nextView.createAccessibilityNodeInfo();
                }
            }
        } finally {
            try {
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
                applyAppScaleAndMagnificationSpecIfNeeded(next, spec);
                if (spec != null) {
                    spec.recycle();
                }
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

        SomeArgs args = SomeArgs.obtain();
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

        args.recycle();

        boolean succeeded = false;
        try {
            if (mViewRootImpl.mView == null || mViewRootImpl.mAttachInfo == null) {
                return;
            }
            mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = flags;
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
                mViewRootImpl.mAttachInfo.mAccessibilityFetchFlags = 0;
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

    private void applyAppScaleAndMagnificationSpecIfNeeded(List<AccessibilityNodeInfo> infos,
            MagnificationSpec spec) {
        if (infos == null) {
            return;
        }
        final float applicationScale = mViewRootImpl.mAttachInfo.mApplicationScale;
        if (shouldApplyAppScaleAndMagnificationSpec(applicationScale, spec)) {
            final int infoCount = infos.size();
            for (int i = 0; i < infoCount; i++) {
                AccessibilityNodeInfo info = infos.get(i);
                applyAppScaleAndMagnificationSpecIfNeeded(info, spec);
            }
        }
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

        if (spec != null) {
            AttachInfo attachInfo = mViewRootImpl.mAttachInfo;
            if (attachInfo.mDisplay == null) {
                return;
            }

            final float scale = attachInfo.mApplicationScale * spec.scale;

            Rect visibleWinFrame = mTempRect1;
            visibleWinFrame.left = (int) (attachInfo.mWindowLeft * scale + spec.offsetX);
            visibleWinFrame.top = (int) (attachInfo.mWindowTop * scale + spec.offsetY);
            visibleWinFrame.right = (int) (visibleWinFrame.left + mViewRootImpl.mWidth * scale);
            visibleWinFrame.bottom = (int) (visibleWinFrame.top + mViewRootImpl.mHeight * scale);

            attachInfo.mDisplay.getRealSize(mTempPoint);
            final int displayWidth = mTempPoint.x;
            final int displayHeight = mTempPoint.y;

            Rect visibleDisplayFrame = mTempRect2;
            visibleDisplayFrame.set(0, 0, displayWidth, displayHeight);

            visibleWinFrame.intersect(visibleDisplayFrame);

            if (!visibleWinFrame.intersects(boundsInScreen.left, boundsInScreen.top,
                    boundsInScreen.right, boundsInScreen.bottom)) {
                info.setVisibleToUser(false);
            }
        }
    }

    private boolean shouldApplyAppScaleAndMagnificationSpec(float appScale,
            MagnificationSpec spec) {
        return (appScale != 1.0f || (spec != null && !spec.isNop()));
    }

    /**
     * This class encapsulates a prefetching strategy for the accessibility APIs for
     * querying window content. It is responsible to prefetch a batch of
     * AccessibilityNodeInfos in addition to the one for a requested node.
     */
    private class AccessibilityNodePrefetcher {

        private static final int MAX_ACCESSIBILITY_NODE_INFO_BATCH_SIZE = 50;

        private final ArrayList<View> mTempViewList = new ArrayList<View>();

        public void prefetchAccessibilityNodeInfos(View view, int virtualViewId, int fetchFlags,
                List<AccessibilityNodeInfo> outInfos) {
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider == null) {
                AccessibilityNodeInfo root = view.createAccessibilityNodeInfo();
                if (root != null) {
                    outInfos.add(root);
                    if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS) != 0) {
                        prefetchPredecessorsOfRealNode(view, outInfos);
                    }
                    if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS) != 0) {
                        prefetchSiblingsOfRealNode(view, outInfos);
                    }
                    if ((fetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS) != 0) {
                        prefetchDescendantsOfRealNode(view, outInfos);
                    }
                }
            } else {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(virtualViewId);
                if (root != null) {
                    outInfos.add(root);
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
        private final static int MSG_FIND_ACCESSIBLITY_NODE_INFOS_BY_VIEW_ID = 3;
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
                case MSG_FIND_ACCESSIBLITY_NODE_INFOS_BY_VIEW_ID:
                    return "MSG_FIND_ACCESSIBLITY_NODE_INFOS_BY_VIEW_ID";
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
                case MSG_FIND_ACCESSIBLITY_NODE_INFOS_BY_VIEW_ID: {
                    findAccessibilityNodeInfosByViewIdUiThread(message);
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
        public boolean apply(View view) {
            if (view.getId() == mViewId && isShown(view)) {
                mInfos.add(view.createAccessibilityNodeInfo());
            }
            return false;
        }
    }
}
