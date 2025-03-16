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

package com.android.systemui.qs.tiles.impl.notes.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotesTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val testUser = UserHandle.of(1)
    private lateinit var underTest: NotesTileDataInteractor


    @EnableFlags(Flags.FLAG_NOTES_ROLE_QS_TILE)
    @Test
    fun availability_qsFlagEnabled_notesRoleEnabled_returnTrue() =
        testScope.runTest {
            underTest = NotesTileDataInteractor(isNoteTaskEnabled = true)

            val availability = underTest.availability(testUser).toCollection(mutableListOf())

            assertThat(availability).containsExactly(true)
        }

    @DisableFlags(Flags.FLAG_NOTES_ROLE_QS_TILE)
    @Test
    fun availability_qsFlagDisabled_notesRoleEnabled_returnFalse() =
        testScope.runTest {
            underTest = NotesTileDataInteractor(isNoteTaskEnabled = true)

            val availability = underTest.availability(testUser).toCollection(mutableListOf())

            assertThat(availability).containsExactly(false)
        }

    @EnableFlags(Flags.FLAG_NOTES_ROLE_QS_TILE)
    @Test
    fun availability_qsFlagEnabled_notesRoleDisabled_returnFalse() =
        testScope.runTest {
            underTest = NotesTileDataInteractor(isNoteTaskEnabled = false)

            val availability = underTest.availability(testUser).toCollection(mutableListOf())

            assertThat(availability).containsExactly(false)
        }

    @DisableFlags(Flags.FLAG_NOTES_ROLE_QS_TILE)
    @Test
    fun availability_qsFlagDisabled_notesRoleDisabled_returnFalse() =
        testScope.runTest {
            underTest = NotesTileDataInteractor(isNoteTaskEnabled = false)

            val availability = underTest.availability(testUser).toCollection(mutableListOf())

            assertThat(availability).containsExactly(false)
        }

    @Test
    fun tileData_notEmpty() = runTest {
        underTest = NotesTileDataInteractor(isNoteTaskEnabled = true)
        val flowValue by
        collectLastValue(underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()

        assertThat(flowValue).isNotNull()
    }
}
