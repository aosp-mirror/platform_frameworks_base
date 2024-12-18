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

import android.os.FileUtils
import android.util.AtomicFile
import android.util.Slog
import com.android.server.security.FileIntegrity;
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** Read from an [AtomicFile], fallback to reserve file to read the data. */
@Throws(Exception::class)
inline fun AtomicFile.readWithReserveCopy(block: (FileInputStream) -> Unit) {
    try {
        openRead().use(block)
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        Slog.wtf("AccessPersistence", "Failed to read $this", e)
        val reserveFile = File(baseFile.parentFile, baseFile.name + ".reservecopy")
        try {
            AtomicFile(reserveFile).openRead().use(block)
        } catch (e2: Exception) {
            Slog.e("AccessPersistence", "Failed to read $reserveFile", e2)
            throw e
        }
    }
}

/** Write to actual file and reserve file. */
@Throws(IOException::class)
inline fun AtomicFile.writeWithReserveCopy(block: (FileOutputStream) -> Unit) {
    writeInlined(block)
    val reserveFile = File(baseFile.parentFile, baseFile.name + ".reservecopy")
    reserveFile.delete()
    try {
        FileInputStream(baseFile).use { inputStream ->
            FileOutputStream(reserveFile).use { outputStream ->
                FileUtils.copy(inputStream, outputStream)
                outputStream.fd.sync()
            }
        }
    } catch (e: Exception) {
        Slog.e("AccessPersistence", "Failed to write $reserveFile", e)
    }
    try {
        FileIntegrity.setUpFsVerity(baseFile)
        FileIntegrity.setUpFsVerity(reserveFile)
    } catch (e: Exception) {
        Slog.e("AccessPersistence", "Failed to verity-protect runtime-permissions", e)
    }
}

/** Write to an [AtomicFile] and close everything safely when done. */
@Throws(IOException::class)
// Renamed to writeInlined() to avoid conflict with the hidden AtomicFile.write() that isn't inline.
inline fun AtomicFile.writeInlined(block: (FileOutputStream) -> Unit) {
    startWrite().use {
        try {
            block(it)
            finishWrite(it)
        } catch (t: Throwable) {
            failWrite(it)
            throw t
        }
    }
}
