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
import android.content.Intent
import android.content.pm.UserInfo
import android.content.res.mainResources
import android.os.UserManager.USER_TYPE_PROFILE_MANAGED
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.data.model.DisabledReason
import com.android.systemui.communal.data.repository.CommunalSettingsRepositoryImpl.Companion.GLANCEABLE_HUB_BACKGROUND_SETTING
import com.android.systemui.communal.domain.interactor.setCommunalV2Enabled
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.communal.shared.model.WhenToDream
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalSettingsRepositoryImplTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos =
        testKosmos()
            .apply { mainResources = mContext.orCreateTestableResources.resources }
            .useUnconfinedTestDispatcher()

    private val Kosmos.underTest by Kosmos.Fixture { communalSettingsRepository }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        setKeyguardFeaturesDisabled(PRIMARY_USER, KEYGUARD_DISABLE_FEATURES_NONE)
        setKeyguardFeaturesDisabled(SECONDARY_USER, KEYGUARD_DISABLE_FEATURES_NONE)
        setKeyguardFeaturesDisabled(WORK_PROFILE, KEYGUARD_DISABLE_FEATURES_NONE)

        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault,
            false,
        )
        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault,
            false,
        )
        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_dreamsActivatedOnPosturedByDefault,
            false,
        )
    }

    @After
    fun tearDown() {
        mContext.orCreateTestableResources.removeOverride(
            com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault
        )
        mContext.orCreateTestableResources.removeOverride(
            com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault
        )
        mContext.orCreateTestableResources.removeOverride(
            com.android.internal.R.bool.config_dreamsActivatedOnPosturedByDefault
        )
    }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @DisableFlags(FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun getFlagEnabled_bothEnabled() =
        kosmos.runTest {
            fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)

            assertThat(underTest.getFlagEnabled()).isTrue()
        }

    @DisableFlags(FLAG_COMMUNAL_HUB, FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun getFlagEnabled_bothDisabled() =
        kosmos.runTest {
            fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, false)

            assertThat(underTest.getFlagEnabled()).isFalse()
        }

    @DisableFlags(FLAG_COMMUNAL_HUB, FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun getFlagEnabled_onlyClassicFlagEnabled() =
        kosmos.runTest {
            fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)

            assertThat(underTest.getFlagEnabled()).isFalse()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @DisableFlags(FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun getFlagEnabled_onlyTrunkFlagEnabled() =
        kosmos.runTest {
            fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, false)

            assertThat(underTest.getFlagEnabled()).isFalse()
        }

    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    @DisableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun getFlagEnabled_mobileConfigEnabled() =
        kosmos.runTest {
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_glanceableHubEnabled,
                true,
            )

            assertThat(underTest.getFlagEnabled()).isTrue()
        }

    @DisableFlags(FLAG_GLANCEABLE_HUB_V2, FLAG_COMMUNAL_HUB)
    @Test
    fun getFlagEnabled_onlyMobileConfigEnabled() =
        kosmos.runTest {
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_glanceableHubEnabled,
                true,
            )

            assertThat(underTest.getFlagEnabled()).isFalse()
        }

    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    @DisableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun getFlagEnabled_onlyMobileFlagEnabled() =
        kosmos.runTest {
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_glanceableHubEnabled,
                false,
            )

            assertThat(underTest.getFlagEnabled()).isFalse()
        }

    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    @DisableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun getFlagEnabled_oldFlagIgnored() =
        kosmos.runTest {
            // New config flag enabled.
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_glanceableHubEnabled,
                true,
            )

            // Old config flag disabled.
            fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, false)

            assertThat(underTest.getFlagEnabled()).isTrue()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun secondaryUserIsInvalid() =
        kosmos.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(SECONDARY_USER))

            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_INVALID_USER)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB, FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun classicFlagIsDisabled() =
        kosmos.runTest {
            setCommunalV2Enabled(false)
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_FLAG)
        }

    @DisableFlags(FLAG_COMMUNAL_HUB, FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun communalHubFlagIsDisabled() =
        kosmos.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_FLAG)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsDisabledByUser() =
        kosmos.runTest {
            fakeSettings.putIntForUser(Settings.Secure.GLANCEABLE_HUB_ENABLED, 0, PRIMARY_USER.id)
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_USER_SETTING)

            fakeSettings.putIntForUser(Settings.Secure.GLANCEABLE_HUB_ENABLED, 1, SECONDARY_USER.id)
            assertThat(enabledState?.enabled).isFalse()

            fakeSettings.putIntForUser(Settings.Secure.GLANCEABLE_HUB_ENABLED, 1, PRIMARY_USER.id)
            assertThat(enabledState?.enabled).isTrue()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsDisabledByDevicePolicy() =
        kosmos.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isTrue()

            setKeyguardFeaturesDisabled(PRIMARY_USER, KEYGUARD_DISABLE_WIDGETS_ALL)
            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState).containsExactly(DisabledReason.DISABLED_REASON_DEVICE_POLICY)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun widgetsAllowedForWorkProfile_isFalse_whenDisallowedByDevicePolicy() =
        kosmos.runTest {
            val widgetsAllowedForWorkProfile by
                collectLastValue(underTest.getAllowedByDevicePolicy(WORK_PROFILE))
            assertThat(widgetsAllowedForWorkProfile).isTrue()

            setKeyguardFeaturesDisabled(WORK_PROFILE, KEYGUARD_DISABLE_WIDGETS_ALL)
            assertThat(widgetsAllowedForWorkProfile).isFalse()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsEnabled_whenDisallowedByDevicePolicyForWorkProfile() =
        kosmos.runTest {
            val enabledStateForPrimaryUser by
                collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledStateForPrimaryUser?.enabled).isTrue()

            setKeyguardFeaturesDisabled(WORK_PROFILE, KEYGUARD_DISABLE_WIDGETS_ALL)
            assertThat(enabledStateForPrimaryUser?.enabled).isTrue()
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun hubIsDisabledByUserAndDevicePolicy() =
        kosmos.runTest {
            val enabledState by collectLastValue(underTest.getEnabledState(PRIMARY_USER))
            assertThat(enabledState?.enabled).isTrue()

            fakeSettings.putIntForUser(Settings.Secure.GLANCEABLE_HUB_ENABLED, 0, PRIMARY_USER.id)
            setKeyguardFeaturesDisabled(PRIMARY_USER, KEYGUARD_DISABLE_WIDGETS_ALL)

            assertThat(enabledState?.enabled).isFalse()
            assertThat(enabledState)
                .containsExactly(
                    DisabledReason.DISABLED_REASON_DEVICE_POLICY,
                    DisabledReason.DISABLED_REASON_USER_SETTING,
                )
        }

    @Test
    @DisableFlags(FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND)
    fun backgroundType_defaultValue() =
        kosmos.runTest {
            val backgroundType by collectLastValue(underTest.getBackground(PRIMARY_USER))
            assertThat(backgroundType).isEqualTo(CommunalBackgroundType.ANIMATED)
        }

    @Test
    fun backgroundType_verifyAllValues() =
        kosmos.runTest {
            val backgroundType by collectLastValue(underTest.getBackground(PRIMARY_USER))
            for (type in CommunalBackgroundType.entries) {
                fakeSettings.putIntForUser(
                    GLANCEABLE_HUB_BACKGROUND_SETTING,
                    type.value,
                    PRIMARY_USER.id,
                )
                assertWithMessage(
                        "Expected $type when $GLANCEABLE_HUB_BACKGROUND_SETTING is set to" +
                            " ${type.value} but was $backgroundType"
                    )
                    .that(backgroundType)
                    .isEqualTo(type)
            }
        }

    @Test
    fun screensaverDisabledByUser() =
        kosmos.runTest {
            val enabledState by collectLastValue(underTest.getScreensaverEnabledState(PRIMARY_USER))

            fakeSettings.putIntForUser(Settings.Secure.SCREENSAVER_ENABLED, 0, PRIMARY_USER.id)

            assertThat(enabledState).isFalse()
        }

    @Test
    fun screensaverEnabledByUser() =
        kosmos.runTest {
            val enabledState by collectLastValue(underTest.getScreensaverEnabledState(PRIMARY_USER))

            fakeSettings.putIntForUser(Settings.Secure.SCREENSAVER_ENABLED, 1, PRIMARY_USER.id)

            assertThat(enabledState).isTrue()
        }

    @Test
    fun whenToDream_charging() =
        kosmos.runTest {
            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))

            fakeSettings.putIntForUser(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                1,
                PRIMARY_USER.id,
            )

            assertThat(whenToDreamState).isEqualTo(WhenToDream.WHILE_CHARGING)
        }

    @Test
    fun whenToDream_charging_defaultValue() =
        kosmos.runTest {
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault,
                true,
            )

            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))
            assertThat(whenToDreamState).isEqualTo(WhenToDream.WHILE_CHARGING)
        }

    @Test
    fun whenToDream_docked() =
        kosmos.runTest {
            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))

            fakeSettings.putIntForUser(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                1,
                PRIMARY_USER.id,
            )

            assertThat(whenToDreamState).isEqualTo(WhenToDream.WHILE_DOCKED)
        }

    @Test
    fun whenToDream_docked_defaultValue() =
        kosmos.runTest {
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault,
                true,
            )

            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))
            assertThat(whenToDreamState).isEqualTo(WhenToDream.WHILE_DOCKED)
        }

    @Test
    fun whenToDream_postured() =
        kosmos.runTest {
            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))

            fakeSettings.putIntForUser(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED,
                1,
                PRIMARY_USER.id,
            )

            assertThat(whenToDreamState).isEqualTo(WhenToDream.WHILE_POSTURED)
        }

    @Test
    fun whenToDream_postured_defaultValue() =
        kosmos.runTest {
            mContext.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_dreamsActivatedOnPosturedByDefault,
                true,
            )

            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))
            assertThat(whenToDreamState).isEqualTo(WhenToDream.WHILE_POSTURED)
        }

    @Test
    fun whenToDream_default() =
        kosmos.runTest {
            val whenToDreamState by collectLastValue(underTest.getWhenToDreamState(PRIMARY_USER))
            assertThat(whenToDreamState).isEqualTo(WhenToDream.NEVER)
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
            UserInfo(10, "work", /* iconPath= */ "", /* flags= */ 0, USER_TYPE_PROFILE_MANAGED)

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_GLANCEABLE_HUB_V2)
        }
    }
}
