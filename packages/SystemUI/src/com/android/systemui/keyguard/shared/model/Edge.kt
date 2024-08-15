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
 * limitations under the License
 */
package com.android.systemui.keyguard.shared.model

import android.util.Log
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.keyguard.shared.model.KeyguardState.UNDEFINED
import com.android.systemui.scene.shared.flag.SceneContainerFlag

/**
 * Represents an edge either between two Keyguard Transition Framework states (KTF) or a KTF state
 * and a scene container scene. Passing [null] in either [from] or [to] indicates a wildcard.
 */
sealed class Edge {

    fun verifyValidKeyguardStates() {
        when (this) {
            is StateToState -> verifyValidKeyguardStates(from, to)
            is SceneToState -> verifyValidKeyguardStates(null, to)
            is StateToScene -> verifyValidKeyguardStates(from, null)
        }
    }

    private fun verifyValidKeyguardStates(from: KeyguardState?, to: KeyguardState?) {
        val mappedFrom = from?.mapToSceneContainerState()
        val mappedTo = to?.mapToSceneContainerState()

        val fromChanged = from != mappedFrom
        val toChanged = to != mappedTo

        if (SceneContainerFlag.isEnabled) {
            if (fromChanged && toChanged) {
                // TODO:(b/330311871) As we come close to having all current edges converted these
                //  error messages can be converted to throw such that future developers fail early
                //  when they introduce invalid edges.
                Log.e(
                    TAG,
                    """
                    The edge ${from?.name} => ${to?.name} was automatically converted to
                    ${mappedFrom?.name} => ${mappedTo?.name} but does not exist anymore in KTF.
                    Please remove or port this edge to scene container."""
                        .trimIndent(),
                )
            } else if (fromChanged || toChanged) {
                Log.w(
                    TAG,
                    """
                    The edge ${from?.name} => ${to?.name} was automatically converted to
                    ${mappedFrom?.name} => ${mappedTo?.name} it probably exists but needs explicit
                    conversion. Please remove or port this edge to scene container."""
                        .trimIndent(),
                )
            }
        } else {
            if (from == UNDEFINED || to == UNDEFINED) {
                Log.e(
                    TAG,
                    "UNDEFINED should not be used when scene container is disabled",
                )
            }
        }
    }

    fun isSceneWildcardEdge(): Boolean {
        return when (this) {
            is StateToState -> false
            is SceneToState -> to == null
            is StateToScene -> from == null
        }
    }

    data class StateToState(val from: KeyguardState?, val to: KeyguardState?) : Edge() {

        init {
            check(!(from == null && to == null)) { "to and from can't both be null" }
        }
    }

    data class StateToScene(val from: KeyguardState? = null, val to: SceneKey) : Edge()

    data class SceneToState(val from: SceneKey, val to: KeyguardState? = null) : Edge()

    companion object {
        private const val TAG = "Edge"

        @JvmStatic
        @JvmOverloads
        fun create(from: KeyguardState? = null, to: KeyguardState? = null) = StateToState(from, to)

        @JvmStatic
        @JvmOverloads
        fun create(from: KeyguardState? = null, to: SceneKey) = StateToScene(from, to)

        @JvmStatic
        @JvmOverloads
        fun create(from: SceneKey, to: KeyguardState? = null) = SceneToState(from, to)

        /**
         * This edge is a placeholder for when an edge needs to be passed but there is no edge for
         * this flag configuration available. Usually for Scene <-> Scene edges with scene container
         * enabled where these edges are managed by STL separately.
         */
        val INVALID = StateToState(UNDEFINED, UNDEFINED)
    }
}
