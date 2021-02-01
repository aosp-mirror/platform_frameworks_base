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
import android.service.controls.DeviceTypes
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsFavoritePersistenceWrapperTest : SysuiTestCase() {

    private lateinit var file: File

    private val executor = FakeExecutor(FakeSystemClock())

    private lateinit var wrapper: ControlsFavoritePersistenceWrapper

    @Before
    fun setUp() {
        file = File.createTempFile("controls_favorites", ".temp")
        wrapper = ControlsFavoritePersistenceWrapper(file, executor)
    }

    @After
    fun tearDown() {
        if (file.exists()) {
            file.delete()
        }
    }

    @Test
    fun testSaveAndRestore() {
        val structureInfo1 = StructureInfo(
            ComponentName.unflattenFromString("TEST_PKG/.TEST_CLS_1")!!,
            "",
            listOf(
                ControlInfo("id1", "name_1", "", DeviceTypes.TYPE_UNKNOWN)
            )
        )

        val structureInfo2 = StructureInfo(
            ComponentName.unflattenFromString("TEST_PKG/.TEST_CLS_2")!!,
            "structure1",
            listOf(
                ControlInfo("id2", "name_2", "sub2", DeviceTypes.TYPE_GENERIC_ON_OFF),
                ControlInfo("id3", "name_3", "sub3", DeviceTypes.TYPE_GENERIC_ON_OFF)
            )
        )
        val list = listOf(structureInfo1, structureInfo2)
        wrapper.storeFavorites(list)

        executor.runAllReady()

        assertEquals(list, wrapper.readFavorites())
    }

    @Test
    fun testSaveEmptyOnNonExistingFile() {
        if (file.exists()) {
            file.delete()
        }

        wrapper.storeFavorites(emptyList())

        assertFalse(file.exists())
    }
}
