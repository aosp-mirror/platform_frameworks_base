/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.shared.flag

import androidx.annotation.VisibleForTesting
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.Flags.keyguardBottomAreaRefactor
import com.android.systemui.Flags.sceneContainer
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flag
import com.android.systemui.flags.Flags
import com.android.systemui.flags.ReleasedFlag
import com.android.systemui.flags.ResourceBooleanFlag
import com.android.systemui.flags.UnreleasedFlag
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * Defines interface for classes that can check whether the scene container framework feature is
 * enabled.
 */
interface SceneContainerFlags {

    /** Returns `true` if the Scene Container Framework is enabled; `false` otherwise. */
    fun isEnabled(): Boolean

    /** Returns a developer-readable string that describes the current requirement list. */
    fun requirementDescription(): String
}

class SceneContainerFlagsImpl
@AssistedInject
constructor(
    private val featureFlagsClassic: FeatureFlagsClassic,
    @Assisted private val isComposeAvailable: Boolean,
) : SceneContainerFlags {

    companion object {
        @VisibleForTesting
        val classicFlagTokens: List<Flag<Boolean>> =
            listOf(
                Flags.MIGRATE_KEYGUARD_STATUS_BAR_VIEW,
            )
    }

    /** The list of requirements, all must be met for the feature to be enabled. */
    private val requirements =
        listOf(
            AconfigFlagMustBeEnabled(
                flagName = AConfigFlags.FLAG_SCENE_CONTAINER,
                flagValue = sceneContainer(),
            ),
            AconfigFlagMustBeEnabled(
                flagName = AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR,
                flagValue = keyguardBottomAreaRefactor(),
            ),
            AconfigFlagMustBeEnabled(
                flagName = KeyguardShadeMigrationNssl.FLAG_NAME,
                flagValue = KeyguardShadeMigrationNssl.isEnabled,
            ),
        ) +
            classicFlagTokens.map { flagToken -> FlagMustBeEnabled(flagToken) } +
            listOf(ComposeMustBeAvailable(), CompileTimeFlagMustBeEnabled())

    override fun isEnabled(): Boolean {
        // SCENE_CONTAINER_ENABLED is an explicit static flag check that helps with downstream
        // optimizations, e.g., unused code stripping. Do not remove!
        return Flags.SCENE_CONTAINER_ENABLED && requirements.all { it.isMet() }
    }

    override fun requirementDescription(): String {
        return buildString {
            requirements.forEach { requirement ->
                append('\n')
                append(if (requirement.isMet()) "    [MET]" else "[NOT MET]")
                append(" ${requirement.name}")
            }
        }
    }

    private interface Requirement {
        val name: String

        fun isMet(): Boolean
    }

    private inner class ComposeMustBeAvailable : Requirement {
        override val name = "Jetpack Compose must be available"

        override fun isMet(): Boolean {
            return isComposeAvailable
        }
    }

    private inner class CompileTimeFlagMustBeEnabled : Requirement {
        override val name = "Flags.SCENE_CONTAINER_ENABLED must be enabled in code"

        override fun isMet(): Boolean {
            return Flags.SCENE_CONTAINER_ENABLED
        }
    }

    private inner class FlagMustBeEnabled<FlagType : Flag<*>>(
        private val flag: FlagType,
    ) : Requirement {
        override val name = "Flag ${flag.name} must be enabled"

        override fun isMet(): Boolean {
            return when (flag) {
                is ResourceBooleanFlag -> featureFlagsClassic.isEnabled(flag)
                is ReleasedFlag -> featureFlagsClassic.isEnabled(flag)
                is UnreleasedFlag -> featureFlagsClassic.isEnabled(flag)
                else -> error("Unsupported flag type ${flag.javaClass}")
            }
        }
    }

    private inner class AconfigFlagMustBeEnabled(
        flagName: String,
        private val flagValue: Boolean,
    ) : Requirement {
        override val name: String = "Aconfig flag $flagName must be enabled"

        override fun isMet(): Boolean {
            return flagValue
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(isComposeAvailable: Boolean): SceneContainerFlagsImpl
    }
}

@Module
object SceneContainerFlagsModule {

    @Provides
    @SysUISingleton
    fun impl(factory: SceneContainerFlagsImpl.Factory): SceneContainerFlags {
        return factory.create(ComposeFacade.isComposeAvailable())
    }
}
