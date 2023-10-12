/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.permission.access.util

import com.android.modules.utils.BinaryXmlSerializer
import java.io.IOException
import java.io.OutputStream

/** Serialize content into [OutputStream] with [BinaryXmlSerializer]. */
@Throws(IOException::class)
inline fun OutputStream.serializeBinaryXml(block: BinaryXmlSerializer.() -> Unit) {
    BinaryXmlSerializer().apply {
        setOutput(this@serializeBinaryXml, null)
        document(block)
    }
}

/**
 * Write a document with [BinaryXmlSerializer].
 *
 * @see BinaryXmlSerializer.startDocument
 * @see BinaryXmlSerializer.endDocument
 */
@Throws(IOException::class)
inline fun BinaryXmlSerializer.document(block: BinaryXmlSerializer.() -> Unit) {
    startDocument(null, true)
    block()
    endDocument()
}

/**
 * Write a tag with [BinaryXmlSerializer].
 *
 * @see BinaryXmlSerializer.startTag
 * @see BinaryXmlSerializer.endTag
 */
@Throws(IOException::class)
inline fun BinaryXmlSerializer.tag(name: String, block: BinaryXmlSerializer.() -> Unit) {
    startTag(null, name)
    block()
    endTag(null, name)
}

/** @see BinaryXmlSerializer.attribute */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attribute(name: String, value: String) {
    attribute(null, name, value)
}

/** @see BinaryXmlSerializer.attributeInterned */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeInterned(name: String, value: String) {
    attributeInterned(null, name, value)
}

/** @see BinaryXmlSerializer.attributeBytesHex */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeBytesHex(name: String, value: ByteArray) {
    attributeBytesHex(null, name, value)
}

/** @see BinaryXmlSerializer.attributeBytesBase64 */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeBytesBase64(name: String, value: ByteArray) {
    attributeBytesBase64(null, name, value)
}

/** @see BinaryXmlSerializer.attributeInt */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeInt(name: String, value: Int) {
    attributeInt(null, name, value)
}

/** @see BinaryXmlSerializer.attributeInt */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeIntWithDefault(
    name: String,
    value: Int,
    defaultValue: Int
) {
    if (value != defaultValue) {
        attributeInt(null, name, value)
    }
}

/** @see BinaryXmlSerializer.attributeIntHex */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeIntHex(name: String, value: Int) {
    attributeIntHex(null, name, value)
}

/** @see BinaryXmlSerializer.attributeIntHex */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeIntHexWithDefault(
    name: String,
    value: Int,
    defaultValue: Int
) {
    if (value != defaultValue) {
        attributeIntHex(null, name, value)
    }
}

/** @see BinaryXmlSerializer.attributeLong */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeLong(name: String, value: Long) {
    attributeLong(null, name, value)
}

/** @see BinaryXmlSerializer.attributeLong */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeLongWithDefault(
    name: String,
    value: Long,
    defaultValue: Long
) {
    if (value != defaultValue) {
        attributeLong(null, name, value)
    }
}

/** @see BinaryXmlSerializer.attributeLongHex */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeLongHex(name: String, value: Long) {
    attributeLongHex(null, name, value)
}

/** @see BinaryXmlSerializer.attributeLongHex */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeLongHexWithDefault(
    name: String,
    value: Long,
    defaultValue: Long
) {
    if (value != defaultValue) {
        attributeLongHex(null, name, value)
    }
}

/** @see BinaryXmlSerializer.attributeFloat */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeFloat(name: String, value: Float) {
    attributeFloat(null, name, value)
}

/** @see BinaryXmlSerializer.attributeFloat */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeFloatWithDefault(
    name: String,
    value: Float,
    defaultValue: Float
) {
    if (value != defaultValue) {
        attributeFloat(null, name, value)
    }
}

/** @see BinaryXmlSerializer.attributeDouble */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeDouble(name: String, value: Double) {
    attributeDouble(null, name, value)
}

/** @see BinaryXmlSerializer.attributeDouble */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeDoubleWithDefault(
    name: String,
    value: Double,
    defaultValue: Double
) {
    if (value != defaultValue) {
        attributeDouble(null, name, value)
    }
}

/** @see BinaryXmlSerializer.attributeBoolean */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeBoolean(name: String, value: Boolean) {
    attributeBoolean(null, name, value)
}

/** @see BinaryXmlSerializer.attributeBoolean */
@Suppress("NOTHING_TO_INLINE")
@Throws(IOException::class)
inline fun BinaryXmlSerializer.attributeBooleanWithDefault(
    name: String,
    value: Boolean,
    defaultValue: Boolean
) {
    if (value != defaultValue) {
        attributeBoolean(null, name, value)
    }
}
