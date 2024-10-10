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

package com.android.systemui.statusbar.window

import android.platform.test.annotations.EnableFlags
import android.view.Display
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.app.viewcapture.mockViewCaptureAwareWindowManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.fakeDisplayWindowPropertiesRepository
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.unconfinedTestDispatcher
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

@RunWith(AndroidJUnit4::class)
@SmallTest
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class MultiDisplayStatusBarWindowControllerStoreTest : SysuiTestCase() {

    private val kosmos = testKosmos().also { it.testDispatcher = it.unconfinedTestDispatcher }
    private val testScope = kosmos.testScope
    private val fakeDisplayRepository = kosmos.displayRepository

    private val store =
        MultiDisplayStatusBarWindowControllerStore(
            backgroundApplicationScope = kosmos.applicationCoroutineScope,
            controllerFactory = kosmos.fakeStatusBarWindowControllerFactory,
            displayWindowPropertiesRepository = kosmos.fakeDisplayWindowPropertiesRepository,
            viewCaptureAwareWindowManagerFactory =
                object : ViewCaptureAwareWindowManager.Factory {
                    override fun create(
                        windowManager: WindowManager
                    ): ViewCaptureAwareWindowManager {
                        return kosmos.mockViewCaptureAwareWindowManager
                    }
                },
            displayRepository = fakeDisplayRepository,
        )

    @Before
    fun start() {
        store.start()
    }

    @Before
    fun addDisplays() = runBlocking {
        fakeDisplayRepository.addDisplay(createDisplay(DEFAULT_DISPLAY_ID))
        fakeDisplayRepository.addDisplay(createDisplay(NON_DEFAULT_DISPLAY_ID))
    }

    @Test
    fun forDisplay_defaultDisplay_multipleCalls_returnsSameInstance() =
        testScope.runTest {
            val controller = store.defaultDisplay

            assertThat(store.defaultDisplay).isSameInstanceAs(controller)
        }

    @Test
    fun forDisplay_nonDefaultDisplay_multipleCalls_returnsSameInstance() =
        testScope.runTest {
            val controller = store.forDisplay(NON_DEFAULT_DISPLAY_ID)

            assertThat(store.forDisplay(NON_DEFAULT_DISPLAY_ID)).isSameInstanceAs(controller)
        }

    @Test
    fun forDisplay_nonDefaultDisplay_afterDisplayRemoved_returnsNewInstance() =
        testScope.runTest {
            val controller = store.forDisplay(NON_DEFAULT_DISPLAY_ID)

            fakeDisplayRepository.removeDisplay(NON_DEFAULT_DISPLAY_ID)
            fakeDisplayRepository.addDisplay(createDisplay(NON_DEFAULT_DISPLAY_ID))

            assertThat(store.forDisplay(NON_DEFAULT_DISPLAY_ID)).isNotSameInstanceAs(controller)
        }

    @Test(expected = IllegalArgumentException::class)
    fun forDisplay_nonExistingDisplayId_throws() =
        testScope.runTest { store.forDisplay(NON_EXISTING_DISPLAY_ID) }

    private fun createDisplay(displayId: Int): Display = mock {
        on { getDisplayId() } doReturn displayId
    }

    companion object {
        private const val DEFAULT_DISPLAY_ID = Display.DEFAULT_DISPLAY
        private const val NON_DEFAULT_DISPLAY_ID = DEFAULT_DISPLAY_ID + 1
        private const val NON_EXISTING_DISPLAY_ID = DEFAULT_DISPLAY_ID + 2
    }
}
