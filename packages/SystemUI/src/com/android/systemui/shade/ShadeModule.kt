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

import android.content.ContentResolver
import android.os.Handler
import android.view.LayoutInflater
import android.view.ViewStub
import androidx.constraintlayout.motion.widget.MotionLayout
import com.android.keyguard.LockIconView
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.biometrics.AuthRippleController
import com.android.systemui.biometrics.AuthRippleView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.TapAgainView
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tuner.TunerService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Named

/** Module for classes related to the notification shade. */
@Module
abstract class ShadeModule {

    @Binds
    @IntoMap
    @ClassKey(AuthRippleController::class)
    abstract fun bindAuthRippleController(controller: AuthRippleController): CoreStartable

    companion object {
        const val SHADE_HEADER = "large_screen_shade_header"

        @Provides
        @SysUISingleton
        // TODO(b/277762009): Do something similar to
        //  {@link StatusBarWindowModule.InternalWindowView} so that only
        //  {@link NotificationShadeWindowViewController} can inject this view.
        fun providesNotificationShadeWindowView(
            layoutInflater: LayoutInflater,
        ): NotificationShadeWindowView {
            return layoutInflater.inflate(R.layout.super_notification_shade, /* root= */ null)
                as NotificationShadeWindowView?
                ?: throw IllegalStateException(
                    "R.layout.super_notification_shade could not be properly inflated"
                )
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

        @Provides
        @SysUISingleton
        fun providesLightRevealScrim(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): LightRevealScrim {
            return notificationShadeWindowView.requireViewById(R.id.light_reveal_scrim)
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
        fun providesLockIconView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): LockIconView {
            return notificationShadeWindowView.requireViewById(R.id.lock_icon_view)
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
    }
}
