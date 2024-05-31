/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import static com.android.server.wm.ActivityStarter.Request;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.Size;
import android.util.Slog;
import android.view.Gravity;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The class that defines the default launch params for tasks.
 */
class TaskLaunchParamsModifier implements LaunchParamsModifier {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskLaunchParamsModifier" : TAG_ATM;
    private static final boolean DEBUG = false;

    // Allowance of size matching.
    private static final int EPSILON = 2;

    // Cascade window offset.
    private static final int CASCADING_OFFSET_DP = 75;

    // Threshold how close window corners have to be to call them colliding.
    private static final int BOUNDS_CONFLICT_THRESHOLD = 4;

    // Divide display size by this number to get each step to adjust bounds to avoid conflict.
    private static final int STEP_DENOMINATOR = 16;

    // We always want to step by at least this.
    private static final int MINIMAL_STEP = 1;

    private final ActivityTaskSupervisor mSupervisor;
    private final Rect mTmpBounds = new Rect();
    private final Rect mTmpStableBounds = new Rect();
    private final int[] mTmpDirections = new int[2];

    private TaskDisplayArea mTmpDisplayArea;

    private StringBuilder mLogBuilder;

    TaskLaunchParamsModifier(ActivityTaskSupervisor supervisor) {
        mSupervisor = supervisor;
    }

