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

package com.android.systemui.qs.tiles.impl.work.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.work.domain.model.WorkModeTileModel
import com.android.systemui.utils.leaks.FakeManagedProfileController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class WorkModeTileDataInteractorTest : SysuiTestCase() {
    private val controller = FakeManagedProfileController(LeakCheck())
    private val underTest: WorkModeTileDataInteractor = WorkModeTileDataInteractor(controller)

    @Test
    fun availability_matchesControllerHasActiveProfiles() = runTest {
        val availability by collectLastValue(underTest.availability(TEST_USER))

        assertThat(availability).isFalse()

        controller.setHasActiveProfile(true)
        assertThat(availability).isTrue()

        controller.setHasActiveProfile(false)
        assertThat(availability).isFalse()
    }

    @Test
    fun tileData_whenHasActiveProfile_matchesControllerIsEnabled() = runTest {
        controller.setHasActiveProfile(true)
        val data by
            collectLastValue(
                underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
            )

        assertThat(data).isInstanceOf(WorkModeTileModel.HasActiveProfile::class.java)
        assertThat((data as WorkModeTileModel.HasActiveProfile).isEnabled).isFalse()

        controller.isWorkModeEnabled = true
        assertThat(data).isInstanceOf(WorkModeTileModel.HasActiveProfile::class.java)
        assertThat((data as WorkModeTileModel.HasActiveProfile).isEnabled).isTrue()

        controller.isWorkModeEnabled = false
        assertThat(data).isInstanceOf(WorkModeTileModel.HasActiveProfile::class.java)
        assertThat((data as WorkModeTileModel.HasActiveProfile).isEnabled).isFalse()
    }

    @Test
    fun tileData_matchesControllerHasActiveProfile() = runTest {
        val data by
            collectLastValue(
                underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
            )
        assertThat(data).isInstanceOf(WorkModeTileModel.NoActiveProfile::class.java)

        controller.setHasActiveProfile(true)
        assertThat(data).isInstanceOf(WorkModeTileModel.HasActiveProfile::class.java)

        controller.setHasActiveProfile(false)
        assertThat(data).isInstanceOf(WorkModeTileModel.NoActiveProfile::class.java)
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
