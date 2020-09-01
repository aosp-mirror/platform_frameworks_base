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

import android.content.Context;
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

import com.android.systemui.R;
import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.pip.PipTaskOrganizer;

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

    private Context mContext;
    private Handler mHandler;
    private PipMotionHelper mMotionHelper;
    private PipTaskOrganizer mTaskOrganizer;
    private PipSnapAlgorithm mSnapAlgorithm;
    private Runnable mUpdateMovementBoundCallback;
    private AccessibilityCallbacks mCallbacks;

    private final Rect mNormalBounds = new Rect();
    private final Rect mExpandedBounds = new Rect();
    private final Rect mNormalMovementBounds = new Rect();
    private final Rect mExpandedMovementBounds = new Rect();
    private Rect mTmpBounds = new Rect();

    public PipAccessibilityInteractionConnection(Context context, PipMotionHelper motionHelper,
            PipTaskOrganizer taskOrganizer, PipSnapAlgorithm snapAlgorithm,
            AccessibilityCallbacks callbacks, Runnable updateMovementBoundCallback,
            Handler handler) {
        mContext = context;
        mHandler = handler;
        mMotionHelper = motionHelper;
        mTaskOrganizer = taskOrganizer;
        mSnapAlgorithm = snapAlgorithm;
        mUpdateMovementBoundCallback = updateMovementBoundCallback;
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

            // R constants are not final so this cannot be put in the switch-case.
            if (action == R.id.action_pip_resize) {
                if (mMotionHelper.getBounds().width() == mNormalBounds.width()
                        && mMotionHelper.getBounds().height() == mNormalBounds.height()) {
                    setToExpandedBounds();
                } else {
                    setToNormalBounds();
                }
                result = true;
            } else {
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
                        mMotionHelper.expandPipToFullscreen();
                        result = true;
                        break;
                    default:
                        // Leave result as false
                }
            }
        }
        try {
            callback.setPerformAccessibilityActionResult(result, interactionId);
        } catch (RemoteException re) {
                /* best effort - ignore */
        }
    }

    private void setToExpandedBounds() {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(
                new Rect(mTaskOrganizer.getLastReportedBounds()), mNormalMovementBounds);
        mSnapAlgorithm.applySnapFraction(mExpandedBounds, mExpandedMovementBounds,
                savedSnapFraction);
        mTaskOrganizer.scheduleFinishResizePip(mExpandedBounds, (Rect bounds) -> {
            mMotionHelper.synchronizePinnedStackBounds();
            mUpdateMovementBoundCallback.run();
        });
    }

    private void setToNormalBounds() {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(
                new Rect(mTaskOrganizer.getLastReportedBounds()), mExpandedMovementBounds);
        mSnapAlgorithm.applySnapFraction(mNormalBounds, mNormalMovementBounds, savedSnapFraction);
        mTaskOrganizer.scheduleFinishResizePip(mNormalBounds, (Rect bounds) -> {
            mMotionHelper.synchronizePinnedStackBounds();
            mUpdateMovementBoundCallback.run();
        });
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

    /**
     * Update the normal and expanded bounds so they can be used for Resize.
     */
    void onMovementBoundsChanged(Rect normalBounds, Rect expandedBounds, Rect normalMovementBounds,
            Rect expandedMovementBounds) {
        mNormalBounds.set(normalBounds);
        mExpandedBounds.set(expandedBounds);
        mNormalMovementBounds.set(normalMovementBounds);
        mExpandedMovementBounds.set(expandedMovementBounds);
    }

    /**
     * Update the Root node with PIP Accessibility action items.
     */
    public static AccessibilityNodeInfo obtainRootAccessibilityNodeInfo(Context context) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_MOVE_WINDOW);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_pip_resize,
                context.getString(R.string.accessibility_action_pip_resize)));
        info.setImportantForAccessibility(true);
        info.setClickable(true);
        info.setVisibleToUser(true);
        return info;
    }

    private List<AccessibilityNodeInfo> getNodeList() {
        if (mAccessibilityNodeInfoList == null) {
            mAccessibilityNodeInfoList = new ArrayList<>(1);
        }
        AccessibilityNodeInfo info = obtainRootAccessibilityNodeInfo(mContext);
        mAccessibilityNodeInfoList.clear();
        mAccessibilityNodeInfoList.add(info);
        return mAccessibilityNodeInfoList;
    }
}
