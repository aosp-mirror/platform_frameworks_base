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

package com.android.systemui.qs.user

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class UserDialogTest : SysuiTestCase() {

    private lateinit var dialog: UserDialog

    @Before
    fun setUp() {
        dialog = UserDialog(mContext)
    }

    @After
    fun tearDown() {
        dialog.dismiss()
    }

    @Test
    fun doneButtonExists() {
        assertThat(dialog.doneButton).isInstanceOf(View::class.java)
    }

    @Test
    fun settingsButtonExists() {
        assertThat(dialog.settingsButton).isInstanceOf(View::class.java)
    }

    @Test
    fun gridExistsAndIsViewGroup() {
        assertThat(dialog.grid).isInstanceOf(ViewGroup::class.java)
    }
}