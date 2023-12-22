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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class RemoteInputRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var remoteInputManager: NotificationRemoteInputManager

    private lateinit var testScope: TestScope
    private lateinit var underTest: RemoteInputRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope()
        underTest = RemoteInputRepositoryImpl(remoteInputManager)
    }

    @Test
    fun isRemoteInputActive_updatesOnChange() =
        testScope.runTest {
            val active by collectLastValue(underTest.isRemoteInputActive)
            runCurrent()
            assertThat(active).isFalse()

            val callback = withArgCaptor {
                verify(remoteInputManager).addControllerCallback(capture())
            }

            callback.onRemoteInputActive(true)
            runCurrent()
            assertThat(active).isTrue()

            callback.onRemoteInputActive(false)
            runCurrent()
            assertThat(active).isFalse()
        }
}
