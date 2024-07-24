/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.flags

import android.util.Log
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import org.junit.Assert
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Should always be used with [SetFlagsRule] and should be ordered after it.
 *
 * Used to ensure tests annotated with [EnableSceneContainer] can actually get `true` from
 * [SceneContainerFlag.isEnabled].
 */
class SceneContainerRule : TestRule {
    override fun apply(base: Statement?, description: Description?): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val initialEnabledValue = Flags.SCENE_CONTAINER_ENABLED
                val hasAnnotation =
                    description?.testClass?.getAnnotation(EnableSceneContainer::class.java) !=
                        null || description?.getAnnotation(EnableSceneContainer::class.java) != null
                if (hasAnnotation) {
                    Assume.assumeTrue(
                        "Couldn't set Flags.SCENE_CONTAINER_ENABLED for @EnableSceneContainer test",
                        trySetSceneContainerEnabled(true)
                    )
                    Assert.assertTrue(
                        "SceneContainerFlag.isEnabled is false:" +
                            "\n * Did you forget to add a new aconfig flag dependency in" +
                            " @EnableSceneContainer?" +
                            "\n * Did you forget to use SetFlagsRule with an earlier order?",
                        SceneContainerFlag.isEnabled
                    )
                }
                try {
                    base?.evaluate()
                } finally {
                    if (hasAnnotation) {
                        trySetSceneContainerEnabled(initialEnabledValue)
                    }
                }
            }
        }
    }

    companion object {
        fun trySetSceneContainerEnabled(enabled: Boolean): Boolean {
            if (Flags.SCENE_CONTAINER_ENABLED == enabled) {
                return true
            }
            return try {
                // TODO(b/283300105): remove this reflection setting once the hard-coded
                //  Flags.SCENE_CONTAINER_ENABLED is no longer needed.
                val field = Flags::class.java.getField("SCENE_CONTAINER_ENABLED")
                field.isAccessible = true
                field.set(null, enabled) // note: this does not work with multivalent tests
                true
            } catch (t: Throwable) {
                Log.e("SceneContainerRule", "Unable to set SCENE_CONTAINER_ENABLED=$enabled", t)
                false
            }
        }
    }
}
