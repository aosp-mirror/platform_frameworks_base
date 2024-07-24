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

package com.android.systemui.statusbar.notification.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.NoOpCoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import com.android.systemui.statusbar.notification.logging.NotificationLogger.ExpansionStateLogger
import com.android.systemui.statusbar.notification.logging.NotificationPanelLogger
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationRowStatsLogger
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLoggerImpl
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationLoggerViewModel
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.kotlin.getOrNull
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Provider

@Module
interface NotificationStatsLoggerModule {

    /** Binds an implementation to the [NotificationStatsLogger]. */
    @Binds fun bindsStatsLoggerImpl(impl: NotificationStatsLoggerImpl): NotificationStatsLogger

    companion object {

        /** Provides a [NotificationStatsLogger] if the refactor flag is on. */
        @Provides
        fun provideStatsLogger(
            provider: Provider<NotificationStatsLogger>
        ): Optional<NotificationStatsLogger> {
            return if (NotificationsLiveDataStoreRefactor.isEnabled) {
                Optional.of(provider.get())
            } else {
                Optional.empty()
            }
        }

        /** Provides a [NotificationLoggerViewModel] if the refactor flag is on. */
        @Provides
        fun provideViewModel(
            provider: Provider<NotificationLoggerViewModel>
        ): Optional<NotificationLoggerViewModel> {
            return if (NotificationsLiveDataStoreRefactor.isEnabled) {
                Optional.of(provider.get())
            } else {
                Optional.empty()
            }
        }

        /** Provides the legacy [NotificationLogger] if the refactor flag is off. */
        @Provides
        @SysUISingleton
        fun provideLegacyLoggerOptional(
            notificationListener: NotificationListener?,
            @UiBackground uiBgExecutor: Executor?,
            notifLiveDataStore: NotifLiveDataStore?,
            visibilityProvider: NotificationVisibilityProvider?,
            notifPipeline: NotifPipeline?,
            statusBarStateController: StatusBarStateController?,
            windowRootViewVisibilityInteractor: WindowRootViewVisibilityInteractor?,
            javaAdapter: JavaAdapter?,
            expansionStateLogger: ExpansionStateLogger?,
            notificationPanelLogger: NotificationPanelLogger?
        ): Optional<NotificationLogger> {
            return if (NotificationsLiveDataStoreRefactor.isEnabled) {
                Optional.empty()
            } else {
                Optional.of(
                    NotificationLogger(
                        notificationListener,
                        uiBgExecutor,
                        notifLiveDataStore,
                        visibilityProvider,
                        notifPipeline,
                        statusBarStateController,
                        windowRootViewVisibilityInteractor,
                        javaAdapter,
                        expansionStateLogger,
                        notificationPanelLogger
                    )
                )
            }
        }

        /**
         * Provides a the legacy [NotificationLogger] or the new [NotificationStatsLogger] to the
         * notification row.
         *
         * TODO(b/308623704) remove the [NotificationRowStatsLogger] interface, and provide a
         *   [NotificationStatsLogger] to the row directly.
         */
        @Provides
        fun provideRowStatsLogger(
            newProvider: Provider<NotificationStatsLogger>,
            legacyLoggerOptional: Optional<NotificationLogger>,
        ): NotificationRowStatsLogger {
            return legacyLoggerOptional.getOrNull() ?: newProvider.get()
        }

        /**
         * Binds the legacy [NotificationLogger] as a [CoreStartable] if the feature flag is off, or
         * binds a no-op [CoreStartable] otherwise.
         *
         * The old [NotificationLogger] is a [CoreStartable], because it's managing its own data
         * updates, but the new [NotificationStatsLogger] is not. Currently Dagger doesn't support
         * optionally binding entries with @[IntoMap], therefore we provide a no-op [CoreStartable]
         * here if the feature flag is on, but this can be removed once the flag is released.
         */
        @Provides
        @IntoMap
        @ClassKey(NotificationLogger::class)
        fun provideCoreStartable(
            legacyLoggerOptional: Optional<NotificationLogger>
        ): CoreStartable {
            return legacyLoggerOptional.getOrNull() ?: NoOpCoreStartable()
        }
    }
}
