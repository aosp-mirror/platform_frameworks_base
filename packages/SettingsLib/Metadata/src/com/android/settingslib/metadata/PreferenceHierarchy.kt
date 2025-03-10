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

package com.android.settingslib.metadata

/** A node in preference hierarchy that is associated with [PreferenceMetadata]. */
open class PreferenceHierarchyNode internal constructor(val metadata: PreferenceMetadata) {
    /**
     * Preference order in the hierarchy.
     *
     * When a preference appears on different screens, different order values could be specified.
     */
    var order: Int? = null
        internal set
}

/**
 * Preference hierarchy describes the structure of preferences recursively.
 *
 * A root hierarchy represents a preference screen. A sub-hierarchy represents a preference group.
 */
class PreferenceHierarchy internal constructor(metadata: PreferenceMetadata) :
    PreferenceHierarchyNode(metadata) {

    private val children = mutableListOf<PreferenceHierarchyNode>()

    /** Adds a preference to the hierarchy. */
    operator fun PreferenceMetadata.unaryPlus() = +PreferenceHierarchyNode(this)

    /**
     * Adds preference screen with given key (as a placeholder) to the hierarchy.
     *
     * This is mainly to support Android Settings overlays. OEMs might want to custom some of the
     * screens. In resource-based hierarchy, it leverages the resource overlay. In terms of DSL or
     * programmatic hierarchy, it will be a problem to specify concrete screen metadata objects.
     * Instead, use preference screen key as a placeholder in the hierarchy and screen metadata will
     * be looked up from [PreferenceScreenRegistry] lazily at runtime.
     *
     * @throws NullPointerException if screen is not registered to [PreferenceScreenRegistry]
     */
    operator fun String.unaryPlus() = +PreferenceHierarchyNode(PreferenceScreenRegistry[this]!!)

    operator fun PreferenceHierarchyNode.unaryPlus() = also { children.add(it) }

    /** Specifies preference order in the hierarchy. */
    infix fun PreferenceHierarchyNode.order(order: Int) = apply { this.order = order }

    /** Specifies preference order in the hierarchy for group. */
    infix fun PreferenceHierarchy.order(order: Int) = apply { this.order = order }

    /** Adds a preference to the hierarchy. */
    @JvmOverloads
    fun add(metadata: PreferenceMetadata, order: Int? = null) {
        PreferenceHierarchyNode(metadata).also {
            it.order = order
            children.add(it)
        }
    }

    /** Adds a preference to the hierarchy before given key. */
    fun addBefore(key: String, metadata: PreferenceMetadata) {
        val (list, index) = findPreference(key) ?: (children to children.size)
        list.add(index, PreferenceHierarchyNode(metadata))
    }

    /** Adds a preference group to the hierarchy before given key. */
    fun addGroupBefore(key: String, metadata: PreferenceMetadata): PreferenceHierarchy {
        val (list, index) = findPreference(key) ?: (children to children.size)
        return PreferenceHierarchy(metadata).also { list.add(index, it) }
    }

    /** Adds a preference to the hierarchy after given key. */
    fun addAfter(key: String, metadata: PreferenceMetadata) {
        val (list, index) = findPreference(key) ?: (children to children.size - 1)
        list.add(index + 1, PreferenceHierarchyNode(metadata))
    }

    /** Adds a preference group to the hierarchy after given key. */
    fun addGroupAfter(key: String, metadata: PreferenceMetadata): PreferenceHierarchy {
        val (list, index) = findPreference(key) ?: (children to children.size - 1)
        return PreferenceHierarchy(metadata).also { list.add(index + 1, it) }
    }

    private fun findPreference(key: String): Pair<MutableList<PreferenceHierarchyNode>, Int>? {
        children.forEachIndexed { index, node ->
            if (node.metadata.key == key) return children to index
            if (node is PreferenceHierarchy) {
                val result = node.findPreference(key)
                if (result != null) return result
            }
        }
        return null
    }

    /** Adds a preference group to the hierarchy. */
    operator fun PreferenceGroup.unaryPlus() = PreferenceHierarchy(this).also { children.add(it) }

    /** Adds a preference group and returns its preference hierarchy. */
    @JvmOverloads
    fun addGroup(metadata: PreferenceGroup, order: Int? = null): PreferenceHierarchy =
        PreferenceHierarchy(metadata).also {
            this.order = order
            children.add(it)
        }

    /**
     * Adds preference screen with given key (as a placeholder) to the hierarchy.
     *
     * This is mainly to support Android Settings overlays. OEMs might want to custom some of the
     * screens. In resource-based hierarchy, it leverages the resource overlay. In terms of DSL or
     * programmatic hierarchy, it will be a problem to specify concrete screen metadata objects.
     * Instead, use preference screen key as a placeholder in the hierarchy and screen metadata will
     * be looked up from [PreferenceScreenRegistry] lazily at runtime.
     *
     * @throws NullPointerException if screen is not registered to [PreferenceScreenRegistry]
     */
    fun addPreferenceScreen(screenKey: String) {
        children.add(PreferenceHierarchy(PreferenceScreenRegistry[screenKey]!!))
    }

    /** Extensions to add more preferences to the hierarchy. */
    operator fun PreferenceHierarchy.plusAssign(init: PreferenceHierarchy.() -> Unit) = init(this)

    /** Traversals preference hierarchy and applies given action. */
    fun forEach(action: (PreferenceHierarchyNode) -> Unit) {
        for (it in children) action(it)
    }

    /** Traversals preference hierarchy recursively and applies given action. */
    fun forEachRecursively(action: (PreferenceHierarchyNode) -> Unit) {
        action(this)
        for (child in children) {
            if (child is PreferenceHierarchy) {
                child.forEachRecursively(action)
            } else {
                action(child)
            }
        }
    }

    /** Traversals preference hierarchy and applies given action. */
    suspend fun forEachAsync(action: suspend (PreferenceHierarchyNode) -> Unit) {
        for (it in children) action(it)
    }

    /** Finds the [PreferenceMetadata] object associated with given key. */
    fun find(key: String): PreferenceMetadata? {
        if (metadata.key == key) return metadata
        for (child in children) {
            if (child is PreferenceHierarchy) {
                val result = child.find(key)
                if (result != null) return result
            } else {
                if (child.metadata.key == key) return child.metadata
            }
        }
        return null
    }
}

/**
 * Builder function to create [PreferenceHierarchy] in
 * [DSL](https://kotlinlang.org/docs/type-safe-builders.html) manner.
 */
fun preferenceHierarchy(metadata: PreferenceMetadata, init: PreferenceHierarchy.() -> Unit) =
    PreferenceHierarchy(metadata).also(init)
