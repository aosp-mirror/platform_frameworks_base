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
 */

package com.android.compose.animation.scene

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/** Scenes keys that can be reused by tests. */
object TestScenes {
    val SceneA = SceneKey("SceneA")
    val SceneB = SceneKey("SceneB")
    val SceneC = SceneKey("SceneC")
    val SceneD = SceneKey("SceneD")
}

/** Element keys that can be reused by tests. */
object TestElements {
    val Foo = ElementKey("Foo")
    val Bar = ElementKey("Bar")
}

/** Value keys that can be reused by tests. */
object TestValues {
    val Value1 = ValueKey("Value1")
    val Value2 = ValueKey("Value2")
    val Value3 = ValueKey("Value3")
    val Value4 = ValueKey("Value4")
}

// We use a transition duration of 480ms here because it is a multiple of 16, the time of a frame in
// C JVM/Android. Doing so allows us for instance to test the state at progress = 0.5f given that t
// = 240ms is also a multiple of 16.
val TestTransitionDuration = 480L

/** A definition of empty transitions between [TestScenes], using different animation specs. */
val EmptyTestTransitions = transitions {
    from(TestScenes.SceneA, to = TestScenes.SceneB) {
        spec = tween(durationMillis = TestTransitionDuration.toInt(), easing = LinearEasing)
    }

    from(TestScenes.SceneB, to = TestScenes.SceneC) {
        spec = tween(durationMillis = TestTransitionDuration.toInt(), easing = FastOutSlowInEasing)
    }

    from(TestScenes.SceneC, to = TestScenes.SceneA) { spec = snap() }
}
