package com.android.systemui.scene

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
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

val Kosmos.initialSceneKey by Fixture { Scenes.Lockscreen }
val Kosmos.sceneContainerConfig by Fixture { SceneContainerConfig(sceneKeys, initialSceneKey) }
