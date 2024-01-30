package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.SceneKey as SceneTransitionSceneKey
import com.android.systemui.scene.shared.model.SceneKey

val Lockscreen = SceneKey.Lockscreen.toTransitionSceneKey()
val Bouncer = SceneKey.Bouncer.toTransitionSceneKey()
val Shade = SceneKey.Shade.toTransitionSceneKey()
val QuickSettings = SceneKey.QuickSettings.toTransitionSceneKey()
val Gone = SceneKey.Gone.toTransitionSceneKey()
val Communal = SceneKey.Communal.toTransitionSceneKey()

// TODO(b/293899074): Remove this file once we can use the scene keys from SceneTransitionLayout.
fun SceneKey.toTransitionSceneKey(): SceneTransitionSceneKey {
    return SceneTransitionSceneKey(debugName = toString(), identity = this)
}
