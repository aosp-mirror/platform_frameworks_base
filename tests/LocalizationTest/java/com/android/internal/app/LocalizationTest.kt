/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.android.internal.app

import android.os.SystemProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.internal.R
import com.android.internal.app.LocalePicker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockitoSession

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private val mContext = InstrumentationRegistry.getInstrumentation().context
    private val mUnfilteredLocales =
            mContext.getResources().getStringArray(R.array.supported_locales)

    private lateinit var mMockitoSession: MockitoSession

    @Before
    fun setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .spyStatic(SystemProperties::class.java)
                .startMocking()
    }

    @After
    fun tearDown() {
        mMockitoSession.finishMocking()
    }

    @Test
    fun testGetSupportedLocales_noFilter() {
        // Filter not set.
        setTestLocaleFilter(null)

        val locales1 = LocalePicker.getSupportedLocales(mContext)

        assertThat(locales1).isEqualTo(mUnfilteredLocales)

        // Empty filter.
        setTestLocaleFilter("")

        val locales2 = LocalePicker.getSupportedLocales(mContext)

        assertThat(locales2).isEqualTo(mUnfilteredLocales)
    }

    @Test
    fun testGetSupportedLocales_invalidFilter() {
        setTestLocaleFilter("**")

        val locales = LocalePicker.getSupportedLocales(mContext)

        assertThat(locales).isEqualTo(mUnfilteredLocales)
    }

    @Test
    fun testGetSupportedLocales_inclusiveFilter() {
        setTestLocaleFilter("^(de-AT|de-DE|en|ru).*")

        val locales = LocalePicker.getSupportedLocales(mContext)

        assertThat(locales).isEqualTo(
                mUnfilteredLocales
                        .filter { it.startsWithAnyOf("de-AT", "de-DE", "en", "ru") }
                        .toTypedArray()
        )
    }

    @Test
    fun testGetSupportedLocales_exclusiveFilter() {
        setTestLocaleFilter("^(?!de-IT|es|fr).*")

        val locales = LocalePicker.getSupportedLocales(mContext)

        assertThat(locales).isEqualTo(
                mUnfilteredLocales
                        .filter { !it.startsWithAnyOf("de-IT", "es", "fr") }
                        .toTypedArray()
        )
    }

    private fun setTestLocaleFilter(localeFilter: String?) {
        doReturn(localeFilter).`when` { SystemProperties.get(eq("ro.localization.locale_filter")) }
    }

    private fun String.startsWithAnyOf(vararg prefixes: String): Boolean {
        prefixes.forEach {
            if (startsWith(it)) return true
        }

        return false
    }
}
