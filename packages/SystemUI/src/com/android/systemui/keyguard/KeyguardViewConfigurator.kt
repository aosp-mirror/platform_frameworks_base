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
import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.internal.jank.InteractionJankMonitor
import com.android.keyguard.KeyguardStatusView
import com.android.keyguard.KeyguardStatusViewController
import com.android.keyguard.LegacyLockIconViewController
import com.android.keyguard.LockIconView
import com.android.keyguard.dagger.KeyguardStatusViewComponent
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.ui.binder.DeviceEntryUnlockTrackerViewBinder
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.LockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.binder.KeyguardBlueprintViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.composable.LockscreenContent
import com.android.systemui.keyguard.ui.composable.blueprint.ComposableLockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.view.KeyguardIndicationArea
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.google.android.msdl.domain.MSDLPlayer
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Binds keyguard views on startup, and also exposes methods to allow rebinding if views change */
@ExperimentalCoroutinesApi
@SysUISingleton
class KeyguardViewConfigurator
@Inject
constructor(
    private val keyguardRootView: KeyguardRootView,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val keyguardIndicationAreaViewModel: KeyguardIndicationAreaViewModel,
    private val notificationShadeWindowView: NotificationShadeWindowView,
    private val indicationController: KeyguardIndicationController,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
    private val chipbarCoordinator: ChipbarCoordinator,
    private val keyguardBlueprintViewModel: KeyguardBlueprintViewModel,
    private val keyguardStatusViewComponentFactory: KeyguardStatusViewComponent.Factory,
    private val configuration: ConfigurationState,
    private val context: Context,
    private val keyguardIndicationController: KeyguardIndicationController,
    private val lockIconViewController: Lazy<LegacyLockIconViewController>,
    private val shadeInteractor: ShadeInteractor,
    private val interactionJankMonitor: InteractionJankMonitor,
    private val deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor,
    private val vibratorHelper: VibratorHelper,
    private val falsingManager: FalsingManager,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val smartspaceViewModel: KeyguardSmartspaceViewModel,
    private val lockscreenContentViewModelFactory: LockscreenContentViewModel.Factory,
    private val lockscreenSceneBlueprintsLazy: Lazy<Set<LockscreenSceneBlueprint>>,
    private val clockInteractor: KeyguardClockInteractor,
    private val keyguardViewMediator: KeyguardViewMediator,
    private val deviceEntryUnlockTrackerViewBinder: Optional<DeviceEntryUnlockTrackerViewBinder>,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val msdlPlayer: MSDLPlayer,
) : CoreStartable {

    private var rootViewHandle: DisposableHandle? = null
    private var indicationAreaHandle: DisposableHandle? = null

    var keyguardStatusViewController: KeyguardStatusViewController? = null
        get() {
            if (field == null) {
                val statusViewComponent =
                    keyguardStatusViewComponentFactory.build(
                        LayoutInflater.from(context).inflate(R.layout.keyguard_status_view, null)
                            as KeyguardStatusView,
                        context.display,
                    )
                val controller = statusViewComponent.keyguardStatusViewController
                controller.init()
                field = controller
            }

            return field
        }

    override fun start() {
        bindKeyguardRootView()
        initializeViews()

        if (!SceneContainerFlag.isEnabled) {
            KeyguardBlueprintViewBinder.bind(
                keyguardRootView,
                keyguardBlueprintViewModel,
                keyguardClockViewModel,
                smartspaceViewModel,
            )
        }
        if (deviceEntryUnlockTrackerViewBinder.isPresent) {
            deviceEntryUnlockTrackerViewBinder.get().bind(keyguardRootView)
        }
    }

    fun bindIndicationArea() {
        indicationAreaHandle?.dispose()

        if (!KeyguardBottomAreaRefactor.isEnabled) {
            keyguardRootView.findViewById<View?>(R.id.keyguard_indication_area)?.let {
                keyguardRootView.removeView(it)
            }
        }

        indicationAreaHandle =
            KeyguardIndicationAreaBinder.bind(
                notificationShadeWindowView.requireViewById(R.id.keyguard_indication_area),
                keyguardIndicationAreaViewModel,
                indicationController,
            )
    }

    /** Initialize views so that corresponding controllers have a view set. */
    private fun initializeViews() {
        val indicationArea = KeyguardIndicationArea(context, null)
        keyguardIndicationController.setIndicationArea(indicationArea)

        if (!DeviceEntryUdfpsRefactor.isEnabled) {
            lockIconViewController.get().setLockIconView(LockIconView(context, null))
        }
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
                keyguardClockViewModel,
                interactionJankMonitor,
                deviceEntryHapticsInteractor,
                vibratorHelper,
                falsingManager,
                keyguardViewMediator,
                statusBarKeyguardViewManager,
                mainDispatcher,
                msdlPlayer,
            )
    }

    private fun createLockscreen(
        context: Context,
        viewModelFactory: LockscreenContentViewModel.Factory,
        blueprints: Set<@JvmSuppressWildcards LockscreenSceneBlueprint>,
    ): View {
        val sceneBlueprints =
            blueprints.mapNotNull { it as? ComposableLockscreenSceneBlueprint }.toSet()
        return ComposeView(context).apply {
            setContent {
                // STL is used solely to provide a SceneScope to enable us to invoke SceneScope
                // composables.
                val currentScene = remember { SceneKey("root-view-scene-key") }
                val state = remember { MutableSceneTransitionLayoutState(currentScene) }
                SceneTransitionLayout(state) {
                    scene(currentScene) {
                        with(
                            LockscreenContent(
                                viewModelFactory = viewModelFactory,
                                blueprints = sceneBlueprints,
                                clockInteractor = clockInteractor,
                            )
                        ) {
                            Content(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    /**
     * Temporary, to allow NotificationPanelViewController to use the same instance while code is
     * migrated: b/288242803
     */
    fun getKeyguardRootView() = keyguardRootView
}
