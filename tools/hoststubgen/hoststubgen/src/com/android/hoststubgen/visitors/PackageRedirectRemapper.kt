/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.visitors

import com.android.hoststubgen.asm.toJvmClassName
import org.objectweb.asm.commons.Remapper

/**
 * A [Remapper] for `--package-redirect`
 */
class PackageRedirectRemapper(
        packageRedirects: List<Pair<String, String>>,
        ) : Remapper() {

    /**
     * Example: `dalvik/` -> `com/android/hostsubgen/substitution/dalvik/`
     */
    private val packageRedirectsWithSlash: List<Pair<String, String>> = packageRedirects.map {
        p -> Pair(p.first.toJvmClassName() + "/", p.second.toJvmClassName() + "/")
    }

    /**
     * Cache.
     * If a class is a redirect-from class, then the "to" class name will be stored as the value.
     * Otherwise, "" will be stored.
     */
    private val cache = mutableMapOf<String, String>()

    /**
     * Return whether any redirect is defined.
     */
    val isEmpty get() = packageRedirectsWithSlash.isEmpty()

    override fun map(internalName: String?): String? {
        if (internalName == null) {
            return null
        }
        val to = mapInner(internalName)
        return to ?: internalName
    }

    /**
     * Internal "map" function. Unlike [map(String)], this method will return null
     * if a class is not a redirect-from class.
     */
    private fun mapInner(internalName: String): String? {
        cache[internalName]?.let {
            return if (it.isEmpty()) null else it
        }

        var ret = ""
        packageRedirectsWithSlash.forEach { fromTo ->
            if (internalName.startsWith(fromTo.first)) {
                ret = fromTo.second + internalName.substring(fromTo.first.length)
            }
        }
        cache.set(internalName, ret)

        return if (ret.isEmpty()) null else ret
    }

    /**
     * Return true if a class is a redirect-from class.
     */
    fun isTarget(internalName: String): Boolean {
        return mapInner(internalName) != null
    }
}

