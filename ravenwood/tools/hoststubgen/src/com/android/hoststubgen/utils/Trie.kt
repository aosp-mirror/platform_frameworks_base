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
package com.android.hoststubgen.utils

abstract class Trie<Key, Component, Value> {

    private val root = TrieNode<Component, Value>()

    abstract fun splitToComponents(key: Key): Iterator<Component>

    operator fun set(key: Key, value: Value) {
        val node = root.getExactNode(splitToComponents(key))
        node.value = value
    }

    operator fun get(key: Key): Value? {
        return root.getNearestValue(null, splitToComponents(key))
    }

    private class TrieNode<Component, Value> {
        private val children = mutableMapOf<Component, TrieNode<Component, Value>>()
        var value: Value? = null

        fun getExactNode(components: Iterator<Component>): TrieNode<Component, Value> {
            val n = components.next()
            val child = children.getOrPut(n) { TrieNode() }
            return if (components.hasNext()) {
                child.getExactNode(components)
            } else {
                child
            }
        }

        fun getNearestValue(current: Value?, components: Iterator<Component>): Value? {
            val n = components.next()
            val child = children[n] ?: return current
            val newValue = child.value ?: current
            return if (components.hasNext()) {
                child.getNearestValue(newValue, components)
            } else {
                newValue
            }
        }
    }
}
