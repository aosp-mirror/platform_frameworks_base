/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.logging.dagger

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.dagger.NotifInflationLog
import com.android.systemui.log.dagger.NotifInteractionLog
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.log.dagger.NotificationInterruptLog
import com.android.systemui.log.dagger.NotificationLockscreenLog
import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.log.dagger.NotificationRemoteInputLog
import com.android.systemui.log.dagger.NotificationRenderLog
import com.android.systemui.log.dagger.NotificationSectionLog
import com.android.systemui.log.dagger.SensitiveNotificationProtectionLog
import com.android.systemui.log.dagger.UnseenNotificationLog
import com.android.systemui.log.dagger.VisualStabilityLog
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationLog
import com.android.systemui.util.Compile
import dagger.Module
import dagger.Provides

@Module
object NotificationsLogModule {
    /** Provides a logging buffer for logs related to heads up presentation of notifications. */
    @Provides
    @SysUISingleton
    @NotificationHeadsUpLog
    fun provideNotificationHeadsUpLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifHeadsUpLog", 1000)
    }

    /** Provides a logging buffer for logs related to inflation of notifications. */
    @Provides
    @SysUISingleton
    @NotifInflationLog
    fun provideNotifInflationLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifInflationLog", 250)
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotifInteractionLog
    fun provideNotifInteractionLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifInteractionLog", 50)
    }

    /** Provides a logging buffer for notification interruption calculations. */
    @Provides
    @SysUISingleton
    @NotificationInterruptLog
    fun provideNotificationInterruptLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifInterruptLog", 100)
    }

    /** Provides a logging buffer for all logs related to notifications on the lockscreen. */
    @Provides
    @SysUISingleton
    @NotificationLockscreenLog
    fun provideNotificationLockScreenLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifLockscreenLog", 50, false /* systrace */)
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotificationLog
    fun provideNotificationsLogBuffer(
        factory: LogBufferFactory,
        notifPipelineFlags: NotifPipelineFlags,
    ): LogBuffer {
        var maxSize = 1000
        if (Compile.IS_DEBUG && notifPipelineFlags.isDevLoggingEnabled()) {
            maxSize *= 10
        }
        return factory.create("NotifLog", maxSize, Compile.IS_DEBUG /* systrace */)
    }

    /** Provides a logging buffer for all logs related to remote input controller. */
    @Provides
    @SysUISingleton
    @NotificationRemoteInputLog
    fun provideNotificationRemoteInputLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifRemoteInputLog", 50, /* maxSize */ false /* systrace */)
    }

    /** Provides a logging buffer for notification rendering events. */
    @Provides
    @SysUISingleton
    @NotificationRenderLog
    fun provideNotificationRenderLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifRenderLog", 100)
    }

    /** Provides a logging buffer for all logs related to managing notification sections. */
    @Provides
    @SysUISingleton
    @NotificationSectionLog
    fun provideNotificationSectionLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("NotifSectionLog", 1000, /* maxSize */ false /* systrace */)
    }

    /** Provides a [LogBuffer] for use by promoted notifications. */
    @Provides
    @SysUISingleton
    @PromotedNotificationLog
    fun providesPromotedNotificationLog(factory: LogBufferFactory): LogBuffer {
        return factory.create("PromotedNotifLog", 50)
    }

    /**  */
    @Provides
    @SysUISingleton
    @SensitiveNotificationProtectionLog
    fun provideSensitiveNotificationProtectionLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("SensitiveNotificationProtectionLog", 10)
    }

    /** Provides a logging buffer for all logs related to unseen notifications. */
    @Provides
    @SysUISingleton
    @UnseenNotificationLog
    fun provideUnseenNotificationLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("UnseenNotifLog", 20, /* maxSize */ false /* systrace */)
    }

    /** Provides a logging buffer for all logs related to notification visual stability. */
    @Provides
    @SysUISingleton
    @VisualStabilityLog
    fun provideVisualStabilityLogBuffer(factory: LogBufferFactory): LogBuffer {
        return factory.create("VisualStabilityLog", 50, /* maxSize */ false /* systrace */)
    }
}
