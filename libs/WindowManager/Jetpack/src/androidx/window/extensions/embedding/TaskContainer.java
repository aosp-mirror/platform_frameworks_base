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

import static androidx.window.extensions.embedding.DividerPresenter.RATIO_EXPANDED_PRIMARY;
import static androidx.window.extensions.embedding.DividerPresenter.RATIO_EXPANDED_SECONDARY;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.core.util.function.Predicate;
import androidx.window.extensions.embedding.SplitAttributes.SplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.ExpandContainersSplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.RatioSplitType;

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
    private final List<TaskFragmentContainer> mContainers = new ArrayList<>();

    /** Active split pairs in this Task. */
    @NonNull
    private final List<SplitContainer> mSplitContainers = new ArrayList<>();

    /** Active pin split pair in this Task. */
    @Nullable
    private SplitPinContainer mSplitPinContainer;

    /**
     * The {@link TaskFragmentContainer#isAlwaysOnTopOverlay()} in the Task.
     */
    @Nullable
    private TaskFragmentContainer mAlwaysOnTopOverlayContainer;

    @NonNull
    private final Configuration mConfiguration;

    private int mDisplayId;

    private boolean mIsVisible;

    private boolean mHasDirectActivity;

    @Nullable
    private TaskFragmentParentInfo mTaskFragmentParentInfo;

    /**
     * TaskFragments that the organizer has requested to be closed. They should be removed when
     * the organizer receives
     * {@link SplitController#onTaskFragmentVanished(WindowContainerTransaction, TaskFragmentInfo)}
     * event for them.
     */
    final Set<IBinder> mFinishedContainer = new ArraySet<>();

    /**
     * The {@link RatioSplitType} that will be applied to newly added containers. This is to ensure
     * the required UX that, after user dragging the divider, the split ratio is persistent after
     * launching a new activity into a new TaskFragment in the same Task.
     */
    private RatioSplitType mOverrideSplitType;

    /**
     * If {@code true}, suppress the placeholder rules in the {@link TaskContainer}.
     * <p>
     * This is used in case the user drags the divider to fully expand the primary container and
     * dismiss the secondary container while a {@link SplitPlaceholderRule} is used. Without this
     * flag, after dismissing the secondary container, a placeholder will be launched again.
     * <p>
     * This flag is set true when the primary container is fully expanded and cleared when a new
     * split is added to the {@link TaskContainer}.
     */
    private boolean mPlaceholderRuleSuppressed;

    /**
     * The {@link TaskContainer} constructor
     *
     * @param taskId         The ID of the Task, which must match {@link Activity#getTaskId()} with
     *                       {@code activityInTask}.
     * @param activityInTask The {@link Activity} in the Task with {@code taskId}. It is used to
     *                       initialize the {@link TaskContainer} properties.
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
        // the host task is visible and has an activity in the task.
        mIsVisible = true;
        mHasDirectActivity = true;
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

    void setIsVisible(boolean visible) {
        mIsVisible = visible;
    }

    boolean hasDirectActivity() {
        return mHasDirectActivity;
    }

    @NonNull
    Rect getBounds() {
        return mConfiguration.windowConfiguration.getBounds();
    }

    @NonNull
    TaskProperties getTaskProperties() {
        return new TaskProperties(mDisplayId, mConfiguration);
    }

    void updateTaskFragmentParentInfo(@NonNull TaskFragmentParentInfo info) {
        // TODO(b/293654166): cache the TaskFragmentParentInfo and remove these fields.
        mConfiguration.setTo(info.getConfiguration());
        mDisplayId = info.getDisplayId();
        mIsVisible = info.isVisible();
        mHasDirectActivity = info.hasDirectActivity();
        mTaskFragmentParentInfo = info;
    }

    @Nullable
    TaskFragmentParentInfo getTaskFragmentParentInfo() {
        return mTaskFragmentParentInfo;
    }

    /**
     * Returns {@code true} if the container should be updated with {@code info}.
     */
    boolean shouldUpdateContainer(@NonNull TaskFragmentParentInfo info) {
        final Configuration configuration = info.getConfiguration();

        if (isInPictureInPicture(configuration)) {
            // No need to update presentation in PIP until the Task exit PIP.
            return false;
        }

        // If the task properties equals regardless of starting position, don't
        // need to update the container.
        return mConfiguration.diffPublicOnly(configuration) != 0
                || mDisplayId != info.getDisplayId();
    }

    /**
     * Returns the windowing mode for the TaskFragments below this Task, which should be split with
     * other TaskFragments.
     *
     * @param taskFragmentBounds Requested bounds for the TaskFragment. It will be empty when
     *                           the pair of TaskFragments are stacked due to the limited space.
     */
    @WindowingMode
    int getWindowingModeForTaskFragment(@Nullable Rect taskFragmentBounds) {
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
        return isInPictureInPicture(mConfiguration);
    }

    private static boolean isInPictureInPicture(@NonNull Configuration configuration) {
        return configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }

    boolean isInMultiWindow() {
        return WindowConfiguration.inMultiWindowMode(getWindowingMode());
    }

    @WindowingMode
    private int getWindowingMode() {
        return mConfiguration.windowConfiguration.getWindowingMode();
    }

    /** Whether there is any {@link TaskFragmentContainer} below this Task. */
    boolean isEmpty() {
        return mContainers.isEmpty() && mFinishedContainer.isEmpty();
    }

    /** Called when the activity {@link Activity#isFinishing()} and paused. */
    void onFinishingActivityPaused(@NonNull WindowContainerTransaction wct,
                                   @NonNull IBinder activityToken) {
        for (TaskFragmentContainer container : mContainers) {
            container.onFinishingActivityPaused(wct, activityToken);
        }
    }

    /** Called when the activity is destroyed. */
    void onActivityDestroyed(@NonNull WindowContainerTransaction wct,
                             @NonNull IBinder activityToken) {
        for (TaskFragmentContainer container : mContainers) {
            container.onActivityDestroyed(wct, activityToken);
        }
    }

    /** Removes the pending appeared activity from all TaskFragments in this Task. */
    void cleanupPendingAppearedActivity(@NonNull IBinder activityToken) {
        for (TaskFragmentContainer container : mContainers) {
            container.removePendingAppearedActivity(activityToken);
        }
    }

    @Nullable
    TaskFragmentContainer getTopNonFinishingTaskFragmentContainer() {
        return getTopNonFinishingTaskFragmentContainer(true /* includePin */);
    }

    @Nullable
    TaskFragmentContainer getTopNonFinishingTaskFragmentContainer(boolean includePin) {
        return getTopNonFinishingTaskFragmentContainer(includePin, false /* includeOverlay */);
    }

    @Nullable
    TaskFragmentContainer getTopNonFinishingTaskFragmentContainer(boolean includePin,
            boolean includeOverlay) {
        for (int i = mContainers.size() - 1; i >= 0; i--) {
            final TaskFragmentContainer container = mContainers.get(i);
            if (!includePin && isTaskFragmentContainerPinned(container)) {
                continue;
            }
            if (!includeOverlay && container.isOverlay()) {
                continue;
            }
            if (!container.isFinished()) {
                return container;
            }
        }
        return null;
    }

    /** Gets a non-finishing container below the given one. */
    @Nullable
    TaskFragmentContainer getNonFinishingTaskFragmentContainerBelow(
            @NonNull TaskFragmentContainer current) {
        final int index = mContainers.indexOf(current);
        for (int i = index - 1; i >= 0; i--) {
            final TaskFragmentContainer container = mContainers.get(i);
            if (!container.isFinished()) {
                return container;
            }
        }
        return null;
    }

    @Nullable
    Activity getTopNonFinishingActivity(boolean includeOverlay) {
        for (int i = mContainers.size() - 1; i >= 0; i--) {
            final TaskFragmentContainer container = mContainers.get(i);
            if (!includeOverlay && container.isOverlay()) {
                continue;
            }
            final Activity activity = container.getTopNonFinishingActivity();
            if (activity != null) {
                return activity;
            }
        }
        return null;
    }

    @Nullable
    TaskFragmentContainer getContainerWithActivity(@NonNull IBinder activityToken) {
        return getContainer(container -> container.hasAppearedActivity(activityToken)
                || container.hasPendingAppearedActivity(activityToken));
    }

    @Nullable
    TaskFragmentContainer getContainer(@NonNull Predicate<TaskFragmentContainer> predicate) {
        for (int i = mContainers.size() - 1; i >= 0; i--) {
            final TaskFragmentContainer container = mContainers.get(i);
            if (predicate.test(container)) {
                return container;
            }
        }
        return null;
    }

    @Nullable
    SplitContainer getActiveSplitForContainer(@Nullable TaskFragmentContainer container) {
        if (container == null) {
            return null;
        }
        for (int i = mSplitContainers.size() - 1; i >= 0; i--) {
            final SplitContainer splitContainer = mSplitContainers.get(i);
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                return splitContainer;
            }
        }
        return null;
    }

    /**
     * Returns the always-on-top overlay container in the task, or {@code null} if it doesn't exist.
     */
    @Nullable
    TaskFragmentContainer getAlwaysOnTopOverlayContainer() {
        return mAlwaysOnTopOverlayContainer;
    }

    int indexOf(@NonNull TaskFragmentContainer child) {
        return mContainers.indexOf(child);
    }

    /** Whether the Task is in an intermediate state waiting for the server update. */
    boolean isInIntermediateState() {
        for (TaskFragmentContainer container : mContainers) {
            if (container.isInIntermediateState()) {
                // We are in an intermediate state to wait for server update on this TaskFragment.
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of {@link SplitContainer}. Do not modify the containers directly on the
     * returned list. Use {@link #addSplitContainer} or {@link #removeSplitContainers} instead.
     */
    @NonNull
    List<SplitContainer> getSplitContainers() {
        return mSplitContainers;
    }

    void addSplitContainer(@NonNull SplitContainer splitContainer) {
        // Reset the placeholder rule suppression when a new split container is added.
        mPlaceholderRuleSuppressed = false;

        applyOverrideSplitTypeIfNeeded(splitContainer);

        if (splitContainer instanceof SplitPinContainer) {
            mSplitPinContainer = (SplitPinContainer) splitContainer;
            mSplitContainers.add(splitContainer);
            return;
        }

        // Keeps the SplitPinContainer on the top of the list.
        mSplitContainers.remove(mSplitPinContainer);
        mSplitContainers.add(splitContainer);
        if (mSplitPinContainer != null) {
            mSplitContainers.add(mSplitPinContainer);
        }
    }

    boolean isPlaceholderRuleSuppressed() {
        return mPlaceholderRuleSuppressed;
    }

    // If there is an override SplitType due to user dragging the divider, the split ratio should
    // be applied to newly added SplitContainers.
    private void applyOverrideSplitTypeIfNeeded(@NonNull SplitContainer splitContainer) {
        if (mOverrideSplitType == null) {
            return;
        }
        final SplitAttributes splitAttributes = splitContainer.getCurrentSplitAttributes();
        final DividerAttributes dividerAttributes = splitAttributes.getDividerAttributes();
        if (!(splitAttributes.getSplitType() instanceof RatioSplitType)) {
            // Skip if the original split type is not a ratio type.
            return;
        }
        if (dividerAttributes == null
                || dividerAttributes.getDividerType() != DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
            // Skip if the split does not have a draggable divider.
            return;
        }
        updateDefaultSplitAttributes(splitContainer, mOverrideSplitType);
    }

    private static void updateDefaultSplitAttributes(
            @NonNull SplitContainer splitContainer, @NonNull SplitType overrideSplitType) {
        splitContainer.updateDefaultSplitAttributes(
                new SplitAttributes.Builder(splitContainer.getDefaultSplitAttributes())
                        .setSplitType(overrideSplitType)
                        .build()
        );
    }

    void removeSplitContainers(@NonNull List<SplitContainer> containers) {
        mSplitContainers.removeAll(containers);
    }

    void removeSplitPinContainer() {
        if (mSplitPinContainer == null) {
            return;
        }

        final TaskFragmentContainer primaryContainer = mSplitPinContainer.getPrimaryContainer();
        final TaskFragmentContainer secondaryContainer = mSplitPinContainer.getSecondaryContainer();
        mSplitContainers.remove(mSplitPinContainer);
        mSplitPinContainer = null;

        // Remove the other SplitContainers that contains the unpinned container (unless it
        // is the current top-most split-pair), since the state are no longer valid.
        final List<SplitContainer> splitsToRemove = new ArrayList<>();
        for (SplitContainer splitContainer : mSplitContainers) {
            if (splitContainer.getSecondaryContainer().equals(secondaryContainer)
                    && !splitContainer.getPrimaryContainer().equals(primaryContainer)) {
                splitsToRemove.add(splitContainer);
            }
        }
        removeSplitContainers(splitsToRemove);
    }

    @Nullable
    SplitPinContainer getSplitPinContainer() {
        return mSplitPinContainer;
    }

    boolean isTaskFragmentContainerPinned(@NonNull TaskFragmentContainer taskFragmentContainer) {
        return mSplitPinContainer != null
                && mSplitPinContainer.getSecondaryContainer() == taskFragmentContainer;
    }

    void addTaskFragmentContainer(@NonNull TaskFragmentContainer taskFragmentContainer) {
        mContainers.add(taskFragmentContainer);
        onTaskFragmentContainerUpdated();
    }

    void addTaskFragmentContainer(int index, @NonNull TaskFragmentContainer taskFragmentContainer) {
        mContainers.add(index, taskFragmentContainer);
        onTaskFragmentContainerUpdated();
    }

    void removeTaskFragmentContainer(@NonNull TaskFragmentContainer taskFragmentContainer) {
        mContainers.remove(taskFragmentContainer);
        onTaskFragmentContainerUpdated();
    }

    void removeTaskFragmentContainers(@NonNull List<TaskFragmentContainer> taskFragmentContainers) {
        mContainers.removeAll(taskFragmentContainers);
        onTaskFragmentContainerUpdated();
    }

    void clearTaskFragmentContainer() {
        mContainers.clear();
        onTaskFragmentContainerUpdated();
    }

    /**
     * Returns a list of {@link TaskFragmentContainer}. Do not modify the containers directly on
     * the returned list. Use {@link #addTaskFragmentContainer},
     * {@link #removeTaskFragmentContainer} or other related methods instead.
     */
    @NonNull
    List<TaskFragmentContainer> getTaskFragmentContainers() {
        return mContainers;
    }

    void updateTopSplitContainerForDivider(
            @NonNull DividerPresenter dividerPresenter,
            @NonNull List<TaskFragmentContainer> outContainersToFinish) {
        final SplitContainer topSplitContainer = getTopNonFinishingSplitContainer();
        if (topSplitContainer == null) {
            return;
        }
        final TaskFragmentContainer primaryContainer = topSplitContainer.getPrimaryContainer();
        final float newRatio = dividerPresenter.calculateNewSplitRatio(topSplitContainer);

        // If the primary container is fully expanded, we should finish all the associated
        // secondary containers.
        if (newRatio == RATIO_EXPANDED_PRIMARY) {
            for (final SplitContainer splitContainer : mSplitContainers) {
                if (primaryContainer == splitContainer.getPrimaryContainer()) {
                    outContainersToFinish.add(splitContainer.getSecondaryContainer());
                }
            }

            // Temporarily suppress the placeholder rule in the TaskContainer. This will be restored
            // if a new split is added into the TaskContainer.
            mPlaceholderRuleSuppressed = true;

            mOverrideSplitType = null;
            return;
        }

        final SplitType newSplitType;
        if (newRatio == RATIO_EXPANDED_SECONDARY) {
            newSplitType = new ExpandContainersSplitType();
            // We do not want to apply ExpandContainersSplitType to new split containers.
            mOverrideSplitType = null;
        } else {
            // We save the override RatioSplitType and apply to new split containers.
            newSplitType = mOverrideSplitType = new RatioSplitType(newRatio);
        }
        for (final SplitContainer splitContainer : mSplitContainers) {
            if (primaryContainer == splitContainer.getPrimaryContainer()) {
                updateDefaultSplitAttributes(splitContainer, newSplitType);
            }
        }
    }

    @Nullable
    SplitContainer getTopNonFinishingSplitContainer() {
        for (int i = mSplitContainers.size() - 1; i >= 0; i--) {
            final SplitContainer splitContainer = mSplitContainers.get(i);
            if (!splitContainer.getPrimaryContainer().isFinished()
                    && !splitContainer.getSecondaryContainer().isFinished()) {
                return splitContainer;
            }
        }
        return null;
    }

    private void onTaskFragmentContainerUpdated() {
        // TODO(b/300211704): Find a better mechanism to handle the z-order in case we introduce
        //  another special container that should also be on top in the future.
        updateSplitPinContainerIfNecessary();
        // Update overlay container after split pin container since the overlay should be on top of
        // pin container.
        updateAlwaysOnTopOverlayIfNecessary();
    }

    private void updateAlwaysOnTopOverlayIfNecessary() {
        final List<TaskFragmentContainer> alwaysOnTopOverlays = mContainers
                .stream().filter(TaskFragmentContainer::isAlwaysOnTopOverlay).toList();
        if (alwaysOnTopOverlays.size() > 1) {
            throw new IllegalStateException("There must be at most one always-on-top overlay "
                    + "container per Task");
        }
        mAlwaysOnTopOverlayContainer = alwaysOnTopOverlays.isEmpty()
                ? null : alwaysOnTopOverlays.getFirst();
        if (mAlwaysOnTopOverlayContainer != null) {
            moveContainerToLastIfNecessary(mAlwaysOnTopOverlayContainer);
        }
    }

    private void updateSplitPinContainerIfNecessary() {
        if (mSplitPinContainer == null) {
            return;
        }

        final TaskFragmentContainer pinnedContainer = mSplitPinContainer.getSecondaryContainer();
        final int pinnedContainerIndex = mContainers.indexOf(pinnedContainer);
        if (pinnedContainerIndex <= 0) {
            removeSplitPinContainer();
            return;
        }

        // Ensure the pinned container is top-most.
        moveContainerToLastIfNecessary(pinnedContainer);

        // Update the primary container adjacent to the pinned container if needed.
        final TaskFragmentContainer adjacentContainer =
                getNonFinishingTaskFragmentContainerBelow(pinnedContainer);
        if (adjacentContainer == null) {
            removeSplitPinContainer();
        } else if (mSplitPinContainer.getPrimaryContainer() != adjacentContainer) {
            mSplitPinContainer.setPrimaryContainer(adjacentContainer);
        }
    }

    /** Moves the {@code container} to the last to align taskFragments' z-order. */
    private void moveContainerToLastIfNecessary(@NonNull TaskFragmentContainer container) {
        final int index = mContainers.indexOf(container);
        if (index < 0) {
            Log.w(TAG, "The container:" + container + " is not in the container list!");
            return;
        }
        if (index != mContainers.size() - 1) {
            mContainers.remove(container);
            mContainers.add(container);
        }
    }

    /**
     * Gets the descriptors of split states in this Task.
     *
     * @return a list of {@code SplitInfo} if all the SplitContainers are stable, or {@code null} if
     * any SplitContainer is in an intermediate state.
     */
    @Nullable
    List<SplitInfo> getSplitStatesIfStable() {
        final List<SplitInfo> splitStates = new ArrayList<>();
        for (SplitContainer container : mSplitContainers) {
            final SplitInfo splitInfo = container.toSplitInfoIfStable();
            if (splitInfo == null) {
                return null;
            }
            splitStates.add(splitInfo);
        }
        return splitStates;
    }

    // TODO(b/317358445): Makes ActivityStack and SplitInfo callback more stable.
    /**
     * Returns a list of currently active {@link ActivityStack activityStacks}.
     *
     * @return a list of {@link ActivityStack activityStacks} if all the containers are in
     * a stable state, or {@code null} otherwise.
     */
    @Nullable
    List<ActivityStack> getActivityStacksIfStable() {
        final List<ActivityStack> activityStacks = new ArrayList<>();
        for (TaskFragmentContainer container : mContainers) {
            final ActivityStack activityStack = container.toActivityStackIfStable();
            if (activityStack == null) {
                return null;
            }
            activityStacks.add(activityStack);
        }
        return activityStacks;
    }

    /** A wrapper class which contains the information of {@link TaskContainer} */
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

        /** A helper method to return task {@link WindowMetrics} from this {@link TaskProperties} */
        @NonNull
        WindowMetrics getTaskMetrics() {
            final Rect taskBounds = mConfiguration.windowConfiguration.getBounds();
            // TODO(b/190433398): Supply correct insets.
            final float density = mConfiguration.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
            return new WindowMetrics(taskBounds, WindowInsets.CONSUMED, density);
        }

        /** Translates the given absolute bounds to relative bounds in this Task coordinate. */
        void translateAbsoluteBoundsToRelativeBounds(@NonNull Rect inOutBounds) {
            if (inOutBounds.isEmpty()) {
                return;
            }
            final Rect taskBounds = mConfiguration.windowConfiguration.getBounds();
            inOutBounds.offset(-taskBounds.left, -taskBounds.top);
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
