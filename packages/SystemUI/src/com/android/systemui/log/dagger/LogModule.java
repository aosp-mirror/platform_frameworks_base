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

package com.android.systemui.log.dagger;

import android.content.ContentResolver;
import android.os.Build;
import android.os.Looper;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogBufferFactory;
import com.android.systemui.log.LogcatEchoTracker;
import com.android.systemui.log.LogcatEchoTrackerDebug;
import com.android.systemui.log.LogcatEchoTrackerProd;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for providing instances of {@link LogBuffer}.
 */
@Module
public class LogModule {
    /** Provides a logging buffer for doze-related logs. */
    @Provides
    @SysUISingleton
    @DozeLog
    public static LogBuffer provideDozeLogBuffer(LogBufferFactory factory) {
        return factory.create("DozeLog", 100);
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotificationLog
    public static LogBuffer provideNotificationsLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifLog", 1000);
    }

    /** Provides a logging buffer for all logs related to managing notification sections. */
    @Provides
    @SysUISingleton
    @NotificationSectionLog
    public static LogBuffer provideNotificationSectionLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifSectionLog", 1000);
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotifInteractionLog
    public static LogBuffer provideNotifInteractionLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifInteractionLog", 50);
    }

    /** Provides a logging buffer for all logs related to Quick Settings. */
    @Provides
    @SysUISingleton
    @QSLog
    public static LogBuffer provideQuickSettingsLogBuffer(LogBufferFactory factory) {
        return factory.create("QSLog", 500);
    }

    /** Provides a logging buffer for {@link com.android.systemui.broadcast.BroadcastDispatcher} */
    @Provides
    @SysUISingleton
    @BroadcastDispatcherLog
    public static LogBuffer provideBroadcastDispatcherLogBuffer(LogBufferFactory factory) {
        return factory.create("BroadcastDispatcherLog", 500);
    }

    /** Provides a logging buffer for all logs related to Toasts shown by SystemUI. */
    @Provides
    @SysUISingleton
    @ToastLog
    public static LogBuffer provideToastLogBuffer(LogBufferFactory factory) {
        return factory.create("ToastLog", 50);
    }

    /** Provides a logging buffer for all logs related to privacy indicators in SystemUI. */
    @Provides
    @SysUISingleton
    @PrivacyLog
    public static LogBuffer providePrivacyLogBuffer(LogBufferFactory factory) {
        return factory.create("PrivacyLog", 100);
    }

    /** Allows logging buffers to be tweaked via adb on debug builds but not on prod builds. */
    @Provides
    @SysUISingleton
    public static LogcatEchoTracker provideLogcatEchoTracker(
            ContentResolver contentResolver,
            @Main Looper looper) {
        if (Build.IS_DEBUGGABLE) {
            return LogcatEchoTrackerDebug.create(contentResolver, looper);
        } else {
            return new LogcatEchoTrackerProd();
        }
    }
}
