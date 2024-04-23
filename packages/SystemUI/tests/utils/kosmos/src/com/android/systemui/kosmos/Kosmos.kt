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

package com.android.systemui.kosmos

import kotlin.reflect.KProperty

// (Historical note: The name Kosmos is meant to invoke "Kotlin", the "Object Mother" pattern
//   (https://martinfowler.com/bliki/ObjectMother.html), and of course the Greek word "kosmos" for
//   the "order of the world" (https://en.wiktionary.org/wiki/%CE%BA%CF%8C%CF%83%CE%BC%CE%BF%CF%82)

/**
 * Each Kosmos is its own self-contained set of fixtures, which may reference each other. Fixtures
 * can be defined through extension properties in any file:
 * ```
 * // fixture that must be set:
 * var Kosmos.context by Fixture<Context>()
 *
 * // fixture with overrideable default.
 * var Kosmos.landscapeMode by Fixture { false }
 *
 * // fixture forbidding override (note `val`, and referencing context fixture from above)
 * val Kosmos.lifecycleScope by Fixture { context.lifecycleScope }
 * ```
 *
 * To use the fixtures, create an instance of Kosmos and retrieve the values you need:
 * ```
 * val k = Kosmos()
 * k.context = mContext
 * val underTest = YourInteractor(
 *     context = k.context,
 *     landscapeMode = k.landscapeMode,
 * )
 * ```
 */
interface Kosmos {
    /**
     * Lookup a fixture in the Kosmos by [name], using [creator] to instantiate and store one if
     * there is none present.
     */
    fun <T> get(name: String, creator: (Kosmos.() -> T)?): T

    /** Sets the [value] of a fixture with the given [name]. */
    fun set(name: String, value: Any?)

    /**
     * A value in the kosmos that has a single value once it's read. It can be overridden before
     * first use only; all objects that are dependent on this fixture will get the same value.
     *
     * Example classic uses would be a clock, filesystem, or singleton controller.
     *
     * If no [creator] parameter is provided, the fixture must be set before use.
     */
    class Fixture<T>(private val creator: (Kosmos.() -> T)? = null) {
        operator fun getValue(thisRef: Kosmos, property: KProperty<*>): T =
            thisRef.get(property.name, creator)

        operator fun setValue(thisRef: Kosmos, property: KProperty<*>, value: T) {
            thisRef.set(property.name, value)
        }
    }
}

/** Constructs a fresh Kosmos. */
fun Kosmos(): Kosmos = KosmosRegistry()

private class KosmosRegistry : Kosmos {
    val map: MutableMap<String, Any?> = mutableMapOf()
    val gotten: MutableSet<String> = mutableSetOf()

    override fun <T> get(name: String, creator: (Kosmos.() -> T)?): T {
        gotten.add(name)
        if (name !in map) {
            checkNotNull(creator) { "Fixture $name has no default, and is read before set." }
            map[name] = creator()
        }
        @Suppress("UNCHECKED_CAST") return map[name] as T
    }

    override fun set(name: String, value: Any?) {
        check(name !in gotten) { "Tried to set fixture '$name' after it's already been read." }
        map[name] = value
    }
}
