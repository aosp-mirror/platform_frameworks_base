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

package com.android.compose.animation.scene.testing

import androidx.compose.ui.semantics.SemanticsNode
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.Element.Companion.AlphaUnspecified
import com.android.compose.animation.scene.ElementModifier
import com.android.compose.animation.scene.Scale

val SemanticsNode.lastAlphaForTesting: Float?
    get() = elementState.lastAlpha.takeIf { it != AlphaUnspecified }

val SemanticsNode.lastScaleForTesting: Scale?
    get() = elementState.lastScale.takeIf { it != Scale.Unspecified }

private val SemanticsNode.elementState: Element.State
    get() {
        val elementModifier =
            layoutInfo
                .getModifierInfo()
                .map { it.modifier }
                .filterIsInstance<ElementModifier>()
                .firstOrNull()
        requireNotNull(elementModifier) {
            "No ElementModifier found. Did you use the Modifier.element(...)?"
        }
        return with(elementModifier) {
            val element =
                requireNotNull(layoutImpl.elements[key]) {
                    "No element found in STL layout with key: ${key.testTag}"
                }
            requireNotNull(element.stateByContent[content.key]) {
                "No state found for given content key: ${content.key.testTag}"
            }
        }
    }
