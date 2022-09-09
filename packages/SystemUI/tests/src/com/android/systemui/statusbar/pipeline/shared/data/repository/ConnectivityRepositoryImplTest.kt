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

package com.android.systemui.statusbar.pipeline.shared.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlots
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl.Companion.DEFAULT_HIDDEN_ICONS_RESOURCE
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl.Companion.HIDDEN_ICONS_TUNABLE_KEY
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class ConnectivityRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: ConnectivityRepositoryImpl

    @Mock private lateinit var connectivitySlots: ConnectivitySlots
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    private lateinit var scope: CoroutineScope
    @Mock private lateinit var tunerService: TunerService

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        scope = CoroutineScope(IMMEDIATE)

        underTest = ConnectivityRepositoryImpl(
            connectivitySlots,
            context,
            dumpManager,
            logger,
            scope,
            tunerService,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun forceHiddenSlots_initiallyGetsDefault() = runBlocking(IMMEDIATE) {
        setUpEthernetWifiMobileSlotNames()
        context.getOrCreateTestableResources().addOverride(
            DEFAULT_HIDDEN_ICONS_RESOURCE,
            arrayOf(SLOT_WIFI, SLOT_ETHERNET)
        )
        // Re-create our [ConnectivityRepositoryImpl], since it fetches
        // config_statusBarIconsToExclude when it's first constructed
        underTest = ConnectivityRepositoryImpl(
            connectivitySlots,
            context,
            dumpManager,
            logger,
            scope,
            tunerService,
        )

        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).containsExactly(ConnectivitySlot.ETHERNET, ConnectivitySlot.WIFI)

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_slotNamesAdded_flowHasSlots() = runBlocking(IMMEDIATE) {
        setUpEthernetWifiMobileSlotNames()

        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, SLOT_MOBILE)

        assertThat(latest).containsExactly(ConnectivitySlot.MOBILE)

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_wrongKey_doesNotUpdate() = runBlocking(IMMEDIATE) {
        setUpEthernetWifiMobileSlotNames()

        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, SLOT_MOBILE)

        // WHEN onTuningChanged with the wrong key
        getTunable().onTuningChanged("wrongKey", SLOT_WIFI)
        yield()

        // THEN we didn't update our value and still have the old one
        assertThat(latest).containsExactly(ConnectivitySlot.MOBILE)

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_slotNamesAddedThenNull_flowHasDefault() = runBlocking(IMMEDIATE) {
        setUpEthernetWifiMobileSlotNames()
        context.getOrCreateTestableResources().addOverride(
            DEFAULT_HIDDEN_ICONS_RESOURCE,
            arrayOf(SLOT_WIFI, SLOT_ETHERNET)
        )
        // Re-create our [ConnectivityRepositoryImpl], since it fetches
        // config_statusBarIconsToExclude when it's first constructed
        underTest = ConnectivityRepositoryImpl(
            connectivitySlots,
            context,
            dumpManager,
            logger,
            scope,
            tunerService,
        )

        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        // First, update the slots
        getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, SLOT_MOBILE)
        assertThat(latest).containsExactly(ConnectivitySlot.MOBILE)

        // WHEN we update to a null value
        getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, null)
        yield()

        // THEN we go back to our default value
        assertThat(latest).containsExactly(ConnectivitySlot.ETHERNET, ConnectivitySlot.WIFI)

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_someInvalidSlotNames_flowHasValidSlotsOnly() = runBlocking(IMMEDIATE) {
        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        whenever(connectivitySlots.getSlotFromName(SLOT_WIFI))
            .thenReturn(ConnectivitySlot.WIFI)
        whenever(connectivitySlots.getSlotFromName(SLOT_MOBILE)).thenReturn(null)

        getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_WIFI,$SLOT_MOBILE")

        assertThat(latest).containsExactly(ConnectivitySlot.WIFI)

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_someEmptySlotNames_flowHasValidSlotsOnly() = runBlocking(IMMEDIATE) {
        setUpEthernetWifiMobileSlotNames()

        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        // WHEN there's empty and blank slot names
        getTunable().onTuningChanged(
            HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_MOBILE,  ,,$SLOT_WIFI"
        )

        // THEN we skip that slot but still process the other ones
        assertThat(latest).containsExactly(ConnectivitySlot.WIFI, ConnectivitySlot.MOBILE)

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_allInvalidOrEmptySlotNames_flowHasEmpty() = runBlocking(IMMEDIATE) {
        var latest: Set<ConnectivitySlot>? = null
        val job = underTest
            .forceHiddenSlots
            .onEach { latest = it }
            .launchIn(this)

        whenever(connectivitySlots.getSlotFromName(SLOT_WIFI)).thenReturn(null)
        whenever(connectivitySlots.getSlotFromName(SLOT_ETHERNET)).thenReturn(null)
        whenever(connectivitySlots.getSlotFromName(SLOT_MOBILE)).thenReturn(null)

        getTunable().onTuningChanged(
            HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_MOBILE,,$SLOT_WIFI,$SLOT_ETHERNET,,,"
        )

        assertThat(latest).isEmpty()

        job.cancel()
    }

    @Test
    fun forceHiddenSlots_newSubscriberGetsCurrentValue() = runBlocking(IMMEDIATE) {
        setUpEthernetWifiMobileSlotNames()

        var latest1: Set<ConnectivitySlot>? = null
        val job1 = underTest
            .forceHiddenSlots
            .onEach { latest1 = it }
            .launchIn(this)

        getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_WIFI,$SLOT_ETHERNET")

        assertThat(latest1).containsExactly(ConnectivitySlot.WIFI, ConnectivitySlot.ETHERNET)

        // WHEN we add a second subscriber after having already emitted a value
        var latest2: Set<ConnectivitySlot>? = null
        val job2 = underTest
            .forceHiddenSlots
            .onEach { latest2 = it }
            .launchIn(this)

        // THEN the second subscribe receives the already-emitted value
        assertThat(latest2).containsExactly(ConnectivitySlot.WIFI, ConnectivitySlot.ETHERNET)

        job1.cancel()
        job2.cancel()
    }

    private fun getTunable(): TunerService.Tunable {
        val callbackCaptor = argumentCaptor<TunerService.Tunable>()
        Mockito.verify(tunerService).addTunable(callbackCaptor.capture(), any())
        return callbackCaptor.value!!
    }

    private fun setUpEthernetWifiMobileSlotNames() {
        whenever(connectivitySlots.getSlotFromName(SLOT_ETHERNET))
            .thenReturn(ConnectivitySlot.ETHERNET)
        whenever(connectivitySlots.getSlotFromName(SLOT_WIFI))
            .thenReturn(ConnectivitySlot.WIFI)
        whenever(connectivitySlots.getSlotFromName(SLOT_MOBILE))
            .thenReturn(ConnectivitySlot.MOBILE)
    }

    companion object {
        private const val SLOT_ETHERNET = "ethernet"
        private const val SLOT_WIFI = "wifi"
        private const val SLOT_MOBILE = "mobile"
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
