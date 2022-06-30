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
        return factory.create("NotifLog", 1000 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotificationHeadsUpLog
    public static LogBuffer provideNotificationHeadsUpLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifHeadsUpLog", 1000);
    }

    /** Provides a logging buffer for all logs for lockscreen to shade transition events. */
    @Provides
    @SysUISingleton
    @LSShadeTransitionLog
    public static LogBuffer provideLSShadeTransitionControllerBuffer(LogBufferFactory factory) {
        return factory.create("LSShadeTransitionLog", 50);
    }

    /** Provides a logging buffer for all logs related to managing notification sections. */
    @Provides
    @SysUISingleton
    @NotificationSectionLog
    public static LogBuffer provideNotificationSectionLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifSectionLog", 1000 /* maxSize */, false /* systrace */);
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
        return factory.create("QSLog", 500 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for {@link com.android.systemui.broadcast.BroadcastDispatcher} */
    @Provides
    @SysUISingleton
    @BroadcastDispatcherLog
    public static LogBuffer provideBroadcastDispatcherLogBuffer(LogBufferFactory factory) {
        return factory.create("BroadcastDispatcherLog", 500 /* maxSize */,
                false /* systrace */);
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

    /**
     * Provides a logging buffer for
     * {@link com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment}.
     */
    @Provides
    @SysUISingleton
    @CollapsedSbFragmentLog
    public static LogBuffer provideCollapsedSbFragmentLogBuffer(LogBufferFactory factory) {
        return factory.create("CollapsedSbFragmentLog", 20);
    }

    /**
     * Provides a logging buffer for logs related to {@link com.android.systemui.qs.QSFragment}'s
     * disable flag adjustments.
     */
    @Provides
    @SysUISingleton
    @QSFragmentDisableLog
    public static LogBuffer provideQSFragmentDisableLogBuffer(LogBufferFactory factory) {
        return factory.create("QSFragmentDisableFlagsLog", 10 /* maxSize */,
                false /* systrace */);
    }

    /**
     * Provides a logging buffer for logs related to swiping away the status bar while in immersive
     * mode. See {@link com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureLogger}.
     */
    @Provides
    @SysUISingleton
    @SwipeStatusBarAwayLog
    public static LogBuffer provideSwipeAwayGestureLogBuffer(LogBufferFactory factory) {
        return factory.create("SwipeStatusBarAwayLog", 30);
    }

    /**
     * Provides a logging buffer for logs related to the media tap-to-transfer chip on the sender
     * device. See {@link com.android.systemui.media.taptotransfer.sender.MediaTttSenderLogger}.
     */
    @Provides
    @SysUISingleton
    @MediaTttSenderLogBuffer
    public static LogBuffer provideMediaTttSenderLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaTttSender", 20);
    }

    /**
     * Provides a logging buffer for logs related to the media tap-to-transfer chip on the receiver
     * device. See {@link com.android.systemui.media.taptotransfer.receiver.MediaTttReceiverLogger}.
     */
    @Provides
    @SysUISingleton
    @MediaTttReceiverLogBuffer
    public static LogBuffer provideMediaTttReceiverLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaTttReceiver", 20);
    }

    /**
     * Provides a logging buffer for logs related to the media mute-await connections. See
     * {@link com.android.systemui.media.muteawait.MediaMuteAwaitConnectionManager}.
     */
    @Provides
    @SysUISingleton
    @MediaMuteAwaitLog
    public static LogBuffer provideMediaMuteAwaitLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaMuteAwaitLog", 20);
    }

    /**
     * Provides a logging buffer for logs related to the media mute-await connections. See
     * {@link com.android.systemui.media.nearby.NearbyMediaDevicesManager}.
     */
    @Provides
    @SysUISingleton
    @NearbyMediaDevicesLog
    public static LogBuffer provideNearbyMediaDevicesLogBuffer(LogBufferFactory factory) {
        return factory.create("NearbyMediaDevicesLog", 20);
    }

    /**
     * Provides a buffer for logs related to media view events
     */
    @Provides
    @SysUISingleton
    @MediaViewLog
    public static LogBuffer provideMediaViewLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaView", 100);
    }

    /**
     * Provides a buffer for media playback state changes
     */
    @Provides
    @SysUISingleton
    @MediaTimeoutListenerLog
    public static LogBuffer providesMediaTimeoutListenerLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaTimeout", 100);
    }

    /**
     * Provides a buffer for our connections and disconnections to MediaBrowserService.
     *
     * See {@link com.android.systemui.media.ResumeMediaBrowser}.
     */
    @Provides
    @SysUISingleton
    @MediaBrowserLog
    public static LogBuffer provideMediaBrowserBuffer(LogBufferFactory factory) {
        return factory.create("MediaBrowser", 100);
    }

    /**
     * Provides a buffer for updates to the media carousel.
     *
     * See {@link com.android.systemui.media.MediaCarouselController}.
     */
    @Provides
    @SysUISingleton
    @MediaCarouselControllerLog
    public static LogBuffer provideMediaCarouselControllerBuffer(LogBufferFactory factory) {
        return factory.create("MediaCarouselCtlrLog", 20);
    }

    /** Allows logging buffers to be tweaked via adb on debug builds but not on prod builds. */
    @Provides
    @SysUISingleton
    public static LogcatEchoTracker provideLogcatEchoTracker(
            ContentResolver contentResolver,
            @Main Looper looper) {
        if (Build.isDebuggable()) {
            return LogcatEchoTrackerDebug.create(contentResolver, looper);
        } else {
            return new LogcatEchoTrackerProd();
        }
    }
}
