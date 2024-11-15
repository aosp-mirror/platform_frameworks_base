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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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

fun IntentProto.toIntent(): Intent? {
    if (!hasComponent()) return null
    val componentName = ComponentName.unflattenFromString(component) ?: return null
    val intent = Intent()
    intent.component = componentName
    if (hasAction()) intent.action = action
    if (hasData()) intent.data = Uri.parse(data)
    if (hasPkg()) intent.`package` = pkg
    if (hasFlags()) intent.flags = flags
    if (hasExtras()) intent.putExtras(extras.toBundle())
    if (hasMimeType()) intent.setType(mimeType)
    return intent
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

fun BundleProto.toBundle(): Bundle =
    Bundle().apply {
        for ((key, value) in valuesMap) {
            when {
                value.hasBooleanValue() -> putBoolean(key, value.booleanValue)
                value.hasBytesValue() -> putByteArray(key, value.bytesValue.toByteArray())
                value.hasIntValue() -> putInt(key, value.intValue)
                value.hasLongValue() -> putLong(key, value.longValue)
                value.hasStringValue() -> putString(key, value.stringValue)
                value.hasDoubleValue() -> putDouble(key, value.doubleValue)
                value.hasBundleValue() -> putBundle(key, value.bundleValue.toBundle())
                else -> throw IllegalArgumentException("Unknown type: ${value.javaClass} $value")
            }
        }
    }
