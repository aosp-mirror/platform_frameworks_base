package com.android.sdkparcelables

/** A class that uses an ancestor map to find all classes that
 * implement android.os.Parcelable, including indirectly through
 * super classes or super interfaces.
 */
class ParcelableDetector {
    companion object {
        fun ancestorsToParcelables(ancestors: Map<String, Ancestors>): List<String> {
            val impl = Impl(ancestors)
            impl.build()
            return impl.parcelables
        }
    }

    private class Impl(val ancestors: Map<String, Ancestors>) {
        val isParcelableCache = HashMap<String, Boolean>()
        val parcelables = ArrayList<String>()

        fun build() {
            val classList = ancestors.keys
            classList.filterTo(parcelables, this::isParcelable)
            parcelables.sort()
        }

        private fun isParcelable(c: String?): Boolean {
            if (c == null) {
                return false
            }

            if (c == "android/os/Parcelable") {
                return true
            }

            val old = isParcelableCache[c]
            if (old != null) {
                return old
            }

            val cAncestors = ancestors[c] ?:
                    throw RuntimeException("class $c missing ancestor information")

            val seq = (cAncestors.interfaces?.asSequence() ?: emptySequence()) +
                    cAncestors.superName

            val ancestorIsParcelable = seq.any(this::isParcelable)

            isParcelableCache[c] = ancestorIsParcelable
            return ancestorIsParcelable
        }
    }
}
