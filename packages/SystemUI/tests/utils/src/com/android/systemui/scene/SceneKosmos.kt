package com.android.systemui.scene

import com.android.compose.animation.scene.OverlayKey
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.scene.shared.model.FakeScene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.FakeOverlay

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

val Kosmos.fakeScenes by Fixture { sceneKeys.map { key -> FakeScene(key) }.toSet() }

val Kosmos.scenes by Fixture { fakeScenes }

val Kosmos.initialSceneKey by Fixture { Scenes.Lockscreen }

var Kosmos.overlayKeys by Fixture {
    listOf<OverlayKey>(
        // TODO(b/356596436): Add overlays here when we have them.
    )
}

val Kosmos.fakeOverlays by Fixture { overlayKeys.map { key -> FakeOverlay(key) }.toSet() }

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
