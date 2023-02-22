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

package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.res.Resources
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.settingslib.core.lifecycle.Lifecycle
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AppAdapterTest : SysuiTestCase() {
    private val fakeSystemClock = FakeSystemClock()
    private val backgroundExecutor = FakeExecutor(fakeSystemClock)
    private val uiExecutor = FakeExecutor(fakeSystemClock)
    @Mock lateinit var lifecycle: Lifecycle
    @Mock lateinit var controlsListingController: ControlsListingController
    @Mock lateinit var layoutInflater: LayoutInflater
    @Mock lateinit var onAppSelected: (ComponentName?) -> Unit
    @Mock lateinit var favoritesRenderer: FavoritesRenderer
    val resources: Resources = context.resources
    lateinit var adapter: AppAdapter
    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        adapter = AppAdapter(backgroundExecutor,
            uiExecutor,
            lifecycle,
            controlsListingController,
            layoutInflater,
            onAppSelected,
            favoritesRenderer,
            resources)
    }

    @Test
    fun testOnServicesUpdated_nullLoadLabel() {
        val captor = ArgumentCaptor
            .forClass(ControlsListingController.ControlsListingCallback::class.java)
        val controlsServiceInfo = mock<ControlsServiceInfo>()
        val serviceInfo = listOf(controlsServiceInfo)
        `when`(controlsServiceInfo.loadLabel()).thenReturn(null)
        verify(controlsListingController).observe(any(Lifecycle::class.java), captor.capture())

        captor.value.onServicesUpdated(serviceInfo)
        backgroundExecutor.runAllReady()
        uiExecutor.runAllReady()

        assertThat(adapter.itemCount).isEqualTo(serviceInfo.size)
    }

    @Test
    fun testOnServicesUpdatedDoesntHavePanels() {
        val captor = ArgumentCaptor
                .forClass(ControlsListingController.ControlsListingCallback::class.java)
        val serviceInfo = listOf(
                ControlsServiceInfo("no panel", null),
                ControlsServiceInfo("panel", mock())
        )
        verify(controlsListingController).observe(any(Lifecycle::class.java), captor.capture())

        captor.value.onServicesUpdated(serviceInfo)
        backgroundExecutor.runAllReady()
        uiExecutor.runAllReady()

        assertThat(adapter.itemCount).isEqualTo(1)
    }

    fun ControlsServiceInfo(
        label: CharSequence,
        panelComponentName: ComponentName? = null
    ): ControlsServiceInfo {
        return mock {
            `when`(this.loadLabel()).thenReturn(label)
            `when`(this.panelActivity).thenReturn(panelComponentName)
            `when`(this.loadIcon()).thenReturn(mock())
        }
    }
}
