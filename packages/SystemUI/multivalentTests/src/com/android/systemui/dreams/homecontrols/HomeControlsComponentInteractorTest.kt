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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.FakeSelectedComponentRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsComponentInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var controlsComponent: ControlsComponent
    private lateinit var controlsListingController: ControlsListingController
    private lateinit var authorizedPanelsRepository: AuthorizedPanelsRepository
    private lateinit var underTest: HomeControlsComponentInteractor
    private lateinit var userRepository: FakeUserRepository
    private lateinit var selectedComponentRepository: FakeSelectedComponentRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        userRepository = kosmos.fakeUserRepository
        userRepository.setUserInfos(listOf(PRIMARY_USER, ANOTHER_USER))

        controlsComponent = kosmos.controlsComponent
        authorizedPanelsRepository = kosmos.authorizedPanelsRepository
        controlsListingController = kosmos.controlsListingController
        selectedComponentRepository = kosmos.selectedComponentRepository

        selectedComponentRepository.setCurrentUserHandle(PRIMARY_USER.userHandle)
        whenever(controlsComponent.getControlsListingController())
            .thenReturn(Optional.of(controlsListingController))

        underTest =
            HomeControlsComponentInteractor(
                selectedComponentRepository,
                controlsComponent,
                authorizedPanelsRepository,
                userRepository,
                kosmos.applicationCoroutineScope,
            )
    }

    @Test
    fun testPanelComponentReturnsComponentNameForSelectedItemByUser() =
        with(kosmos) {
            testScope.runTest {
                whenever(authorizedPanelsRepository.getAuthorizedPanels())
                    .thenReturn(setOf(TEST_PACKAGE_PANEL))
                userRepository.setSelectedUserInfo(PRIMARY_USER)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
                runServicesUpdate()
                assertThat(actualValue).isEqualTo(TEST_COMPONENT_PANEL)
            }
        }

    @Test
    fun testPanelComponentReturnsComponentNameAsInitialValueWithoutServiceUpdate() =
        with(kosmos) {
            testScope.runTest {
                whenever(authorizedPanelsRepository.getAuthorizedPanels())
                    .thenReturn(setOf(TEST_PACKAGE_PANEL))
                userRepository.setSelectedUserInfo(PRIMARY_USER)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                whenever(controlsListingController.getCurrentServices())
                    .thenReturn(
                        listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
                    )
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isEqualTo(TEST_COMPONENT_PANEL)
            }
        }

    @Test
    fun testPanelComponentReturnsNullForHomeControlsThatDoesNotSupportPanel() =
        with(kosmos) {
            testScope.runTest {
                whenever(authorizedPanelsRepository.getAuthorizedPanels())
                    .thenReturn(setOf(TEST_PACKAGE_PANEL))
                userRepository.setSelectedUserInfo(PRIMARY_USER)
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
                whenever(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf())
                userRepository.setSelectedUserInfo(PRIMARY_USER)
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
                whenever(authorizedPanelsRepository.getAuthorizedPanels())
                    .thenReturn(setOf(TEST_PACKAGE_PANEL))
                userRepository.setSelectedUserInfo(ANOTHER_USER)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)
                selectedComponentRepository.setCurrentUserHandle(ANOTHER_USER.userHandle)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)

                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
                runServicesUpdate()
                assertThat(actualValue).isEqualTo(TEST_COMPONENT_PANEL)
            }
        }

    @Test
    fun testPanelComponentReturnsNullWhenControlsComponentReturnsNullForListingController() =
        with(kosmos) {
            testScope.runTest {
                whenever(authorizedPanelsRepository.getAuthorizedPanels())
                    .thenReturn(setOf(TEST_PACKAGE_PANEL))
                whenever(controlsComponent.getControlsListingController())
                    .thenReturn(Optional.empty())
                userRepository.setSelectedUserInfo(PRIMARY_USER)
                selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
                val actualValue by collectLastValue(underTest.panelComponent)
                assertThat(actualValue).isNull()
            }
        }

    private fun runServicesUpdate(hasPanelBoolean: Boolean = true) {
        val listings =
            listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = hasPanelBoolean))
        val callback = withArgCaptor { verify(controlsListingController).addCallback(capture()) }
        callback.onServicesUpdated(listings)
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
        private const val TEST_PACKAGE_PANEL = "pkg.panel"
        private val TEST_COMPONENT_PANEL = ComponentName(TEST_PACKAGE_PANEL, "service")
        private val TEST_SELECTED_COMPONENT_PANEL =
            SelectedComponentRepository.SelectedComponent(
                TEST_PACKAGE_PANEL,
                TEST_COMPONENT_PANEL,
                true
            )
        private val TEST_SELECTED_COMPONENT_NON_PANEL =
            SelectedComponentRepository.SelectedComponent(
                TEST_PACKAGE_PANEL,
                TEST_COMPONENT_PANEL,
                false
            )
    }
}
