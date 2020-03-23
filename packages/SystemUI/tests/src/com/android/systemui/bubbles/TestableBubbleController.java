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

package com.android.systemui.bubbles;

import android.content.Context;

import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.FloatingContentCoordinator;

/**
 * Testable BubbleController subclass that immediately synchronizes surfaces.
 */
public class TestableBubbleController extends BubbleController {

    // Let's assume surfaces can be synchronized immediately.
    TestableBubbleController(Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController,
            ShadeController shadeController,
            BubbleData data,
            ConfigurationController configurationController,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager lockscreenUserManager,
            NotificationGroupManager groupManager,
            NotificationEntryManager entryManager,
            NotifPipeline notifPipeline,
            FeatureFlags featureFlags,
            DumpManager dumpManager,
            FloatingContentCoordinator floatingContentCoordinator,
            SysUiState sysUiState) {
        super(context,
                notificationShadeWindowController, statusBarStateController, shadeController,
                data, Runnable::run, configurationController, interruptionStateProvider,
                zenModeController, lockscreenUserManager, groupManager, entryManager,
                notifPipeline, featureFlags, dumpManager, floatingContentCoordinator, sysUiState);
        setInflateSynchronously(true);
    }
}
