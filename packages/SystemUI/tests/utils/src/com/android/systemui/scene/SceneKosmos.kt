package com.android.systemui.scene

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.model.FakeScene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.Scenes

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

val Kosmos.fakeScenes by Fixture {
    sceneKeys
        .map { key ->
            FakeScene(
                scope = testScope.backgroundScope,
                key = key,
            )
        }
        .toSet()
}

val Kosmos.scenes by Fixture { fakeScenes }

val Kosmos.initialSceneKey by Fixture { Scenes.Lockscreen }
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
    SceneContainerConfig(sceneKeys, initialSceneKey, navigationDistances)
}
