/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard

import android.content.Context
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.Flags.lightRevealMigration
import com.android.systemui.biometrics.ui.binder.DeviceEntryUnlockTrackerViewBinder
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.WallpaperFocalAreaInteractor
import com.android.systemui.keyguard.ui.binder.KeyguardBlueprintViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardJankBinder
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.binder.LightRevealScrimViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardIndicationArea
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardJankViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.wallpapers.ui.viewmodel.WallpaperViewModel
import com.google.android.msdl.domain.MSDLPlayer
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle

/** Binds keyguard views on startup, and also exposes methods to allow rebinding if views change */
@SysUISingleton
class KeyguardViewConfigurator
@Inject
constructor(
    private val keyguardRootView: KeyguardRootView,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val keyguardJankViewModel: KeyguardJankViewModel,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
    private val chipbarCoordinator: ChipbarCoordinator,
    private val keyguardBlueprintViewModel: KeyguardBlueprintViewModel,
    @ShadeDisplayAware private val configuration: ConfigurationState,
    @ShadeDisplayAware private val context: Context,
    private val keyguardIndicationController: KeyguardIndicationController,
    private val shadeInteractor: ShadeInteractor,
    private val interactionJankMonitor: InteractionJankMonitor,
    private val deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor,
    private val vibratorHelper: VibratorHelper,
    private val falsingManager: FalsingManager,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val smartspaceViewModel: KeyguardSmartspaceViewModel,
    private val clockInteractor: KeyguardClockInteractor,
    private val wallpaperFocalAreaInteractor: WallpaperFocalAreaInteractor,
    private val keyguardViewMediator: KeyguardViewMediator,
    private val deviceEntryUnlockTrackerViewBinder: Optional<DeviceEntryUnlockTrackerViewBinder>,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val lightRevealScrimViewModel: LightRevealScrimViewModel,
    private val lightRevealScrim: LightRevealScrim,
    private val wallpaperViewModel: WallpaperViewModel,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val msdlPlayer: MSDLPlayer,
    @KeyguardBlueprintLog private val blueprintLog: LogBuffer,
) : CoreStartable {

    private var rootViewHandle: DisposableHandle? = null
    private var jankHandle: DisposableHandle? = null

    override fun start() {
        bindKeyguardRootView()
        bindJankViewModel()
        initializeViews()

        if (lightRevealMigration()) {
            LightRevealScrimViewBinder.bind(
                lightRevealScrim,
                lightRevealScrimViewModel,
                wallpaperViewModel,
            )
        }

        if (!SceneContainerFlag.isEnabled) {
            KeyguardBlueprintViewBinder.bind(
                keyguardRootView,
                keyguardBlueprintViewModel,
                keyguardClockViewModel,
                smartspaceViewModel,
                blueprintLog,
            )
        }
        if (deviceEntryUnlockTrackerViewBinder.isPresent) {
            deviceEntryUnlockTrackerViewBinder.get().bind(keyguardRootView)
        }
    }

    /** Initialize views so that corresponding controllers have a view set. */
    private fun initializeViews() {
        val indicationArea = KeyguardIndicationArea(context, null)
        keyguardIndicationController.setIndicationArea(indicationArea)
    }

    private fun bindKeyguardRootView() {
        if (SceneContainerFlag.isEnabled) {
            return
        }

        rootViewHandle?.dispose()
        rootViewHandle =
            KeyguardRootViewBinder.bind(
                keyguardRootView,
                keyguardRootViewModel,
                keyguardBlueprintViewModel,
                configuration,
                occludingAppDeviceEntryMessageViewModel,
                chipbarCoordinator,
                screenOffAnimationController,
                shadeInteractor,
                clockInteractor,
                wallpaperFocalAreaInteractor,
                keyguardClockViewModel,
                deviceEntryHapticsInteractor,
                vibratorHelper,
                falsingManager,
                statusBarKeyguardViewManager,
                mainDispatcher,
                msdlPlayer,
                blueprintLog,
            )
    }

    private fun bindJankViewModel() {
        if (SceneContainerFlag.isEnabled) {
            return
        }

        jankHandle?.dispose()
        jankHandle =
            KeyguardJankBinder.bind(
                keyguardRootView,
                keyguardJankViewModel,
                interactionJankMonitor,
                clockInteractor,
                keyguardViewMediator,
                mainDispatcher,
            )
    }

    fun getKeyguardRootView() = keyguardRootView
}
