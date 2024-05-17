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
import android.os.Bundle;
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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

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

    /**
     * The activities that were explicitly requested to be launched in its current TaskFragment,
     * but haven't been added to {@link #mInfo} yet.
     */
    final ArrayList<IBinder> mPendingAppearedInRequestedTaskFragmentActivities = new ArrayList<>();

    /** Containers that are dependent on this one and should be completely destroyed on exit. */
    private final List<TaskFragmentContainer> mContainersToFinishOnExit =
            new ArrayList<>();

    /**
     * Individual associated activity tokens in different containers that should be finished on
     * exit.
     */
    private final List<IBinder> mActivitiesToFinishOnExit = new ArrayList<>();

    @Nullable
    private final String mOverlayTag;

    /**
     * The launch options that was used to create this container. Must not {@link Bundle#isEmpty()}
     * for {@link #isOverlay()} container.
     */
    @NonNull
    private final Bundle mLaunchOptions = new Bundle();

    /**
     * The associated {@link Activity#getActivityToken()} of the overlay container.
     * Must be {@code null} for non-overlay container.
     * <p>
     * If an overlay container is associated with an activity, this overlay container will be
     * dismissed when the associated activity is destroyed. If the overlay container is visible,
     * activity will be launched on top of the overlay container and expanded to fill the parent
     * container.
     */
    @Nullable
    private final IBinder mAssociatedActivityToken;

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
     * TaskFragment token that was requested last via
     * {@link android.window.TaskFragmentOperation#OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS}.
     */
    @Nullable
    private IBinder mLastAdjacentTaskFragment;

    /**
     * {@link WindowContainerTransaction.TaskFragmentAdjacentParams} token that was requested last
     * via {@link android.window.TaskFragmentOperation#OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS}.
     */
    @Nullable
    private WindowContainerTransaction.TaskFragmentAdjacentParams mLastAdjacentParams;

    /**
     * TaskFragment token that was requested last via
     * {@link android.window.TaskFragmentOperation#OP_TYPE_SET_COMPANION_TASK_FRAGMENT}.
     */
    @Nullable
    private IBinder mLastCompanionTaskFragment;

    /**
     * When the TaskFragment has appeared in server, but is empty, we should remove the TaskFragment
     * if it is still empty after the timeout.
     */
    @VisibleForTesting
    @Nullable
    Runnable mAppearEmptyTimeout;

    /**
     * Whether this TaskFragment contains activities of another process/package.
     */
    private boolean mHasCrossProcessActivities;

    /** Whether this TaskFragment enable isolated navigation. */
    private boolean mIsIsolatedNavigationEnabled;

    /**
     * Whether this TaskFragment is pinned.
     */
    private boolean mIsPinned;

    /**
     * Whether to apply dimming on the parent Task that was requested last.
     */
    private boolean mLastDimOnTask;

    /**
     * @see #TaskFragmentContainer(Activity, Intent, TaskContainer, SplitController,
     * TaskFragmentContainer, String, Bundle, Activity)
     */
    TaskFragmentContainer(@Nullable Activity pendingAppearedActivity,
                          @Nullable Intent pendingAppearedIntent,
                          @NonNull TaskContainer taskContainer,
                          @NonNull SplitController controller,
                          @Nullable TaskFragmentContainer pairedPrimaryContainer) {
        this(pendingAppearedActivity, pendingAppearedIntent, taskContainer,
                controller, pairedPrimaryContainer, null /* overlayTag */,
                null /* launchOptions */, null /* associatedActivity */);
    }

    /**
     * Creates a container with an existing activity that will be re-parented to it in a window
     * container transaction.
     * @param pairedPrimaryContainer    when it is set, the new container will be add right above it
     * @param overlayTag                Sets to indicate this taskFragment is an overlay container
     * @param launchOptions             The launch options to create this container. Must not be
     *                                  {@code null} for an overlay container
     * @param associatedActivity        the associated activity of the overlay container. Must be
     *                                  {@code null} for a non-overlay container.
     */
    TaskFragmentContainer(@Nullable Activity pendingAppearedActivity,
            @Nullable Intent pendingAppearedIntent, @NonNull TaskContainer taskContainer,
            @NonNull SplitController controller,
            @Nullable TaskFragmentContainer pairedPrimaryContainer, @Nullable String overlayTag,
            @Nullable Bundle launchOptions, @Nullable Activity associatedActivity) {
        if ((pendingAppearedActivity == null && pendingAppearedIntent == null)
                || (pendingAppearedActivity != null && pendingAppearedIntent != null)) {
            throw new IllegalArgumentException(
                    "One and only one of pending activity and intent must be non-null");
        }
        mController = controller;
        mToken = new Binder("TaskFragmentContainer");
        mTaskContainer = taskContainer;
        mOverlayTag = overlayTag;
        if (overlayTag != null) {
            Objects.requireNonNull(launchOptions);
        } else if (associatedActivity != null) {
            throw new IllegalArgumentException("Associated activity must be null for "
                    + "non-overlay activity.");
        }
        mAssociatedActivityToken = associatedActivity != null
                ? associatedActivity.getActivityToken() : null;

        if (launchOptions != null) {
            mLaunchOptions.putAll(launchOptions);
        }

        if (pairedPrimaryContainer != null) {
            // The TaskFragment will be positioned right above the paired container.
            if (pairedPrimaryContainer.getTaskContainer() != taskContainer) {
                throw new IllegalArgumentException(
                        "pairedPrimaryContainer must be in the same Task");
            }
            final int primaryIndex = taskContainer.indexOf(pairedPrimaryContainer);
            taskContainer.addTaskFragmentContainer(primaryIndex + 1, this);
        } else if (pendingAppearedActivity != null) {
            // The TaskFragment will be positioned right above the pending appeared Activity. If any
            // existing TaskFragment is empty with pending Intent, it is likely that the Activity of
            // the pending Intent hasn't been created yet, so the new Activity should be below the
            // empty TaskFragment.
            final List<TaskFragmentContainer> containers =
                    taskContainer.getTaskFragmentContainers();
            int i = containers.size() - 1;
            for (; i >= 0; i--) {
                final TaskFragmentContainer container = containers.get(i);
                if (!container.isEmpty() || container.getPendingAppearedIntent() == null) {
                    break;
                }
            }
            taskContainer.addTaskFragmentContainer(i + 1, this);
        } else {
            taskContainer.addTaskFragmentContainer(this);
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
        final List<Activity> activities = collectNonFinishingActivities(false /* checkIfStable */);
        if (activities == null) {
            throw new IllegalStateException(
                    "Result activities should never be null when checkIfstable is false.");
        }
        return activities;
    }

    /**
     * Collects non-finishing activities that belong to this container and live in this process.
     *
     * @param checkIfStable if {@code true}, returns {@code null} when the container is in an
     *                      intermediate state.
     * @return List of non-finishing activities that belong to this container and live in this
     * process, {@code null} if checkIfStable is {@code true} and the container is in an
     * intermediate state.
     */
    @Nullable
    List<Activity> collectNonFinishingActivities(boolean checkIfStable) {
        if (checkIfStable
                && (mInfo == null || mInfo.isEmpty() || !mPendingAppearedActivities.isEmpty())) {
            return null;
        }

        final List<Activity> allActivities = new ArrayList<>();
        if (mInfo != null) {
            // Add activities reported from the server.
            for (IBinder token : mInfo.getActivities()) {
                final Activity activity = mController.getActivity(token);
                if (activity != null && !activity.isFinishing()) {
                    allActivities.add(activity);
                } else {
                    if (checkIfStable) {
                        // Return null except for a special case when the activity is started in
                        // background.
                        if (activity == null && !mTaskContainer.isVisible()) {
                            continue;
                        }
                        return null;
                    }
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

    /**
     * Returns the ActivityStack representing this container.
     *
     * @return ActivityStack representing this container if it is in a stable state. {@code null} if
     * in an intermediate state.
     */
    @Nullable
    ActivityStack toActivityStackIfStable() {
        final List<Activity> activities = collectNonFinishingActivities(true /* checkIfStable */);
        if (activities == null) {
            return null;
        }
        return new ActivityStack(activities, isEmpty(),
                ActivityStack.Token.createFromBinder(mToken), mOverlayTag);
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
        // Also remove the activity from the mPendingInRequestedTaskFragmentActivities.
        mPendingAppearedInRequestedTaskFragmentActivities.remove(activityToken);
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

    /** Called when the activity {@link Activity#isFinishing()} and paused. */
    void onFinishingActivityPaused(@NonNull WindowContainerTransaction wct,
                                   @NonNull IBinder activityToken) {
        finishSelfWithActivityIfNeeded(wct, activityToken);
    }

    /** Called when the activity is destroyed. */
    void onActivityDestroyed(@NonNull WindowContainerTransaction wct,
                             @NonNull IBinder activityToken) {
        removePendingAppearedActivity(activityToken);
        if (mInfo != null) {
            // Remove the activity now because there can be a delay before the server callback.
            mInfo.getActivities().remove(activityToken);
        }
        mActivitiesToFinishOnExit.remove(activityToken);
        finishSelfWithActivityIfNeeded(wct, activityToken);
    }

    @VisibleForTesting
    void finishSelfWithActivityIfNeeded(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder activityToken) {
        if (mIsFinished) {
            return;
        }
        // Early return if this container is not an overlay with activity association.
        if (!isOverlayWithActivityAssociation()) {
            return;
        }
        if (mAssociatedActivityToken == activityToken) {
            // If the associated activity is destroyed, also finish this overlay container.
            mController.mPresenter.cleanupContainer(wct, this, false /* shouldFinishDependent */);
        }
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
        return mTaskContainer.getContainerWithActivity(activityToken) == this;
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

        mHasCrossProcessActivities = false;
        mInfo = info;
        if (mInfo == null || mInfo.isEmpty()) {
            return;
        }

        // Contains activities of another process if the activities size is not matched to the
        // running activity count
        if (mInfo.getRunningActivityCount() != mInfo.getActivities().size()) {
            mHasCrossProcessActivities = true;
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
                removePendingAppearedActivity(activityToken);
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
        removeContainersToFinishOnExit(Collections.singletonList(containerToRemove));
    }

    /**
     * Removes container list that should be finished when this container is finished.
     */
    void removeContainersToFinishOnExit(@NonNull List<TaskFragmentContainer> containersToRemove) {
        if (mIsFinished) {
            return;
        }
        mContainersToFinishOnExit.removeAll(containersToRemove);
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
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(container.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mController.mLock")
    void finish(boolean shouldFinishDependent, @NonNull SplitPresenter presenter,
            @NonNull WindowContainerTransaction wct, @NonNull SplitController controller) {
        finish(shouldFinishDependent, presenter, wct, controller, true /* shouldRemoveRecord */);
    }

    /**
     * Removes all activities that belong to this process and finishes other containers/activities
     * configured to finish together.
     */
    void finish(boolean shouldFinishDependent, @NonNull SplitPresenter presenter,
            @NonNull WindowContainerTransaction wct, @NonNull SplitController controller,
            boolean shouldRemoveRecord) {
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
        if (shouldRemoveRecord) {
            // Cleanup the records
            controller.removeContainer(this);
        }
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
            // Always finish the placeholder when the primary is finished.
            finishPlaceholderIfAny(wct, presenter);
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

    @GuardedBy("mController.mLock")
    private void finishPlaceholderIfAny(@NonNull WindowContainerTransaction wct,
            @NonNull SplitPresenter presenter) {
        final List<TaskFragmentContainer> containersToRemove = new ArrayList<>();
        for (TaskFragmentContainer container : mContainersToFinishOnExit) {
            if (container.mIsFinished) {
                continue;
            }
            final SplitContainer splitContainer = mController.getActiveSplitForContainers(
                    this, container);
            if (splitContainer != null && splitContainer.isPlaceholderContainer()
                    && splitContainer.getSecondaryContainer() == container) {
                // Remove the placeholder secondary TaskFragment.
                containersToRemove.add(container);
            }
        }
        mContainersToFinishOnExit.removeAll(containersToRemove);
        for (TaskFragmentContainer container : containersToRemove) {
            container.finish(false /* shouldFinishDependent */, presenter, wct, mController);
        }
    }

    boolean isFinished() {
        return mIsFinished;
    }

    /**
     * Checks if last requested bounds are equal to the provided value.
     * The requested bounds are relative bounds in parent coordinate.
     * @see WindowContainerTransaction#setRelativeBounds
     */
    boolean areLastRequestedBoundsEqual(@Nullable Rect relBounds) {
        return (relBounds == null && mLastRequestedBounds.isEmpty())
                || mLastRequestedBounds.equals(relBounds);
    }

    /**
     * Updates the last requested bounds.
     * The requested bounds are relative bounds in parent coordinate.
     * @see WindowContainerTransaction#setRelativeBounds
     */
    void setLastRequestedBounds(@Nullable Rect relBounds) {
        if (relBounds == null) {
            mLastRequestedBounds.setEmpty();
        } else {
            mLastRequestedBounds.set(relBounds);
        }
    }

    @NonNull Rect getLastRequestedBounds() {
        return mLastRequestedBounds;
    }

    /**
     * Checks if last requested windowing mode is equal to the provided value.
     * @see WindowContainerTransaction#setWindowingMode
     */
    boolean isLastRequestedWindowingModeEqual(@WindowingMode int windowingMode) {
        return mLastRequestedWindowingMode == windowingMode;
    }

    /**
     * Updates the last requested windowing mode.
     * @see WindowContainerTransaction#setWindowingMode
     */
    void setLastRequestedWindowingMode(@WindowingMode int windowingModes) {
        mLastRequestedWindowingMode = windowingModes;
    }

    /**
     * Checks if last requested {@link TaskFragmentAnimationParams} are equal to the provided value.
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_ANIMATION_PARAMS
     */
    boolean areLastRequestedAnimationParamsEqual(
            @NonNull TaskFragmentAnimationParams animationParams) {
        return mLastAnimationParams.equals(animationParams);
    }

    /**
     * Updates the last requested {@link TaskFragmentAnimationParams}.
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_ANIMATION_PARAMS
     */
    void setLastRequestAnimationParams(@NonNull TaskFragmentAnimationParams animationParams) {
        mLastAnimationParams = animationParams;
    }

    /**
     * Checks if last requested adjacent TaskFragment token and params are equal to the provided
     * values.
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS
     * @see android.window.TaskFragmentOperation#OP_TYPE_CLEAR_ADJACENT_TASK_FRAGMENTS
     */
    boolean isLastAdjacentTaskFragmentEqual(@Nullable IBinder fragmentToken,
            @Nullable WindowContainerTransaction.TaskFragmentAdjacentParams params) {
        return Objects.equals(mLastAdjacentTaskFragment, fragmentToken)
                && Objects.equals(mLastAdjacentParams, params);
    }

    /**
     * Updates the last requested adjacent TaskFragment token and params.
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS
     */
    void setLastAdjacentTaskFragment(@NonNull IBinder fragmentToken,
            @NonNull WindowContainerTransaction.TaskFragmentAdjacentParams params) {
        mLastAdjacentTaskFragment = fragmentToken;
        mLastAdjacentParams = params;
    }

    /**
     * Clears the last requested adjacent TaskFragment token and params.
     * @see android.window.TaskFragmentOperation#OP_TYPE_CLEAR_ADJACENT_TASK_FRAGMENTS
     */
    void clearLastAdjacentTaskFragment() {
        final TaskFragmentContainer lastAdjacentTaskFragment = mLastAdjacentTaskFragment != null
                ? mController.getContainer(mLastAdjacentTaskFragment)
                : null;
        mLastAdjacentTaskFragment = null;
        mLastAdjacentParams = null;
        if (lastAdjacentTaskFragment != null) {
            // Clear the previous adjacent TaskFragment as well.
            lastAdjacentTaskFragment.clearLastAdjacentTaskFragment();
        }
    }

    /**
     * Checks if last requested companion TaskFragment token is equal to the provided value.
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_COMPANION_TASK_FRAGMENT
     */
    boolean isLastCompanionTaskFragmentEqual(@Nullable IBinder fragmentToken) {
        return Objects.equals(mLastCompanionTaskFragment, fragmentToken);
    }

    /**
     * Updates the last requested companion TaskFragment token.
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_COMPANION_TASK_FRAGMENT
     */
    void setLastCompanionTaskFragment(@Nullable IBinder fragmentToken) {
        mLastCompanionTaskFragment = fragmentToken;
    }

    /** Returns whether to enable isolated navigation or not. */
    boolean isIsolatedNavigationEnabled() {
        return mIsIsolatedNavigationEnabled;
    }

    /** Sets whether to enable isolated navigation or not. */
    void setIsolatedNavigationEnabled(boolean isolatedNavigationEnabled) {
        mIsIsolatedNavigationEnabled = isolatedNavigationEnabled;
    }

    /**
     * Returns whether this container is pinned.
     *
     * @see android.window.TaskFragmentOperation#OP_TYPE_SET_PINNED
     */
    boolean isPinned() {
        return mIsPinned;
    }

    /**
     * Sets whether to pin this container or not.
     *
     * @see #isPinned()
     */
    void setPinned(boolean pinned) {
        mIsPinned = pinned;
    }

    /**
     * Indicates to skip activity resolving if the activity is from this container.
     *
     * @see #isIsolatedNavigationEnabled()
     * @see #isPinned()
     */
    boolean shouldSkipActivityResolving() {
        return isIsolatedNavigationEnabled() || isPinned();
    }

    /** Sets whether to apply dim on the parent Task. */
    void setLastDimOnTask(boolean lastDimOnTask) {
        mLastDimOnTask = lastDimOnTask;
    }

    /** Returns whether to apply dim on the parent Task. */
    boolean isLastDimOnTask() {
        return mLastDimOnTask;
    }

    /**
     * Adds the pending appeared activity that has requested to be launched in this task fragment.
     * @see android.app.ActivityClient#isRequestedToLaunchInTaskFragment
     */
    void addPendingAppearedInRequestedTaskFragmentActivity(Activity activity) {
        final IBinder activityToken = activity.getActivityToken();
        if (hasActivity(activityToken)) {
            return;
        }
        mPendingAppearedInRequestedTaskFragmentActivities.add(activity.getActivityToken());
    }

    /**
     * Checks if the given activity has requested to be launched in this task fragment.
     * @see #addPendingAppearedInRequestedTaskFragmentActivity
     */
    boolean isActivityInRequestedTaskFragment(IBinder activityToken) {
        if (mInfo != null && mInfo.getActivitiesRequestedInTaskFragment().contains(activityToken)) {
            return true;
        }
        return mPendingAppearedInRequestedTaskFragmentActivities.contains(activityToken);
    }

    /** Whether contains activities of another process */
    boolean hasCrossProcessActivities() {
        return mHasCrossProcessActivities;
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

    /** Returns whether this taskFragment container is an overlay container. */
    boolean isOverlay() {
        return mOverlayTag != null;
    }

    /**
     * Returns the tag specified in launch options. {@code null} if this taskFragment container is
     * not an overlay container.
     */
    @Nullable
    String getOverlayTag() {
        return mOverlayTag;
    }

    /**
     * Returns the options that was used to launch this {@link TaskFragmentContainer}.
     * {@link Bundle#isEmpty()} means there's no launch option for this container.
     * <p>
     * Note that WM Jetpack owns the logic. The WM Extension library must not modify this object.
     */
    @NonNull
    Bundle getLaunchOptions() {
        return mLaunchOptions;
    }

    /**
     * Returns the associated Activity token of this overlay container. It must be {@code null}
     * for non-overlay container.
     * <p>
     * If an overlay container is associated with an activity, this overlay container will be
     * dismissed when the associated activity is destroyed. If the overlay container is visible,
     * activity will be launched on top of the overlay container and expanded to fill the parent
     * container.
     */
    @Nullable
    IBinder getAssociatedActivityToken() {
        return mAssociatedActivityToken;
    }

    /**
     * Returns {@code true} if the overlay container should be always on top, which should be
     * a non-fill-parent overlay without activity association.
     */
    boolean isAlwaysOnTopOverlay() {
        return isOverlay() && mAssociatedActivityToken == null;
    }

    boolean isOverlayWithActivityAssociation() {
        return isOverlay() && mAssociatedActivityToken != null;
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
                + " overlayTag=" + mOverlayTag
                + " associatedActivityToken=" + mAssociatedActivityToken
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
