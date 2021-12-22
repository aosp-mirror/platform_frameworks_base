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

import com.android.systemui.CoreStartable;
import com.android.systemui.LatencyTester;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.SliceBroadcastRelayHandler;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.accessibility.WindowMagnification;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.communal.CommunalManagerUpdater;
import com.android.systemui.dreams.DreamOverlayRegistrant;
import com.android.systemui.dreams.appwidgets.ComplicationPrimer;
import com.android.systemui.globalactions.GlobalActionsComponent;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.dagger.KeyguardModule;
import com.android.systemui.media.systemsounds.HomeSoundEffectController;
import com.android.systemui.power.PowerUI;
import com.android.systemui.privacy.television.TvOngoingPrivacyChip;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsModule;
import com.android.systemui.shortcut.ShortcutKeyDispatcher;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.notification.InstantAppNotifier;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.tv.TvStatusBar;
import com.android.systemui.statusbar.tv.notifications.TvNotificationPanel;
import com.android.systemui.theme.ThemeOverlayController;
import com.android.systemui.toast.ToastUI;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.volume.VolumeUI;
import com.android.systemui.wmshell.WMShell;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * SystemUI objects that are injectable should go here.
 */
@Module(includes = {
        RecentsModule.class,
        StatusBarModule.class,
        KeyguardModule.class,
})
public abstract class SystemUIBinder {
    /** Inject into AuthController. */
    @Binds
    @IntoMap
    @ClassKey(AuthController.class)
    public abstract CoreStartable bindAuthController(AuthController service);

    /** Inject into GarbageMonitor.Service. */
    @Binds
    @IntoMap
    @ClassKey(GarbageMonitor.Service.class)
    public abstract CoreStartable bindGarbageMonitorService(GarbageMonitor.Service sysui);

    /** Inject into GlobalActionsComponent. */
    @Binds
    @IntoMap
    @ClassKey(GlobalActionsComponent.class)
    public abstract CoreStartable bindGlobalActionsComponent(GlobalActionsComponent sysui);

    /** Inject into InstantAppNotifier. */
    @Binds
    @IntoMap
    @ClassKey(InstantAppNotifier.class)
    public abstract CoreStartable bindInstantAppNotifier(InstantAppNotifier sysui);

    /** Inject into KeyguardViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(KeyguardViewMediator.class)
    public abstract CoreStartable bindKeyguardViewMediator(KeyguardViewMediator sysui);

    /** Inject into LatencyTests. */
    @Binds
    @IntoMap
    @ClassKey(LatencyTester.class)
    public abstract CoreStartable bindLatencyTester(LatencyTester sysui);

    /** Inject into PowerUI. */
    @Binds
    @IntoMap
    @ClassKey(PowerUI.class)
    public abstract CoreStartable bindPowerUI(PowerUI sysui);

    /** Inject into Recents. */
    @Binds
    @IntoMap
    @ClassKey(Recents.class)
    public abstract CoreStartable bindRecents(Recents sysui);

    /** Inject into ScreenDecorations. */
    @Binds
    @IntoMap
    @ClassKey(ScreenDecorations.class)
    public abstract CoreStartable bindScreenDecorations(ScreenDecorations sysui);

    /** Inject into ShortcutKeyDispatcher. */
    @Binds
    @IntoMap
    @ClassKey(ShortcutKeyDispatcher.class)
    public abstract CoreStartable bindsShortcutKeyDispatcher(ShortcutKeyDispatcher sysui);

    /** Inject into SliceBroadcastRelayHandler. */
    @Binds
    @IntoMap
    @ClassKey(SliceBroadcastRelayHandler.class)
    public abstract CoreStartable bindSliceBroadcastRelayHandler(SliceBroadcastRelayHandler sysui);

    /** Inject into StatusBar. */
    @Binds
    @IntoMap
    @ClassKey(StatusBar.class)
    public abstract CoreStartable bindsStatusBar(StatusBar sysui);

    /** Inject into SystemActions. */
    @Binds
    @IntoMap
    @ClassKey(SystemActions.class)
    public abstract CoreStartable bindSystemActions(SystemActions sysui);

    /** Inject into ThemeOverlayController. */
    @Binds
    @IntoMap
    @ClassKey(ThemeOverlayController.class)
    public abstract CoreStartable bindThemeOverlayController(ThemeOverlayController sysui);

    /** Inject into ToastUI. */
    @Binds
    @IntoMap
    @ClassKey(ToastUI.class)
    public abstract CoreStartable bindToastUI(ToastUI service);

    /** Inject into TvStatusBar. */
    @Binds
    @IntoMap
    @ClassKey(TvStatusBar.class)
    public abstract CoreStartable bindsTvStatusBar(TvStatusBar sysui);

    /** Inject into TvNotificationPanel. */
    @Binds
    @IntoMap
    @ClassKey(TvNotificationPanel.class)
    public abstract CoreStartable bindsTvNotificationPanel(TvNotificationPanel sysui);

    /** Inject into TvOngoingPrivacyChip. */
    @Binds
    @IntoMap
    @ClassKey(TvOngoingPrivacyChip.class)
    public abstract CoreStartable bindsTvOngoingPrivacyChip(TvOngoingPrivacyChip sysui);

    /** Inject into VolumeUI. */
    @Binds
    @IntoMap
    @ClassKey(VolumeUI.class)
    public abstract CoreStartable bindVolumeUI(VolumeUI sysui);

    /** Inject into WindowMagnification. */
    @Binds
    @IntoMap
    @ClassKey(WindowMagnification.class)
    public abstract CoreStartable bindWindowMagnification(WindowMagnification sysui);

    /** Inject into WMShell. */
    @Binds
    @IntoMap
    @ClassKey(WMShell.class)
    public abstract CoreStartable bindWMShell(WMShell sysui);

    /** Inject into HomeSoundEffectController. */
    @Binds
    @IntoMap
    @ClassKey(HomeSoundEffectController.class)
    public abstract CoreStartable bindHomeSoundEffectController(HomeSoundEffectController sysui);

    /** Inject into DreamOverlay. */
    @Binds
    @IntoMap
    @ClassKey(DreamOverlayRegistrant.class)
    public abstract CoreStartable bindDreamOverlayRegistrant(
            DreamOverlayRegistrant dreamOverlayRegistrant);

    /** Inject into AppWidgetOverlayPrimer. */
    @Binds
    @IntoMap
    @ClassKey(ComplicationPrimer.class)
    public abstract CoreStartable bindAppWidgetOverlayPrimer(
            ComplicationPrimer complicationPrimer);

    /** Inject into CommunalManagerUpdater. */
    @Binds
    @IntoMap
    @ClassKey(CommunalManagerUpdater.class)
    public abstract CoreStartable bindCommunalManagerUpdater(
            CommunalManagerUpdater communalManagerUpdater);
}
