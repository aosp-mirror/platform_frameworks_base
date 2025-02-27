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
import android.platform.test.flag.junit.FlagsParameterization
import android.service.controls.flags.Flags.FLAG_HOME_PANEL_DREAM
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_HOME_CONTROLS_DREAM_HSUM
import com.android.systemui.Flags.homeControlsDreamHsum
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.authorizedPanelsRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.dreams.homecontrols.system.HomeControlsDreamStartable
import com.android.systemui.dreams.homecontrols.system.domain.interactor.controlsComponent
import com.android.systemui.dreams.homecontrols.system.domain.interactor.controlsListingController
import com.android.systemui.dreams.homecontrols.system.domain.interactor.homeControlsComponentInteractor
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class HomeControlsDreamStartableTest(flags: FlagsParameterization) : SysuiTestCase() {
    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val systemUserPackageManager = mock<PackageManager>()
    private val userPackageManager = mock<PackageManager>()

    private val selectedComponentRepository = kosmos.selectedComponentRepository
    private val userRepository =
        kosmos.fakeUserRepository.apply { setUserInfos(listOf(PRIMARY_USER)) }
    private val controlsListingController =
        kosmos.controlsListingController.stub {
            on { getCurrentServices() } doReturn
                listOf(buildControlsServiceInfo(TEST_COMPONENT_PANEL, "panel", hasPanel = true))
        }
    private val controlsComponent =
        kosmos.controlsComponent.stub {
            on { getControlsListingController() } doReturn Optional.of(controlsListingController)
        }

    private val underTest by lazy {
        HomeControlsDreamStartable(
            mContext,
            systemUserPackageManager,
            kosmos.userTracker,
            kosmos.homeControlsComponentInteractor,
            kosmos.applicationCoroutineScope,
        )
    }
    private val componentName = ComponentName(context, HomeControlsDreamService::class.java)

    @Before
    fun setUp() {
        kosmos.authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE_PANEL))
        whenever(controlsComponent.getControlsListingController())
            .thenReturn(Optional.of(controlsListingController))
        whenever(kosmos.fakeUserTracker.userContext.packageManager).thenReturn(userPackageManager)
    }

    @Test
    @EnableFlags(FLAG_HOME_PANEL_DREAM)
    fun testStartEnablesHomeControlsDreamServiceWhenPanelComponentIsNotNull() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
            underTest.start()
            runCurrent()
            val packageManager =
                if (homeControlsDreamHsum()) {
                    userPackageManager
                } else {
                    systemUserPackageManager
                }
            verify(packageManager)
                .setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
        }

    @Test
    @EnableFlags(FLAG_HOME_PANEL_DREAM)
    fun testStartDisablesHomeControlsDreamServiceWhenPanelComponentIsNull() =
        testScope.runTest {
            selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)
            underTest.start()
            runCurrent()
            val packageManager =
                if (homeControlsDreamHsum()) {
                    userPackageManager
                } else {
                    systemUserPackageManager
                }
            verify(packageManager)
                .setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
        }

    @Test
    @DisableFlags(FLAG_HOME_PANEL_DREAM)
    fun testStartDisablesDreamServiceWhenFlagIsDisabled() =
        testScope.runTest {
            selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_NON_PANEL)
            underTest.start()
            runCurrent()
            val packageManager =
                if (homeControlsDreamHsum()) {
                    userPackageManager
                } else {
                    systemUserPackageManager
                }
            verify(packageManager)
                .setComponentEnabledSetting(
                    eq(componentName),
                    eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                    eq(PackageManager.DONT_KILL_APP),
                )
        }

    private fun buildControlsServiceInfo(
        componentName: ComponentName,
        label: CharSequence,
        hasPanel: Boolean,
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
        hasPanel: Boolean,
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
        @get:Parameters(name = "{0}")
        @JvmStatic
        val params = FlagsParameterization.allCombinationsOf(FLAG_HOME_CONTROLS_DREAM_HSUM)

        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER =
            UserInfo(
                /* id= */ PRIMARY_USER_ID,
                /* name= */ "primary user",
                /* flags= */ UserInfo.FLAG_PRIMARY,
            )
        private const val TEST_PACKAGE_PANEL = "pkg.panel"
        private val TEST_COMPONENT_PANEL = ComponentName(TEST_PACKAGE_PANEL, "service")
        private val TEST_SELECTED_COMPONENT_PANEL =
            SelectedComponentRepository.SelectedComponent(
                TEST_PACKAGE_PANEL,
                TEST_COMPONENT_PANEL,
                true,
            )
        private val TEST_SELECTED_COMPONENT_NON_PANEL =
            SelectedComponentRepository.SelectedComponent(
                TEST_PACKAGE_PANEL,
                TEST_COMPONENT_PANEL,
                false,
            )
    }
}
