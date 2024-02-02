/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.mediaprojection.taskswitcher

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.mediaprojection.taskswitcher.ui.TaskSwitcherNotificationCoordinator
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class MediaProjectionTaskSwitcherCoreStartableTest : SysuiTestCase() {

    @Mock private lateinit var flags: FeatureFlags
    @Mock private lateinit var coordinator: TaskSwitcherNotificationCoordinator

    private lateinit var coreStartable: MediaProjectionTaskSwitcherCoreStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        coreStartable = MediaProjectionTaskSwitcherCoreStartable(coordinator, flags)
    }

    @Test
    fun start_flagEnabled_startsCoordinator() {
        whenever(flags.isEnabled(Flags.PARTIAL_SCREEN_SHARING_TASK_SWITCHER)).thenReturn(true)

        coreStartable.start()

        verify(coordinator).start()
    }

    @Test
    fun start_flagDisabled_doesNotStartCoordinator() {
        whenever(flags.isEnabled(Flags.PARTIAL_SCREEN_SHARING_TASK_SWITCHER)).thenReturn(false)

        coreStartable.start()

        verifyZeroInteractions(coordinator)
    }
}
