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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.pm.UserInfo
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.service.controls.flags.Flags.FLAG_HOME_PANEL_DREAM
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import java.util.Optional
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsDreamStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    @Mock private lateinit var packageManager: PackageManager

    private lateinit var homeControlsComponentInteractor: HomeControlsComponentInteractor
    private lateinit var selectedComponentRepository: SelectedComponentRepository
    private lateinit var authorizedPanelsRepository: AuthorizedPanelsRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var controlsComponent: ControlsComponent
    private lateinit var controlsListingController: ControlsListingController

    private lateinit var startable: HomeControlsDreamStartable
    private val componentName = ComponentName(context, HomeControlsDreamService::class.java)
    private val testScope = kosmos.testScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        selectedComponentRepository = kosmos.selectedComponentRepository
        authorizedPanelsRepository = kosmos.authorizedPanelsRepository
        userRepository = kosmos.fakeUserRepository
        controlsComponent = kosmos.controlsComponent
        controlsListingController = kosmos.controlsListingController

        userRepository.setUserInfos(listOf(PRIMARY_USER))

        whenever(authorizedPanelsRepository.getAuthorizedPanels())
            .thenReturn(setOf(TEST_PACKAGE_PANEL))

        whenever(controlsComponent.getControlsListingController())
            .thenReturn(Optional.of(controlsListingController))
        whenever(controlsListingController.getCurrentServices())
            .thenReturn(listOf(ControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true)))

        homeControlsComponentInteractor = kosmos.homeControlsComponentInteractor

        startable =
            HomeControlsDreamStartable(
                mContext,
                packageManager,
                homeControlsComponentInteractor,
                kosmos.applicationCoroutineScope
            )
    }

    @Test
    @EnableFlags(FLAG_HOME_PANEL_DREAM)
    fun testStartEnablesHomeControlsDreamServiceWhenPanelComponentIsNotNull() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
            startable.start()
            runCurrent()
            verify(packageManager)
                .setComponentEnabledSetting(
                    eq(componentName),
                    eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                    eq(PackageManager.DONT_KILL_APP)
                )
        }

    @Test
    @EnableFlags(FLAG_HOME_PANEL_DREAM)
    fun testStartDisablesHomeControlsDreamServiceWhenPanelComponentIsNull() =
        testScope.runTest {
            selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)
            startable.start()
            runCurrent()
            verify(packageManager)
                .setComponentEnabledSetting(
                    eq(componentName),
                    eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                    eq(PackageManager.DONT_KILL_APP)
                )
        }

    @Test
    @DisableFlags(FLAG_HOME_PANEL_DREAM)
    fun testStartDoesNotRunDreamServiceWhenFlagIsDisabled() =
        testScope.runTest {
            selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)
            startable.start()
            runCurrent()
            verify(packageManager, never()).setComponentEnabledSetting(any(), any(), any())
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
