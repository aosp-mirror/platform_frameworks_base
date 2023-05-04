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
import com.android.settingslib.spaprivileged.framework.common.userManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.Mockito.`when` as whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppListRepositoryTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Spy
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var resources: Resources

    @Mock
    private lateinit var packageManager: PackageManager

    @Mock
    private lateinit var userManager: UserManager

    private lateinit var repository: AppListRepository

    @Before
    fun setUp() {
        whenever(context.resources).thenReturn(resources)
        whenever(resources.getStringArray(R.array.config_hideWhenDisabled_packageNames))
            .thenReturn(emptyArray())
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.userManager).thenReturn(userManager)
        whenever(packageManager.getInstalledModules(anyInt())).thenReturn(emptyList())
        whenever(packageManager.getHomeActivities(any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val resolveInfos = it.arguments[0] as MutableList<ResolveInfo>
            resolveInfos += resolveInfoOf(packageName = HOME_APP.packageName)
            null
        }
        whenever(
            packageManager.queryIntentActivitiesAsUser(any(), any<ResolveInfoFlags>(), anyInt())
        ).thenReturn(listOf(resolveInfoOf(packageName = IN_LAUNCHER_APP.packageName)))
        whenever(userManager.getUserInfo(ADMIN_USER_ID)).thenReturn(UserInfo().apply {
            flags = UserInfo.FLAG_ADMIN
        })
        whenever(userManager.getProfileIdsWithDisabled(ADMIN_USER_ID))
            .thenReturn(intArrayOf(ADMIN_USER_ID, MANAGED_PROFILE_USER_ID))

        repository = AppListRepositoryImpl(context)
    }

    private fun mockInstalledApplications(apps: List<ApplicationInfo>, userId: Int) {
        whenever(
            packageManager.getInstalledApplicationsAsUser(any<ApplicationInfoFlags>(), eq(userId))
        ).thenReturn(apps)
    }

    @Test
    fun loadApps_notShowInstantApps() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP, INSTANT_APP), ADMIN_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            showInstantApps = false,
        )

        assertThat(appList).containsExactly(NORMAL_APP)
    }

    @Test
    fun loadApps_showInstantApps() = runTest {
        mockInstalledApplications(listOf(NORMAL_APP, INSTANT_APP), ADMIN_USER_ID)

        val appList = repository.loadApps(
            userId = ADMIN_USER_ID,
            showInstantApps = true,
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
        val flags = ArgumentCaptor.forClass(ApplicationInfoFlags::class.java)
        verify(packageManager).getInstalledApplicationsAsUser(flags.capture(), eq(ADMIN_USER_ID))
        assertThat(flags.value.value).isEqualTo(
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
        val flags = ArgumentCaptor.forClass(ApplicationInfoFlags::class.java)
        verify(packageManager).getInstalledApplicationsAsUser(flags.capture(), eq(ADMIN_USER_ID))
        assertThat(flags.value.value and PackageManager.MATCH_ANY_USER.toLong()).isGreaterThan(0L)
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
                NORMAL_APP, INSTANT_APP, SYSTEM_APP, UPDATED_SYSTEM_APP, HOME_APP, IN_LAUNCHER_APP
            ),
            userId = ADMIN_USER_ID,
        )

        val systemPackageNames = AppListRepositoryUtil.getSystemPackageNames(
            context = context,
            userId = ADMIN_USER_ID,
        )

        assertThat(systemPackageNames).containsExactly(SYSTEM_APP.packageName)
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

        fun resolveInfoOf(packageName: String) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
            }
        }
    }
}
