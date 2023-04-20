/*
 * Copyright (C) 2022 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.inMultiWindowMode;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArraySet;
import android.util.Log;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Represents TaskFragments and split pairs below a Task. */
class TaskContainer {
    private static final String TAG = TaskContainer.class.getSimpleName();

    /** The unique task id. */
    private final int mTaskId;

    /** Active TaskFragments in this Task. */
    @NonNull
    final List<TaskFragmentContainer> mContainers = new ArrayList<>();

    /** Active split pairs in this Task. */
    @NonNull
    final List<SplitContainer> mSplitContainers = new ArrayList<>();

    @NonNull
    private final Configuration mConfiguration;

    private int mDisplayId;

    private boolean mIsVisible;

    /**
     * TaskFragments that the organizer has requested to be closed. They should be removed when
     * the organizer receives
     * {@link SplitController#onTaskFragmentVanished(WindowContainerTransaction, TaskFragmentInfo)}
     * event for them.
     */
    final Set<IBinder> mFinishedContainer = new ArraySet<>();

    /**
     * The {@link TaskContainer} constructor
     *
     * @param taskId The ID of the Task, which must match {@link Activity#getTaskId()} with
     *               {@code activityInTask}.
     * @param activityInTask The {@link Activity} in the Task with {@code taskId}. It is used to
     *                       initialize the {@link TaskContainer} properties.
     *
     */
    TaskContainer(int taskId, @NonNull Activity activityInTask) {
        if (taskId == INVALID_TASK_ID) {
            throw new IllegalArgumentException("Invalid Task id");
        }
        mTaskId = taskId;
        final TaskProperties taskProperties = TaskProperties
                .getTaskPropertiesFromActivity(activityInTask);
        mConfiguration = taskProperties.getConfiguration();
        mDisplayId = taskProperties.getDisplayId();
        // Note that it is always called when there's a new Activity is started, which implies
        // the host task is visible.
        mIsVisible = true;
    }

    int getTaskId() {
        return mTaskId;
    }

    int getDisplayId() {
        return mDisplayId;
    }

    boolean isVisible() {
        return mIsVisible;
    }

    @NonNull
    Configuration getConfiguration() {
        // Make a copy in case the config is updated unexpectedly.
        return new Configuration(mConfiguration);
    }

    @NonNull
    TaskProperties getTaskProperties() {
        return new TaskProperties(mDisplayId, mConfiguration);
    }

    void updateTaskFragmentParentInfo(@NonNull TaskFragmentParentInfo info) {
        mConfiguration.setTo(info.getConfiguration());
        mDisplayId = info.getDisplayId();
        mIsVisible = info.isVisible();
    }

    /**
     * Returns the windowing mode for the TaskFragments below this Task, which should be split with
     * other TaskFragments.
     *
     * @param taskFragmentBounds    Requested bounds for the TaskFragment. It will be empty when
     *                              the pair of TaskFragments are stacked due to the limited space.
     */
    @WindowingMode
    int getWindowingModeForSplitTaskFragment(@Nullable Rect taskFragmentBounds) {
        // Only set to multi-windowing mode if the pair are showing side-by-side. Otherwise, it
        // will be set to UNDEFINED which will then inherit the Task windowing mode.
        if (taskFragmentBounds == null || taskFragmentBounds.isEmpty() || isInPictureInPicture()) {
            return WINDOWING_MODE_UNDEFINED;
        }
        // We use WINDOWING_MODE_MULTI_WINDOW when the Task is fullscreen.
        // However, when the Task is in other multi windowing mode, such as Freeform, we need to
        // have the activity windowing mode to match the Task, otherwise things like
        // DecorCaptionView won't work correctly. As a result, have the TaskFragment to be in the
        // Task windowing mode if the Task is in multi window.
        // TODO we won't need this anymore after we migrate Freeform caption to WM Shell.
        return isInMultiWindow() ? getWindowingMode() : WINDOWING_MODE_MULTI_WINDOW;
    }

    boolean isInPictureInPicture() {
        return getWindowingMode() == WINDOWING_MODE_PINNED;
    }

    boolean isInMultiWindow() {
        return WindowConfiguration.inMultiWindowMode(getWindowingMode());
    }

    @WindowingMode
    private int getWindowingMode() {
        return getConfiguration().windowConfiguration.getWindowingMode();
    }

