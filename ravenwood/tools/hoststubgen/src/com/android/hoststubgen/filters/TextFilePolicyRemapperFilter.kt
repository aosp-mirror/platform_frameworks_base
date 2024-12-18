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
package com.android.hoststubgen.filters

import com.android.hoststubgen.log
import java.util.regex.Pattern

/**
 * A filter that provides a simple "jarjar" functionality via [mapType]
 */
class TextFilePolicyRemapperFilter(
    val typeRenameSpecs: List<TypeRenameSpec>,
    fallback: OutputFilter,
) : DelegatingFilter(fallback) {
    /**
     * When a package name matches [typeInternalNamePattern], we prepend [typeInternalNamePrefix]
     * to it.
     */
    data class TypeRenameSpec(
        val typeInternalNamePattern: Pattern,
        val typeInternalNamePrefix: String,
    )

    private val cache = mutableMapOf<String, String>()

    override fun remapType(className: String): String? {
        var mapped: String = className
        typeRenameSpecs.forEach {
            if (it.typeInternalNamePattern.matcher(className).matches()) {
                mapped = it.typeInternalNamePrefix + className
                log.d("Renaming type $className to $mapped")
            }
        }
        cache[className] = mapped
        return mapped
    }
}
