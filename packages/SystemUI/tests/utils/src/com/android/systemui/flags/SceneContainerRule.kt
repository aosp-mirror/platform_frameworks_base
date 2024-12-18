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
import org.junit.AssumptionViolatedException
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
                if (description.hasAnnotation<EnableSceneContainer>()) {
                    Assert.assertTrue(
                        "SceneContainerFlag.isEnabled is false:" +
                            "\n * Did you forget to add a new aconfig flag dependency in" +
                            " @EnableSceneContainer?" +
                            "\n * Did you forget to use SetFlagsRule with an earlier order?",
                        SceneContainerFlag.isEnabled
                    )
                }
                // Get the flag value, treating the unset error as false.
                val sceneContainerAconfigEnabled = try {
                    com.android.systemui.Flags.sceneContainer()
                } catch (e: Exception) {
                    false
                }
                if (sceneContainerAconfigEnabled) {
                    Assert.assertTrue(
                            "FLAG_SCENE_CONTAINER is enabled but SceneContainerFlag.isEnabled" +
                                    " is false.  Use `.andSceneContainer()` from" +
                                    " SceneContainerFlagParameterization.kt to parameterize this" +
                                    " flag correctly.",
                            SceneContainerFlag.isEnabled
                    )
                }
                if (
                    description.hasAnnotation<BrokenWithSceneContainer>() &&
                        SceneContainerFlag.isEnabled
                ) {
                    runCatching { base?.evaluate() }
                        .onFailure { exception ->
                            if (exception is AssumptionViolatedException) {
                                throw AssertionError(
                                    "This is marked @BrokenWithSceneContainer, but was skipped.",
                                    exception
                                )
                            }
                            throw AssumptionViolatedException("Test is still broken", exception)
                        }
                    throw AssertionError(
                        "HOORAY! You fixed a test that was marked @BrokenWithSceneContainer. " +
                            "Remove the obsolete annotation to fix this failure."
                    )
                }
                base?.evaluate()
            }
        }
    }

    inline fun <reified T : Annotation> Description?.hasAnnotation(): Boolean =
        this?.testClass?.getAnnotation(T::class.java) != null ||
            this?.getAnnotation(T::class.java) != null
}
