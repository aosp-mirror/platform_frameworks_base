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

import android.app.backup.BackupManager
import android.content.ComponentName
import android.util.AtomicFile
import android.util.Log
import android.util.Xml
import com.android.systemui.backup.BackupHelper
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
    private val executor: Executor,
    private var backupManager: BackupManager? = null
) {

    companion object {
        private const val TAG = "ControlsFavoritePersistenceWrapper"
        const val FILE_NAME = "controls_favorites.xml"
        private const val TAG_CONTROLS = "controls"
        private const val TAG_STRUCTURES = "structures"
        private const val TAG_STRUCTURE = "structure"
        private const val TAG_CONTROL = "control"
        private const val TAG_COMPONENT = "component"
        private const val TAG_ID = "id"
        private const val TAG_TITLE = "title"
        private const val TAG_SUBTITLE = "subtitle"
        private const val TAG_TYPE = "type"
        private const val TAG_VERSION = "version"

        // must increment with every change to the XML structure
        private const val VERSION = 1
    }

    /**
     * Change the file location for storing/reading the favorites and the [BackupManager]
     *
     * @param fileName new location
     * @param newBackupManager new [BackupManager]. Pass null to not trigger backups.
     */
    fun changeFileAndBackupManager(fileName: File, newBackupManager: BackupManager?) {
        file = fileName
        backupManager = newBackupManager
    }

    val fileExists: Boolean
        get() = file.exists()

    fun deleteFile() {
        file.delete()
    }

    /**
     * Stores the list of favorites in the corresponding file.
     *
     * @param list a list of favorite controls. The list will be stored in the same order.
     */
    fun storeFavorites(structures: List<StructureInfo>) {
        executor.execute {
            Log.d(TAG, "Saving data to file: $file")
            val atomicFile = AtomicFile(file)
            val dataWritten = synchronized(BackupHelper.controlsDataLock) {
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
                        startTag(null, TAG_VERSION)
                        text("$VERSION")
                        endTag(null, TAG_VERSION)

                        startTag(null, TAG_STRUCTURES)
                        structures.forEach { s ->
                            startTag(null, TAG_STRUCTURE)
                            attribute(null, TAG_COMPONENT, s.componentName.flattenToString())
                            attribute(null, TAG_STRUCTURE, s.structure.toString())

                            startTag(null, TAG_CONTROLS)
                            s.controls.forEach { c ->
                                startTag(null, TAG_CONTROL)
                                attribute(null, TAG_ID, c.controlId)
                                attribute(null, TAG_TITLE, c.controlTitle.toString())
                                attribute(null, TAG_SUBTITLE, c.controlSubtitle.toString())
                                attribute(null, TAG_TYPE, c.deviceType.toString())
                                endTag(null, TAG_CONTROL)
                            }
                            endTag(null, TAG_CONTROLS)
                            endTag(null, TAG_STRUCTURE)
                        }
                        endTag(null, TAG_STRUCTURES)
                        endDocument()
                        atomicFile.finishWrite(writer)
                    }
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to write file, reverting to previous version")
                    atomicFile.failWrite(writer)
                    false
                } finally {
                    IoUtils.closeQuietly(writer)
                }
            }
            if (dataWritten) backupManager?.dataChanged()
        }
    }

    /**
     * Stores the list of favorites in the corresponding file.
     *
     * @return a list of stored favorite controls. Return an empty list if the file is not found
     * @throws [IllegalStateException] if there is an error while reading the file
     */
    fun readFavorites(): List<StructureInfo> {
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
            synchronized(BackupHelper.controlsDataLock) {
                val parser = Xml.newPullParser()
                parser.setInput(reader, null)
                return parseXml(parser)
            }
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("Failed parsing favorites file: $file", e)
        } catch (e: IOException) {
            throw IllegalStateException("Failed parsing favorites file: $file", e)
        } finally {
            IoUtils.closeQuietly(reader)
        }
    }

    private fun parseXml(parser: XmlPullParser): List<StructureInfo> {
        var type: Int
        val infos = mutableListOf<StructureInfo>()

        var lastComponent: ComponentName? = null
        var lastStructure: CharSequence? = null
        var controls = mutableListOf<ControlInfo>()
        while (parser.next().also { type = it } != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name ?: ""
            if (type == XmlPullParser.START_TAG && tagName == TAG_STRUCTURE) {
                lastComponent = ComponentName.unflattenFromString(
                    parser.getAttributeValue(null, TAG_COMPONENT))
                lastStructure = parser.getAttributeValue(null, TAG_STRUCTURE) ?: ""
            } else if (type == XmlPullParser.START_TAG && tagName == TAG_CONTROL) {
                val id = parser.getAttributeValue(null, TAG_ID)
                val title = parser.getAttributeValue(null, TAG_TITLE)
                val subtitle = parser.getAttributeValue(null, TAG_SUBTITLE) ?: ""
                val deviceType = parser.getAttributeValue(null, TAG_TYPE)?.toInt()
                if (id != null && title != null && deviceType != null) {
                    controls.add(ControlInfo(id, title, subtitle, deviceType))
                }
            } else if (type == XmlPullParser.END_TAG && tagName == TAG_STRUCTURE) {
                infos.add(StructureInfo(lastComponent!!, lastStructure!!, controls.toList()))
                controls.clear()
            }
        }

        return infos
    }
}
