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

package com.android.settingslib.spa.framework.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment = SpaEnvironmentForTest(context)

    @Before
    fun setEnvironment() {
        SpaEnvironmentFactory.reset(spaEnvironment)
    }

    @Test
    fun testCreateIntent() {
        val nullPage = SettingsPage.createNull()
        Truth.assertThat(nullPage.createIntent()).isNull()
        Truth.assertThat(SettingsEntryBuilder.createInject(nullPage).build().createIntent())
            .isNull()

        val page = spaEnvironment.createPage("SppHome")
        val pageIntent = page.createIntent()
        Truth.assertThat(pageIntent).isNotNull()
        Truth.assertThat(pageIntent!!.getDestination()).isEqualTo(page.buildRoute())
        Truth.assertThat(pageIntent.getEntryId()).isNull()
        Truth.assertThat(pageIntent.getSessionName()).isNull()

        val entry = SettingsEntryBuilder.createInject(page).build()
        val entryIntent = entry.createIntent(SESSION_SEARCH)
        Truth.assertThat(entryIntent).isNotNull()
        Truth.assertThat(entryIntent!!.getDestination()).isEqualTo(page.buildRoute())
        Truth.assertThat(entryIntent.getEntryId()).isEqualTo(entry.id)
        Truth.assertThat(entryIntent.getSessionName()).isEqualTo(SESSION_SEARCH)
    }
}