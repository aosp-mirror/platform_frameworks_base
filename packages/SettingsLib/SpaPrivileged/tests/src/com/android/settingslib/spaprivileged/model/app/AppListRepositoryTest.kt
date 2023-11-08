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

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.FakeFeatureFlagsImpl
import android.content.pm.Flags
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.pm.UserInfo
import android.content.res.Resources
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class AppListRepositoryTest {
    private val resources = mock<Resources> {
        on { getStringArray(R.array.config_hideWhenDisabled_packageNames) } doReturn emptyArray()
    }

    private val packageManager = mock<PackageManager> {
        on { getInstalledModules(any()) } doReturn emptyList()
        on { getHomeActivities(any()) } doAnswer {
            @Suppress("UNCHECKED_CAST")
            val resolveInfos = it.arguments[0] as MutableList<ResolveInfo>
            resolveInfos += resolveInfoOf(packageName = HOME_APP.packageName)
            null
        }
        on { queryIntentActivitiesAsUser(any(), any<ResolveInfoFlags>(), any<Int>()) } doReturn
            listOf(resolveInfoOf(packageName = IN_LAUNCHER_APP.packageName))
    }

    private val mockUserManager = mock<UserManager> {
        on { getUserInfo(ADMIN_USER_ID) } doReturn UserInfo().apply {
            flags = UserInfo.FLAG_ADMIN
        }
        on { getProfileIdsWithDisabled(ADMIN_USER_ID) } doReturn
            intArrayOf(ADMIN_USER_ID, MANAGED_PROFILE_USER_ID)
    }

    private val context: Context = spy(ApplicationProvider.getApplicationContext()) {
        on { resources } doReturn resources
        on { packageManager } doReturn packageManager
        on { getSystemService(UserManager::class.java) } doReturn mockUserManager
    }

    private val repository = AppListRepositoryImpl(context)

    private fun mockInstalledApplications(apps: List<ApplicationInfo>, userId: Int) {
        packageManager.stub {
            on { getInstalledApplicationsAsUser(any<ApplicationInfoFlags>(), eq(userId)) } doReturn
                apps
        }
    }

    @Test
    fun loadApps_notShowInstantApps() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP, INSTANT_APP), ADMIN_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            loadInstantApps = false,
        )

        assertThat(appList).containsExactly(NORMAL_APP)
    }

    @Test
    fun loadApps_showInstantApps() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP, INSTANT_APP), ADMIN_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            loadInstantApps = true,
        )

        assertThat(appList).containsExactly(NORMAL_APP, INSTANT_APP)
    }

    @Test
    fun loadApps_notMatchAnyUserForAdmin_withRegularFlags() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP), ADMIN_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            matchAnyUserForAdmin = false,
        )

        assertThat(appList).containsExactly(NORMAL_APP)
        val flags = argumentCaptor<ApplicationInfoFlags> {
            verify(packageManager).getInstalledApplicationsAsUser(capture(), eq(ADMIN_USER_ID))
        }.firstValue
        assertThat(flags.value).isEqualTo(
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        )
    }

    @Test
    fun loadApps_matchAnyUserForAdmin_withMatchAnyUserFlag() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP), ADMIN_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            matchAnyUserForAdmin = true,
        )

        assertThat(appList).containsExactly(NORMAL_APP)
        val flags = argumentCaptor<ApplicationInfoFlags> {
            verify(packageManager).getInstalledApplicationsAsUser(capture(), eq(ADMIN_USER_ID))
        }.firstValue
        assertThat(flags.value and PackageManager.MATCH_ANY_USER.toLong()).isGreaterThan(0L)
    }

    @Test
    fun loadApps_matchAnyUserForAdminAndInstalledOnManagedProfileOnly_notDisplayed() = runTest {
        val managedProfileOnlyPackageName = "installed.on.managed.profile.only"
        mockInstalledApplications(listOf(ApplicationInfo().apply {
            packageName = managedProfileOnlyPackageName
        }), ADMIN_USER_ID)
        mockInstalledApplications(listOf(ApplicationInfo().apply {
            packageName = managedProfileOnlyPackageName
            flags = ApplicationInfo.FLAG_INSTALLED
        }), MANAGED_PROFILE_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            matchAnyUserForAdmin = true,
        )

        assertThat(appList).isEmpty()
    }

    @Test
    fun loadApps_matchAnyUserForAdminAndInstalledOnSecondaryUserOnly_displayed() = runTest {
        val secondaryUserOnlyApp = ApplicationInfo().apply {
            packageName = "installed.on.secondary.user.only"
        }
        mockInstalledApplications(listOf(secondaryUserOnlyApp), ADMIN_USER_ID)
        mockInstalledApplications(emptyList(), MANAGED_PROFILE_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            matchAnyUserForAdmin = true,
        )

        assertThat(appList).containsExactly(secondaryUserOnlyApp)
    }

    @Test
    fun loadApps_isHideWhenDisabledPackageAndDisabled() = runTest {
        val app = ApplicationInfo().apply {
            packageName = "is.hide.when.disabled"
            enabled = false
        }
        whenever(resources.getStringArray(R.array.config_hideWhenDisabled_packageNames))
            .thenReturn(arrayOf(app.packageName))
        mockInstalledApplications(listOf(app), ADMIN_USER_ID)

        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).isEmpty()
    }

    @Test
    fun loadApps_isHideWhenDisabledPackageAndDisabledUntilUsed() = runTest {
        val app = ApplicationInfo().apply {
            packageName = "is.hide.when.disabled"
            enabled = true
            enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        }
        whenever(resources.getStringArray(R.array.config_hideWhenDisabled_packageNames))
            .thenReturn(arrayOf(app.packageName))
        mockInstalledApplications(listOf(app), ADMIN_USER_ID)

        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).isEmpty()
    }

    @Test
    fun loadApps_isHideWhenDisabledPackageAndEnabled() = runTest {
        val app = ApplicationInfo().apply {
            packageName = "is.hide.when.disabled"
            enabled = true
        }
        whenever(resources.getStringArray(R.array.config_hideWhenDisabled_packageNames))
            .thenReturn(arrayOf(app.packageName))
        mockInstalledApplications(listOf(app), ADMIN_USER_ID)

        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).containsExactly(app)
    }

    @Test
    fun loadApps_disabledByUser() = runTest {
        val app = ApplicationInfo().apply {
            packageName = "disabled.by.user"
            enabled = false
            enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
        mockInstalledApplications(listOf(app), ADMIN_USER_ID)

        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).containsExactly(app)
    }

    @Test
    fun loadApps_disabledButNotByUser() = runTest {
        val app = ApplicationInfo().apply {
            packageName = "disabled"
            enabled = false
        }
        mockInstalledApplications(listOf(app), ADMIN_USER_ID)

        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).isEmpty()
    }

    @Test
    fun loadApps_archivedAppsEnabled() = runTest {
        val fakeFlags = FakeFeatureFlagsImpl()
        fakeFlags.setFlag(Flags.FLAG_ARCHIVING, true)
        mockInstalledApplications(listOf(NORMAL_APP, ARCHIVED_APP), ADMIN_USER_ID)
        val repository = AppListRepositoryImpl(context, fakeFlags)
        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).containsExactly(NORMAL_APP, ARCHIVED_APP)
        val flags = argumentCaptor<ApplicationInfoFlags> {
            verify(packageManager).getInstalledApplicationsAsUser(capture(), eq(ADMIN_USER_ID))
        }.firstValue
        assertThat(flags.value).isEqualTo(
            (PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS).toLong() or
                PackageManager.MATCH_ARCHIVED_PACKAGES
        )
    }

    @Test
    fun loadApps_archivedAppsDisabled() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP), ADMIN_USER_ID)
        val appList = repository.loadApps(userId = ADMIN_USER_ID)

        assertThat(appList).containsExactly(NORMAL_APP)
        val flags = argumentCaptor<ApplicationInfoFlags> {
            verify(packageManager).getInstalledApplicationsAsUser(capture(), eq(ADMIN_USER_ID))
        }.firstValue
        assertThat(flags.value).isEqualTo(
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        )
    }

    @Test
    fun showSystemPredicate_showSystem() = runTest {
        val app = SYSTEM_APP

        val showSystemPredicate = getShowSystemPredicate(showSystem = true)

        assertThat(showSystemPredicate(app)).isTrue()
    }

    @Test
    fun showSystemPredicate_notShowSystemAndIsSystemApp() = runTest {
        val app = SYSTEM_APP

        val showSystemPredicate = getShowSystemPredicate(showSystem = false)

        assertThat(showSystemPredicate(app)).isFalse()
    }

    @Test
    fun showSystemPredicate_isUpdatedSystemApp() = runTest {
        val app = UPDATED_SYSTEM_APP

        val showSystemPredicate = getShowSystemPredicate(showSystem = false)

        assertThat(showSystemPredicate(app)).isTrue()
    }

    @Test
    fun showSystemPredicate_isHome() = runTest {
        val showSystemPredicate = getShowSystemPredicate(showSystem = false)

        assertThat(showSystemPredicate(HOME_APP)).isTrue()
    }

    @Test
    fun showSystemPredicate_appInLauncher() = runTest {
        val showSystemPredicate = getShowSystemPredicate(showSystem = false)

        assertThat(showSystemPredicate(IN_LAUNCHER_APP)).isTrue()
    }

    @Test
    fun getSystemPackageNames_returnExpectedValues() = runTest {
        mockInstalledApplications(
            apps = listOf(
                NORMAL_APP,
                INSTANT_APP,
                SYSTEM_APP,
                UPDATED_SYSTEM_APP,
                HOME_APP,
                IN_LAUNCHER_APP,
            ),
            userId = ADMIN_USER_ID,
        )

        val systemPackageNames = AppListRepositoryUtil.getSystemPackageNames(
            context = context,
            userId = ADMIN_USER_ID,
        )

        assertThat(systemPackageNames).containsExactly(SYSTEM_APP.packageName)
    }

    @Test
    fun loadAndFilterApps_loadNonSystemApp_returnExpectedValues() = runTest {
        mockInstalledApplications(
            apps = listOf(
                NORMAL_APP,
                INSTANT_APP,
                SYSTEM_APP,
                UPDATED_SYSTEM_APP,
                HOME_APP,
                IN_LAUNCHER_APP,
            ),
            userId = ADMIN_USER_ID,
        )

        val appList = repository.loadAndFilterApps(userId = ADMIN_USER_ID, isSystemApp = false)

        assertThat(appList)
            .containsExactly(NORMAL_APP, UPDATED_SYSTEM_APP, HOME_APP, IN_LAUNCHER_APP)
    }

    private suspend fun getShowSystemPredicate(showSystem: Boolean) =
        repository.showSystemPredicate(
            userIdFlow = flowOf(ADMIN_USER_ID),
            showSystemFlow = flowOf(showSystem),
        ).first()

    private companion object {
        const val ADMIN_USER_ID = 0
        const val MANAGED_PROFILE_USER_ID = 11

        val NORMAL_APP = ApplicationInfo().apply {
            packageName = "normal"
            enabled = true
        }

        val INSTANT_APP = ApplicationInfo().apply {
            packageName = "instant"
            enabled = true
            privateFlags = ApplicationInfo.PRIVATE_FLAG_INSTANT
        }

        val SYSTEM_APP = ApplicationInfo().apply {
            packageName = "system.app"
            flags = ApplicationInfo.FLAG_SYSTEM
        }

        val UPDATED_SYSTEM_APP = ApplicationInfo().apply {
            packageName = "updated.system.app"
            flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }

        val HOME_APP = ApplicationInfo().apply {
            packageName = "home.app"
            flags = ApplicationInfo.FLAG_SYSTEM
        }

        val IN_LAUNCHER_APP = ApplicationInfo().apply {
            packageName = "app.in.launcher"
            flags = ApplicationInfo.FLAG_SYSTEM
        }

        val ARCHIVED_APP = ApplicationInfo().apply {
            packageName = "archived.app"
            flags = ApplicationInfo.FLAG_SYSTEM
            isArchived = true
        }

        fun resolveInfoOf(packageName: String) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
            }
        }
    }
}
