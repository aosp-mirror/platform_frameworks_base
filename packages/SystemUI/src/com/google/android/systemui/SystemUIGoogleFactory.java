package com.google.android.systemui;

import android.app.AlarmManager;
import android.content.Context;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.util.function.TriConsumer;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;

import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.function.Consumer;

public class SystemUIGoogleFactory extends SystemUIFactory {
    @Override
    public ScrimController createScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
            ScrimView scrimForBubble, LockscreenWallpaper lockscreenWallpaper,
            TriConsumer<ScrimState, Float, GradientColors> scrimStateListener,
            Consumer<Integer> scrimVisibleListener, DozeParameters dozeParameters,
            AlarmManager alarmManager, KeyguardMonitor keyguardMonitor) {
        return new LiveWallpaperScrimController(scrimBehind, scrimInFront, scrimForBubble, lockscreenWallpaper,
                scrimStateListener, scrimVisibleListener, dozeParameters, alarmManager, keyguardMonitor);
    }
}
