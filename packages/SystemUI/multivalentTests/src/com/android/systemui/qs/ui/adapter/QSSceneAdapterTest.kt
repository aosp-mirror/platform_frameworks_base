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

package com.android.systemui.qs.ui.adapter

import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.Companion.Collapsing
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class QSSceneAdapterTest : SysuiTestCase() {

    @Test
    fun expanding_squishiness1() {
        assertThat(QSSceneAdapter.State.Expanding(0.3f).squishiness).isEqualTo(1f)
    }

    @Test
    fun expandingSpecialValues() {
        assertThat(QSSceneAdapter.State.QQS).isEqualTo(QSSceneAdapter.State.Expanding(0f))
        assertThat(QSSceneAdapter.State.QS).isEqualTo(QSSceneAdapter.State.Expanding(1f))
    }

    @Test
    fun collapsing() {
        val collapsingProgress = 0.3f
        assertThat(Collapsing(collapsingProgress))
            .isEqualTo(QSSceneAdapter.State.Expanding(1 - collapsingProgress))
    }

    @Test
    fun unsquishing_expansionSameAsQQS() {
        val squishiness = 0.6f
        assertThat(QSSceneAdapter.State.Unsquishing(squishiness).expansion)
            .isEqualTo(QSSceneAdapter.State.QQS.expansion)
    }
}
