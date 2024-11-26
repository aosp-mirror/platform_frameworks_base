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

package com.android.compose.animation.scene.transformation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.testTransition
import com.android.compose.test.assertSizeIsEqualTo
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTransformationTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun customSize() {
        /** A size transformation that adds [add] to the size of the transformed element(s). */
        class AddSizeTransformation(private val add: Dp) : CustomPropertyTransformation<IntSize> {
            override val property = PropertyTransformation.Property.Size

            override fun PropertyTransformationScope.transform(
                content: ContentKey,
                element: ElementKey,
                transition: TransitionState.Transition,
                transitionScope: CoroutineScope,
            ): IntSize {
                val idleSize = checkNotNull(element.targetSize(content))
                val progress = 1f - transition.progressTo(content)
                val addPx = (add * progress).roundToPx()
                return IntSize(width = idleSize.width + addPx, height = idleSize.height + addPx)
            }
        }

        rule.testTransition(
            fromSceneContent = { Box(Modifier.element(TestElements.Foo).size(40.dp, 20.dp)) },
            toSceneContent = {},
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)

                // Add 80dp to the width and height of Foo.
                transformation(TestElements.Foo) { AddSizeTransformation(80.dp) }
            },
        ) {
            before { onElement(TestElements.Foo).assertSizeIsEqualTo(40.dp, 20.dp) }
            at(0) { onElement(TestElements.Foo).assertSizeIsEqualTo(40.dp, 20.dp) }
            at(16) { onElement(TestElements.Foo).assertSizeIsEqualTo(60.dp, 40.dp) }
            at(32) { onElement(TestElements.Foo).assertSizeIsEqualTo(80.dp, 60.dp) }
            at(48) { onElement(TestElements.Foo).assertSizeIsEqualTo(100.dp, 80.dp) }
            after { onElement(TestElements.Foo).assertDoesNotExist() }
        }
    }

    @Test
    fun customOffset() {
        /** An offset transformation that adds [add] to the offset of the transformed element(s). */
        class AddOffsetTransformation(private val add: Dp) : CustomPropertyTransformation<Offset> {
            override val property = PropertyTransformation.Property.Offset

            override fun PropertyTransformationScope.transform(
                content: ContentKey,
                element: ElementKey,
                transition: TransitionState.Transition,
                transitionScope: CoroutineScope,
            ): Offset {
                val idleOffset = checkNotNull(element.targetOffset(content))
                val progress = 1f - transition.progressTo(content)
                val addPx = (add * progress).toPx()
                return Offset(x = idleOffset.x + addPx, y = idleOffset.y + addPx)
            }
        }

        rule.testTransition(
            fromSceneContent = { Box(Modifier.element(TestElements.Foo)) },
            toSceneContent = {},
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)

                // Add 80dp to the offset of Foo.
                transformation(TestElements.Foo) { AddOffsetTransformation(80.dp) }
            },
        ) {
            before { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(0.dp, 0.dp) }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(0.dp, 0.dp) }
            at(16) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(20.dp, 20.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(40.dp, 40.dp) }
            at(48) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(60.dp, 60.dp) }
            after { onElement(TestElements.Foo).assertDoesNotExist() }
        }
    }
}
