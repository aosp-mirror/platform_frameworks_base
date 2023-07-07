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
import android.view.View
import androidx.test.filters.SmallTest
import com.android.settingslib.core.lifecycle.Lifecycle
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.text.Collator

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
    @Mock lateinit var onAppSelected: (ControlsServiceInfo) -> Unit
    @Mock lateinit var favoritesRenderer: FavoritesRenderer
    val resources: Resources = context.resources
    lateinit var adapter: AppAdapter
    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testOnServicesUpdated_nullLoadLabel() {
        adapter = createAdapterWithAuthorizedPanels(emptySet())
        val captor = ArgumentCaptor
            .forClass(ControlsListingController.ControlsListingCallback::class.java)
        val controlsServiceInfo = mock<ControlsServiceInfo>()
        val serviceInfo = listOf(controlsServiceInfo)
        `when`(controlsServiceInfo.loadLabel()).thenReturn(null)
        verify(controlsListingController).observe(any(Lifecycle::class.java), captor.capture())

        captor.value.onServicesUpdated(serviceInfo)
        FakeExecutor.exhaustExecutors(backgroundExecutor, uiExecutor)

        assertThat(adapter.itemCount).isEqualTo(serviceInfo.size)
    }

    @Test
    fun testOnServicesUpdated_showsNotAuthorizedPanels() {
        adapter = createAdapterWithAuthorizedPanels(emptySet())
        val captor = ArgumentCaptor
                .forClass(ControlsListingController.ControlsListingCallback::class.java)
        val serviceInfo = listOf(
                ControlsServiceInfo("no panel", null),
                ControlsServiceInfo("panel", mock())
        )
        verify(controlsListingController).observe(any(Lifecycle::class.java), captor.capture())

        captor.value.onServicesUpdated(serviceInfo)
        FakeExecutor.exhaustExecutors(backgroundExecutor, uiExecutor)

        assertThat(adapter.itemCount).isEqualTo(2)
    }

    @Test
    fun testOnServicesUpdated_doesntShowAuthorizedPanels() {
        adapter = createAdapterWithAuthorizedPanels(setOf(TEST_PACKAGE))

        val captor = ArgumentCaptor
                .forClass(ControlsListingController.ControlsListingCallback::class.java)
        val serviceInfo = listOf(
                ControlsServiceInfo("no panel", null),
                ControlsServiceInfo("panel", ComponentName(TEST_PACKAGE, "cls"))
        )
        verify(controlsListingController).observe(any(Lifecycle::class.java), captor.capture())

        captor.value.onServicesUpdated(serviceInfo)
        FakeExecutor.exhaustExecutors(backgroundExecutor, uiExecutor)

        assertThat(adapter.itemCount).isEqualTo(1)
    }

    @Test
    fun testOnBindSetsClickListenerToCallOnAppSelected() {
        adapter = createAdapterWithAuthorizedPanels(emptySet())

        val captor = ArgumentCaptor
                .forClass(ControlsListingController.ControlsListingCallback::class.java)
        val serviceInfo = listOf(
                ControlsServiceInfo("no panel", null),
                ControlsServiceInfo("panel", ComponentName(TEST_PACKAGE, "cls"))
        )
        verify(controlsListingController).observe(any(Lifecycle::class.java), captor.capture())

        captor.value.onServicesUpdated(serviceInfo)
        FakeExecutor.exhaustExecutors(backgroundExecutor, uiExecutor)

        val sorted = serviceInfo.sortedWith(
                compareBy(Collator.getInstance(resources.configuration.locales[0])) {
                    it.loadLabel() ?: ""
                })

        sorted.forEachIndexed { index, info ->
            val fakeView: View = mock()
            val fakeHolder: AppAdapter.Holder = mock()
            `when`(fakeHolder.view).thenReturn(fakeView)

            clearInvocations(onAppSelected)
            adapter.onBindViewHolder(fakeHolder, index)
            val listenerCaptor: ArgumentCaptor<View.OnClickListener> = argumentCaptor()
            verify(fakeView).setOnClickListener(capture(listenerCaptor))
            listenerCaptor.value.onClick(fakeView)

            verify(onAppSelected).invoke(info)
        }
    }

    private fun createAdapterWithAuthorizedPanels(packages: Set<String>): AppAdapter {
        return AppAdapter(backgroundExecutor,
                uiExecutor,
                lifecycle,
                controlsListingController,
                layoutInflater,
                onAppSelected,
                favoritesRenderer,
                resources,
                packages)
    }

    companion object {
        private fun ControlsServiceInfo(
                label: CharSequence,
                panelComponentName: ComponentName? = null
        ): ControlsServiceInfo {
            return mock {
                `when`(loadLabel()).thenReturn(label)
                `when`(panelActivity).thenReturn(panelComponentName)
                `when`(loadIcon()).thenReturn(mock())
            }
        }

        private const val TEST_PACKAGE = "package"
    }
}
