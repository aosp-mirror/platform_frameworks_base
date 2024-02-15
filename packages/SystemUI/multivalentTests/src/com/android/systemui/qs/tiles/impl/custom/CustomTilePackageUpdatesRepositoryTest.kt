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

package com.android.systemui.qs.tiles.impl.custom

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTilePackageUpdatesRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTilePackageUpdatesRepositoryImpl
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@SuppressLint("UnspecifiedRegisterReceiverFlag") // Not needed in the test
class CustomTilePackageUpdatesRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var mockedContext: Context
    @Captor private lateinit var listenerCaptor: ArgumentCaptor<BroadcastReceiver>

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: CustomTilePackageUpdatesRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            CustomTilePackageUpdatesRepositoryImpl(
                TileSpec.create(COMPONENT_1),
                mockedContext,
                testScope.backgroundScope,
                testDispatcher,
            )
    }

    @Test
    fun packageChangesEmittedForTilePassing() =
        testScope.runTest {
            val events by collectValues(underTest.getPackageChangesForUser(USER_1))
            runCurrent()

            emitPackageChange(COMPONENT_1)
            runCurrent()

            assertThat(events).hasSize(1)
        }

    @Test
    fun packageChangesEmittedForAnotherTileIgnored() =
        testScope.runTest {
            val events by collectValues(underTest.getPackageChangesForUser(USER_1))
            runCurrent()

            emitPackageChange(COMPONENT_2)
            runCurrent()

            assertThat(events).isEmpty()
        }

    @Test
    fun unsupportedActionDoesntEmmit() =
        testScope.runTest {
            val events by collectValues(underTest.getPackageChangesForUser(USER_1))
            runCurrent()

            verify(mockedContext)
                .registerReceiverAsUser(
                    capture(listenerCaptor),
                    any(),
                    any(),
                    nullable(),
                    nullable()
                )
            listenerCaptor.value.onReceive(mockedContext, Intent(Intent.ACTION_MAIN))
            runCurrent()

            assertThat(events).isEmpty()
        }

    @Test
    fun cachesCallsPerUser() =
        testScope.runTest {
            underTest.getPackageChangesForUser(USER_1).launchIn(backgroundScope)
            underTest.getPackageChangesForUser(USER_1).launchIn(backgroundScope)
            underTest.getPackageChangesForUser(USER_2).launchIn(backgroundScope)
            underTest.getPackageChangesForUser(USER_2).launchIn(backgroundScope)
            runCurrent()

            // Register receiver once per each user
            verify(mockedContext)
                .registerReceiverAsUser(any(), eq(USER_1), any(), nullable(), nullable())
            verify(mockedContext)
                .registerReceiverAsUser(any(), eq(USER_2), any(), nullable(), nullable())
        }

    private fun emitPackageChange(componentName: ComponentName) {
        verify(mockedContext)
            .registerReceiverAsUser(capture(listenerCaptor), any(), any(), nullable(), nullable())
        listenerCaptor.value.onReceive(
            mockedContext,
            Intent(Intent.ACTION_PACKAGE_CHANGED).apply {
                type = IntentFilter.SCHEME_PACKAGE
                putExtra(
                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                    arrayOf(componentName.packageName)
                )
            }
        )
    }

    private companion object {
        val USER_1 = UserHandle(1)
        val USER_2 = UserHandle(2)
        val COMPONENT_1 = ComponentName("pkg.test.1", "cls.test")
        val COMPONENT_2 = ComponentName("pkg.test.2", "cls.test")
    }
}
