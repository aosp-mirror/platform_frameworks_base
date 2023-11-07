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
class Kosmos {
    private val map: MutableMap<String, Any?> = mutableMapOf()
    private val gotten: MutableSet<String> = mutableSetOf()

    /**
     * A value in the kosmos that has a single value once it's read. It can be overridden before
     * first use only; all objects that are dependent on this fixture will get the same value.
     *
     * Example classic uses would be a clock, filesystem, or singleton controller.
     *
     * If no [creator] parameter is provided, the fixture must be set before use.
     */
    class Fixture<T>(private val creator: (Kosmos.() -> T)? = null) {
        operator fun getValue(thisRef: Kosmos, property: KProperty<*>): T {
            thisRef.gotten.add(property.name)
            @Suppress("UNCHECKED_CAST")
            if (!thisRef.map.contains(property.name)) {
                if (creator == null) {
                    throw IllegalStateException(
                        "Fixture ${property.name} has no default, and is read before set."
                    )
                } else {
                    val nonNullCreator = creator
                    // The Kotlin compiler seems to need this odd workaround
                    thisRef.map[property.name] = thisRef.nonNullCreator()
                }
            }
            return thisRef.map[property.name] as T
        }

        operator fun setValue(thisRef: Kosmos, property: KProperty<*>, value: T) {
            check(!thisRef.gotten.contains(property.name)) {
                "Tried to set fixture '${property.name}' after it's already been read."
            }
            thisRef.map[property.name] = value
        }
    }
}
