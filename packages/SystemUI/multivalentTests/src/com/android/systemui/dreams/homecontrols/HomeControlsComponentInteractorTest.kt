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
package com.android.systemui.dreams.homecontrols

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.pm.UserInfo
import android.os.PowerManager
import android.os.UserHandle
import android.os.powerManager
import android.service.dream.dreamManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.data.repository.fakePackageChangeRepository
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.authorizedPanelsRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor.Companion.MAX_UPDATE_CORRELATION_DELAY
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsComponentInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest by lazy { kosmos.homeControlsComponentInteractor }

    @Before
    fun setUp() =
        with(kosmos) {
            fakeSystemClock.setCurrentTimeMillis(0)
            fakeUserRepository.setUserInfos(listOf(PRIMARY_USER, ANOTHER_USER))
            whenever(controlsComponent.getControlsListingController())
                .thenReturn(Optional.of(controlsListingController))
            Unit
        }

    @Test
    fun testPanelComponentReturnsComponentNameForSelectedItemByUser() =
        with(kosmos) {
            testScope.runTest {
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
                runServicesUpdate()
                assertThat(actualValue).isEqualTo(TEST_COMPONENT)
            }
        }

    @Test
    fun testPanelComponentReturnsComponentNameAsInitialValueWithoutServiceUpdate() =
        with(kosmos) {
            testScope.runTest {
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                whenever(controlsListingController.getCurrentServices())
                    .thenReturn(
                        listOf(ControlsServiceInfo(TEST_COMPONENT, "panel", hasPanel = true))
                    )
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isEqualTo(TEST_COMPONENT)
            }
        }

    @Test
    fun testPanelComponentReturnsNullForHomeControlsThatDoesNotSupportPanel() =
        with(kosmos) {
            testScope.runTest {
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
                runServicesUpdate(false)
                assertThat(actualValue).isNull()
            }
        }

    @Test
    fun testPanelComponentReturnsNullWhenPanelIsUnauthorized() =
        with(kosmos) {
            testScope.runTest {
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.removeAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
                runServicesUpdate()
                assertThat(actualValue).isNull()
            }
        }

    @Test
    fun testPanelComponentReturnsComponentNameForDifferentUsers() =
        with(kosmos) {
            testScope.runTest {
                val actualValue by collectLastValue(underTest.panelComponent)

                // Secondary user has non-panel selected.
                setActiveUser(ANOTHER_USER)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)

                // Primary user has panel selected.
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)

                runServicesUpdate()
                assertThat(actualValue).isEqualTo(TEST_COMPONENT)

                // Back to secondary user, should be null.
                setActiveUser(ANOTHER_USER)
                runServicesUpdate()
                assertThat(actualValue).isNull()
            }
        }

    @Test
    fun testPanelComponentReturnsNullWhenControlsComponentReturnsNullForListingController() =
        with(kosmos) {
            testScope.runTest {
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                whenever(controlsComponent.getControlsListingController())
                    .thenReturn(Optional.empty())
                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
            }
        }

    @Test
    fun testMonitoringUpdatesAndRestart() =
        with(kosmos) {
            testScope.runTest {
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                whenever(controlsListingController.getCurrentServices())
                    .thenReturn(
                        listOf(ControlsServiceInfo(TEST_COMPONENT, "panel", hasPanel = true))
                    )

                val job = launch { underTest.monitorUpdatesAndRestart() }
                val panelComponent by collectLastValue(underTest.panelComponent)

                assertThat(panelComponent).isEqualTo(TEST_COMPONENT)
                verify(dreamManager, never()).startDream()

                fakeSystemClock.advanceTime(100)
                // The package update is started.
                fakePackageChangeRepository.notifyUpdateStarted(
                    TEST_PACKAGE,
                    UserHandle.of(PRIMARY_USER_ID),
                )
                fakeSystemClock.advanceTime(MAX_UPDATE_CORRELATION_DELAY.inWholeMilliseconds)
                // Task fragment becomes empty as a result of the update.
                underTest.onDreamEndUnexpectedly()

                runCurrent()
                verify(dreamManager, never()).startDream()

                fakeSystemClock.advanceTime(500)
                // The package update is finished.
                fakePackageChangeRepository.notifyUpdateFinished(
                    TEST_PACKAGE,
                    UserHandle.of(PRIMARY_USER_ID),
                )

                runCurrent()
                verify(dreamManager).startDream()
                job.cancel()
            }
        }

    @Test
    fun testMonitoringUpdatesAndRestart_dreamEndsAfterDelay() =
        with(kosmos) {
            testScope.runTest {
                setActiveUser(PRIMARY_USER)
                authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                whenever(controlsListingController.getCurrentServices())
                    .thenReturn(
                        listOf(ControlsServiceInfo(TEST_COMPONENT, "panel", hasPanel = true))
                    )

                val job = launch { underTest.monitorUpdatesAndRestart() }
                val panelComponent by collectLastValue(underTest.panelComponent)

                assertThat(panelComponent).isEqualTo(TEST_COMPONENT)
                verify(dreamManager, never()).startDream()

                fakeSystemClock.advanceTime(100)
                // The package update is started.
                fakePackageChangeRepository.notifyUpdateStarted(
                    TEST_PACKAGE,
                    UserHandle.of(PRIMARY_USER_ID),
                )
                fakeSystemClock.advanceTime(MAX_UPDATE_CORRELATION_DELAY.inWholeMilliseconds + 100)
                // Task fragment becomes empty as a result of the update.
                underTest.onDreamEndUnexpectedly()

                runCurrent()
                verify(dreamManager, never()).startDream()

                fakeSystemClock.advanceTime(500)
                // The package update is finished.
                fakePackageChangeRepository.notifyUpdateFinished(
                    TEST_PACKAGE,
                    UserHandle.of(PRIMARY_USER_ID),
                )

                runCurrent()
                verify(dreamManager, never()).startDream()
                job.cancel()
            }
        }

    @Test
    fun testDreamUnexpectedlyEnds_triggersUserActivity() =
        with(kosmos) {
            testScope.runTest {
                fakeSystemClock.setUptimeMillis(100000L)
                verify(powerManager, never()).userActivity(anyLong(), anyInt(), anyInt())

                // Dream ends unexpectedly
                underTest.onDreamEndUnexpectedly()

                verify(powerManager)
                    .userActivity(
                        100000L,
                        PowerManager.USER_ACTIVITY_EVENT_OTHER,
                        PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS
                    )
            }
        }

    private fun runServicesUpdate(hasPanelBoolean: Boolean = true) {
        val listings =
            listOf(ControlsServiceInfo(TEST_COMPONENT, "panel", hasPanel = hasPanelBoolean))
        val callback = withArgCaptor {
            verify(kosmos.controlsListingController).addCallback(capture())
        }
        callback.onServicesUpdated(listings)
    }

    private suspend fun TestScope.setActiveUser(user: UserInfo) {
        kosmos.fakeUserRepository.setSelectedUserInfo(user)
        kosmos.fakeUserTracker.set(listOf(user), 0)
        runCurrent()
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
        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER =
            UserInfo(
                /* id= */ PRIMARY_USER_ID,
                /* name= */ "primary user",
                /* flags= */ UserInfo.FLAG_PRIMARY
            )

        private const val ANOTHER_USER_ID = 1
        private val ANOTHER_USER =
            UserInfo(
                /* id= */ ANOTHER_USER_ID,
                /* name= */ "another user",
                /* flags= */ UserInfo.FLAG_PRIMARY
            )
        private const val TEST_PACKAGE = "pkg"
        private val TEST_COMPONENT = ComponentName(TEST_PACKAGE, "service")
        private val TEST_SELECTED_COMPONENT_PANEL =
            SelectedComponentRepository.SelectedComponent(TEST_PACKAGE, TEST_COMPONENT, true)
        private val TEST_SELECTED_COMPONENT_NON_PANEL =
            SelectedComponentRepository.SelectedComponent(TEST_PACKAGE, TEST_COMPONENT, false)
    }
}
