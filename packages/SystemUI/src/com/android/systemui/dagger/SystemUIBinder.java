/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.dagger;

import com.android.systemui.LatencyTester;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.SizeCompatModeActivityController;
import com.android.systemui.SliceBroadcastRelayHandler;
import com.android.systemui.SystemUI;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.accessibility.WindowMagnification;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FODCircleViewImpl;
import com.android.systemui.bubbles.dagger.BubbleModule;
import com.android.systemui.globalactions.GlobalActionsComponent;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.dagger.KeyguardModule;
import com.android.systemui.pip.PipUI;
import com.android.systemui.power.PowerUI;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsModule;
import com.android.systemui.shortcut.ShortcutKeyDispatcher;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.notification.InstantAppNotifier;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.tv.TvStatusBar;
import com.android.systemui.theme.ThemeOverlayController;
import com.android.systemui.toast.ToastUI;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.volume.VolumeUI;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * SystemUI objects that are injectable should go here.
 */
@Module(includes = {RecentsModule.class, StatusBarModule.class, BubbleModule.class,
        KeyguardModule.class})
public abstract class SystemUIBinder {
    /** Inject into AuthController. */
    @Binds
    @IntoMap
    @ClassKey(AuthController.class)
    public abstract SystemUI bindAuthController(AuthController service);

    /** Inject into Divider. */
    @Binds
    @IntoMap
    @ClassKey(Divider.class)
    public abstract SystemUI bindDivider(Divider sysui);

    /** Inject into FODCircleViewImpl. */
    @Binds
    @IntoMap
    @ClassKey(FODCircleViewImpl.class)
    public abstract SystemUI FODCircleViewImpl(FODCircleViewImpl sysui);

    /** Inject into GarbageMonitor.Service. */
    @Binds
    @IntoMap
    @ClassKey(GarbageMonitor.Service.class)
    public abstract SystemUI bindGarbageMonitorService(GarbageMonitor.Service sysui);

    /** Inject into GlobalActionsComponent. */
    @Binds
    @IntoMap
    @ClassKey(GlobalActionsComponent.class)
    public abstract SystemUI bindGlobalActionsComponent(GlobalActionsComponent sysui);

    /** Inject into InstantAppNotifier. */
    @Binds
    @IntoMap
    @ClassKey(InstantAppNotifier.class)
    public abstract SystemUI bindInstantAppNotifier(InstantAppNotifier sysui);

    /** Inject into KeyguardViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(KeyguardViewMediator.class)
    public abstract SystemUI bindKeyguardViewMediator(KeyguardViewMediator sysui);

    /** Inject into LatencyTests. */
    @Binds
    @IntoMap
    @ClassKey(LatencyTester.class)
    public abstract SystemUI bindLatencyTester(LatencyTester sysui);

    /** Inject into PipUI. */
    @Binds
    @IntoMap
    @ClassKey(PipUI.class)
    public abstract SystemUI bindPipUI(PipUI sysui);

    /** Inject into PowerUI. */
    @Binds
    @IntoMap
    @ClassKey(PowerUI.class)
    public abstract SystemUI bindPowerUI(PowerUI sysui);

    /** Inject into Recents. */
    @Binds
    @IntoMap
    @ClassKey(Recents.class)
    public abstract SystemUI bindRecents(Recents sysui);

    /** Inject into ScreenDecorations. */
    @Binds
    @IntoMap
    @ClassKey(ScreenDecorations.class)
    public abstract SystemUI bindScreenDecorations(ScreenDecorations sysui);

    /** Inject into ShortcutKeyDispatcher. */
    @Binds
    @IntoMap
    @ClassKey(ShortcutKeyDispatcher.class)
    public abstract SystemUI bindsShortcutKeyDispatcher(ShortcutKeyDispatcher sysui);

    /** Inject into SizeCompatModeActivityController. */
    @Binds
    @IntoMap
    @ClassKey(SizeCompatModeActivityController.class)
    public abstract SystemUI bindsSizeCompatModeActivityController(
            SizeCompatModeActivityController sysui);

    /** Inject into SliceBroadcastRelayHandler. */
    @Binds
    @IntoMap
    @ClassKey(SliceBroadcastRelayHandler.class)
    public abstract SystemUI bindSliceBroadcastRelayHandler(SliceBroadcastRelayHandler sysui);

    /** Inject into StatusBar. */
    @Binds
    @IntoMap
    @ClassKey(StatusBar.class)
    public abstract SystemUI bindsStatusBar(StatusBar sysui);

    /** Inject into SystemActions. */
    @Binds
    @IntoMap
    @ClassKey(SystemActions.class)
    public abstract SystemUI bindSystemActions(SystemActions sysui);

    /** Inject into ThemeOverlayController. */
    @Binds
    @IntoMap
    @ClassKey(ThemeOverlayController.class)
    public abstract SystemUI bindThemeOverlayController(ThemeOverlayController sysui);

    /** Inject into ToastUI. */
    @Binds
    @IntoMap
    @ClassKey(ToastUI.class)
    public abstract SystemUI bindToastUI(ToastUI service);

    /** Inject into TvStatusBar. */
    @Binds
    @IntoMap
    @ClassKey(TvStatusBar.class)
    public abstract SystemUI bindsTvStatusBar(TvStatusBar sysui);

    /** Inject into VolumeUI. */
    @Binds
    @IntoMap
    @ClassKey(VolumeUI.class)
    public abstract SystemUI bindVolumeUI(VolumeUI sysui);

    /** Inject into WindowMagnification. */
    @Binds
    @IntoMap
    @ClassKey(WindowMagnification.class)
    public abstract SystemUI bindWindowMagnification(WindowMagnification sysui);
}
