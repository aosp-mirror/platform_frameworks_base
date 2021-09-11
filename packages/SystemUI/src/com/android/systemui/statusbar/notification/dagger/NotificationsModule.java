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

package com.android.systemui.statusbar.notification.dagger;

import android.app.INotificationManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutManager;
import android.os.Handler;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.ForegroundServiceDismissalFeatureController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationEntryManagerLogger;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.coordinator.VisualStabilityCoordinator;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.inflation.OnUserInteractionCallbackImpl;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.legacy.OnUserInteractionCallbackImplLegacy;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManagerImpl;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManagerImpl;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.init.NotificationsControllerImpl;
import com.android.systemui.statusbar.notification.init.NotificationsControllerStub;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLogger;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLoggerImpl;
import com.android.systemui.statusbar.notification.row.ChannelEditorDialogController;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager;
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.wmshell.BubblesManager;

import java.util.Optional;
import java.util.concurrent.Executor;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for classes found within the com.android.systemui.statusbar.notification package.
 */
@Module(includes = { NotificationSectionHeadersModule.class })
public interface NotificationsModule {
    @Binds
    StackScrollAlgorithm.SectionProvider bindSectionProvider(
            NotificationSectionsManager impl);

    @Binds
    StackScrollAlgorithm.BypassController bindBypassController(
            KeyguardBypassController impl);

    /** Provides an instance of {@link NotificationEntryManager} */
    @SysUISingleton
    @Provides
    static NotificationEntryManager provideNotificationEntryManager(
            NotificationEntryManagerLogger logger,
            NotificationGroupManagerLegacy groupManager,
            FeatureFlags featureFlags,
            Lazy<NotificationRowBinder> notificationRowBinderLazy,
            Lazy<NotificationRemoteInputManager> notificationRemoteInputManagerLazy,
            LeakDetector leakDetector,
            ForegroundServiceDismissalFeatureController fgsFeatureController,
            IStatusBarService statusBarService,
            DumpManager dumpManager) {
        return new NotificationEntryManager(
                logger,
                groupManager,
                featureFlags,
                notificationRowBinderLazy,
                notificationRemoteInputManagerLazy,
                leakDetector,
                fgsFeatureController,
                statusBarService,
                dumpManager);
    }

    /** Provides an instance of {@link NotificationGutsManager} */
    @SysUISingleton
    @Provides
    static NotificationGutsManager provideNotificationGutsManager(
            Context context,
            Lazy<Optional<StatusBar>> statusBarOptionalLazy,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            AccessibilityManager accessibilityManager,
            HighPriorityProvider highPriorityProvider,
            INotificationManager notificationManager,
            NotificationEntryManager notificationEntryManager,
            PeopleSpaceWidgetManager peopleSpaceWidgetManager,
            LauncherApps launcherApps,
            ShortcutManager shortcutManager,
            ChannelEditorDialogController channelEditorDialogController,
            UserContextProvider contextTracker,
            AssistantFeedbackController assistantFeedbackController,
            Optional<BubblesManager> bubblesManagerOptional,
            UiEventLogger uiEventLogger,
            OnUserInteractionCallback onUserInteractionCallback,
            ShadeController shadeController,
            DumpManager dumpManager) {
        return new NotificationGutsManager(
                context,
                statusBarOptionalLazy,
                mainHandler,
                bgHandler,
                accessibilityManager,
                highPriorityProvider,
                notificationManager,
                notificationEntryManager,
                peopleSpaceWidgetManager,
                launcherApps,
                shortcutManager,
                channelEditorDialogController,
                contextTracker,
                assistantFeedbackController,
                bubblesManagerOptional,
                uiEventLogger,
                onUserInteractionCallback,
                shadeController,
                dumpManager);
    }

    /** Provides an instance of {@link VisualStabilityManager} */
    @SysUISingleton
    @Provides
    static VisualStabilityManager provideVisualStabilityManager(
            NotificationEntryManager notificationEntryManager,
            Handler handler,
            StatusBarStateController statusBarStateController,
            WakefulnessLifecycle wakefulnessLifecycle,
            DumpManager dumpManager) {
        return new VisualStabilityManager(
                notificationEntryManager,
                handler,
                statusBarStateController,
                wakefulnessLifecycle,
                dumpManager);
    }

