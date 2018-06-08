/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;

import com.android.server.am.LaunchParamsController.LaunchParams;
import com.android.server.am.LaunchParamsController.LaunchParamsModifier;

/**
 * An implementation of {@link LaunchParamsModifier}, which applies the launch bounds specified
 * inside {@link ActivityOptions#getLaunchBounds()}.
 */
public class ActivityLaunchParamsModifier implements LaunchParamsModifier {
    private final ActivityStackSupervisor mSupervisor;

    ActivityLaunchParamsModifier(ActivityStackSupervisor activityStackSupervisor) {
        mSupervisor = activityStackSupervisor;
    }

    @Override
    public int onCalculate(TaskRecord task, ActivityInfo.WindowLayout layout,
            ActivityRecord activity, ActivityRecord source, ActivityOptions options,
            LaunchParams currentParams, LaunchParams outParams) {
        // We only care about figuring out bounds for activities.
        if (activity == null) {
            return RESULT_SKIP;
        }

        // Activity must be resizeable in the specified task.
        if (!(mSupervisor.canUseActivityOptionsLaunchBounds(options)
                && (activity.isResizeable() || (task != null && task.isResizeable())))) {
            return RESULT_SKIP;
        }

        final Rect bounds = options.getLaunchBounds();

        // Bounds weren't valid.
        if (bounds == null || bounds.isEmpty()) {
            return RESULT_SKIP;
        }

        outParams.mBounds.set(bounds);

        // When this is the most explicit position specification so we should not allow further
        // modification of the position.
        return RESULT_DONE;
    }
}
