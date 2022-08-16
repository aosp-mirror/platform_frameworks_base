/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.tv

import com.android.systemui.CoreStartable
import com.android.systemui.SliceBroadcastRelayHandler
import com.android.systemui.accessibility.WindowMagnification
import com.android.systemui.dagger.qualifiers.PerUser
import com.android.systemui.globalactions.GlobalActionsComponent
import com.android.systemui.keyboard.KeyboardUI
import com.android.systemui.media.RingtonePlayer
import com.android.systemui.media.systemsounds.HomeSoundEffectController
import com.android.systemui.power.PowerUI
import com.android.systemui.privacy.television.TvOngoingPrivacyChip
import com.android.systemui.shortcut.ShortcutKeyDispatcher
import com.android.systemui.statusbar.notification.InstantAppNotifier
import com.android.systemui.statusbar.tv.TvStatusBar
import com.android.systemui.statusbar.tv.VpnStatusObserver
import com.android.systemui.statusbar.tv.notifications.TvNotificationHandler
import com.android.systemui.statusbar.tv.notifications.TvNotificationPanel
import com.android.systemui.theme.ThemeOverlayController
import com.android.systemui.toast.ToastUI
import com.android.systemui.usb.StorageNotification
import com.android.systemui.util.NotificationChannels
import com.android.systemui.volume.VolumeUI
import com.android.systemui.wmshell.WMShell
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/**
 * Collection of {@link CoreStartable}s that should be run on TV.
 */
@Module
abstract class TVSystemUICoreStartableModule {
    /** Inject into GlobalActionsComponent.  */
    @Binds
    @IntoMap
    @ClassKey(GlobalActionsComponent::class)
    abstract fun bindGlobalActionsComponent(sysui: GlobalActionsComponent): CoreStartable

    /** Inject into HomeSoundEffectController.  */
    @Binds
    @IntoMap
    @ClassKey(HomeSoundEffectController::class)
    abstract fun bindHomeSoundEffectController(sysui: HomeSoundEffectController): CoreStartable

    /** Inject into InstantAppNotifier.  */
    @Binds
    @IntoMap
    @ClassKey(InstantAppNotifier::class)
    abstract fun bindInstantAppNotifier(sysui: InstantAppNotifier): CoreStartable

    /** Inject into KeyboardUI.  */
    @Binds
    @IntoMap
    @ClassKey(KeyboardUI::class)
    abstract fun bindKeyboardUI(sysui: KeyboardUI): CoreStartable

    /** Inject into NotificationChannels.  */
    @Binds
    @IntoMap
    @ClassKey(NotificationChannels::class)
    @PerUser
    abstract fun bindNotificationChannels(sysui: NotificationChannels): CoreStartable

    /** Inject into PowerUI.  */
    @Binds
    @IntoMap
    @ClassKey(PowerUI::class)
    abstract fun bindPowerUI(sysui: PowerUI): CoreStartable

    /** Inject into RingtonePlayer.  */
    @Binds
    @IntoMap
    @ClassKey(RingtonePlayer::class)
    abstract fun bind(sysui: RingtonePlayer): CoreStartable

    /** Inject into ShortcutKeyDispatcher.  */
    @Binds
    @IntoMap
    @ClassKey(ShortcutKeyDispatcher::class)
    abstract fun bindShortcutKeyDispatcher(sysui: ShortcutKeyDispatcher): CoreStartable

    /** Inject into SliceBroadcastRelayHandler.  */
    @Binds
    @IntoMap
    @ClassKey(SliceBroadcastRelayHandler::class)
    abstract fun bindSliceBroadcastRelayHandler(sysui: SliceBroadcastRelayHandler): CoreStartable

    /** Inject into StorageNotification.  */
    @Binds
    @IntoMap
    @ClassKey(StorageNotification::class)
    abstract fun bindStorageNotification(sysui: StorageNotification): CoreStartable

    /** Inject into ThemeOverlayController.  */
    @Binds
    @IntoMap
    @ClassKey(ThemeOverlayController::class)
    abstract fun bindThemeOverlayController(sysui: ThemeOverlayController): CoreStartable

    /** Inject into ToastUI.  */
    @Binds
    @IntoMap
    @ClassKey(ToastUI::class)
    abstract fun bindToastUI(service: ToastUI): CoreStartable

    /** Inject into TvNotificationHandler.  */
    @Binds
    @IntoMap
    @ClassKey(TvNotificationHandler::class)
    abstract fun bindTvNotificationHandler(sysui: TvNotificationHandler): CoreStartable

    /** Inject into TvNotificationPanel.  */
    @Binds
    @IntoMap
    @ClassKey(TvNotificationPanel::class)
    abstract fun bindTvNotificationPanel(sysui: TvNotificationPanel): CoreStartable

    /** Inject into TvOngoingPrivacyChip.  */
    @Binds
    @IntoMap
    @ClassKey(TvOngoingPrivacyChip::class)
    abstract fun bindTvOngoingPrivacyChip(sysui: TvOngoingPrivacyChip): CoreStartable

    /** Inject into TvStatusBar.  */
    @Binds
    @IntoMap
    @ClassKey(TvStatusBar::class)
    abstract fun bindTvStatusBar(sysui: TvStatusBar): CoreStartable

    /** Inject into VolumeUI.  */
    @Binds
    @IntoMap
    @ClassKey(VolumeUI::class)
    abstract fun bindVolumeUI(sysui: VolumeUI): CoreStartable

    /** Inject into VpnStatusObserver.  */
    @Binds
    @IntoMap
    @ClassKey(VpnStatusObserver::class)
    abstract fun bindVpnStatusObserver(sysui: VpnStatusObserver): CoreStartable

    /** Inject into WindowMagnification.  */
    @Binds
    @IntoMap
    @ClassKey(WindowMagnification::class)
    abstract fun bindWindowMagnification(sysui: WindowMagnification): CoreStartable

    /** Inject into WMShell.  */
    @Binds
    @IntoMap
    @ClassKey(WMShell::class)
    abstract fun bindWMShell(sysui: WMShell): CoreStartable
}
