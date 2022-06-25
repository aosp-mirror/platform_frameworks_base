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

import static android.content.pm.PackageManager.MATCH_ALL;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.LayoutDirection;
import android.util.Pair;
import android.util.Size;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.window.WindowContainerTransaction;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;

/**
 * Controls the visual presentation of the splits according to the containers formed by
 * {@link SplitController}.
 */
class SplitPresenter extends JetpackTaskFragmentOrganizer {
    @VisibleForTesting
    static final int POSITION_START = 0;
    @VisibleForTesting
    static final int POSITION_END = 1;
    @VisibleForTesting
    static final int POSITION_FILL = 2;

    @IntDef(value = {
            POSITION_START,
            POSITION_END,
            POSITION_FILL,
    })
    private @interface Position {}

    /**
     * Result of {@link #expandSplitContainerIfNeeded(WindowContainerTransaction, SplitContainer,
     * Activity, Activity, Intent)}.
     * No need to expand the splitContainer because screen is big enough to
     * {@link #shouldShowSideBySide(Rect, SplitRule, Pair)} and minimum dimensions is satisfied.
     */
    static final int RESULT_NOT_EXPANDED = 0;
    /**
     * Result of {@link #expandSplitContainerIfNeeded(WindowContainerTransaction, SplitContainer,
     * Activity, Activity, Intent)}.
     * The splitContainer should be expanded. It is usually because minimum dimensions is not
     * satisfied.
     * @see #shouldShowSideBySide(Rect, SplitRule, Pair)
     */
    static final int RESULT_EXPANDED = 1;
    /**
     * Result of {@link #expandSplitContainerIfNeeded(WindowContainerTransaction, SplitContainer,
     * Activity, Activity, Intent)}.
     * The splitContainer should be expanded, but the client side hasn't received
     * {@link android.window.TaskFragmentInfo} yet. Fallback to create new expanded SplitContainer
     * instead.
     */
    static final int RESULT_EXPAND_FAILED_NO_TF_INFO = 2;

    /**
     * Result of {@link #expandSplitContainerIfNeeded(WindowContainerTransaction, SplitContainer,
     * Activity, Activity, Intent)}
     */
    @IntDef(value = {
            RESULT_NOT_EXPANDED,
            RESULT_EXPANDED,
            RESULT_EXPAND_FAILED_NO_TF_INFO,
    })
    private @interface ResultCode {}

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
    @NonNull
    TaskFragmentContainer createNewSplitWithEmptySideContainer(
            @NonNull WindowContainerTransaction wct, @NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent, @NonNull SplitPairRule rule) {
        final Rect parentBounds = getParentContainerBounds(primaryActivity);
        final Pair<Size, Size> minDimensionsPair = getActivityIntentMinDimensionsPair(
                primaryActivity, secondaryIntent);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                primaryActivity, minDimensionsPair);
        final TaskFragmentContainer primaryContainer = prepareContainerForActivity(wct,
                primaryActivity, primaryRectBounds, null);

