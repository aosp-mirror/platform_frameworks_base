package com.android.systemui.scene

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.logger.sceneLogger
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.FakeOverlay
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.scene.ui.viewmodel.splitEdgeDetector
import com.android.systemui.shade.domain.interactor.shadeInteractor
import kotlinx.coroutines.flow.MutableStateFlow

var Kosmos.sceneKeys by Fixture {
    listOf(
        Scenes.QuickSettings,
        Scenes.Shade,
        Scenes.Lockscreen,
        Scenes.Bouncer,
        Scenes.Gone,
        Scenes.Communal,
    )
}

val Kosmos.initialSceneKey by Fixture { Scenes.Lockscreen }

var Kosmos.overlayKeys by Fixture {
    listOf(
        Overlays.NotificationsShade,
        Overlays.QuickSettingsShade,
    )
}

val Kosmos.fakeOverlaysByKeys by Fixture { overlayKeys.associateWith { FakeOverlay(it) } }

val Kosmos.fakeOverlays by Fixture { fakeOverlaysByKeys.values.toSet() }

val Kosmos.overlays by Fixture { fakeOverlays }

var Kosmos.sceneContainerConfig by Fixture {
    val navigationDistances =
        mapOf(
            Scenes.Gone to 0,
            Scenes.Lockscreen to 0,
            Scenes.Communal to 1,
            Scenes.Shade to 2,
            Scenes.QuickSettings to 3,
            Scenes.Bouncer to 4,
        )

    SceneContainerConfig(
        sceneKeys = sceneKeys,
        initialSceneKey = initialSceneKey,
        overlayKeys = overlayKeys,
        navigationDistances = navigationDistances,
    )
}

val Kosmos.transitionState by Fixture {
    MutableStateFlow<ObservableTransitionState>(
        ObservableTransitionState.Idle(sceneContainerConfig.initialSceneKey)
    )
}

val Kosmos.sceneContainerViewModel by Fixture {
    SceneContainerViewModel(
            sceneInteractor = sceneInteractor,
            falsingInteractor = falsingInteractor,
            powerInteractor = powerInteractor,
            shadeInteractor = shadeInteractor,
            splitEdgeDetector = splitEdgeDetector,
            motionEventHandlerReceiver = {},
            logger = sceneLogger
        )
        .apply { setTransitionState(transitionState) }
}
