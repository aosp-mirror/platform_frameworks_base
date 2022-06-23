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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static androidx.window.extensions.embedding.SplitContainer.getFinishPrimaryWithSecondaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.getFinishSecondaryWithPrimaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.isStickyPlaceholderRule;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenAdjacent;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenStacked;
import static androidx.window.extensions.embedding.SplitPresenter.boundsSmallerThanMinDimensions;
import static androidx.window.extensions.embedding.SplitPresenter.getActivityIntentMinDimensionsPair;
import static androidx.window.extensions.embedding.SplitPresenter.getMinDimensions;
import static androidx.window.extensions.embedding.SplitPresenter.shouldShowSideBySide;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SparseArray;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    static final String TAG = "SplitController";

    @VisibleForTesting
    @GuardedBy("mLock")
    final SplitPresenter mPresenter;

    // Currently applied split configuration.
    @GuardedBy("mLock")
    private final List<EmbeddingRule> mSplitRules = new ArrayList<>();
    /**
     * Map from Task id to {@link TaskContainer} which contains all TaskFragment and split pair info
     * below it.
     * When the app is host of multiple Tasks, there can be multiple splits controlled by the same
     * organizer.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    final SparseArray<TaskContainer> mTaskContainers = new SparseArray<>();

    // Callback to Jetpack to notify about changes to split states.
    @NonNull
    private Consumer<List<SplitInfo>> mEmbeddingCallback;
    private final List<SplitInfo> mLastReportedSplitStates = new ArrayList<>();
    private final Handler mHandler;
    private final Object mLock = new Object();

    public SplitController() {
        final MainThreadExecutor executor = new MainThreadExecutor();
        mHandler = executor.mHandler;
        mPresenter = new SplitPresenter(executor, this);
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
        synchronized (mLock) {
            mSplitRules.clear();
            mSplitRules.addAll(rules);
            for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
                updateAnimationOverride(mTaskContainers.valueAt(i));
            }
        }
    }

    @NonNull
    List<EmbeddingRule> getSplitRules() {
        return mSplitRules;
    }

    /**
     * Registers the split organizer callback to notify about changes to active splits.
     */
    @Override
    public void setSplitInfoCallback(@NonNull Consumer<List<SplitInfo>> callback) {
        synchronized (mLock) {
            mEmbeddingCallback = callback;
            updateCallbackIfNecessary();
        }
    }

    @Override
    public void onTaskFragmentAppeared(@NonNull TaskFragmentInfo taskFragmentInfo) {
        synchronized (mLock) {
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
    }

    @Override
    public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {
        synchronized (mLock) {
            TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
            if (container == null) {
                return;
            }

            final WindowContainerTransaction wct = new WindowContainerTransaction();
            final boolean wasInPip = isInPictureInPicture(container);
            container.setInfo(taskFragmentInfo);
            final boolean isInPip = isInPictureInPicture(container);
            // Check if there are no running activities - consider the container empty if there are
            // no non-finishing activities left.
            if (!taskFragmentInfo.hasRunningActivity()) {
                if (taskFragmentInfo.isTaskFragmentClearedForPip()) {
                    // Do not finish the dependents if the last activity is reparented to PiP.
                    // Instead, the original split should be cleanup, and the dependent may be
                    // expanded to fullscreen.
                    cleanupForEnterPip(wct, container);
                    mPresenter.cleanupContainer(container, false /* shouldFinishDependent */, wct);
                } else if (taskFragmentInfo.isTaskClearedForReuse()) {
                    // Do not finish the dependents if this TaskFragment was cleared due to
                    // launching activity in the Task.
                    mPresenter.cleanupContainer(container, false /* shouldFinishDependent */, wct);
                } else if (!container.isWaitingActivityAppear()) {
                    // Do not finish the container before the expected activity appear until
                    // timeout.
                    mPresenter.cleanupContainer(container, true /* shouldFinishDependent */, wct);
                }
            } else if (wasInPip && isInPip) {
                // No update until exit PIP.
                return;
            } else if (isInPip) {
                // Enter PIP.
                // All overrides will be cleanup.
                container.setLastRequestedBounds(null /* bounds */);
                container.setLastRequestedWindowingMode(WINDOWING_MODE_UNDEFINED);
                cleanupForEnterPip(wct, container);
            } else if (wasInPip) {
                // Exit PIP.
                // Updates the presentation of the container. Expand or launch placeholder if
                // needed.
                updateContainer(wct, container);
            }
            mPresenter.applyTransaction(wct);
            updateCallbackIfNecessary();
        }
    }

    @Override
    public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {
        synchronized (mLock) {
            final TaskFragmentContainer container = getContainer(
                    taskFragmentInfo.getFragmentToken());
            if (container != null) {
                // Cleanup if the TaskFragment vanished is not requested by the organizer.
                removeContainer(container);
                // Make sure the top container is updated.
                final TaskFragmentContainer newTopContainer = getTopActiveContainer(
                        container.getTaskId());
                if (newTopContainer != null) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    updateContainer(wct, newTopContainer);
                    mPresenter.applyTransaction(wct);
                }
                updateCallbackIfNecessary();
            }
            cleanupTaskFragment(taskFragmentInfo.getFragmentToken());
        }
    }

    @Override
    public void onTaskFragmentParentInfoChanged(@NonNull IBinder fragmentToken,
            @NonNull Configuration parentConfig) {
        synchronized (mLock) {
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
    }

    @Override
    public void onActivityReparentToTask(int taskId, @NonNull Intent activityIntent,
            @NonNull IBinder activityToken) {
        synchronized (mLock) {
            // If the activity belongs to the current app process, we treat it as a new activity
            // launch.
            final Activity activity = getActivity(activityToken);
            if (activity != null) {
                // We don't allow split as primary for new launch because we currently only support
                // launching to top. We allow split as primary for activity reparent because the
                // activity may be split as primary before it is reparented out. In that case, we
                // want to show it as primary again when it is reparented back.
                if (!resolveActivityToContainer(activity, true /* isOnReparent */)) {
                    // When there is no embedding rule matched, try to place it in the top container
                    // like a normal launch.
                    placeActivityInTopContainer(activity);
                }
                updateCallbackIfNecessary();
                return;
            }

            final TaskContainer taskContainer = getTaskContainer(taskId);
            if (taskContainer == null || taskContainer.isInPictureInPicture()) {
                // We don't embed activity when it is in PIP.
                return;
            }

            // If the activity belongs to a different app process, we treat it as starting new
            // intent, since both actions might result in a new activity that should appear in an
            // organized TaskFragment.
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            TaskFragmentContainer targetContainer = resolveStartActivityIntent(wct, taskId,
                    activityIntent, null /* launchingActivity */);
            if (targetContainer == null) {
                // When there is no embedding rule matched, try to place it in the top container
                // like a normal launch.
                targetContainer = taskContainer.getTopTaskFragmentContainer();
            }
            if (targetContainer == null) {
                return;
            }
            wct.reparentActivityToTaskFragment(targetContainer.getTaskFragmentToken(),
                    activityToken);
            mPresenter.applyTransaction(wct);
            // Because the activity does not belong to the organizer process, we wait until
            // onTaskFragmentAppeared to trigger updateCallbackIfNecessary().
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
                mPresenter.stopOverrideSplitAnimation(taskContainer.getTaskId());
                mTaskContainers.remove(taskContainer.getTaskId());
            }
            return;
        }
    }

    private void onTaskConfigurationChanged(int taskId, @NonNull Configuration config) {
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        if (taskContainer == null) {
            return;
        }
        final boolean wasInPip = taskContainer.isInPictureInPicture();
        final boolean isInPIp = isInPictureInPicture(config);
        taskContainer.setWindowingMode(config.windowConfiguration.getWindowingMode());

        // We need to check the animation override when enter/exit PIP or has bounds changed.
        boolean shouldUpdateAnimationOverride = wasInPip != isInPIp;
        if (taskContainer.setTaskBounds(config.windowConfiguration.getBounds())
                && !isInPIp) {
            // We don't care the bounds change when it has already entered PIP.
            shouldUpdateAnimationOverride = true;
        }
        if (shouldUpdateAnimationOverride) {
            updateAnimationOverride(taskContainer);
        }
    }

    /**
     * Updates if we should override transition animation. We only want to override if the Task
     * bounds is large enough for at least one split rule.
     */
    private void updateAnimationOverride(@NonNull TaskContainer taskContainer) {
        if (!taskContainer.isTaskBoundsInitialized()
                || !taskContainer.isWindowingModeInitialized()) {
            // We don't know about the Task bounds/windowingMode yet.
            return;
        }

        // We only want to override if it supports split.
        if (supportSplit(taskContainer)) {
            mPresenter.startOverrideSplitAnimation(taskContainer.getTaskId());
        } else {
            mPresenter.stopOverrideSplitAnimation(taskContainer.getTaskId());
        }
    }

    private boolean supportSplit(@NonNull TaskContainer taskContainer) {
        // No split inside PIP.
        if (taskContainer.isInPictureInPicture()) {
            return false;
        }
        // Check if the parent container bounds can support any split rule.
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitRule)) {
                continue;
            }
            if (shouldShowSideBySide(taskContainer.getTaskBounds(), (SplitRule) rule)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    void onActivityCreated(@NonNull Activity launchedActivity) {
        // TODO(b/229680885): we don't support launching into primary yet because we want to always
        // launch the new activity on top.
        resolveActivityToContainer(launchedActivity, false /* isOnReparent */);
        updateCallbackIfNecessary();
    }

    /**
     * Checks if the new added activity should be routed to a particular container. It can create a
     * new container for the activity and a new split container if necessary.
     * @param activity      the activity that is newly added to the Task.
     * @param isOnReparent  whether the activity is reparented to the Task instead of new launched.
     *                      We only support to split as primary for reparented activity for now.
     * @return {@code true} if the activity has been handled, such as placed in a TaskFragment, or
     *         in a state that the caller shouldn't handle.
     */
    @VisibleForTesting
    boolean resolveActivityToContainer(@NonNull Activity activity, boolean isOnReparent) {
        if (isInPictureInPicture(activity) || activity.isFinishing()) {
            // We don't embed activity when it is in PIP, or finishing. Return true since we don't
            // want any extra handling.
            return true;
        }

        if (!isOnReparent && getContainerWithActivity(activity) == null
                && getInitialTaskFragmentToken(activity) != null) {
            // We can't find the new launched activity in any recorded container, but it is
            // currently placed in an embedded TaskFragment. This can happen in two cases:
            // 1. the activity is embedded in another app.
            // 2. the organizer has already requested to remove the TaskFragment.
            // In either case, return true since we don't want any extra handling.
            Log.d(TAG, "Activity is in a TaskFragment that is not recorded by the organizer. r="
                    + activity);
            return true;
        }

        /*
         * We will check the following to see if there is any embedding rule matched:
         * 1. Whether the new launched activity should always expand.
         * 2. Whether the new launched activity should launch a placeholder.
         * 3. Whether the new launched activity has already been in a split with a rule matched
         *    (likely done in #onStartActivity).
         * 4. Whether the activity below (if any) should be split with the new launched activity.
         * 5. Whether the activity split with the activity below (if any) should be split with the
         *    new launched activity.
         */

        // 1. Whether the new launched activity should always expand.
        if (shouldExpand(activity, null /* intent */)) {
            expandActivity(activity);
            return true;
        }

        // 2. Whether the new launched activity should launch a placeholder.
        if (launchPlaceholderIfNecessary(activity, !isOnReparent)) {
            return true;
        }

        // 3. Whether the new launched activity has already been in a split with a rule matched.
        if (isNewActivityInSplitWithRuleMatched(activity)) {
            return true;
        }

        // 4. Whether the activity below (if any) should be split with the new launched activity.
        final Activity activityBelow = findActivityBelow(activity);
        if (activityBelow == null) {
            // Can't find any activity below.
            return false;
        }
        if (putActivitiesIntoSplitIfNecessary(activityBelow, activity)) {
            // Have split rule of [ activityBelow | launchedActivity ].
            return true;
        }
        if (isOnReparent && putActivitiesIntoSplitIfNecessary(activity, activityBelow)) {
            // Have split rule of [ launchedActivity | activityBelow].
            return true;
        }

        // 5. Whether the activity split with the activity below (if any) should be split with the
        //    new launched activity.
        final TaskFragmentContainer activityBelowContainer = getContainerWithActivity(
                activityBelow);
        final SplitContainer topSplit = getActiveSplitForContainer(activityBelowContainer);
        if (topSplit == null || !isTopMostSplit(topSplit)) {
            // Skip if it is not the topmost split.
            return false;
        }
        final TaskFragmentContainer otherTopContainer =
                topSplit.getPrimaryContainer() == activityBelowContainer
                        ? topSplit.getSecondaryContainer()
                        : topSplit.getPrimaryContainer();
        final Activity otherTopActivity = otherTopContainer.getTopNonFinishingActivity();
        if (otherTopActivity == null || otherTopActivity == activity) {
            // Can't find the top activity on the other split TaskFragment.
            return false;
        }
        if (putActivitiesIntoSplitIfNecessary(otherTopActivity, activity)) {
            // Have split rule of [ otherTopActivity | launchedActivity ].
            return true;
        }
        // Have split rule of [ launchedActivity | otherTopActivity].
        return isOnReparent && putActivitiesIntoSplitIfNecessary(activity, otherTopActivity);
    }

    /**
     * Places the given activity to the top most TaskFragment in the task if there is any.
     */
    @VisibleForTesting
    void placeActivityInTopContainer(@NonNull Activity activity) {
        if (getContainerWithActivity(activity) != null) {
            // The activity has already been put in a TaskFragment. This is likely to be done by
            // the server when the activity is started.
            return;
        }
        final int taskId = getTaskId(activity);
        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer == null) {
            return;
        }
        final TaskFragmentContainer targetContainer = taskContainer.getTopTaskFragmentContainer();
        if (targetContainer == null) {
            return;
        }
        targetContainer.addPendingAppearedActivity(activity);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparentActivityToTaskFragment(targetContainer.getTaskFragmentToken(),
                activity.getActivityToken());
        mPresenter.applyTransaction(wct);
    }

    /**
     * Starts an activity to side of the launchingActivity with the provided split config.
     */
    private void startActivityToSide(@NonNull Activity launchingActivity, @NonNull Intent intent,
            @Nullable Bundle options, @NonNull SplitRule sideRule,
            @Nullable Consumer<Exception> failureCallback, boolean isPlaceholder) {
        try {
            mPresenter.startActivityToSide(launchingActivity, intent, options, sideRule,
                    isPlaceholder);
        } catch (Exception e) {
            if (failureCallback != null) {
                failureCallback.accept(e);
            }
        }
    }

    /**
     * Expands the given activity by either expanding the TaskFragment it is currently in or putting
     * it into a new expanded TaskFragment.
     */
    private void expandActivity(@NonNull Activity activity) {
        final TaskFragmentContainer container = getContainerWithActivity(activity);
        if (shouldContainerBeExpanded(container)) {
            // Make sure that the existing container is expanded.
            mPresenter.expandTaskFragment(container.getTaskFragmentToken());
        } else {
            // Put activity into a new expanded container.
            final TaskFragmentContainer newContainer = newContainer(activity, getTaskId(activity));
            mPresenter.expandActivity(newContainer.getTaskFragmentToken(), activity);
        }
    }

    /** Whether the given new launched activity is in a split with a rule matched. */
    private boolean isNewActivityInSplitWithRuleMatched(@NonNull Activity launchedActivity) {
        final TaskFragmentContainer container = getContainerWithActivity(launchedActivity);
        final SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer == null) {
            return false;
        }

        if (container == splitContainer.getPrimaryContainer()) {
            // The new launched can be in the primary container when it is starting a new activity
            // onCreate.
            final TaskFragmentContainer secondaryContainer = splitContainer.getSecondaryContainer();
            final Intent secondaryIntent = secondaryContainer.getPendingAppearedIntent();
            if (secondaryIntent != null) {
                // Check with the pending Intent before it is started on the server side.
                // This can happen if the launched Activity start a new Intent to secondary during
                // #onCreated().
                return getSplitRule(launchedActivity, secondaryIntent) != null;
            }
            final Activity secondaryActivity = secondaryContainer.getTopNonFinishingActivity();
            return secondaryActivity != null
                    && getSplitRule(launchedActivity, secondaryActivity) != null;
        }

        // Check if the new launched activity is a placeholder.
        if (splitContainer.getSplitRule() instanceof SplitPlaceholderRule) {
            final SplitPlaceholderRule placeholderRule =
                    (SplitPlaceholderRule) splitContainer.getSplitRule();
            final ComponentName placeholderName = placeholderRule.getPlaceholderIntent()
                    .getComponent();
            // TODO(b/232330767): Do we have a better way to check this?
            return placeholderName == null
                    || placeholderName.equals(launchedActivity.getComponentName())
                    || placeholderRule.getPlaceholderIntent().equals(launchedActivity.getIntent());
        }

        // Check if the new launched activity should be split with the primary top activity.
        final Activity primaryActivity = splitContainer.getPrimaryContainer()
                .getTopNonFinishingActivity();
        if (primaryActivity == null) {
            return false;
        }
        /* TODO(b/231845476) we should always respect clearTop.
        final SplitPairRule curSplitRule = (SplitPairRule) splitContainer.getSplitRule();
        final SplitPairRule splitRule = getSplitRule(primaryActivity, launchedActivity);
        return splitRule != null && haveSamePresentation(splitRule, curSplitRule)
                // If the new launched split rule should clear top and it is not the bottom most,
                // it means we should create a new split pair and clear the existing secondary.
                && (!splitRule.shouldClearTop()
                || container.getBottomMostActivity() == launchedActivity);
         */
        return getSplitRule(primaryActivity, launchedActivity) != null;
    }

    /** Finds the activity below the given activity. */
    @Nullable
    private Activity findActivityBelow(@NonNull Activity activity) {
        Activity activityBelow = null;
        final TaskFragmentContainer container = getContainerWithActivity(activity);
        if (container != null) {
            final List<Activity> containerActivities = container.collectNonFinishingActivities();
            final int index = containerActivities.indexOf(activity);
            if (index > 0) {
                activityBelow = containerActivities.get(index - 1);
            }
        }
        if (activityBelow == null) {
            final IBinder belowToken = ActivityClient.getInstance().getActivityTokenBelow(
                    activity.getActivityToken());
            if (belowToken != null) {
                activityBelow = getActivity(belowToken);
            }
        }
        return activityBelow;
    }

    /**
     * Checks if there is a rule to split the two activities. If there is one, puts them into split
     * and returns {@code true}. Otherwise, returns {@code false}.
     */
    private boolean putActivitiesIntoSplitIfNecessary(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        final SplitPairRule splitRule = getSplitRule(primaryActivity, secondaryActivity);
        if (splitRule == null) {
            return false;
        }
        final TaskFragmentContainer primaryContainer = getContainerWithActivity(
                primaryActivity);
        final SplitContainer splitContainer = getActiveSplitForContainer(primaryContainer);
        if (splitContainer != null && primaryContainer == splitContainer.getPrimaryContainer()
                && canReuseContainer(splitRule, splitContainer.getSplitRule())) {
            // Can launch in the existing secondary container if the rules share the same
            // presentation.
            final TaskFragmentContainer secondaryContainer = splitContainer.getSecondaryContainer();
            if (secondaryContainer == getContainerWithActivity(secondaryActivity)
                    && !boundsSmallerThanMinDimensions(secondaryContainer.getLastRequestedBounds(),
                            getMinDimensions(secondaryActivity))) {
                // The activity is already in the target TaskFragment.
                return true;
            }
            secondaryContainer.addPendingAppearedActivity(secondaryActivity);
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            mPresenter.expandSplitContainerIfNeeded(wct, splitContainer, primaryActivity,
                    secondaryActivity, null /* secondaryIntent */);
            wct.reparentActivityToTaskFragment(
                    secondaryContainer.getTaskFragmentToken(),
                    secondaryActivity.getActivityToken());
            mPresenter.applyTransaction(wct);
            return true;
        }
        // Create new split pair.
        mPresenter.createNewSplitContainer(primaryActivity, secondaryActivity, splitRule);
        return true;
    }

    private void onActivityConfigurationChanged(@NonNull Activity activity) {
        if (activity.isFinishing()) {
            // Do nothing if the activity is currently finishing.
            return;
        }

        if (isInPictureInPicture(activity)) {
            // We don't embed activity when it is in PIP.
            return;
        }
        final TaskFragmentContainer currentContainer = getContainerWithActivity(activity);

        if (currentContainer != null) {
            // Changes to activities in controllers are handled in
            // onTaskFragmentParentInfoChanged
            return;
        }

        // Check if activity requires a placeholder
        launchPlaceholderIfNecessary(activity, false /* isOnCreated */);
    }

    @VisibleForTesting
    void onActivityDestroyed(@NonNull Activity activity) {
        // Remove any pending appeared activity, as the server won't send finished activity to the
        // organizer.
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            mTaskContainers.valueAt(i).cleanupPendingAppearedActivity(activity);
        }
        // We didn't trigger the callback if there were any pending appeared activities, so check
        // again after the pending is removed.
        updateCallbackIfNecessary();
    }

    /**
     * Called when we have been waiting too long for the TaskFragment to become non-empty after
     * creation.
     */
    void onTaskFragmentAppearEmptyTimeout(@NonNull TaskFragmentContainer container) {
        mPresenter.cleanupContainer(container, false /* shouldFinishDependent */);
    }

    /**
     * When we are trying to handle a new activity Intent, returns the {@link TaskFragmentContainer}
     * that we should reparent the new activity to if there is any embedding rule matched.
     *
     * @param wct               {@link WindowContainerTransaction} including all the window change
     *                          requests. The caller is responsible to call
     *                          {@link android.window.TaskFragmentOrganizer#applyTransaction}.
     * @param taskId            The Task to start the activity in.
     * @param intent            The {@link Intent} for starting the new launched activity.
     * @param launchingActivity The {@link Activity} that starts the new activity. We will
     *                          prioritize to split the new activity with it if it is not
     *                          {@code null}.
     * @return the {@link TaskFragmentContainer} to start the new activity in. {@code null} if there
     *         is no embedding rule matched.
     */
    @VisibleForTesting
    @Nullable
    TaskFragmentContainer resolveStartActivityIntent(@NonNull WindowContainerTransaction wct,
            int taskId, @NonNull Intent intent, @Nullable Activity launchingActivity) {
        /*
         * We will check the following to see if there is any embedding rule matched:
         * 1. Whether the new activity intent should always expand.
         * 2. Whether the launching activity (if set) should be split with the new activity intent.
         * 3. Whether the top activity (if any) should be split with the new activity intent.
         * 4. Whether the top activity (if any) in other split should be split with the new
         *    activity intent.
         */

        // 1. Whether the new activity intent should always expand.
        if (shouldExpand(null /* activity */, intent)) {
            return createEmptyExpandedContainer(wct, intent, taskId, launchingActivity);
        }

        // 2. Whether the launching activity (if set) should be split with the new activity intent.
        if (launchingActivity != null) {
            final TaskFragmentContainer container = getSecondaryContainerForSplitIfAny(wct,
                    launchingActivity, intent, true /* respectClearTop */);
            if (container != null) {
                return container;
            }
        }

        // 3. Whether the top activity (if any) should be split with the new activity intent.
        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer == null || taskContainer.getTopTaskFragmentContainer() == null) {
            // There is no other activity in the Task to check split with.
            return null;
        }
        final TaskFragmentContainer topContainer = taskContainer.getTopTaskFragmentContainer();
        final Activity topActivity = topContainer.getTopNonFinishingActivity();
        if (topActivity != null && topActivity != launchingActivity) {
            final TaskFragmentContainer container = getSecondaryContainerForSplitIfAny(wct,
                    topActivity, intent, false /* respectClearTop */);
            if (container != null) {
                return container;
            }
        }

        // 4. Whether the top activity (if any) in other split should be split with the new
        //    activity intent.
        final SplitContainer topSplit = getActiveSplitForContainer(topContainer);
        if (topSplit == null) {
            return null;
        }
        final TaskFragmentContainer otherTopContainer =
                topSplit.getPrimaryContainer() == topContainer
                        ? topSplit.getSecondaryContainer()
                        : topSplit.getPrimaryContainer();
        final Activity otherTopActivity = otherTopContainer.getTopNonFinishingActivity();
        if (otherTopActivity != null && otherTopActivity != launchingActivity) {
            return getSecondaryContainerForSplitIfAny(wct, otherTopActivity, intent,
                    false /* respectClearTop */);
        }
        return null;
    }

    /**
     * Returns an empty expanded {@link TaskFragmentContainer} that we can launch an activity into.
     */
    @Nullable
    private TaskFragmentContainer createEmptyExpandedContainer(
            @NonNull WindowContainerTransaction wct, @NonNull Intent intent, int taskId,
            @Nullable Activity launchingActivity) {
        // We need an activity in the organizer process in the same Task to use as the owner
        // activity, as well as to get the Task window info.
        final Activity activityInTask;
        if (launchingActivity != null) {
            activityInTask = launchingActivity;
        } else {
            final TaskContainer taskContainer = getTaskContainer(taskId);
            activityInTask = taskContainer != null
                    ? taskContainer.getTopNonFinishingActivity()
                    : null;
        }
        if (activityInTask == null) {
            // Can't find any activity in the Task that we can use as the owner activity.
            return null;
        }
        final TaskFragmentContainer expandedContainer = newContainer(intent, activityInTask,
                taskId);
        mPresenter.createTaskFragment(wct, expandedContainer.getTaskFragmentToken(),
                activityInTask.getActivityToken(), new Rect(), WINDOWING_MODE_UNDEFINED);
        return expandedContainer;
    }

    /**
     * Returns a container for the new activity intent to launch into as splitting with the primary
     * activity.
     */
    @Nullable
    private TaskFragmentContainer getSecondaryContainerForSplitIfAny(
            @NonNull WindowContainerTransaction wct, @NonNull Activity primaryActivity,
            @NonNull Intent intent, boolean respectClearTop) {
        final SplitPairRule splitRule = getSplitRule(primaryActivity, intent);
        if (splitRule == null) {
            return null;
        }
        final TaskFragmentContainer existingContainer = getContainerWithActivity(primaryActivity);
        final SplitContainer splitContainer = getActiveSplitForContainer(existingContainer);
        if (splitContainer != null && existingContainer == splitContainer.getPrimaryContainer()
                && (canReuseContainer(splitRule, splitContainer.getSplitRule())
                // TODO(b/231845476) we should always respect clearTop.
                || !respectClearTop)) {
            mPresenter.expandSplitContainerIfNeeded(wct, splitContainer, primaryActivity,
                    null /* secondaryActivity */, intent);
            // Can launch in the existing secondary container if the rules share the same
            // presentation.
            return splitContainer.getSecondaryContainer();
        }
        // Create a new TaskFragment to split with the primary activity for the new activity.
        return mPresenter.createNewSplitWithEmptySideContainer(wct, primaryActivity, intent,
                splitRule);
    }

    /**
     * Returns a container that this activity is registered with. An activity can only belong to one
     * container, or no container at all.
     */
    @Nullable
    TaskFragmentContainer getContainerWithActivity(@NonNull Activity activity) {
        final IBinder activityToken = activity.getActivityToken();
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            // Traverse from top to bottom in case an activity is added to top pending, and hasn't
            // received update from server yet.
            for (int j = containers.size() - 1; j >= 0; j--) {
                final TaskFragmentContainer container = containers.get(j);
                if (container.hasActivity(activityToken)) {
                    return container;
                }
            }
        }
        return null;
    }

    TaskFragmentContainer newContainer(@NonNull Activity pendingAppearedActivity, int taskId) {
        return newContainer(pendingAppearedActivity, pendingAppearedActivity, taskId);
    }

    TaskFragmentContainer newContainer(@NonNull Activity pendingAppearedActivity,
            @NonNull Activity activityInTask, int taskId) {
        return newContainer(pendingAppearedActivity, null /* pendingAppearedIntent */,
                activityInTask, taskId);
    }

    TaskFragmentContainer newContainer(@NonNull Intent pendingAppearedIntent,
            @NonNull Activity activityInTask, int taskId) {
        return newContainer(null /* pendingAppearedActivity */, pendingAppearedIntent,
                activityInTask, taskId);
    }

    /**
     * Creates and registers a new organized container with an optional activity that will be
     * re-parented to it in a WCT.
     *
     * @param pendingAppearedActivity   the activity that will be reparented to the TaskFragment.
     * @param pendingAppearedIntent     the Intent that will be started in the TaskFragment.
     * @param activityInTask            activity in the same Task so that we can get the Task bounds
     *                                  if needed.
     * @param taskId                    parent Task of the new TaskFragment.
     */
    TaskFragmentContainer newContainer(@Nullable Activity pendingAppearedActivity,
            @Nullable Intent pendingAppearedIntent, @NonNull Activity activityInTask, int taskId) {
        if (activityInTask == null) {
            throw new IllegalArgumentException("activityInTask must not be null,");
        }
        if (!mTaskContainers.contains(taskId)) {
            mTaskContainers.put(taskId, new TaskContainer(taskId));
        }
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        final TaskFragmentContainer container = new TaskFragmentContainer(pendingAppearedActivity,
                pendingAppearedIntent, taskContainer, this);
        if (!taskContainer.isTaskBoundsInitialized()) {
            // Get the initial bounds before the TaskFragment has appeared.
            final Rect taskBounds = SplitPresenter.getTaskBoundsFromActivity(activityInTask);
            if (!taskContainer.setTaskBounds(taskBounds)) {
                Log.w(TAG, "Can't find bounds from activity=" + activityInTask);
            }
        }
        if (!taskContainer.isWindowingModeInitialized()) {
            taskContainer.setWindowingMode(activityInTask.getResources().getConfiguration()
                    .windowConfiguration.getWindowingMode());
        }
        updateAnimationOverride(taskContainer);
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
        primaryContainer.getTaskContainer().mSplitContainers.add(splitContainer);
    }

    /** Cleanups all the dependencies when the TaskFragment is entering PIP. */
    private void cleanupForEnterPip(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        final TaskContainer taskContainer = container.getTaskContainer();
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
        for (TaskFragmentContainer containerToUpdate : containersToUpdate) {
            updateContainer(wct, containerToUpdate);
        }
    }

    /**
     * Removes the container from bookkeeping records.
     */
    void removeContainer(@NonNull TaskFragmentContainer container) {
        // Remove all split containers that included this one
        final TaskContainer taskContainer = container.getTaskContainer();
        if (taskContainer == null) {
            return;
        }
        taskContainer.mContainers.remove(container);
        // Marked as a pending removal which will be removed after it is actually removed on the
        // server side (#onTaskFragmentVanished).
        // In this way, we can keep track of the Task bounds until we no longer have any
        // TaskFragment there.
        taskContainer.mFinishedContainer.add(container.getTaskFragmentToken());

        // Cleanup any split references.
        final List<SplitContainer> containersToRemove = new ArrayList<>();
        for (SplitContainer splitContainer : taskContainer.mSplitContainers) {
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                containersToRemove.add(splitContainer);
            }
        }
        taskContainer.mSplitContainers.removeAll(containersToRemove);

        // Cleanup any dependent references.
        for (TaskFragmentContainer containerToUpdate : taskContainer.mContainers) {
            containerToUpdate.removeContainerToFinishOnExit(container);
        }
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
            if (!container.isFinished() && (container.getRunningActivityCount() > 0
                    // We may be waiting for the top TaskFragment to become non-empty after
                    // creation. In that case, we don't want to treat the TaskFragment below it as
                    // top active, otherwise it may incorrectly launch placeholder on top of the
                    // pending TaskFragment.
                    || container.isWaitingActivityAppear())) {
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
        if (!isTopMostSplit(splitContainer)) {
            // Skip position update - it isn't the topmost split.
            return;
        }
        if (splitContainer.getPrimaryContainer().isFinished()
                || splitContainer.getSecondaryContainer().isFinished()) {
            // Skip position update - one or both containers are finished.
            return;
        }
        if (dismissPlaceholderIfNecessary(splitContainer)) {
            // Placeholder was finished, the positions will be updated when its container is emptied
            return;
        }
        mPresenter.updateSplitContainer(splitContainer, container, wct);
    }

    /** Whether the given split is the topmost split in the Task. */
    private boolean isTopMostSplit(@NonNull SplitContainer splitContainer) {
        final List<SplitContainer> splitContainers = splitContainer.getPrimaryContainer()
                .getTaskContainer().mSplitContainers;
        return splitContainer == splitContainers.get(splitContainers.size() - 1);
    }

    /**
     * Returns the top active split container that has the provided container, if available.
     */
    @Nullable
    private SplitContainer getActiveSplitForContainer(@Nullable TaskFragmentContainer container) {
        if (container == null) {
            return null;
        }
        final List<SplitContainer> splitContainers = container.getTaskContainer().mSplitContainers;
        if (splitContainers.isEmpty()) {
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
    @VisibleForTesting
    @Nullable
    SplitContainer getActiveSplitForContainers(
            @NonNull TaskFragmentContainer firstContainer,
            @NonNull TaskFragmentContainer secondContainer) {
        final List<SplitContainer> splitContainers = firstContainer.getTaskContainer()
                .mSplitContainers;
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

        return launchPlaceholderIfNecessary(topActivity, false /* isOnCreated */);
    }

    boolean launchPlaceholderIfNecessary(@NonNull Activity activity, boolean isOnCreated) {
        if (activity.isFinishing()) {
            return false;
        }

        final TaskFragmentContainer container = getContainerWithActivity(activity);
        // Don't launch placeholder if the container is occluded.
        if (container != null && container != getTopActiveContainer(container.getTaskId())) {
            return false;
        }

        final SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer != null && container.equals(splitContainer.getPrimaryContainer())) {
            // Don't launch placeholder in primary split container
            return false;
        }

        // Check if there is enough space for launch
        final SplitPlaceholderRule placeholderRule = getPlaceholderRule(activity);

        if (placeholderRule == null) {
            return false;
        }

        final Pair<Size, Size> minDimensionsPair = getActivityIntentMinDimensionsPair(activity,
                placeholderRule.getPlaceholderIntent());
        if (!shouldShowSideBySide(
                mPresenter.getParentContainerBounds(activity), placeholderRule,
                minDimensionsPair)) {
            return false;
        }

        // TODO(b/190433398): Handle failed request
        final Bundle options = getPlaceholderOptions(activity, isOnCreated);
        startActivityToSide(activity, placeholderRule.getPlaceholderIntent(), options,
                placeholderRule, null /* failureCallback */, true /* isPlaceholder */);
        return true;
    }

    /**
     * Gets the activity options for starting the placeholder activity. In case the placeholder is
     * launched when the Task is in the background, we don't want to bring the Task to the front.
     * @param primaryActivity   the primary activity to launch the placeholder from.
     * @param isOnCreated       whether this happens during the primary activity onCreated.
     */
    @VisibleForTesting
    @Nullable
    Bundle getPlaceholderOptions(@NonNull Activity primaryActivity, boolean isOnCreated) {
        // Setting avoid move to front will also skip the animation. We only want to do that when
        // the Task is currently in background.
        // Check if the primary is resumed or if this is called when the primary is onCreated
        // (not resumed yet).
        if (isOnCreated || primaryActivity.isResumed()) {
            return null;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setAvoidMoveToFront();
        return options.toBundle();
    }

    @VisibleForTesting
    boolean dismissPlaceholderIfNecessary(@NonNull SplitContainer splitContainer) {
        if (!splitContainer.isPlaceholderContainer()) {
            return false;
        }

        if (isStickyPlaceholderRule(splitContainer.getSplitRule())) {
            // The placeholder should remain after it was first shown.
            return false;
        }

        if (shouldShowSideBySide(splitContainer)) {
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

    /**
     * Notifies listeners about changes to split states if necessary.
     */
    private void updateCallbackIfNecessary() {
        if (mEmbeddingCallback == null) {
            return;
        }
        if (!allActivitiesCreated()) {
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
                        shouldShowSideBySide(container)
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
                if (!container.taskInfoActivityCountMatchesCreated()) {
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
        return getActiveSplitForContainer(container) == null;
    }

    /**
     * Returns a split rule for the provided pair of primary activity and secondary activity intent
     * if available.
     */
    @Nullable
    private SplitPairRule getSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryActivityIntent) {
        for (EmbeddingRule rule : mSplitRules) {
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
    private SplitPairRule getSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        for (EmbeddingRule rule : mSplitRules) {
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

    @Nullable
    TaskContainer getTaskContainer(int taskId) {
        return mTaskContainers.get(taskId);
    }

    Handler getHandler() {
        return mHandler;
    }

    int getTaskId(@NonNull Activity activity) {
        // Prefer to get the taskId from TaskFragmentContainer because Activity.getTaskId() is an
        // IPC call.
        final TaskFragmentContainer container = getContainerWithActivity(activity);
        return container != null ? container.getTaskId() : activity.getTaskId();
    }

    @Nullable
    Activity getActivity(@NonNull IBinder activityToken) {
        return ActivityThread.currentActivityThread().getActivity(activityToken);
    }

    /**
     * Gets the token of the initial TaskFragment that embedded this activity. Do not rely on it
     * after creation because the activity could be reparented.
     */
    @VisibleForTesting
    @Nullable
    IBinder getInitialTaskFragmentToken(@NonNull Activity activity) {
        final ActivityThread.ActivityClientRecord record = ActivityThread.currentActivityThread()
                .getActivityClient(activity.getActivityToken());
        return record != null ? record.mInitialTaskFragmentToken : null;
    }

    /**
     * Returns {@code true} if an Activity with the provided component name should always be
     * expanded to occupy full task bounds. Such activity must not be put in a split.
     */
    private boolean shouldExpand(@Nullable Activity activity, @Nullable Intent intent) {
        for (EmbeddingRule rule : mSplitRules) {
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
        if (shouldShowSideBySide(splitContainer)) {
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
        final TaskFragmentContainer associatedContainer = getContainerWithActivity(
                associatedActivity);
        if (associatedContainer == null) {
            return false;
        }

        return shouldRetainAssociatedContainer(finishingContainer, associatedContainer);
    }

    private final class LifecycleCallbacks extends EmptyLifecycleCallbacksAdapter {

        @Override
        public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
            synchronized (mLock) {
                final IBinder activityToken = activity.getActivityToken();
                final IBinder initialTaskFragmentToken = getInitialTaskFragmentToken(activity);
                // If the activity is not embedded, then it will not have an initial task fragment
                // token so no further action is needed.
                if (initialTaskFragmentToken == null) {
                    return;
                }
                for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
                    final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i)
                            .mContainers;
                    for (int j = containers.size() - 1; j >= 0; j--) {
                        final TaskFragmentContainer container = containers.get(j);
                        if (!container.hasActivity(activityToken)
                                && container.getTaskFragmentToken()
                                .equals(initialTaskFragmentToken)) {
                            // The onTaskFragmentInfoChanged callback containing this activity has
                            // not reached the client yet, so add the activity to the pending
                            // appeared activities.
                            container.addPendingAppearedActivity(activity);
                            return;
                        }
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
            synchronized (mLock) {
                SplitController.this.onActivityCreated(activity);
            }
        }

        @Override
        public void onActivityConfigurationChanged(Activity activity) {
            synchronized (mLock) {
                SplitController.this.onActivityConfigurationChanged(activity);
            }
        }

        @Override
        public void onActivityPostDestroyed(Activity activity) {
            synchronized (mLock) {
                SplitController.this.onActivityDestroyed(activity);
            }
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

            synchronized (mLock) {
                final int taskId = getTaskId(launchingActivity);
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                final TaskFragmentContainer launchedInTaskFragment = resolveStartActivityIntent(wct,
                        taskId, intent, launchingActivity);
                if (launchedInTaskFragment != null) {
                    mPresenter.applyTransaction(wct);
                    // Amend the request to let the WM know that the activity should be placed in
                    // the dedicated container.
                    options.putBinder(ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN,
                            launchedInTaskFragment.getTaskFragmentToken());
                }
            }

            return super.onStartActivity(who, intent, options);
        }
    }

    /**
     * Checks if an activity is embedded and its presentation is customized by a
     * {@link android.window.TaskFragmentOrganizer} to only occupy a portion of Task bounds.
     */
    @Override
    public boolean isActivityEmbedded(@NonNull Activity activity) {
        synchronized (mLock) {
            return mPresenter.isActivityEmbedded(activity.getActivityToken());
        }
    }

    /**
     * If the two rules have the same presentation, we can reuse the same {@link SplitContainer} if
     * there is any.
     */
    private static boolean canReuseContainer(SplitRule rule1, SplitRule rule2) {
        if (!isContainerReusableRule(rule1) || !isContainerReusableRule(rule2)) {
            return false;
        }
        return haveSamePresentation((SplitPairRule) rule1, (SplitPairRule) rule2);
    }

    /** Whether the two rules have the same presentation. */
    private static boolean haveSamePresentation(SplitPairRule rule1, SplitPairRule rule2) {
        // TODO(b/231655482): add util method to do the comparison in SplitPairRule.
        return rule1.getSplitRatio() == rule2.getSplitRatio()
                && rule1.getLayoutDirection() == rule2.getLayoutDirection()
                && rule1.getFinishPrimaryWithSecondary()
                == rule2.getFinishPrimaryWithSecondary()
                && rule1.getFinishSecondaryWithPrimary()
                == rule2.getFinishSecondaryWithPrimary();
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
}
