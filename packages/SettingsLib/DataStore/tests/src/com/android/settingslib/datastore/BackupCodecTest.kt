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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

/** Tests of [BackupCodec]. */
@RunWith(AndroidJUnit4::class)
class BackupCodecTest {
    @Test
    fun name() {
        val names = mutableSetOf<String>()
        for (codec in allCodecs()) {
            assertThat(names).doesNotContain(codec.name)
            names.add(codec.name)
        }
    }

    @Test
    fun fromId() {
        for (codec in allCodecs()) {
            assertThat(BackupCodec.fromId(codec.id)).isInstanceOf(codec::class.java)
        }
    }

    @Test
    fun fromId_unknownId() {
        assertFailsWith(IllegalArgumentException::class) { BackupCodec.fromId(-1) }
    }

    @Test
    fun encode_decode() {
        val random = Random.Default
        fun test(codec: BackupCodec, size: Int) {
            val data = random.nextBytes(size)

            // encode
            val outputStream = ByteArrayOutputStream()
            codec.encode(outputStream).use { it.write(data) }

            // decode
            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            val result = codec.decode(inputStream).use { it.readBytes() }

            assertWithMessage("$size bytes: $data").that(result).isEqualTo(data)
        }

        for (codec in allCodecs()) {
            test(codec, 0)
            repeat(10) { test(codec, random.nextInt(1, 1024)) }
        }
    }
}
