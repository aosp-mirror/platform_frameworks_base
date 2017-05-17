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

import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.MagnificationSpec;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * If we are stripping and/or replacing the actions from a window, we need to intercept the
 * nodes heading back to the service and swap out the actions.
 */
public class ActionReplacingCallback extends IAccessibilityInteractionConnectionCallback.Stub {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "ActionReplacingCallback";

    private final IAccessibilityInteractionConnectionCallback mServiceCallback;
    private final IAccessibilityInteractionConnection mConnectionWithReplacementActions;
    private final int mInteractionId;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    List<AccessibilityNodeInfo> mNodesWithReplacementActions;

    @GuardedBy("mLock")
    List<AccessibilityNodeInfo> mNodesFromOriginalWindow;

    @GuardedBy("mLock")
    AccessibilityNodeInfo mNodeFromOriginalWindow;

    // Keep track of whether or not we've been called back for a single node
    @GuardedBy("mLock")
    boolean mSingleNodeCallbackHappened;

    // Keep track of whether or not we've been called back for multiple node
    @GuardedBy("mLock")
    boolean mMultiNodeCallbackHappened;

    // We shouldn't get any more callbacks after we've called back the original service, but
    // keep track to make sure we catch such strange things
    @GuardedBy("mLock")
    boolean mDone;

    public ActionReplacingCallback(IAccessibilityInteractionConnectionCallback serviceCallback,
            IAccessibilityInteractionConnection connectionWithReplacementActions,
            int interactionId, int interrogatingPid, long interrogatingTid) {
        mServiceCallback = serviceCallback;
        mConnectionWithReplacementActions = connectionWithReplacementActions;
        mInteractionId = interactionId;

        // Request the root node of the replacing window
        final long identityToken = Binder.clearCallingIdentity();
        try {
            mConnectionWithReplacementActions.findAccessibilityNodeInfoByAccessibilityId(
                    AccessibilityNodeInfo.ROOT_NODE_ID, null, interactionId + 1, this, 0,
                    interrogatingPid, interrogatingTid, null, null);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfoByAccessibilityId()");
            }
            // Pretend we already got a (null) list of replacement nodes
            mMultiNodeCallbackHappened = true;
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info, int interactionId) {
        boolean readyForCallback;
        synchronized(mLock) {
            if (interactionId == mInteractionId) {
                mNodeFromOriginalWindow = info;
            } else {
                Slog.e(LOG_TAG, "Callback with unexpected interactionId");
                return;
            }

            mSingleNodeCallbackHappened = true;
            readyForCallback = mMultiNodeCallbackHappened;
        }
        if (readyForCallback) {
            replaceInfoActionsAndCallService();
        }
    }

    @Override
    public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
            int interactionId) {
        boolean callbackForSingleNode;
        boolean callbackForMultipleNodes;
        synchronized(mLock) {
            if (interactionId == mInteractionId) {
                mNodesFromOriginalWindow = infos;
            } else if (interactionId == mInteractionId + 1) {
                mNodesWithReplacementActions = infos;
            } else {
                Slog.e(LOG_TAG, "Callback with unexpected interactionId");
                return;
            }
            callbackForSingleNode = mSingleNodeCallbackHappened;
            callbackForMultipleNodes = mMultiNodeCallbackHappened;
            mMultiNodeCallbackHappened = true;
        }
        if (callbackForSingleNode) {
            replaceInfoActionsAndCallService();
        }
        if (callbackForMultipleNodes) {
            replaceInfosActionsAndCallService();
        }
    }

    @Override
    public void setPerformAccessibilityActionResult(boolean succeeded, int interactionId)
            throws RemoteException {
        // There's no reason to use this class when performing actions. Do something reasonable.
        mServiceCallback.setPerformAccessibilityActionResult(succeeded, interactionId);
    }

    private void replaceInfoActionsAndCallService() {
        final AccessibilityNodeInfo nodeToReturn;
        synchronized (mLock) {
            if (mDone) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Extra callback");
                }
                return;
            }
            if (mNodeFromOriginalWindow != null) {
                replaceActionsOnInfoLocked(mNodeFromOriginalWindow);
            }
            recycleReplaceActionNodesLocked();
            nodeToReturn = mNodeFromOriginalWindow;
            mDone = true;
        }
        try {
            mServiceCallback.setFindAccessibilityNodeInfoResult(nodeToReturn, mInteractionId);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Failed to setFindAccessibilityNodeInfoResult");
            }
        }
    }

    private void replaceInfosActionsAndCallService() {
        final List<AccessibilityNodeInfo> nodesToReturn;
        synchronized (mLock) {
            if (mDone) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Extra callback");
                }
                return;
            }
            if (mNodesFromOriginalWindow != null) {
                for (int i = 0; i < mNodesFromOriginalWindow.size(); i++) {
                    replaceActionsOnInfoLocked(mNodesFromOriginalWindow.get(i));
                }
            }
            recycleReplaceActionNodesLocked();
            nodesToReturn = (mNodesFromOriginalWindow == null)
                    ? null : new ArrayList<>(mNodesFromOriginalWindow);
            mDone = true;
        }
        try {
            mServiceCallback.setFindAccessibilityNodeInfosResult(nodesToReturn, mInteractionId);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Failed to setFindAccessibilityNodeInfosResult");
            }
        }
    }

    @GuardedBy("mLock")
    private void replaceActionsOnInfoLocked(AccessibilityNodeInfo info) {
        info.removeAllActions();
        info.setClickable(false);
        info.setFocusable(false);
        info.setContextClickable(false);
        info.setScrollable(false);
        info.setLongClickable(false);
        info.setDismissable(false);
        // We currently only replace actions for the root node
        if ((info.getSourceNodeId() == AccessibilityNodeInfo.ROOT_NODE_ID)
                && mNodesWithReplacementActions != null) {
            // This list should always contain a single node with the root ID
            for (int i = 0; i < mNodesWithReplacementActions.size(); i++) {
                AccessibilityNodeInfo nodeWithReplacementActions =
                        mNodesWithReplacementActions.get(i);
                if (nodeWithReplacementActions.getSourceNodeId()
                        == AccessibilityNodeInfo.ROOT_NODE_ID) {
                    List<AccessibilityAction> actions = nodeWithReplacementActions.getActionList();
                    if (actions != null) {
                        for (int j = 0; j < actions.size(); j++) {
                            info.addAction(actions.get(j));
                        }
                        // The PIP needs to be able to take accessibility focus
                        info.addAction(AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
                        info.addAction(AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
                    }
                    info.setClickable(nodeWithReplacementActions.isClickable());
                    info.setFocusable(nodeWithReplacementActions.isFocusable());
                    info.setContextClickable(nodeWithReplacementActions.isContextClickable());
                    info.setScrollable(nodeWithReplacementActions.isScrollable());
                    info.setLongClickable(nodeWithReplacementActions.isLongClickable());
                    info.setDismissable(nodeWithReplacementActions.isDismissable());
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void recycleReplaceActionNodesLocked() {
        if (mNodesWithReplacementActions == null) return;
        for (int i = mNodesWithReplacementActions.size() - 1; i >= 0; i--) {
            AccessibilityNodeInfo nodeWithReplacementAction = mNodesWithReplacementActions.get(i);
            nodeWithReplacementAction.recycle();
        }
        mNodesWithReplacementActions = null;
    }
}
