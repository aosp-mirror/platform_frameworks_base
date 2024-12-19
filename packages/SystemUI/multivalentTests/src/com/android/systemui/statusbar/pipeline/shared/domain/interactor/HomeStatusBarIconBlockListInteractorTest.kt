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

package com.android.systemui.statusbar.pipeline.shared.domain.interactor

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.res.R
import com.android.systemui.shared.settings.data.repository.fakeSecureSettingsRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeStatusBarIconBlockListInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    private val Kosmos.underTest by Kosmos.Fixture { kosmos.homeStatusBarIconBlockListInteractor }

    @Test
    fun iconBlockList_containsResources() =
        kosmos.runTest {
            // GIVEN a list of blocked icons
            overrideResource(
                R.array.config_collapsed_statusbar_icon_blocklist,
                arrayOf("test1", "test2"),
            )

            // GIVEN the vibrate is set to show (not blocked)
            fakeSecureSettingsRepository.setInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 1)

            val latest by collectLastValue(underTest.iconBlockList)

            // THEN the volume is not the blocklist
            assertThat(latest).containsExactly("test1", "test2")
        }

    @Test
    fun iconBlockList_checksVolumeSetting() =
        kosmos.runTest {
            // GIVEN a list of blocked icons
            overrideResource(
                R.array.config_collapsed_statusbar_icon_blocklist,
                arrayOf("test1", "test2"),
            )

            // GIVEN the vibrate icon is set to be hidden
            fakeSecureSettingsRepository.setInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0)

            val latest by collectLastValue(underTest.iconBlockList)

            // THEN the volume is in the blocklist
            assertThat(latest).containsExactly("test1", "test2", "volume")
        }

    @Test
    fun iconBlockList_updatesWithVolumeSetting() =
        kosmos.runTest {
            // GIVEN a list of blocked icons
            overrideResource(
                R.array.config_collapsed_statusbar_icon_blocklist,
                arrayOf("test1", "test2"),
            )

            fakeSecureSettingsRepository.setInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0)

            val latest by collectLastValue(underTest.iconBlockList)

            // Initially blocked
            assertThat(latest).containsExactly("test1", "test2", "volume")

            // Setting updates
            fakeSecureSettingsRepository.setInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 1)

            // Not blocked
            assertThat(latest).containsExactly("test1", "test2")

            // Setting updates again
            fakeSecureSettingsRepository.setInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0)

            // ... blocked
            assertThat(latest).containsExactly("test1", "test2", "volume")
        }
}
