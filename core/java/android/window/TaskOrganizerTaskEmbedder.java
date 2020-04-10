/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.window;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityView;
import android.app.TaskStackListener;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceControl;

/**
 * A component which handles embedded display of tasks within another window. The embedded task can
 * be presented using the SurfaceControl provided from {@link #getSurfaceControl()}.
 *
 * @hide
 */
public class TaskOrganizerTaskEmbedder extends TaskEmbedder {
    private static final String TAG = "TaskOrgTaskEmbedder";
    private static final boolean DEBUG = false;

    private TaskOrganizer mTaskOrganizer;
    private ActivityManager.RunningTaskInfo mTaskInfo;
    private WindowContainerToken mTaskToken;
    private SurfaceControl mTaskLeash;
    private boolean mPendingNotifyBoundsChanged;

    /**
     * Constructs a new TaskEmbedder.
     *
     * @param context the context
     * @param host the host for this embedded task
     */
    public TaskOrganizerTaskEmbedder(Context context, TaskOrganizerTaskEmbedder.Host host) {
        super(context, host);
    }

    @Override
    public TaskStackListener createTaskStackListener() {
        return new TaskStackListenerImpl();
    }

    /**
     * Whether this container has been initialized.
     *
     * @return true if initialized
     */
    @Override
    public boolean isInitialized() {
        return mTaskOrganizer != null;
    }

    @Override
    public boolean onInitialize() {
        if (DEBUG) {
            log("onInitialize");
        }
        // Register the task organizer
        mTaskOrganizer =  new TaskOrganizerImpl();
        // TODO(wm-shell): This currently prevents other organizers from controlling MULT_WINDOW
        // windowing mode tasks. Plan is to migrate this to a wm-shell front-end when that
        // infrastructure is ready.
        mTaskOrganizer.registerOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        mTaskOrganizer.setInterceptBackPressedOnTaskRoot(true);
        return true;
    }

    @Override
    protected boolean onRelease() {
        if (DEBUG) {
            log("onRelease");
        }
        if (!isInitialized()) {
            return false;
        }
        mTaskOrganizer.unregisterOrganizer();
        resetTaskInfo();
        return true;
    }

    /**
     * Starts presentation of tasks in this container.
     */
    @Override
    public void start() {
        if (DEBUG) {
            log("start");
        }
        if (!isInitialized()) {
            return;
        }
        if (mTaskToken == null) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(mTaskToken, false /* hidden */);
        WindowOrganizer.applyTransaction(wct);
        // TODO(b/151449487): Only call callback once we enable synchronization
        if (mListener != null) {
            mListener.onTaskVisibilityChanged(getTaskId(), true);
        }
    }

    /**
     * Stops presentation of tasks in this container.
     */
    @Override
    public void stop() {
        if (DEBUG) {
            log("stop");
        }
        if (!isInitialized()) {
            return;
        }
        if (mTaskToken == null) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(mTaskToken, true /* hidden */);
        WindowOrganizer.applyTransaction(wct);
        // TODO(b/151449487): Only call callback once we enable synchronization
        if (mListener != null) {
            mListener.onTaskVisibilityChanged(getTaskId(), false);
        }
    }

    /**
     * This should be called whenever the position or size of the surface changes
     * or if touchable areas above the surface are added or removed.
     */
    @Override
    public void notifyBoundsChanged() {
        if (DEBUG) {
            log("notifyBoundsChanged: screenBounds=" + mHost.getScreenBounds());
        }
        if (mTaskToken == null) {
            mPendingNotifyBoundsChanged = true;
            return;
        }
        mPendingNotifyBoundsChanged = false;

        // Update based on the screen bounds
        Rect screenBounds = mHost.getScreenBounds();
        if (screenBounds.left < 0 || screenBounds.top < 0) {
            screenBounds.offsetTo(0, 0);
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mTaskToken, screenBounds);
        // TODO(b/151449487): Enable synchronization
        WindowOrganizer.applyTransaction(wct);
    }

    /**
     * Injects a pair of down/up key events with keycode {@link KeyEvent#KEYCODE_BACK} to the
     * virtual display.
     */
    @Override
    public void performBackPress() {
        // Do nothing, the task org task should already have focus if the caller is not focused
        return;
    }

    /** An opaque unique identifier for this task surface among others being managed by the app. */
    @Override
    public int getId() {
        return getTaskId();
    }

    /**
     * Check if container is ready to launch and create {@link ActivityOptions} to target the
     * virtual display.
     * @param options The existing options to amend, or null if the caller wants new options to be
     *                created
     */
    @Override
    protected ActivityOptions prepareActivityOptions(ActivityOptions options) {
        options = super.prepareActivityOptions(options);
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        return options;
    }

    private int getTaskId() {
        return mTaskInfo != null
                ? mTaskInfo.taskId
                : INVALID_TASK_ID;
    }

    private void resetTaskInfo() {
        if (DEBUG) {
            log("resetTaskInfo");
        }
        mTaskInfo = null;
        mTaskToken = null;
        mTaskLeash = null;
    }

    private void log(String msg) {
        Log.d(TAG, "[" + System.identityHashCode(this) + "] " + msg);
    }

    /**
     * A task change listener that detects background color change of the topmost stack on our
     * virtual display and updates the background of the surface view. This background will be shown
     * when surface view is resized, but the app hasn't drawn its content in new size yet.
     * It also calls StateCallback.onTaskMovedToFront to notify interested parties that the stack
     * associated with the {@link ActivityView} has had a Task moved to the front. This is useful
     * when needing to also bring the host Activity to the foreground at the same time.
     */
    private class TaskStackListenerImpl extends TaskStackListener {

        @Override
        public void onTaskDescriptionChanged(ActivityManager.RunningTaskInfo taskInfo) {
            if (!isInitialized()) {
                return;
            }
            if (taskInfo.taskId == mTaskInfo.taskId) {
                mTaskInfo.taskDescription = taskInfo.taskDescription;
                mHost.onTaskBackgroundColorChanged(TaskOrganizerTaskEmbedder.this,
                        taskInfo.taskDescription.getBackgroundColor());
            }
        }
    }

    private class TaskOrganizerImpl extends TaskOrganizer {
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
            if (DEBUG) {
                log("taskAppeared: " + taskInfo.taskId);
            }

            // TODO: Ensure visibility/alpha of the leash in its initial state?
            mTaskInfo = taskInfo;
            mTaskToken = taskInfo.token;
            mTaskLeash = mTaskToken.getLeash();
            mTransaction.reparent(mTaskLeash, mSurfaceControl)
                    .show(mTaskLeash)
                    .show(mSurfaceControl)
                    .apply();
            if (mPendingNotifyBoundsChanged) {
                // TODO: Either defer show or hide and synchronize show with the resize
                notifyBoundsChanged();
            }
            mHost.post(() -> mHost.onTaskBackgroundColorChanged(TaskOrganizerTaskEmbedder.this,
                    taskInfo.taskDescription.getBackgroundColor()));

            if (mListener != null) {
                mListener.onTaskCreated(taskInfo.taskId, taskInfo.baseActivity);
            }
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            if (DEBUG) {
                log("taskVanished: " + taskInfo.taskId);
            }

            if (mTaskToken != null && (taskInfo == null
                    || mTaskToken.asBinder().equals(taskInfo.token.asBinder()))) {
                if (mListener != null) {
                    mListener.onTaskRemovalStarted(taskInfo.taskId);
                }
                resetTaskInfo();
            }
        }

        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
            if (mListener != null) {
                mListener.onBackPressedOnTaskRoot(taskInfo.taskId);
            }
        }
    }
}
