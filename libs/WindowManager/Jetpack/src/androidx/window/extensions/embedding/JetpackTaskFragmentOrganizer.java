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
import static android.window.TaskFragmentOperation.OP_TYPE_SET_ANIMATION_PARAMS;

import static androidx.window.extensions.embedding.SplitContainer.getFinishPrimaryWithSecondaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.getFinishSecondaryWithPrimaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenStacked;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishPrimaryWithSecondary;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishSecondaryWithPrimary;

import android.app.Activity;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Platform default Extensions implementation of {@link TaskFragmentOrganizer} to organize
 * task fragments.
 *
 * All calls into methods of this class are expected to be on the UI thread.
 */
class JetpackTaskFragmentOrganizer extends TaskFragmentOrganizer {

    /** Mapping from the client assigned unique token to the {@link TaskFragmentInfo}. */
    @VisibleForTesting
    final Map<IBinder, TaskFragmentInfo> mFragmentInfos = new ArrayMap<>();

    @NonNull
    private final TaskFragmentCallback mCallback;

    @VisibleForTesting
    @Nullable
    TaskFragmentAnimationController mAnimationController;

    /**
     * Callback that notifies the controller about changes to task fragments.
     */
    interface TaskFragmentCallback {
        void onTransactionReady(@NonNull TaskFragmentTransaction transaction);
    }

    /**
     * @param executor  callbacks from WM Core are posted on this executor. It should be tied to the
     *                  UI thread that all other calls into methods of this class are also on.
     */
    JetpackTaskFragmentOrganizer(@NonNull Executor executor,
            @NonNull TaskFragmentCallback callback) {
        super(executor);
        mCallback = callback;
    }

    @Override
    public void unregisterOrganizer() {
        if (mAnimationController != null) {
            mAnimationController.unregisterRemoteAnimations();
            mAnimationController = null;
        }
        super.unregisterOrganizer();
    }

    /**
     * Overrides the animation for transitions of embedded activities organized by this organizer.
     */
    void overrideSplitAnimation() {
        if (mAnimationController == null) {
            mAnimationController = new TaskFragmentAnimationController(this);
        }
        mAnimationController.registerRemoteAnimations();
    }

    /**
     * Starts a new Activity and puts it into split with an existing Activity side-by-side.
     * @param launchingFragmentToken    token for the launching TaskFragment. If it exists, it will
     *                                  be resized based on {@param launchingFragmentBounds}.
     *                                  Otherwise, we will create a new TaskFragment with the given
     *                                  token for the {@param launchingActivity}.
     * @param launchingFragmentBounds   the initial bounds for the launching TaskFragment.
     * @param launchingActivity the Activity to put on the left hand side of the split as the
     *                          primary.
     * @param secondaryFragmentToken    token to create the secondary TaskFragment with.
     * @param secondaryFragmentBounds   the initial bounds for the secondary TaskFragment
     * @param activityIntent    Intent to start the secondary Activity with.
     * @param activityOptions   ActivityOptions to start the secondary Activity with.
     * @param windowingMode     the windowing mode to set for the TaskFragments.
     * @param splitAttributes   the {@link SplitAttributes} to represent the split.
     */
    void startActivityToSide(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder launchingFragmentToken, @NonNull Rect launchingFragmentBounds,
            @NonNull Activity launchingActivity, @NonNull IBinder secondaryFragmentToken,
            @NonNull Rect secondaryFragmentBounds, @NonNull Intent activityIntent,
            @Nullable Bundle activityOptions, @NonNull SplitRule rule,
            @WindowingMode int windowingMode, @NonNull SplitAttributes splitAttributes) {
        final IBinder ownerToken = launchingActivity.getActivityToken();

        // Create or resize the launching TaskFragment.
        if (mFragmentInfos.containsKey(launchingFragmentToken)) {
            resizeTaskFragment(wct, launchingFragmentToken, launchingFragmentBounds);
            updateWindowingMode(wct, launchingFragmentToken, windowingMode);
        } else {
            createTaskFragmentAndReparentActivity(wct, launchingFragmentToken, ownerToken,
                    launchingFragmentBounds, windowingMode, launchingActivity);
        }
        updateAnimationParams(wct, launchingFragmentToken, splitAttributes);

        // Create a TaskFragment for the secondary activity.
        final TaskFragmentCreationParams fragmentOptions = new TaskFragmentCreationParams.Builder(
                getOrganizerToken(), secondaryFragmentToken, ownerToken)
                .setInitialBounds(secondaryFragmentBounds)
                .setWindowingMode(windowingMode)
                // Make sure to set the paired fragment token so that the new TaskFragment will be
                // positioned right above the paired TaskFragment.
                // This is needed in case we need to launch a placeholder Activity to split below a
                // transparent always-expand Activity.
                .setPairedPrimaryFragmentToken(launchingFragmentToken)
                .build();
        createTaskFragment(wct, fragmentOptions);
        updateAnimationParams(wct, secondaryFragmentToken, splitAttributes);
        wct.startActivityInTaskFragment(secondaryFragmentToken, ownerToken, activityIntent,
                activityOptions);

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragments(wct, launchingFragmentToken, secondaryFragmentToken, rule);
        setCompanionTaskFragment(wct, launchingFragmentToken, secondaryFragmentToken, rule,
                false /* isStacked */);
    }

