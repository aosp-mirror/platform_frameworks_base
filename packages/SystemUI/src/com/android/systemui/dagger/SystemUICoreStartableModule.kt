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

package com.android.systemui.dagger

import com.android.keyguard.KeyguardBiometricLockoutLogger
import com.android.systemui.CoreStartable
import com.android.systemui.LatencyTester
import com.android.systemui.ScreenDecorations
import com.android.systemui.SliceBroadcastRelayHandler
import com.android.systemui.accessibility.SystemActions
import com.android.systemui.accessibility.Magnification
import com.android.systemui.back.domain.interactor.BackActionInteractor
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.BiometricNotificationService
import com.android.systemui.clipboardoverlay.ClipboardListener
import com.android.systemui.controls.dagger.StartControlsStartableModule
import com.android.systemui.dagger.qualifiers.PerUser
import com.android.systemui.dreams.AssistantAttentionMonitor
import com.android.systemui.dreams.DreamMonitor
import com.android.systemui.globalactions.GlobalActionsComponent
import com.android.systemui.keyboard.KeyboardUI
import com.android.systemui.keyboard.PhysicalKeyboardCoreStartable
import com.android.systemui.keyguard.KeyguardViewConfigurator
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.data.quickaffordance.MuteQuickAffordanceCoreStartable
import com.android.systemui.keyguard.ui.binder.AlternateBouncerBinder
import com.android.systemui.keyguard.ui.binder.KeyguardDismissActionBinder
import com.android.systemui.keyguard.ui.binder.KeyguardDismissBinder
import com.android.systemui.log.SessionTracker
import com.android.systemui.media.RingtonePlayer
import com.android.systemui.media.dialog.MediaOutputSwitcherDialogUI
import com.android.systemui.media.taptotransfer.MediaTttCommandLineHelper
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver
import com.android.systemui.media.taptotransfer.sender.MediaTttSenderCoordinator
import com.android.systemui.mediaprojection.taskswitcher.MediaProjectionTaskSwitcherCoreStartable
import com.android.systemui.power.PowerUI
import com.android.systemui.reardisplay.RearDisplayDialogController
import com.android.systemui.recents.Recents
import com.android.systemui.recents.ScreenPinningRequest
import com.android.systemui.settings.dagger.MultiUserUtilsModule
import com.android.systemui.shortcut.ShortcutKeyDispatcher
import com.android.systemui.statusbar.ImmersiveModeConfirmation
import com.android.systemui.statusbar.gesture.GesturePointerEventListener
import com.android.systemui.statusbar.notification.InstantAppNotifier
import com.android.systemui.statusbar.phone.KeyguardLiftController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.StatusBarHeadsUpChangeListener
import com.android.systemui.stylus.StylusUsiPowerStartable
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.theme.ThemeOverlayController
import com.android.systemui.toast.ToastUI
import com.android.systemui.usb.StorageNotification
import com.android.systemui.util.NotificationChannels
import com.android.systemui.util.StartBinderLoggerModule
import com.android.systemui.volume.VolumeUI
import com.android.systemui.wallpapers.dagger.WallpaperModule
import com.android.systemui.wmshell.WMShell
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/**
 * Collection of {@link CoreStartable}s that should be run on AOSP.
 */
