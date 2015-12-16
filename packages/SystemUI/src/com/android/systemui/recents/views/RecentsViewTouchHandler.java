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

import android.content.res.Configuration;
import android.graphics.Point;
import android.view.MotionEvent;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartInitializeDropTargetsEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;


/**
 * Represents the dock regions for each orientation.
 */
class DockRegion {
    public static TaskStack.DockState[] PHONE_LANDSCAPE = {
            // We only allow docking to the left for now on small devices
            TaskStack.DockState.LEFT
    };
    public static TaskStack.DockState[] PHONE_PORTRAIT = {
            // We only allow docking to the top for now on small devices
            TaskStack.DockState.TOP
    };
    public static TaskStack.DockState[] TABLET_LANDSCAPE = {
            TaskStack.DockState.LEFT,
            TaskStack.DockState.RIGHT
    };
    public static TaskStack.DockState[] TABLET_PORTRAIT = PHONE_PORTRAIT;
}

/**
 * Handles touch events for a RecentsView.
 */
public class RecentsViewTouchHandler {

    private static final String TAG = "RecentsViewTouchHandler";
    private static final boolean DEBUG = false;

    private RecentsView mRv;

    private Task mDragTask;
    private TaskView mTaskView;

    private Point mTaskViewOffset = new Point();
    private Point mDownPos = new Point();
    private boolean mDragging;

    private DropTarget mLastDropTarget;
    private ArrayList<DropTarget> mDropTargets = new ArrayList<>();

    public RecentsViewTouchHandler(RecentsView rv) {
        mRv = rv;
    }

    /**
     * Registers a new drop target for the current drag only.
     */
    public void registerDropTargetForCurrentDrag(DropTarget target) {
        mDropTargets.add(target);
    }

    /**
     * Returns the preferred dock states for the current orientation.
     */
    public TaskStack.DockState[] getDockStatesForCurrentOrientation() {
        boolean isLandscape = mRv.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        RecentsConfiguration config = Recents.getConfiguration();
        TaskStack.DockState[] dockStates = isLandscape ?
                (config.isLargeScreen ? DockRegion.TABLET_LANDSCAPE : DockRegion.PHONE_LANDSCAPE) :
                (config.isLargeScreen ? DockRegion.TABLET_PORTRAIT : DockRegion.PHONE_PORTRAIT);
        return dockStates;
    }

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        handleTouchEvent(ev);
        return mDragging;
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        handleTouchEvent(ev);
        return mDragging;
    }

    /**** Events ****/

    public final void onBusEvent(DragStartEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        mRv.getParent().requestDisallowInterceptTouchEvent(true);
        mDragging = true;
        mDragTask = event.task;
        mTaskView = event.taskView;
        mDropTargets.clear();

        int[] recentsViewLocation = new int[2];
        mRv.getLocationInWindow(recentsViewLocation);
        mTaskViewOffset.set(mTaskView.getLeft() - recentsViewLocation[0] + event.tlOffset.x,
                mTaskView.getTop() - recentsViewLocation[1] + event.tlOffset.y);
        float x = mDownPos.x - mTaskViewOffset.x;
        float y = mDownPos.y - mTaskViewOffset.y;
        mTaskView.setTranslationX(x);
        mTaskView.setTranslationY(y);

        RecentsConfiguration config = Recents.getConfiguration();
        if (!ssp.hasDockedTask()) {
            // Add the dock state drop targets (these take priority)
            TaskStack.DockState[] dockStates = getDockStatesForCurrentOrientation();
            for (TaskStack.DockState dockState : dockStates) {
                registerDropTargetForCurrentDrag(dockState);
            }
        }

        // Request other drop targets to register themselves
        EventBus.getDefault().send(new DragStartInitializeDropTargetsEvent(event.task, this));
    }

    public final void onBusEvent(DragEndEvent event) {
        mDragging = false;
        mDragTask = null;
        mTaskView = null;
        mLastDropTarget = null;
    }

    /**
     * Handles dragging touch events
     * @param ev
     */
    private void handleTouchEvent(MotionEvent ev) {
        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                mDownPos.set((int) ev.getX(), (int) ev.getY());
                break;
            case MotionEvent.ACTION_MOVE: {
                if (mDragging) {
                    int width = mRv.getMeasuredWidth();
                    int height = mRv.getMeasuredHeight();
                    float evX = ev.getX();
                    float evY = ev.getY();
                    float x = evX - mTaskViewOffset.x;
                    float y = evY - mTaskViewOffset.y;

                    DropTarget currentDropTarget = null;
                    for (DropTarget target : mDropTargets) {
                        if (target.acceptsDrop((int) evX, (int) evY, width, height)) {
                            currentDropTarget = target;
                            break;
                        }
                    }
                    if (mLastDropTarget != currentDropTarget) {
                        mLastDropTarget = currentDropTarget;
                        EventBus.getDefault().send(new DragDropTargetChangedEvent(mDragTask,
                                currentDropTarget));
                    }

                    mTaskView.setTranslationX(x);
                    mTaskView.setTranslationY(y);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragging) {
                    EventBus.getDefault().send(new DragEndEvent(mDragTask, mTaskView,
                            mLastDropTarget));
                    break;
                }
            }
        }
    }
}