    /** Provides an instance of {@link NotificationLogger} */
    @SysUISingleton
    @Provides
    static NotificationLogger provideNotificationLogger(
            NotificationListener notificationListener,
            @UiBackground Executor uiBgExecutor,
            NotificationEntryManager entryManager,
            StatusBarStateController statusBarStateController,
            NotificationLogger.ExpansionStateLogger expansionStateLogger,
            NotificationPanelLogger notificationPanelLogger) {
        return new NotificationLogger(
                notificationListener,
                uiBgExecutor,
                entryManager,
                statusBarStateController,
                expansionStateLogger,
                notificationPanelLogger);
    }

    /** Provides an instance of {@link NotificationPanelLogger} */
    @SysUISingleton
    @Provides
    static NotificationPanelLogger provideNotificationPanelLogger() {
        return new NotificationPanelLoggerImpl();
    }

    /** Provides an instance of {@link GroupMembershipManager} */
    @SysUISingleton
    @Provides
    static GroupMembershipManager provideGroupMembershipManager(
            FeatureFlags featureFlags,
            Lazy<NotificationGroupManagerLegacy> groupManagerLegacy) {
        return featureFlags.isNewNotifPipelineRenderingEnabled()
                ? new GroupMembershipManagerImpl()
                : groupManagerLegacy.get();
    }

    /** Provides an instance of {@link GroupExpansionManager} */
    @SysUISingleton
    @Provides
    static GroupExpansionManager provideGroupExpansionManager(
            FeatureFlags featureFlags,
            Lazy<GroupMembershipManager> groupMembershipManager,
            Lazy<NotificationGroupManagerLegacy> groupManagerLegacy) {
        return featureFlags.isNewNotifPipelineRenderingEnabled()
                ? new GroupExpansionManagerImpl(groupMembershipManager.get())
                : groupManagerLegacy.get();
    }

    /** Initializes the notification data pipeline (can be disabled via config). */
    @SysUISingleton
    @Provides
    static NotificationsController provideNotificationsController(
            Context context,
            Lazy<NotificationsControllerImpl> realController,
            Lazy<NotificationsControllerStub> stubController) {
        if (context.getResources().getBoolean(R.bool.config_renderNotifications)) {
            return realController.get();
        } else {
            return stubController.get();
        }
    }

    /**
     * Provide the active notification collection managing the notifications to render.
     */
    @Provides
    @SysUISingleton
    static CommonNotifCollection provideCommonNotifCollection(
            FeatureFlags featureFlags,
            Lazy<NotifPipeline> pipeline,
            NotificationEntryManager entryManager) {
        return featureFlags.isNewNotifPipelineRenderingEnabled() ? pipeline.get() : entryManager;
    }

    /**
     * Provide a dismissal callback that's triggered when a user manually dismissed a notification
     * from the notification shade or it gets auto-cancelled by click.
     */
    @Provides
    @SysUISingleton
    static OnUserInteractionCallback provideOnUserInteractionCallback(
            FeatureFlags featureFlags,
            HeadsUpManager headsUpManager,
            StatusBarStateController statusBarStateController,
            Lazy<NotifPipeline> pipeline,
            Lazy<NotifCollection> notifCollection,
            Lazy<VisualStabilityCoordinator> visualStabilityCoordinator,
            NotificationEntryManager entryManager,
            VisualStabilityManager visualStabilityManager,
            Lazy<GroupMembershipManager> groupMembershipManagerLazy) {
        return featureFlags.isNewNotifPipelineRenderingEnabled()
                ? new OnUserInteractionCallbackImpl(
                        pipeline.get(),
                        notifCollection.get(),
                        headsUpManager,
                        statusBarStateController,
                        visualStabilityCoordinator.get(),
                        groupMembershipManagerLazy.get())
                : new OnUserInteractionCallbackImplLegacy(
                        entryManager,
                        headsUpManager,
                        statusBarStateController,
                        visualStabilityManager,
                        groupMembershipManagerLazy.get());
    }

    /** */
    @Binds
    NotificationInterruptStateProvider bindNotificationInterruptStateProvider(
            NotificationInterruptStateProviderImpl notificationInterruptStateProviderImpl);

    /** */
    @Binds
    NotifInflater bindNotifInflater(NotifInflaterImpl notifInflaterImpl);
}
