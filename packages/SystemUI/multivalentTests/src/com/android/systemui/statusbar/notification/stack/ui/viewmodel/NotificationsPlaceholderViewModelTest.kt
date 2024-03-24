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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationsPlaceholderViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = kosmos.notificationsPlaceholderViewModel

    @Test
    fun onBoundsChanged() =
        kosmos.testScope.runTest {
            underTest.onScrimBoundsChanged(left = 5f, top = 15f, right = 25f, bottom = 35f)
            val stackBounds by
                collectLastValue(kosmos.notificationStackAppearanceInteractor.shadeScrimBounds)
            assertThat(stackBounds)
                .isEqualTo(ShadeScrimBounds(left = 5f, top = 15f, right = 25f, bottom = 35f))
        }

    @Test
    fun onStackBoundsChanged() =
        kosmos.testScope.runTest {
            underTest.onStackBoundsChanged(top = 5f, bottom = 500f)
            assertThat(kosmos.notificationStackAppearanceInteractor.stackTop.value).isEqualTo(5f)
            assertThat(kosmos.notificationStackAppearanceInteractor.stackBottom.value)
                .isEqualTo(500f)
        }
}
