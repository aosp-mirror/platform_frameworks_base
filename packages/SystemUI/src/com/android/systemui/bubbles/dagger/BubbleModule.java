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

import android.content.Context;

import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.bubbles.BubbleData;
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

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/** */
@Module
public interface BubbleModule {

    /**
     */
    @Singleton
    @Provides
    static BubbleController newBubbleController(
            Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController,
            ShadeController shadeController,
            BubbleData data,
            ConfigurationController configurationController,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManager groupManager,
            NotificationEntryManager entryManager,
            NotifPipeline notifPipeline,
            FeatureFlags featureFlags,
            DumpManager dumpManager,
            FloatingContentCoordinator floatingContentCoordinator,
            SysUiState sysUiState) {
        return new BubbleController(
                context,
                notificationShadeWindowController,
                statusBarStateController,
                shadeController,
                data,
                /* synchronizer */null,
                configurationController,
                interruptionStateProvider,
                zenModeController,
                notifUserManager,
                groupManager,
                entryManager,
                notifPipeline,
                featureFlags,
                dumpManager,
                floatingContentCoordinator,
                sysUiState);
    }
}
