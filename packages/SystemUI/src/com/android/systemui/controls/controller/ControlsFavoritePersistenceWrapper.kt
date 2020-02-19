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

import android.content.ComponentName
import android.util.AtomicFile
import android.util.Log
import android.util.Xml
import libcore.io.IoUtils
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Manages persistence of favorite controls.
 *
 * This class uses an [AtomicFile] to serialize the favorite controls to an xml.
 * @property file a file location for storing/reading the favorites.
 * @property executor an executor in which to execute storing the favorites.
 */
class ControlsFavoritePersistenceWrapper(
    private var file: File,
    private val executor: Executor
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

    /**
     * Change the file location for storing/reading the favorites
     *
     * @param fileName new location
     */
    fun changeFile(fileName: File) {
        file = fileName
    }

    /**
     * Stores the list of favorites in the corresponding file.
     *
     * @param list a list of favorite controls. The list will be stored in the same order.
     */
    fun storeFavorites(list: List<ControlInfo>) {
        executor.execute {
            Log.d(TAG, "Saving data to file: $file")
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

    /**
     * Stores the list of favorites in the corresponding file.
     *
     * @return a list of stored favorite controls. Return an empty list if the file is not found
     * @throws [IllegalStateException] if there is an error while reading the file
     */
    fun readFavorites(): List<ControlInfo> {
        if (!file.exists()) {
            Log.d(TAG, "No favorites, returning empty list")
            return emptyList()
        }
        val reader = try {
            FileInputStream(file)
        } catch (fnfe: FileNotFoundException) {
            Log.i(TAG, "No file found")
            return emptyList()
        }
        try {
            Log.d(TAG, "Reading data from file: $file")
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
        var type: Int
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
                val deviceType = parser.getAttributeValue(null, TAG_TYPE)?.toInt()
                if (component != null && id != null && title != null && deviceType != null) {
                    infos.add(ControlInfo(component, id, title, deviceType))
                }
            }
        }
        return infos
    }
}