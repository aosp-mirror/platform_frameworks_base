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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.LayoutDirection;
import android.util.Pair;
import android.util.Size;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentCreationParams;
import android.window.WindowContainerTransaction;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.core.util.function.Function;
import androidx.window.extensions.embedding.SplitAttributes.SplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.ExpandContainersSplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.HingeSplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.RatioSplitType;
import androidx.window.extensions.embedding.TaskContainer.TaskProperties;
import androidx.window.extensions.layout.DisplayFeature;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;
import androidx.window.extensions.layout.WindowLayoutInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Controls the visual presentation of the splits according to the containers formed by
 * {@link SplitController}.
 *
 * Note that all calls into this class must hold the {@link SplitController} internal lock.
 */
@SuppressWarnings("GuardedBy")
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

    private static final int CONTAINER_POSITION_LEFT = 0;
    private static final int CONTAINER_POSITION_TOP = 1;
    private static final int CONTAINER_POSITION_RIGHT = 2;
    private static final int CONTAINER_POSITION_BOTTOM = 3;

    @IntDef(value = {
            CONTAINER_POSITION_LEFT,
            CONTAINER_POSITION_TOP,
            CONTAINER_POSITION_RIGHT,
            CONTAINER_POSITION_BOTTOM,
    })
    private @interface ContainerPosition {}

    /**
     * Result of {@link #expandSplitContainerIfNeeded(WindowContainerTransaction, SplitContainer,
     * Activity, Activity, Intent)}.
     * No need to expand the splitContainer because screen is big enough to
     * {@link #shouldShowSplit(SplitAttributes)} and minimum dimensions is
     * satisfied.
     */
    static final int RESULT_NOT_EXPANDED = 0;
    /**
     * Result of {@link #expandSplitContainerIfNeeded(WindowContainerTransaction, SplitContainer,
     * Activity, Activity, Intent)}.
     * The splitContainer should be expanded. It is usually because minimum dimensions is not
     * satisfied.
     * @see #shouldShowSplit(SplitAttributes)
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

    @VisibleForTesting
    static final SplitAttributes EXPAND_CONTAINERS_ATTRIBUTES =
            new SplitAttributes.Builder()
            .setSplitType(new ExpandContainersSplitType())
            .build();

    private final WindowLayoutComponentImpl mWindowLayoutComponent;
    private final SplitController mController;

    SplitPresenter(@NonNull Executor executor,
            @NonNull WindowLayoutComponentImpl windowLayoutComponent,
            @NonNull SplitController controller) {
        super(executor, controller);
        mWindowLayoutComponent = windowLayoutComponent;
        mController = controller;
        registerOrganizer();
        if (!SplitController.ENABLE_SHELL_TRANSITIONS) {
            // TODO(b/207070762): cleanup with legacy app transition
            // Animation will be handled by WM Shell when Shell transition is enabled.
            overrideSplitAnimation();
        }
    }

    /**
     * Deletes the specified container and all other associated and dependent containers in the same
     * transaction.
     */
    void cleanupContainer(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container, boolean shouldFinishDependent) {
        container.finish(shouldFinishDependent, this, wct, mController);
        // Make sure the containers in the Task is up-to-date.
        mController.updateContainersInTaskIfVisible(wct, container.getTaskId());
    }

    /**
     * Creates a new split with the primary activity and an empty secondary container.
     * @return The newly created secondary container.
     */
    @NonNull
    TaskFragmentContainer createNewSplitWithEmptySideContainer(
            @NonNull WindowContainerTransaction wct, @NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent, @NonNull SplitPairRule rule) {
        final TaskProperties taskProperties = getTaskProperties(primaryActivity);
        final Pair<Size, Size> minDimensionsPair = getActivityIntentMinDimensionsPair(
                primaryActivity, secondaryIntent);
        final SplitAttributes splitAttributes = computeSplitAttributes(taskProperties, rule,
                minDimensionsPair);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, taskProperties,
                splitAttributes);
        final TaskFragmentContainer primaryContainer = prepareContainerForActivity(wct,
                primaryActivity, primaryRectBounds, splitAttributes, null /* containerToAvoid */);

        // Create new empty task fragment
        final int taskId = primaryContainer.getTaskId();
        final TaskFragmentContainer secondaryContainer = mController.newContainer(
                secondaryIntent, primaryActivity, taskId);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, taskProperties,
                splitAttributes);
        final int windowingMode = mController.getTaskContainer(taskId)
                .getWindowingModeForSplitTaskFragment(secondaryRectBounds);
        createTaskFragment(wct, secondaryContainer.getTaskFragmentToken(),
                primaryActivity.getActivityToken(), secondaryRectBounds,
                windowingMode);
        updateAnimationParams(wct, secondaryContainer.getTaskFragmentToken(), splitAttributes);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule,
                splitAttributes);

        mController.registerSplit(wct, primaryContainer, primaryActivity, secondaryContainer, rule,
                splitAttributes);

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
    void createNewSplitContainer(@NonNull WindowContainerTransaction wct,
            @NonNull Activity primaryActivity, @NonNull Activity secondaryActivity,
            @NonNull SplitPairRule rule) {
        final TaskProperties taskProperties = getTaskProperties(primaryActivity);
        final Pair<Size, Size> minDimensionsPair = getActivitiesMinDimensionsPair(primaryActivity,
                secondaryActivity);
        final SplitAttributes splitAttributes = computeSplitAttributes(taskProperties, rule,
                minDimensionsPair);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, taskProperties,
                splitAttributes);
        final TaskFragmentContainer primaryContainer = prepareContainerForActivity(wct,
                primaryActivity, primaryRectBounds, splitAttributes, null /* containerToAvoid */);

        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, taskProperties,
                splitAttributes);
        final TaskFragmentContainer curSecondaryContainer = mController.getContainerWithActivity(
                secondaryActivity);
        TaskFragmentContainer containerToAvoid = primaryContainer;
        if (curSecondaryContainer != null && curSecondaryContainer != primaryContainer
                && (rule.shouldClearTop() || primaryContainer.isAbove(curSecondaryContainer))) {
            // Do not reuse the current TaskFragment if the rule is to clear top, or if it is below
            // the primary TaskFragment.
            containerToAvoid = curSecondaryContainer;
        }
        final TaskFragmentContainer secondaryContainer = prepareContainerForActivity(wct,
                secondaryActivity, secondaryRectBounds, splitAttributes, containerToAvoid);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, primaryContainer, secondaryContainer, rule,
                splitAttributes);

        mController.registerSplit(wct, primaryContainer, primaryActivity, secondaryContainer, rule,
                splitAttributes);
    }

    /**
     * Creates a new container or resizes an existing container for activity to the provided bounds.
     * @param activity The activity to be re-parented to the container if necessary.
     * @param containerToAvoid Re-parent from this container if an activity is already in it.
     */
    private TaskFragmentContainer prepareContainerForActivity(
            @NonNull WindowContainerTransaction wct, @NonNull Activity activity,
            @NonNull Rect bounds, @NonNull SplitAttributes splitAttributes,
            @Nullable TaskFragmentContainer containerToAvoid) {
        TaskFragmentContainer container = mController.getContainerWithActivity(activity);
        final int taskId = container != null ? container.getTaskId() : activity.getTaskId();
        if (container == null || container == containerToAvoid) {
            container = mController.newContainer(activity, taskId);
            final int windowingMode = mController.getTaskContainer(taskId)
                    .getWindowingModeForSplitTaskFragment(bounds);
            final IBinder reparentActivityToken = activity.getActivityToken();
            createTaskFragment(wct, container.getTaskFragmentToken(), reparentActivityToken,
                    bounds, windowingMode, reparentActivityToken);
            wct.reparentActivityToTaskFragment(container.getTaskFragmentToken(),
                    reparentActivityToken);
        } else {
            resizeTaskFragmentIfRegistered(wct, container, bounds);
            final int windowingMode = mController.getTaskContainer(taskId)
                    .getWindowingModeForSplitTaskFragment(bounds);
            updateTaskFragmentWindowingModeIfRegistered(wct, container, windowingMode);
        }
        updateAnimationParams(wct, container.getTaskFragmentToken(), splitAttributes);

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
    void startActivityToSide(@NonNull WindowContainerTransaction wct,
            @NonNull Activity launchingActivity, @NonNull Intent activityIntent,
            @Nullable Bundle activityOptions, @NonNull SplitRule rule,
            @NonNull SplitAttributes splitAttributes, boolean isPlaceholder) {
        final TaskProperties taskProperties = getTaskProperties(launchingActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, taskProperties,
                splitAttributes);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, taskProperties,
                splitAttributes);

        TaskFragmentContainer primaryContainer = mController.getContainerWithActivity(
                launchingActivity);
        if (primaryContainer == null) {
            primaryContainer = mController.newContainer(launchingActivity,
                    launchingActivity.getTaskId());
        }

        final int taskId = primaryContainer.getTaskId();
        final TaskFragmentContainer secondaryContainer = mController.newContainer(
                null /* pendingAppearedActivity */, activityIntent, launchingActivity, taskId,
                // Pass in the primary container to make sure it is added right above the primary.
                primaryContainer);
        final TaskContainer taskContainer = mController.getTaskContainer(taskId);
        final int windowingMode = taskContainer.getWindowingModeForSplitTaskFragment(
                primaryRectBounds);
        mController.registerSplit(wct, primaryContainer, launchingActivity, secondaryContainer,
                rule, splitAttributes);
        startActivityToSide(wct, primaryContainer.getTaskFragmentToken(), primaryRectBounds,
                launchingActivity, secondaryContainer.getTaskFragmentToken(), secondaryRectBounds,
                activityIntent, activityOptions, rule, windowingMode, splitAttributes);
        if (isPlaceholder) {
            // When placeholder is launched in split, we should keep the focus on the primary.
            wct.requestFocusOnTaskFragment(primaryContainer.getTaskFragmentToken());
        }
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
        // Getting the parent configuration using the updated container - it will have the recent
        // value.
        final SplitRule rule = splitContainer.getSplitRule();
        final TaskFragmentContainer primaryContainer = splitContainer.getPrimaryContainer();
        final Activity activity = primaryContainer.getTopNonFinishingActivity();
        if (activity == null) {
            return;
        }
        final TaskProperties taskProperties = getTaskProperties(updatedContainer);
        final SplitAttributes splitAttributes = splitContainer.getSplitAttributes();
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_START, taskProperties,
                splitAttributes);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_END, taskProperties,
                splitAttributes);
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
                splitAttributes);
        if (isPlaceholderBecomingSplit) {
            // When placeholder is shown in split, we should keep the focus on the primary.
            wct.requestFocusOnTaskFragment(primaryContainer.getTaskFragmentToken());
        }
        final TaskContainer taskContainer = updatedContainer.getTaskContainer();
        final int windowingMode = taskContainer.getWindowingModeForSplitTaskFragment(
                primaryRectBounds);
        updateTaskFragmentWindowingModeIfRegistered(wct, primaryContainer, windowingMode);
        updateTaskFragmentWindowingModeIfRegistered(wct, secondaryContainer, windowingMode);
        updateAnimationParams(wct, primaryContainer.getTaskFragmentToken(), splitAttributes);
        updateAnimationParams(wct, secondaryContainer.getTaskFragmentToken(), splitAttributes);
    }

    private void setAdjacentTaskFragments(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer, @NonNull SplitRule splitRule,
            @NonNull SplitAttributes splitAttributes) {
        // Clear adjacent TaskFragments if the container is shown in fullscreen, or the
        // secondaryContainer could not be finished.
        boolean isStacked = !shouldShowSplit(splitAttributes);
        if (isStacked) {
            setAdjacentTaskFragments(wct, primaryContainer.getTaskFragmentToken(),
                    null /* secondary */, null /* splitRule */);
        } else {
            setAdjacentTaskFragments(wct, primaryContainer.getTaskFragmentToken(),
                    secondaryContainer.getTaskFragmentToken(), splitRule);
        }
        setCompanionTaskFragment(wct, primaryContainer.getTaskFragmentToken(),
                secondaryContainer.getTaskFragmentToken(), splitRule, isStacked);
    }

    /**
     * Resizes the task fragment if it was already registered. Skips the operation if the container
     * creation has not been reported from the server yet.
     */
    // TODO(b/190433398): Handle resize if the fragment hasn't appeared yet.
    private void resizeTaskFragmentIfRegistered(@NonNull WindowContainerTransaction wct,
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
    void createTaskFragment(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentCreationParams fragmentOptions) {
        final TaskFragmentContainer container = mController.getContainer(
                fragmentOptions.getFragmentToken());
        if (container == null) {
            throw new IllegalStateException(
                    "Creating a task fragment that is not registered with controller.");
        }

        container.setLastRequestedBounds(fragmentOptions.getInitialBounds());
        container.setLastRequestedWindowingMode(fragmentOptions.getWindowingMode());
        super.createTaskFragment(wct, fragmentOptions);
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

    @Override
    void updateAnimationParams(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken, @NonNull TaskFragmentAnimationParams animationParams) {
        final TaskFragmentContainer container = mController.getContainer(fragmentToken);
        if (container == null) {
            throw new IllegalStateException("Setting animation params for a task fragment that is"
                    + " not registered with controller.");
        }

        if (container.areLastRequestedAnimationParamsEqual(animationParams)) {
            // Return early if the animation params were already requested
            return;
        }

        container.setLastRequestAnimationParams(animationParams);
        super.updateAnimationParams(wct, fragmentToken, animationParams);
    }

    /**
     * Expands the split container if the current split bounds are smaller than the Activity or
     * Intent that is added to the container.
     *
     * @return the {@link ResultCode} based on
     * {@link #shouldShowSplit(SplitAttributes)} and if
     * {@link android.window.TaskFragmentInfo} has reported to the client side.
     */
    @ResultCode
    int expandSplitContainerIfNeeded(@NonNull WindowContainerTransaction wct,
            @NonNull SplitContainer splitContainer, @NonNull Activity primaryActivity,
            @Nullable Activity secondaryActivity, @Nullable Intent secondaryIntent) {
        if (secondaryActivity == null && secondaryIntent == null) {
            throw new IllegalArgumentException("Either secondaryActivity or secondaryIntent must be"
                    + " non-null.");
        }
        final Pair<Size, Size> minDimensionsPair;
        if (secondaryActivity != null) {
            minDimensionsPair = getActivitiesMinDimensionsPair(primaryActivity, secondaryActivity);
        } else {
            minDimensionsPair = getActivityIntentMinDimensionsPair(primaryActivity,
                    secondaryIntent);
        }
        // Expand the splitContainer if minimum dimensions are not satisfied.
        final TaskContainer taskContainer = splitContainer.getTaskContainer();
        final SplitAttributes splitAttributes = sanitizeSplitAttributes(
                taskContainer.getTaskProperties(), splitContainer.getSplitAttributes(),
                minDimensionsPair);
        splitContainer.setSplitAttributes(splitAttributes);
        if (!shouldShowSplit(splitAttributes)) {
            // If the client side hasn't received TaskFragmentInfo yet, we can't change TaskFragment
            // bounds. Return failure to create a new SplitContainer which fills task bounds.
            if (splitContainer.getPrimaryContainer().getInfo() == null
                    || splitContainer.getSecondaryContainer().getInfo() == null) {
                return RESULT_EXPAND_FAILED_NO_TF_INFO;
            }
            final IBinder primaryToken =
                    splitContainer.getPrimaryContainer().getTaskFragmentToken();
            final IBinder secondaryToken =
                    splitContainer.getSecondaryContainer().getTaskFragmentToken();
            expandTaskFragment(wct, primaryToken);
            expandTaskFragment(wct, secondaryToken);
            // Set the companion TaskFragment when the two containers stacked.
            setCompanionTaskFragment(wct, primaryToken, secondaryToken,
                    splitContainer.getSplitRule(), true /* isStacked */);
            return RESULT_EXPANDED;
        }
        return RESULT_NOT_EXPANDED;
    }

    static boolean shouldShowSplit(@NonNull SplitContainer splitContainer) {
        return shouldShowSplit(splitContainer.getSplitAttributes());
    }

    static boolean shouldShowSplit(@NonNull SplitAttributes splitAttributes) {
        return !(splitAttributes.getSplitType() instanceof ExpandContainersSplitType);
    }

    @NonNull
    SplitAttributes computeSplitAttributes(@NonNull TaskProperties taskProperties,
            @NonNull SplitRule rule, @Nullable Pair<Size, Size> minDimensionsPair) {
        final Configuration taskConfiguration = taskProperties.getConfiguration();
        final WindowMetrics taskWindowMetrics = getTaskWindowMetrics(taskConfiguration);
        final Function<SplitAttributesCalculatorParams, SplitAttributes> calculator =
                mController.getSplitAttributesCalculator();
        final SplitAttributes defaultSplitAttributes = rule.getDefaultSplitAttributes();
        final boolean areDefaultConstraintsSatisfied = rule.checkParentMetrics(taskWindowMetrics);
        if (calculator == null) {
            if (!areDefaultConstraintsSatisfied) {
                return EXPAND_CONTAINERS_ATTRIBUTES;
            }
            return sanitizeSplitAttributes(taskProperties, defaultSplitAttributes,
                    minDimensionsPair);
        }
        final WindowLayoutInfo windowLayoutInfo = mWindowLayoutComponent
                .getCurrentWindowLayoutInfo(taskProperties.getDisplayId(),
                        taskConfiguration.windowConfiguration);
        final SplitAttributesCalculatorParams params = new SplitAttributesCalculatorParams(
                taskWindowMetrics, taskConfiguration, windowLayoutInfo, defaultSplitAttributes,
                areDefaultConstraintsSatisfied, rule.getTag());
        final SplitAttributes splitAttributes = calculator.apply(params);
        return sanitizeSplitAttributes(taskProperties, splitAttributes, minDimensionsPair);
    }

    /**
     * Returns {@link #EXPAND_CONTAINERS_ATTRIBUTES} if the passed {@link SplitAttributes} doesn't
     * meet the minimum dimensions set in {@link ActivityInfo.WindowLayout}. Otherwise, returns
     * the passed {@link SplitAttributes}.
     */
    @NonNull
    private SplitAttributes sanitizeSplitAttributes(@NonNull TaskProperties taskProperties,
            @NonNull SplitAttributes splitAttributes,
            @Nullable Pair<Size, Size> minDimensionsPair) {
        if (minDimensionsPair == null) {
            return splitAttributes;
        }
        final FoldingFeature foldingFeature = getFoldingFeature(taskProperties);
        final Configuration taskConfiguration = taskProperties.getConfiguration();
        final Rect primaryBounds = getPrimaryBounds(taskConfiguration, splitAttributes,
                foldingFeature);
        final Rect secondaryBounds = getSecondaryBounds(taskConfiguration, splitAttributes,
                foldingFeature);
        if (boundsSmallerThanMinDimensions(primaryBounds, minDimensionsPair.first)
                || boundsSmallerThanMinDimensions(secondaryBounds, minDimensionsPair.second)) {
            return EXPAND_CONTAINERS_ATTRIBUTES;
        }
        return splitAttributes;
    }

    @NonNull
    private static Pair<Size, Size> getActivitiesMinDimensionsPair(
            @NonNull Activity primaryActivity, @NonNull Activity secondaryActivity) {
        return new Pair<>(getMinDimensions(primaryActivity), getMinDimensions(secondaryActivity));
    }

    @NonNull
    static Pair<Size, Size> getActivityIntentMinDimensionsPair(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent) {
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

    private static boolean boundsSmallerThanMinDimensions(@NonNull Rect bounds,
            @Nullable Size minDimensions) {
        if (minDimensions == null) {
            return false;
        }
        return bounds.width() < minDimensions.getWidth()
                || bounds.height() < minDimensions.getHeight();
    }

    @VisibleForTesting
    @NonNull
    Rect getBoundsForPosition(@Position int position, @NonNull TaskProperties taskProperties,
            @NonNull SplitAttributes splitAttributes) {
        final Configuration taskConfiguration = taskProperties.getConfiguration();
        final FoldingFeature foldingFeature = getFoldingFeature(taskProperties);
        final SplitType splitType = computeSplitType(splitAttributes, taskConfiguration,
                foldingFeature);
        final SplitAttributes computedSplitAttributes = new SplitAttributes.Builder()
                .setSplitType(splitType)
                .setLayoutDirection(splitAttributes.getLayoutDirection())
                .build();
        if (!shouldShowSplit(computedSplitAttributes)) {
            return new Rect();
        }
        switch (position) {
            case POSITION_START:
                return getPrimaryBounds(taskConfiguration, computedSplitAttributes, foldingFeature);
            case POSITION_END:
                return getSecondaryBounds(taskConfiguration, computedSplitAttributes,
                        foldingFeature);
            case POSITION_FILL:
            default:
                return new Rect();
        }
    }

    @NonNull
    private Rect getPrimaryBounds(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @Nullable FoldingFeature foldingFeature) {
        if (!shouldShowSplit(splitAttributes)) {
            return new Rect();
        }
        switch (splitAttributes.getLayoutDirection()) {
            case SplitAttributes.LayoutDirection.LEFT_TO_RIGHT: {
                return getLeftContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            case SplitAttributes.LayoutDirection.RIGHT_TO_LEFT: {
                return getRightContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            case SplitAttributes.LayoutDirection.LOCALE: {
                final boolean isLtr = taskConfiguration.getLayoutDirection()
                        == View.LAYOUT_DIRECTION_LTR;
                return isLtr
                        ? getLeftContainerBounds(taskConfiguration, splitAttributes, foldingFeature)
                        : getRightContainerBounds(taskConfiguration, splitAttributes,
                                foldingFeature);
            }
            case SplitAttributes.LayoutDirection.TOP_TO_BOTTOM: {
                return getTopContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            case SplitAttributes.LayoutDirection.BOTTOM_TO_TOP: {
                return getBottomContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            default:
                throw new IllegalArgumentException("Unknown layout direction:"
                        + splitAttributes.getLayoutDirection());
        }
    }

    @NonNull
    private Rect getSecondaryBounds(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @Nullable FoldingFeature foldingFeature) {
        if (!shouldShowSplit(splitAttributes)) {
            return new Rect();
        }
        switch (splitAttributes.getLayoutDirection()) {
            case SplitAttributes.LayoutDirection.LEFT_TO_RIGHT: {
                return getRightContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            case SplitAttributes.LayoutDirection.RIGHT_TO_LEFT: {
                return getLeftContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            case SplitAttributes.LayoutDirection.LOCALE: {
                final boolean isLtr = taskConfiguration.getLayoutDirection()
                        == View.LAYOUT_DIRECTION_LTR;
                return isLtr
                        ? getRightContainerBounds(taskConfiguration, splitAttributes,
                                foldingFeature)
                        : getLeftContainerBounds(taskConfiguration, splitAttributes,
                                foldingFeature);
            }
            case SplitAttributes.LayoutDirection.TOP_TO_BOTTOM: {
                return getBottomContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            case SplitAttributes.LayoutDirection.BOTTOM_TO_TOP: {
                return getTopContainerBounds(taskConfiguration, splitAttributes, foldingFeature);
            }
            default:
                throw new IllegalArgumentException("Unknown layout direction:"
                        + splitAttributes.getLayoutDirection());
        }
    }

    @NonNull
    private Rect getLeftContainerBounds(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @Nullable FoldingFeature foldingFeature) {
        final int right = computeBoundaryBetweenContainers(taskConfiguration, splitAttributes,
                CONTAINER_POSITION_LEFT, foldingFeature);
        final Rect taskBounds = taskConfiguration.windowConfiguration.getBounds();
        return new Rect(taskBounds.left, taskBounds.top, right, taskBounds.bottom);
    }

    @NonNull
    private Rect getRightContainerBounds(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @Nullable FoldingFeature foldingFeature) {
        final int left = computeBoundaryBetweenContainers(taskConfiguration, splitAttributes,
                CONTAINER_POSITION_RIGHT, foldingFeature);
        final Rect parentBounds = taskConfiguration.windowConfiguration.getBounds();
        return new Rect(left, parentBounds.top, parentBounds.right, parentBounds.bottom);
    }

    @NonNull
    private Rect getTopContainerBounds(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @Nullable FoldingFeature foldingFeature) {
        final int bottom = computeBoundaryBetweenContainers(taskConfiguration, splitAttributes,
                CONTAINER_POSITION_TOP, foldingFeature);
        final Rect parentBounds = taskConfiguration.windowConfiguration.getBounds();
        return new Rect(parentBounds.left, parentBounds.top, parentBounds.right, bottom);
    }

    @NonNull
    private Rect getBottomContainerBounds(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @Nullable FoldingFeature foldingFeature) {
        final int top = computeBoundaryBetweenContainers(taskConfiguration, splitAttributes,
                CONTAINER_POSITION_BOTTOM, foldingFeature);
        final Rect parentBounds = taskConfiguration.windowConfiguration.getBounds();
        return new Rect(parentBounds.left, top, parentBounds.right, parentBounds.bottom);
    }

    /**
     * Computes the boundary position between the primary and the secondary containers for the given
     * {@link ContainerPosition} with {@link SplitAttributes}, current window and device states.
     * <ol>
     *     <li>For {@link #CONTAINER_POSITION_TOP}, it computes the boundary with the bottom
     *       container, which is {@link Rect#bottom} of the top container bounds.</li>
     *     <li>For {@link #CONTAINER_POSITION_BOTTOM}, it computes the boundary with the top
     *       container, which is {@link Rect#top} of the bottom container bounds.</li>
     *     <li>For {@link #CONTAINER_POSITION_LEFT}, it computes the boundary with the right
     *       container, which is {@link Rect#right} of the left container bounds.</li>
     *     <li>For {@link #CONTAINER_POSITION_RIGHT}, it computes the boundary with the bottom
     *       container, which is {@link Rect#left} of the right container bounds.</li>
     * </ol>
     *
     * @see #getTopContainerBounds(Configuration, SplitAttributes, FoldingFeature)
     * @see #getBottomContainerBounds(Configuration, SplitAttributes, FoldingFeature)
     * @see #getLeftContainerBounds(Configuration, SplitAttributes, FoldingFeature)
     * @see #getRightContainerBounds(Configuration, SplitAttributes, FoldingFeature)
     */
    private int computeBoundaryBetweenContainers(@NonNull Configuration taskConfiguration,
            @NonNull SplitAttributes splitAttributes, @ContainerPosition int position,
            @Nullable FoldingFeature foldingFeature) {
        final Rect parentBounds = taskConfiguration.windowConfiguration.getBounds();
        final int startPoint = shouldSplitHorizontally(splitAttributes)
                ? parentBounds.top
                : parentBounds.left;
        final int dimen = shouldSplitHorizontally(splitAttributes)
                ? parentBounds.height()
                : parentBounds.width();
        final SplitType splitType = splitAttributes.getSplitType();
        if (splitType instanceof RatioSplitType) {
            final RatioSplitType splitRatio = (RatioSplitType) splitType;
            return (int) (startPoint + dimen * splitRatio.getRatio());
        }
        // At this point, SplitType must be a HingeSplitType and foldingFeature must be
        // non-null. RatioSplitType and ExpandContainerSplitType have been handled earlier.
        Objects.requireNonNull(foldingFeature);
        if (!(splitType instanceof HingeSplitType)) {
            throw new IllegalArgumentException("Unknown splitType:" + splitType);
        }
        final Rect hingeArea = foldingFeature.getBounds();
        switch (position) {
            case CONTAINER_POSITION_LEFT:
                return hingeArea.left;
            case CONTAINER_POSITION_TOP:
                return hingeArea.top;
            case CONTAINER_POSITION_RIGHT:
                return hingeArea.right;
            case CONTAINER_POSITION_BOTTOM:
                return hingeArea.bottom;
            default:
                throw new IllegalArgumentException("Unknown position:" + position);
        }
    }

    @Nullable
    private FoldingFeature getFoldingFeature(@NonNull TaskProperties taskProperties) {
        final int displayId = taskProperties.getDisplayId();
        final WindowConfiguration windowConfiguration = taskProperties.getConfiguration()
                .windowConfiguration;
        final WindowLayoutInfo info = mWindowLayoutComponent
                .getCurrentWindowLayoutInfo(displayId, windowConfiguration);
        final List<DisplayFeature> displayFeatures = info.getDisplayFeatures();
        if (displayFeatures.isEmpty()) {
            return null;
        }
        final List<FoldingFeature> foldingFeatures = new ArrayList<>();
        for (DisplayFeature displayFeature : displayFeatures) {
            if (displayFeature instanceof FoldingFeature) {
                foldingFeatures.add((FoldingFeature) displayFeature);
            }
        }
        // TODO(b/240219484): Support device with multiple hinges.
        if (foldingFeatures.size() != 1) {
            return null;
        }
        return foldingFeatures.get(0);
    }

    /**
     * Indicates that this {@link SplitAttributes} splits the task horizontally. Returns
     * {@code false} if this {@link SplitAttributes} splits the task vertically.
     */
    private static boolean shouldSplitHorizontally(SplitAttributes splitAttributes) {
        switch (splitAttributes.getLayoutDirection()) {
            case SplitAttributes.LayoutDirection.TOP_TO_BOTTOM:
            case SplitAttributes.LayoutDirection.BOTTOM_TO_TOP:
                return true;
            default:
                return false;
        }
    }

    /**
     * Computes the {@link SplitType} with the {@link SplitAttributes} and the current device and
     * window state.
     * If passed {@link SplitAttributes#getSplitType} is a {@link RatioSplitType}. It reversed
     * the ratio if the computed {@link SplitAttributes#getLayoutDirection} is
     * {@link SplitAttributes.LayoutDirection.LEFT_TO_RIGHT} or
     * {@link SplitAttributes.LayoutDirection.BOTTOM_TO_TOP} to make the bounds calculation easier.
     * If passed {@link SplitAttributes#getSplitType} is a {@link HingeSplitType}, it checks
     * the current device and window states to determine whether the split container should split
     * by hinge or use {@link HingeSplitType#getFallbackSplitType}.
     */
    private SplitType computeSplitType(@NonNull SplitAttributes splitAttributes,
            @NonNull Configuration taskConfiguration, @Nullable FoldingFeature foldingFeature) {
        final int layoutDirection = splitAttributes.getLayoutDirection();
        final SplitType splitType = splitAttributes.getSplitType();
        if (splitType instanceof ExpandContainersSplitType) {
            return splitType;
        } else if (splitType instanceof RatioSplitType) {
            final RatioSplitType splitRatio = (RatioSplitType) splitType;
            // Reverse the ratio for RIGHT_TO_LEFT and BOTTOM_TO_TOP to make the boundary
            // computation have the same direction, which is from (top, left) to (bottom, right).
            final SplitType reversedSplitType = new RatioSplitType(1 - splitRatio.getRatio());
            switch (layoutDirection) {
                case SplitAttributes.LayoutDirection.LEFT_TO_RIGHT:
                case SplitAttributes.LayoutDirection.TOP_TO_BOTTOM:
                    return splitType;
                case SplitAttributes.LayoutDirection.RIGHT_TO_LEFT:
                case SplitAttributes.LayoutDirection.BOTTOM_TO_TOP:
                    return reversedSplitType;
                case LayoutDirection.LOCALE: {
                    boolean isLtr = taskConfiguration.getLayoutDirection()
                            == View.LAYOUT_DIRECTION_LTR;
                    return isLtr ? splitType : reversedSplitType;
                }
            }
        } else if (splitType instanceof HingeSplitType) {
            final HingeSplitType hinge = (HingeSplitType) splitType;
            @WindowingMode
            final int windowingMode = taskConfiguration.windowConfiguration.getWindowingMode();
            return shouldSplitByHinge(splitAttributes, foldingFeature, windowingMode)
                    ? hinge : hinge.getFallbackSplitType();
        }
        throw new IllegalArgumentException("Unknown SplitType:" + splitType);
    }

    private static boolean shouldSplitByHinge(@NonNull SplitAttributes splitAttributes,
            @Nullable FoldingFeature foldingFeature, @WindowingMode int taskWindowingMode) {
        // Only HingeSplitType may split the task bounds by hinge.
        if (!(splitAttributes.getSplitType() instanceof HingeSplitType)) {
            return false;
        }
        // Device is not foldable, so there's no hinge to match.
        if (foldingFeature == null) {
            return false;
        }
        // The task is in multi-window mode. Match hinge doesn't make sense because current task
        // bounds may not fit display bounds.
        if (WindowConfiguration.inMultiWindowMode(taskWindowingMode)) {
            return false;
        }
        // Return true if how the split attributes split the task bounds matches the orientation of
        // folding area orientation.
        return shouldSplitHorizontally(splitAttributes) == isFoldingAreaHorizontal(foldingFeature);
    }

    private static boolean isFoldingAreaHorizontal(@NonNull FoldingFeature foldingFeature) {
        final Rect bounds = foldingFeature.getBounds();
        return bounds.width() > bounds.height();
    }

    @NonNull
    static TaskProperties getTaskProperties(@NonNull TaskFragmentContainer container) {
        return container.getTaskContainer().getTaskProperties();
    }

    @NonNull
    TaskProperties getTaskProperties(@NonNull Activity activity) {
        final TaskContainer taskContainer = mController.getTaskContainer(
                mController.getTaskId(activity));
        if (taskContainer != null) {
            return taskContainer.getTaskProperties();
        }
        return TaskProperties.getTaskPropertiesFromActivity(activity);
    }

    @NonNull
    WindowMetrics getTaskWindowMetrics(@NonNull Activity activity) {
        return getTaskWindowMetrics(getTaskProperties(activity).getConfiguration());
    }

    @NonNull
    private static WindowMetrics getTaskWindowMetrics(@NonNull Configuration taskConfiguration) {
        final Rect taskBounds = taskConfiguration.windowConfiguration.getBounds();
        // TODO(b/190433398): Supply correct insets.
        return new WindowMetrics(taskBounds, WindowInsets.CONSUMED);
    }
}
