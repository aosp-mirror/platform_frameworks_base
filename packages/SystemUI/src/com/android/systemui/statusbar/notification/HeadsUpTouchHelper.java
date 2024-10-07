/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.content.Context;
import android.os.RemoteException;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/**
 * A helper class to handle touches on the heads-up views.
 */
public class HeadsUpTouchHelper implements Gefingerpoken {

    private final HeadsUpManager mHeadsUpManager;
    private final IStatusBarService mStatusBarService;
    private final Callback mCallback;
    private int mTrackingPointer;
    private final float mTouchSlop;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private boolean mTouchingHeadsUpView;
    private boolean mTrackingHeadsUp;
    private boolean mCollapseSnoozes;
    private final HeadsUpNotificationViewController mPanel;
    private ExpandableNotificationRow mPickedChild;

    public HeadsUpTouchHelper(HeadsUpManager headsUpManager,
            IStatusBarService statusBarService,
            Callback callback,
            HeadsUpNotificationViewController notificationPanelView) {
        mHeadsUpManager = headsUpManager;
        mStatusBarService = statusBarService;
        mCallback = callback;
        mPanel = notificationPanelView;
        Context context = mCallback.getContext();
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
    }

    public boolean isTrackingHeadsUp() {
        return mTrackingHeadsUp;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!mTouchingHeadsUpView && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mInitialTouchY = y;
                mInitialTouchX = x;
                setTrackingHeadsUp(false);
                ExpandableView child = mCallback.getChildAtRawPosition(x, y);
                mTouchingHeadsUpView = false;
                if (child instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow pickedChild = (ExpandableNotificationRow) child;
                    mTouchingHeadsUpView = !mCallback.isExpanded()
                            && pickedChild.isHeadsUp() && pickedChild.isPinned();
                    if (mTouchingHeadsUpView) {
                        mPickedChild = pickedChild;
                    }
                } else if (child == null && !mCallback.isExpanded()) {
                    // We might touch above the visible heads up child, but then we still would
                    // like to capture it.
                    NotificationEntry topEntry = mHeadsUpManager.getTopEntry();
                    if (topEntry != null && topEntry.isRowPinned()) {
                        mPickedChild = topEntry.getRow();
                        mTouchingHeadsUpView = true;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                if (mTouchingHeadsUpView && Math.abs(h) > mTouchSlop
                        && Math.abs(h) > Math.abs(x - mInitialTouchX)) {
                    setTrackingHeadsUp(true);
                    mCollapseSnoozes = h < 0;
                    mInitialTouchX = x;
                    mInitialTouchY = y;
                    int startHeight = (int) (mPickedChild.getActualHeight()
                                                + mPickedChild.getTranslationY());
                    mPanel.setHeadsUpDraggingStartingHeight(startHeight);
                    mPanel.startExpand(x, y, true /* startTracking */, startHeight);

                    if (!SceneContainerFlag.isEnabled()) {
                        // This call needs to be after the expansion start otherwise we will get a
                        // flicker of one frame as it's not expanded yet.
                        mHeadsUpManager.unpinAll(true);
                    }

                    clearNotificationEffects();
                    endMotion();
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mPickedChild != null && mTouchingHeadsUpView) {
                    // We may swallow this click if the heads up just came in.
                    if (mHeadsUpManager.shouldSwallowClick(
                            mPickedChild.getEntry().getSbn().getKey())) {
                        endMotion();
                        return true;
                    }
                }
                endMotion();
                break;
        }
        return false;
    }

    private void setTrackingHeadsUp(boolean tracking) {
        mTrackingHeadsUp = tracking;
        mHeadsUpManager.setTrackingHeadsUp(tracking);
        mPanel.setTrackedHeadsUp(tracking ? mPickedChild : null);
    }

    public void notifyFling(boolean collapse) {
        if (collapse && mCollapseSnoozes) {
            mHeadsUpManager.snooze();
        }
        mCollapseSnoozes = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mTrackingHeadsUp) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endMotion();
                setTrackingHeadsUp(false);
                break;
        }
        return true;
    }

    private void endMotion() {
        mTrackingPointer = -1;
        mPickedChild = null;
        mTouchingHeadsUpView = false;
    }

    private void clearNotificationEffects() {
        try {
            mStatusBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    public interface Callback {
        ExpandableView getChildAtRawPosition(float touchX, float touchY);
        boolean isExpanded();
        Context getContext();
    }

    /** The controller for a view that houses heads up notifications. */
    public interface HeadsUpNotificationViewController {
        /** Called when a HUN is dragged to indicate the starting height for shade motion. */
        void setHeadsUpDraggingStartingHeight(int startHeight);

        /** Sets notification that is being expanded. */
        void setTrackedHeadsUp(ExpandableNotificationRow expandableNotificationRow);

        /** Called when a MotionEvent is about to trigger expansion. */
        void startExpand(float newX, float newY, boolean startTracking, float expandedHeight);
    }
}
