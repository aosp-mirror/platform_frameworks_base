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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.settingslib.graph.SignalDrawable
import com.android.settingslib.mobile.TelephonyIcons.THREE_G
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconInteractor
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class MobileIconViewModelTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconViewModel
    private val interactor = FakeMobileIconInteractor()
    @Mock private lateinit var logger: ConnectivityPipelineLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        interactor.apply {
            setLevel(1)
            setIsDefaultDataEnabled(true)
            setIsFailedConnection(false)
            setIconGroup(THREE_G)
            setIsEmergencyOnly(false)
            setNumberOfLevels(4)
            isDataConnected.value = true
        }
        underTest = MobileIconViewModel(SUB_1_ID, interactor, logger)
    }

    @Test
    fun iconId_correctLevel_notCutout() =
        runBlocking(IMMEDIATE) {
            var latest: Int? = null
            val job = underTest.iconId.onEach { latest = it }.launchIn(this)
            val expected = defaultSignal()

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun iconId_cutout_whenDefaultDataDisabled() =
        runBlocking(IMMEDIATE) {
            interactor.setIsDefaultDataEnabled(false)

            var latest: Int? = null
            val job = underTest.iconId.onEach { latest = it }.launchIn(this)
            val expected = defaultSignal(level = 1, connected = false)

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_dataEnabled_groupIsRepresented() =
        runBlocking(IMMEDIATE) {
            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            interactor.setIconGroup(THREE_G)

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_nullWhenDisabled() =
        runBlocking(IMMEDIATE) {
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(false)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_nullWhenFailedConnection() =
        runBlocking(IMMEDIATE) {
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(true)
            interactor.setIsFailedConnection(true)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_nullWhenDataDisconnects() =
        runBlocking(IMMEDIATE) {
            val initial =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )

            interactor.setIconGroup(THREE_G)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.setIconGroup(THREE_G)
            assertThat(latest).isEqualTo(initial)

            interactor.isDataConnected.value = false
            yield()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_null_changeToDisabled() =
        runBlocking(IMMEDIATE) {
            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(true)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            interactor.setIsDataEnabled(false)
            yield()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenDisabled() =
        runBlocking(IMMEDIATE) {
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(true)
            interactor.alwaysShowDataRatIcon.value = true

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenDisconnected() =
        runBlocking(IMMEDIATE) {
            interactor.setIconGroup(THREE_G)
            interactor.isDataConnected.value = false
            interactor.alwaysShowDataRatIcon.value = true

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenFailedConnection() =
        runBlocking(IMMEDIATE) {
            interactor.setIconGroup(THREE_G)
            interactor.setIsFailedConnection(true)
            interactor.alwaysShowDataRatIcon.value = true

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    /** Convenience constructor for these tests */
    private fun defaultSignal(
        level: Int = 1,
        connected: Boolean = true,
    ): Int {
        return SignalDrawable.getState(level, /* numLevels */ 4, !connected)
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private const val SUB_1_ID = 1
    }
}
