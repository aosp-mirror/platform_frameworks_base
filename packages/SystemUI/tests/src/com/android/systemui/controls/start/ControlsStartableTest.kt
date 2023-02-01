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
 *
 */

package com.android.systemui.controls.start

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsStartableTest : SysuiTestCase() {

    @Mock private lateinit var controlsController: ControlsController
    @Mock private lateinit var controlsListingController: ControlsListingController
    @Mock private lateinit var userTracker: UserTracker

    private lateinit var fakeExecutor: FakeExecutor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf<String>()
        )

        fakeExecutor = FakeExecutor(FakeSystemClock())
    }

    @Test
    fun testDisabledNothingIsCalled() {
        createStartable(enabled = false).start()

        verifyZeroInteractions(controlsController, controlsListingController, userTracker)
    }

    @Test
    fun testNoPreferredPackagesNoDefaultSelected_noNewSelection() {
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).start()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testPreferredPackagesNotInstalled_noNewSelection() {
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE_PANEL)
        )
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        `when`(controlsListingController.getCurrentServices()).thenReturn(emptyList())

        createStartable(enabled = true).start()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testPreferredPackageNotPanel_noNewSelection() {
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE_PANEL)
        )
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT, "not panel", hasPanel = false))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).start()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testExistingSelection_noNewSelection() {
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE_PANEL)
        )
        `when`(controlsController.getPreferredSelection())
            .thenReturn(mock<SelectedItem.PanelItem>())
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).start()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testPanelAdded() {
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE_PANEL)
        )
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).start()

        verify(controlsController).setPreferredSelection(listings[0].toPanelItem())
    }

    @Test
    fun testMultiplePreferredOnlyOnePanel_panelAdded() {
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf("other_package", TEST_PACKAGE_PANEL)
        )
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings =
            listOf(
                ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true),
                ControlsServiceInfo(ComponentName("other_package", "cls"), "non panel", false)
            )
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).start()

        verify(controlsController).setPreferredSelection(listings[0].toPanelItem())
    }

    @Test
    fun testMultiplePreferredMultiplePanels_firstPreferredAdded() {
        context.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE_PANEL, "other_package")
        )
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings =
            listOf(
                ControlsServiceInfo(ComponentName("other_package", "cls"), "panel", true),
                ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true)
            )
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).start()

        verify(controlsController).setPreferredSelection(listings[1].toPanelItem())
    }

    @Test
    fun testPreferredSelectionIsPanel_bindOnStart() {
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)
        `when`(controlsController.getPreferredSelection()).thenReturn(listings[0].toPanelItem())

        createStartable(enabled = true).start()

        verify(controlsController).bindComponentForPanel(TEST_COMPONENT_PANEL)
    }

    @Test
    fun testPreferredSelectionPanel_listingNoPanel_notBind() {
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = false))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)
        `when`(controlsController.getPreferredSelection())
            .thenReturn(SelectedItem.PanelItem("panel", TEST_COMPONENT_PANEL))

        createStartable(enabled = true).start()

        verify(controlsController, never()).bindComponentForPanel(any())
    }

    @Test
    fun testNotPanelSelection_noBind() {
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = false))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)

        createStartable(enabled = true).start()

        verify(controlsController, never()).bindComponentForPanel(any())
    }

    private fun createStartable(enabled: Boolean): ControlsStartable {
        val component: ControlsComponent =
            mock() {
                `when`(isEnabled()).thenReturn(enabled)
                if (enabled) {
                    `when`(getControlsController()).thenReturn(Optional.of(controlsController))
                    `when`(getControlsListingController())
                        .thenReturn(Optional.of(controlsListingController))
                } else {
                    `when`(getControlsController()).thenReturn(Optional.empty())
                    `when`(getControlsListingController()).thenReturn(Optional.empty())
                }
            }
        return ControlsStartable(context.resources, fakeExecutor, component, userTracker)
    }

    private fun ControlsServiceInfo(
        componentName: ComponentName,
        label: CharSequence,
        hasPanel: Boolean
    ): ControlsServiceInfo {
        val serviceInfo =
            ServiceInfo().apply {
                applicationInfo = ApplicationInfo()
                packageName = componentName.packageName
                name = componentName.className
            }
        return FakeControlsServiceInfo(context, serviceInfo, label, hasPanel)
    }

    private class FakeControlsServiceInfo(
        context: Context,
        serviceInfo: ServiceInfo,
        private val label: CharSequence,
        hasPanel: Boolean
    ) : ControlsServiceInfo(context, serviceInfo) {

        init {
            if (hasPanel) {
                panelActivity = serviceInfo.componentName
            }
        }

        override fun loadLabel(): CharSequence {
            return label
        }
    }

    companion object {
        private fun ControlsServiceInfo.toPanelItem(): SelectedItem.PanelItem {
            if (panelActivity == null) {
                throw IllegalArgumentException("$this is not a panel")
            }
            return SelectedItem.PanelItem(loadLabel(), componentName)
        }

        private const val TEST_PACKAGE = "pkg"
        private val TEST_COMPONENT = ComponentName(TEST_PACKAGE, "service")
        private const val TEST_PACKAGE_PANEL = "pkg.panel"
        private val TEST_COMPONENT_PANEL = ComponentName(TEST_PACKAGE_PANEL, "service")
    }
}