@Module(
    includes = [
        MultiUserUtilsModule::class,
        StartControlsStartableModule::class,
        StartBinderLoggerModule::class,
        WallpaperModule::class,
    ]
)
abstract class SystemUICoreStartableModule {
    /** Inject into AuthController.  */
    @Binds
    @IntoMap
    @ClassKey(AuthController::class)
    abstract fun bindAuthController(service: AuthController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(AlternateBouncerBinder::class)
    abstract fun bindAlternateBouncerBinder(impl: AlternateBouncerBinder): CoreStartable

    /** Inject into BiometricNotificationService */
    @Binds
    @IntoMap
    @ClassKey(BiometricNotificationService::class)
    abstract fun bindBiometricNotificationService(
        service: BiometricNotificationService
    ): CoreStartable

    /** Inject into ClipboardListener.  */
    @Binds
    @IntoMap
    @ClassKey(ClipboardListener::class)
    abstract fun bindClipboardListener(sysui: ClipboardListener): CoreStartable

    /** Inject into GlobalActionsComponent.  */
    @Binds
    @IntoMap
    @ClassKey(GlobalActionsComponent::class)
    abstract fun bindGlobalActionsComponent(sysui: GlobalActionsComponent): CoreStartable

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

    /** Inject into MediaProjectionTaskSwitcherCoreStartable. */
    @Binds
    @IntoMap
    @ClassKey(MediaProjectionTaskSwitcherCoreStartable::class)
    abstract fun bindProjectedTaskListener(
            sysui: MediaProjectionTaskSwitcherCoreStartable
    ): CoreStartable

    /** Inject into KeyguardBiometricLockoutLogger */
    @Binds
    @IntoMap
    @ClassKey(KeyguardBiometricLockoutLogger::class)
    abstract fun bindKeyguardBiometricLockoutLogger(
        sysui: KeyguardBiometricLockoutLogger
    ): CoreStartable

    /** Inject into KeyguardViewMediator.  */
    @Binds
    @IntoMap
    @ClassKey(KeyguardViewMediator::class)
    abstract fun bindKeyguardViewMediator(sysui: KeyguardViewMediator): CoreStartable

    /** Inject into LatencyTests.  */
    @Binds
    @IntoMap
    @ClassKey(LatencyTester::class)
    abstract fun bindLatencyTester(sysui: LatencyTester): CoreStartable

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

    /** Inject into Recents.  */
    @Binds
    @IntoMap
    @ClassKey(Recents::class)
    abstract fun bindRecents(sysui: Recents): CoreStartable

    /** Inject into ImmersiveModeConfirmation.  */
    @Binds
    @IntoMap
    @ClassKey(ImmersiveModeConfirmation::class)
    abstract fun bindImmersiveModeConfirmation(sysui: ImmersiveModeConfirmation): CoreStartable

    /** Inject into RingtonePlayer.  */
    @Binds
    @IntoMap
    @ClassKey(RingtonePlayer::class)
    abstract fun bind(sysui: RingtonePlayer): CoreStartable

    /** Inject into ScreenDecorations.  */
    @Binds
    @IntoMap
    @ClassKey(ScreenDecorations::class)
    abstract fun bindScreenDecorations(sysui: ScreenDecorations): CoreStartable

    /** Inject into GesturePointerEventHandler. */
    @Binds
    @IntoMap
    @ClassKey(GesturePointerEventListener::class)
    abstract fun bindGesturePointerEventListener(sysui: GesturePointerEventListener): CoreStartable

    /** Inject into SessionTracker.  */
    @Binds
    @IntoMap
    @ClassKey(SessionTracker::class)
    abstract fun bindSessionTracker(service: SessionTracker): CoreStartable

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

    /** Inject into SystemActions.  */
    @Binds
    @IntoMap
    @ClassKey(SystemActions::class)
    abstract fun bindSystemActions(sysui: SystemActions): CoreStartable

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

    /** Inject into MediaOutputSwitcherDialogUI.  */
    @Binds
    @IntoMap
    @ClassKey(MediaOutputSwitcherDialogUI::class)
    abstract fun MediaOutputSwitcherDialogUI(sysui: MediaOutputSwitcherDialogUI): CoreStartable

    /** Inject into VolumeUI.  */
    @Binds
    @IntoMap
    @ClassKey(VolumeUI::class)
    abstract fun bindVolumeUI(sysui: VolumeUI): CoreStartable

    /** Inject into Magnification.  */
    @Binds
    @IntoMap
    @ClassKey(Magnification::class)
    abstract fun bindMagnification(sysui: Magnification): CoreStartable

    /** Inject into WMShell.  */
    @Binds
    @IntoMap
    @ClassKey(WMShell::class)
    abstract fun bindWMShell(sysui: WMShell): CoreStartable

    /** Inject into KeyguardLiftController.  */
    @Binds
    @IntoMap
    @ClassKey(KeyguardLiftController::class)
    abstract fun bindKeyguardLiftController(sysui: KeyguardLiftController): CoreStartable

    /** Inject into MediaTttSenderCoordinator. */
    @Binds
    @IntoMap
    @ClassKey(MediaTttSenderCoordinator::class)
    abstract fun bindMediaTttSenderCoordinator(sysui: MediaTttSenderCoordinator): CoreStartable

    /** Inject into MediaTttChipControllerReceiver. */
    @Binds
    @IntoMap
    @ClassKey(MediaTttChipControllerReceiver::class)
    abstract fun bindMediaTttChipControllerReceiver(
            sysui: MediaTttChipControllerReceiver
    ): CoreStartable

    /** Inject into MediaTttCommandLineHelper. */
    @Binds
    @IntoMap
    @ClassKey(MediaTttCommandLineHelper::class)
    abstract fun bindMediaTttCommandLineHelper(sysui: MediaTttCommandLineHelper): CoreStartable

    /** Inject into ChipbarCoordinator. */
    @Binds
    @IntoMap
    @ClassKey(ChipbarCoordinator::class)
    abstract fun bindChipbarController(sysui: ChipbarCoordinator): CoreStartable


    /** Inject into RearDisplayDialogController) */
    @Binds
    @IntoMap
    @ClassKey(RearDisplayDialogController::class)
    abstract fun bindRearDisplayDialogController(sysui: RearDisplayDialogController): CoreStartable

    /** Inject into StylusUsiPowerStartable) */
    @Binds
    @IntoMap
    @ClassKey(StylusUsiPowerStartable::class)
    abstract fun bindStylusUsiPowerStartable(sysui: StylusUsiPowerStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(PhysicalKeyboardCoreStartable::class)
    abstract fun bindKeyboardCoreStartable(listener: PhysicalKeyboardCoreStartable): CoreStartable

    /** Inject into MuteQuickAffordanceCoreStartable*/
    @Binds
    @IntoMap
    @ClassKey(MuteQuickAffordanceCoreStartable::class)
    abstract fun bindMuteQuickAffordanceCoreStartable(
            sysui: MuteQuickAffordanceCoreStartable
    ): CoreStartable

    /**Inject into DreamMonitor */
    @Binds
    @IntoMap
    @ClassKey(DreamMonitor::class)
    abstract fun bindDreamMonitor(sysui: DreamMonitor): CoreStartable

    /**Inject into AssistantAttentionMonitor */
    @Binds
    @IntoMap
    @ClassKey(AssistantAttentionMonitor::class)
    abstract fun bindAssistantAttentionMonitor(sysui: AssistantAttentionMonitor): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(KeyguardViewConfigurator::class)
    abstract fun bindKeyguardViewConfigurator(impl: KeyguardViewConfigurator): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(ScrimController::class)
    abstract fun bindScrimController(impl: ScrimController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(StatusBarHeadsUpChangeListener::class)
    abstract fun bindStatusBarHeadsUpChangeListener(
        impl: StatusBarHeadsUpChangeListener
    ): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(BackActionInteractor::class)
    abstract fun bindBackActionInteractor(impl: BackActionInteractor): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(KeyguardDismissActionBinder::class)
    abstract fun bindKeyguardDismissActionBinder(impl: KeyguardDismissActionBinder): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(KeyguardDismissBinder::class)
    abstract fun bindKeyguardDismissBinder(impl: KeyguardDismissBinder): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(ScreenPinningRequest::class)
    abstract fun bindScreenPinningRequest(impl: ScreenPinningRequest): CoreStartable
}
