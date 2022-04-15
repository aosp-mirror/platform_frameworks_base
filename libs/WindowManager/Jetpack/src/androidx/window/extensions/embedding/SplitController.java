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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static androidx.window.extensions.embedding.SplitContainer.getFinishPrimaryWithSecondaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.getFinishSecondaryWithPrimaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.isStickyPlaceholderRule;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenAdjacent;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenStacked;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityClient;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.ArraySet;
import android.util.SparseArray;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.window.common.EmptyLifecycleCallbacksAdapter;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Main controller class that manages split states and presentation.
 */
public class SplitController implements JetpackTaskFragmentOrganizer.TaskFragmentCallback,
        ActivityEmbeddingComponent {

    private final SplitPresenter mPresenter;

    // Currently applied split configuration.
    private final List<EmbeddingRule> mSplitRules = new ArrayList<>();
    /**
     * Map from Task id to {@link TaskContainer} which contains all TaskFragment and split pair info
     * below it.
     * When the app is host of multiple Tasks, there can be multiple splits controlled by the same
     * organizer.
     */
    @VisibleForTesting
    final SparseArray<TaskContainer> mTaskContainers = new SparseArray<>();

    // Callback to Jetpack to notify about changes to split states.
    @NonNull
    private Consumer<List<SplitInfo>> mEmbeddingCallback;
    private final List<SplitInfo> mLastReportedSplitStates = new ArrayList<>();

    public SplitController() {
        mPresenter = new SplitPresenter(new MainThreadExecutor(), this);
        ActivityThread activityThread = ActivityThread.currentActivityThread();
        // Register a callback to be notified about activities being created.
        activityThread.getApplication().registerActivityLifecycleCallbacks(
                new LifecycleCallbacks());
        // Intercept activity starts to route activities to new containers if necessary.
        Instrumentation instrumentation = activityThread.getInstrumentation();
        instrumentation.addMonitor(new ActivityStartMonitor());
    }

    /** Updates the embedding rules applied to future activity launches. */
    @Override
    public void setEmbeddingRules(@NonNull Set<EmbeddingRule> rules) {
        mSplitRules.clear();
        mSplitRules.addAll(rules);
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            updateAnimationOverride(mTaskContainers.valueAt(i));
        }
    }

    @NonNull
    public List<EmbeddingRule> getSplitRules() {
        return mSplitRules;
    }

    /**
     * Starts an activity to side of the launchingActivity with the provided split config.
     */
    public void startActivityToSide(@NonNull Activity launchingActivity, @NonNull Intent intent,
            @Nullable Bundle options, @NonNull SplitRule sideRule,
            @Nullable Consumer<Exception> failureCallback) {
        try {
            mPresenter.startActivityToSide(launchingActivity, intent, options, sideRule);
        } catch (Exception e) {
            if (failureCallback != null) {
                failureCallback.accept(e);
            }
        }
    }

    /**
     * Registers the split organizer callback to notify about changes to active splits.
     */
    @Override
    public void setSplitInfoCallback(@NonNull Consumer<List<SplitInfo>> callback) {
        mEmbeddingCallback = callback;
        updateCallbackIfNecessary();
    }

    @Override
    public void onTaskFragmentAppeared(@NonNull TaskFragmentInfo taskFragmentInfo) {
        TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
        if (container == null) {
            return;
        }

        container.setInfo(taskFragmentInfo);
        if (container.isFinished()) {
            mPresenter.cleanupContainer(container, false /* shouldFinishDependent */);
        }
        updateCallbackIfNecessary();
    }

    @Override
    public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {
        TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
        if (container == null) {
            return;
        }

        final boolean wasInPip = isInPictureInPicture(container);
        container.setInfo(taskFragmentInfo);
        final boolean isInPip = isInPictureInPicture(container);
        // Check if there are no running activities - consider the container empty if there are no
        // non-finishing activities left.
        if (!taskFragmentInfo.hasRunningActivity()) {
            if (taskFragmentInfo.isTaskFragmentClearedForPip()) {
                // Do not finish the dependents if the last activity is reparented to PiP.
                // Instead, the original split should be cleanup, and the dependent may be expanded
                // to fullscreen.
                cleanupForEnterPip(container);
                mPresenter.cleanupContainer(container, false /* shouldFinishDependent */);
            } else {
                // Do not finish the dependents if this TaskFragment was cleared due to launching
                // activity in the Task.
                final boolean shouldFinishDependent = !taskFragmentInfo.isTaskClearedForReuse();
                mPresenter.cleanupContainer(container, shouldFinishDependent);
            }
        } else if (wasInPip && isInPip) {
            // No update until exit PIP.
            return;
        } else if (isInPip) {
            // Enter PIP.
            // All overrides will be cleanup.
            container.setLastRequestedBounds(null /* bounds */);
            cleanupForEnterPip(container);
        } else if (wasInPip) {
            // Exit PIP.
            // Updates the presentation of the container. Expand or launch placeholder if needed.
            mPresenter.updateContainer(container);
        }
        updateCallbackIfNecessary();
    }

    @Override
    public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {
        final TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
        if (container != null) {
            // Cleanup if the TaskFragment vanished is not requested by the organizer.
            mPresenter.cleanupContainer(container, true /* shouldFinishDependent */);
            updateCallbackIfNecessary();
        }
        cleanupTaskFragment(taskFragmentInfo.getFragmentToken());
    }

    @Override
    public void onTaskFragmentParentInfoChanged(@NonNull IBinder fragmentToken,
            @NonNull Configuration parentConfig) {
        final TaskFragmentContainer container = getContainer(fragmentToken);
        if (container != null) {
            onTaskConfigurationChanged(container.getTaskId(), parentConfig);
            if (isInPictureInPicture(parentConfig)) {
                // No need to update presentation in PIP until the Task exit PIP.
                return;
            }
            mPresenter.updateContainer(container);
            updateCallbackIfNecessary();
        }
    }

    /** Called on receiving {@link #onTaskFragmentVanished(TaskFragmentInfo)} for cleanup. */
    private void cleanupTaskFragment(@NonNull IBinder taskFragmentToken) {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final TaskContainer taskContainer = mTaskContainers.valueAt(i);
            if (!taskContainer.mFinishedContainer.remove(taskFragmentToken)) {
                continue;
            }
            if (taskContainer.isEmpty()) {
                // Cleanup the TaskContainer if it becomes empty.
                mPresenter.stopOverrideSplitAnimation(taskContainer.mTaskId);
                mTaskContainers.remove(taskContainer.mTaskId);
            }
            return;
        }
    }

    private void onTaskConfigurationChanged(int taskId, @NonNull Configuration config) {
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        if (taskContainer == null) {
            return;
        }
        final boolean wasInPip = isInPictureInPicture(taskContainer.mConfiguration);
        final boolean isInPIp = isInPictureInPicture(config);
        taskContainer.mConfiguration = config;

        // We need to check the animation override when enter/exit PIP or has bounds changed.
        boolean shouldUpdateAnimationOverride = wasInPip != isInPIp;
        if (onTaskBoundsMayChange(taskContainer, config.windowConfiguration.getBounds())
                && !isInPIp) {
            // We don't care the bounds change when it has already entered PIP.
            shouldUpdateAnimationOverride = true;
        }
        if (shouldUpdateAnimationOverride) {
            updateAnimationOverride(taskContainer);
        }
    }

    /** Returns {@code true} if the bounds is changed. */
    private boolean onTaskBoundsMayChange(@NonNull TaskContainer taskContainer,
            @NonNull Rect taskBounds) {
        if (!taskBounds.isEmpty() && !taskContainer.mTaskBounds.equals(taskBounds)) {
            taskContainer.mTaskBounds.set(taskBounds);
            return true;
        }
        return false;
    }

    /**
     * Updates if we should override transition animation. We only want to override if the Task
     * bounds is large enough for at least one split rule.
     */
    private void updateAnimationOverride(@NonNull TaskContainer taskContainer) {
        if (!taskContainer.isTaskBoundsInitialized()) {
            // We don't know about the Task bounds yet.
            return;
        }

        // We only want to override if it supports split.
        if (supportSplit(taskContainer)) {
            mPresenter.startOverrideSplitAnimation(taskContainer.mTaskId);
        } else {
            mPresenter.stopOverrideSplitAnimation(taskContainer.mTaskId);
        }
    }

    private boolean supportSplit(@NonNull TaskContainer taskContainer) {
        // No split inside PIP.
        if (isInPictureInPicture(taskContainer.mConfiguration)) {
            return false;
        }
        // Check if the parent container bounds can support any split rule.
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitRule)) {
                continue;
            }
            if (mPresenter.shouldShowSideBySide(taskContainer.mTaskBounds, (SplitRule) rule)) {
                return true;
            }
        }
        return false;
    }

    void onActivityCreated(@NonNull Activity launchedActivity) {
        handleActivityCreated(launchedActivity);
        updateCallbackIfNecessary();
    }

    /**
     * Checks if the activity start should be routed to a particular container. It can create a new
     * container for the activity and a new split container if necessary.
     */
    // TODO(b/190433398): Break down into smaller functions.
    void handleActivityCreated(@NonNull Activity launchedActivity) {
        if (isInPictureInPicture(launchedActivity)) {
            // We don't embed activity when it is in PIP.
            return;
        }
        final List<EmbeddingRule> splitRules = getSplitRules();
        final TaskFragmentContainer currentContainer = getContainerWithActivity(
                launchedActivity.getActivityToken());

        // Check if the activity is configured to always be expanded.
        if (shouldExpand(launchedActivity, null, splitRules)) {
            if (shouldContainerBeExpanded(currentContainer)) {
                // Make sure that the existing container is expanded
                mPresenter.expandTaskFragment(currentContainer.getTaskFragmentToken());
            } else {
                // Put activity into a new expanded container
                final TaskFragmentContainer newContainer = newContainer(launchedActivity,
                        launchedActivity.getTaskId());
                mPresenter.expandActivity(newContainer.getTaskFragmentToken(),
                        launchedActivity);
            }
            return;
        }

        // Check if activity requires a placeholder
        if (launchPlaceholderIfNecessary(launchedActivity)) {
            return;
        }

        // TODO(b/190433398): Check if it is a placeholder and there is already another split
        // created by the primary activity. This is necessary for the case when the primary activity
        // launched another secondary in the split, but the placeholder was still launched by the
        // logic above. We didn't prevent the placeholder launcher because we didn't know that
        // another secondary activity is coming up.

        // Check if the activity should form a split with the activity below in the same task
        // fragment.
        Activity activityBelow = null;
        if (currentContainer != null) {
            final List<Activity> containerActivities = currentContainer.collectActivities();
            final int index = containerActivities.indexOf(launchedActivity);
            if (index > 0) {
                activityBelow = containerActivities.get(index - 1);
            }
        }
        if (activityBelow == null) {
            IBinder belowToken = ActivityClient.getInstance().getActivityTokenBelow(
                    launchedActivity.getActivityToken());
            if (belowToken != null) {
                activityBelow = ActivityThread.currentActivityThread().getActivity(belowToken);
            }
        }
        if (activityBelow == null) {
            return;
        }

        // Check if the split is already set.
        final TaskFragmentContainer activityBelowContainer = getContainerWithActivity(
                activityBelow.getActivityToken());
        if (currentContainer != null && activityBelowContainer != null) {
            final SplitContainer existingSplit = getActiveSplitForContainers(currentContainer,
                    activityBelowContainer);
            if (existingSplit != null) {
                // There is already an active split with the activity below.
                return;
            }
        }

        final SplitPairRule splitPairRule = getSplitRule(activityBelow, launchedActivity,
                splitRules);
        if (splitPairRule == null) {
            return;
        }

        mPresenter.createNewSplitContainer(activityBelow, launchedActivity,
                splitPairRule);
    }

    private void onActivityConfigurationChanged(@NonNull Activity activity) {
        if (isInPictureInPicture(activity)) {
            // We don't embed activity when it is in PIP.
            return;
        }
        final TaskFragmentContainer currentContainer = getContainerWithActivity(
                activity.getActivityToken());

        if (currentContainer != null) {
            // Changes to activities in controllers are handled in
            // onTaskFragmentParentInfoChanged
            return;
        }

        // Check if activity requires a placeholder
        launchPlaceholderIfNecessary(activity);
    }

    /**
     * Returns a container that this activity is registered with. An activity can only belong to one
     * container, or no container at all.
     */
    @Nullable
    TaskFragmentContainer getContainerWithActivity(@NonNull IBinder activityToken) {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            for (TaskFragmentContainer container : containers) {
                if (container.hasActivity(activityToken)) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * Creates and registers a new organized container with an optional activity that will be
     * re-parented to it in a WCT.
     */
    TaskFragmentContainer newContainer(@Nullable Activity activity, int taskId) {
        final TaskFragmentContainer container = new TaskFragmentContainer(activity, taskId);
        if (!mTaskContainers.contains(taskId)) {
            mTaskContainers.put(taskId, new TaskContainer(taskId));
        }
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        taskContainer.mContainers.add(container);
        if (activity != null && !taskContainer.isTaskBoundsInitialized()
                && onTaskBoundsMayChange(taskContainer,
                SplitPresenter.getTaskBoundsFromActivity(activity))) {
            // Initial check before any TaskFragment has appeared.
            updateAnimationOverride(taskContainer);
        }
        return container;
    }

    /**
     * Creates and registers a new split with the provided containers and configuration. Finishes
     * existing secondary containers if found for the given primary container.
     */
    void registerSplit(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer primaryContainer, @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull SplitRule splitRule) {
        final SplitContainer splitContainer = new SplitContainer(primaryContainer, primaryActivity,
                secondaryContainer, splitRule);
        // Remove container later to prevent pinning escaping toast showing in lock task mode.
        if (splitRule instanceof SplitPairRule && ((SplitPairRule) splitRule).shouldClearTop()) {
            removeExistingSecondaryContainers(wct, primaryContainer);
        }
        mTaskContainers.get(primaryContainer.getTaskId()).mSplitContainers.add(splitContainer);
    }

    /** Cleanups all the dependencies when the TaskFragment is entering PIP. */
    private void cleanupForEnterPip(@NonNull TaskFragmentContainer container) {
        final int taskId = container.getTaskId();
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        if (taskContainer == null) {
            return;
        }
        final List<SplitContainer> splitsToRemove = new ArrayList<>();
        final Set<TaskFragmentContainer> containersToUpdate = new ArraySet<>();
        for (SplitContainer splitContainer : taskContainer.mSplitContainers) {
            if (splitContainer.getPrimaryContainer() != container
                    && splitContainer.getSecondaryContainer() != container) {
                continue;
            }
            splitsToRemove.add(splitContainer);
            final TaskFragmentContainer splitTf = splitContainer.getPrimaryContainer() == container
                    ? splitContainer.getSecondaryContainer()
                    : splitContainer.getPrimaryContainer();
            containersToUpdate.add(splitTf);
            // We don't want the PIP TaskFragment to be removed as a result of any of its dependents
            // being removed.
            splitTf.removeContainerToFinishOnExit(container);
            if (container.getTopNonFinishingActivity() != null) {
                splitTf.removeActivityToFinishOnExit(container.getTopNonFinishingActivity());
            }
        }
        container.resetDependencies();
        taskContainer.mSplitContainers.removeAll(splitsToRemove);
        // If there is any TaskFragment split with the PIP TaskFragment, update their presentations
        // since the split is dismissed.
        // We don't want to close any of them even if they are dependencies of the PIP TaskFragment.
        mPresenter.updateContainers(containersToUpdate);
    }

    /**
     * Removes the container from bookkeeping records.
     */
    void removeContainer(@NonNull TaskFragmentContainer container) {
        // Remove all split containers that included this one
        final int taskId = container.getTaskId();
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        if (taskContainer == null) {
            return;
        }
        taskContainer.mContainers.remove(container);
        // Marked as a pending removal which will be removed after it is actually removed on the
        // server side (#onTaskFragmentVanished).
        // In this way, we can keep track of the Task bounds until we no longer have any
        // TaskFragment there.
        taskContainer.mFinishedContainer.add(container.getTaskFragmentToken());

        final List<SplitContainer> containersToRemove = new ArrayList<>();
        for (SplitContainer splitContainer : taskContainer.mSplitContainers) {
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                containersToRemove.add(splitContainer);
            }
        }
        taskContainer.mSplitContainers.removeAll(containersToRemove);
    }

    /**
     * Removes a secondary container for the given primary container if an existing split is
     * already registered.
     */
    void removeExistingSecondaryContainers(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer primaryContainer) {
        // If the primary container was already in a split - remove the secondary container that
        // is now covered by the new one that replaced it.
        final SplitContainer existingSplitContainer = getActiveSplitForContainer(
                primaryContainer);
        if (existingSplitContainer == null
                || primaryContainer == existingSplitContainer.getSecondaryContainer()) {
            return;
        }

        existingSplitContainer.getSecondaryContainer().finish(
                false /* shouldFinishDependent */, mPresenter, wct, this);
    }

    /**
     * Returns the topmost not finished container in Task of given task id.
     */
    @Nullable
    TaskFragmentContainer getTopActiveContainer(int taskId) {
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        if (taskContainer == null) {
            return null;
        }
        for (int i = taskContainer.mContainers.size() - 1; i >= 0; i--) {
            final TaskFragmentContainer container = taskContainer.mContainers.get(i);
            if (!container.isFinished() && container.getRunningActivityCount() > 0) {
                return container;
            }
        }
        return null;
    }

    /**
     * Updates the presentation of the container. If the container is part of the split or should
     * have a placeholder, it will also update the other part of the split.
     */
    void updateContainer(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        if (launchPlaceholderIfNecessary(container)) {
            // Placeholder was launched, the positions will be updated when the activity is added
            // to the secondary container.
            return;
        }
        if (shouldContainerBeExpanded(container)) {
            if (container.getInfo() != null) {
                mPresenter.expandTaskFragment(wct, container.getTaskFragmentToken());
            }
            // If the info is not available yet the task fragment will be expanded when it's ready
            return;
        }
        SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer == null) {
            return;
        }
        final List<SplitContainer> splitContainers = mTaskContainers.get(container.getTaskId())
                .mSplitContainers;
        if (splitContainers == null
                || splitContainer != splitContainers.get(splitContainers.size() - 1)) {
            // Skip position update - it isn't the topmost split.
            return;
        }
        if (splitContainer.getPrimaryContainer().isEmpty()
                || splitContainer.getSecondaryContainer().isEmpty()) {
            // Skip position update - one or both containers are empty.
            return;
        }
        if (dismissPlaceholderIfNecessary(splitContainer)) {
            // Placeholder was finished, the positions will be updated when its container is emptied
            return;
        }
        mPresenter.updateSplitContainer(splitContainer, container, wct);
    }

    /**
     * Returns the top active split container that has the provided container, if available.
     */
    @Nullable
    private SplitContainer getActiveSplitForContainer(@NonNull TaskFragmentContainer container) {
        final List<SplitContainer> splitContainers = mTaskContainers.get(container.getTaskId())
                .mSplitContainers;
        if (splitContainers == null) {
            return null;
        }
        for (int i = splitContainers.size() - 1; i >= 0; i--) {
            final SplitContainer splitContainer = splitContainers.get(i);
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                return splitContainer;
            }
        }
        return null;
    }

    /**
     * Returns the active split that has the provided containers as primary and secondary or as
     * secondary and primary, if available.
     */
    @Nullable
    private SplitContainer getActiveSplitForContainers(
            @NonNull TaskFragmentContainer firstContainer,
            @NonNull TaskFragmentContainer secondContainer) {
        final List<SplitContainer> splitContainers = mTaskContainers.get(firstContainer.getTaskId())
                .mSplitContainers;
        if (splitContainers == null) {
            return null;
        }
        for (int i = splitContainers.size() - 1; i >= 0; i--) {
            final SplitContainer splitContainer = splitContainers.get(i);
            final TaskFragmentContainer primary = splitContainer.getPrimaryContainer();
            final TaskFragmentContainer secondary = splitContainer.getSecondaryContainer();
            if ((firstContainer == secondary && secondContainer == primary)
                    || (firstContainer == primary && secondContainer == secondary)) {
                return splitContainer;
            }
        }
        return null;
    }

    /**
     * Checks if the container requires a placeholder and launches it if necessary.
     */
    private boolean launchPlaceholderIfNecessary(@NonNull TaskFragmentContainer container) {
        final Activity topActivity = container.getTopNonFinishingActivity();
        if (topActivity == null) {
            return false;
        }

        return launchPlaceholderIfNecessary(topActivity);
    }

    boolean launchPlaceholderIfNecessary(@NonNull Activity activity) {
        final TaskFragmentContainer container = getContainerWithActivity(
                activity.getActivityToken());
        // Don't launch placeholder if the container is occluded.
        if (container != null && container != getTopActiveContainer(container.getTaskId())) {
            return false;
        }

        SplitContainer splitContainer = container != null ? getActiveSplitForContainer(container)
                : null;
        if (splitContainer != null && container.equals(splitContainer.getPrimaryContainer())) {
            // Don't launch placeholder in primary split container
            return false;
        }

        // Check if there is enough space for launch
        final SplitPlaceholderRule placeholderRule = getPlaceholderRule(activity);
        if (placeholderRule == null || !mPresenter.shouldShowSideBySide(
                mPresenter.getParentContainerBounds(activity), placeholderRule)) {
            return false;
        }

        // TODO(b/190433398): Handle failed request
        startActivityToSide(activity, placeholderRule.getPlaceholderIntent(), null,
                placeholderRule, null);
        return true;
    }

    private boolean dismissPlaceholderIfNecessary(@NonNull SplitContainer splitContainer) {
        if (!splitContainer.isPlaceholderContainer()) {
            return false;
        }

        if (isStickyPlaceholderRule(splitContainer.getSplitRule())) {
            // The placeholder should remain after it was first shown.
            return false;
        }

        if (mPresenter.shouldShowSideBySide(splitContainer)) {
            return false;
        }

        mPresenter.cleanupContainer(splitContainer.getSecondaryContainer(),
                false /* shouldFinishDependent */);
        return true;
    }

    /**
     * Returns the rule to launch a placeholder for the activity with the provided component name
     * if it is configured in the split config.
     */
    private SplitPlaceholderRule getPlaceholderRule(@NonNull Activity activity) {
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitPlaceholderRule)) {
                continue;
            }
            SplitPlaceholderRule placeholderRule = (SplitPlaceholderRule) rule;
            if (placeholderRule.matchesActivity(activity)) {
                return placeholderRule;
            }
        }
        return null;
    }

    private void updateCallbackIfNecessary() {
        updateCallbackIfNecessary(true /* deferCallbackUntilAllActivitiesCreated */);
    }

    /**
     * Notifies listeners about changes to split states if necessary.
     *
     * @param deferCallbackUntilAllActivitiesCreated boolean to indicate whether the split info
     *                                               callback should be deferred until all the
     *                                               organized activities have been created.
     */
    private void updateCallbackIfNecessary(boolean deferCallbackUntilAllActivitiesCreated) {
        if (mEmbeddingCallback == null) {
            return;
        }
        if (deferCallbackUntilAllActivitiesCreated && !allActivitiesCreated()) {
            return;
        }
        List<SplitInfo> currentSplitStates = getActiveSplitStates();
        if (currentSplitStates == null || mLastReportedSplitStates.equals(currentSplitStates)) {
            return;
        }
        mLastReportedSplitStates.clear();
        mLastReportedSplitStates.addAll(currentSplitStates);
        mEmbeddingCallback.accept(currentSplitStates);
    }

    /**
     * @return a list of descriptors for currently active split states. If the value returned is
     * null, that indicates that the active split states are in an intermediate state and should
     * not be reported.
     */
    @Nullable
    private List<SplitInfo> getActiveSplitStates() {
        List<SplitInfo> splitStates = new ArrayList<>();
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<SplitContainer> splitContainers = mTaskContainers.valueAt(i)
                    .mSplitContainers;
            for (SplitContainer container : splitContainers) {
                if (container.getPrimaryContainer().isEmpty()
                        || container.getSecondaryContainer().isEmpty()) {
                    // We are in an intermediate state because either the split container is about
                    // to be removed or the primary or secondary container are about to receive an
                    // activity.
                    return null;
                }
                final ActivityStack primaryContainer = container.getPrimaryContainer()
                        .toActivityStack();
                final ActivityStack secondaryContainer = container.getSecondaryContainer()
                        .toActivityStack();
                final SplitInfo splitState = new SplitInfo(primaryContainer, secondaryContainer,
                        // Splits that are not showing side-by-side are reported as having 0 split
                        // ratio, since by definition in the API the primary container occupies no
                        // width of the split when covered by the secondary.
                        mPresenter.shouldShowSideBySide(container)
                                ? container.getSplitRule().getSplitRatio()
                                : 0.0f);
                splitStates.add(splitState);
            }
        }
        return splitStates;
    }

    /**
     * Checks if all activities that are registered with the containers have already appeared in
     * the client.
     */
    private boolean allActivitiesCreated() {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            for (TaskFragmentContainer container : containers) {
                if (container.getInfo() == null
                        || container.getInfo().getActivities().size()
                        != container.collectActivities().size()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the container is expanded to occupy full task size.
     * Returns {@code false} if the container is included in an active split.
     */
    boolean shouldContainerBeExpanded(@Nullable TaskFragmentContainer container) {
        if (container == null) {
            return false;
        }
        final List<SplitContainer> splitContainers = mTaskContainers.get(container.getTaskId())
                .mSplitContainers;
        if (splitContainers == null) {
            return true;
        }
        for (SplitContainer splitContainer : splitContainers) {
            if (container.equals(splitContainer.getPrimaryContainer())
                    || container.equals(splitContainer.getSecondaryContainer())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a split rule for the provided pair of primary activity and secondary activity intent
     * if available.
     */
    @Nullable
    private static SplitPairRule getSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryActivityIntent, @NonNull List<EmbeddingRule> splitRules) {
        for (EmbeddingRule rule : splitRules) {
            if (!(rule instanceof SplitPairRule)) {
                continue;
            }
            SplitPairRule pairRule = (SplitPairRule) rule;
            if (pairRule.matchesActivityIntentPair(primaryActivity, secondaryActivityIntent)) {
                return pairRule;
            }
        }
        return null;
    }

    /**
     * Returns a split rule for the provided pair of primary and secondary activities if available.
     */
    @Nullable
    private static SplitPairRule getSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, @NonNull List<EmbeddingRule> splitRules) {
        for (EmbeddingRule rule : splitRules) {
            if (!(rule instanceof SplitPairRule)) {
                continue;
            }
            SplitPairRule pairRule = (SplitPairRule) rule;
            final Intent intent = secondaryActivity.getIntent();
            if (pairRule.matchesActivityPair(primaryActivity, secondaryActivity)
                    && (intent == null
                    || pairRule.matchesActivityIntentPair(primaryActivity, intent))) {
                return pairRule;
            }
        }
        return null;
    }

    @Nullable
    TaskFragmentContainer getContainer(@NonNull IBinder fragmentToken) {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            for (TaskFragmentContainer container : containers) {
                if (container.getTaskFragmentToken().equals(fragmentToken)) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if an Activity with the provided component name should always be
     * expanded to occupy full task bounds. Such activity must not be put in a split.
     */
    private static boolean shouldExpand(@Nullable Activity activity, @Nullable Intent intent,
            List<EmbeddingRule> splitRules) {
        if (splitRules == null) {
            return false;
        }
        for (EmbeddingRule rule : splitRules) {
            if (!(rule instanceof ActivityRule)) {
                continue;
            }
            ActivityRule activityRule = (ActivityRule) rule;
            if (!activityRule.shouldAlwaysExpand()) {
                continue;
            }
            if (activity != null && activityRule.matchesActivity(activity)) {
                return true;
            } else if (intent != null && activityRule.matchesIntent(intent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the associated container should be destroyed together with a finishing
     * container. There is a case when primary containers for placeholders should be retained
     * despite the rule configuration to finish primary with secondary - if they are marked as
     * 'sticky' and the placeholder was finished when fully overlapping the primary container.
     * @return {@code true} if the associated container should be retained (and not be finished).
     */
    boolean shouldRetainAssociatedContainer(@NonNull TaskFragmentContainer finishingContainer,
            @NonNull TaskFragmentContainer associatedContainer) {
        SplitContainer splitContainer = getActiveSplitForContainers(associatedContainer,
                finishingContainer);
        if (splitContainer == null) {
            // Containers are not in the same split, no need to retain.
            return false;
        }
        // Find the finish behavior for the associated container
        int finishBehavior;
        SplitRule splitRule = splitContainer.getSplitRule();
        if (finishingContainer == splitContainer.getPrimaryContainer()) {
            finishBehavior = getFinishSecondaryWithPrimaryBehavior(splitRule);
        } else {
            finishBehavior = getFinishPrimaryWithSecondaryBehavior(splitRule);
        }
        // Decide whether the associated container should be retained based on the current
        // presentation mode.
        if (mPresenter.shouldShowSideBySide(splitContainer)) {
            return !shouldFinishAssociatedContainerWhenAdjacent(finishBehavior);
        } else {
            return !shouldFinishAssociatedContainerWhenStacked(finishBehavior);
        }
    }

    /**
     * @see #shouldRetainAssociatedContainer(TaskFragmentContainer, TaskFragmentContainer)
     */
    boolean shouldRetainAssociatedActivity(@NonNull TaskFragmentContainer finishingContainer,
            @NonNull Activity associatedActivity) {
        TaskFragmentContainer associatedContainer = getContainerWithActivity(
                associatedActivity.getActivityToken());
        if (associatedContainer == null) {
            return false;
        }

        return shouldRetainAssociatedContainer(finishingContainer, associatedContainer);
    }

    private final class LifecycleCallbacks extends EmptyLifecycleCallbacksAdapter {

        @Override
        public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
            final IBinder activityToken = activity.getActivityToken();
            final IBinder initialTaskFragmentToken = ActivityThread.currentActivityThread()
                    .getActivityClient(activityToken).mInitialTaskFragmentToken;
            // If the activity is not embedded, then it will not have an initial task fragment token
            // so no further action is needed.
            if (initialTaskFragmentToken == null) {
                return;
            }
            for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
                final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i)
                        .mContainers;
                for (int j = containers.size() - 1; j >= 0; j--) {
                    final TaskFragmentContainer container = containers.get(j);
                    if (!container.hasActivity(activityToken)
                            && container.getTaskFragmentToken().equals(initialTaskFragmentToken)) {
                        // The onTaskFragmentInfoChanged callback containing this activity has not
                        // reached the client yet, so add the activity to the pending appeared
                        // activities and send a split info callback to the client before
                        // {@link Activity#onCreate} is called.
                        container.addPendingAppearedActivity(activity);
                        updateCallbackIfNecessary(
                                false /* deferCallbackUntilAllActivitiesCreated */);
                        return;
                    }
                }
            }
        }

        @Override
        public void onActivityPostCreated(Activity activity, Bundle savedInstanceState) {
            // Calling after Activity#onCreate is complete to allow the app launch something
            // first. In case of a configured placeholder activity we want to make sure
            // that we don't launch it if an activity itself already requested something to be
            // launched to side.
            SplitController.this.onActivityCreated(activity);
        }

        @Override
        public void onActivityConfigurationChanged(Activity activity) {
            SplitController.this.onActivityConfigurationChanged(activity);
        }
    }

    /** Executor that posts on the main application thread. */
    private static class MainThreadExecutor implements Executor {
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable r) {
            mHandler.post(r);
        }
    }

    /**
     * A monitor that intercepts all activity start requests originating in the client process and
     * can amend them to target a specific task fragment to form a split.
     */
    private class ActivityStartMonitor extends Instrumentation.ActivityMonitor {

        @Override
        public Instrumentation.ActivityResult onStartActivity(@NonNull Context who,
                @NonNull Intent intent, @NonNull Bundle options) {
            // TODO(b/190433398): Check if the activity is configured to always be expanded.

            // Check if activity should be put in a split with the activity that launched it.
            if (!(who instanceof Activity)) {
                return super.onStartActivity(who, intent, options);
            }
            final Activity launchingActivity = (Activity) who;
            if (isInPictureInPicture(launchingActivity)) {
                // We don't embed activity when it is in PIP.
                return super.onStartActivity(who, intent, options);
            }

            if (shouldExpand(null, intent, getSplitRules())) {
                setLaunchingInExpandedContainer(launchingActivity, options);
            } else if (!splitWithLaunchingActivity(launchingActivity, intent, options)) {
                setLaunchingInSameSideContainer(launchingActivity, intent, options);
            }

            return super.onStartActivity(who, intent, options);
        }

        private void setLaunchingInExpandedContainer(Activity launchingActivity, Bundle options) {
            TaskFragmentContainer newContainer = mPresenter.createNewExpandedContainer(
                    launchingActivity);

            // Amend the request to let the WM know that the activity should be placed in the
            // dedicated container.
            options.putBinder(ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN,
                    newContainer.getTaskFragmentToken());
        }

        /**
         * Returns {@code true} if the activity that is going to be started via the
         * {@code intent} should be paired with the {@code launchingActivity} and is set to be
         * launched in the side container.
         */
        private boolean splitWithLaunchingActivity(Activity launchingActivity, Intent intent,
                Bundle options) {
            final SplitPairRule splitPairRule = getSplitRule(launchingActivity, intent,
                    getSplitRules());
            if (splitPairRule == null) {
                return false;
            }

            // Check if there is any existing side container to launch into.
            TaskFragmentContainer secondaryContainer = findSideContainerForNewLaunch(
                    launchingActivity, splitPairRule);
            if (secondaryContainer == null) {
                // Create a new split with an empty side container.
                secondaryContainer = mPresenter
                        .createNewSplitWithEmptySideContainer(launchingActivity, splitPairRule);
            }

            // Amend the request to let the WM know that the activity should be placed in the
            // dedicated container.
            options.putBinder(ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN,
                    secondaryContainer.getTaskFragmentToken());
            return true;
        }

        /**
         * Finds if there is an existing split side {@link TaskFragmentContainer} that can be used
         * for the new rule.
         */
        @Nullable
        private TaskFragmentContainer findSideContainerForNewLaunch(Activity launchingActivity,
                SplitPairRule splitPairRule) {
            final TaskFragmentContainer launchingContainer = getContainerWithActivity(
                    launchingActivity.getActivityToken());
            if (launchingContainer == null) {
                return null;
            }

            // We only check if the launching activity is the primary of the split. We will check
            // if the launching activity is the secondary in #setLaunchingInSameSideContainer.
            final SplitContainer splitContainer = getActiveSplitForContainer(launchingContainer);
            if (splitContainer == null
                    || splitContainer.getPrimaryContainer() != launchingContainer) {
                return null;
            }

            if (canReuseContainer(splitPairRule, splitContainer.getSplitRule())) {
                return splitContainer.getSecondaryContainer();
            }
            return null;
        }

        /**
         * Checks if the activity that is going to be started via the {@code intent} should be
         * paired with the existing top activity which is currently paired with the
         * {@code launchingActivity}. If so, set the activity to be launched in the same side
         * container of the {@code launchingActivity}.
         */
        private void setLaunchingInSameSideContainer(Activity launchingActivity, Intent intent,
                Bundle options) {
            final TaskFragmentContainer launchingContainer = getContainerWithActivity(
                    launchingActivity.getActivityToken());
            if (launchingContainer == null) {
                return;
            }

            final SplitContainer splitContainer = getActiveSplitForContainer(launchingContainer);
            if (splitContainer == null) {
                return;
            }

            if (splitContainer.getSecondaryContainer() != launchingContainer) {
                return;
            }

            // The launching activity is on the secondary container. Retrieve the primary
            // activity from the other container.
            Activity primaryActivity =
                    splitContainer.getPrimaryContainer().getTopNonFinishingActivity();
            if (primaryActivity == null) {
                return;
            }

            final SplitPairRule splitPairRule = getSplitRule(primaryActivity, intent,
                    getSplitRules());
            if (splitPairRule == null) {
                return;
            }

            // Can only launch in the same container if the rules share the same presentation.
            if (!canReuseContainer(splitPairRule, splitContainer.getSplitRule())) {
                return;
            }

            // Amend the request to let the WM know that the activity should be placed in the
            // dedicated container. This is necessary for the case that the activity is started
            // into a new Task, or new Task will be escaped from the current host Task and be
            // displayed in fullscreen.
            options.putBinder(ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN,
                    launchingContainer.getTaskFragmentToken());
        }
    }

    /**
     * Checks if an activity is embedded and its presentation is customized by a
     * {@link android.window.TaskFragmentOrganizer} to only occupy a portion of Task bounds.
     */
    @Override
    public boolean isActivityEmbedded(@NonNull Activity activity) {
        return mPresenter.isActivityEmbedded(activity.getActivityToken());
    }

    /**
     * If the two rules have the same presentation, we can reuse the same {@link SplitContainer} if
     * there is any.
     */
    private static boolean canReuseContainer(SplitRule rule1, SplitRule rule2) {
        if (!isContainerReusableRule(rule1) || !isContainerReusableRule(rule2)) {
            return false;
        }
        return rule1.getSplitRatio() == rule2.getSplitRatio()
                && rule1.getLayoutDirection() == rule2.getLayoutDirection();
    }

    /**
     * Whether it is ok for other rule to reuse the {@link TaskFragmentContainer} of the given
     * rule.
     */
    private static boolean isContainerReusableRule(SplitRule rule) {
        // We don't expect to reuse the placeholder rule.
        if (!(rule instanceof SplitPairRule)) {
            return false;
        }
        final SplitPairRule pairRule = (SplitPairRule) rule;

        // Not reuse if it needs to destroy the existing.
        return !pairRule.shouldClearTop();
    }

    private static boolean isInPictureInPicture(@NonNull Activity activity) {
        return isInPictureInPicture(activity.getResources().getConfiguration());
    }

    private static boolean isInPictureInPicture(@NonNull TaskFragmentContainer tf) {
        return isInPictureInPicture(tf.getInfo().getConfiguration());
    }

    private static boolean isInPictureInPicture(@Nullable Configuration configuration) {
        return configuration != null
                && configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }

    /** Represents TaskFragments and split pairs below a Task. */
    @VisibleForTesting
    static class TaskContainer {
        /** The unique task id. */
        final int mTaskId;
        /** Active TaskFragments in this Task. */
        final List<TaskFragmentContainer> mContainers = new ArrayList<>();
        /** Active split pairs in this Task. */
        final List<SplitContainer> mSplitContainers = new ArrayList<>();
        /**
         * TaskFragments that the organizer has requested to be closed. They should be removed when
         * the organizer receives {@link #onTaskFragmentVanished(TaskFragmentInfo)} event for them.
         */
        final Set<IBinder> mFinishedContainer = new ArraySet<>();
        /** Available window bounds of this Task. */
        final Rect mTaskBounds = new Rect();
        /** Configuration of the Task. */
        @Nullable
        Configuration mConfiguration;

        TaskContainer(int taskId) {
            mTaskId = taskId;
        }

        boolean isEmpty() {
            return mContainers.isEmpty() && mFinishedContainer.isEmpty();
        }

        boolean isTaskBoundsInitialized() {
            return !mTaskBounds.isEmpty();
        }
    }
}
