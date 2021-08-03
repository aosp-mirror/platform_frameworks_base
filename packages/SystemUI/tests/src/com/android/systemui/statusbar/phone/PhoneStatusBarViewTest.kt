/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class PhoneStatusBarViewTest : SysuiTestCase() {

    private lateinit var view: PhoneStatusBarView

    @Before
    fun setUp() {
        view = PhoneStatusBarView(mContext, null)
    }

    @Test
    fun panelEnabled_providerReturnsTrue_returnsTrue() {
        view.setPanelEnabledProvider { true }

        assertThat(view.panelEnabled()).isTrue()
    }

    @Test
    fun panelEnabled_providerReturnsFalse_returnsFalse() {
        view.setPanelEnabledProvider { false }

        assertThat(view.panelEnabled()).isFalse()
    }

    @Test
    fun panelEnabled_noProvider_noCrash() {
        view.panelEnabled()
        // No assert needed, just testing no crash
    }
}
