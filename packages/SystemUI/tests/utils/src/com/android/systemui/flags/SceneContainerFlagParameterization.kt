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

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER

/** The name of the one flag to be disabled for OFF parameterization */
private const val flagNameToDisable = FLAG_SCENE_CONTAINER

/** Cache of the flags to be enabled for ON parameterization */
private val flagNamesToEnable =
    EnableSceneContainer::class.java.getAnnotation(EnableFlags::class.java)!!.value.toList()

/**
 * Provides one or two copies of this [FlagsParameterization]; one which disabled
 * [FLAG_SCENE_CONTAINER] and if none of the dependencies of it are disabled by this, a second copy
 * which enables [FLAG_SCENE_CONTAINER] and all the dependencies (just like [EnableSceneContainer]).
 */
fun FlagsParameterization.andSceneContainer(): Sequence<FlagsParameterization> = sequence {
    check(flagNameToDisable !in mOverrides) {
        "Can't add $flagNameToDisable to FlagsParameterization: $this"
    }
    yield(FlagsParameterization(mOverrides + mapOf(flagNameToDisable to false)))
    if (flagNamesToEnable.all { mOverrides[it] != false }) {
        // Can't add the parameterization of enabling SceneContainerFlag to a parameterization that
        // explicitly disables one of the prerequisite flags.
        yield(FlagsParameterization(mOverrides + flagNamesToEnable.associateWith { true }))
    }
}

/**
 * Doubles (roughly; see below) the given list of [FlagsParameterization] for enabling and disabling
 * SceneContainerFlag.
 *
 * The input parameterization may not define [FLAG_SCENE_CONTAINER].
 *
 * Any [FlagsParameterization] which disables any flag that is a dependency of
 * [FLAG_SCENE_CONTAINER], will not add a state for enabling, and the state will simply be converted
 * to one which disables. Just like [EnableSceneContainer], enabling will also enable all the other
 * dependencies. For any flag parameterization where a dependency is disabled, an "enabled"
 * parameterization is inconsistent, so it will not be added.
 */
fun List<FlagsParameterization>.andSceneContainer(): List<FlagsParameterization> =
    flatMap { it.andSceneContainer() }.toList()

/** Parameterizes only the scene container flag. */
fun parameterizeSceneContainerFlag(): List<FlagsParameterization> {
    return FlagsParameterization.allCombinationsOf().andSceneContainer()
}
