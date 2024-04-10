/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shade

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.os.Handler
import android.view.LayoutInflater
import android.view.ViewStub
import androidx.constraintlayout.motion.widget.MotionLayout
import com.android.compose.animation.scene.SceneKey
import com.android.keyguard.logging.ScrimLogger
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.biometrics.AuthRippleView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.view.SceneWindowRootView
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.NotificationInsetsController
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.TapAgainView
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tuner.TunerService
import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Named
import javax.inject.Provider

/** Module for providing views related to the shade. */
@Module
abstract class ShadeViewProviderModule {

    @Binds
    @SysUISingleton
    // TODO(b/277762009): Only allow this view's binder to inject the view.
    abstract fun bindsNotificationScrollView(
        notificationStackScrollLayout: NotificationStackScrollLayout
    ): NotificationScrollView

    companion object {
        const val SHADE_HEADER = "large_screen_shade_header"

        @SuppressLint("InflateParams") // Root views don't have parents.
        @Provides
        @SysUISingleton
        fun providesWindowRootView(
            layoutInflater: LayoutInflater,
            viewModelProvider: Provider<SceneContainerViewModel>,
            containerConfigProvider: Provider<SceneContainerConfig>,
            scenesProvider: Provider<Set<@JvmSuppressWildcards Scene>>,
            layoutInsetController: NotificationInsetsController,
            sceneDataSourceDelegator: Provider<SceneDataSourceDelegator>,
        ): WindowRootView {
            return if (SceneContainerFlag.isEnabled) {
                checkNoSceneDuplicates(scenesProvider.get())
                val sceneWindowRootView =
                    layoutInflater.inflate(R.layout.scene_window_root, null) as SceneWindowRootView
                sceneWindowRootView.init(
                    viewModel = viewModelProvider.get(),
                    containerConfig = containerConfigProvider.get(),
                    sharedNotificationContainer =
                        sceneWindowRootView.requireViewById(R.id.shared_notification_container),
                    scenes = scenesProvider.get(),
                    layoutInsetController = layoutInsetController,
                    sceneDataSourceDelegator = sceneDataSourceDelegator.get(),
                )
                sceneWindowRootView
            } else {
                layoutInflater.inflate(R.layout.super_notification_shade, null)
            }
                as WindowRootView?
                ?: throw IllegalStateException("Window root view could not be properly inflated")
        }

        // TODO(b/277762009): Do something similar to
        //  {@link StatusBarWindowModule.InternalWindowView} so that only
        //  {@link NotificationShadeWindowViewController} can inject this view.
        @Provides
        @SysUISingleton
        fun providesNotificationShadeWindowView(
            root: WindowRootView,
        ): NotificationShadeWindowView {
            if (SceneContainerFlag.isEnabled) {
                return root.requireViewById(R.id.legacy_window_root)
            }
            return root as NotificationShadeWindowView?
                ?: throw IllegalStateException("root view not a NotificationShadeWindowView")
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationStackScrollLayout(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): NotificationStackScrollLayout {
            return notificationShadeWindowView.requireViewById(R.id.notification_stack_scroller)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationPanelView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): NotificationPanelView {
            return notificationShadeWindowView.requireViewById(R.id.notification_panel)
        }

        /**
         * Constructs a new, unattached [KeyguardBottomAreaView].
         *
         * Note that this is explicitly _not_ a singleton, as we want to be able to reinflate it
         */
        @Provides
        fun providesKeyguardBottomAreaView(
            npv: NotificationPanelView,
            layoutInflater: LayoutInflater,
        ): KeyguardBottomAreaView {
            return layoutInflater.inflate(R.layout.keyguard_bottom_area, npv, false)
                as KeyguardBottomAreaView
        }

        @Provides
        @SysUISingleton
        fun providesLightRevealScrim(
            notificationShadeWindowView: NotificationShadeWindowView,
            scrimLogger: ScrimLogger,
        ): LightRevealScrim {
            val scrim =
                notificationShadeWindowView.requireViewById<LightRevealScrim>(
                    R.id.light_reveal_scrim
                )
            scrim.scrimLogger = scrimLogger
            return scrim
        }

        @Provides
        @SysUISingleton
        fun providesKeyguardRootView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): KeyguardRootView {
            return notificationShadeWindowView.requireViewById(R.id.keyguard_root_view)
        }

        @Provides
        @SysUISingleton
        fun providesSharedNotificationContainer(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): SharedNotificationContainer {
            return notificationShadeWindowView.requireViewById(R.id.shared_notification_container)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesAuthRippleView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): AuthRippleView? {
            return notificationShadeWindowView.requireViewById(R.id.auth_ripple)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesTapAgainView(
            notificationPanelView: NotificationPanelView,
        ): TapAgainView {
            return notificationPanelView.requireViewById(R.id.shade_falsing_tap_again)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationsQuickSettingsContainer(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): NotificationsQuickSettingsContainer {
            return notificationShadeWindowView.requireViewById(R.id.notification_container_parent)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        @Named(SHADE_HEADER)
        fun providesShadeHeaderView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): MotionLayout {
            val stub = notificationShadeWindowView.requireViewById<ViewStub>(R.id.qs_header_stub)
            val layoutId = R.layout.combined_qs_header
            stub.layoutResource = layoutId
            return stub.inflate() as MotionLayout
        }

        @Provides
        @SysUISingleton
        fun providesCombinedShadeHeadersConstraintManager(): CombinedShadeHeadersConstraintManager {
            return CombinedShadeHeadersConstraintManagerImpl
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        @Named(SHADE_HEADER)
        fun providesBatteryMeterView(@Named(SHADE_HEADER) view: MotionLayout): BatteryMeterView {
            return view.requireViewById(R.id.batteryRemainingIcon)
        }

        @Provides
        @SysUISingleton
        @Named(SHADE_HEADER)
        fun providesBatteryMeterViewController(
            @Named(SHADE_HEADER) batteryMeterView: BatteryMeterView,
            userTracker: UserTracker,
            configurationController: ConfigurationController,
            tunerService: TunerService,
            @Main mainHandler: Handler,
            contentResolver: ContentResolver,
            featureFlags: FeatureFlags,
            batteryController: BatteryController,
        ): BatteryMeterViewController {
            return BatteryMeterViewController(
                batteryMeterView,
                StatusBarLocation.QS,
                userTracker,
                configurationController,
                tunerService,
                mainHandler,
                contentResolver,
                featureFlags,
                batteryController,
            )
        }

        @Provides
        @SysUISingleton
        @Named(SHADE_HEADER)
        fun providesOngoingPrivacyChip(
            @Named(SHADE_HEADER) header: MotionLayout,
        ): OngoingPrivacyChip {
            return header.requireViewById(R.id.privacy_chip)
        }

        @Provides
        @SysUISingleton
        @Named(SHADE_HEADER)
        fun providesStatusIconContainer(
            @Named(SHADE_HEADER) header: MotionLayout,
        ): StatusIconContainer {
            return header.requireViewById(R.id.statusIcons)
        }

        private fun checkNoSceneDuplicates(scenes: Set<Scene>) {
            val keys = mutableSetOf<SceneKey>()
            val duplicates = mutableSetOf<SceneKey>()
            scenes
                .map { it.key }
                .forEach { sceneKey ->
                    if (keys.contains(sceneKey)) {
                        duplicates.add(sceneKey)
                    } else {
                        keys.add(sceneKey)
                    }
                }

            check(duplicates.isEmpty()) { "Duplicate scenes detected: $duplicates" }
        }
    }
}
