/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.util.Size;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-side container for a stack of activities. Corresponds to an instance of TaskFragment
 * on the server side.
 */
// Suppress GuardedBy warning because all the TaskFragmentContainers are stored in
// SplitController.mTaskContainers which is guarded.
@SuppressWarnings("GuardedBy")
class TaskFragmentContainer {
    private static final int APPEAR_EMPTY_TIMEOUT_MS = 3000;

    @NonNull
    private final SplitController mController;

    /**
     * Client-created token that uniquely identifies the task fragment container instance.
     */
    @NonNull
    private final IBinder mToken;

    /** Parent leaf Task. */
    @NonNull
    private final TaskContainer mTaskContainer;

    /**
     * Server-provided task fragment information.
     */
    @VisibleForTesting
    TaskFragmentInfo mInfo;

    /**
     * Activity tokens that are being reparented or being started to this container, but haven't
     * been added to {@link #mInfo} yet.
     */
    @VisibleForTesting
    final ArrayList<IBinder> mPendingAppearedActivities = new ArrayList<>();

    /**
     * When this container is created for an {@link Intent} to start within, we store that Intent
     * until the container becomes non-empty on the server side, so that we can use it to check
     * rules associated with this container.
     */
    @Nullable
    private Intent mPendingAppearedIntent;

    /** Containers that are dependent on this one and should be completely destroyed on exit. */
    private final List<TaskFragmentContainer> mContainersToFinishOnExit =
            new ArrayList<>();

    /**
     * Individual associated activity tokens in different containers that should be finished on
     * exit.
     */
    private final List<IBinder> mActivitiesToFinishOnExit = new ArrayList<>();

    /** Indicates whether the container was cleaned up after the last activity was removed. */
    private boolean mIsFinished;

    /**
     * Bounds that were requested last via {@link android.window.WindowContainerTransaction}.
     */
    private final Rect mLastRequestedBounds = new Rect();

    /**
     * Windowing mode that was requested last via {@link android.window.WindowContainerTransaction}.
     */
    @WindowingMode
    private int mLastRequestedWindowingMode = WINDOWING_MODE_UNDEFINED;

    /**
     * TaskFragmentAnimationParams that was requested last via
     * {@link android.window.WindowContainerTransaction}.
     */
    @NonNull
    private TaskFragmentAnimationParams mLastAnimationParams = TaskFragmentAnimationParams.DEFAULT;

    /**
     * When the TaskFragment has appeared in server, but is empty, we should remove the TaskFragment
     * if it is still empty after the timeout.
     */
    @VisibleForTesting
    @Nullable
    Runnable mAppearEmptyTimeout;

    /**
     * Creates a container with an existing activity that will be re-parented to it in a window
     * container transaction.
     * @param pairedPrimaryContainer    when it is set, the new container will be add right above it
     */
    TaskFragmentContainer(@Nullable Activity pendingAppearedActivity,
            @Nullable Intent pendingAppearedIntent, @NonNull TaskContainer taskContainer,
            @NonNull SplitController controller,
            @Nullable TaskFragmentContainer pairedPrimaryContainer) {
        if ((pendingAppearedActivity == null && pendingAppearedIntent == null)
                || (pendingAppearedActivity != null && pendingAppearedIntent != null)) {
            throw new IllegalArgumentException(
                    "One and only one of pending activity and intent must be non-null");
        }
        mController = controller;
        mToken = new Binder("TaskFragmentContainer");
        mTaskContainer = taskContainer;
        if (pairedPrimaryContainer != null) {
            if (pairedPrimaryContainer.getTaskContainer() != taskContainer) {
                throw new IllegalArgumentException(
                        "pairedPrimaryContainer must be in the same Task");
            }
            final int primaryIndex = taskContainer.mContainers.indexOf(pairedPrimaryContainer);
            taskContainer.mContainers.add(primaryIndex + 1, this);
        } else {
            taskContainer.mContainers.add(this);
        }
        if (pendingAppearedActivity != null) {
            addPendingAppearedActivity(pendingAppearedActivity);
        }
        mPendingAppearedIntent = pendingAppearedIntent;
    }

    /**
     * Returns the client-created token that uniquely identifies this container.
     */
    @NonNull
    IBinder getTaskFragmentToken() {
        return mToken;
    }