    /**
     * Expands an existing TaskFragment to fill parent.
     * @param wct WindowContainerTransaction in which the task fragment should be resized.
     * @param fragmentToken token of an existing TaskFragment.
     */
    void expandTaskFragment(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken) {
        resizeTaskFragment(wct, fragmentToken, new Rect());
        setAdjacentTaskFragments(wct, fragmentToken, null /* secondary */, null /* splitRule */);
        updateWindowingMode(wct, fragmentToken, WINDOWING_MODE_UNDEFINED);
        updateAnimationParams(wct, fragmentToken, TaskFragmentAnimationParams.DEFAULT);
    }

    /**
     * Expands an Activity to fill parent by moving it to a new TaskFragment.
     * @param fragmentToken token to create new TaskFragment with.
     * @param activity      activity to move to the fill-parent TaskFragment.
     */
    void expandActivity(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
            @NonNull Activity activity) {
        createTaskFragmentAndReparentActivity(
                wct, fragmentToken, activity.getActivityToken(), new Rect(),
                WINDOWING_MODE_UNDEFINED, activity);
        updateAnimationParams(wct, fragmentToken, TaskFragmentAnimationParams.DEFAULT);
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    void createTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
            @NonNull IBinder ownerToken, @NonNull Rect bounds, @WindowingMode int windowingMode) {
        final TaskFragmentCreationParams fragmentOptions = new TaskFragmentCreationParams.Builder(
                getOrganizerToken(), fragmentToken, ownerToken)
                .setInitialBounds(bounds)
                .setWindowingMode(windowingMode)
                .build();
        createTaskFragment(wct, fragmentOptions);
    }

