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

package com.android.systemui.statusbar.window

import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarWindowViewTest : SysuiTestCase() {

    private val underTest =
        LayoutInflater.from(context).inflate(R.layout.super_status_bar, /* root= */ null)
            as StatusBarWindowView

    @Test
    fun onConfigurationChanged_configurationControllerSet_forwardsCall() {
        val configuration = Configuration()
        val configurationController = mock<StatusBarConfigurationController>()
        underTest.setStatusBarConfigurationController(configurationController)

        underTest.onConfigurationChanged(configuration)

        verify(configurationController).onConfigurationChanged(configuration)
    }

    @Test
    fun onConfigurationChanged_configurationControllerNotSet_doesNotCrash() {
        val configuration = Configuration()

        underTest.onConfigurationChanged(configuration)
    }
}
