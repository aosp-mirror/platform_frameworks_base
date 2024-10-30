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

package com.android.systemui.display.data.repository

import android.content.testableContext
import android.platform.test.annotations.EnableFlags
import android.view.Display
import android.view.layoutInflater
import android.view.mockWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.shared.model.DisplayWindowProperties
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
@RunWith(AndroidJUnit4::class)
@SmallTest
class DisplayWindowPropertiesRepositoryImplTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val fakeDisplayRepository = kosmos.displayRepository
    private val testScope = kosmos.testScope

    private val applicationContext = kosmos.testableContext
    private val applicationWindowManager = kosmos.mockWindowManager
    private val applicationLayoutInflater = kosmos.layoutInflater

    // Lazy so that @EnableFlags has time to run before this repo is instantiated
    private val repo by lazy {
        DisplayWindowPropertiesRepositoryImpl(
            kosmos.applicationCoroutineScope,
            applicationContext,
            applicationWindowManager,
            kosmos.layoutInflater,
            fakeDisplayRepository,
        )
    }

    @Before
    fun start() {
        repo.start()
    }

    @Before
    fun addDisplays() = runBlocking {
        fakeDisplayRepository.addDisplay(createDisplay(DEFAULT_DISPLAY_ID))
        fakeDisplayRepository.addDisplay(createDisplay(NON_DEFAULT_DISPLAY_ID))
    }

    @Test
    fun get_defaultDisplayId_returnsDefaultProperties() =
        testScope.runTest {
            val displayContext = repo.get(DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(displayContext)
                .isEqualTo(
                    DisplayWindowProperties(
                        displayId = DEFAULT_DISPLAY_ID,
                        windowType = WINDOW_TYPE_FOO,
                        context = applicationContext,
                        windowManager = applicationWindowManager,
                        layoutInflater = applicationLayoutInflater,
                    )
                )
        }

    @Test
    fun get_nonDefaultDisplayId_returnsNewStatusBarContext() =
        testScope.runTest {
            val displayContext = repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(displayContext.context).isNotSameInstanceAs(applicationContext)
        }

    @Test
    fun get_nonDefaultDisplayId_returnsNewWindowManager() =
        testScope.runTest {
            val displayContext = repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(displayContext.windowManager).isNotSameInstanceAs(applicationWindowManager)
        }

    @Test
    fun get_nonDefaultDisplayId_returnsNewLayoutInflater() =
        testScope.runTest {
            val displayContext = repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(displayContext.layoutInflater).isNotSameInstanceAs(applicationLayoutInflater)
        }

    @Test
    fun get_multipleCallsForDefaultDisplay_returnsSameInstance() =
        testScope.runTest {
            val displayContext = repo.get(DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(repo.get(DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO))
                .isSameInstanceAs(displayContext)
        }

    @Test
    fun get_multipleCallsForNonDefaultDisplay_returnsSameInstance() =
        testScope.runTest {
            val displayContext = repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO))
                .isSameInstanceAs(displayContext)
        }

    @Test
    fun get_multipleCalls_differentType_returnsNewInstance() =
        testScope.runTest {
            val displayContext = repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            assertThat(repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_BAR))
                .isNotSameInstanceAs(displayContext)
        }

    @Test
    fun get_afterDisplayRemoved_returnsNewInstance() =
        testScope.runTest {
            val displayContext = repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO)

            fakeDisplayRepository.removeDisplay(NON_DEFAULT_DISPLAY_ID)
            fakeDisplayRepository.addDisplay(createDisplay(NON_DEFAULT_DISPLAY_ID))

            assertThat(repo.get(NON_DEFAULT_DISPLAY_ID, WINDOW_TYPE_FOO))
                .isNotSameInstanceAs(displayContext)
        }

    @Test(expected = IllegalArgumentException::class)
    fun get_nonExistingDisplayId_throws() =
        testScope.runTest { repo.get(NON_EXISTING_DISPLAY_ID, WINDOW_TYPE_FOO) }

    private fun createDisplay(displayId: Int) =
        mock<Display> { on { getDisplayId() } doReturn displayId }

    companion object {
        private const val DEFAULT_DISPLAY_ID = Display.DEFAULT_DISPLAY
        private const val NON_DEFAULT_DISPLAY_ID = DEFAULT_DISPLAY_ID + 1
        private const val NON_EXISTING_DISPLAY_ID = DEFAULT_DISPLAY_ID + 2
        private const val WINDOW_TYPE_FOO = 123
        private const val WINDOW_TYPE_BAR = 321
    }
}
