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

package com.android.systemui.shade

import android.app.StatusBarManager
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class QuickSettingsControllerImplWithCoroutinesTest : QuickSettingsControllerImplBaseTest() {

    @Test
    fun isExpansionEnabled_dozing_false() =
        mTestScope.runTest {
            mKeyguardRepository.setIsDozing(true)
            runCurrent()

            assertThat(mQsController.isExpansionEnabled).isFalse()

            coroutineContext.cancelChildren()
        }

    @Test
    fun isExpansionEnabled_notDozing_true() =
        mTestScope.runTest {
            mKeyguardRepository.setIsDozing(false)
            runCurrent()

            assertThat(mQsController.isExpansionEnabled).isTrue()

            coroutineContext.cancelChildren()
        }

    @Test
    fun isExpansionEnabled_qsDisabled_false() =
        mTestScope.runTest {
            mDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    StatusBarManager.DISABLE_NONE,
                    StatusBarManager.DISABLE2_QUICK_SETTINGS
                )
            runCurrent()

            assertThat(mQsController.isExpansionEnabled).isFalse()

            coroutineContext.cancelChildren()
        }

    @Test
    fun isExpansionEnabled_shadeDisabled_false() =
        mTestScope.runTest {
            mDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    StatusBarManager.DISABLE_NONE,
                    StatusBarManager.DISABLE2_NOTIFICATION_SHADE
                )
            runCurrent()

            assertThat(mQsController.isExpansionEnabled).isFalse()

            coroutineContext.cancelChildren()
        }

    @Test
    fun isExpansionEnabled_qsAndShadeEnabled_true() =
        mTestScope.runTest {
            mDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(StatusBarManager.DISABLE_NONE, StatusBarManager.DISABLE2_NONE)
            runCurrent()

            assertThat(mQsController.isExpansionEnabled).isTrue()

            coroutineContext.cancelChildren()
        }
}
