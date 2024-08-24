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

package com.android.systemui.scene.data.model

import com.android.compose.animation.scene.SceneKey

/** An immutable stack of [SceneKey]s backed by a singly-linked list. */
sealed interface SceneStack

private data object EmptyStack : SceneStack

private data class StackedNodes(val head: SceneKey, val tail: SceneStack) : SceneStack

/** Returns the scene at the head of the stack, or `null` if empty. O(1) */
fun SceneStack.peek(): SceneKey? =
    when (this) {
        EmptyStack -> null
        is StackedNodes -> head
    }

/** Returns a stack with the head removed, or `null` if empty. O(1) */
fun SceneStack.pop(): SceneStack? =
    when (this) {
        EmptyStack -> null
        is StackedNodes -> tail
    }

/** Returns a stack with [sceneKey] as the head on top of [this]. O(1) */
fun SceneStack.push(sceneKey: SceneKey): SceneStack = StackedNodes(sceneKey, this)

/** Returns an iterable that produces all elements in the stack, from head to tail. */
fun SceneStack.asIterable(): Iterable<SceneKey> = Iterable {
    iterator {
        when (this@asIterable) {
            EmptyStack -> {}
            is StackedNodes -> {
                yield(head)
                yieldAll(tail.asIterable())
            }
        }
    }
}

/** Does this stack contain the given [sceneKey]? O(N) */
fun SceneStack.contains(sceneKey: SceneKey): Boolean = asIterable().any { it == sceneKey }

/**
 * Returns a new [SceneStack] containing the given [scenes], ordered such that the first argument is
 * the head returned from [peek], then the second, and so forth.
 */
fun sceneStackOf(vararg scenes: SceneKey): SceneStack {
    var result: SceneStack = EmptyStack
    for (sceneKey in scenes.reversed()) {
        result = result.push(sceneKey)
    }
    return result
}
