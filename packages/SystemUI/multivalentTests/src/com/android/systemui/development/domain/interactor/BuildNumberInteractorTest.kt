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

package com.android.systemui.development.domain.interactor

import android.content.ClipData
import android.content.ClipDescription
import android.content.clipboardManager
import android.content.pm.UserInfo
import android.content.res.mainResources
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.development.shared.model.BuildNumber
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class BuildNumberInteractorTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            fakeUserRepository.setUserInfos(listOf(adminUserInfo, nonAdminUserInfo))
        }

    private val expectedBuildNumber =
        BuildNumber(
            kosmos.mainResources.getString(
                R.string.bugreport_status,
                Build.VERSION.RELEASE_OR_CODENAME,
                Build.ID,
            )
        )

    private val clipLabel =
        kosmos.mainResources.getString(
            com.android.systemui.res.R.string.build_number_clip_data_label
        )

    private val underTest = kosmos.buildNumberInteractor

    @Test
    fun nonAdminUser_settingEnabled_buildNumberNull() =
        with(kosmos) {
            testScope.runTest {
                val buildNumber by collectLastValue(underTest.buildNumber)

                fakeUserRepository.setSelectedUserInfo(nonAdminUserInfo)
                setSettingValue(true)

                assertThat(buildNumber).isNull()
            }
        }

    @Test
    fun adminUser_buildNumberCorrect_onlyWhenSettingEnabled() =
        with(kosmos) {
            testScope.runTest {
                val buildNumber by collectLastValue(underTest.buildNumber)

                fakeUserRepository.setSelectedUserInfo(adminUserInfo)

                setSettingValue(false)
                assertThat(buildNumber).isNull()

                setSettingValue(true)
                assertThat(buildNumber).isEqualTo(expectedBuildNumber)
            }
        }

    @Test
    fun copyToClipboard() =
        with(kosmos) {
            testScope.runTest {
                fakeUserRepository.setSelectedUserInfo(adminUserInfo)

                underTest.copyBuildNumber()
                runCurrent()

                val argumentCaptor = argumentCaptor<ClipData>()

                verify(clipboardManager).setPrimaryClip(argumentCaptor.capture())

                with(argumentCaptor.firstValue) {
                    assertThat(description.label).isEqualTo(clipLabel)
                    assertThat(description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
                        .isTrue()
                    assertThat(itemCount).isEqualTo(1)
                    assertThat(getItemAt(0).text).isEqualTo(expectedBuildNumber.value)
                }
            }
        }

    private companion object {
        const val SETTING_NAME = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED

        val adminUserInfo =
            UserInfo(
                /* id= */ 10,
                /* name= */ "",
                /* flags */ UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
            )
        val nonAdminUserInfo =
            UserInfo(/* id= */ 11, /* name= */ "", /* flags */ UserInfo.FLAG_FULL)

        fun Kosmos.setSettingValue(enabled: Boolean) {
            fakeGlobalSettings.putInt(SETTING_NAME, if (enabled) 1 else 0)
        }
    }
}
