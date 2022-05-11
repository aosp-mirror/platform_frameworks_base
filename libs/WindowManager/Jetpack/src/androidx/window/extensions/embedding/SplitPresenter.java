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
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.LayoutDirection;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.window.WindowContainerTransaction;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * Controls the visual presentation of the splits according to the containers formed by
 * {@link SplitController}.
 */
class SplitPresenter extends JetpackTaskFragmentOrganizer {
    private static final int POSITION_START = 0;
    private static final int POSITION_END = 1;
    private static final int POSITION_FILL = 2;

    @IntDef(value = {
            POSITION_START,
            POSITION_END,
            POSITION_FILL,
    })
    private @interface Position {}

    private final SplitController mController;

    SplitPresenter(@NonNull Executor executor, SplitController controller) {
        super(executor, controller);
        mController = controller;
        registerOrganizer();
    }

    /**
     * Updates the presentation of the provided container.
     */
    void updateContainer(@NonNull TaskFragmentContainer container) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mController.updateContainer(wct, container);
        applyTransaction(wct);
    }

    /**
     * Deletes the specified container and all other associated and dependent containers in the same
     * transaction.
     */
    void cleanupContainer(@NonNull TaskFragmentContainer container, boolean shouldFinishDependent) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        cleanupContainer(container, shouldFinishDependent, wct);
        applyTransaction(wct);
    }

    /**
     * Deletes the specified container and all other associated and dependent containers in the same
     * transaction.
     */
    void cleanupContainer(@NonNull TaskFragmentContainer container, boolean shouldFinishDependent,
            @NonNull WindowContainerTransaction wct) {
        container.finish(shouldFinishDependent, this, wct, mController);

        final TaskFragmentContainer newTopContainer = mController.getTopActiveContainer(
                container.getTaskId());
        if (newTopContainer != null) {
            mController.updateContainer(wct, newTopContainer);
        }
    }

    /**
     * Creates a new split with the primary activity and an empty secondary container.
     * @return The newly created secondary container.
     */
    TaskFragmentContainer createNewSplitWithEmptySideContainer(@NonNull Activity primaryActivity,
            @NonNull SplitPairRule rule) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        final Rect parentBounds = getParentContainerBounds(primaryActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                isLtr(primaryActivity, rule));
        final TaskFragmentContainer primaryContainer = prepareContainerForActivity(wct,
                primaryActivity, primaryRectBounds, null);

        // Create new empty task fragment
        final int taskId = primaryContainer.getTaskId();
        final TaskFragmentContainer secondaryContainer = mController.newContainer(
                null /* activity */, primaryActivity, taskId);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds,
                rule, isLtr(primaryActivity, rule));
        final int windowingMode = mController.getTaskContainer(taskId)
                .getWindowingModeForSplitTaskFragment(secondaryRectBounds);
        createTaskFragment(wct, secondaryContainer.getTaskFragmentToken(),
                primaryActivity.getActivityToken(), secondaryRectBounds,
                windowingMode);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule);

        mController.registerSplit(wct, primaryContainer, primaryActivity, secondaryContainer, rule);

        applyTransaction(wct);

        return secondaryContainer;
    }

    /**
     * Creates a new split container with the two provided activities.
     * @param primaryActivity An activity that should be in the primary container. If it is not
     *                        currently in an existing container, a new one will be created and the
     *                        activity will be re-parented to it.
     * @param secondaryActivity An activity that should be in the secondary container. If it is not
     *                          currently in an existing container, or if it is currently in the
     *                          same container as the primary activity, a new container will be
     *                          created and the activity will be re-parented to it.
     * @param rule The split rule to be applied to the container.
     */
    void createNewSplitContainer(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, @NonNull SplitPairRule rule) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        final Rect parentBounds = getParentContainerBounds(primaryActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                isLtr(primaryActivity, rule));
        final TaskFragmentContainer primaryContainer = prepareContainerForActivity(wct,
                primaryActivity, primaryRectBounds, null);

        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds, rule,
                isLtr(primaryActivity, rule));
        final TaskFragmentContainer secondaryContainer = prepareContainerForActivity(wct,
                secondaryActivity, secondaryRectBounds, primaryContainer);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule);

        mController.registerSplit(wct, primaryContainer, primaryActivity, secondaryContainer, rule);

        applyTransaction(wct);
    }

    /**
     * Creates a new expanded container.
     */
    TaskFragmentContainer createNewExpandedContainer(@NonNull Activity launchingActivity) {
        final TaskFragmentContainer newContainer = mController.newContainer(null /* activity */,
                launchingActivity, launchingActivity.getTaskId());

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        createTaskFragment(wct, newContainer.getTaskFragmentToken(),
                launchingActivity.getActivityToken(), new Rect(), WINDOWING_MODE_UNDEFINED);

        applyTransaction(wct);
        return newContainer;
    }

    /**
     * Creates a new container or resizes an existing container for activity to the provided bounds.
     * @param activity The activity to be re-parented to the container if necessary.
     * @param containerToAvoid Re-parent from this container if an activity is already in it.
     */
    private TaskFragmentContainer prepareContainerForActivity(
            @NonNull WindowContainerTransaction wct, @NonNull Activity activity,
            @NonNull Rect bounds, @Nullable TaskFragmentContainer containerToAvoid) {
        TaskFragmentContainer container = mController.getContainerWithActivity(
                activity.getActivityToken());
        final int taskId = container != null ? container.getTaskId() : activity.getTaskId();
        if (container == null || container == containerToAvoid) {
            container = mController.newContainer(activity, taskId);
            final int windowingMode = mController.getTaskContainer(taskId)
                    .getWindowingModeForSplitTaskFragment(bounds);
            createTaskFragment(wct, container.getTaskFragmentToken(), activity.getActivityToken(),
                    bounds, windowingMode);
            wct.reparentActivityToTaskFragment(container.getTaskFragmentToken(),
                    activity.getActivityToken());
        } else {
            resizeTaskFragmentIfRegistered(wct, container, bounds);
            final int windowingMode = mController.getTaskContainer(taskId)
                    .getWindowingModeForSplitTaskFragment(bounds);
            updateTaskFragmentWindowingModeIfRegistered(wct, container, windowingMode);
        }

        return container;
    }

    /**
     * Starts a new activity to the side, creating a new split container. A new container will be
     * created for the activity that will be started.
     * @param launchingActivity An activity that should be in the primary container. If it is not
     *                          currently in an existing container, a new one will be created and
     *                          the activity will be re-parented to it.
     * @param activityIntent    The intent to start the new activity.
     * @param activityOptions   The options to apply to new activity start.
     * @param rule              The split rule to be applied to the container.
     * @param isPlaceholder     Whether the launch is a placeholder.
     */
    void startActivityToSide(@NonNull Activity launchingActivity, @NonNull Intent activityIntent,
            @Nullable Bundle activityOptions, @NonNull SplitRule rule, boolean isPlaceholder) {
        final Rect parentBounds = getParentContainerBounds(launchingActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                isLtr(launchingActivity, rule));
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds, rule,
                isLtr(launchingActivity, rule));

        TaskFragmentContainer primaryContainer = mController.getContainerWithActivity(
                launchingActivity.getActivityToken());
        if (primaryContainer == null) {
            primaryContainer = mController.newContainer(launchingActivity,
                    launchingActivity.getTaskId());
        }

        final int taskId = primaryContainer.getTaskId();
        TaskFragmentContainer secondaryContainer = mController.newContainer(null /* activity */,
                launchingActivity, taskId);
        final int windowingMode = mController.getTaskContainer(taskId)
                .getWindowingModeForSplitTaskFragment(primaryRectBounds);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mController.registerSplit(wct, primaryContainer, launchingActivity, secondaryContainer,
                rule);
        startActivityToSide(wct, primaryContainer.getTaskFragmentToken(), primaryRectBounds,
                launchingActivity, secondaryContainer.getTaskFragmentToken(), secondaryRectBounds,
                activityIntent, activityOptions, rule, windowingMode);
        if (isPlaceholder) {
            // When placeholder is launched in split, we should keep the focus on the primary.
            wct.requestFocusOnTaskFragment(primaryContainer.getTaskFragmentToken());
        }
        applyTransaction(wct);
    }

    /**
     * Updates the positions of containers in an existing split.
     * @param splitContainer The split container to be updated.
     * @param updatedContainer The task fragment that was updated and caused this split update.
     * @param wct WindowContainerTransaction that this update should be performed with.
     */
    void updateSplitContainer(@NonNull SplitContainer splitContainer,
            @NonNull TaskFragmentContainer updatedContainer,
            @NonNull WindowContainerTransaction wct) {
        // Getting the parent bounds using the updated container - it will have the recent value.
        final Rect parentBounds = getParentContainerBounds(updatedContainer);
        final SplitRule rule = splitContainer.getSplitRule();
        final TaskFragmentContainer primaryContainer = splitContainer.getPrimaryContainer();
        final Activity activity = primaryContainer.getTopNonFinishingActivity();
        if (activity == null) {
            return;
        }
        final boolean isLtr = isLtr(activity, rule);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                isLtr);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds, rule,
                isLtr);
        final TaskFragmentContainer secondaryContainer = splitContainer.getSecondaryContainer();
        // Whether the placeholder is becoming side-by-side with the primary from fullscreen.
        final boolean isPlaceholderBecomingSplit = splitContainer.isPlaceholderContainer()
                && secondaryContainer.areLastRequestedBoundsEqual(null /* bounds */)
                && !secondaryRectBounds.isEmpty();

        // If the task fragments are not registered yet, the positions will be updated after they
        // are created again.
        resizeTaskFragmentIfRegistered(wct, primaryContainer, primaryRectBounds);
        resizeTaskFragmentIfRegistered(wct, secondaryContainer, secondaryRectBounds);
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule);
        if (isPlaceholderBecomingSplit) {
            // When placeholder is shown in split, we should keep the focus on the primary.
            wct.requestFocusOnTaskFragment(primaryContainer.getTaskFragmentToken());
        }
        final TaskContainer taskContainer = updatedContainer.getTaskContainer();
        final int windowingMode = taskContainer.getWindowingModeForSplitTaskFragment(
                primaryRectBounds);
        updateTaskFragmentWindowingModeIfRegistered(wct, primaryContainer, windowingMode);
        updateTaskFragmentWindowingModeIfRegistered(wct, secondaryContainer, windowingMode);
    }

    private void setAdjacentTaskFragments(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer, @NonNull SplitRule splitRule) {
        final Rect parentBounds = getParentContainerBounds(primaryContainer);
        // Clear adjacent TaskFragments if the container is shown in fullscreen, or the
        // secondaryContainer could not be finished.
        if (!shouldShowSideBySide(parentBounds, splitRule)) {
            setAdjacentTaskFragments(wct, primaryContainer.getTaskFragmentToken(),
                    null /* secondary */, null /* splitRule */);
        } else {
            setAdjacentTaskFragments(wct, primaryContainer.getTaskFragmentToken(),
                    secondaryContainer.getTaskFragmentToken(), splitRule);
        }
    }

    /**
     * Resizes the task fragment if it was already registered. Skips the operation if the container
     * creation has not been reported from the server yet.
     */
    // TODO(b/190433398): Handle resize if the fragment hasn't appeared yet.
    void resizeTaskFragmentIfRegistered(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container,
            @Nullable Rect bounds) {
        if (container.getInfo() == null) {
            return;
        }
        resizeTaskFragment(wct, container.getTaskFragmentToken(), bounds);
    }

    private void updateTaskFragmentWindowingModeIfRegistered(
            @NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container,
            @WindowingMode int windowingMode) {
        if (container.getInfo() != null) {
            updateWindowingMode(wct, container.getTaskFragmentToken(), windowingMode);
        }
    }

    @Override
    void createTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
            @NonNull IBinder ownerToken, @NonNull Rect bounds, @WindowingMode int windowingMode) {
        final TaskFragmentContainer container = mController.getContainer(fragmentToken);
        if (container == null) {
            throw new IllegalStateException(
                    "Creating a task fragment that is not registered with controller.");
        }

        container.setLastRequestedBounds(bounds);
        container.setLastRequestedWindowingMode(windowingMode);
        super.createTaskFragment(wct, fragmentToken, ownerToken, bounds, windowingMode);
    }

    @Override
    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
            @Nullable Rect bounds) {
        TaskFragmentContainer container = mController.getContainer(fragmentToken);
        if (container == null) {
            throw new IllegalStateException(
                    "Resizing a task fragment that is not registered with controller.");
        }

        if (container.areLastRequestedBoundsEqual(bounds)) {
            // Return early if the provided bounds were already requested
            return;
        }

        container.setLastRequestedBounds(bounds);
        super.resizeTaskFragment(wct, fragmentToken, bounds);
    }

    @Override
    void updateWindowingMode(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken, @WindowingMode int windowingMode) {
        final TaskFragmentContainer container = mController.getContainer(fragmentToken);
        if (container == null) {
            throw new IllegalStateException("Setting windowing mode for a task fragment that is"
                    + " not registered with controller.");
        }

        if (container.isLastRequestedWindowingModeEqual(windowingMode)) {
            // Return early if the windowing mode were already requested
            return;
        }

        container.setLastRequestedWindowingMode(windowingMode);
        super.updateWindowingMode(wct, fragmentToken, windowingMode);
    }

    boolean shouldShowSideBySide(@NonNull SplitContainer splitContainer) {
        final Rect parentBounds = getParentContainerBounds(splitContainer.getPrimaryContainer());
        return shouldShowSideBySide(parentBounds, splitContainer.getSplitRule());
    }

    boolean shouldShowSideBySide(@Nullable Rect parentBounds, @NonNull SplitRule rule) {
        // TODO(b/190433398): Supply correct insets.
        final WindowMetrics parentMetrics = new WindowMetrics(parentBounds,
                new WindowInsets(new Rect()));
        return rule.checkParentMetrics(parentMetrics);
    }

    @NonNull
    private Rect getBoundsForPosition(@Position int position, @NonNull Rect parentBounds,
            @NonNull SplitRule rule, boolean isLtr) {
        if (!shouldShowSideBySide(parentBounds, rule)) {
            return new Rect();
        }

        final float splitRatio = rule.getSplitRatio();
        final float rtlSplitRatio = 1 - splitRatio;
        switch (position) {
            case POSITION_START:
                return isLtr ? getLeftContainerBounds(parentBounds, splitRatio)
                        : getRightContainerBounds(parentBounds, rtlSplitRatio);
            case POSITION_END:
                return isLtr ? getRightContainerBounds(parentBounds, splitRatio)
                        : getLeftContainerBounds(parentBounds, rtlSplitRatio);
            case POSITION_FILL:
                return parentBounds;
        }
        return parentBounds;
    }

    private Rect getLeftContainerBounds(@NonNull Rect parentBounds, float splitRatio) {
        return new Rect(
                parentBounds.left,
                parentBounds.top,
                (int) (parentBounds.left + parentBounds.width() * splitRatio),
                parentBounds.bottom);
    }

    private Rect getRightContainerBounds(@NonNull Rect parentBounds, float splitRatio) {
        return new Rect(
                (int) (parentBounds.left + parentBounds.width() * splitRatio),
                parentBounds.top,
                parentBounds.right,
                parentBounds.bottom);
    }

    /**
     * Checks if a split with the provided rule should be displays in left-to-right layout
     * direction, either always or with the current configuration.
     */
    private boolean isLtr(@NonNull Context context, @NonNull SplitRule rule) {
        switch (rule.getLayoutDirection()) {
            case LayoutDirection.LOCALE:
                return context.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_LTR;
            case LayoutDirection.RTL:
                return false;
            case LayoutDirection.LTR:
            default:
                return true;
        }
    }

    @NonNull
    Rect getParentContainerBounds(@NonNull TaskFragmentContainer container) {
        return container.getTaskContainer().getTaskBounds();
    }

    @NonNull
    Rect getParentContainerBounds(@NonNull Activity activity) {
        final TaskFragmentContainer container = mController.getContainerWithActivity(
                activity.getActivityToken());
        if (container != null) {
            return getParentContainerBounds(container);
        }
        return getTaskBoundsFromActivity(activity);
    }

    @NonNull
    static Rect getTaskBoundsFromActivity(@NonNull Activity activity) {
        final WindowConfiguration windowConfiguration =
                activity.getResources().getConfiguration().windowConfiguration;
        if (!activity.isInMultiWindowMode()) {
            // In fullscreen mode the max bounds should correspond to the task bounds.
            return windowConfiguration.getMaxBounds();
        }
        return windowConfiguration.getBounds();
    }
}
