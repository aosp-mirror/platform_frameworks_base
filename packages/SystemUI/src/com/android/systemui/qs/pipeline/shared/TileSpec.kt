/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.shared

import android.content.ComponentName
import android.text.TextUtils
import com.android.systemui.qs.external.CustomTile

/**
 * Container for the spec that identifies a tile.
 *
 * A tile's [spec] is one of two options:
 * * `custom(<componentName>)`: A [ComponentName] surrounded by [CustomTile.PREFIX] and terminated
 *   by `)`, represents a tile provided by an app, corresponding to a `TileService`.
 * * a string not starting with [CustomTile.PREFIX], representing a tile provided by SystemUI.
 */
sealed class TileSpec private constructor(open val spec: String) {

    /** Represents a spec that couldn't be parsed into a valid type of tile. */
    object Invalid : TileSpec("") {
        override fun toString(): String {
            return "TileSpec.INVALID"
        }
    }

    /** Container for the spec of a tile provided by SystemUI. */
    data class PlatformTileSpec
    internal constructor(
        override val spec: String,
    ) : TileSpec(spec)

    /**
     * Container for the spec of a tile provided by an app.
     *
     * [componentName] indicates the associated `TileService`.
     */
    data class CustomTileSpec
    internal constructor(
        override val spec: String,
        val componentName: ComponentName,
    ) : TileSpec(spec) {
        override fun toString(): String {
            return "CustomTileSpec(${componentName.toShortString()})"
        }
    }

    companion object {
        /** Create a [TileSpec] from the string [spec]. */
        fun create(spec: String): TileSpec {
            return if (TextUtils.isEmpty(spec)) {
                Invalid
            } else if (!spec.isCustomTileSpec) {
                PlatformTileSpec(spec)
            } else {
                spec.componentName?.let { CustomTileSpec(spec, it) } ?: Invalid
            }
        }

        fun create(component: ComponentName): CustomTileSpec {
            return CustomTileSpec(CustomTile.toSpec(component), component)
        }

        private val String.isCustomTileSpec: Boolean
            get() = startsWith(CustomTile.PREFIX)

        private val String.componentName: ComponentName?
            get() =
                if (!isCustomTileSpec) {
                    null
                } else {
                    if (endsWith(")")) {
                        val extracted = substring(CustomTile.PREFIX.length, length - 1)
                        ComponentName.unflattenFromString(extracted)
                    } else {
                        null
                    }
                }
    }
}
