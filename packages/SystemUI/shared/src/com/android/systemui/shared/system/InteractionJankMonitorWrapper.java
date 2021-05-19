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

package com.android.systemui.shared.system;

import android.annotation.IntDef;
import android.view.View;

import com.android.internal.jank.InteractionJankMonitor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class InteractionJankMonitorWrapper {
    // Launcher journeys.
    public static final int CUJ_APP_LAUNCH_FROM_RECENTS =
            InteractionJankMonitor.CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS;
    public static final int CUJ_APP_LAUNCH_FROM_ICON =
            InteractionJankMonitor.CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON;
    public static final int CUJ_APP_CLOSE_TO_HOME =
            InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_HOME;
    public static final int CUJ_APP_CLOSE_TO_PIP =
            InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_PIP;
    public static final int CUJ_QUICK_SWITCH =
            InteractionJankMonitor.CUJ_LAUNCHER_QUICK_SWITCH;
    public static final int CUJ_OPEN_ALL_APPS =
            InteractionJankMonitor.CUJ_LAUNCHER_OPEN_ALL_APPS;
    public static final int CUJ_ALL_APPS_SCROLL =
            InteractionJankMonitor.CUJ_LAUNCHER_ALL_APPS_SCROLL;
    public static final int CUJ_APP_LAUNCH_FROM_WIDGET =
            InteractionJankMonitor.CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET;

    @IntDef({
            CUJ_APP_LAUNCH_FROM_RECENTS,
            CUJ_APP_LAUNCH_FROM_ICON,
            CUJ_APP_CLOSE_TO_HOME,
            CUJ_APP_CLOSE_TO_PIP,
            CUJ_QUICK_SWITCH,
            CUJ_APP_LAUNCH_FROM_WIDGET,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CujType {
    }

    public static boolean begin(View v, @CujType int cujType) {
        return InteractionJankMonitor.getInstance().begin(v, cujType);
    }

    public static boolean begin(View v, @CujType int cujType, long timeout) {
        return InteractionJankMonitor.getInstance().begin(v, cujType, timeout);
    }

    public static boolean end(@CujType int cujType) {
        return InteractionJankMonitor.getInstance().end(cujType);
    }

    public static boolean cancel(@CujType int cujType) {
        return InteractionJankMonitor.getInstance().cancel(cujType);
    }
}
