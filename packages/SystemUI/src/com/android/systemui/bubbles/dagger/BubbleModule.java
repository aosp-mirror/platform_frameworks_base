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

package com.android.systemui.bubbles.dagger;

import android.app.INotificationManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.bubbles.Bubbles;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.FloatingContentCoordinator;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/** */
@Module
public interface BubbleModule {

    /**
     */
    @SysUISingleton
    @Provides
    static Bubbles newBubbleController(Context context,
            FloatingContentCoordinator floatingContentCoordinator,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            UiEventLogger uiEventLogger,
            @Main Handler mainHandler,
            ShellTaskOrganizer organizer) {
        return BubbleController.create(context, null /* synchronizer */, floatingContentCoordinator,
                statusBarService, windowManager, windowManagerShellWrapper, launcherApps,
                uiEventLogger, mainHandler, organizer);
    }

    /** Provides Optional of BubbleManager */
    @SysUISingleton
    @Provides
    static Optional<BubblesManager> provideBubblesManager(Context context,
            Optional<Bubbles> bubblesOptional,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController, ShadeController shadeController,
            ConfigurationController configurationController,
            @Nullable IStatusBarService statusBarService, INotificationManager notificationManager,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController, NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManagerLegacy groupManager, NotificationEntryManager entryManager,
            NotifPipeline notifPipeline, SysUiState sysUiState, FeatureFlags featureFlags,
            DumpManager dumpManager) {
        return Optional.ofNullable(BubblesManager.create(context, bubblesOptional,
                notificationShadeWindowController, statusBarStateController, shadeController,
                configurationController, statusBarService, notificationManager,
                interruptionStateProvider, zenModeController, notifUserManager,
                groupManager, entryManager, notifPipeline, sysUiState, featureFlags, dumpManager));
    }
}
