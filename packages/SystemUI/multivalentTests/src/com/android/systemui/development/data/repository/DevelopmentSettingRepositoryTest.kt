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

package com.android.systemui.development.data.repository

import android.content.pm.UserInfo
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.os.userManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.stub

@RunWith(AndroidJUnit4::class)
@SmallTest
class DevelopmentSettingRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest = kosmos.developmentSettingRepository

    @Test
    fun nonAdminUser_unrestricted_neverDevelopmentEnabled() =
        with(kosmos) {
            testScope.runTest {
                val userInfo = nonAdminUserInfo
                val settingEnabled by
                    collectLastValue(underTest.isDevelopmentSettingEnabled(userInfo))

                setUserRestriction(userInfo.userHandle, restricted = false)

                assertThat(settingEnabled).isFalse()

                setSettingValue(false)
                assertThat(settingEnabled).isFalse()

                setSettingValue(true)
                assertThat(settingEnabled).isFalse()
            }
        }

    @Test
    fun nonAdminUser_restricted_neverDevelopmentEnabled() =
        with(kosmos) {
            testScope.runTest {
                val userInfo = nonAdminUserInfo
                val settingEnabled by
                    collectLastValue(underTest.isDevelopmentSettingEnabled(userInfo))

                setUserRestriction(userInfo.userHandle, restricted = true)

                assertThat(settingEnabled).isFalse()

                setSettingValue(false)
                assertThat(settingEnabled).isFalse()

                setSettingValue(true)
                assertThat(settingEnabled).isFalse()
            }
        }

    @Test
    fun adminUser_unrestricted_defaultValueOfSetting() =
        with(kosmos) {
            testScope.runTest {
                val userInfo = adminUserInfo
                val settingEnabled by
                    collectLastValue(underTest.isDevelopmentSettingEnabled(userInfo))

                setUserRestriction(userInfo.userHandle, restricted = false)

                val defaultValue = Build.TYPE == "eng"

                assertThat(settingEnabled).isEqualTo(defaultValue)
            }
        }

    @Test
    fun adminUser_unrestricted_enabledTracksSetting() =
        with(kosmos) {
            testScope.runTest {
                val userInfo = adminUserInfo
                val settingEnabled by
                    collectLastValue(underTest.isDevelopmentSettingEnabled(userInfo))

                setUserRestriction(userInfo.userHandle, restricted = false)

                setSettingValue(false)
                assertThat(settingEnabled).isFalse()

                setSettingValue(true)
                assertThat(settingEnabled).isTrue()
            }
        }

    @Test
    fun adminUser_restricted_neverDevelopmentEnabled() =
        with(kosmos) {
            testScope.runTest {
                val userInfo = adminUserInfo
                val settingEnabled by
                    collectLastValue(underTest.isDevelopmentSettingEnabled(userInfo))

                setUserRestriction(userInfo.userHandle, restricted = true)

                assertThat(settingEnabled).isFalse()

                setSettingValue(false)
                assertThat(settingEnabled).isFalse()

                setSettingValue(true)
                assertThat(settingEnabled).isFalse()
            }
        }

    private companion object {
        const val USER_RESTRICTION = UserManager.DISALLOW_DEBUGGING_FEATURES
        const val SETTING_NAME = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED

        val adminUserInfo =
            UserInfo(
                /* id= */ 10,
                /* name= */ "",
                /* flags */ UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
            )
        val nonAdminUserInfo =
            UserInfo(/* id= */ 11, /* name= */ "", /* flags */ UserInfo.FLAG_FULL)

        fun Kosmos.setUserRestriction(userHandle: UserHandle, restricted: Boolean) {
            userManager.stub {
                on { hasUserRestrictionForUser(eq(USER_RESTRICTION), eq(userHandle)) } doReturn
                    restricted
            }
        }

        fun Kosmos.setSettingValue(enabled: Boolean) {
            fakeGlobalSettings.putInt(SETTING_NAME, if (enabled) 1 else 0)
        }
    }
}
