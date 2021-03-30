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

package com.android.systemui.wmshell;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.view.WindowManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;

/**
 * Testable BubbleController subclass that immediately synchronizes surfaces.
 */
public class TestableBubbleController extends BubbleController {

    // Let's assume surfaces can be synchronized immediately.
    TestableBubbleController(Context context,
            BubbleData data,
            FloatingContentCoordinator floatingContentCoordinator,
            BubbleDataRepository dataRepository,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            BubbleLogger bubbleLogger,
            ShellTaskOrganizer shellTaskOrganizer,
            BubblePositioner positioner,
            ShellExecutor shellMainExecutor,
            Handler shellMainHandler) {
        super(context, data, Runnable::run, floatingContentCoordinator, dataRepository,
                statusBarService, windowManager, windowManagerShellWrapper, launcherApps,
                bubbleLogger, shellTaskOrganizer, positioner, shellMainExecutor, shellMainHandler);
        setInflateSynchronously(true);
        initialize();
    }
}