    /** Whether there is any {@link TaskFragmentContainer} below this Task. */
    boolean isEmpty() {
        return mContainers.isEmpty() && mFinishedContainer.isEmpty();
    }

    /** Called when the activity is destroyed. */
    void onActivityDestroyed(@NonNull IBinder activityToken) {
        for (TaskFragmentContainer container : mContainers) {
            container.onActivityDestroyed(activityToken);
        }
    }

    /** Removes the pending appeared activity from all TaskFragments in this Task. */
    void cleanupPendingAppearedActivity(@NonNull IBinder activityToken) {
        for (TaskFragmentContainer container : mContainers) {
            container.removePendingAppearedActivity(activityToken);
        }
    }

    @Nullable
    TaskFragmentContainer getTopTaskFragmentContainer() {
        if (mContainers.isEmpty()) {
            return null;
        }
        return mContainers.get(mContainers.size() - 1);
    }

    @Nullable
    Activity getTopNonFinishingActivity() {
        for (int i = mContainers.size() - 1; i >= 0; i--) {
            final Activity activity = mContainers.get(i).getTopNonFinishingActivity();
            if (activity != null) {
                return activity;
            }
        }
        return null;
    }

    int indexOf(@NonNull TaskFragmentContainer child) {
        return mContainers.indexOf(child);
    }

    /** Whether the Task is in an intermediate state waiting for the server update.*/
    boolean isInIntermediateState() {
        for (TaskFragmentContainer container : mContainers) {
            if (container.isInIntermediateState()) {
                // We are in an intermediate state to wait for server update on this TaskFragment.
                return true;
            }
        }
        return false;
    }

    /** Adds the descriptors of split states in this Task to {@code outSplitStates}. */
    void getSplitStates(@NonNull List<SplitInfo> outSplitStates) {
        for (SplitContainer container : mSplitContainers) {
            outSplitStates.add(container.toSplitInfo());
        }
    }

    /**
     * A wrapper class which contains the display ID and {@link Configuration} of a
     * {@link TaskContainer}
     */
    static final class TaskProperties {
        private final int mDisplayId;
        @NonNull
        private final Configuration mConfiguration;

        TaskProperties(int displayId, @NonNull Configuration configuration) {
            mDisplayId = displayId;
            mConfiguration = configuration;
        }

        int getDisplayId() {
            return mDisplayId;
        }

        @NonNull
        Configuration getConfiguration() {
            return mConfiguration;
        }

        /**
         * Obtains the {@link TaskProperties} for the task that the provided {@link Activity} is
         * associated with.
         * <p>
         * Note that for most case, caller should use
         * {@link SplitPresenter#getTaskProperties(Activity)} instead. This method is used before
         * the {@code activity} goes into split.
         * </p><p>
         * If the {@link Activity} is in fullscreen, override
         * {@link WindowConfiguration#getBounds()} with {@link WindowConfiguration#getMaxBounds()}
         * in case the {@link Activity} is letterboxed. Otherwise, get the Task
         * {@link Configuration} from the server side or use {@link Activity}'s
         * {@link Configuration} as a fallback if the Task {@link Configuration} cannot be obtained.
         */
        @NonNull
        static TaskProperties getTaskPropertiesFromActivity(@NonNull Activity activity) {
            final int displayId = activity.getDisplayId();
            // Use a copy of configuration because activity's configuration may be updated later,
            // or we may get unexpected TaskContainer's configuration if Activity's configuration is
            // updated. An example is Activity is going to be in split.
            final Configuration activityConfig = new Configuration(
                    activity.getResources().getConfiguration());
            final WindowConfiguration windowConfiguration = activityConfig.windowConfiguration;
            final int windowingMode = windowConfiguration.getWindowingMode();
            if (!inMultiWindowMode(windowingMode)) {
                // Use the max bounds in fullscreen in case the Activity is letterboxed.
                windowConfiguration.setBounds(windowConfiguration.getMaxBounds());
                return new TaskProperties(displayId, activityConfig);
            }
            final Configuration taskConfig = ActivityClient.getInstance()
                    .getTaskConfiguration(activity.getActivityToken());
            if (taskConfig == null) {
                Log.w(TAG, "Could not obtain task configuration for activity:" + activity);
                // Still report activity config if task config cannot be obtained from the server
                // side.
                return new TaskProperties(displayId, activityConfig);
            }
            return new TaskProperties(displayId, taskConfig);
        }
    }
}
