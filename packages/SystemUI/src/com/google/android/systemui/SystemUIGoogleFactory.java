package com.google.android.systemui;

import android.app.AlarmManager;
import android.content.Context;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.util.function.TriConsumer;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;

import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.function.Consumer;

import com.android.systemui.statusbar.notification.NotificationEntryManager;

import com.google.android.systemui.statusbar.NotificationEntryManagerGoogle;

public class SystemUIGoogleFactory extends SystemUIFactory {
    @Override
    public NotificationEntryManager provideNotificationEntryManager(Context context) {
        return new NotificationEntryManagerGoogle(context);
    }

    @Override
    public NotificationLockscreenUserManager provideNotificationLockscreenUserManager(Context context) {
        return new NotificationLockscreenUserManagerGoogle(context);
    }

    @Override
    public ScrimController createScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
            LockscreenWallpaper lockscreenWallpaper,
            TriConsumer<ScrimState, Float, GradientColors> scrimStateListener,
            Consumer<Integer> scrimVisibleListener, DozeParameters dozeParameters,
            AlarmManager alarmManager, KeyguardMonitor keyguardMonitor) {
        return new LiveWallpaperScrimController(scrimBehind, scrimInFront, lockscreenWallpaper,
                scrimStateListener, scrimVisibleListener, dozeParameters, alarmManager, keyguardMonitor);
    }
}
