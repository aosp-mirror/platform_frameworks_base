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

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import kotlin.random.Random
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify

/** Tests of [SharedPreferencesStorage]. */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesStorageTest {
    private val random = Random.Default
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val map =
        mapOf(
            "boolean" to true,
            "float" to random.nextFloat(),
            "int" to random.nextInt(),
            "long" to random.nextLong(),
            "string" to "string",
            "set" to setOf("string"),
        )

    @After
    fun tearDown() {
        application.getSharedPreferences(NAME, MODE).edit().clear().applySync()
    }

    @Test
    fun constructors() {
        val storage1 = SharedPreferencesStorage(application, NAME, MODE)
        val storage2 =
            SharedPreferencesStorage(
                application,
                NAME,
                application.getSharedPreferences(NAME, MODE),
            )
        assertThat(storage1.sharedPreferences).isSameInstanceAs(storage2.sharedPreferences)
    }

    @Test
    fun observer() {
        val observer = mock<KeyedObserver<Any?>>()
        val keyedObserver = mock<KeyedObserver<Any>>()
        val storage = SharedPreferencesStorage(application, NAME, MODE)
        val executor: Executor = MoreExecutors.directExecutor()
        storage.addObserver(observer, executor)
        storage.addObserver("key", keyedObserver, executor)

        storage.sharedPreferences.edit().putString("key", "string").applySync()
        verify(observer).onKeyChanged("key", DataChangeReason.UPDATE)
        verify(keyedObserver).onKeyChanged("key", DataChangeReason.UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage.sharedPreferences.edit().clear().applySync()
            verify(observer).onKeyChanged(null, DataChangeReason.DELETE)
            verify(keyedObserver).onKeyChanged("key", DataChangeReason.DELETE)
        }
    }

    @Test
    fun prepareBackup_commitFailed() {
        val editor = mock<SharedPreferences.Editor> { on { commit() } doReturn false }
        val storage =
            spy(SharedPreferencesStorage(application, NAME, MODE)) {
                onGeneric { mergeSharedPreferences(any(), any(), any()) } doReturn editor
            }
        storage.prepareBackup(File(""))
    }

    @Test
    fun backupAndRestore() {
        fun test(codec: BackupCodec) {
            val storage = SharedPreferencesStorage(application, NAME, MODE, codec)
            storage.mergeSharedPreferences(storage.sharedPreferences, map, "op").commit()
            assertThat(storage.sharedPreferences.all).isEqualTo(map)

            val outputStream = ByteArrayOutputStream()
            assertThat(storage.toBackupRestoreEntity().backup(BackupContext(mock()), outputStream))
                .isEqualTo(EntityBackupResult.UPDATE)
            val payload = outputStream.toByteArray()

            storage.sharedPreferences.edit().clear().commit()
            assertThat(storage.sharedPreferences.all).isEmpty()

            BackupRestoreFileArchiver(application, listOf(storage), "archiver")
                .restoreEntity(newBackupDataInputStream(storage.storageFilePath, payload))
            assertThat(storage.sharedPreferences.all).isEqualTo(map)
        }

        for (codec in allCodecs()) test(codec)
    }

    @Test
    fun mergeSharedPreferences_filter() {
        val storage =
            SharedPreferencesStorage(application, NAME, MODE) { key, value ->
                key == "float" || value is String
            }
        storage.mergeSharedPreferences(storage.sharedPreferences, map, "op").apply()
        assertThat(storage.sharedPreferences.all)
            .containsExactly("float", map["float"], "string", map["string"])
    }

    @Test
    fun mergeSharedPreferences_invalidSet() {
        val storage = SharedPreferencesStorage(application, NAME, MODE, verbose = true)
        storage
            .mergeSharedPreferences(
                storage.sharedPreferences,
                mapOf<String, Any>("set" to setOf(Any())),
                "op"
            )
            .apply()
        assertThat(storage.sharedPreferences.all).isEmpty()
    }

    @Test
    fun mergeSharedPreferences_unknownType() {
        val storage = SharedPreferencesStorage(application, NAME, MODE)
        storage
            .mergeSharedPreferences(storage.sharedPreferences, map + ("key" to Any()), "op")
            .apply()
        assertThat(storage.sharedPreferences.all).isEqualTo(map)
    }

    @Test
    fun mergeSharedPreferences() {
        val storage = SharedPreferencesStorage(application, NAME, MODE, verbose = true)
        storage.mergeSharedPreferences(storage.sharedPreferences, map, "op").apply()
        assertThat(storage.sharedPreferences.all).isEqualTo(map)
    }

    private fun SharedPreferences.Editor.applySync() {
        apply()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    companion object {
        private const val NAME = "pref"
        private const val MODE = Context.MODE_PRIVATE
    }
}
