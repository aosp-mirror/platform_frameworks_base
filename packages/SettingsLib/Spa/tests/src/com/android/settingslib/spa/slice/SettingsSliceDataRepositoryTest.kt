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

package com.android.settingslib.spa.slice

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.slice.Slice
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.android.settingslib.spa.tests.testutils.SppHome
import com.android.settingslib.spa.tests.testutils.SppLayer2
import com.android.settingslib.spa.tests.testutils.getUniqueEntryId
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSliceDataRepositoryTest {
    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment =
        SpaEnvironmentForTest(context, listOf(SppHome.createSettingsPage()))
    private val sliceDataRepository by spaEnvironment.sliceDataRepository

    @Test
    fun getOrBuildSliceDataTest() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        // Slice empty
        assertThat(sliceDataRepository.getOrBuildSliceData(Uri.EMPTY)).isNull()

        // Slice supported
        val page = SppLayer2.createSettingsPage()
        val entryId = getUniqueEntryId("Layer2Entry1", page)
        val sliceUri = Uri.Builder().appendSpaParams(page.buildRoute(), entryId).build()
        assertThat(sliceUri.getDestination()).isEqualTo("SppLayer2")
        assertThat(sliceUri.getSliceId()).isEqualTo("${entryId}_Bundle[{}]")
        val sliceData = sliceDataRepository.getOrBuildSliceData(sliceUri)
        assertThat(sliceData).isNotNull()
        assertThat(sliceDataRepository.getOrBuildSliceData(sliceUri)).isSameInstanceAs(sliceData)

        // Slice unsupported
        val entryId2 = getUniqueEntryId("Layer2Entry2", page)
        val sliceUri2 = Uri.Builder().appendSpaParams(page.buildRoute(), entryId2).build()
        assertThat(sliceUri2.getDestination()).isEqualTo("SppLayer2")
        assertThat(sliceUri2.getSliceId()).isEqualTo("${entryId2}_Bundle[{}]")
        assertThat(sliceDataRepository.getOrBuildSliceData(sliceUri2)).isNull()
    }

    @Test
    fun getActiveSliceDataTest() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        val page = SppLayer2.createSettingsPage()
        val entryId = getUniqueEntryId("Layer2Entry1", page)
        val sliceUri = Uri.Builder().appendSpaParams(page.buildRoute(), entryId).build()

        // build slice data first
        val sliceData = sliceDataRepository.getOrBuildSliceData(sliceUri)

        // slice data is inactive
        assertThat(sliceData!!.isActive()).isFalse()
        assertThat(sliceDataRepository.getActiveSliceData(sliceUri)).isNull()

        // slice data is active
        val observer = Observer<Slice?> { }
        sliceData.observeForever(observer)
        assertThat(sliceData.isActive()).isTrue()
        assertThat(sliceDataRepository.getActiveSliceData(sliceUri)).isSameInstanceAs(sliceData)

        // slice data is inactive again
        sliceData.removeObserver(observer)
        assertThat(sliceData.isActive()).isFalse()
        assertThat(sliceDataRepository.getActiveSliceData(sliceUri)).isNull()
    }
}