        // Create new empty task fragment
        final int taskId = primaryContainer.getTaskId();
        final TaskFragmentContainer secondaryContainer = mController.newContainer(
                secondaryIntent, primaryActivity, taskId);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds,
                rule, primaryActivity, minDimensionsPair);
        final int windowingMode = mController.getTaskContainer(taskId)
                .getWindowingModeForSplitTaskFragment(secondaryRectBounds);
        createTaskFragment(wct, secondaryContainer.getTaskFragmentToken(),
                primaryActivity.getActivityToken(), secondaryRectBounds,
                windowingMode);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule,
                minDimensionsPair);

        mController.registerSplit(wct, primaryContainer, primaryActivity, secondaryContainer, rule);

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
        final Pair<Size, Size> minDimensionsPair = getActivitiesMinDimensionsPair(primaryActivity,
                secondaryActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                primaryActivity, minDimensionsPair);
        final TaskFragmentContainer primaryContainer = prepareContainerForActivity(wct,
                primaryActivity, primaryRectBounds, null);

        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds, rule,
                primaryActivity, minDimensionsPair);
        final TaskFragmentContainer curSecondaryContainer = mController.getContainerWithActivity(
                secondaryActivity);
        TaskFragmentContainer containerToAvoid = primaryContainer;
        if (rule.shouldClearTop() && curSecondaryContainer != null) {
            // Do not reuse the current TaskFragment if the rule is to clear top.
            containerToAvoid = curSecondaryContainer;
        }
        final TaskFragmentContainer secondaryContainer = prepareContainerForActivity(wct,
                secondaryActivity, secondaryRectBounds, containerToAvoid);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule,
                minDimensionsPair);

        mController.registerSplit(wct, primaryContainer, primaryActivity, secondaryContainer, rule);

        applyTransaction(wct);
    }

    /**
     * Creates a new container or resizes an existing container for activity to the provided bounds.
     * @param activity The activity to be re-parented to the container if necessary.
     * @param containerToAvoid Re-parent from this container if an activity is already in it.
     */
    private TaskFragmentContainer prepareContainerForActivity(
            @NonNull WindowContainerTransaction wct, @NonNull Activity activity,
            @NonNull Rect bounds, @Nullable TaskFragmentContainer containerToAvoid) {
        TaskFragmentContainer container = mController.getContainerWithActivity(activity);
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
        final Pair<Size, Size> minDimensionsPair = getActivityIntentMinDimensionsPair(
                launchingActivity, activityIntent);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                launchingActivity, minDimensionsPair);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds, rule,
                launchingActivity, minDimensionsPair);

        TaskFragmentContainer primaryContainer = mController.getContainerWithActivity(
                launchingActivity);
        if (primaryContainer == null) {
            primaryContainer = mController.newContainer(launchingActivity,
                    launchingActivity.getTaskId());
        }

        final int taskId = primaryContainer.getTaskId();
        final TaskFragmentContainer secondaryContainer = mController.newContainer(activityIntent,
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
        final Pair<Size, Size> minDimensionsPair = splitContainer.getMinDimensionsPair();
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, parentBounds, rule,
                activity, minDimensionsPair);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, parentBounds, rule,
                activity, minDimensionsPair);
        final TaskFragmentContainer secondaryContainer = splitContainer.getSecondaryContainer();
        // Whether the placeholder is becoming side-by-side with the primary from fullscreen.
        final boolean isPlaceholderBecomingSplit = splitContainer.isPlaceholderContainer()
                && secondaryContainer.areLastRequestedBoundsEqual(null /* bounds */)
                && !secondaryRectBounds.isEmpty();

        // If the task fragments are not registered yet, the positions will be updated after they
        // are created again.
        resizeTaskFragmentIfRegistered(wct, primaryContainer, primaryRectBounds);
        resizeTaskFragmentIfRegistered(wct, secondaryContainer, secondaryRectBounds);
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule,
                minDimensionsPair);
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
            @NonNull TaskFragmentContainer secondaryContainer, @NonNull SplitRule splitRule,
            @NonNull Pair<Size, Size> minDimensionsPair) {
        final Rect parentBounds = getParentContainerBounds(primaryContainer);
        // Clear adjacent TaskFragments if the container is shown in fullscreen, or the
        // secondaryContainer could not be finished.
        if (!shouldShowSideBySide(parentBounds, splitRule, minDimensionsPair)) {
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

    /**
     * Expands the split container if the current split bounds are smaller than the Activity or
     * Intent that is added to the container.
     *
     * @return the {@link ResultCode} based on {@link #shouldShowSideBySide(Rect, SplitRule, Pair)}
     * and if {@link android.window.TaskFragmentInfo} has reported to the client side.
     */
    @ResultCode
    int expandSplitContainerIfNeeded(@NonNull WindowContainerTransaction wct,
            @NonNull SplitContainer splitContainer, @NonNull Activity primaryActivity,
            @Nullable Activity secondaryActivity, @Nullable Intent secondaryIntent) {
        if (secondaryActivity == null && secondaryIntent == null) {
            throw new IllegalArgumentException("Either secondaryActivity or secondaryIntent must be"
                    + " non-null.");
        }
        final Rect taskBounds = getParentContainerBounds(primaryActivity);
        final Pair<Size, Size> minDimensionsPair;
        if (secondaryActivity != null) {
            minDimensionsPair = getActivitiesMinDimensionsPair(primaryActivity, secondaryActivity);
        } else {
            minDimensionsPair = getActivityIntentMinDimensionsPair(primaryActivity,
                    secondaryIntent);
        }
        // Expand the splitContainer if minimum dimensions are not satisfied.
        if (!shouldShowSideBySide(taskBounds, splitContainer.getSplitRule(), minDimensionsPair)) {
            // If the client side hasn't received TaskFragmentInfo yet, we can't change TaskFragment
            // bounds. Return failure to create a new SplitContainer which fills task bounds.
            if (splitContainer.getPrimaryContainer().getInfo() == null
                    || splitContainer.getSecondaryContainer().getInfo() == null) {
                return RESULT_EXPAND_FAILED_NO_TF_INFO;
            }
            expandTaskFragment(wct, splitContainer.getPrimaryContainer().getTaskFragmentToken());
            expandTaskFragment(wct, splitContainer.getSecondaryContainer().getTaskFragmentToken());
            return RESULT_EXPANDED;
        }
        return RESULT_NOT_EXPANDED;
    }

    static boolean shouldShowSideBySide(@NonNull Rect parentBounds, @NonNull SplitRule rule) {
        return shouldShowSideBySide(parentBounds, rule, null /* minimumDimensionPair */);
    }

    static boolean shouldShowSideBySide(@NonNull SplitContainer splitContainer) {
        final Rect parentBounds = getParentContainerBounds(splitContainer.getPrimaryContainer());

        return shouldShowSideBySide(parentBounds, splitContainer.getSplitRule(),
                splitContainer.getMinDimensionsPair());
    }

    static boolean shouldShowSideBySide(@NonNull Rect parentBounds, @NonNull SplitRule rule,
            @Nullable Pair<Size, Size> minDimensionsPair) {
        // TODO(b/190433398): Supply correct insets.
        final WindowMetrics parentMetrics = new WindowMetrics(parentBounds,
                new WindowInsets(new Rect()));
        // Don't show side by side if bounds is not qualified.
        if (!rule.checkParentMetrics(parentMetrics)) {
            return false;
        }
        final float splitRatio = rule.getSplitRatio();
        // We only care the size of the bounds regardless of its position.
        final Rect primaryBounds = getPrimaryBounds(parentBounds, splitRatio, true /* isLtr */);
        final Rect secondaryBounds = getSecondaryBounds(parentBounds, splitRatio, true /* isLtr */);

        if (minDimensionsPair == null) {
            return true;
        }
        return !boundsSmallerThanMinDimensions(primaryBounds, minDimensionsPair.first)
                && !boundsSmallerThanMinDimensions(secondaryBounds, minDimensionsPair.second);
    }

    @NonNull
    static Pair<Size, Size> getActivitiesMinDimensionsPair(Activity primaryActivity,
            Activity secondaryActivity) {
        return new Pair<>(getMinDimensions(primaryActivity), getMinDimensions(secondaryActivity));
    }

    @NonNull
    static Pair<Size, Size> getActivityIntentMinDimensionsPair(Activity primaryActivity,
            Intent secondaryIntent) {
        return new Pair<>(getMinDimensions(primaryActivity), getMinDimensions(secondaryIntent));
    }

    @Nullable
    static Size getMinDimensions(@Nullable Activity activity) {
        if (activity == null) {
            return null;
        }
        final ActivityInfo.WindowLayout windowLayout = activity.getActivityInfo().windowLayout;
        if (windowLayout == null) {
            return null;
        }
        return new Size(windowLayout.minWidth, windowLayout.minHeight);
    }

    // TODO(b/232871351): find a light-weight approach for this check.
    @Nullable
    static Size getMinDimensions(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        final PackageManager packageManager = ActivityThread.currentActivityThread()
                .getApplication().getPackageManager();
        final ResolveInfo resolveInfo = packageManager.resolveActivity(intent,
                PackageManager.ResolveInfoFlags.of(MATCH_ALL));
        if (resolveInfo == null) {
            return null;
        }
        final ActivityInfo activityInfo = resolveInfo.activityInfo;
        if (activityInfo == null) {
            return null;
        }
        final ActivityInfo.WindowLayout windowLayout = activityInfo.windowLayout;
        if (windowLayout == null) {
            return null;
        }
        return new Size(windowLayout.minWidth, windowLayout.minHeight);
    }

    static boolean boundsSmallerThanMinDimensions(@NonNull Rect bounds,
            @Nullable Size minDimensions) {
        if (minDimensions == null) {
            return false;
        }
        return bounds.width() < minDimensions.getWidth()
                || bounds.height() < minDimensions.getHeight();
    }

    @VisibleForTesting
    @NonNull
    static Rect getBoundsForPosition(@Position int position, @NonNull Rect parentBounds,
            @NonNull SplitRule rule, @NonNull Activity primaryActivity,
            @Nullable Pair<Size, Size> minDimensionsPair) {
        if (!shouldShowSideBySide(parentBounds, rule, minDimensionsPair)) {
            return new Rect();
        }
        final boolean isLtr = isLtr(primaryActivity, rule);
        final float splitRatio = rule.getSplitRatio();

        switch (position) {
            case POSITION_START:
                return getPrimaryBounds(parentBounds, splitRatio, isLtr);
            case POSITION_END:
                return getSecondaryBounds(parentBounds, splitRatio, isLtr);
            case POSITION_FILL:
            default:
                return new Rect();
        }
    }

    @NonNull
    private static Rect getPrimaryBounds(@NonNull Rect parentBounds, float splitRatio,
            boolean isLtr) {
        return isLtr ? getLeftContainerBounds(parentBounds, splitRatio)
                : getRightContainerBounds(parentBounds, 1 - splitRatio);
    }

    @NonNull
    private static Rect getSecondaryBounds(@NonNull Rect parentBounds, float splitRatio,
            boolean isLtr) {
        return isLtr ? getRightContainerBounds(parentBounds, splitRatio)
                : getLeftContainerBounds(parentBounds, 1 - splitRatio);
    }

    private static Rect getLeftContainerBounds(@NonNull Rect parentBounds, float splitRatio) {
        return new Rect(
                parentBounds.left,
                parentBounds.top,
                (int) (parentBounds.left + parentBounds.width() * splitRatio),
                parentBounds.bottom);
    }

    private static Rect getRightContainerBounds(@NonNull Rect parentBounds, float splitRatio) {
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
    private static boolean isLtr(@NonNull Context context, @NonNull SplitRule rule) {
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
    static Rect getParentContainerBounds(@NonNull TaskFragmentContainer container) {
        return container.getTaskContainer().getTaskBounds();
    }

    @NonNull
    Rect getParentContainerBounds(@NonNull Activity activity) {
        final TaskFragmentContainer container = mController.getContainerWithActivity(activity);
        if (container != null) {
            return getParentContainerBounds(container);
        }
        // Obtain bounds from Activity instead because the Activity hasn't been embedded yet.
        return getNonEmbeddedActivityBounds(activity);
    }

    /**
     * Obtains the bounds from a non-embedded Activity.
     * <p>
     * Note that callers should use {@link #getParentContainerBounds(Activity)} instead for most
     * cases unless we want to obtain task bounds before
     * {@link TaskContainer#isTaskBoundsInitialized()}.
     */
    @NonNull
    static Rect getNonEmbeddedActivityBounds(@NonNull Activity activity) {
        final WindowConfiguration windowConfiguration =
                activity.getResources().getConfiguration().windowConfiguration;
        if (!activity.isInMultiWindowMode()) {
            // In fullscreen mode the max bounds should correspond to the task bounds.
            return windowConfiguration.getMaxBounds();
        }
        return windowConfiguration.getBounds();
    }
}
