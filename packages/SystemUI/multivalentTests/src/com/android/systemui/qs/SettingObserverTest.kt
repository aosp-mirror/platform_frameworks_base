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
package com.android.systemui.qs

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.settings.SettingsProxy
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class SettingObserverTest : SysuiTestCase() {

    private val DEFAULT_VALUE = 7

    @Mock lateinit var settingsProxy: SettingsProxy
    @Captor private lateinit var argumentCaptor: ArgumentCaptor<Runnable>

    private lateinit var testSettingObserver: SettingObserver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(settingsProxy.getInt(any(), any())).thenReturn(5)
        whenever(settingsProxy.getUriFor(any())).thenReturn(Uri.parse("content://test_uri"))
        testSettingObserver =
            object :
                SettingObserver(
                    settingsProxy,
                    Handler(Looper.getMainLooper()),
                    "test_setting",
                    DEFAULT_VALUE
                ) {
                override fun handleValueChanged(value: Int, observedChange: Boolean) {}
            }
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_REGISTER_SETTING_OBSERVER_ON_BG_THREAD)
    fun setListening_true_settingsProxyRegistered() {
        testSettingObserver.isListening = true
        verify(settingsProxy)
            .registerContentObserverAsync(
                any<Uri>(),
                eq(false),
                eq(testSettingObserver),
                capture(argumentCaptor)
            )
        assertThat(testSettingObserver.value).isEqualTo(5)

        // Verify if the callback applies updated value after the fact
        whenever(settingsProxy.getInt(any(), any())).thenReturn(12341234)
        argumentCaptor.value.run()
        assertThat(testSettingObserver.value).isEqualTo(12341234)
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_REGISTER_SETTING_OBSERVER_ON_BG_THREAD)
    fun setListening_false_settingsProxyRegistered() {
        testSettingObserver.isListening = true
        reset(settingsProxy)
        testSettingObserver.isListening = false

        verify(settingsProxy).unregisterContentObserverAsync(eq(testSettingObserver))
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_REGISTER_SETTING_OBSERVER_ON_BG_THREAD)
    fun setListening_bgFlagDisabled_true_settingsProxyRegistered() {
        testSettingObserver.isListening = true
        verify(settingsProxy)
            .registerContentObserverSync(any<Uri>(), eq(false), eq(testSettingObserver))
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_REGISTER_SETTING_OBSERVER_ON_BG_THREAD)
    fun setListening_bgFlagDisabled_false_settingsProxyRegistered() {
        testSettingObserver.isListening = true
        reset(settingsProxy)
        testSettingObserver.isListening = false

        verify(settingsProxy).unregisterContentObserverSync(eq(testSettingObserver))
    }
}