    @Override
    public int onCalculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable Request request, int phase,
            LaunchParams currentParams, LaunchParams outParams) {
        initLogBuilder(task, activity);
        final int result = calculate(task, layout, activity, source, options, request, phase,
                currentParams, outParams);
        outputLog();
        return result;
    }

    private int calculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable Request request, int phase,
            LaunchParams currentParams, LaunchParams outParams) {
        final ActivityRecord root;
        if (task != null) {
            root = task.getRootActivity() == null ? activity : task.getRootActivity();
        } else {
            root = activity;
        }

        if (root == null && phase != PHASE_DISPLAY) {
            // There is a case that can lead us here. The caller is moving the top activity that is
            // in a task that has multiple activities to PIP mode. For that the caller is creating a
            // new task to host the activity so that we only move the top activity to PIP mode and
            // keep other activities in the previous task. There is no point to apply the launch
            // logic in this case.
            // However, for PHASE_DISPLAY the root may be null, but we still want to get a hint of
            // what the suggested launch display area would be.
            return RESULT_SKIP;
        }

        // STEP 1: Determine the suggested display area to launch the activity/task.
        final TaskDisplayArea suggestedDisplayArea = getPreferredLaunchTaskDisplayArea(task,
                options, source, currentParams, activity, request);
        outParams.mPreferredTaskDisplayArea = suggestedDisplayArea;
        final DisplayContent display = suggestedDisplayArea.mDisplayContent;
        if (DEBUG) {
            appendLog("display-id=" + display.getDisplayId()
                    + " task-display-area-windowing-mode=" + suggestedDisplayArea.getWindowingMode()
                    + " suggested-display-area=" + suggestedDisplayArea);
        }

        if (phase == PHASE_DISPLAY) {
            return RESULT_CONTINUE;
        }

        // STEP 2: Resolve launch windowing mode.
        // STEP 2.1: Determine if any parameter can specify initial bounds/windowing mode. That
        // might be the launch bounds from activity options, or size/gravity passed in layout. It
        // also treats the launch windowing mode in options and source activity windowing mode in
        // some cases as a suggestion for future resolution.
        int launchMode = options != null ? options.getLaunchWindowingMode()
                : WINDOWING_MODE_UNDEFINED;
        // In some cases we want to use the source's windowing mode as the default value, e.g. when
        // source is a freeform window in a fullscreen display launching an activity on the same
        // display.
        if (launchMode == WINDOWING_MODE_UNDEFINED
                && canInheritWindowingModeFromSource(display, suggestedDisplayArea, source)) {
            // The source's windowing mode may be different from its task, e.g. activity is set
            // to fullscreen and its task is pinned windowing mode when the activity is entering
            // pip.
            launchMode = source.getTask().getWindowingMode();
            if (DEBUG) {
                appendLog("inherit-from-source="
                        + WindowConfiguration.windowingModeToString(launchMode));
            }
        }
        // If the launch windowing mode is still undefined, inherit from the target task if the
        // task is already on the right display area (otherwise, the task may be on a different
        // display area that has incompatible windowing mode or the task organizer request to
        // disassociate the leaf task if relaunched and reparented it to TDA as root task).
        if (launchMode == WINDOWING_MODE_UNDEFINED
                && task != null && task.getTaskDisplayArea() == suggestedDisplayArea
                && !task.getRootTask().mReparentLeafTaskIfRelaunch) {
            launchMode = task.getWindowingMode();
            if (DEBUG) {
                appendLog("inherit-from-task="
                        + WindowConfiguration.windowingModeToString(launchMode));
            }
        }
        // hasInitialBounds is set if either activity options or layout has specified bounds. If
        // that's set we'll skip some adjustments later to avoid overriding the initial bounds.
        boolean hasInitialBounds = false;
        // hasInitialBoundsForSuggestedDisplayAreaInFreeformWindow is set if the outParams.mBounds
        // is set with the suggestedDisplayArea. If it is set, but the eventual TaskDisplayArea is
        // different, we should recalculating the bounds.
        boolean hasInitialBoundsForSuggestedDisplayAreaInFreeformWindow = false;
        // Note that initial bounds needs to be set to fullscreen tasks too as it's used as restore
        // bounds.
        final boolean canCalculateBoundsForFullscreenTask =
                canCalculateBoundsForFullscreenTask(suggestedDisplayArea, launchMode);
        final boolean canApplyFreeformWindowPolicy =
                canApplyFreeformWindowPolicy(suggestedDisplayArea, launchMode);
        final boolean canApplyWindowLayout = layout != null
                && (canApplyFreeformWindowPolicy || canCalculateBoundsForFullscreenTask);
        final boolean canApplyBoundsFromActivityOptions =
                mSupervisor.canUseActivityOptionsLaunchBounds(options)
                        && (canApplyFreeformWindowPolicy
                        || canApplyPipWindowPolicy(launchMode)
                        || canCalculateBoundsForFullscreenTask);

        if (canApplyBoundsFromActivityOptions) {
            hasInitialBounds = true;
            // |launchMode| at this point can be fullscreen, PIP, MultiWindow, etc. Only set
            // freeform windowing mode if appropriate by checking |canApplyFreeformWindowPolicy|.
            launchMode = launchMode == WINDOWING_MODE_UNDEFINED && canApplyFreeformWindowPolicy
                    ? WINDOWING_MODE_FREEFORM
                    : launchMode;
            outParams.mBounds.set(options.getLaunchBounds());
            if (DEBUG) appendLog("activity-options-bounds=" + outParams.mBounds);
        } else if (canApplyWindowLayout) {
            mTmpBounds.set(currentParams.mBounds);
            getLayoutBounds(suggestedDisplayArea, root, layout, mTmpBounds);
            if (!mTmpBounds.isEmpty()) {
                launchMode = canApplyFreeformWindowPolicy ? WINDOWING_MODE_FREEFORM : launchMode;
                outParams.mBounds.set(mTmpBounds);
                hasInitialBounds = true;
                hasInitialBoundsForSuggestedDisplayAreaInFreeformWindow = true;
                if (DEBUG) appendLog("bounds-from-layout=" + outParams.mBounds);
            } else {
                if (DEBUG) appendLog("empty-window-layout");
            }
        } else if (launchMode == WINDOWING_MODE_MULTI_WINDOW
                && options != null && options.getLaunchBounds() != null) {
            // TODO: Investigate whether we can migrate this clause to the
            //  |canApplyBoundsFromActivityOptions| case above.
            outParams.mBounds.set(options.getLaunchBounds());
            hasInitialBounds = true;
            if (DEBUG) appendLog("multiwindow-activity-options-bounds=" + outParams.mBounds);
        }

        // STEP 2.2: Check if previous modifier or the controller (referred as "callers" below) has
        // some opinions on launch mode and launch bounds. If they have opinions and there is no
        // initial bounds set in parameters. Note the check on display ID is also input param
        // related because we always defer to callers' suggestion if there is no specific display ID
        // in options or from source activity.
        //
        // If opinions from callers don't need any further resolution, we try to honor that as is as
        // much as possible later.

        // Flag to indicate if current param needs no further resolution. It's true it current
        // param isn't freeform mode, or it already has launch bounds.
        boolean fullyResolvedCurrentParam = false;
        // We inherit launch params from previous modifiers or LaunchParamsController if options,
        // layout and display conditions are not contradictory to their suggestions. It's important
        // to carry over their values because LaunchParamsController doesn't automatically do that.
        // We only check if display matches because display area can be changed later.
        if (!currentParams.isEmpty() && !hasInitialBounds
                && (currentParams.mPreferredTaskDisplayArea == null
                    || currentParams.mPreferredTaskDisplayArea.getDisplayId()
                        == display.getDisplayId())) {
            // Only set windowing mode if display is in freeform. If the display is in fullscreen
            // mode we should only launch a task in fullscreen mode.
            if (currentParams.hasWindowingMode()
                    && suggestedDisplayArea.inFreeformWindowingMode()) {
                launchMode = currentParams.mWindowingMode;
                fullyResolvedCurrentParam = launchMode != WINDOWING_MODE_FREEFORM;
                if (DEBUG) {
                    appendLog("inherit-" + WindowConfiguration.windowingModeToString(launchMode));
                }
            }

            if (!currentParams.mBounds.isEmpty()) {
                // Carry over bounds from callers regardless of launch mode because bounds is still
                // used to restore last non-fullscreen bounds when launch mode is not freeform.
                outParams.mBounds.set(currentParams.mBounds);
                fullyResolvedCurrentParam = true;
                if (launchMode == WINDOWING_MODE_FREEFORM) {
                    if (DEBUG) appendLog("inherit-bounds=" + outParams.mBounds);
                }
            }
        }

        // STEP 2.3: Adjust launch parameters as needed for freeform display. We enforce the
        // policies related to unresizable apps here. If an app is unresizable and the freeform
        // size-compat mode is enabled, it can be launched in freeform depending on other properties
        // such as orientation. Otherwise, the app is forcefully launched in maximized. The rest of
        // this step is to define the default policy when there is no initial bounds or a fully
        // resolved current params from callers.

        // hasInitialBoundsForSuggestedDisplayAreaInFreeformMode is set if the outParams.mBounds
        // is set with the suggestedDisplayArea. If it is set, but the eventual TaskDisplayArea is
        // different, we should recalcuating the bounds.
        boolean hasInitialBoundsForSuggestedDisplayAreaInFreeformMode = false;
        if (suggestedDisplayArea.inFreeformWindowingMode()) {
            if (launchMode == WINDOWING_MODE_PINNED) {
                if (DEBUG) appendLog("picture-in-picture");
            } else if (!root.isResizeable()) {
                if (shouldLaunchUnresizableAppInFreeform(root, suggestedDisplayArea, options)) {
                    launchMode = WINDOWING_MODE_FREEFORM;
                    if (outParams.mBounds.isEmpty()) {
                        getTaskBounds(root, suggestedDisplayArea, layout, launchMode,
                                hasInitialBounds, outParams.mBounds);
                        hasInitialBoundsForSuggestedDisplayAreaInFreeformMode = true;
                    }
                    if (DEBUG) appendLog("unresizable-freeform");
                } else {
                    launchMode = WINDOWING_MODE_FULLSCREEN;
                    outParams.mBounds.setEmpty();
                    if (DEBUG) appendLog("unresizable-forced-maximize");
                }
            }
        } else {
            if (DEBUG) appendLog("non-freeform-task-display-area");
        }
        // If launch mode matches display windowing mode, let it inherit from display.
        outParams.mWindowingMode = launchMode == suggestedDisplayArea.getWindowingMode()
                && !shouldUpdateExistingTaskWindowingMode(task, launchMode)
                ? WINDOWING_MODE_UNDEFINED : launchMode;

        if (phase == PHASE_WINDOWING_MODE) {
            return RESULT_CONTINUE;
        }

        // STEP 3: Finalize the display area. Here we allow WM shell route all launches that match
        // certain criteria to specific task display areas.
        final int resolvedMode = (launchMode != WINDOWING_MODE_UNDEFINED) ? launchMode
                : suggestedDisplayArea.getWindowingMode();
        TaskDisplayArea taskDisplayArea = suggestedDisplayArea;
        // If launch task display area is set in options we should just use it. We assume the
        // suggestedDisplayArea has the right one in this case.
        if (options == null || (options.getLaunchTaskDisplayArea() == null
                && options.getLaunchTaskDisplayAreaFeatureId() == FEATURE_UNDEFINED)) {
            final int activityType =
                    mSupervisor.mRootWindowContainer.resolveActivityType(root, options, task);
            display.forAllTaskDisplayAreas(displayArea -> {
                final Task launchRoot = displayArea.getLaunchRootTask(
                        resolvedMode, activityType, null /* ActivityOptions */,
                        null /* sourceTask*/, 0 /* launchFlags */);
                if (launchRoot == null) {
                    return false;
                }
                mTmpDisplayArea = displayArea;
                return true;
            });
            // We may need to recalculate the bounds and the windowing mode if the new
            // TaskDisplayArea is different from the suggested one we used to calculate the two
            // configurations.
            if (mTmpDisplayArea != null && mTmpDisplayArea != suggestedDisplayArea) {
                outParams.mWindowingMode = (launchMode == mTmpDisplayArea.getWindowingMode())
                        ? WINDOWING_MODE_UNDEFINED : launchMode;
                if (hasInitialBoundsForSuggestedDisplayAreaInFreeformWindow) {
                    outParams.mBounds.setEmpty();
                    getLayoutBounds(mTmpDisplayArea, root, layout, outParams.mBounds);
                    hasInitialBounds = !outParams.mBounds.isEmpty();
                } else if (hasInitialBoundsForSuggestedDisplayAreaInFreeformMode) {
                    outParams.mBounds.setEmpty();
                    getTaskBounds(root, mTmpDisplayArea, layout, launchMode,
                            hasInitialBounds, outParams.mBounds);
                }
            }

            if (mTmpDisplayArea != null) {
                taskDisplayArea = mTmpDisplayArea;
                mTmpDisplayArea = null;
                appendLog("overridden-display-area=["
                        + WindowConfiguration.activityTypeToString(activityType) + ", "
                        + WindowConfiguration.windowingModeToString(resolvedMode) + ", "
                        + taskDisplayArea + "]");
            }
        }
        appendLog("display-area=" + taskDisplayArea);
        outParams.mPreferredTaskDisplayArea = taskDisplayArea;

        if (phase == PHASE_DISPLAY_AREA) {
            return RESULT_CONTINUE;
        }

        // STEP 4: Determine final launch bounds based on resolved windowing mode and activity
        // requested orientation. We set bounds to empty for fullscreen mode and keep bounds as is
        // for all other windowing modes that's not freeform mode. One can read comments in
        // relevant methods to further understand this step.
        //
        // We skip making adjustments if the params are fully resolved from previous results.
        if (fullyResolvedCurrentParam) {
            if (resolvedMode == WINDOWING_MODE_FREEFORM) {
                // Make sure bounds are in the displayArea.
                if (currentParams.mPreferredTaskDisplayArea != taskDisplayArea) {
                    adjustBoundsToFitInDisplayArea(taskDisplayArea, layout, outParams.mBounds);
                }
                // Even though we want to keep original bounds, we still don't want it to stomp on
                // an existing task.
                adjustBoundsToAvoidConflictInDisplayArea(taskDisplayArea, outParams.mBounds);
            }
        } else {
            if (source != null && source.inFreeformWindowingMode()
                    && resolvedMode == WINDOWING_MODE_FREEFORM
                    && outParams.mBounds.isEmpty()
                    && source.getDisplayArea() == taskDisplayArea) {
                // Set bounds to be not very far from source activity.
                cascadeBounds(source.getConfiguration().windowConfiguration.getBounds(),
                        taskDisplayArea, outParams.mBounds);
            }
            getTaskBounds(root, taskDisplayArea, layout, resolvedMode, hasInitialBounds,
                    outParams.mBounds);
        }
        return RESULT_CONTINUE;
    }

    private boolean shouldUpdateExistingTaskWindowingMode(Task task, int launchMode) {
        return task != null
                && task.getRequestedOverrideWindowingMode() != WINDOWING_MODE_UNDEFINED
                && task.getRequestedOverrideWindowingMode() != WINDOWING_MODE_PINNED
                && launchMode != task.getRequestedOverrideWindowingMode();
    }

    private TaskDisplayArea getPreferredLaunchTaskDisplayArea(@Nullable Task task,
            @Nullable ActivityOptions options, @Nullable ActivityRecord source,
            @Nullable LaunchParams currentParams, @Nullable ActivityRecord activityRecord,
            @Nullable Request request) {
        TaskDisplayArea taskDisplayArea = null;

        final WindowContainerToken optionLaunchTaskDisplayAreaToken = options != null
                ? options.getLaunchTaskDisplayArea() : null;
        if (optionLaunchTaskDisplayAreaToken != null) {
            taskDisplayArea = (TaskDisplayArea) WindowContainer.fromBinder(
                    optionLaunchTaskDisplayAreaToken.asBinder());
            if (DEBUG) appendLog("display-area-token-from-option=" + taskDisplayArea);
        }

        if (taskDisplayArea == null && options != null) {
            final int launchTaskDisplayAreaFeatureId = options.getLaunchTaskDisplayAreaFeatureId();
            if (launchTaskDisplayAreaFeatureId != FEATURE_UNDEFINED) {
                final int launchDisplayId = options.getLaunchDisplayId() == INVALID_DISPLAY
                        ? DEFAULT_DISPLAY : options.getLaunchDisplayId();
                final DisplayContent dc = mSupervisor.mRootWindowContainer
                        .getDisplayContent(launchDisplayId);
                if (dc != null) {
                    taskDisplayArea = dc.getItemFromTaskDisplayAreas(tda ->
                            tda.mFeatureId == launchTaskDisplayAreaFeatureId ? tda : null);
                    if (DEBUG) appendLog("display-area-feature-from-option=" + taskDisplayArea);
                }
            }
        }

        // If task display area is not specified in options - try display id
        if (taskDisplayArea == null) {
            final int optionLaunchId =
                    options != null ? options.getLaunchDisplayId() : INVALID_DISPLAY;
            if (optionLaunchId != INVALID_DISPLAY) {
                final DisplayContent dc = mSupervisor.mRootWindowContainer
                        .getDisplayContent(optionLaunchId);
                if (dc != null) {
                    taskDisplayArea = dc.getDefaultTaskDisplayArea();
                    if (DEBUG) appendLog("display-from-option=" + optionLaunchId);
                }
            }
        }

        // If the source activity is a no-display activity, pass on the launch display area token
        // from source activity as currently preferred.
        if (taskDisplayArea == null && source != null && source.noDisplay) {
            taskDisplayArea = source.mHandoverTaskDisplayArea;
            if (taskDisplayArea != null) {
                if (DEBUG) appendLog("display-area-from-no-display-source=" + taskDisplayArea);
            } else {
                // Try handover display id
                final int displayId = source.mHandoverLaunchDisplayId;
                final DisplayContent dc =
                        mSupervisor.mRootWindowContainer.getDisplayContent(displayId);
                if (dc != null) {
                    taskDisplayArea = dc.getDefaultTaskDisplayArea();
                    if (DEBUG) appendLog("display-from-no-display-source=" + displayId);
                }
            }
        }

        if (taskDisplayArea == null && source != null) {
            final TaskDisplayArea sourceDisplayArea = source.getDisplayArea();
            if (DEBUG) appendLog("display-area-from-source=" + sourceDisplayArea);
            taskDisplayArea = sourceDisplayArea;
        }

        Task rootTask = (taskDisplayArea == null && task != null)
                ? task.getRootTask() : null;
        if (rootTask != null) {
            if (DEBUG) appendLog("display-from-task=" + rootTask.getDisplayId());
            taskDisplayArea = rootTask.getDisplayArea();
        }

        if (taskDisplayArea == null && options != null) {
            final int callerDisplayId = options.getCallerDisplayId();
            final DisplayContent dc =
                    mSupervisor.mRootWindowContainer.getDisplayContent(callerDisplayId);
            if (dc != null) {
                taskDisplayArea = dc.getDefaultTaskDisplayArea();
                if (DEBUG) appendLog("display-from-caller=" + callerDisplayId);
            }
        }

        if (taskDisplayArea == null && currentParams != null) {
            taskDisplayArea = currentParams.mPreferredTaskDisplayArea;
            if (DEBUG) appendLog("display-area-from-current-params=" + taskDisplayArea);
        }

        // Re-route to default display if the device didn't declare support for multi-display
        if (taskDisplayArea != null && !mSupervisor.mService.mSupportsMultiDisplay
                && taskDisplayArea.getDisplayId() != DEFAULT_DISPLAY) {
            taskDisplayArea = mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
            if (DEBUG) appendLog("display-area-from-no-multidisplay=" + taskDisplayArea);
        }

        // Re-route to default display if the home activity doesn't support multi-display
        if (taskDisplayArea != null && activityRecord != null && activityRecord.isActivityTypeHome()
                && !mSupervisor.mRootWindowContainer.canStartHomeOnDisplayArea(activityRecord.info,
                        taskDisplayArea, false /* allowInstrumenting */)) {
            taskDisplayArea = mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
            if (DEBUG) appendLog("display-area-from-home=" + taskDisplayArea);
        }

        return (taskDisplayArea != null)
                ? taskDisplayArea
                : getFallbackDisplayAreaForActivity(activityRecord, request);
    }

    /**
     * Calculates the default {@link TaskDisplayArea} for a task. We attempt to put the activity
     * within the same display area if possible. The strategy is to find the display in the
     * following order:
     *
     * <ol>
     *     <li>The display area of the top activity from the launching process will be used</li>
     *     <li>The display area of the top activity from the real launching process will be used
     *     </li>
     *     <li>Default display area from the associated root window container.</li>
     * </ol>
     * @param activityRecord the activity being started
     * @param request optional {@link Request} made to start the activity record
     * @return {@link TaskDisplayArea} to house the task
     */
    private TaskDisplayArea getFallbackDisplayAreaForActivity(
            @Nullable ActivityRecord activityRecord, @Nullable Request request) {
        if (activityRecord != null) {
            WindowProcessController controllerFromLaunchingRecord =
                    mSupervisor.mService.getProcessController(
                            activityRecord.launchedFromPid, activityRecord.launchedFromUid);
            if (controllerFromLaunchingRecord != null) {
                final TaskDisplayArea taskDisplayAreaForLaunchingRecord =
                        controllerFromLaunchingRecord.getTopActivityDisplayArea();
                if (taskDisplayAreaForLaunchingRecord != null) {
                    if (DEBUG) {
                        appendLog("display-area-for-launching-record="
                                + taskDisplayAreaForLaunchingRecord);
                    }
                    return taskDisplayAreaForLaunchingRecord;
                }
            }

            WindowProcessController controllerFromProcess =
                    mSupervisor.mService.getProcessController(
                            activityRecord.getProcessName(), activityRecord.getUid());
            if (controllerFromProcess != null) {
                final TaskDisplayArea displayAreaForRecord =
                        controllerFromProcess.getTopActivityDisplayArea();
                if (displayAreaForRecord != null) {
                    if (DEBUG) appendLog("display-area-for-record=" + displayAreaForRecord);
                    return displayAreaForRecord;
                }
            }
        }

        if (request != null) {
            WindowProcessController controllerFromRequest =
                    mSupervisor.mService.getProcessController(
                            request.realCallingPid, request.realCallingUid);
            if (controllerFromRequest != null) {
                final TaskDisplayArea displayAreaFromSourceProcess =
                            controllerFromRequest.getTopActivityDisplayArea();
                if (displayAreaFromSourceProcess != null) {
                    if (DEBUG) {
                        appendLog("display-area-source-process=" + displayAreaFromSourceProcess);
                    }
                    return displayAreaFromSourceProcess;
                }
            }
        }

        final TaskDisplayArea defaultTaskDisplayArea =
                mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
        if (DEBUG) appendLog("display-area-from-default-fallback=" + defaultTaskDisplayArea);
        return defaultTaskDisplayArea;
    }

    private boolean canInheritWindowingModeFromSource(@NonNull DisplayContent display,
            TaskDisplayArea suggestedDisplayArea, @Nullable ActivityRecord source) {
        if (source == null) {
            return false;
        }

        // There is not really any strong reason to tie the launching windowing mode and the source
        // on freeform displays. The launching windowing mode is more tied to the content of the new
        // activities.
        if (suggestedDisplayArea.inFreeformWindowingMode()) {
            return false;
        }

        final int sourceWindowingMode = source.getTask().getWindowingMode();
        if (sourceWindowingMode != WINDOWING_MODE_FULLSCREEN
                && sourceWindowingMode != WINDOWING_MODE_FREEFORM) {
            return false;
        }

        // Only inherit windowing mode if both source and target activities are on the same display.
        // Otherwise we may have unintended freeform windows showing up if an activity in freeform
        // window launches an activity on a fullscreen display by specifying display ID.
        return display.getDisplayId() == source.getDisplayId();
    }

    private boolean canCalculateBoundsForFullscreenTask(@NonNull TaskDisplayArea displayArea,
                                                        int launchMode) {
        return mSupervisor.mService.mSupportsFreeformWindowManagement
                && ((displayArea.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && launchMode == WINDOWING_MODE_UNDEFINED)
                || launchMode == WINDOWING_MODE_FULLSCREEN);
    }

    private boolean canApplyFreeformWindowPolicy(@NonNull TaskDisplayArea suggestedDisplayArea,
            int launchMode) {
        return mSupervisor.mService.mSupportsFreeformWindowManagement
                && ((suggestedDisplayArea.inFreeformWindowingMode()
                && launchMode == WINDOWING_MODE_UNDEFINED)
                || launchMode == WINDOWING_MODE_FREEFORM);
    }

    private boolean canApplyPipWindowPolicy(int launchMode) {
        return mSupervisor.mService.mSupportsPictureInPicture
                && launchMode == WINDOWING_MODE_PINNED;
    }

    private void getLayoutBounds(@NonNull TaskDisplayArea displayArea, @NonNull ActivityRecord root,
            @NonNull ActivityInfo.WindowLayout windowLayout, @NonNull Rect inOutBounds) {
        final int verticalGravity = windowLayout.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        final int horizontalGravity = windowLayout.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (!windowLayout.hasSpecifiedSize() && verticalGravity == 0 && horizontalGravity == 0) {
            inOutBounds.setEmpty();
            return;
        }

        // Use stable frame instead of raw frame to avoid launching freeform windows on top of
        // stable insets, which usually are system widgets such as sysbar & navbar.
        final Rect stableBounds = mTmpStableBounds;
        displayArea.getStableRect(stableBounds);

        if (windowLayout.hasSpecifiedSize()) {
            LaunchParamsUtil.calculateLayoutBounds(stableBounds, windowLayout, inOutBounds,
                    /* desiredBounds */ null);
        } else if (inOutBounds.isEmpty()) {
            getTaskBounds(root, displayArea, windowLayout, WINDOWING_MODE_FREEFORM,
                    /* hasInitialBounds */ false, inOutBounds);
        }
        LaunchParamsUtil.applyLayoutGravity(verticalGravity, horizontalGravity, inOutBounds,
                stableBounds);
    }

    private boolean shouldLaunchUnresizableAppInFreeform(ActivityRecord activity,
            TaskDisplayArea displayArea, @Nullable ActivityOptions options) {
        if (options != null && options.getLaunchWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            // Do not launch the activity in freeform if it explicitly requested fullscreen mode.
            return false;
        }
        if (!activity.supportsFreeformInDisplayArea(displayArea) || activity.isResizeable()) {
            return false;
        }

        final int displayOrientation = orientationFromBounds(displayArea.getBounds());
        final int activityOrientation = resolveOrientation(activity, displayArea,
                displayArea.getBounds());
        if (displayArea.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && displayOrientation != activityOrientation) {
            return true;
        }

        return false;
    }

    /**
     * Resolves activity requested orientation to 4 categories:
     * 1) {@link ActivityInfo#SCREEN_ORIENTATION_LOCKED} indicating app wants to lock down
     *    orientation;
     * 2) {@link ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE} indicating app wants to be in landscape;
     * 3) {@link ActivityInfo#SCREEN_ORIENTATION_PORTRAIT} indicating app wants to be in portrait;
     * 4) {@link ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} indicating app can handle any
     *    orientation.
     *
     * @param activity the activity to check
     * @return corresponding resolved orientation value.
     */
    private int resolveOrientation(@NonNull ActivityRecord activity) {
        int orientation = activity.info.screenOrientation;
        switch (orientation) {
            case SCREEN_ORIENTATION_NOSENSOR:
            case SCREEN_ORIENTATION_LOCKED:
                orientation = SCREEN_ORIENTATION_LOCKED;
                break;
            case SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            case SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
            case SCREEN_ORIENTATION_USER_LANDSCAPE:
            case SCREEN_ORIENTATION_LANDSCAPE:
                if (DEBUG) appendLog("activity-requested-landscape");
                orientation = SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case SCREEN_ORIENTATION_SENSOR_PORTRAIT:
            case SCREEN_ORIENTATION_REVERSE_PORTRAIT:
            case SCREEN_ORIENTATION_USER_PORTRAIT:
            case SCREEN_ORIENTATION_PORTRAIT:
                if (DEBUG) appendLog("activity-requested-portrait");
                orientation = SCREEN_ORIENTATION_PORTRAIT;
                break;
            default:
                orientation = SCREEN_ORIENTATION_UNSPECIFIED;
        }

        return orientation;
    }

    private void cascadeBounds(@NonNull Rect srcBounds, @NonNull TaskDisplayArea displayArea,
            @NonNull Rect outBounds) {
        outBounds.set(srcBounds);
        float density = (float) displayArea.getConfiguration().densityDpi / DENSITY_DEFAULT;
        final int defaultOffset = (int) (CASCADING_OFFSET_DP * density + 0.5f);

        displayArea.getBounds(mTmpBounds);
        final int dx = Math.min(defaultOffset, Math.max(0, mTmpBounds.right - srcBounds.right));
        final int dy = Math.min(defaultOffset, Math.max(0, mTmpBounds.bottom - srcBounds.bottom));
        outBounds.offset(dx, dy);
    }

    private void getTaskBounds(@NonNull ActivityRecord root, @NonNull TaskDisplayArea displayArea,
            @NonNull ActivityInfo.WindowLayout layout, int resolvedMode, boolean hasInitialBounds,
            @NonNull Rect inOutBounds) {
        if (resolvedMode != WINDOWING_MODE_FREEFORM
                && resolvedMode != WINDOWING_MODE_FULLSCREEN) {
            // This function should be used only for freeform bounds adjustment. Freeform bounds
            // needs to be set to fullscreen tasks too as restore bounds.
            if (DEBUG) {
                appendLog("skip-bounds-" + WindowConfiguration.windowingModeToString(resolvedMode));
            }
            return;
        }

        final int orientation = resolveOrientation(root, displayArea, inOutBounds);
        if (orientation != SCREEN_ORIENTATION_PORTRAIT
                && orientation != SCREEN_ORIENTATION_LANDSCAPE) {
            throw new IllegalStateException(
                    "Orientation must be one of portrait or landscape, but it's "
                    + ActivityInfo.screenOrientationToString(orientation));
        }

        // First we get the default size we want.
        displayArea.getStableRect(mTmpStableBounds);
        final Size defaultSize = LaunchParamsUtil.getDefaultFreeformSize(root, displayArea,
                layout, orientation, mTmpStableBounds);
        mTmpBounds.set(0, 0, defaultSize.getWidth(), defaultSize.getHeight());
        if (hasInitialBounds || sizeMatches(inOutBounds, mTmpBounds)) {
            // We're here because either input parameters specified initial bounds, or the suggested
            // bounds have the same size of the default freeform size. We should use the suggested
            // bounds if possible -- so if app can handle the orientation we just use it, and if not
            // we transpose the suggested bounds in-place.
            if (orientation == orientationFromBounds(inOutBounds)) {
                if (DEBUG) appendLog("freeform-size-orientation-match=" + inOutBounds);
            } else {
                // Meh, orientation doesn't match. Let's rotate inOutBounds in-place.
                LaunchParamsUtil.centerBounds(displayArea, inOutBounds.height(),
                        inOutBounds.width(), inOutBounds);
                if (DEBUG) appendLog("freeform-orientation-mismatch=" + inOutBounds);
            }
        } else {
            // We are here either because there is no suggested bounds, or the suggested bounds is
            // a cascade from source activity. We should use the default freeform size and center it
            // to the center of suggested bounds (or the displayArea if no suggested bounds). The
            // default size might be too big to center to source activity bounds in displayArea, so
            // we may need to move it back to the displayArea.
            adjustBoundsToFitInDisplayArea(displayArea, layout, mTmpBounds);
            inOutBounds.setEmpty();
            LaunchParamsUtil.centerBounds(displayArea, mTmpBounds.width(), mTmpBounds.height(),
                    inOutBounds);
            if (DEBUG) appendLog("freeform-size-mismatch=" + inOutBounds);
        }

        // Lastly we adjust bounds to avoid conflicts with other tasks as much as possible.
        adjustBoundsToAvoidConflictInDisplayArea(displayArea, inOutBounds);
    }

    private int convertOrientationToScreenOrientation(int orientation) {
        switch (orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                return SCREEN_ORIENTATION_LANDSCAPE;
            case Configuration.ORIENTATION_PORTRAIT:
                return SCREEN_ORIENTATION_PORTRAIT;
            default:
                return SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private int resolveOrientation(@NonNull ActivityRecord root,
            @NonNull TaskDisplayArea displayArea, @NonNull Rect bounds) {
        int orientation = resolveOrientation(root);

        if (orientation == SCREEN_ORIENTATION_LOCKED) {
            orientation = bounds.isEmpty()
                    ? convertOrientationToScreenOrientation(
                            displayArea.getConfiguration().orientation)
                    : orientationFromBounds(bounds);
            if (DEBUG) {
                appendLog(bounds.isEmpty() ? "locked-orientation-from-display=" + orientation
                        : "locked-orientation-from-bounds=" + bounds);
            }
        }

        if (orientation == SCREEN_ORIENTATION_UNSPECIFIED) {
            orientation = bounds.isEmpty() ? SCREEN_ORIENTATION_PORTRAIT
                    : orientationFromBounds(bounds);
            if (DEBUG) {
                appendLog(bounds.isEmpty() ? "default-portrait"
                        : "orientation-from-bounds=" + bounds);
            }
        }

        return orientation;
    }

    private void adjustBoundsToFitInDisplayArea(@NonNull TaskDisplayArea displayArea,
                                                @NonNull ActivityInfo.WindowLayout layout,
                                                @NonNull Rect inOutBounds) {
        final int layoutDirection = mSupervisor.mRootWindowContainer.getConfiguration()
                .getLayoutDirection();
        LaunchParamsUtil.adjustBoundsToFitInDisplayArea(displayArea, layoutDirection, layout,
                inOutBounds);
    }

    /**
     * Adjusts input bounds to avoid conflict with existing tasks in the displayArea.
     *
     * If the input bounds conflict with existing tasks, this method scans the bounds in a series of
     * directions to find a location where the we can put the bounds in displayArea without conflict
     * with any other tasks.
     *
     * It doesn't try to adjust bounds that's not fully in the given displayArea.
     *
     * @param displayArea the displayArea which tasks are to check
     * @param inOutBounds the bounds used to input initial bounds and output result bounds
     */
    private void adjustBoundsToAvoidConflictInDisplayArea(@NonNull TaskDisplayArea displayArea,
            @NonNull Rect inOutBounds) {
        final List<Rect> taskBoundsToCheck = new ArrayList<>();
        displayArea.forAllRootTasks(task -> {
            if (!task.inFreeformWindowingMode()) {
                return;
            }

            for (int j = 0; j < task.getChildCount(); ++j) {
                taskBoundsToCheck.add(task.getChildAt(j).getBounds());
            }
        }, false /* traverseTopToBottom */);
        adjustBoundsToAvoidConflict(displayArea.getBounds(), taskBoundsToCheck, inOutBounds);
    }

    /**
     * Adjusts input bounds to avoid conflict with provided displayArea bounds and list of tasks
     * bounds for the displayArea.
     *
     * Scans the bounds in directions to find a candidate location that does not conflict with the
     * provided list of task bounds. If starting bounds are outside the displayArea bounds or if no
     * suitable candidate bounds are found, the method returns the input bounds.
     *
     * @param displayAreaBounds displayArea bounds used to restrict the candidate bounds
     * @param taskBoundsToCheck list of task bounds to check for conflict
     * @param inOutBounds the bounds used to input initial bounds and output result bounds
     */
    @VisibleForTesting
    void adjustBoundsToAvoidConflict(@NonNull Rect displayAreaBounds,
            @NonNull List<Rect> taskBoundsToCheck,
            @NonNull Rect inOutBounds) {
        if (!displayAreaBounds.contains(inOutBounds)) {
            // The initial bounds are already out of displayArea. The scanning algorithm below
            // doesn't work so well with them.
            return;
        }

        if (!boundsConflict(taskBoundsToCheck, inOutBounds)) {
            // Current proposal doesn't conflict with any task. Early return to avoid unnecessary
            // calculation.
            return;
        }

        calculateCandidateShiftDirections(displayAreaBounds, inOutBounds);
        for (int direction : mTmpDirections) {
            if (direction == Gravity.NO_GRAVITY) {
                // We exhausted candidate directions, give up.
                break;
            }

            mTmpBounds.set(inOutBounds);
            while (boundsConflict(taskBoundsToCheck, mTmpBounds)
                    && displayAreaBounds.contains(mTmpBounds)) {
                shiftBounds(direction, displayAreaBounds, mTmpBounds);
            }

            if (!boundsConflict(taskBoundsToCheck, mTmpBounds)
                    && displayAreaBounds.contains(mTmpBounds)) {
                // Found a candidate. Just use this.
                inOutBounds.set(mTmpBounds);
                if (DEBUG) appendLog("avoid-bounds-conflict=" + inOutBounds);
                return;
            }

            // Didn't find a conflict free bounds here. Try the next candidate direction.
        }

        // We failed to find a conflict free location. Just keep the original result.
    }

    /**
     * Determines scanning directions and their priorities to avoid bounds conflict.
     *
     * @param availableBounds bounds that the result must be in
     * @param initialBounds initial bounds when start scanning
     */
    private void calculateCandidateShiftDirections(@NonNull Rect availableBounds,
            @NonNull Rect initialBounds) {
        for (int i = 0; i < mTmpDirections.length; ++i) {
            mTmpDirections[i] = Gravity.NO_GRAVITY;
        }

        final int oneThirdWidth = (2 * availableBounds.left + availableBounds.right) / 3;
        final int twoThirdWidth = (availableBounds.left + 2 * availableBounds.right) / 3;
        final int centerX = initialBounds.centerX();
        if (centerX < oneThirdWidth) {
            // Too close to left, just scan to the right.
            mTmpDirections[0] = Gravity.RIGHT;
            return;
        } else if (centerX > twoThirdWidth) {
            // Too close to right, just scan to the left.
            mTmpDirections[0] = Gravity.LEFT;
            return;
        }

        final int oneThirdHeight = (2 * availableBounds.top + availableBounds.bottom) / 3;
        final int twoThirdHeight = (availableBounds.top + 2 * availableBounds.bottom) / 3;
        final int centerY = initialBounds.centerY();
        if (centerY < oneThirdHeight || centerY > twoThirdHeight) {
            // Too close to top or bottom boundary and we're in the middle horizontally, scan
            // horizontally in both directions.
            mTmpDirections[0] = Gravity.RIGHT;
            mTmpDirections[1] = Gravity.LEFT;
            return;
        }

        // We're in the center region both horizontally and vertically. Scan in both directions of
        // primary diagonal.
        mTmpDirections[0] = Gravity.BOTTOM | Gravity.RIGHT;
        mTmpDirections[1] = Gravity.TOP | Gravity.LEFT;
    }

    private boolean boundsConflict(@NonNull List<Rect> taskBoundsToCheck,
                                   @NonNull Rect candidateBounds) {
        for (Rect taskBounds : taskBoundsToCheck) {
            final boolean leftClose = Math.abs(taskBounds.left - candidateBounds.left)
                    < BOUNDS_CONFLICT_THRESHOLD;
            final boolean topClose = Math.abs(taskBounds.top - candidateBounds.top)
                    < BOUNDS_CONFLICT_THRESHOLD;
            final boolean rightClose = Math.abs(taskBounds.right - candidateBounds.right)
                    < BOUNDS_CONFLICT_THRESHOLD;
            final boolean bottomClose = Math.abs(taskBounds.bottom - candidateBounds.bottom)
                    < BOUNDS_CONFLICT_THRESHOLD;

            if ((leftClose && topClose) || (leftClose && bottomClose) || (rightClose && topClose)
                    || (rightClose && bottomClose)) {
                return true;
            }
        }

        return false;
    }

    private void shiftBounds(int direction, @NonNull Rect availableRect,
            @NonNull Rect inOutBounds) {
        final int horizontalOffset;
        switch (direction & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.LEFT:
                horizontalOffset = -Math.max(MINIMAL_STEP,
                        availableRect.width() / STEP_DENOMINATOR);
                break;
            case Gravity.RIGHT:
                horizontalOffset = Math.max(MINIMAL_STEP, availableRect.width() / STEP_DENOMINATOR);
                break;
            default:
                horizontalOffset = 0;
        }

        final int verticalOffset;
        switch (direction & Gravity.VERTICAL_GRAVITY_MASK) {
            case Gravity.TOP:
                verticalOffset = -Math.max(MINIMAL_STEP, availableRect.height() / STEP_DENOMINATOR);
                break;
            case Gravity.BOTTOM:
                verticalOffset = Math.max(MINIMAL_STEP, availableRect.height() / STEP_DENOMINATOR);
                break;
            default:
                verticalOffset = 0;
        }

        inOutBounds.offset(horizontalOffset, verticalOffset);
    }

    private void initLogBuilder(Task task, ActivityRecord activity) {
        if (DEBUG) {
            mLogBuilder = new StringBuilder("TaskLaunchParamsModifier:task=" + task
                    + " activity=" + activity);
        }
    }

    private void appendLog(String log) {
        if (DEBUG) mLogBuilder.append(" ").append(log);
    }

    private void outputLog() {
        if (DEBUG) Slog.d(TAG, mLogBuilder.toString());
    }

    private static int orientationFromBounds(Rect bounds) {
        return bounds.width() > bounds.height() ? SCREEN_ORIENTATION_LANDSCAPE
                : SCREEN_ORIENTATION_PORTRAIT;
    }

    private static boolean sizeMatches(Rect left, Rect right) {
        return (Math.abs(right.width() - left.width()) < EPSILON)
                && (Math.abs(right.height() - left.height()) < EPSILON);
    }
}
