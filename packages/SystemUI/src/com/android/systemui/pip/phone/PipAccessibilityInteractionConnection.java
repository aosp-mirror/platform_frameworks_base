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
package com.android.systemui.pip.phone;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.view.MagnificationSpec;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Expose the touch actions to accessibility as if this object were a window with a single view.
 * That pseudo-view exposes all of the actions this object can perform.
 */
public class PipAccessibilityInteractionConnection
        extends IAccessibilityInteractionConnection.Stub {

    public interface AccessibilityCallbacks {
        void onAccessibilityShowMenu();
    }

    private static final long ACCESSIBILITY_NODE_ID = 1;
    private List<AccessibilityNodeInfo> mAccessibilityNodeInfoList;

    private Handler mHandler;
    private PipMotionHelper mMotionHelper;
    private AccessibilityCallbacks mCallbacks;

    private Rect mTmpBounds = new Rect();

    public PipAccessibilityInteractionConnection(PipMotionHelper motionHelper,
            AccessibilityCallbacks callbacks, Handler handler) {
        mHandler = handler;
        mMotionHelper = motionHelper;
        mCallbacks = callbacks;
    }

    @Override
    public void findAccessibilityNodeInfoByAccessibilityId(long accessibilityNodeId,
            Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid, MagnificationSpec spec, Bundle args) {
        try {
            callback.setFindAccessibilityNodeInfosResult(
                    (accessibilityNodeId == AccessibilityNodeInfo.ROOT_NODE_ID)
                            ? getNodeList() : null, interactionId);
        } catch (RemoteException re) {
                /* best effort - ignore */
        }
    }

    @Override
    public void performAccessibilityAction(long accessibilityNodeId, int action,
            Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid) {
        // We only support one view. A request for anything else is invalid
        boolean result = false;
        if (accessibilityNodeId == AccessibilityNodeInfo.ROOT_NODE_ID) {
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    mHandler.post(() -> {
                        mCallbacks.onAccessibilityShowMenu();
                    });
                    result = true;
                    break;
                case AccessibilityNodeInfo.ACTION_DISMISS:
                    mMotionHelper.dismissPip();
                    result = true;
                    break;
                case com.android.internal.R.id.accessibilityActionMoveWindow:
                    int newX = arguments.getInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_X);
                    int newY = arguments.getInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_Y);
                    Rect pipBounds = new Rect();
                    pipBounds.set(mMotionHelper.getBounds());
                    mTmpBounds.offsetTo(newX, newY);
                    mMotionHelper.movePip(mTmpBounds);
                    result = true;
                    break;
                case AccessibilityNodeInfo.ACTION_EXPAND:
                    mMotionHelper.expandPip();
                    result = true;
                    break;
                default:
                    // Leave result as false
            }
        }
        try {
            callback.setPerformAccessibilityActionResult(result, interactionId);
        } catch (RemoteException re) {
                /* best effort - ignore */
        }
    }

    @Override
    public void findAccessibilityNodeInfosByViewId(long accessibilityNodeId,
            String viewId, Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
        // We have no view with a proper ID
        try {
            callback.setFindAccessibilityNodeInfoResult(null, interactionId);
        } catch (RemoteException re) {
            /* best effort - ignore */
        }
    }

    @Override
    public void findAccessibilityNodeInfosByText(long accessibilityNodeId, String text,
            Region interactiveRegion, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
        // We have no view with text
        try {
            callback.setFindAccessibilityNodeInfoResult(null, interactionId);
        } catch (RemoteException re) {
            /* best effort - ignore */
        }
    }

    @Override
    public void findFocus(long accessibilityNodeId, int focusType, Region interactiveRegion,
            int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
        // We have no view that can take focus
        try {
            callback.setFindAccessibilityNodeInfoResult(null, interactionId);
        } catch (RemoteException re) {
            /* best effort - ignore */
        }
    }

    @Override
    public void focusSearch(long accessibilityNodeId, int direction, Region interactiveRegion,
            int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
        // We have no view that can take focus
        try {
            callback.setFindAccessibilityNodeInfoResult(null, interactionId);
        } catch (RemoteException re) {
            /* best effort - ignore */
        }
    }

    @Override
    public void clearAccessibilityFocus() {
        // We should not be here.
    }

    @Override
    public void notifyOutsideTouch() {
        // Do nothing.
    }

    public static AccessibilityNodeInfo obtainRootAccessibilityNodeInfo() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_MOVE_WINDOW);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
        info.setImportantForAccessibility(true);
        info.setClickable(true);
        info.setVisibleToUser(true);
        return info;
    }

    private List<AccessibilityNodeInfo> getNodeList() {
        if (mAccessibilityNodeInfoList == null) {
            mAccessibilityNodeInfoList = new ArrayList<>(1);
        }
        AccessibilityNodeInfo info = obtainRootAccessibilityNodeInfo();
        mAccessibilityNodeInfoList.clear();
        mAccessibilityNodeInfoList.add(info);
        return mAccessibilityNodeInfoList;
    }
}
