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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationStackAppearanceInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationsPlaceholderViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = kosmos.notificationsPlaceholderViewModel
    @Test
    fun onBoundsChanged_setsNotificationContainerBounds() {
        underTest.onBoundsChanged(left = 5f, top = 5f, right = 5f, bottom = 5f)
        assertThat(kosmos.keyguardInteractor.notificationContainerBounds.value)
            .isEqualTo(NotificationContainerBounds(left = 5f, top = 5f, right = 5f, bottom = 5f))
        assertThat(kosmos.notificationStackAppearanceInteractor.stackBounds.value)
            .isEqualTo(NotificationContainerBounds(left = 5f, top = 5f, right = 5f, bottom = 5f))
    }
    @Test
    fun onContentTopChanged_setsContentTop() {
        underTest.onContentTopChanged(padding = 5f)
        assertThat(kosmos.notificationStackAppearanceInteractor.contentTop.value).isEqualTo(5f)
    }
}
