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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_PSS_TASK_SWITCHER
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.taskswitcher.ui.TaskSwitcherNotificationCoordinator
import com.android.systemui.util.mockito.mock
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

@RunWith(AndroidJUnit4::class)
@SmallTest
class MediaProjectionTaskSwitcherCoreStartableTest : SysuiTestCase() {

    private val coordinator = mock<TaskSwitcherNotificationCoordinator>()

    private val coreStartable =
        MediaProjectionTaskSwitcherCoreStartable(notificationCoordinatorLazy = { coordinator })

    @Test
    fun start_flagEnabled_startsCoordinator() {
        mSetFlagsRule.enableFlags(FLAG_PSS_TASK_SWITCHER)

        coreStartable.start()

        verify(coordinator).start()
    }

    @Test
    fun start_flagDisabled_doesNotStartCoordinator() {
        mSetFlagsRule.disableFlags(FLAG_PSS_TASK_SWITCHER)

        coreStartable.start()

        verifyZeroInteractions(coordinator)
    }
}
