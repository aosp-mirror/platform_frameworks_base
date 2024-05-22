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

package com.android.systemui.communal.data.repository

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL
import android.app.admin.devicePolicyManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.UserInfo
import android.os.UserManager.USER_TYPE_PROFILE_MANAGED
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.flags.Flags.FLAG_ALLOW_ALL_WIDGETS_ON_LOCKSCREEN_BY_DEFAULT
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.data.model.DisabledReason
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalSettingsRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var underTest: CommunalSettingsRepository

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        setKeyguardFeaturesDisabled(PRIMARY_USER, KEYGUARD_DISABLE_FEATURES_NONE)
        setKeyguardFeaturesDisabled(SECONDARY_USER, KEYGUARD_DISABLE_FEATURES_NONE)
        setKeyguardFeaturesDisabled(WORK_PROFILE, KEYGUARD_DISABLE_FEATURES_NONE)
        underTest = kosmos.communalSettingsRepository
    }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun secondaryUserIsInvalid() =
        testScope.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(SECONDARY_USER))

            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_INVALID_USER)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun classicFlagIsDisabled() =
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, false)
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_FLAG)
        }

    @DisableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun communalHubFlagIsDisabled() =
        testScope.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_FLAG)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsDisabledByUser() =
        testScope.runTest {
            kosmos.fakeSettings.putIntForUser(
                Settings.Secure.GLANCEABLE_HUB_ENABLED,
                0,
                PRIMARY_USER.id
            )
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_USER_SETTING)

            kosmos.fakeSettings.putIntForUser(
                Settings.Secure.GLANCEABLE_HUB_ENABLED,
                1,
                SECONDARY_USER.id
            )
            assertThat(enabledState?.enabled).isFalse()

            kosmos.fakeSettings.putIntForUser(
                Settings.Secure.GLANCEABLE_HUB_ENABLED,
                1,
                PRIMARY_USER.id
            )
            assertThat(enabledState?.enabled).isTrue()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsDisabledByDevicePolicy() =
        testScope.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isTrue()

            setKeyguardFeaturesDisabled(PRIMARY_USER, KEYGUARD_DISABLE_WIDGETS_ALL)
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_DEVICE_POLICY)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun widgetsAllowedForWorkProfile_isFalse_whenDisallowedByDevicePolicy() =
        testScope.runTest {
            val widgetsAllowedForWorkProfile by
                collectLastValue(underTest.getAllowedByDevicePolicy(WORK_PROFILE))
            assertThat(widgetsAllowedForWorkProfile).isTrue()

            setKeyguardFeaturesDisabled(WORK_PROFILE, KEYGUARD_DISABLE_WIDGETS_ALL)
            assertThat(widgetsAllowedForWorkProfile).isFalse()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsEnabled_whenDisallowedByDevicePolicyForWorkProfile() =
        testScope.runTest {
            val enabledStateForPrimaryUser by
                collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledStateForPrimaryUser?.enabled).isTrue()

            setKeyguardFeaturesDisabled(WORK_PROFILE, KEYGUARD_DISABLE_WIDGETS_ALL)
            assertThat(enabledStateForPrimaryUser?.enabled).isTrue()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsDisabledByUserAndDevicePolicy() =
        testScope.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isTrue()

            kosmos.fakeSettings.putIntForUser(
                Settings.Secure.GLANCEABLE_HUB_ENABLED,
                0,
                PRIMARY_USER.id
            )
            setKeyguardFeaturesDisabled(PRIMARY_USER, KEYGUARD_DISABLE_WIDGETS_ALL)

            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState)
                .containsExactly(
                    DisabledReason.DISABLED_REASON_DEVICE_POLICY,
                    DisabledReason.DISABLED_REASON_USER_SETTING,
                )
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubShowsWidgetCategoriesSetByUser() =
        testScope.runTest {
            kosmos.fakeSettings.putIntForUser(
                CommunalSettingsRepositoryImpl.GLANCEABLE_HUB_CONTENT_SETTING,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                PRIMARY_USER.id
            )
            val setting by collectLastValue(underTest.getWidgetCategories(PRIMARY_USER))
            assertThat(setting?.categories)
                .isEqualTo(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @DisableFlags(FLAG_ALLOW_ALL_WIDGETS_ON_LOCKSCREEN_BY_DEFAULT)
    @Test
    fun hubShowsKeyguardWidgetsByDefault() =
        testScope.runTest {
            val setting by collectLastValue(underTest.getWidgetCategories(PRIMARY_USER))
            assertThat(setting?.categories)
                .isEqualTo(AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB, FLAG_ALLOW_ALL_WIDGETS_ON_LOCKSCREEN_BY_DEFAULT)
    @Test
    fun hubShowsAllWidgetsByDefaultWhenFlagEnabled() =
        testScope.runTest {
            val setting by collectLastValue(underTest.getWidgetCategories(PRIMARY_USER))
            assertThat(setting?.categories)
                .isEqualTo(
                    AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD +
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
                )
        }

    private fun setKeyguardFeaturesDisabled(user: UserInfo, disabledFlags: Int) {
        whenever(kosmos.devicePolicyManager.getKeyguardDisabledFeatures(nullable(), eq(user.id)))
            .thenReturn(disabledFlags)
        kosmos.broadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
        )
    }

    private companion object {
        val PRIMARY_USER =
            UserInfo(/* id= */ 0, /* name= */ "primary user", /* flags= */ UserInfo.FLAG_MAIN)
        val SECONDARY_USER = UserInfo(/* id= */ 1, /* name= */ "secondary user", /* flags= */ 0)
        val WORK_PROFILE =
            UserInfo(
                10,
                "work",
                /* iconPath= */ "",
                /* flags= */ 0,
                USER_TYPE_PROFILE_MANAGED,
            )
    }
}
