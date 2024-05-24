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

package com.android.systemui.statusbar.policy.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.fakeUserSetupRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class UserSetupInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fakeUserSetupRepository = kosmos.fakeUserSetupRepository
    private val underTest = kosmos.userSetupInteractor

    @Test
    fun isUserSetup_false() =
        testScope.runTest {
            val setup by collectLastValue(underTest.isUserSetUp)

            fakeUserSetupRepository.setUserSetUp(false)

            assertThat(setup).isFalse()
        }

    @Test
    fun isUserSetup_true() =
        testScope.runTest {
            val setup by collectLastValue(underTest.isUserSetUp)

            fakeUserSetupRepository.setUserSetUp(true)

            assertThat(setup).isTrue()
        }
}
