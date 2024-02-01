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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.data.repository.fakePackageChangeRepository
import com.android.systemui.common.data.repository.packageChangeRepository
import com.android.systemui.common.data.shared.model.PackageChangeModel
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.UserTracker
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    @Mock private lateinit var controlsController: ControlsController
    @Mock private lateinit var controlsListingController: ControlsListingController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var authorizedPanelsRepository: AuthorizedPanelsRepository
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    private lateinit var preferredPanelsRepository: SelectedComponentRepository

    private lateinit var fakeExecutor: FakeExecutor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(authorizedPanelsRepository.getPreferredPackages()).thenReturn(setOf())
        whenever(userManager.isUserUnlocked(anyInt())).thenReturn(true)
        whenever(userTracker.userHandle).thenReturn(UserHandle.of(1))

        fakeExecutor = FakeExecutor(FakeSystemClock())
        preferredPanelsRepository = kosmos.selectedComponentRepository
    }

    @Test
    fun testDisabledNothingIsCalled() {
        createStartable(enabled = false).apply {
            start()
            onBootCompleted()
        }

        verifyZeroInteractions(controlsController, controlsListingController, userTracker)
    }

    @Test
    fun testNothingCalledOnStart() {
        createStartable(enabled = true).start()

        fakeExecutor.advanceClockToLast()
        fakeExecutor.runAllReady()

        verifyZeroInteractions(controlsController, controlsListingController, userTracker)
    }

    @Test
    fun testNoPreferredPackagesNoDefaultSelected_noNewSelection() {
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        setUpControlsListingControls(listings)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testPreferredPackagesNotInstalled_noNewSelection() {
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        setUpControlsListingControls(emptyList())

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testPreferredPackageNotPanel_noNewSelection() {
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT, "not panel", hasPanel = false))
        setUpControlsListingControls(listings)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testExistingSelection_noNewSelection() {
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection())
            .thenReturn(mock<SelectedItem.PanelItem>())
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        setUpControlsListingControls(listings)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testPanelAdded() {
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        setUpControlsListingControls(listings)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController).setPreferredSelection(listings[0].toPanelItem())
    }

    @Test
    fun testMultiplePreferredOnlyOnePanel_panelAdded() {
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings =
            listOf(
                ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true),
                ControlsServiceInfo(ComponentName("other_package", "cls"), "non panel", false)
            )
        setUpControlsListingControls(listings)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController).setPreferredSelection(listings[0].toPanelItem())
    }

    @Test
    fun testMultiplePreferredMultiplePanels_firstPreferredAdded() {
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings =
            listOf(
                ControlsServiceInfo(ComponentName("other_package", "cls"), "panel", true),
                ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true)
            )
        setUpControlsListingControls(listings)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController).setPreferredSelection(listings[1].toPanelItem())
    }

    @Test
    fun testPreferredSelectionIsPanel_bindOnBoot() {
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        setUpControlsListingControls(listings)
        `when`(controlsController.getPreferredSelection()).thenReturn(listings[0].toPanelItem())

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController).bindComponentForPanel(TEST_COMPONENT_PANEL)
    }

    @Test
    fun testPreferredSelectionIsPanel_userNotUnlocked_notBind() {
        whenever(userManager.isUserUnlocked(anyInt())).thenReturn(false)

        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        setUpControlsListingControls(listings)
        `when`(controlsController.getPreferredSelection()).thenReturn(listings[0].toPanelItem())

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).bindComponentForPanel(TEST_COMPONENT_PANEL)
    }

    @Test
    fun testPreferredSelectionIsPanel_userNotUnlocked_broadcastRegistered_broadcastSentBinds() {
        whenever(userManager.isUserUnlocked(anyInt())).thenReturn(false)

        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        setUpControlsListingControls(listings)
        `when`(controlsController.getPreferredSelection()).thenReturn(listings[0].toPanelItem())

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        val intentFilterCaptor = argumentCaptor<IntentFilter>()
        val receiverCaptor = argumentCaptor<BroadcastReceiver>()

        verify(broadcastDispatcher)
            .registerReceiver(
                capture(receiverCaptor),
                capture(intentFilterCaptor),
                eq(fakeExecutor),
                nullable(),
                anyInt(),
                nullable()
            )
        assertThat(intentFilterCaptor.value.matchAction(Intent.ACTION_USER_UNLOCKED)).isTrue()

        // User is unlocked
        whenever(userManager.isUserUnlocked(anyInt())).thenReturn(true)
        receiverCaptor.value.onReceive(mock(), Intent(Intent.ACTION_USER_UNLOCKED))

        verify(controlsController).bindComponentForPanel(TEST_COMPONENT_PANEL)
    }

    @Test
    fun testPreferredSelectionPanel_listingNoPanel_notBind() {
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = false))
        setUpControlsListingControls(listings)
        `when`(controlsController.getPreferredSelection())
            .thenReturn(SelectedItem.PanelItem("panel", TEST_COMPONENT_PANEL))

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).bindComponentForPanel(any())
    }

    @Test
    fun testNotPanelSelection_noBind() {
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = false))
        setUpControlsListingControls(listings)
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)

        createStartable(enabled = true).onBootCompleted()
        fakeExecutor.runAllReady()

        verify(controlsController, never()).bindComponentForPanel(any())
    }

    @Test
    fun testAlreadyAddedPanel_noNewSelection() {
        preferredPanelsRepository.setShouldAddDefaultComponent(false)
        whenever(authorizedPanelsRepository.getPreferredPackages())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))
        `when`(controlsController.getPreferredSelection()).thenReturn(SelectedItem.EMPTY_SELECTION)
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        `when`(controlsListingController.getCurrentServices()).thenReturn(listings)

        createStartable(enabled = true).onBootCompleted()

        verify(controlsController, never()).setPreferredSelection(any())
    }

    @Test
    fun testSelectedComponentIsUninstalled() =
        with(kosmos) {
            testScope.runTest {
                val selectedComponent =
                    SelectedComponentRepository.SelectedComponent(
                        "panel",
                        TEST_COMPONENT_PANEL,
                        isPanel = true
                    )
                preferredPanelsRepository.setSelectedComponent(selectedComponent)
                val activeUser = UserHandle.of(100)
                whenever(userTracker.userHandle).thenReturn(activeUser)

                createStartable(enabled = true).onBootCompleted()
                fakeExecutor.runAllReady()
                runCurrent()

                assertThat(preferredPanelsRepository.getSelectedComponent())
                    .isEqualTo(selectedComponent)
                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Uninstalled(
                        packageName = TEST_PACKAGE_PANEL,
                        packageUid = UserHandle.getUid(100, 1)
                    )
                )
                runCurrent()

                assertThat(preferredPanelsRepository.getSelectedComponent()).isNull()
            }
        }

    @Test
    fun testSelectedComponentIsChanged() =
        with(kosmos) {
            testScope.runTest {
                val selectedComponent =
                    SelectedComponentRepository.SelectedComponent(
                        "panel",
                        TEST_COMPONENT_PANEL,
                        isPanel = true
                    )
                preferredPanelsRepository.setSelectedComponent(selectedComponent)
                val activeUser = UserHandle.of(100)
                whenever(userTracker.userHandle).thenReturn(activeUser)

                createStartable(enabled = true).onBootCompleted()
                fakeExecutor.runAllReady()
                runCurrent()

                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Changed(
                        packageName = TEST_PACKAGE_PANEL,
                        packageUid = UserHandle.getUid(100, 1)
                    )
                )
                runCurrent()

                assertThat(preferredPanelsRepository.getSelectedComponent())
                    .isEqualTo(selectedComponent)
            }
        }

    @Test
    fun testOtherPackageIsUninstalled() =
        with(kosmos) {
            testScope.runTest {
                val selectedComponent =
                    SelectedComponentRepository.SelectedComponent(
                        "panel",
                        TEST_COMPONENT_PANEL,
                        isPanel = true
                    )
                preferredPanelsRepository.setSelectedComponent(selectedComponent)
                val activeUser = UserHandle.of(100)
                whenever(userTracker.userHandle).thenReturn(activeUser)

                createStartable(enabled = true).onBootCompleted()
                fakeExecutor.runAllReady()
                runCurrent()

                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Uninstalled(
                        packageName = TEST_PACKAGE,
                        packageUid = UserHandle.getUid(100, 1)
                    )
                )
                runCurrent()

                assertThat(preferredPanelsRepository.getSelectedComponent())
                    .isEqualTo(selectedComponent)
            }
        }

    private fun setUpControlsListingControls(listings: List<ControlsServiceInfo>) {
        doAnswer { doReturn(listings).`when`(controlsListingController).getCurrentServices() }
            .`when`(controlsListingController)
            .forceReload()
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
        return ControlsStartable(
            kosmos.applicationCoroutineScope,
            kosmos.testDispatcher,
            fakeExecutor,
            component,
            userTracker,
            authorizedPanelsRepository,
            preferredPanelsRepository,
            kosmos.packageChangeRepository,
            userManager,
            broadcastDispatcher,
        )
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
