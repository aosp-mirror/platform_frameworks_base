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

import android.app.IActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.service.dreams.IDreamManager;
import android.util.Log;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.AnimationFeatureFlags;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpHandler;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.carrier.QSCarrierGroupController;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.ActionClickLogger;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.MediaArtworkProcessor;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.commandline.CommandRegistry;
import com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureHandler;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.CentralSurfacesImpl;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarIconList;
import com.android.systemui.statusbar.phone.StatusBarRemoteInputCallback;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallFlags;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallLogger;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.RemoteInputUriController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;

import java.util.Optional;
import java.util.concurrent.Executor;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * This module provides instances needed to construct {@link CentralSurfacesImpl}. These are moved to
 * this separate from {@link CentralSurfacesModule} module so that components that wish to build
 * their own version of CentralSurfaces can include just dependencies, without injecting
 * CentralSurfaces itself.
 */
@Module
public interface CentralSurfacesDependenciesModule {
    /** */
    @SysUISingleton
    @Provides
    static NotificationRemoteInputManager provideNotificationRemoteInputManager(
            Context context,
            NotifPipelineFlags notifPipelineFlags,
            NotificationLockscreenUserManager lockscreenUserManager,
            SmartReplyController smartReplyController,
            NotificationVisibilityProvider visibilityProvider,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            StatusBarStateController statusBarStateController,
            RemoteInputUriController remoteInputUriController,
            NotificationClickNotifier clickNotifier,
            ActionClickLogger actionClickLogger,
            DumpManager dumpManager) {
        return new NotificationRemoteInputManager(
                context,
                notifPipelineFlags,
                lockscreenUserManager,
                smartReplyController,
                visibilityProvider,
                centralSurfacesOptionalLazy,
                statusBarStateController,
                remoteInputUriController,
                clickNotifier,
                actionClickLogger,
                dumpManager);
    }

    /** */
    @SysUISingleton
    @Provides
    static NotificationMediaManager provideNotificationMediaManager(
            Context context,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            Lazy<NotificationShadeWindowController> notificationShadeWindowController,
            NotificationVisibilityProvider visibilityProvider,
            MediaArtworkProcessor mediaArtworkProcessor,
            KeyguardBypassController keyguardBypassController,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            @Main DelayableExecutor mainExecutor,
            MediaDataManager mediaDataManager,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor,
            KeyguardStateController keyguardStateController,
            DumpManager dumpManager,
            DisplayManager displayManager) {
        return new NotificationMediaManager(
                context,
                centralSurfacesOptionalLazy,
                notificationShadeWindowController,
                visibilityProvider,
                mediaArtworkProcessor,
                keyguardBypassController,
                notifPipeline,
                notifCollection,
                mainExecutor,
                mediaDataManager,
                statusBarStateController,
                colorExtractor,
                keyguardStateController,
                dumpManager,
                displayManager);
    }

    /** */
    @SysUISingleton
    @Provides
    static SmartReplyController provideSmartReplyController(
            DumpManager dumpManager,
            NotificationVisibilityProvider visibilityProvider,
            IStatusBarService statusBarService,
            NotificationClickNotifier clickNotifier) {
        return new SmartReplyController(
                dumpManager,
                visibilityProvider,
                statusBarService,
                clickNotifier);
    }


    /** */
    @Binds
    NotificationRemoteInputManager.Callback provideNotificationRemoteInputManagerCallback(
            StatusBarRemoteInputCallback callbackImpl);

    /**
     * Provides our instance of CommandQueue which is considered optional.
     */
    @Provides
    @SysUISingleton
    static CommandQueue provideCommandQueue(
            Context context,
            DisplayTracker displayTracker,
            ProtoTracer protoTracer,
            CommandRegistry registry,
            DumpHandler dumpHandler
    ) {
        return new CommandQueue(context, displayTracker, protoTracer, registry, dumpHandler);
    }

    /**
     */
    @Binds
    ManagedProfileController provideManagedProfileController(
            ManagedProfileControllerImpl controllerImpl);

