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
 * limitations under the License
 */

package com.android.server.appwidget

import android.util.SizeF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.server.appwidget.AppWidgetXmlUtil.deserializeWidgetSizesStr
import com.android.server.appwidget.AppWidgetXmlUtil.serializeWidgetSizes
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppWidgetXmlUtilTest {

    private val sizes = ArrayList<SizeF>()
    private val sizeStr = "1.0x2.1,-9.91x6291.134,0.0x0.0"

    @Before
    fun setup() {
        sizes.add(SizeF(1.0f, 2.1f))
        sizes.add(SizeF(-9.91f, 6291.134f))
        sizes.add(SizeF(0f, 0f))
    }

    @Test
    fun serializeWidgetSizes() {
        val serializedSizeStr = serializeWidgetSizes(sizes)

        assertThat(serializedSizeStr).isEqualTo(sizeStr)
    }

    @Test
    fun deserializeWidgetSizesStr() {
        val deserializedSizes = deserializeWidgetSizesStr(sizeStr)

        assertThat(deserializedSizes).isEqualTo(sizes)
    }

    @Test
    fun deserializeInvalidWidgetSizesStr1() {
        assertThat(deserializeWidgetSizesStr("abc,def")).isEqualTo(null)
    }

    @Test
    fun deserializeInvalidWidgetSizesStr2() {
        assertThat(deserializeWidgetSizesStr("+30x9,90")).isEqualTo(null)
    }

    @Test
    fun deserializeNullWidgetSizesStr1() {
        assertThat(deserializeWidgetSizesStr(null)).isEqualTo(null)
    }

    @Test
    fun deserializeEmptyWidgetSizesStr1() {
        assertThat(deserializeWidgetSizesStr("")).isEqualTo(null)
    }

    @Test
    fun deserializeEmptyWidgetSizesStr2() {
        assertThat(deserializeWidgetSizesStr(",")).isEqualTo(ArrayList<SizeF>())
    }

    @Test
    fun deserializeEmptyWidgetSizesStr3() {
        assertThat(deserializeWidgetSizesStr(",,,")).isEqualTo(ArrayList<SizeF>())
    }
}
