/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.app.ActivityManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.ui.DismissAllTaskViewsEvent;
import com.android.systemui.recents.events.ui.HideIncompatibleAppOverlayEvent;
import com.android.systemui.recents.events.ui.ShowIncompatibleAppOverlayEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartInitializeDropTargetsEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.shared.recents.model.Task;

import java.util.ArrayList;

/**
 * Handles touch events for a RecentsView.
 */
public class RecentsViewTouchHandler {

    private RecentsView mRv;

    @ViewDebug.ExportedProperty(deepExport=true, prefix="drag_task")
    private Task mDragTask;
    @ViewDebug.ExportedProperty(deepExport=true, prefix="drag_task_view_")
    private TaskView mTaskView;

    @ViewDebug.ExportedProperty(category="recents")
    private Point mTaskViewOffset = new Point();
    @ViewDebug.ExportedProperty(category="recents")
    private Point mDownPos = new Point();
    @ViewDebug.ExportedProperty(category="recents")
    private boolean mDragRequested;
    @ViewDebug.ExportedProperty(category="recents")
    private boolean mIsDragging;
    private float mDragSlop;
    private int mDeviceId = -1;

    private DropTarget mLastDropTarget;
    private DividerSnapAlgorithm mDividerSnapAlgorithm;
    private ArrayList<DropTarget> mDropTargets = new ArrayList<>();
    private ArrayList<DockState> mVisibleDockStates = new ArrayList<>();

    public RecentsViewTouchHandler(RecentsView rv) {
        mRv = rv;
        mDragSlop = ViewConfiguration.get(rv.getContext()).getScaledTouchSlop();
        updateSnapAlgorithm();
    }

    private void updateSnapAlgorithm() {
        Rect insets = new Rect();
        SystemServicesProxy.getInstance(mRv.getContext()).getStableInsets(insets);
        mDividerSnapAlgorithm = DividerSnapAlgorithm.create(mRv.getContext(), insets);
    }

    /**
     * Registers a new drop target for the current drag only.
     */
    public void registerDropTargetForCurrentDrag(DropTarget target) {
        mDropTargets.add(target);
    }