    void createTaskFragment(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentCreationParams fragmentOptions) {
        if (mFragmentInfos.containsKey(fragmentOptions.getFragmentToken())) {
            throw new IllegalArgumentException(
                    "There is an existing TaskFragment with fragmentToken="
                            + fragmentOptions.getFragmentToken());
        }
        wct.createTaskFragment(fragmentOptions);
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    private void createTaskFragmentAndReparentActivity(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken, @NonNull IBinder ownerToken, @NonNull Rect bounds,
            @WindowingMode int windowingMode, @NonNull Activity activity) {
        createTaskFragment(wct, fragmentToken, ownerToken, bounds, windowingMode);
        wct.reparentActivityToTaskFragment(fragmentToken, activity.getActivityToken());
    }

    void setAdjacentTaskFragments(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder primary, @Nullable IBinder secondary, @Nullable SplitRule splitRule) {
        WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams = null;
        final boolean finishSecondaryWithPrimary =
                splitRule != null && SplitContainer.shouldFinishSecondaryWithPrimary(splitRule);
        final boolean finishPrimaryWithSecondary =
                splitRule != null && SplitContainer.shouldFinishPrimaryWithSecondary(splitRule);
        if (finishSecondaryWithPrimary || finishPrimaryWithSecondary) {
            adjacentParams = new WindowContainerTransaction.TaskFragmentAdjacentParams();
            adjacentParams.setShouldDelayPrimaryLastActivityRemoval(finishSecondaryWithPrimary);
            adjacentParams.setShouldDelaySecondaryLastActivityRemoval(finishPrimaryWithSecondary);
        }
        wct.setAdjacentTaskFragments(primary, secondary, adjacentParams);
    }

    void setCompanionTaskFragment(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder primary, @NonNull IBinder secondary, @NonNull SplitRule splitRule,
            boolean isStacked) {
        final boolean finishPrimaryWithSecondary;
        if (isStacked) {
            finishPrimaryWithSecondary = shouldFinishAssociatedContainerWhenStacked(
                    getFinishPrimaryWithSecondaryBehavior(splitRule));
        } else {
            finishPrimaryWithSecondary = shouldFinishPrimaryWithSecondary(splitRule);
        }
        wct.setCompanionTaskFragment(primary, finishPrimaryWithSecondary ? secondary : null);

        final boolean finishSecondaryWithPrimary;
        if (isStacked) {
            finishSecondaryWithPrimary = shouldFinishAssociatedContainerWhenStacked(
                    getFinishSecondaryWithPrimaryBehavior(splitRule));
        } else {
            finishSecondaryWithPrimary = shouldFinishSecondaryWithPrimary(splitRule);
        }
        wct.setCompanionTaskFragment(secondary, finishSecondaryWithPrimary ? primary : null);
    }

    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
            @Nullable Rect bounds) {
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        if (bounds == null) {
            bounds = new Rect();
        }
        wct.setBounds(mFragmentInfos.get(fragmentToken).getToken(), bounds);
    }

    void updateWindowingMode(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken, @WindowingMode int windowingMode) {
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        wct.setWindowingMode(mFragmentInfos.get(fragmentToken).getToken(), windowingMode);
    }

    /**
     * Updates the {@link TaskFragmentAnimationParams} for the given TaskFragment based on
     * {@link SplitAttributes}.
     */
    void updateAnimationParams(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken, @NonNull SplitAttributes splitAttributes) {
        updateAnimationParams(wct, fragmentToken, createAnimationParamsOrDefault(splitAttributes));
    }

    void updateAnimationParams(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken, @NonNull TaskFragmentAnimationParams animationParams) {
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_ANIMATION_PARAMS)
                .setAnimationParams(animationParams)
                .build();
        wct.setTaskFragmentOperation(fragmentToken, operation);
    }

    void deleteTaskFragment(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder fragmentToken) {
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        wct.deleteTaskFragment(mFragmentInfos.get(fragmentToken).getToken());
    }

    void updateTaskFragmentInfo(@NonNull TaskFragmentInfo taskFragmentInfo) {
        mFragmentInfos.put(taskFragmentInfo.getFragmentToken(), taskFragmentInfo);
    }

    void removeTaskFragmentInfo(@NonNull TaskFragmentInfo taskFragmentInfo) {
        mFragmentInfos.remove(taskFragmentInfo.getFragmentToken());
    }

    @Override
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        mCallback.onTransactionReady(transaction);
    }

    private static TaskFragmentAnimationParams createAnimationParamsOrDefault(
            @Nullable SplitAttributes splitAttributes) {
        if (splitAttributes == null) {
            return TaskFragmentAnimationParams.DEFAULT;
        }
        return new TaskFragmentAnimationParams.Builder()
                .setAnimationBackgroundColor(splitAttributes.getAnimationBackgroundColor())
                .build();
    }
}
