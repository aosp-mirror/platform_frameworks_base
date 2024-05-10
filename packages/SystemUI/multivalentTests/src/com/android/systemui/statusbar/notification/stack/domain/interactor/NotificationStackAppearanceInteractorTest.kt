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

package com.android.systemui.statusbar.notification.stack.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationStackAppearanceInteractorTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.notificationStackAppearanceInteractor

    @Test
    fun stackBounds() =
        testScope.runTest {
            val stackBounds by collectLastValue(underTest.stackBounds)

            val bounds1 =
                NotificationContainerBounds(
                    top = 100f,
                    bottom = 200f,
                    isAnimated = true,
                )
            underTest.setStackBounds(bounds1)
            assertThat(stackBounds).isEqualTo(bounds1)

            val bounds2 =
                NotificationContainerBounds(
                    top = 200f,
                    bottom = 300f,
                    isAnimated = false,
                )
            underTest.setStackBounds(bounds2)
            assertThat(stackBounds).isEqualTo(bounds2)
        }

    @Test(expected = IllegalStateException::class)
    fun setStackBounds_withImproperBounds_throwsException() =
        testScope.runTest {
            underTest.setStackBounds(
                NotificationContainerBounds(
                    top = 100f,
                    bottom = 99f,
                )
            )
        }
}
