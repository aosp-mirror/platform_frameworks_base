/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.controls.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ControlsTileResourceConfigurationImplTest : SysuiTestCase() {

    @Test
    fun getPackageName() {
        assertThat(ControlsTileResourceConfigurationImpl().getPackageName()).isNull()
    }

    @Test
    fun getTileImageId() {
        val instance = ControlsTileResourceConfigurationImpl()
        assertEquals(instance.getTileImageId(), R.drawable.controls_icon)
    }
    @Test
    fun getTileTitleId() {
        val instance = ControlsTileResourceConfigurationImpl()
        assertEquals(instance.getTileTitleId(), R.string.quick_controls_title)
    }
}