    /** List of non-finishing activities that belong to this container and live in this process. */
    @NonNull
    List<Activity> collectNonFinishingActivities() {
        final List<Activity> allActivities = new ArrayList<>();
        if (mInfo != null) {
            // Add activities reported from the server.
            for (IBinder token : mInfo.getActivities()) {
                final Activity activity = mController.getActivity(token);
                if (activity != null && !activity.isFinishing()) {
                    allActivities.add(activity);
                }
            }
        }

        // Add the re-parenting activity, in case the server has not yet reported the task
        // fragment info update with it placed in this container. We still want to apply rules
        // in this intermediate state.
        // Place those on top of the list since they will be on the top after reported from the
        // server.
        for (IBinder token : mPendingAppearedActivities) {
            final Activity activity = mController.getActivity(token);
            if (activity != null && !activity.isFinishing()) {
                allActivities.add(activity);
            }
        }
        return allActivities;
    }

    /** Whether this TaskFragment is visible. */
    boolean isVisible() {
        return mInfo != null && mInfo.isVisible();
    }

    /** Whether the TaskFragment is in an intermediate state waiting for the server update.*/
    boolean isInIntermediateState() {
        if (mInfo == null) {
            // Haven't received onTaskFragmentAppeared event.
            return true;
        }
        if (mInfo.isEmpty()) {
            // Empty TaskFragment will be removed or will have activity launched into it soon.
            return true;
        }
        if (!mPendingAppearedActivities.isEmpty()) {
            // Reparented activity hasn't appeared.
            return true;
        }
        // Check if there is any reported activity that is no longer alive.
        for (IBinder token : mInfo.getActivities()) {
            final Activity activity = mController.getActivity(token);
            if (activity == null && !mTaskContainer.isVisible()) {
                // Activity can be null if the activity is not attached to process yet. That can
                // happen when the activity is started in background.
                continue;
            }
            if (activity == null || activity.isFinishing()) {
                // One of the reported activity is no longer alive, wait for the server update.
                return true;
            }
        }
        return false;
    }

    @NonNull
    ActivityStack toActivityStack() {
        return new ActivityStack(collectNonFinishingActivities(), isEmpty());
    }

    /** Adds the activity that will be reparented to this container. */
    void addPendingAppearedActivity(@NonNull Activity pendingAppearedActivity) {
        final IBinder activityToken = pendingAppearedActivity.getActivityToken();
        if (hasActivity(activityToken)) {
            return;
        }
        // Remove the pending activity from other TaskFragments in case the activity is reparented
        // again before the server update.
        mTaskContainer.cleanupPendingAppearedActivity(activityToken);
        mPendingAppearedActivities.add(activityToken);
        updateActivityClientRecordTaskFragmentToken(activityToken);
    }

    /**
     * Updates the {@link ActivityThread.ActivityClientRecord#mTaskFragmentToken} for the
     * activity. This makes sure the token is up-to-date if the activity is relaunched later.
     */
    private void updateActivityClientRecordTaskFragmentToken(@NonNull IBinder activityToken) {
        final ActivityThread.ActivityClientRecord record = ActivityThread
                .currentActivityThread().getActivityClient(activityToken);
        if (record != null) {
            record.mTaskFragmentToken = mToken;
        }
    }

    void removePendingAppearedActivity(@NonNull IBinder activityToken) {
        mPendingAppearedActivities.remove(activityToken);
    }

    @GuardedBy("mController.mLock")
    void clearPendingAppearedActivities() {
        final List<IBinder> cleanupActivities = new ArrayList<>(mPendingAppearedActivities);
        // Clear mPendingAppearedActivities so that #getContainerWithActivity won't return the
        // current TaskFragment.
        mPendingAppearedActivities.clear();
        mPendingAppearedIntent = null;

        // For removed pending activities, we need to update the them to their previous containers.
        for (IBinder activityToken : cleanupActivities) {
            final TaskFragmentContainer curContainer = mController.getContainerWithActivity(
                    activityToken);
            if (curContainer != null) {
                curContainer.updateActivityClientRecordTaskFragmentToken(activityToken);
            }
        }
    }

    /** Called when the activity is destroyed. */
    void onActivityDestroyed(@NonNull IBinder activityToken) {
        removePendingAppearedActivity(activityToken);
        if (mInfo != null) {
            // Remove the activity now because there can be a delay before the server callback.
            mInfo.getActivities().remove(activityToken);
        }
        mActivitiesToFinishOnExit.remove(activityToken);
    }

    @Nullable
    Intent getPendingAppearedIntent() {
        return mPendingAppearedIntent;
    }

