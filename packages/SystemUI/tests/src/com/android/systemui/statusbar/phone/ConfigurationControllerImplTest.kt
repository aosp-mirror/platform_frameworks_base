/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone

import androidx.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ConfigurationControllerImplTest : SysuiTestCase() {

    private val mConfigurationController =
            com.android.systemui.statusbar.phone.ConfigurationControllerImpl(mContext)

    @Test
    fun testThemeChange() {
        val listener = mock(ConfigurationListener::class.java)
        mConfigurationController.addCallback(listener)

        mConfigurationController.notifyThemeChanged()
        verify(listener).onThemeChanged()
    }

    @Test
    fun testRemoveListenerDuringCallback() {
        val listener = mock(ConfigurationListener::class.java)
        mConfigurationController.addCallback(listener)
        val listener2 = mock(ConfigurationListener::class.java)
        mConfigurationController.addCallback(listener2)

        doAnswer {
            mConfigurationController.removeCallback(listener2)
            null
        }.`when`(listener).onThemeChanged()

        mConfigurationController.notifyThemeChanged()
        verify(listener).onThemeChanged()
        verify(listener2, never()).onThemeChanged()
    }
}
