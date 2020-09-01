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

package com.android.systemui.statusbar.dagger;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.ActionClickLogger;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.MediaArtworkProcessor;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.DynamicChildBindController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.inflation.LowPriorityInflationHelper;
import com.android.systemui.statusbar.notification.stack.ForegroundServiceSectionController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.RemoteInputUriController;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Singleton;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * This module provides instances needed to construct {@link StatusBar}. These are moved to this
 * separate from {@link StatusBarModule} module so that components that wish to build their own
 * version of StatusBar can include just dependencies, without injecting StatusBar itself.
 */
@Module
public interface StatusBarDependenciesModule {
    /** */
    @Singleton
    @Provides
    static NotificationRemoteInputManager provideNotificationRemoteInputManager(
            Context context,
            NotificationLockscreenUserManager lockscreenUserManager,
            SmartReplyController smartReplyController,
            NotificationEntryManager notificationEntryManager,
            Lazy<StatusBar> statusBarLazy,
            StatusBarStateController statusBarStateController,
            Handler mainHandler,
            RemoteInputUriController remoteInputUriController,
            NotificationClickNotifier clickNotifier,
            ActionClickLogger actionClickLogger) {
        return new NotificationRemoteInputManager(
                context,
                lockscreenUserManager,
                smartReplyController,
                notificationEntryManager,
                statusBarLazy,
                statusBarStateController,
                mainHandler,
                remoteInputUriController,
                clickNotifier,
                actionClickLogger);
    }

    /** */
    @Singleton
    @Provides
    static NotificationMediaManager provideNotificationMediaManager(
            Context context,
            Lazy<StatusBar> statusBarLazy,
            Lazy<NotificationShadeWindowController> notificationShadeWindowController,
            NotificationEntryManager notificationEntryManager,
            MediaArtworkProcessor mediaArtworkProcessor,
            KeyguardBypassController keyguardBypassController,
            @Main DelayableExecutor mainExecutor,
            DeviceConfigProxy deviceConfigProxy,
            MediaDataManager mediaDataManager) {
        return new NotificationMediaManager(
                context,
                statusBarLazy,
                notificationShadeWindowController,
                notificationEntryManager,
                mediaArtworkProcessor,
                keyguardBypassController,
                mainExecutor,
                deviceConfigProxy,
                mediaDataManager);
    }

    /** */
    @Singleton
    @Provides
    static NotificationListener provideNotificationListener(
            Context context,
            NotificationManager notificationManager,
            @Main Handler mainHandler) {
        return new NotificationListener(
                context, notificationManager, mainHandler);
    }

    /** */
    @Singleton
    @Provides
    static SmartReplyController provideSmartReplyController(
            NotificationEntryManager entryManager,
            IStatusBarService statusBarService,
            NotificationClickNotifier clickNotifier) {
        return new SmartReplyController(entryManager, statusBarService, clickNotifier);
    }

    /** */
    @Singleton
    @Provides
    static NotificationViewHierarchyManager provideNotificationViewHierarchyManager(
            Context context,
            @Main Handler mainHandler,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationGroupManager groupManager,
            VisualStabilityManager visualStabilityManager,
            StatusBarStateController statusBarStateController,
            NotificationEntryManager notificationEntryManager,
            KeyguardBypassController bypassController,
            BubbleController bubbleController,
            DynamicPrivacyController privacyController,
            ForegroundServiceSectionController fgsSectionController,
            DynamicChildBindController dynamicChildBindController,
            LowPriorityInflationHelper lowPriorityInflationHelper) {
        return new NotificationViewHierarchyManager(
                context,
                mainHandler,
                notificationLockscreenUserManager,
                groupManager,
                visualStabilityManager,
                statusBarStateController,
                notificationEntryManager,
                bypassController,
                bubbleController,
                privacyController,
                fgsSectionController,
                dynamicChildBindController,
                lowPriorityInflationHelper);
    }

    /**
     * Provides our instance of CommandQueue which is considered optional.
     */
    @Provides
    @Singleton
    static CommandQueue provideCommandQueue(Context context, ProtoTracer protoTracer) {
        return new CommandQueue(context, protoTracer);
    }
}