    void setPendingAppearedIntent(@Nullable Intent intent) {
        mPendingAppearedIntent = intent;
    }

    /**
     * Clears the pending appeared Intent if it is the same as given Intent. Otherwise, the
     * pending appeared Intent is cleared when TaskFragmentInfo is set and is not empty (has
     * running activities).
     */
    void clearPendingAppearedIntentIfNeeded(@NonNull Intent intent) {
        if (mPendingAppearedIntent == null || mPendingAppearedIntent != intent) {
            return;
        }
        mPendingAppearedIntent = null;
    }

    boolean hasActivity(@NonNull IBinder activityToken) {
        // Instead of using (hasAppearedActivity() || hasPendingAppearedActivity), we want to make
        // sure the controller considers this container as the one containing the activity.
        // This is needed when the activity is added as pending appeared activity to one
        // TaskFragment while it is also an appeared activity in another.
        return mController.getContainerWithActivity(activityToken) == this;
    }

    /** Whether this activity has appeared in the TaskFragment on the server side. */
    boolean hasAppearedActivity(@NonNull IBinder activityToken) {
        return mInfo != null && mInfo.getActivities().contains(activityToken);
    }

    /**
     * Whether we are waiting for this activity to appear in the TaskFragment on the server side.
     */
    boolean hasPendingAppearedActivity(@NonNull IBinder activityToken) {
        return mPendingAppearedActivities.contains(activityToken);
    }

    int getRunningActivityCount() {
        int count = mPendingAppearedActivities.size();
        if (mInfo != null) {
            count += mInfo.getRunningActivityCount();
        }
        return count;
    }

    /** Whether we are waiting for the TaskFragment to appear and become non-empty. */
    boolean isWaitingActivityAppear() {
        return !mIsFinished && (mInfo == null || mAppearEmptyTimeout != null);
    }

    @Nullable
    TaskFragmentInfo getInfo() {
        return mInfo;
    }

    @GuardedBy("mController.mLock")
    void setInfo(@NonNull WindowContainerTransaction wct, @NonNull TaskFragmentInfo info) {
        if (!mIsFinished && mInfo == null && info.isEmpty()) {
            // onTaskFragmentAppeared with empty info. We will remove the TaskFragment if no
            // pending appeared intent/activities. Otherwise, wait and removing the TaskFragment if
            // it is still empty after timeout.
            if (mPendingAppearedIntent != null || !mPendingAppearedActivities.isEmpty()) {
                mAppearEmptyTimeout = () -> {
                    synchronized (mController.mLock) {
                        mAppearEmptyTimeout = null;
                        // Call without the pass-in wct when timeout. We need to applyWct directly
                        // in this case.
                        mController.onTaskFragmentAppearEmptyTimeout(this);
                    }
                };
                mController.getHandler().postDelayed(mAppearEmptyTimeout, APPEAR_EMPTY_TIMEOUT_MS);
            } else {
                mAppearEmptyTimeout = null;
                mController.onTaskFragmentAppearEmptyTimeout(wct, this);
            }
        } else if (mAppearEmptyTimeout != null && !info.isEmpty()) {
            mController.getHandler().removeCallbacks(mAppearEmptyTimeout);
            mAppearEmptyTimeout = null;
        }

        mInfo = info;
        if (mInfo == null || mInfo.isEmpty()) {
            return;
        }
        // Only track the pending Intent when the container is empty.
        mPendingAppearedIntent = null;
        if (mPendingAppearedActivities.isEmpty()) {
            return;
        }
        // Cleanup activities that were being re-parented
        List<IBinder> infoActivities = mInfo.getActivities();
        for (int i = mPendingAppearedActivities.size() - 1; i >= 0; --i) {
            final IBinder activityToken = mPendingAppearedActivities.get(i);
            if (infoActivities.contains(activityToken)) {
                mPendingAppearedActivities.remove(i);
            }
        }
    }

    @Nullable
    Activity getTopNonFinishingActivity() {
        final List<Activity> activities = collectNonFinishingActivities();
        return activities.isEmpty() ? null : activities.get(activities.size() - 1);
    }

    @Nullable
    Activity getBottomMostActivity() {
        final List<Activity> activities = collectNonFinishingActivities();
        return activities.isEmpty() ? null : activities.get(0);
    }

    boolean isEmpty() {
        return mPendingAppearedActivities.isEmpty() && (mInfo == null || mInfo.isEmpty());
    }

