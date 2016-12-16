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

import android.app.RemoteAction;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.util.SparseArray;
import com.android.server.UiThread;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.List;

import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

/**
 * Controller for the stack container. This is created by activity manager to link activity stacks
 * to the stack container they use in window manager.
 *
 * Test class: {@link StackWindowControllerTests}
 */
public class StackWindowController
        extends WindowContainerController<TaskStack, StackWindowListener> {

    final int mStackId;

    private final H mHandler;

    public StackWindowController(int stackId, StackWindowListener listener,
            int displayId, boolean onTop, Rect outBounds) {
        this(stackId, listener, displayId, onTop, outBounds, WindowManagerService.getInstance());
    }

    @VisibleForTesting
    public StackWindowController(int stackId, StackWindowListener listener,
            int displayId, boolean onTop, Rect outBounds, WindowManagerService service) {
        super(listener, service);
        mStackId = stackId;
        mHandler = new H(new WeakReference<>(this), service.mH.getLooper());

        synchronized (mWindowMap) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);
            if (dc == null) {
                throw new IllegalArgumentException("Trying to add stackId=" + stackId
                        + " to unknown displayId=" + displayId);
            }

            final TaskStack stack = dc.addStackToDisplay(stackId, onTop);
            stack.setController(this);
            getRawBounds(outBounds);
        }
    }

    @Override
    public void removeContainer() {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.removeIfPossible();
                super.removeContainer();
            }
        }
    }

    public void reparent(int displayId, Rect outStackBounds) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("Trying to move unknown stackId=" + mStackId
                        + " to displayId=" + displayId);
            }

            final DisplayContent targetDc = mRoot.getDisplayContent(displayId);
            if (targetDc == null) {
                throw new IllegalArgumentException("Trying to move stackId=" + mStackId
                        + " to unknown displayId=" + displayId);
            }

            targetDc.moveStackToDisplay(mContainer);
            getRawBounds(outStackBounds);
        }
    }

    public void positionChildAt(TaskWindowContainerController child, int position, Rect bounds,
            Configuration overrideConfig) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "positionChildAt: positioning task=" + child
                    + " at " + position);
            if (child.mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionChildAt: could not find task=" + this);
                return;
            }
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionChildAt: could not find stack for task=" + mContainer);
                return;
            }
            child.mContainer.positionAt(position, bounds, overrideConfig);
            mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    public void positionChildAtTop(TaskWindowContainerController child, boolean includingParents) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        synchronized(mWindowMap) {
            final Task childTask = child.mContainer;
            if (childTask == null) {
                Slog.e(TAG_WM, "positionChildAtTop: task=" + child + " not found");
                return;
            }
            mContainer.positionChildAt(POSITION_TOP, childTask, includingParents);

            if (mService.mAppTransition.isTransitionSet()) {
                childTask.setSendingToBottom(false);
            }
            mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    public void positionChildAtBottom(TaskWindowContainerController child) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        synchronized(mWindowMap) {
            final Task childTask = child.mContainer;
            if (childTask == null) {
                Slog.e(TAG_WM, "positionChildAtBottom: task=" + child + " not found");
                return;
            }
            mContainer.positionChildAt(POSITION_BOTTOM, childTask, false /* includingParents */);

            if (mService.mAppTransition.isTransitionSet()) {
                childTask.setSendingToBottom(true);
            }
            mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    /**
     * Re-sizes a stack and its containing tasks.
     *
     * @param bounds New stack bounds. Passing in null sets the bounds to fullscreen.
     * @param configs Configurations for tasks in the resized stack, keyed by task id.
     * @param taskBounds Bounds for tasks in the resized stack, keyed by task id.
     * @return True if the stack is now fullscreen.
     */
    public boolean resize(Rect bounds, SparseArray<Configuration> configs,
            SparseArray<Rect> taskBounds, SparseArray<Rect> taskTempInsetBounds) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("resizeStack: stack " + this + " not found.");
            }
            // We might trigger a configuration change. Save the current task bounds for freezing.
            mContainer.prepareFreezingTaskBounds();
            if (mContainer.setBounds(bounds, configs, taskBounds, taskTempInsetBounds)
                    && mContainer.isVisible()) {
                mContainer.getDisplayContent().setLayoutNeeded();
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
            return mContainer.getRawFullscreen();
        }
    }

    // TODO: This and similar methods should be separated into PinnedStackWindowContainerController
    public void animateResizePinnedStack(final Rect bounds, final int animationDuration) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("Pinned stack container not found :(");
            }
            final Rect originalBounds = new Rect();
            mContainer.getBounds(originalBounds);
            mContainer.setAnimatingBounds(bounds);
            UiThread.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    mService.mBoundsAnimationController.animateBounds(
                            mContainer, originalBounds, bounds, animationDuration);
                }
            });
        }
    }

    /** Sets the current picture-in-picture aspect ratio. */
    public void setPictureInPictureAspectRatio(float aspectRatio) {
        synchronized (mWindowMap) {
            if (!mService.mSupportsPictureInPicture || mContainer == null) {
                return;
            }

            final int displayId = mContainer.getDisplayContent().getDisplayId();
            final Rect toBounds = mService.getPictureInPictureBounds(displayId, aspectRatio);
            animateResizePinnedStack(toBounds, -1 /* duration */);
        }
    }

    public void getStackDockedModeBounds(Rect outBounds, boolean ignoreVisibility) {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.getStackDockedModeBoundsLocked(outBounds, ignoreVisibility);
                return;
            }
            outBounds.setEmpty();
        }
    }

    public void prepareFreezingTaskBounds() {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("prepareFreezingTaskBounds: stack " + this
                        + " not found.");
            }
            mContainer.prepareFreezingTaskBounds();
        }
    }

    /** Sets the current picture-in-picture actions. */
    public void setPictureInPictureActions(List<RemoteAction> actions) {
        synchronized (mWindowMap) {
            if (!mService.mSupportsPictureInPicture || mContainer == null) {
                return;
            }

            mContainer.getDisplayContent().getPinnedStackController().setActions(actions);
        }
    }

    private void getRawBounds(Rect outBounds) {
        if (mContainer.getRawFullscreen()) {
            outBounds.setEmpty();
        } else {
            mContainer.getRawBounds(outBounds);
        }
    }

    public void getBounds(Rect outBounds) {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.getBounds(outBounds);
                return;
            }
            outBounds.setEmpty();
        }
    }

    public Rect getBoundsForNewConfiguration() {
        synchronized(mWindowMap) {
            final Rect outBounds = new Rect();
            mContainer.getBoundsForNewConfiguration(outBounds);
            return outBounds;
        }
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
