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

package com.android.systemui.controls.controller

import android.app.ActivityManager
import android.content.ComponentName
import android.util.AtomicFile
import android.util.Log
import android.util.Slog
import android.util.Xml
import com.android.systemui.util.concurrency.DelayableExecutor
import libcore.io.IoUtils
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

class ControlsFavoritePersistenceWrapper(
    val file: File,
    val executor: DelayableExecutor
) {

    companion object {
        private const val TAG = "ControlsFavoritePersistenceWrapper"
        const val FILE_NAME = "controls_favorites.xml"
        private const val TAG_CONTROLS = "controls"
        private const val TAG_CONTROL = "control"
        private const val TAG_COMPONENT = "component"
        private const val TAG_ID = "id"
        private const val TAG_TITLE = "title"
        private const val TAG_TYPE = "type"
    }

    val currentUser: Int
        get() = ActivityManager.getCurrentUser()

    fun storeFavorites(list: List<ControlInfo>) {
        executor.execute {
            val atomicFile = AtomicFile(file)
            val writer = try {
                atomicFile.startWrite()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start write file", e)
                return@execute
            }
            try {
                Xml.newSerializer().apply {
                    setOutput(writer, "utf-8")
                    setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
                    startDocument(null, true)
                    startTag(null, TAG_CONTROLS)
                    list.forEach {
                        startTag(null, TAG_CONTROL)
                        attribute(null, TAG_COMPONENT, it.component.flattenToString())
                        attribute(null, TAG_ID, it.controlId)
                        attribute(null, TAG_TITLE, it.controlTitle.toString())
                        attribute(null, TAG_TYPE, it.deviceType.toString())
                        endTag(null, TAG_CONTROL)
                    }
                    endTag(null, TAG_CONTROLS)
                    endDocument()
                    atomicFile.finishWrite(writer)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to write file, reverting to previous version")
                atomicFile.failWrite(writer)
            } finally {
                IoUtils.closeQuietly(writer)
            }
        }
    }

    fun readFavorites(): List<ControlInfo> {
        if (!file.exists()) {
            Log.d(TAG, "No favorites, returning empty list")
            return emptyList()
        }
        val reader = try {
            FileInputStream(file)
        } catch (fnfe: FileNotFoundException) {
            Slog.i(TAG, "No file found")
            return emptyList()
        }
        try {
            val parser = Xml.newPullParser()
            parser.setInput(reader, null)
            return parseXml(parser)
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("Failed parsing favorites file: $file", e)
        } catch (e: IOException) {
            throw IllegalStateException("Failed parsing favorites file: $file", e)
        } finally {
            IoUtils.closeQuietly(reader)
        }
    }

    private fun parseXml(parser: XmlPullParser): List<ControlInfo> {
        var type: Int = 0
        val infos = mutableListOf<ControlInfo>()
        while (parser.next().also { type = it } != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue
            }
            val tagName = parser.name
            if (tagName == TAG_CONTROL) {
                val component = ComponentName.unflattenFromString(
                        parser.getAttributeValue(null, TAG_COMPONENT))
                val id = parser.getAttributeValue(null, TAG_ID)
                val title = parser.getAttributeValue(null, TAG_TITLE)
                val type = parser.getAttributeValue(null, TAG_TYPE)?.toInt()
                if (component != null && id != null && title != null && type != null) {
                    infos.add(ControlInfo(component, id, title, type))
                }
            }
        }
        return infos
    }
}