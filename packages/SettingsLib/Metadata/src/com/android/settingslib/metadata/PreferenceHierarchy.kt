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
open class PreferenceHierarchyNode internal constructor(val metadata: PreferenceMetadata)

/**
 * Preference hierarchy describes the structure of preferences recursively.
 *
 * A root hierarchy represents a preference screen. A sub-hierarchy represents a preference group.
 */
class PreferenceHierarchy internal constructor(metadata: PreferenceMetadata) :
    PreferenceHierarchyNode(metadata) {

    private val children = mutableListOf<PreferenceHierarchyNode>()

    /** Adds a preference to the hierarchy. */
    operator fun PreferenceMetadata.unaryPlus() {
        children.add(PreferenceHierarchyNode(this))
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
    operator fun String.unaryPlus() {
        children.add(PreferenceHierarchyNode(PreferenceScreenRegistry[this]!!))
    }

    /** Adds a preference to the hierarchy. */
    fun add(metadata: PreferenceMetadata) {
        children.add(PreferenceHierarchyNode(metadata))
    }

    /** Adds a preference group to the hierarchy. */
    operator fun PreferenceGroup.unaryPlus() = PreferenceHierarchy(this).also { children.add(it) }

    /** Adds a preference group and returns its preference hierarchy. */
    fun addGroup(metadata: PreferenceGroup): PreferenceHierarchy =
        PreferenceHierarchy(metadata).also { children.add(it) }

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
    operator fun plusAssign(init: PreferenceHierarchy.() -> Unit) = init(this)

    /** Traversals preference hierarchy and applies given action. */
    fun forEach(action: (PreferenceHierarchyNode) -> Unit) {
        for (it in children) action(it)
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

    /** Returns all the [PreferenceMetadata]s appear in the hierarchy. */
    fun getAllPreferences(): List<PreferenceMetadata> =
        mutableListOf<PreferenceMetadata>().also { getAllPreferences(it) }

    private fun getAllPreferences(result: MutableList<PreferenceMetadata>) {
        result.add(metadata)
        for (child in children) {
            if (child is PreferenceHierarchy) {
                child.getAllPreferences(result)
            } else {
                result.add(child.metadata)
            }
        }
    }
}

/**
 * Builder function to create [PreferenceHierarchy] in
 * [DSL](https://kotlinlang.org/docs/type-safe-builders.html) manner.
 */
fun preferenceHierarchy(metadata: PreferenceMetadata, init: PreferenceHierarchy.() -> Unit) =
    PreferenceHierarchy(metadata).also(init)
