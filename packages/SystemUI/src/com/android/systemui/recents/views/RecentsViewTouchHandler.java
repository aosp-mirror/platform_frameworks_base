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
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.ui.dragndrop.DragDockStateChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;


/**
 * Represents the dock regions for each orientation.
 */
class DockRegion {
    public static TaskStack.DockState[] LANDSCAPE = {
            TaskStack.DockState.LEFT, TaskStack.DockState.RIGHT
    };
    public static TaskStack.DockState[] PORTRAIT = {
            // We only allow docking to the top for now
            TaskStack.DockState.TOP
    };
}

/**
 * Handles touch events for a RecentsView.
 */
class RecentsViewTouchHandler {

    private RecentsView mRv;

    private Task mDragTask;
    private TaskView mTaskView;
    private DragView mDragView;

    private Point mDownPos = new Point();
    private boolean mDragging;
    private TaskStack.DockState mLastDockState = TaskStack.DockState.NONE;

    public RecentsViewTouchHandler(RecentsView rv) {
        mRv = rv;
    }

    public TaskStack.DockState getPreferredDockStateForCurrentOrientation() {
        boolean isLandscape = mRv.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        TaskStack.DockState[] dockStates = isLandscape ?
                DockRegion.LANDSCAPE : DockRegion.PORTRAIT;
        return dockStates[0];
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
        mRv.getParent().requestDisallowInterceptTouchEvent(true);
        mDragging = true;
        mDragTask = event.task;
        mTaskView = event.taskView;
        mDragView = event.dragView;

        float x = mDownPos.x - mDragView.getTopLeftOffset().x;
        float y = mDownPos.y - mDragView.getTopLeftOffset().y;
        mDragView.setTranslationX(x);
        mDragView.setTranslationY(y);
    }

    public final void onBusEvent(DragEndEvent event) {
        mDragging = false;
        mDragTask = null;
        mTaskView = null;
        mDragView = null;
        mLastDockState = TaskStack.DockState.NONE;
    }

    /**
     * Handles dragging touch events
     * @param ev
     */
    private void handleTouchEvent(MotionEvent ev) {
        boolean isLandscape = mRv.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
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
                    float x = evX - mDragView.getTopLeftOffset().x;
                    float y = evY - mDragView.getTopLeftOffset().y;

                    // Update the dock state
                    TaskStack.DockState[] dockStates = isLandscape ?
                            DockRegion.LANDSCAPE : DockRegion.PORTRAIT;
                    TaskStack.DockState foundDockState = TaskStack.DockState.NONE;
                    for (int i = 0; i < dockStates.length; i++) {
                        TaskStack.DockState state = dockStates[i];
                        if (state.touchAreaContainsPoint(width, height, evX, evY)) {
                            foundDockState = state;
                            break;
                        }
                    }
                    if (mLastDockState != foundDockState) {
                        mLastDockState = foundDockState;
                        EventBus.getDefault().send(new DragDockStateChangedEvent(mDragTask,
                                foundDockState));
                    }

                    mDragView.setTranslationX(x);
                    mDragView.setTranslationY(y);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragging) {
                    ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger(
                            mRv.getContext(), null, null, null);
                    postAnimationTrigger.increment();
                    EventBus.getDefault().send(new DragEndEvent(mDragTask, mTaskView, mDragView,
                            mLastDockState, postAnimationTrigger));
                    postAnimationTrigger.decrement();
                    break;
                }
            }
        }
    }
}
