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

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.CurrentUserContextTracker;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.ForegroundServiceDismissalFeatureController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationEntryManagerLogger;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.init.NotificationsControllerImpl;
import com.android.systemui.statusbar.notification.init.NotificationsControllerStub;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLogger;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLoggerImpl;
import com.android.systemui.statusbar.notification.row.NotificationBlockingHelperManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.PriorityOnboardingDialogController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.util.leak.LeakDetector;

import java.util.concurrent.Executor;

import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for classes found within the com.android.systemui.statusbar.notification package.
 */
@Module
public interface NotificationsModule {
    /** Provides an instance of {@link NotificationEntryManager} */
    @Singleton
    @Provides
    static NotificationEntryManager provideNotificationEntryManager(
            NotificationEntryManagerLogger logger,
            NotificationGroupManager groupManager,
            NotificationRankingManager rankingManager,
            NotificationEntryManager.KeyguardEnvironment keyguardEnvironment,
            FeatureFlags featureFlags,
            Lazy<NotificationRowBinder> notificationRowBinderLazy,
            Lazy<NotificationRemoteInputManager> notificationRemoteInputManagerLazy,
            LeakDetector leakDetector,
            ForegroundServiceDismissalFeatureController fgsFeatureController) {
        return new NotificationEntryManager(
                logger,
                groupManager,
                rankingManager,
                keyguardEnvironment,
                featureFlags,
                notificationRowBinderLazy,
                notificationRemoteInputManagerLazy,
                leakDetector,
                fgsFeatureController);
    }

    /** Provides an instance of {@link NotificationGutsManager} */
    @Singleton
    @Provides
    static NotificationGutsManager provideNotificationGutsManager(
            Context context,
            VisualStabilityManager visualStabilityManager,
            Lazy<StatusBar> statusBarLazy,
            @Main Handler mainHandler,
            AccessibilityManager accessibilityManager,
            HighPriorityProvider highPriorityProvider,
            INotificationManager notificationManager,
            LauncherApps launcherApps,
            ShortcutManager shortcutManager,
            CurrentUserContextTracker contextTracker,
            Provider<PriorityOnboardingDialogController.Builder> builderProvider) {
        return new NotificationGutsManager(
                context,
                visualStabilityManager,
                statusBarLazy,
                mainHandler,
                accessibilityManager,
                highPriorityProvider,
                notificationManager,
                launcherApps,
                shortcutManager,
                contextTracker,
                builderProvider);
    }

    /** Provides an instance of {@link VisualStabilityManager} */
    @Singleton
    @Provides
    static VisualStabilityManager provideVisualStabilityManager(
            NotificationEntryManager notificationEntryManager, Handler handler) {
        return new VisualStabilityManager(notificationEntryManager, handler);
    }

    /** Provides an instance of {@link NotificationLogger} */
    @Singleton
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
    @Singleton
    @Provides
    static NotificationPanelLogger provideNotificationPanelLogger() {
        return new NotificationPanelLoggerImpl();
    }

    /** Provides an instance of {@link com.android.internal.logging.UiEventLogger} */
    @Singleton
    @Provides
    static UiEventLogger provideUiEventLogger() {
        return new UiEventLoggerImpl();
    }

    /** Provides an instance of {@link NotificationBlockingHelperManager} */
    @Singleton
    @Provides
    static NotificationBlockingHelperManager provideNotificationBlockingHelperManager(
            Context context,
            NotificationGutsManager notificationGutsManager,
            NotificationEntryManager notificationEntryManager,
            MetricsLogger metricsLogger) {
        return new NotificationBlockingHelperManager(
                context, notificationGutsManager, notificationEntryManager, metricsLogger);
    }

    /** Initializes the notification data pipeline (can be disabled via config). */
    @Singleton
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
    @Singleton
    static CommonNotifCollection provideCommonNotifCollection(
            FeatureFlags featureFlags,
            Lazy<NotifPipeline> pipeline,
            NotificationEntryManager entryManager) {
        return featureFlags.isNewNotifPipelineRenderingEnabled() ? pipeline.get() : entryManager;
    }

    /** */
    @Binds
    NotificationInterruptStateProvider bindNotificationInterruptStateProvider(
            NotificationInterruptStateProviderImpl notificationInterruptStateProviderImpl);
}
