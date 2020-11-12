/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.SystemProperties;

import com.android.server.policy.WindowManagerPolicy.StartingSurface;

/**
 * Managing to create and release a starting window surface.
 */
public class StartingSurfaceController {

    /** Set to {@code true} to enable shell starting surface drawer. */
    private static final boolean DEBUG_ENABLE_SHELL_DRAWER =
            SystemProperties.getBoolean("persist.debug.shell_starting_surface", false);

    private final WindowManagerService mService;

    public StartingSurfaceController(WindowManagerService wm) {
        mService = wm;
    }

    StartingSurface createSplashScreenStartingSurface(ActivityRecord activity, String packageName,
            int theme, CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes,
            int icon, int logo, int windowFlags, Configuration overrideConfig, int displayId) {
        if (!DEBUG_ENABLE_SHELL_DRAWER) {
            return mService.mPolicy.addSplashScreen(activity.token, packageName, theme,
                    compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                    overrideConfig, displayId);
        }

        final Task task = activity.getTask();
        if (task != null && mService.mAtmService.mTaskOrganizerController.addStartingWindow(task,
                activity.token)) {
            return new SplashScreenContainerSurface(task);
        }
        return null;
    }

    private final class SplashScreenContainerSurface implements StartingSurface {
        private final Task mTask;

        SplashScreenContainerSurface(Task task) {
            mTask = task;
        }

        @Override
        public void remove() {
            mService.mAtmService.mTaskOrganizerController.removeStartingWindow(mTask);
        }
    }
}
