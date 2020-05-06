/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.bubbles.storage

import com.android.internal.util.FastXmlSerializer
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private const val TAG_BUBBLES = "bs"
private const val TAG_BUBBLE = "bb"
private const val ATTR_USER_ID = "uid"
private const val ATTR_PACKAGE = "pkg"
private const val ATTR_SHORTCUT_ID = "sid"

/**
 * Writes the bubbles in xml format into given output stream.
 */
@Throws(IOException::class)
fun writeXml(stream: OutputStream, bubbles: List<BubbleXmlEntity>) {
    val serializer: XmlSerializer = FastXmlSerializer()
    serializer.setOutput(stream, StandardCharsets.UTF_8.name())
    serializer.startDocument(null, true)
    serializer.startTag(null, TAG_BUBBLES)
    bubbles.forEach { b -> writeXmlEntry(serializer, b) }
    serializer.endTag(null, TAG_BUBBLES)
    serializer.endDocument()
}

/**
 * Creates a xml entry for given bubble in following format:
 * ```
 * <bb uid="0" pkg="com.example.messenger" sid="my-shortcut" />
 * ```
 */
private fun writeXmlEntry(serializer: XmlSerializer, bubble: BubbleXmlEntity) {
    try {
        serializer.startTag(null, TAG_BUBBLE)
        serializer.attribute(null, ATTR_USER_ID, bubble.userId.toString())
        serializer.attribute(null, ATTR_PACKAGE, bubble.packageName)
        serializer.attribute(null, ATTR_SHORTCUT_ID, bubble.shortcutId)
        serializer.endTag(null, TAG_BUBBLE)
    } catch (e: IOException) {
        throw RuntimeException(e)
    }
}