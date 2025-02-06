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

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.service.dreams.IDreamManager;
import android.util.Log;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.CoreStartable;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpHandler;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.ShadeDisplayAware;
import com.android.systemui.shade.ShadeSurface;
import com.android.systemui.shade.ShadeSurfaceImpl;
import com.android.systemui.shade.carrier.ShadeCarrierGroupController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.commandline.CommandRegistry;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.phone.CentralSurfacesImpl;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarRemoteInputCallback;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl;
import com.android.systemui.statusbar.phone.ui.StatusBarIconList;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.shared.ShellTransitions;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

import java.util.concurrent.Executor;

import javax.inject.Provider;

/**
 * This module provides instances needed to construct {@link CentralSurfacesImpl}. These are moved to
 * this separate from {@link CentralSurfacesModule} module so that components that wish to build
 * their own version of CentralSurfaces can include just dependencies, without injecting
 * CentralSurfaces itself.
 */
@Module
public interface CentralSurfacesDependenciesModule {

    /** */
    @Binds
    @IntoMap
    @ClassKey(NotificationRemoteInputManager.class)
    CoreStartable bindsStartNotificationRemoteInputManager(NotificationRemoteInputManager nrim);

    /** */
    @SysUISingleton
    @Provides
    static NotificationMediaManager provideNotificationMediaManager(
            @ShadeDisplayAware Context context,
            NotificationVisibilityProvider visibilityProvider,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            MediaDataManager mediaDataManager,
            DumpManager dumpManager,
            @Background Executor backgroundExecutor,
            @Main Handler handler) {
        return new NotificationMediaManager(
                context,
                visibilityProvider,
                notifPipeline,
                notifCollection,
                mediaDataManager,
                dumpManager,
                backgroundExecutor,
                handler);
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
            CommandRegistry registry,
            DumpHandler dumpHandler,
            Lazy<PowerInteractor> powerInteractor
    ) {
        return new CommandQueue(context, displayTracker, registry, dumpHandler, powerInteractor);
    }

    /** */
    @Binds
    ManagedProfileController provideManagedProfileController(
            ManagedProfileControllerImpl controllerImpl);

    /** */
    @Binds
    SysuiStatusBarStateController providesSysuiStatusBarStateController(
            StatusBarStateControllerImpl statusBarStateControllerImpl);

    /** */
    @Binds
    @IntoMap
    @ClassKey(SysuiStatusBarStateController.class)
    CoreStartable bindsStartStatusBarStateController(StatusBarStateControllerImpl sbsc);

    /** */
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
     * {@link NotificationPanelViewController} implements two interfaces:
     *  - {@link com.android.systemui.shade.ShadeViewController}, which can be used by any class
     *    needing access to the shade.
     *  - {@link ShadeSurface}, which should *only* be used by {@link CentralSurfacesImpl}.
     *
     * Since {@link ShadeSurface} should only be accessible by {@link CentralSurfacesImpl}, it's
     * *only* bound in this CentralSurfaces dependencies module.
     * The {@link com.android.systemui.shade.ShadeViewController} interface is bound in
     * {@link com.android.systemui.shade.ShadeModule} so others can access it.
     */
    @Provides
    @SysUISingleton
    static ShadeSurface provideShadeSurface(
            Provider<ShadeSurfaceImpl> sceneContainerOn,
            Provider<NotificationPanelViewController> sceneContainerOff) {
        if (SceneContainerFlag.isEnabled()) {
            return sceneContainerOn.get();
        } else {
            return sceneContainerOff.get();
        }

    }


    /** */
    @Binds
    ShadeCarrierGroupController.SlotIndexResolver provideSlotIndexResolver(
            ShadeCarrierGroupController.SubscriptionManagerSlotIndexResolver impl);

    /** */
    @Provides
    @SysUISingleton
    static ActivityTransitionAnimator provideActivityTransitionAnimator(
            @Main Executor mainExecutor, ShellTransitions shellTransitions) {
        return new ActivityTransitionAnimator(mainExecutor, shellTransitions);
    }

    /** */
    @Provides
    @SysUISingleton
    static DialogTransitionAnimator provideDialogTransitionAnimator(@Main Executor mainExecutor,
            IDreamManager dreamManager,
            KeyguardStateController keyguardStateController,
            Lazy<AlternateBouncerInteractor> alternateBouncerInteractor,
            InteractionJankMonitor interactionJankMonitor) {
        DialogTransitionAnimator.Callback callback = new DialogTransitionAnimator.Callback() {
            @Override
            public boolean isDreaming() {
                try {
                    return dreamManager.isDreaming();
                } catch (RemoteException e) {
                    Log.e("DialogTransitionAnimator.Callback", "dreamManager.isDreaming failed", e);
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
        return new DialogTransitionAnimator(mainExecutor, callback, interactionJankMonitor);
    }
}