    /**
     * Returns the set of visible dock states for this current drag.
     */
    public ArrayList<DockState> getVisibleDockStates() {
        return mVisibleDockStates;
    }

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return handleTouchEvent(ev) || mDragRequested;
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        handleTouchEvent(ev);
        if (ev.getAction() == MotionEvent.ACTION_UP && mRv.getStack().getTaskCount() == 0) {
            EventBus.getDefault().send(new HideRecentsEvent(false, true));
        }
        return true;
    }

    /**** Events ****/

    public final void onBusEvent(DragStartEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        mRv.getParent().requestDisallowInterceptTouchEvent(true);
        mDragRequested = true;
        // We defer starting the actual drag handling until the user moves past the drag slop
        mIsDragging = false;
        mDragTask = event.task;
        mTaskView = event.taskView;
        mDropTargets.clear();

        int[] recentsViewLocation = new int[2];
        mRv.getLocationInWindow(recentsViewLocation);
        mTaskViewOffset.set(mTaskView.getLeft() - recentsViewLocation[0] + event.tlOffset.x,
                mTaskView.getTop() - recentsViewLocation[1] + event.tlOffset.y);

        // Change space coordinates relative to the view to RecentsView when user initiates a touch
        if (event.isUserTouchInitiated) {
            float x = mDownPos.x - mTaskViewOffset.x;
            float y = mDownPos.y - mTaskViewOffset.y;
            mTaskView.setTranslationX(x);
            mTaskView.setTranslationY(y);
        }

        mVisibleDockStates.clear();
        if (ActivityManager.supportsMultiWindow(mRv.getContext()) && !ssp.hasDockedTask()
                && mDividerSnapAlgorithm.isSplitScreenFeasible()) {
            Recents.logDockAttempt(mRv.getContext(), event.task.getTopComponent(),
                    event.task.resizeMode);
            if (!event.task.isDockable) {
                EventBus.getDefault().send(new ShowIncompatibleAppOverlayEvent());
            } else {
                // Add the dock state drop targets (these take priority)
                DockState[] dockStates = Recents.getConfiguration()
                        .getDockStatesForCurrentOrientation();
                for (DockState dockState : dockStates) {
                    registerDropTargetForCurrentDrag(dockState);
                    dockState.update(mRv.getContext());
                    mVisibleDockStates.add(dockState);
                }
            }
        }

        // Request other drop targets to register themselves
        EventBus.getDefault().send(new DragStartInitializeDropTargetsEvent(event.task,
                event.taskView, this));
        if (mDeviceId != -1) {
            InputDevice device = InputDevice.getDevice(mDeviceId);
            if (device != null) {
                device.setPointerType(PointerIcon.TYPE_GRABBING);
            }
        }
    }

    public final void onBusEvent(DragEndEvent event) {
        if (!mDragTask.isDockable) {
            EventBus.getDefault().send(new HideIncompatibleAppOverlayEvent());
        }
        mDragRequested = false;
        mDragTask = null;
        mTaskView = null;
        mLastDropTarget = null;
    }

    public final void onBusEvent(ConfigurationChangedEvent event) {
        if (event.fromDisplayDensityChange || event.fromDeviceOrientationChange) {
            updateSnapAlgorithm();
        }
    }

    void cancelStackActionButtonClick() {
        mRv.getStackActionButton().setPressed(false);
    }

    private boolean isWithinStackActionButton(float x, float y) {
        Rect rect = mRv.getStackActionButtonBoundsFromStackLayout();
        return mRv.getStackActionButton().getVisibility() == View.VISIBLE &&
                mRv.getStackActionButton().pointInView(x - rect.left, y - rect.top, 0 /* slop */);
    }

    private void changeStackActionButtonDrawableHotspot(float x, float y) {
        Rect rect = mRv.getStackActionButtonBoundsFromStackLayout();
        mRv.getStackActionButton().drawableHotspotChanged(x - rect.left, y - rect.top);
    }

    /**
     * Handles dragging touch events
     */
    private boolean handleTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        boolean consumed = false;
        float evX = ev.getX();
        float evY = ev.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownPos.set((int) evX, (int) evY);
                mDeviceId = ev.getDeviceId();

                if (isWithinStackActionButton(evX, evY)) {
                    changeStackActionButtonDrawableHotspot(evX, evY);
                    mRv.getStackActionButton().setPressed(true);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                float x = evX - mTaskViewOffset.x;
                float y = evY - mTaskViewOffset.y;

                if (mRv.getStackActionButton().isPressed() && isWithinStackActionButton(evX, evY)) {
                    changeStackActionButtonDrawableHotspot(evX, evY);
                }

                if (mDragRequested) {
                    if (!mIsDragging) {
                        mIsDragging = Math.hypot(evX - mDownPos.x, evY - mDownPos.y) > mDragSlop;
                    }
                    if (mIsDragging) {
                        int width = mRv.getMeasuredWidth();
                        int height = mRv.getMeasuredHeight();

                        DropTarget currentDropTarget = null;

                        // Give priority to the current drop target to retain the touch handling
                        if (mLastDropTarget != null) {
                            if (mLastDropTarget.acceptsDrop((int) evX, (int) evY, width, height,
                                    mRv.mSystemInsets, true /* isCurrentTarget */)) {
                                currentDropTarget = mLastDropTarget;
                            }
                        }

                        // Otherwise, find the next target to handle this event
                        if (currentDropTarget == null) {
                            for (DropTarget target : mDropTargets) {
                                if (target.acceptsDrop((int) evX, (int) evY, width, height,
                                        mRv.mSystemInsets, false /* isCurrentTarget */)) {
                                    currentDropTarget = target;
                                    break;
                                }
                            }
                        }
                        if (mLastDropTarget != currentDropTarget) {
                            mLastDropTarget = currentDropTarget;
                            EventBus.getDefault().send(new DragDropTargetChangedEvent(mDragTask,
                                    currentDropTarget));
                        }
                    }
                    mTaskView.setTranslationX(x);
                    mTaskView.setTranslationY(y);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mRv.getStackActionButton().isPressed() && isWithinStackActionButton(evX, evY)) {
                    EventBus.getDefault().send(new DismissAllTaskViewsEvent());
                    consumed = true;
                }
                cancelStackActionButtonClick();
                if (mDragRequested) {
                    boolean cancelled = action == MotionEvent.ACTION_CANCEL;
                    if (cancelled) {
                        EventBus.getDefault().send(new DragDropTargetChangedEvent(mDragTask, null));
                    }
                    EventBus.getDefault().send(new DragEndEvent(mDragTask, mTaskView,
                            !cancelled ? mLastDropTarget : null));
                    break;
                }
                mDeviceId = -1;
            }
        }
        return consumed;
    }
}
