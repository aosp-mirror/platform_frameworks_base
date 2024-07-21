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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.DesktopModeHelper.canEnterDesktopMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.Slog;

import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;
/**
 * The class that defines default launch params for tasks in desktop mode
 */
class DesktopModeLaunchParamsModifier implements LaunchParamsModifier {

    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "DesktopModeLaunchParamsModifier" : TAG_ATM;
    private static final boolean DEBUG = false;

    private StringBuilder mLogBuilder;

    @NonNull private final Context mContext;

    DesktopModeLaunchParamsModifier(@NonNull Context context) {
        mContext = context;
    }

    @Override
    public int onCalculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            @NonNull LaunchParamsController.LaunchParams currentParams,
            @NonNull LaunchParamsController.LaunchParams outParams) {

        initLogBuilder(task, activity);
        int result = calculate(task, layout, activity, source, options, request, phase,
                currentParams, outParams);
        outputLog();
        return result;
    }

    private int calculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            @NonNull LaunchParamsController.LaunchParams currentParams,
            @NonNull LaunchParamsController.LaunchParams outParams) {

        if (!canEnterDesktopMode(mContext)) {
            appendLog("desktop mode is not enabled, skipping");
            return RESULT_SKIP;
        }

        if (task == null || !task.isAttached()) {
            appendLog("task null, skipping");
            return RESULT_SKIP;
        }
        if (!task.isActivityTypeStandardOrUndefined()) {
            appendLog("not standard or undefined activity type, skipping");
            return RESULT_SKIP;
        }
        if (phase < PHASE_WINDOWING_MODE) {
            appendLog("not in windowing mode or bounds phase, skipping");
            return RESULT_SKIP;
        }

        // Copy over any values
        outParams.set(currentParams);

        // In Proto2, trampoline task launches of an existing background task can result in the
        // previous windowing mode to be restored even if the desktop mode state has changed.
        // Let task launches inherit the windowing mode from the source task if available, which
        // should have the desired windowing mode set by WM Shell. See b/286929122.
        if (source != null && source.getTask() != null) {
            final Task sourceTask = source.getTask();
            outParams.mWindowingMode = sourceTask.getWindowingMode();
            appendLog("inherit-from-source=" + outParams.mWindowingMode);
        }

        if (phase == PHASE_WINDOWING_MODE) {
            return RESULT_CONTINUE;
        }

        if (!currentParams.mBounds.isEmpty()) {
            appendLog("currentParams has bounds set, not overriding");
            return RESULT_SKIP;
        }

        DesktopModeBoundsCalculator.updateInitialBounds(task, layout, activity, options,
                outParams.mBounds, this::appendLog);
        appendLog("final desktop mode task bounds set to %s", outParams.mBounds);
        return RESULT_CONTINUE;
    }

    private void initLogBuilder(Task task, ActivityRecord activity) {
        if (DEBUG) {
            mLogBuilder = new StringBuilder(
                    "DesktopModeLaunchParamsModifier: task=" + task + " activity=" + activity);
        }
    }

    private void appendLog(String format, Object... args) {
        if (DEBUG) mLogBuilder.append(" ").append(String.format(format, args));
    }

    private void outputLog() {
        if (DEBUG) Slog.d(TAG, mLogBuilder.toString());
    }
}