    /**
     * Adds a container that should be finished when this container is finished.
     */
    void addContainerToFinishOnExit(@NonNull TaskFragmentContainer containerToFinish) {
        if (mIsFinished) {
            return;
        }
        mContainersToFinishOnExit.add(containerToFinish);
    }

    /**
     * Removes a container that should be finished when this container is finished.
     */
    void removeContainerToFinishOnExit(@NonNull TaskFragmentContainer containerToRemove) {
        if (mIsFinished) {
            return;
        }
        mContainersToFinishOnExit.remove(containerToRemove);
    }

    /**
     * Adds an activity that should be finished when this container is finished.
     */
    void addActivityToFinishOnExit(@NonNull Activity activityToFinish) {
        if (mIsFinished) {
            return;
        }
        mActivitiesToFinishOnExit.add(activityToFinish.getActivityToken());
    }

    /**
     * Removes an activity that should be finished when this container is finished.
     */
    void removeActivityToFinishOnExit(@NonNull Activity activityToRemove) {
        if (mIsFinished) {
            return;
        }
        mActivitiesToFinishOnExit.remove(activityToRemove.getActivityToken());
    }

    /** Removes all dependencies that should be finished when this container is finished. */
    void resetDependencies() {
        if (mIsFinished) {
            return;
        }
        mContainersToFinishOnExit.clear();
        mActivitiesToFinishOnExit.clear();
    }

    /**
     * Removes all activities that belong to this process and finishes other containers/activities
     * configured to finish together.
     */
    @GuardedBy("mController.mLock")
    void finish(boolean shouldFinishDependent, @NonNull SplitPresenter presenter,
            @NonNull WindowContainerTransaction wct, @NonNull SplitController controller) {
        if (!mIsFinished) {
            mIsFinished = true;
            if (mAppearEmptyTimeout != null) {
                mController.getHandler().removeCallbacks(mAppearEmptyTimeout);
                mAppearEmptyTimeout = null;
            }
            finishActivities(shouldFinishDependent, presenter, wct, controller);
        }

        if (mInfo == null) {
            // Defer removal the container and wait until TaskFragment appeared.
            return;
        }

        // Cleanup the visuals
        presenter.deleteTaskFragment(wct, getTaskFragmentToken());
        // Cleanup the records
        controller.removeContainer(this);
        // Clean up task fragment information
        mInfo = null;
    }

    @GuardedBy("mController.mLock")
    private void finishActivities(boolean shouldFinishDependent, @NonNull SplitPresenter presenter,
            @NonNull WindowContainerTransaction wct, @NonNull SplitController controller) {
        // Finish own activities
        for (Activity activity : collectNonFinishingActivities()) {
            if (!activity.isFinishing()
                    // In case we have requested to reparent the activity to another container (as
                    // pendingAppeared), we don't want to finish it with this container.
                    && mController.getContainerWithActivity(activity) == this) {
                wct.finishActivity(activity.getActivityToken());
            }
        }

        if (!shouldFinishDependent) {
            return;
        }

        // Finish dependent containers
        for (TaskFragmentContainer container : mContainersToFinishOnExit) {
            if (container.mIsFinished
                    || controller.shouldRetainAssociatedContainer(this, container)) {
                continue;
            }
            container.finish(true /* shouldFinishDependent */, presenter,
                    wct, controller);
        }
        mContainersToFinishOnExit.clear();

        // Finish associated activities
        for (IBinder activityToken : mActivitiesToFinishOnExit) {
            final Activity activity = mController.getActivity(activityToken);
            if (activity == null || activity.isFinishing()
                    || controller.shouldRetainAssociatedActivity(this, activity)) {
                continue;
            }
            wct.finishActivity(activity.getActivityToken());
        }
        mActivitiesToFinishOnExit.clear();
    }

    boolean isFinished() {
        return mIsFinished;
    }

    /**
     * Checks if last requested bounds are equal to the provided value.
     */
    boolean areLastRequestedBoundsEqual(@Nullable Rect bounds) {
        return (bounds == null && mLastRequestedBounds.isEmpty())
                || mLastRequestedBounds.equals(bounds);
    }

    /**
     * Updates the last requested bounds.
     */
    void setLastRequestedBounds(@Nullable Rect bounds) {
        if (bounds == null) {
            mLastRequestedBounds.setEmpty();
        } else {
            mLastRequestedBounds.set(bounds);
        }
    }

