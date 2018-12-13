/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

/**
 * Controller for the stack container. This is created by activity manager to link activity stacks
 * to the stack container they use in window manager.
 *
 * Test class: {@link StackWindowControllerTests}
 */
public class StackWindowController
        extends WindowContainerController<TaskStack, StackWindowListener> {

    private final int mStackId;

    private final H mHandler;

    final Rect mTmpBounds = new Rect();

    public StackWindowController(int stackId, StackWindowListener listener, int displayId,
            boolean onTop, Rect outBounds) {
        this(stackId, listener, displayId, onTop, outBounds, WindowManagerService.getInstance());
    }

    @VisibleForTesting
    public StackWindowController(int stackId, StackWindowListener listener,
            int displayId, boolean onTop, Rect outBounds, WindowManagerService service) {
        super(listener, service);
        mStackId = stackId;
        mHandler = new H(new WeakReference<>(this), service.mH.getLooper());

        final DisplayContent dc = mRoot.getDisplayContent(displayId);
        if (dc == null) {
            throw new IllegalArgumentException("Trying to add stackId=" + stackId
                    + " to unknown displayId=" + displayId);
        }

        dc.createStack(stackId, onTop, this);
        getRawBounds(outBounds);
    }

    @Override
    public void removeContainer() {
        if (mContainer != null) {
            mContainer.removeIfPossible();
            super.removeContainer();
        }
    }

    void reparent(int displayId, Rect outStackBounds, boolean onTop) {
        if (mContainer == null) {
            throw new IllegalArgumentException("Trying to move unknown stackId=" + mStackId
                    + " to displayId=" + displayId);
        }

        final DisplayContent targetDc = mRoot.getDisplayContent(displayId);
        if (targetDc == null) {
            throw new IllegalArgumentException("Trying to move stackId=" + mStackId
                    + " to unknown displayId=" + displayId);
        }

        targetDc.moveStackToDisplay(mContainer, onTop);
        getRawBounds(outStackBounds);
    }

    void positionChildAt(Task child, int position) {
        if (DEBUG_STACK) {
            Slog.i(TAG_WM, "positionChildAt: positioning task=" + child + " at " + position);
        }
        if (child == null) {
            if (DEBUG_STACK) {
                Slog.i(TAG_WM, "positionChildAt: could not find task=" + this);
            }
            return;
        }
        if (mContainer == null) {
            if (DEBUG_STACK) {
                Slog.i(TAG_WM, "positionChildAt: could not find stack for task=" + mContainer);
            }
            return;
        }
        child.positionAt(position);
        mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    void positionChildAtTop(Task child, boolean includingParents) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        mContainer.positionChildAt(POSITION_TOP, child, includingParents);

        final DisplayContent displayContent = mContainer.getDisplayContent();
        if (displayContent.mAppTransition.isTransitionSet()) {
            child.setSendingToBottom(false);
        }
        displayContent.layoutAndAssignWindowLayersIfNeeded();
    }

    void positionChildAtBottom(Task child, boolean includingParents) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        mContainer.positionChildAt(POSITION_BOTTOM, child, includingParents);

        if (mContainer.getDisplayContent().mAppTransition.isTransitionSet()) {
            child.setSendingToBottom(true);
        }
        mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    /**
     * Re-sizes a stack and its containing tasks.
     *
     * @param bounds New stack bounds. Passing in null sets the bounds to fullscreen.
     * @param taskBounds Bounds for tasks in the resized stack, keyed by task id.
     * @param taskTempInsetBounds Inset bounds for individual tasks, keyed by task id.
     */
    public void resize(Rect bounds, SparseArray<Rect> taskBounds,
            SparseArray<Rect> taskTempInsetBounds) {
        if (mContainer == null) {
            throw new IllegalArgumentException("resizeStack: stack " + this + " not found.");
        }
        // We might trigger a configuration change. Save the current task bounds for freezing.
        mContainer.prepareFreezingTaskBounds();
        if (mContainer.setBounds(bounds, taskBounds, taskTempInsetBounds)
                && mContainer.isVisible()) {
            mContainer.getDisplayContent().setLayoutNeeded();
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    public void onPipAnimationEndResize() {
        mContainer.onPipAnimationEndResize();
    }

    /**
     * @see TaskStack.getStackDockedModeBoundsLocked(ConfigurationContainer, Rect, Rect, Rect)
     */
    public void getStackDockedModeBounds(Configuration parentConfig, Rect dockedBounds,
            Rect currentTempTaskBounds,
            Rect outStackBounds, Rect outTempTaskBounds) {
        if (mContainer != null) {
            mContainer.getStackDockedModeBoundsLocked(parentConfig, dockedBounds,
                    currentTempTaskBounds, outStackBounds, outTempTaskBounds);
            return;
        }
        outStackBounds.setEmpty();
        outTempTaskBounds.setEmpty();
    }

    public void prepareFreezingTaskBounds() {
        if (mContainer == null) {
            throw new IllegalArgumentException("prepareFreezingTaskBounds: stack " + this
                    + " not found.");
        }
        mContainer.prepareFreezingTaskBounds();
    }

    public void getRawBounds(Rect outBounds) {
        if (mContainer.matchParentBounds()) {
            outBounds.setEmpty();
        } else {
            mContainer.getRawBounds(outBounds);
        }
    }

    public void getBounds(Rect outBounds) {
        if (mContainer != null) {
            mContainer.getBounds(outBounds);
            return;
        }
        outBounds.setEmpty();
    }

    void requestResize(Rect bounds) {
        mHandler.obtainMessage(H.REQUEST_RESIZE, bounds).sendToTarget();
    }

    @Override
    public String toString() {
        return "{StackWindowController stackId=" + mStackId + "}";
    }

    private static final class H extends Handler {

        static final int REQUEST_RESIZE = 0;

        private final WeakReference<StackWindowController> mController;

        H(WeakReference<StackWindowController> controller, Looper looper) {
            super(looper);
            mController = controller;
        }

        @Override
        public void handleMessage(Message msg) {
            final StackWindowController controller = mController.get();
            final StackWindowListener listener = (controller != null)
                    ? controller.mListener : null;
            if (listener == null) {
                return;
            }
            switch (msg.what) {
                case REQUEST_RESIZE:
                    listener.requestResize((Rect) msg.obj);
                    break;
            }
        }
    }
}
