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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.row.data.repository.fakeNotificationRowRepository
import com.android.systemui.statusbar.notification.row.shared.EnRouteContentModel
import com.android.systemui.statusbar.notification.row.shared.IconModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
@SmallTest
@EnableFlags(RichOngoingNotificationFlag.FLAG_NAME)
class EnRouteViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeNotificationRowRepository

    private var contentModel: EnRouteContentModel?
        get() = repository.richOngoingContentModel.value as? EnRouteContentModel
        set(value) {
            repository.richOngoingContentModel.value = value
        }

    private lateinit var underTest: EnRouteViewModel

    @Before
    fun setup() {
        underTest = kosmos.getEnRouteViewModel(repository)
    }

    @Test
    fun viewModelShowsContent() =
        testScope.runTest {
            val title by collectLastValue(underTest.title)
            val text by collectLastValue(underTest.text)
            contentModel =
                exampleEnRouteContent(
                    title = "Example EnRoute Title",
                    text = "Example EnRoute Text",
                )
            assertThat(title).isEqualTo("Example EnRoute Title")
            assertThat(text).isEqualTo("Example EnRoute Text")
        }

    private fun exampleEnRouteContent(
        icon: IconModel = mock(),
        title: CharSequence = "example text",
        text: CharSequence = "example title",
    ) =
        EnRouteContentModel(
            smallIcon = icon,
            title = title,
            text = text,
        )
}
