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

package com.android.systemui.statusbar.notification.fsi

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.TaskView
import com.android.wm.shell.TaskViewFactory
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.function.Consumer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class FsiChromeViewModelFactoryTest : SysuiTestCase() {
    @Mock private lateinit var taskViewFactoryOptional: Optional<TaskViewFactory>
    @Mock private lateinit var taskViewFactory: TaskViewFactory
    @Mock lateinit var taskView: TaskView

    @Main var mainExecutor = FakeExecutor(FakeSystemClock())
    lateinit var viewModelFactory: FsiChromeViewModelFactory

    private val fakeInfoFlow = MutableStateFlow<FsiChromeRepo.FSIInfo?>(null)
    private var fsiChromeRepo: FsiChromeRepo =
        mock<FsiChromeRepo>().apply { whenever(infoFlow).thenReturn(fakeInfoFlow) }

    private val appName = "appName"
    private val appIcon: Drawable = context.getDrawable(com.android.systemui.R.drawable.ic_android)
    private val fsi: PendingIntent = Mockito.mock(PendingIntent::class.java)
    private val fsiInfo = FsiChromeRepo.FSIInfo(appName, appIcon, fsi)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(taskViewFactoryOptional.get()).thenReturn(taskViewFactory)

        viewModelFactory =
            FsiChromeViewModelFactory(fsiChromeRepo, taskViewFactoryOptional, context, mainExecutor)
    }

    @Test
    fun testViewModelFlow_update_createsTaskView() {
        runTest {
            val latestViewModel =
                viewModelFactory.viewModelFlow
                    .onStart { FsiDebug.log("viewModelFactory.viewModelFlow.onStart") }
                    .stateIn(
                        backgroundScope, // stateIn runs forever, don't count it as test coroutine
                        SharingStarted.Eagerly,
                        null
                    )
            runCurrent() // Drain queued backgroundScope operations

            // Test: emit the fake FSIInfo
            fakeInfoFlow.emit(fsiInfo)
            runCurrent()

            val taskViewFactoryCallback: Consumer<TaskView> = withArgCaptor {
                verify(taskViewFactory).create(any(), any(), capture())
            }
            taskViewFactoryCallback.accept(taskView) // this will call k.resume
            runCurrent()

            // Verify that the factory has produced a new ViewModel
            // containing the relevant data from FsiInfo
            val expectedViewModel =
                FsiChromeViewModel(appName, appIcon, taskView, fsi, fsiChromeRepo)

            assertThat(latestViewModel.value).isEqualTo(expectedViewModel)
        }
    }
}