    /**
     */
    @Binds
    SysuiStatusBarStateController providesSysuiStatusBarStateController(
            StatusBarStateControllerImpl statusBarStateControllerImpl);

    /**
     */
    @Binds
    StatusBarIconController provideStatusBarIconController(
            StatusBarIconControllerImpl controllerImpl);

    /**
     */
    @Provides
    @SysUISingleton
    static StatusBarIconList provideStatusBarIconList(Context context) {
        return new StatusBarIconList(
                context.getResources().getStringArray(
                        com.android.internal.R.array.config_statusBarIcons));
    }

    /**
     */
    @Provides
    @SysUISingleton
    static OngoingCallController provideOngoingCallController(
            Context context,
            CommonNotifCollection notifCollection,
            SystemClock systemClock,
            ActivityStarter activityStarter,
            @Main Executor mainExecutor,
            IActivityManager iActivityManager,
            OngoingCallLogger logger,
            DumpManager dumpManager,
            StatusBarWindowController statusBarWindowController,
            SwipeStatusBarAwayGestureHandler swipeStatusBarAwayGestureHandler,
            StatusBarStateController statusBarStateController,
            OngoingCallFlags ongoingCallFlags) {

        boolean ongoingCallInImmersiveEnabled = ongoingCallFlags.isInImmersiveEnabled();
        Optional<StatusBarWindowController> windowController =
                ongoingCallInImmersiveEnabled
                        ? Optional.of(statusBarWindowController)
                        : Optional.empty();
        Optional<SwipeStatusBarAwayGestureHandler> gestureHandler =
                ongoingCallInImmersiveEnabled
                        ? Optional.of(swipeStatusBarAwayGestureHandler)
                        : Optional.empty();
        OngoingCallController ongoingCallController =
                new OngoingCallController(
                        context,
                        notifCollection,
                        ongoingCallFlags,
                        systemClock,
                        activityStarter,
                        mainExecutor,
                        iActivityManager,
                        logger,
                        dumpManager,
                        windowController,
                        gestureHandler,
                        statusBarStateController);
        ongoingCallController.init();
        return ongoingCallController;
    }

    /** */
    @Binds
    QSCarrierGroupController.SlotIndexResolver provideSlotIndexResolver(
            QSCarrierGroupController.SubscriptionManagerSlotIndexResolver impl);

    /**
     */
    @Provides
    @SysUISingleton
    static ActivityLaunchAnimator provideActivityLaunchAnimator() {
        return new ActivityLaunchAnimator();
    }

    /**
     */
    @Provides
    @SysUISingleton
    static DialogLaunchAnimator provideDialogLaunchAnimator(IDreamManager dreamManager,
            KeyguardStateController keyguardStateController,
            Lazy<AlternateBouncerInteractor> alternateBouncerInteractor,
            InteractionJankMonitor interactionJankMonitor,
            AnimationFeatureFlags animationFeatureFlags) {
        DialogLaunchAnimator.Callback callback = new DialogLaunchAnimator.Callback() {
            @Override
            public boolean isDreaming() {
                try {
                    return dreamManager.isDreaming();
                } catch (RemoteException e) {
                    Log.e("DialogLaunchAnimator.Callback", "dreamManager.isDreaming failed", e);
                    return false;
                }
            }

            @Override
            public boolean isUnlocked() {
                return keyguardStateController.isUnlocked();
            }

            @Override
            public boolean isShowingAlternateAuthOnUnlock() {
                return alternateBouncerInteractor.get().canShowAlternateBouncerForFingerprint();
            }
        };
        return new DialogLaunchAnimator(callback, interactionJankMonitor, animationFeatureFlags);
    }

    /**
     */
    @Provides
    @SysUISingleton
    static AnimationFeatureFlags provideAnimationFeatureFlags(FeatureFlags featureFlags) {
        return new AnimationFeatureFlags() {
            @Override
            public boolean isPredictiveBackQsDialogAnim() {
                return featureFlags.isEnabled(Flags.WM_ENABLE_PREDICTIVE_BACK_QS_DIALOG_ANIM);
            }
        };
    }
}
