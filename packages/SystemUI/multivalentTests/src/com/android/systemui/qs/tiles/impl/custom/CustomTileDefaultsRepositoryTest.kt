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
 */

package com.android.systemui.qs.tiles.impl.custom

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileDefaultsRepositoryImpl
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CustomTileDefaultsRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var sysuiContext: Context
    @Mock private lateinit var user1Context: Context
    @Mock private lateinit var user2Context: Context
    @Mock private lateinit var packageManager1: PackageManager
    @Mock private lateinit var packageManager2: PackageManager

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: CustomTileDefaultsRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(sysuiContext.createContextAsUser(eq(USER_1), any())).thenReturn(user1Context)
        whenever(user1Context.packageManager).thenReturn(packageManager1)
        packageManager1.setupApp1()

        whenever(sysuiContext.createContextAsUser(eq(USER_2), any())).thenReturn(user2Context)
        whenever(user2Context.packageManager).thenReturn(packageManager2)
        packageManager2.setupApp2()

        underTest =
            CustomTileDefaultsRepositoryImpl(
                sysuiContext,
                testScope.backgroundScope,
                testDispatcher,
            )
    }

    @Test
    fun regularRequestingEmitsTheNewDefault() =
        testScope.runTest {
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)

            runCurrent()

            val default = underTest.defaults(USER_1).first() as CustomTileDefaults.Result
            assertThat(default.label).isEqualTo(APP_LABEL_1)
            assertThat(default.icon.resId).isEqualTo(SERVICE_ICON_1)
            assertThat(default.icon.resPackage).isEqualTo(COMPONENT_NAME_1.packageName)
        }

    @Test
    fun requestingSystemAppEmitsTheNewDefault() =
        testScope.runTest {
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)

            runCurrent()

            val default = underTest.defaults(USER_1).first() as CustomTileDefaults.Result
            assertThat(default.label).isEqualTo(APP_LABEL_1)
            assertThat(default.icon.resId).isEqualTo(SERVICE_ICON_1)
            assertThat(default.icon.resPackage).isEqualTo(COMPONENT_NAME_1.packageName)
        }

    @Test
    fun requestingForcesTheNewEmit() =
        testScope.runTest {
            val defaults = mutableListOf<CustomTileDefaults.Result>()
            backgroundScope.launch {
                underTest
                    .defaults(USER_1)
                    .map { it as CustomTileDefaults.Result }
                    .collect { defaults.add(it) }
            }
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)
            // the same request should be skipped. This leads to 2 result in assertions
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)
            runCurrent()

            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, true)
            runCurrent()

            assertThat(defaults).hasSize(2)
            assertThat(defaults.last().label).isEqualTo(APP_LABEL_1)
            assertThat(defaults.last().icon.resId).isEqualTo(SERVICE_ICON_1)
            assertThat(defaults.last().icon.resPackage).isEqualTo(COMPONENT_NAME_1.packageName)
        }

    @Test
    fun userChangeForcesTheNewEmit() =
        testScope.runTest {
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)
            runCurrent()

            underTest.requestNewDefaults(USER_2, COMPONENT_NAME_2, false)
            runCurrent()

            val default = underTest.defaults(USER_2).first() as CustomTileDefaults.Result
            assertThat(default.label).isEqualTo(APP_LABEL_2)
            assertThat(default.icon.resId).isEqualTo(SERVICE_ICON_2)
            assertThat(default.icon.resPackage).isEqualTo(COMPONENT_NAME_2.packageName)
        }

    @Test
    fun componentNameChangeForcesTheNewEmit() =
        testScope.runTest {
            packageManager1.setupApp2(false)
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)
            runCurrent()

            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_2, false)
            runCurrent()

            val default = underTest.defaults(USER_1).first() as CustomTileDefaults.Result
            assertThat(default.label).isEqualTo(APP_LABEL_2)
            assertThat(default.icon.resId).isEqualTo(SERVICE_ICON_2)
            assertThat(default.icon.resPackage).isEqualTo(COMPONENT_NAME_2.packageName)
        }

    @Test
    fun noIconIsAnError() =
        testScope.runTest {
            packageManager1.setupApp(
                componentName = COMPONENT_NAME_1,
                appLabel = "",
                serviceIcon = 0,
                appInfoIcon = 0,
                isSystemApp = false,
            )
            underTest.requestNewDefaults(USER_1, COMPONENT_NAME_1, false)

            runCurrent()

            assertThat(underTest.defaults(USER_1).first())
                .isInstanceOf(CustomTileDefaults.Error::class.java)
        }

    @Test
    fun applicationScopeIsFreedWhileNotSubscribed() =
        testScope.runTest {
            val listenJob = underTest.defaults(USER_1).launchIn(backgroundScope)
            listenJob.cancel()
            assertThat(this.coroutineContext[Job]!!.children.toList()).isEmpty()
        }

    private fun PackageManager.setupApp1(isSystemApp: Boolean = false) =
        setupApp(
            componentName = COMPONENT_NAME_1,
            serviceIcon = SERVICE_ICON_1,
            appLabel = APP_LABEL_1,
            appInfoIcon = APP_INFO_ICON_1,
            isSystemApp = isSystemApp,
        )
    private fun PackageManager.setupApp2(isSystemApp: Boolean = false) =
        setupApp(
            componentName = COMPONENT_NAME_2,
            serviceIcon = SERVICE_ICON_2,
            appLabel = APP_LABEL_2,
            appInfoIcon = APP_INFO_ICON_2,
            isSystemApp = isSystemApp,
        )

    private fun PackageManager.setupApp(
        componentName: ComponentName,
        serviceIcon: Int,
        appLabel: CharSequence,
        appInfoIcon: Int = serviceIcon,
        isSystemApp: Boolean = false,
    ) {
        val appInfo =
            object : ApplicationInfo() {
                    override fun isSystemApp(): Boolean = isSystemApp
                }
                .apply { icon = appInfoIcon }
        whenever(getApplicationInfo(eq(componentName.packageName), any<Int>())).thenReturn(appInfo)

        // set of desired flags is different for system and a regular app.
        var serviceFlags =
            (PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE)
        if (isSystemApp) {
            serviceFlags = serviceFlags or PackageManager.MATCH_DISABLED_COMPONENTS
        }

        val serviceInfo =
            object : ServiceInfo() {
                    override fun loadLabel(pm: PackageManager): CharSequence = appLabel
                }
                .apply {
                    applicationInfo = appInfo
                    icon = serviceIcon
                }
        whenever(getServiceInfo(eq(componentName), eq(serviceFlags))).thenReturn(serviceInfo)
    }

    private companion object {
        val USER_1 = UserHandle(1)
        val USER_2 = UserHandle(2)

        val COMPONENT_NAME_1 = ComponentName("pkg.test_1", "cls")
        const val SERVICE_ICON_1 = 11
        const val APP_INFO_ICON_1 = 12
        const val APP_LABEL_1 = "app_1"

        val COMPONENT_NAME_2 = ComponentName("pkg.test_2", "cls")
        const val SERVICE_ICON_2 = 21
        const val APP_INFO_ICON_2 = 22
        const val APP_LABEL_2 = "app_2"
    }
}
