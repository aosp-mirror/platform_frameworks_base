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

package com.android.systemui.statusbar.notification.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.notification.data.repository.NotificationLaunchAnimationRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationLaunchAnimationInteractorTest : SysuiTestCase() {
    private val repository = NotificationLaunchAnimationRepository()
    private val underTest = NotificationLaunchAnimationInteractor(repository)

    @Test
    fun setIsLaunchAnimationRunning_startsAsFalse() = runTest {
        val latest by collectLastValue(underTest.isLaunchAnimationRunning)

        assertThat(latest).isFalse()
    }

    @Test
    fun setIsLaunchAnimationRunning_false_emitsTrue() = runTest {
        val latest by collectLastValue(underTest.isLaunchAnimationRunning)

        underTest.setIsLaunchAnimationRunning(true)

        assertThat(latest).isTrue()
    }

    @Test
    fun setIsLaunchAnimationRunning_false_emitsFalse() = runTest {
        val latest by collectLastValue(underTest.isLaunchAnimationRunning)
        underTest.setIsLaunchAnimationRunning(true)

        // WHEN the animation is no longer running
        underTest.setIsLaunchAnimationRunning(false)

        // THEN the flow emits false
        assertThat(latest).isFalse()
    }
}
