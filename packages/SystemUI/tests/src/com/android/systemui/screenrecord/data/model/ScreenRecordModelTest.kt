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

package com.android.systemui.screenrecord.data.model

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenrecord.data.model.ScreenRecordModel.Starting.Companion.toCountdownSeconds
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

@SmallTest
class ScreenRecordModelTest : SysuiTestCase() {
    @Test
    fun countdownSeconds_millis0_is0() {
        assertThat(0L.toCountdownSeconds()).isEqualTo(0)
        assertThat(ScreenRecordModel.Starting(0L).countdownSeconds).isEqualTo(0)
    }

    @Test
    fun countdownSeconds_millis500_isOne() {
        assertThat(500L.toCountdownSeconds()).isEqualTo(1)
        assertThat(ScreenRecordModel.Starting(500L).countdownSeconds).isEqualTo(1)
    }

    @Test
    fun countdownSeconds_millis999_isOne() {
        assertThat(999L.toCountdownSeconds()).isEqualTo(1)
        assertThat(ScreenRecordModel.Starting(999L).countdownSeconds).isEqualTo(1)
    }

    @Test
    fun countdownSeconds_millis1000_isOne() {
        assertThat(1000L.toCountdownSeconds()).isEqualTo(1)
        assertThat(ScreenRecordModel.Starting(1000L).countdownSeconds).isEqualTo(1)
    }

    @Test
    fun countdownSeconds_millis1499_isOne() {
        assertThat(1499L.toCountdownSeconds()).isEqualTo(1)
        assertThat(ScreenRecordModel.Starting(1499L).countdownSeconds).isEqualTo(1)
    }

    @Test
    fun countdownSeconds_millis1500_isTwo() {
        assertThat(1500L.toCountdownSeconds()).isEqualTo(2)
        assertThat(ScreenRecordModel.Starting(1500L).countdownSeconds).isEqualTo(2)
    }

    @Test
    fun countdownSeconds_millis1999_isTwo() {
        assertThat(1599L.toCountdownSeconds()).isEqualTo(2)
        assertThat(ScreenRecordModel.Starting(1599L).countdownSeconds).isEqualTo(2)
    }

    @Test
    fun countdownSeconds_millis2000_isTwo() {
        assertThat(2000L.toCountdownSeconds()).isEqualTo(2)
        assertThat(ScreenRecordModel.Starting(2000L).countdownSeconds).isEqualTo(2)
    }

    @Test
    fun countdownSeconds_millis2500_isThree() {
        assertThat(2500L.toCountdownSeconds()).isEqualTo(3)
        assertThat(ScreenRecordModel.Starting(2500L).countdownSeconds).isEqualTo(3)
    }

    @Test
    fun countdownSeconds_millis2999_isThree() {
        assertThat(2999L.toCountdownSeconds()).isEqualTo(3)
        assertThat(ScreenRecordModel.Starting(2999L).countdownSeconds).isEqualTo(3)
    }

    @Test
    fun countdownSeconds_millis3000_isThree() {
        assertThat(3000L.toCountdownSeconds()).isEqualTo(3)
        assertThat(ScreenRecordModel.Starting(3000L).countdownSeconds).isEqualTo(3)
    }
}
