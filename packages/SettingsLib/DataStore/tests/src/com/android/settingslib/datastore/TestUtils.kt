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

package com.android.settingslib.datastore

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataInputStream
import android.os.Build
import java.io.ByteArrayInputStream
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

internal const val MAX_DATA_SIZE = 1 shl 12

internal fun allCodecs() =
    arrayOf<BackupCodec>(
        BackupNoOpCodec(),
    ) + zipCodecs()

internal fun zipCodecs() =
    arrayOf<BackupCodec>(
        BackupZipCodec.DEFAULT_COMPRESSION,
        BackupZipCodec.BEST_COMPRESSION,
        BackupZipCodec.BEST_SPEED,
    )

internal fun <T : Any> Class<T>.newInstance(arg: Any, type: Class<*> = arg.javaClass): T =
    getDeclaredConstructor(type).apply { isAccessible = true }.newInstance(arg)

internal fun newBackupDataInputStream(
    key: String,
    data: ByteArray,
    e: Exception? = null,
): BackupDataInputStream {
    // ShadowBackupDataOutput does not write data to file, so mock for reading data
    val inputStream = ByteArrayInputStream(data)
    val backupDataInput =
        mock<BackupDataInput> {
            on { readEntityData(any(), any(), any()) } doAnswer
                {
                    if (e != null) throw e
                    val buf = it.arguments[0] as ByteArray
                    val offset = it.arguments[1] as Int
                    val size = it.arguments[2] as Int
                    inputStream.read(buf, offset, size)
                }
        }
    return BackupDataInputStream::class
        .java
        .newInstance(backupDataInput, BackupDataInput::class.java)
        .apply {
            setKey(key)
            setDataSize(data.size)
        }
}

internal fun BackupDataInputStream.setKey(value: Any) {
    val field = javaClass.getDeclaredField("key")
    field.isAccessible = true
    field.set(this, value)
}

internal fun BackupDataInputStream.setDataSize(dataSize: Int) {
    val field = javaClass.getDeclaredField("dataSize")
    field.isAccessible = true
    field.setInt(this, dataSize)
}

internal fun isRobolectric() = Build.FINGERPRINT.contains("robolectric")
