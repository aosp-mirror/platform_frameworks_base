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

import androidx.annotation.IntDef
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Unique id of the codec. */
@Target(AnnotationTarget.TYPE)
@IntDef(
    BackupCodecId.NO_OP.toInt(),
    BackupCodecId.ZIP.toInt(),
)
@Retention(AnnotationRetention.SOURCE)
annotation class BackupCodecId {
    companion object {
        /** Unknown reason of the change. */
        const val NO_OP: Byte = 0
        /** Data is updated. */
        const val ZIP: Byte = 1
    }
}

/** How to encode/decode the backup data. */
interface BackupCodec {
    /** Unique id of the codec. */
    val id: @BackupCodecId Byte

    /** Name of the codec. */
    val name: String

    /** Encodes the backup data. */
    fun encode(outputStream: OutputStream): OutputStream

    /** Decodes the backup data. */
    fun decode(inputStream: InputStream): InputStream

    companion object {
        @JvmStatic
        fun fromId(id: @BackupCodecId Byte): BackupCodec =
            when (id) {
                BackupCodecId.NO_OP -> BackupNoOpCodec()
                BackupCodecId.ZIP -> BackupZipCodec.BEST_COMPRESSION
                else -> throw IllegalArgumentException("Unknown codec id $id")
            }
    }
}

/** Codec without any additional encoding/decoding. */
class BackupNoOpCodec : BackupCodec {
    override val id
        get() = BackupCodecId.NO_OP

    override val name
        get() = "N/A"

    override fun encode(outputStream: OutputStream) = outputStream

    override fun decode(inputStream: InputStream) = inputStream
}

/** Codec with ZIP compression. */
class BackupZipCodec(
    private val compressionLevel: Int,
    override val name: String,
) : BackupCodec {
    override val id
        get() = BackupCodecId.ZIP

    override fun encode(outputStream: OutputStream) =
        DeflaterOutputStream(outputStream, Deflater(compressionLevel))

    override fun decode(inputStream: InputStream) = InflaterInputStream(inputStream)

    companion object {
        val DEFAULT_COMPRESSION = BackupZipCodec(Deflater.DEFAULT_COMPRESSION, "ZipDefault")
        val BEST_COMPRESSION = BackupZipCodec(Deflater.BEST_COMPRESSION, "ZipBestCompression")
        val BEST_SPEED = BackupZipCodec(Deflater.BEST_SPEED, "ZipBestSpeed")
    }
}
