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
import android.os.UserManager;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.properties.BubbleProperties;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

/**
 * Testable BubbleController subclass that immediately synchronizes surfaces.
 */
public class TestableBubbleController extends BubbleController {

    // Let's assume surfaces can be synchronized immediately.
    TestableBubbleController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            BubbleData data,
            FloatingContentCoordinator floatingContentCoordinator,
            BubbleDataRepository dataRepository,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            UserManager userManager,
            LauncherApps launcherApps,
            BubbleLogger bubbleLogger,
            TaskStackListenerImpl taskStackListener,
            ShellTaskOrganizer shellTaskOrganizer,
            BubblePositioner positioner,
            DisplayController displayController,
            Optional<OneHandedController> oneHandedOptional,
            DragAndDropController dragAndDropController,
            ShellExecutor shellMainExecutor,
            Handler shellMainHandler,
            TaskViewTransitions taskViewTransitions,
            Transitions transitions,
            SyncTransactionQueue syncQueue,
            IWindowManager wmService,
            BubbleProperties bubbleProperties) {
        super(context, shellInit, shellCommandHandler, shellController, data, Runnable::run,
                floatingContentCoordinator, dataRepository, statusBarService, windowManager,
                windowManagerShellWrapper, userManager, launcherApps, bubbleLogger,
                taskStackListener, shellTaskOrganizer, positioner, displayController,
                oneHandedOptional, dragAndDropController, shellMainExecutor, shellMainHandler,
                new SyncExecutor(), taskViewTransitions, transitions,
                syncQueue, wmService, bubbleProperties);
        setInflateSynchronously(true);
        onInit();
    }
}
