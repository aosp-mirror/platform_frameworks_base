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

package com.android.systemui.keyguard.dagger;

import android.app.IActivityTaskManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.os.PowerManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.keyguard.dagger.KeyguardDisplayModule;
import com.android.keyguard.dagger.KeyguardQsUserSwitchComponent;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.keyguard.dagger.KeyguardUserSwitcherComponent;
import com.android.keyguard.mediator.ScreenOnCoordinator;
import com.android.systemui.CoreStartable;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.SystemPropertiesHelper;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.WindowManagerLockscreenVisibilityManager;
import com.android.systemui.keyguard.data.quickaffordance.KeyguardDataQuickAffordanceModule;
import com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthModule;
import com.android.systemui.keyguard.data.repository.KeyguardRepositoryModule;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.StartKeyguardTransitionModule;
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger;
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLoggerImpl;
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransitionModule;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.wallpapers.data.repository.WallpaperRepository;
import com.android.wm.shell.keyguard.KeyguardTransitions;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

import java.util.concurrent.Executor;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.ExperimentalCoroutinesApi;

/**
 * Dagger Module providing keyguard.
 */
@ExperimentalCoroutinesApi
@Module(subcomponents = {
        KeyguardQsUserSwitchComponent.class,
        KeyguardStatusBarViewComponent.class,
        KeyguardStatusViewComponent.class,
        KeyguardUserSwitcherComponent.class},
        includes = {
            DeviceEntryIconTransitionModule.class,
            FalsingModule.class,
            KeyguardDataQuickAffordanceModule.class,
            KeyguardRepositoryModule.class,
            DeviceEntryFaceAuthModule.class,
            KeyguardDisplayModule.class,
            StartKeyguardTransitionModule.class,
            ResourceTrimmerModule.class,
        })
public interface KeyguardModule {
    /**
     * Provides our instance of KeyguardViewMediator which is considered optional.
     */
    @Provides
    @SysUISingleton
    static KeyguardViewMediator newKeyguardViewMediator(
            Context context,
            UiEventLogger uiEventLogger,
            SessionTracker sessionTracker,
            UserTracker userTracker,
            FalsingCollector falsingCollector,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            Lazy<KeyguardViewController> statusBarKeyguardViewManagerLazy,
            DismissCallbackRegistry dismissCallbackRegistry,
            KeyguardUpdateMonitor updateMonitor,
            DumpManager dumpManager,
            PowerManager powerManager,
            TrustManager trustManager,
            UserSwitcherController userSwitcherController,
            @UiBackground Executor uiBgExecutor,
            DeviceConfigProxy deviceConfig,
            NavigationModeController navigationModeController,
            KeyguardDisplayManager keyguardDisplayManager,
            DozeParameters dozeParameters,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController,
            Lazy<KeyguardUnlockAnimationController> keyguardUnlockAnimationController,
            ScreenOffAnimationController screenOffAnimationController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthController,
            ScreenOnCoordinator screenOnCoordinator,
            KeyguardTransitions keyguardTransitions,
            InteractionJankMonitor interactionJankMonitor,
            DreamOverlayStateController dreamOverlayStateController,
            JavaAdapter javaAdapter,
            WallpaperRepository wallpaperRepository,
            Lazy<ShadeController> shadeController,
            Lazy<NotificationShadeWindowController> notificationShadeWindowController,
            Lazy<ActivityLaunchAnimator> activityLaunchAnimator,
            Lazy<ScrimController> scrimControllerLazy,
            IActivityTaskManager activityTaskManagerService,
            FeatureFlags featureFlags,
            SecureSettings secureSettings,
            SystemSettings systemSettings,
            SystemClock systemClock,
            @Main CoroutineDispatcher mainDispatcher,
            Lazy<DreamingToLockscreenTransitionViewModel> dreamingToLockscreenTransitionViewModel,
            SystemPropertiesHelper systemPropertiesHelper,
            Lazy<WindowManagerLockscreenVisibilityManager> wmLockscreenVisibilityManager,
            SelectedUserInteractor selectedUserInteractor,
            KeyguardInteractor keyguardInteractor) {
        return new KeyguardViewMediator(
                context,
                uiEventLogger,
                sessionTracker,
                userTracker,
                falsingCollector,
                lockPatternUtils,
                broadcastDispatcher,
                statusBarKeyguardViewManagerLazy,
                dismissCallbackRegistry,
                updateMonitor,
                dumpManager,
                uiBgExecutor,
                powerManager,
                trustManager,
                userSwitcherController,
                deviceConfig,
                navigationModeController,
                keyguardDisplayManager,
                dozeParameters,
                statusBarStateController,
                keyguardStateController,
                keyguardUnlockAnimationController,
                screenOffAnimationController,
                notificationShadeDepthController,
                screenOnCoordinator,
                keyguardTransitions,
                interactionJankMonitor,
                dreamOverlayStateController,
                javaAdapter,
                wallpaperRepository,
                shadeController,
                notificationShadeWindowController,
                activityLaunchAnimator,
                scrimControllerLazy,
                activityTaskManagerService,
                featureFlags,
                secureSettings,
                systemSettings,
                systemClock,
                mainDispatcher,
                dreamingToLockscreenTransitionViewModel,
                systemPropertiesHelper,
                wmLockscreenVisibilityManager,
                selectedUserInteractor,
                keyguardInteractor);
    }

    /** */
    @Provides
    static ViewMediatorCallback providesViewMediatorCallback(KeyguardViewMediator viewMediator) {
        return viewMediator.getViewMediatorCallback();
    }

    /** */
    @Provides
    static KeyguardQuickAffordancesMetricsLogger providesKeyguardQuickAffordancesMetricsLogger() {
        return new KeyguardQuickAffordancesMetricsLoggerImpl();
    }

    /** Binds {@link KeyguardUpdateMonitor} as a {@link CoreStartable}. */
    @Binds
    @IntoMap
    @ClassKey(KeyguardUpdateMonitor.class)
    CoreStartable bindsKeyguardUpdateMonitor(KeyguardUpdateMonitor keyguardUpdateMonitor);
}
