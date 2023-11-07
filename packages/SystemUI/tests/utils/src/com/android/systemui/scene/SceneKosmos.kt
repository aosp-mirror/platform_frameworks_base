package com.android.systemui.scene

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey

var Kosmos.sceneKeys by Fixture {
    listOf(
        SceneKey.QuickSettings,
        SceneKey.Shade,
        SceneKey.Lockscreen,
        SceneKey.Bouncer,
        SceneKey.Gone,
        SceneKey.Communal,
    )
}

val Kosmos.initialSceneKey by Fixture { SceneKey.Lockscreen }
val Kosmos.sceneContainerConfig by Fixture { SceneContainerConfig(sceneKeys, initialSceneKey) }
