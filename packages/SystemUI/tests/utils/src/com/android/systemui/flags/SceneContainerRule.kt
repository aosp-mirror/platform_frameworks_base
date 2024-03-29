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

import com.android.systemui.scene.shared.flag.SceneContainerFlag
import org.junit.Assert
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Should always be used with `SetFlagsRule` and should be ordered after it.
 *
 * Used to ensure tests annotated with [EnableSceneContainer] can actually get `true` from
 * [SceneContainerFlag.isEnabled].
 */
class SceneContainerRule : TestRule {
    override fun apply(base: Statement?, description: Description?): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val hasAnnotation =
                    description?.testClass?.getAnnotation(EnableSceneContainer::class.java) !=
                        null || description?.getAnnotation(EnableSceneContainer::class.java) != null
                if (hasAnnotation) {
                    Assert.assertTrue(
                        "SceneContainerFlag.isEnabled is false:" +
                            "\n * Did you forget to add a new aconfig flag dependency in" +
                            " @EnableSceneContainer?" +
                            "\n * Did you forget to use SetFlagsRule with an earlier order?",
                        SceneContainerFlag.isEnabled
                    )
                }
                base?.evaluate()
            }
        }
    }
}
