package com.google.android.systemui;

import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;

import com.google.android.systemui.statusbar.NotificationEntryManagerGoogle;

import dagger.Binds;
import dagger.Module;

/**
 * A dagger module for injecting default implementations of components of System UI that may be
 * overridden by the System UI implementation.
 */
@Module
public abstract class SystemUIGoogleModule {

    @Binds
    abstract NotificationEntryManager bindNotificationEntryManager(
        NotificationEntryManagerGoogle notificationEntryManagerManager);

    @Binds
    abstract NotificationLockscreenUserManager bindNotificationLockscreenUserManager(
        NotificationLockscreenUserManagerGoogle notificationLockscreenUserManager);
}
