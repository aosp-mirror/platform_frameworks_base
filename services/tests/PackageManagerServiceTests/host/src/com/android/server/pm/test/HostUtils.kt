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

package com.android.server.pm.test

import com.android.internal.util.test.SystemPreparer
import com.android.tradefed.device.ITestDevice
import java.io.File
import java.io.FileOutputStream

internal fun SystemPreparer.pushApk(file: String, partition: Partition) =
        pushResourceFile(file, HostUtils.makePathForApk(file, partition).toString())

internal fun SystemPreparer.deleteApkFolders(
    partition: Partition,
    vararg javaResourceNames: String
) = apply {
    javaResourceNames.forEach {
        deleteFile(partition.baseAppFolder.resolve(it.removeSuffix(".apk")).toString())
    }
}

internal object HostUtils {

    fun getDataDir(device: ITestDevice, pkgName: String) =
            device.executeShellCommand("dumpsys package $pkgName")
                    .lineSequence()
                    .map(String::trim)
                    .single { it.startsWith("dataDir=") }
                    .removePrefix("dataDir=")

    fun makePathForApk(fileName: String, partition: Partition) =
            makePathForApk(File(fileName), partition)

    fun makePathForApk(file: File, partition: Partition) =
            partition.baseAppFolder
                    .resolve(file.nameWithoutExtension)
                    .resolve(file.name)

    fun copyResourceToHostFile(javaResourceName: String, file: File): File {
        javaClass.classLoader!!.getResource(javaResourceName).openStream().use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }
}
