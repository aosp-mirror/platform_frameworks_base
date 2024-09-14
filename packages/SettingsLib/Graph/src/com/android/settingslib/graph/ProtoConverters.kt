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

package com.android.settingslib.graph

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.android.settingslib.graph.proto.BundleProto
import com.android.settingslib.graph.proto.BundleProto.BundleValue
import com.android.settingslib.graph.proto.IntentProto
import com.android.settingslib.graph.proto.TextProto
import com.google.protobuf.ByteString

fun TextProto.getText(context: Context): String? =
    when {
        hasResourceId() -> context.getString(resourceId)
        hasString() -> string
        else -> null
    }

fun Intent.toProto(): IntentProto = intentProto {
    this@toProto.action?.let { action = it }
    this@toProto.dataString?.let { data = it }
    this@toProto.`package`?.let { pkg = it }
    this@toProto.component?.let { component = it.flattenToShortString() }
    this@toProto.flags.let { if (it != 0) flags = it }
    this@toProto.extras?.let { extras = it.toProto() }
    this@toProto.type?.let { mimeType = it }
}

fun Bundle.toProto(): BundleProto = bundleProto {
    fun toProto(value: Any): BundleValue = bundleValueProto {
        when (value) {
            is String -> stringValue = value
            is ByteArray -> bytesValue = ByteString.copyFrom(value)
            is Int -> intValue = value
            is Long -> longValue = value
            is Boolean -> booleanValue = value
            is Double -> doubleValue = value
            is Bundle -> bundleValue = value.toProto()
            else -> throw IllegalArgumentException("Unknown type: ${value.javaClass} $value")
        }
    }

    for (key in keySet()) {
        @Suppress("DEPRECATION") get(key)?.let { putValues(key, toProto(it)) }
    }
}

fun BundleValue.stringify(): String =
    when {
        hasBooleanValue() -> "$valueCase"
        hasBytesValue() -> "$bytesValue"
        hasIntValue() -> "$intValue"
        hasLongValue() -> "$longValue"
        hasStringValue() -> stringValue
        hasDoubleValue() -> "$doubleValue"
        hasBundleValue() -> "$bundleValue"
        else -> "Unknown"
    }
