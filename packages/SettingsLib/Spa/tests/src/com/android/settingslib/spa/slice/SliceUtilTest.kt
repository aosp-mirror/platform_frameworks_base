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
import android.content.Intent
import android.net.Uri
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SliceUtilTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment = SpaEnvironmentForTest(context)

    @Test
    fun sliceUriTest() {
        assertThat(Uri.EMPTY.getEntryId()).isNull()
        assertThat(Uri.EMPTY.getDestination()).isNull()
        assertThat(Uri.EMPTY.getRuntimeArguments().size()).isEqualTo(0)
        assertThat(Uri.EMPTY.getSliceId()).isNull()

        // valid slice uri
        val dest = "myRoute"
        val entryId = "myEntry"
        val sliceUriWithoutParams = Uri.Builder().appendSpaParams(dest, entryId).build()
        assertThat(sliceUriWithoutParams.getEntryId()).isEqualTo(entryId)
        assertThat(sliceUriWithoutParams.getDestination()).isEqualTo(dest)
        assertThat(sliceUriWithoutParams.getRuntimeArguments().size()).isEqualTo(0)
        assertThat(sliceUriWithoutParams.getSliceId()).isEqualTo("${entryId}_Bundle[{}]")

        val sliceUriWithParams =
            Uri.Builder().appendSpaParams(dest, entryId, bundleOf("p1" to "v1")).build()
        assertThat(sliceUriWithParams.getEntryId()).isEqualTo(entryId)
        assertThat(sliceUriWithParams.getDestination()).isEqualTo(dest)
        assertThat(sliceUriWithParams.getRuntimeArguments().size()).isEqualTo(1)
        assertThat(sliceUriWithParams.getSliceId()).isEqualTo("${entryId}_Bundle[{p1=v1}]")
    }

    @Test
    fun createBroadcastPendingIntentTest() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        // Empty Slice Uri
        assertThat(Uri.EMPTY.createBroadcastPendingIntent()).isNull()

        // Valid Slice Uri
        val dest = "myRoute"
        val entryId = "myEntry"
        val sliceUriWithoutParams = Uri.Builder().appendSpaParams(dest, entryId).build()
        val pendingIntent = sliceUriWithoutParams.createBroadcastPendingIntent()
        assertThat(pendingIntent).isNotNull()
        assertThat(pendingIntent!!.isBroadcast).isTrue()
        assertThat(pendingIntent.isImmutable).isFalse()
    }

    @Test
    fun createBrowsePendingIntentTest() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        // Empty Slice Uri
        assertThat(Uri.EMPTY.createBrowsePendingIntent()).isNull()

        // Empty Intent
        assertThat(Intent().createBrowsePendingIntent()).isNull()

        // Valid Slice Uri
        val dest = "myRoute"
        val entryId = "myEntry"
        val sliceUri = Uri.Builder().appendSpaParams(dest, entryId).build()
        val pendingIntent = sliceUri.createBrowsePendingIntent()
        assertThat(pendingIntent).isNotNull()
        assertThat(pendingIntent!!.isActivity).isTrue()
        assertThat(pendingIntent.isImmutable).isTrue()

        // Valid Intent
        val intent = Intent().apply {
            putExtra("spaActivityDestination", dest)
            putExtra("highlightEntry", entryId)
        }
        val pendingIntent2 = intent.createBrowsePendingIntent()
        assertThat(pendingIntent2).isNotNull()
        assertThat(pendingIntent2!!.isActivity).isTrue()
        assertThat(pendingIntent2.isImmutable).isTrue()
    }
}