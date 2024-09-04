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

package com.android.systemui.biometrics.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PromptHistoryImplTest : SysuiTestCase() {

    private lateinit var history: PromptHistoryImpl

    @Before
    fun setup() {
        history = PromptHistoryImpl()
    }

    @Test
    fun empty() {
        assertThat(history.faceFailed).isFalse()
        assertThat(history.fingerprintFailed).isFalse()
    }

    @Test
    fun faceFailed() =
        repeat(2) {
            history.failure(BiometricModality.None)
            history.failure(BiometricModality.Face)

            assertThat(history.faceFailed).isTrue()
            assertThat(history.fingerprintFailed).isFalse()
        }

    @Test
    fun fingerprintFailed() =
        repeat(2) {
            history.failure(BiometricModality.None)
            history.failure(BiometricModality.Fingerprint)

            assertThat(history.faceFailed).isFalse()
            assertThat(history.fingerprintFailed).isTrue()
        }

    @Test
    fun coexFailed() =
        repeat(2) {
            history.failure(BiometricModality.Face)
            history.failure(BiometricModality.Fingerprint)

            assertThat(history.faceFailed).isTrue()
            assertThat(history.fingerprintFailed).isTrue()

            history.failure(BiometricModality.None)
        }
}