    @NonNull
    Rect getLastRequestedBounds() {
        return mLastRequestedBounds;
    }

    /**
     * Checks if last requested windowing mode is equal to the provided value.
     */
    boolean isLastRequestedWindowingModeEqual(@WindowingMode int windowingMode) {
        return mLastRequestedWindowingMode == windowingMode;
    }

    /**
     * Updates the last requested windowing mode.
     */
    void setLastRequestedWindowingMode(@WindowingMode int windowingModes) {
        mLastRequestedWindowingMode = windowingModes;
    }

    /**
     * Checks if last requested {@link TaskFragmentAnimationParams} are equal to the provided value.
     */
    boolean areLastRequestedAnimationParamsEqual(
            @NonNull TaskFragmentAnimationParams animationParams) {
        return mLastAnimationParams.equals(animationParams);
    }

    /**
     * Updates the last requested {@link TaskFragmentAnimationParams}.
     */
    void setLastRequestAnimationParams(@NonNull TaskFragmentAnimationParams animationParams) {
        mLastAnimationParams = animationParams;
    }

    /** Gets the parent leaf Task id. */
    int getTaskId() {
        return mTaskContainer.getTaskId();
    }

    /** Gets the parent Task. */
    @NonNull
    TaskContainer getTaskContainer() {
        return mTaskContainer;
    }

    @Nullable
    Size getMinDimensions() {
        if (mInfo == null) {
            return null;
        }
        int maxMinWidth = mInfo.getMinimumWidth();
        int maxMinHeight = mInfo.getMinimumHeight();
        for (IBinder activityToken : mPendingAppearedActivities) {
            final Activity activity = mController.getActivity(activityToken);
            final Size minDimensions = SplitPresenter.getMinDimensions(activity);
            if (minDimensions == null) {
                continue;
            }
            maxMinWidth = Math.max(maxMinWidth, minDimensions.getWidth());
            maxMinHeight = Math.max(maxMinHeight, minDimensions.getHeight());
        }
        if (mPendingAppearedIntent != null) {
            final Size minDimensions = SplitPresenter.getMinDimensions(mPendingAppearedIntent);
            if (minDimensions != null) {
                maxMinWidth = Math.max(maxMinWidth, minDimensions.getWidth());
                maxMinHeight = Math.max(maxMinHeight, minDimensions.getHeight());
            }
        }
        return new Size(maxMinWidth, maxMinHeight);
    }

    /** Whether the current TaskFragment is above the {@code other} TaskFragment. */
    boolean isAbove(@NonNull TaskFragmentContainer other) {
        if (mTaskContainer != other.mTaskContainer) {
            throw new IllegalArgumentException(
                    "Trying to compare two TaskFragments in different Task.");
        }
        if (this == other) {
            throw new IllegalArgumentException("Trying to compare a TaskFragment with itself.");
        }
        return mTaskContainer.indexOf(this) > mTaskContainer.indexOf(other);
    }

    @Override
    public String toString() {
        return toString(true /* includeContainersToFinishOnExit */);
    }

    /**
     * @return string for this TaskFragmentContainer and includes containers to finish on exit
     * based on {@code includeContainersToFinishOnExit}. If containers to finish on exit are always
     * included in the string, then calling {@link #toString()} on a container that mutually
     * finishes with another container would cause a stack overflow.
     */
    private String toString(boolean includeContainersToFinishOnExit) {
        return "TaskFragmentContainer{"
                + " parentTaskId=" + getTaskId()
                + " token=" + mToken
                + " topNonFinishingActivity=" + getTopNonFinishingActivity()
                + " runningActivityCount=" + getRunningActivityCount()
                + " isFinished=" + mIsFinished
                + " lastRequestedBounds=" + mLastRequestedBounds
                + " pendingAppearedActivities=" + mPendingAppearedActivities
                + (includeContainersToFinishOnExit ? " containersToFinishOnExit="
                + containersToFinishOnExitToString() : "")
                + " activitiesToFinishOnExit=" + mActivitiesToFinishOnExit
                + " info=" + mInfo
                + "}";
    }

    private String containersToFinishOnExitToString() {
        StringBuilder sb = new StringBuilder("[");
        Iterator<TaskFragmentContainer> containerIterator = mContainersToFinishOnExit.iterator();
        while (containerIterator.hasNext()) {
            sb.append(containerIterator.next().toString(
                    false /* includeContainersToFinishOnExit */));
            if (containerIterator.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.append("]").toString();
    }
}
