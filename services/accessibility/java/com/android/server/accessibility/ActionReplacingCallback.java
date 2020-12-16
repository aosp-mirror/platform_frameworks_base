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
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;

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
    private final int mNodeWithReplacementActionsInteractionId;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mRequestForNodeWithReplacementActionFailed;

    @GuardedBy("mLock")
    AccessibilityNodeInfo mNodeWithReplacementActions;

    @GuardedBy("mLock")
    List<AccessibilityNodeInfo> mNodesFromOriginalWindow;

    @GuardedBy("mLock")
    AccessibilityNodeInfo mNodeFromOriginalWindow;

    @GuardedBy("mLock")
    List<AccessibilityNodeInfo> mPrefetchedNodesFromOriginalWindow;

    public ActionReplacingCallback(IAccessibilityInteractionConnectionCallback serviceCallback,
            IAccessibilityInteractionConnection connectionWithReplacementActions,
            int interactionId, int interrogatingPid, long interrogatingTid) {
        mServiceCallback = serviceCallback;
        mConnectionWithReplacementActions = connectionWithReplacementActions;
        mInteractionId = interactionId;
        mNodeWithReplacementActionsInteractionId = interactionId + 1;

        // Request the root node of the replacing window
        final long identityToken = Binder.clearCallingIdentity();
        try {
            mConnectionWithReplacementActions.findAccessibilityNodeInfoByAccessibilityId(
                    AccessibilityNodeInfo.ROOT_NODE_ID, null,
                    mNodeWithReplacementActionsInteractionId, this, 0,
                    interrogatingPid, interrogatingTid, null, null);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfoByAccessibilityId()");
            }
            mRequestForNodeWithReplacementActionFailed = true;
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info, int interactionId) {
        synchronized (mLock) {
            if (interactionId == mInteractionId) {
                mNodeFromOriginalWindow = info;
            } else if (interactionId == mNodeWithReplacementActionsInteractionId) {
                mNodeWithReplacementActions = info;
            } else {
                Slog.e(LOG_TAG, "Callback with unexpected interactionId");
                return;
            }
        }
        replaceInfoActionsAndCallServiceIfReady();
    }

    @Override
    public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
            int interactionId) {
        synchronized (mLock) {
            if (interactionId == mInteractionId) {
                mNodesFromOriginalWindow = infos;
            } else if (interactionId == mNodeWithReplacementActionsInteractionId) {
                setNodeWithReplacementActionsFromList(infos);
            } else {
                Slog.e(LOG_TAG, "Callback with unexpected interactionId");
                return;
            }
        }
        replaceInfoActionsAndCallServiceIfReady();
    }

    @Override
    public void setPrefetchAccessibilityNodeInfoResult(List<AccessibilityNodeInfo> infos,
                                                       int interactionId)
            throws RemoteException {
        synchronized (mLock) {
            if (interactionId == mInteractionId) {
                mPrefetchedNodesFromOriginalWindow = infos;
            }  else {
                Slog.e(LOG_TAG, "Callback with unexpected interactionId");
                return;
            }
        }
        replaceInfoActionsAndCallServiceIfReady();
    }

    private void replaceInfoActionsAndCallServiceIfReady() {
        boolean originalAndReplacementCallsHaveHappened = false;
        synchronized (mLock) {
            originalAndReplacementCallsHaveHappened = mNodeWithReplacementActions != null
                    && (mNodeFromOriginalWindow != null
                    || mNodesFromOriginalWindow != null
                    || mPrefetchedNodesFromOriginalWindow != null);
            originalAndReplacementCallsHaveHappened
                    |= mRequestForNodeWithReplacementActionFailed;
        }
        if (originalAndReplacementCallsHaveHappened) {
            replaceInfoActionsAndCallService();
            replaceInfosActionsAndCallService();
            replacePrefetchInfosActionsAndCallService();
        }
    }

    private void setNodeWithReplacementActionsFromList(List<AccessibilityNodeInfo> infos) {
        for (int i = 0; i < infos.size(); i++) {
            AccessibilityNodeInfo info = infos.get(i);
            if (info.getSourceNodeId() == AccessibilityNodeInfo.ROOT_NODE_ID) {
                mNodeWithReplacementActions = info;
            }
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
            if (mNodeFromOriginalWindow != null) {
                replaceActionsOnInfoLocked(mNodeFromOriginalWindow);
            }
            nodeToReturn = mNodeFromOriginalWindow;
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
            nodesToReturn = replaceActionsLocked(mNodesFromOriginalWindow);
        }
        try {
            mServiceCallback.setFindAccessibilityNodeInfosResult(nodesToReturn, mInteractionId);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Failed to setFindAccessibilityNodeInfosResult");
            }
        }
    }

    private void replacePrefetchInfosActionsAndCallService() {
        final List<AccessibilityNodeInfo> nodesToReturn;
        synchronized (mLock) {
            nodesToReturn = replaceActionsLocked(mPrefetchedNodesFromOriginalWindow);
        }
        try {
            mServiceCallback.setPrefetchAccessibilityNodeInfoResult(nodesToReturn, mInteractionId);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Failed to setFindAccessibilityNodeInfosResult");
            }
        }
    }

    @GuardedBy("mLock")
    private List<AccessibilityNodeInfo> replaceActionsLocked(List<AccessibilityNodeInfo> infos) {
        if (infos != null) {
            for (int i = 0; i < infos.size(); i++) {
                replaceActionsOnInfoLocked(infos.get(i));
            }
        }
        return (infos == null)
                ? null : new ArrayList<>(infos);
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
                && mNodeWithReplacementActions != null) {
            List<AccessibilityAction> actions = mNodeWithReplacementActions.getActionList();
            if (actions != null) {
                for (int j = 0; j < actions.size(); j++) {
                    info.addAction(actions.get(j));
                }
                // The PIP needs to be able to take accessibility focus
                info.addAction(AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
                info.addAction(AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            }
            info.setClickable(mNodeWithReplacementActions.isClickable());
            info.setFocusable(mNodeWithReplacementActions.isFocusable());
            info.setContextClickable(mNodeWithReplacementActions.isContextClickable());
            info.setScrollable(mNodeWithReplacementActions.isScrollable());
            info.setLongClickable(mNodeWithReplacementActions.isLongClickable());
            info.setDismissable(mNodeWithReplacementActions.isDismissable());
        }
    }
}
