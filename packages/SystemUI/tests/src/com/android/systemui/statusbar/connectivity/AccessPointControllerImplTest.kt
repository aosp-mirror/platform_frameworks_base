/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity

import android.os.UserManager
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.lifecycle.Lifecycle
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.capture
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiPickerTracker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class AccessPointControllerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var wifiPickerTrackerFactory:
            AccessPointControllerImpl.WifiPickerTrackerFactory
    @Mock
    private lateinit var wifiPickerTracker: WifiPickerTracker
    @Mock
    private lateinit var callback: AccessPointController.AccessPointCallback
    @Mock
    private lateinit var otherCallback: AccessPointController.AccessPointCallback
    @Mock
    private lateinit var wifiEntryConnected: WifiEntry
    @Mock
    private lateinit var wifiEntryOther: WifiEntry
    @Captor
    private lateinit var wifiEntryListCaptor: ArgumentCaptor<List<WifiEntry>>

    private val instantExecutor = Executor { it.run() }
    private lateinit var controller: AccessPointControllerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(wifiPickerTrackerFactory.create(any(), any())).thenReturn(wifiPickerTracker)

        `when`(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntryConnected)
        `when`(wifiPickerTracker.wifiEntries).thenReturn(ArrayList<WifiEntry>().apply {
            add(wifiEntryOther)
        })

        controller = AccessPointControllerImpl(
                userManager,
                userTracker,
                instantExecutor,
                wifiPickerTrackerFactory
        )

        controller.init()
    }

    @Test
    fun testInitialLifecycleStateCreated() {
        assertThat(controller.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun testLifecycleStartedAfterFirstCallback() {
        controller.addAccessPointCallback(callback)
        assertThat(controller.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun testLifecycleBackToCreatedAfterRemovingOnlyCallback() {
        controller.addAccessPointCallback(callback)
        controller.removeAccessPointCallback(callback)

        assertThat(controller.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun testLifecycleStillStartedAfterRemovingSecondCallback() {
        controller.addAccessPointCallback(callback)
        controller.addAccessPointCallback(otherCallback)
        controller.removeAccessPointCallback(callback)

        assertThat(controller.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun testScanForAccessPointsTriggersCallbackWithEntriesInOrder() {
        controller.addAccessPointCallback(callback)
        controller.scanForAccessPoints()

        verify(callback).onAccessPointsChanged(capture(wifiEntryListCaptor))

        assertThat(wifiEntryListCaptor.value).containsExactly(wifiEntryConnected, wifiEntryOther)
    }

    @Test
    fun testOnWifiStateChangedTriggersCallbackWithEntriesInOrder() {
        controller.addAccessPointCallback(callback)
        controller.onWifiStateChanged()

        verify(callback).onAccessPointsChanged(capture(wifiEntryListCaptor))

        assertThat(wifiEntryListCaptor.value).containsExactly(wifiEntryConnected, wifiEntryOther)
    }

    @Test
    fun testOnWifiEntriesChangedTriggersCallbackWithEntriesInOrder() {
        controller.addAccessPointCallback(callback)
        controller.onWifiEntriesChanged()

        verify(callback).onAccessPointsChanged(capture(wifiEntryListCaptor))

        assertThat(wifiEntryListCaptor.value).containsExactly(wifiEntryConnected, wifiEntryOther)
    }

    @Test
    fun testOnNumSavedNetworksChangedDoesntTriggerCallback() {
        controller.addAccessPointCallback(callback)
        controller.onNumSavedNetworksChanged()

        verify(callback, never()).onAccessPointsChanged(anyList())
    }

    @Test
    fun testOnNumSavedSubscriptionsChangedDoesntTriggerCallback() {
        controller.addAccessPointCallback(callback)
        controller.onNumSavedSubscriptionsChanged()

        verify(callback, never()).onAccessPointsChanged(anyList())
    }

    @Test
    fun testReturnEmptyListWhenNoWifiPickerTracker() {
        `when`(wifiPickerTrackerFactory.create(any(), any())).thenReturn(null)
        val otherController = AccessPointControllerImpl(
                userManager,
                userTracker,
                instantExecutor,
                wifiPickerTrackerFactory
        )
        otherController.init()

        otherController.addAccessPointCallback(callback)
        otherController.scanForAccessPoints()

        verify(callback).onAccessPointsChanged(capture(wifiEntryListCaptor))

        assertThat(wifiEntryListCaptor.value).isEmpty()
    }

    @Test
    fun connectToNullEntry() {
        controller.addAccessPointCallback(callback)

        assertThat(controller.connect(null)).isFalse()

        verify(callback, never()).onSettingsActivityTriggered(any())
    }

    @Test
    fun connectToSavedWifiEntry() {
        controller.addAccessPointCallback(callback)
        `when`(wifiEntryOther.isSaved).thenReturn(true)

        assertThat(controller.connect(wifiEntryOther)).isFalse()

        verify(wifiEntryOther).connect(any())
        verify(callback, never()).onSettingsActivityTriggered(any())
    }

    @Test
    fun connectToSecuredNotSavedWifiEntry() {
        controller.addAccessPointCallback(callback)
        `when`(wifiEntryOther.isSaved).thenReturn(false)
        `when`(wifiEntryOther.security).thenReturn(WifiEntry.SECURITY_EAP)

        // True means we will launch WifiSettings
        assertThat(controller.connect(wifiEntryOther)).isTrue()

        verify(wifiEntryOther, never()).connect(any())
        verify(callback).onSettingsActivityTriggered(any())
    }

    @Test
    fun connectToNotSecuredNotSavedWifiEntry() {
        controller.addAccessPointCallback(callback)
        `when`(wifiEntryOther.isSaved).thenReturn(false)
        `when`(wifiEntryOther.security).thenReturn(WifiEntry.SECURITY_NONE)

        assertThat(controller.connect(wifiEntryOther)).isFalse()

        verify(wifiEntryOther).connect(any())
        verify(callback, never()).onSettingsActivityTriggered(any())
    }
}