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

package com.android.systemui.scene.domain.resolver

import com.android.compose.animation.scene.SceneKey
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.StateFlow

/** Resolves [concrete scenes][Scenes] from a [scene family][SceneFamilies]. */
interface SceneResolver {
    /** The scene family that this resolves. */
    val targetFamily: SceneKey

    /** The concrete scene that [targetFamily] is currently resolved to. */
    val resolvedScene: StateFlow<SceneKey>

    /** Returns `true` if [scene] can be resolved from [targetFamily]. */
    fun includesScene(scene: SceneKey): Boolean
}

@Module
interface SceneResolverModule {

    @Multibinds fun resolverSet(): Set<@JvmSuppressWildcards SceneResolver>

    companion object {
        @Provides
        fun provideResolverMap(
            resolverSet: Set<@JvmSuppressWildcards SceneResolver>
        ): Map<SceneKey, SceneResolver> = resolverSet.associateBy { it.targetFamily }
    }
}
