/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.data.repository

import android.Manifest.permission.BIND_QUICK_SETTINGS_TILE
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.service.quicksettings.TileService
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.data.repository.fakePackageChangeRepository
import com.android.systemui.common.data.repository.packageChangeRepository
import com.android.systemui.common.data.shared.model.PackageChangeModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class InstalledTilesComponentRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var context: Context
    @Mock private lateinit var packageManager: PackageManager

    private lateinit var underTest: InstalledTilesComponentRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.createContextAsUser(any(), anyInt())).thenReturn(context)
        whenever(context.packageManager).thenReturn(packageManager)

        // Use the default value set in the ServiceInfo
        whenever(packageManager.getComponentEnabledSetting(any()))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        // Return empty by default
        whenever(packageManager.queryIntentServicesAsUser(any(), any<ResolveInfoFlags>(), anyInt()))
            .thenReturn(emptyList())

        underTest =
            InstalledTilesComponentRepositoryImpl(
                context,
                kosmos.testDispatcher,
                kosmos.packageChangeRepository
            )
    }

    @Test
    fun componentsLoadedOnStart() =
        testScope.runTest {
            val userId = 0
            val resolveInfo =
                ResolveInfo(TEST_COMPONENT, hasPermission = true, defaultEnabled = true)
            whenever(
                    packageManager.queryIntentServicesAsUser(
                        matchIntent(),
                        matchFlags(),
                        eq(userId)
                    )
                )
                .thenReturn(listOf(resolveInfo))

            val componentNames by collectLastValue(underTest.getInstalledTilesComponents(userId))

            assertThat(componentNames).containsExactly(TEST_COMPONENT)
        }

    @Test
    fun componentAdded_foundAfterPackageChange() =
        testScope.runTest {
            val userId = 0
            val resolveInfo =
                ResolveInfo(TEST_COMPONENT, hasPermission = true, defaultEnabled = true)

            val componentNames by collectLastValue(underTest.getInstalledTilesComponents(userId))
            assertThat(componentNames).isEmpty()

            whenever(
                    packageManager.queryIntentServicesAsUser(
                        matchIntent(),
                        matchFlags(),
                        eq(userId)
                    )
                )
                .thenReturn(listOf(resolveInfo))
            kosmos.fakePackageChangeRepository.notifyChange(PackageChangeModel.Empty)

            assertThat(componentNames).containsExactly(TEST_COMPONENT)
        }

    @Test
    fun componentWithoutPermission_notValid() =
        testScope.runTest {
            val userId = 0
            val resolveInfo =
                ResolveInfo(TEST_COMPONENT, hasPermission = false, defaultEnabled = true)
            whenever(
                    packageManager.queryIntentServicesAsUser(
                        matchIntent(),
                        matchFlags(),
                        eq(userId)
                    )
                )
                .thenReturn(listOf(resolveInfo))

            val componentNames by collectLastValue(underTest.getInstalledTilesComponents(userId))
            assertThat(componentNames).isEmpty()
        }

    @Test
    fun componentNotEnabled_notValid() =
        testScope.runTest {
            val userId = 0
            val resolveInfo =
                ResolveInfo(TEST_COMPONENT, hasPermission = true, defaultEnabled = false)
            whenever(
                    packageManager.queryIntentServicesAsUser(
                        matchIntent(),
                        matchFlags(),
                        eq(userId)
                    )
                )
                .thenReturn(listOf(resolveInfo))

            val componentNames by collectLastValue(underTest.getInstalledTilesComponents(userId))
            assertThat(componentNames).isEmpty()
        }

    @Test
    fun packageOnlyInSecondaryUser_noException() =
        testScope.runTest {
            val userId = 10
            val secondaryUserContext: Context = mock()
            whenever(context.userId).thenReturn(0) // System context
            whenever(context.createContextAsUser(eq(UserHandle.of(userId)), anyInt()))
                .thenReturn(secondaryUserContext)

            val secondaryUserPackageManager: PackageManager = mock()
            whenever(secondaryUserContext.packageManager).thenReturn(secondaryUserPackageManager)

            // Use the default value set in the ServiceInfo
            whenever(secondaryUserPackageManager.getComponentEnabledSetting(any()))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            // System User package manager throws exception if the component doesn't exist for that
            // user
            whenever(packageManager.getComponentEnabledSetting(TEST_COMPONENT))
                .thenThrow(IllegalArgumentException()) // The package is not in the system user

            val resolveInfo =
                ResolveInfo(TEST_COMPONENT, hasPermission = true, defaultEnabled = true)
            // Both package manager should return the same (because the query is for the secondary
            // user)
            whenever(
                    secondaryUserPackageManager.queryIntentServicesAsUser(
                        matchIntent(),
                        matchFlags(),
                        eq(userId)
                    )
                )
                .thenReturn(listOf(resolveInfo))
            whenever(
                    packageManager.queryIntentServicesAsUser(
                        matchIntent(),
                        matchFlags(),
                        eq(userId)
                    )
                )
                .thenReturn(listOf(resolveInfo))

            val componentNames by collectLastValue(underTest.getInstalledTilesComponents(userId))

            assertThat(componentNames).containsExactly(TEST_COMPONENT)
        }

    companion object {
        private val INTENT = Intent(TileService.ACTION_QS_TILE)
        private val FLAGS =
            ResolveInfoFlags.of(
                (PackageManager.MATCH_DIRECT_BOOT_AWARE or
                        PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
                        PackageManager.GET_SERVICES)
                    .toLong()
            )
        private val PERMISSION = BIND_QUICK_SETTINGS_TILE

        private val TEST_COMPONENT = ComponentName("pkg", "cls")

        private fun matchFlags() =
            argThat<ResolveInfoFlags> { flags -> flags?.value == FLAGS.value }
        private fun matchIntent() = argThat<Intent> { intent -> intent.action == INTENT.action }

        private fun ResolveInfo(
            componentName: ComponentName,
            hasPermission: Boolean,
            defaultEnabled: Boolean
        ): ResolveInfo {
            val applicationInfo = ApplicationInfo().apply { enabled = true }
            val serviceInfo =
                ServiceInfo().apply {
                    packageName = componentName.packageName
                    name = componentName.className
                    if (hasPermission) {
                        permission = PERMISSION
                    }
                    enabled = defaultEnabled
                    this.applicationInfo = applicationInfo
                }
            val resolveInfo = ResolveInfo()
            resolveInfo.serviceInfo = serviceInfo
            return resolveInfo
        }
    }
}
