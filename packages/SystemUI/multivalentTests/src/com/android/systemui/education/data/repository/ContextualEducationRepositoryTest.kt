/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.SysuiTestableContext
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.education.data.model.EduDeviceConnectionTime
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.domain.interactor.mockEduInputManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ContextualEducationRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: UserContextualEducationRepository
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val dsScopeProvider: Provider<CoroutineScope> = Provider {
        TestScope(kosmos.testDispatcher).backgroundScope
    }

    private val testUserId = 1111
    private val secondTestUserId = 1112

    // For deleting any test files created after the test
    @get:Rule val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Before
    fun setUp() {
        // Create TestContext here because TemporaryFolder.create() is called in @Before. It is
        // needed before calling TemporaryFolder.newFolder().
        val testContext = TestContext(context, tmpFolder.newFolder())
        underTest =
            UserContextualEducationRepository(
                testContext,
                dsScopeProvider,
                kosmos.mockEduInputManager,
                kosmos.testDispatcher
            )
        underTest.setUser(testUserId)
    }

    @Test
    fun changeRetrievedValueForNewUser() =
        testScope.runTest {
            // Update data for old user.
            underTest.updateGestureEduModel(BACK) { it.copy(signalCount = 1) }
            val model by collectLastValue(underTest.readGestureEduModelFlow(BACK))
            assertThat(model?.signalCount).isEqualTo(1)

            // User is changed.
            underTest.setUser(secondTestUserId)
            // Assert count is 0 after user is changed.
            assertThat(model?.signalCount).isEqualTo(0)
        }

    @Test
    fun changeUserIdForNewUser() =
        testScope.runTest {
            val model by collectLastValue(underTest.readGestureEduModelFlow(BACK))
            assertThat(model?.userId).isEqualTo(testUserId)
            underTest.setUser(secondTestUserId)
            assertThat(model?.userId).isEqualTo(secondTestUserId)
        }

    @Test
    fun dataChangedOnUpdate() =
        testScope.runTest {
            val newModel =
                GestureEduModel(
                    signalCount = 2,
                    educationShownCount = 1,
                    lastShortcutTriggeredTime = kosmos.fakeEduClock.instant(),
                    lastEducationTime = kosmos.fakeEduClock.instant(),
                    usageSessionStartTime = kosmos.fakeEduClock.instant(),
                    userId = testUserId,
                    gestureType = BACK
                )
            underTest.updateGestureEduModel(BACK) { newModel }
            val model by collectLastValue(underTest.readGestureEduModelFlow(BACK))
            assertThat(model).isEqualTo(newModel)
        }

    @Test
    fun eduDeviceConnectionTimeDataChangedOnUpdate() =
        testScope.runTest {
            val newModel =
                EduDeviceConnectionTime(
                    keyboardFirstConnectionTime = kosmos.fakeEduClock.instant(),
                    touchpadFirstConnectionTime = kosmos.fakeEduClock.instant(),
                )
            underTest.updateEduDeviceConnectionTime { newModel }
            val model by collectLastValue(underTest.readEduDeviceConnectionTime())
            assertThat(model).isEqualTo(newModel)
        }

    /** Test context which allows overriding getFilesDir path */
    private class TestContext(context: Context, private val folder: File) :
        SysuiTestableContext(context) {
        override fun getFilesDir(): File {
            return folder
        }
    }
}
