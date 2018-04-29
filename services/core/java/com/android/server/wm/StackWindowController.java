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

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayCutout;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

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

    private final int mStackId;

    private final H mHandler;

    // Temp bounds only used in adjustConfigurationForBounds()
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpStableInsets = new Rect();
    private final Rect mTmpNonDecorInsets = new Rect();
    private final Rect mTmpDisplayBounds = new Rect();

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

        synchronized (mWindowMap) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);
            if (dc == null) {
                throw new IllegalArgumentException("Trying to add stackId=" + stackId
                        + " to unknown displayId=" + displayId);
            }

            dc.createStack(stackId, onTop, this);
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

    public void reparent(int displayId, Rect outStackBounds, boolean onTop) {
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

            targetDc.moveStackToDisplay(mContainer, onTop);
            getRawBounds(outStackBounds);
        }
    }

    public void positionChildAt(TaskWindowContainerController child, int position) {
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
            child.mContainer.positionAt(position);
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

    public void positionChildAtBottom(TaskWindowContainerController child,
            boolean includingParents) {
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
            mContainer.positionChildAt(POSITION_BOTTOM, childTask, includingParents);

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
     * @param taskBounds Bounds for tasks in the resized stack, keyed by task id.
     * @param taskTempInsetBounds Inset bounds for individual tasks, keyed by task id.
     */
    public void resize(Rect bounds, SparseArray<Rect> taskBounds,
            SparseArray<Rect> taskTempInsetBounds) {
        synchronized (mWindowMap) {
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
    }

    /**
     * @see TaskStack.getStackDockedModeBoundsLocked(Rect, Rect, Rect, boolean)
     */
   public void getStackDockedModeBounds(Rect currentTempTaskBounds, Rect outStackBounds,
           Rect outTempTaskBounds, boolean ignoreVisibility) {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.getStackDockedModeBoundsLocked(currentTempTaskBounds, outStackBounds,
                        outTempTaskBounds, ignoreVisibility);
                return;
            }
            outStackBounds.setEmpty();
            outTempTaskBounds.setEmpty();
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

    public void getRawBounds(Rect outBounds) {
        synchronized (mWindowMap) {
            if (mContainer.matchParentBounds()) {
                outBounds.setEmpty();
            } else {
                mContainer.getRawBounds(outBounds);
            }
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

    public void getBoundsForNewConfiguration(Rect outBounds) {
        synchronized(mWindowMap) {
            mContainer.getBoundsForNewConfiguration(outBounds);
        }
    }

    /**
     * Adjusts the screen size in dp's for the {@param config} for the given params. The provided
     * params represent the desired state of a configuration change. Since this utility is used
     * before mContainer has been updated, any relevant properties (like {@param windowingMode})
     * need to be passed in.
     */
    public void adjustConfigurationForBounds(Rect bounds, Rect insetBounds,
            Rect nonDecorBounds, Rect stableBounds, boolean overrideWidth,
            boolean overrideHeight, float density, Configuration config,
            Configuration parentConfig, int windowingMode) {
        synchronized (mWindowMap) {
            final TaskStack stack = mContainer;
            final DisplayContent displayContent = stack.getDisplayContent();
            final DisplayInfo di = displayContent.getDisplayInfo();
            final DisplayCutout displayCutout = di.displayCutout;

            // Get the insets and display bounds
            mService.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                    displayCutout, mTmpStableInsets);
            mService.mPolicy.getNonDecorInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                    displayCutout, mTmpNonDecorInsets);
            mTmpDisplayBounds.set(0, 0, di.logicalWidth, di.logicalHeight);

            int width;
            int height;

            final Rect parentAppBounds = parentConfig.windowConfiguration.getAppBounds();

            config.windowConfiguration.setBounds(bounds);
            config.windowConfiguration.setAppBounds(!bounds.isEmpty() ? bounds : null);
            boolean intersectParentBounds = false;

            if (WindowConfiguration.isFloating(windowingMode)) {
                // Floating tasks should not be resized to the screen's bounds.

                if (windowingMode == WindowConfiguration.WINDOWING_MODE_PINNED
                        && bounds.width() == mTmpDisplayBounds.width()
                        && bounds.height() == mTmpDisplayBounds.height()) {
                    // If the bounds we are animating is the same as the fullscreen stack
                    // dimensions, then apply the same inset calculations that we normally do for
                    // the fullscreen stack, without intersecting it with the display bounds
                    stableBounds.inset(mTmpStableInsets);
                    nonDecorBounds.inset(mTmpNonDecorInsets);
                    // Move app bounds to zero to apply intersection with parent correctly. They are
                    // used only for evaluating width and height, so it's OK to move them around.
                    config.windowConfiguration.getAppBounds().offsetTo(0, 0);
                    intersectParentBounds = true;
                }
                width = (int) (stableBounds.width() / density);
                height = (int) (stableBounds.height() / density);
            } else {
                // For calculating screenWidthDp, screenWidthDp, we use the stable inset screen
                // area, i.e. the screen area without the system bars.
                // Additionally task dimensions should not be bigger than its parents dimensions.
                // The non decor inset are areas that could never be removed in Honeycomb. See
                // {@link WindowManagerPolicy#getNonDecorInsetsLw}.
                intersectDisplayBoundsExcludeInsets(nonDecorBounds,
                        insetBounds != null ? insetBounds : bounds, mTmpNonDecorInsets,
                        mTmpDisplayBounds, overrideWidth, overrideHeight);
                intersectDisplayBoundsExcludeInsets(stableBounds,
                        insetBounds != null ? insetBounds : bounds, mTmpStableInsets,
                        mTmpDisplayBounds, overrideWidth, overrideHeight);
                width = Math.min((int) (stableBounds.width() / density),
                        parentConfig.screenWidthDp);
                height = Math.min((int) (stableBounds.height() / density),
                        parentConfig.screenHeightDp);
                intersectParentBounds = true;
            }

            if (intersectParentBounds && config.windowConfiguration.getAppBounds() != null) {
                config.windowConfiguration.getAppBounds().intersect(parentAppBounds);
            }

            config.screenWidthDp = width;
            config.screenHeightDp = height;
            config.smallestScreenWidthDp = getSmallestWidthForTaskBounds(
                    insetBounds != null ? insetBounds : bounds, density, windowingMode);
        }
    }

    /**
     * Intersects the specified {@code inOutBounds} with the display frame that excludes the stable
     * inset areas.
     *
     * @param inOutBounds The inOutBounds to subtract the stable inset areas from.
     */
    private void intersectDisplayBoundsExcludeInsets(Rect inOutBounds, Rect inInsetBounds,
            Rect stableInsets, Rect displayBounds, boolean overrideWidth, boolean overrideHeight) {
        mTmpRect.set(inInsetBounds);
        mService.intersectDisplayInsetBounds(displayBounds, stableInsets, mTmpRect);
        int leftInset = mTmpRect.left - inInsetBounds.left;
        int topInset = mTmpRect.top - inInsetBounds.top;
        int rightInset = overrideWidth ? 0 : inInsetBounds.right - mTmpRect.right;
        int bottomInset = overrideHeight ? 0 : inInsetBounds.bottom - mTmpRect.bottom;
        inOutBounds.inset(leftInset, topInset, rightInset, bottomInset);
    }

    /**
     * Calculates the smallest width for a task given the target {@param bounds} and
     * {@param windowingMode}. Avoid using values from mContainer since they can be out-of-date.
     *
     * @return the smallest width to be used in the Configuration, in dips
     */
    private int getSmallestWidthForTaskBounds(Rect bounds, float density, int windowingMode) {
        final DisplayContent displayContent = mContainer.getDisplayContent();
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();

        if (bounds == null || (bounds.width() == displayInfo.logicalWidth &&
                bounds.height() == displayInfo.logicalHeight)) {
            // If the bounds are fullscreen, return the value of the fullscreen configuration
            return displayContent.getConfiguration().smallestScreenWidthDp;
        } else if (WindowConfiguration.isFloating(windowingMode)) {
            // For floating tasks, calculate the smallest width from the bounds of the task
            return (int) (Math.min(bounds.width(), bounds.height()) / density);
        } else {
            // Iterating across all screen orientations, and return the minimum of the task
            // width taking into account that the bounds might change because the snap algorithm
            // snaps to a different value
            return displayContent.getDockedDividerController()
                    .getSmallestWidthDpForBounds(bounds);
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
