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

package com.android.server.pm.parsing

import android.annotation.RawRes
import android.content.Context
import android.content.pm.parsing.ParsingPackage
import android.content.pm.parsing.ParsingPackageImpl
import android.content.pm.parsing.ParsingPackageUtils
import android.content.pm.parsing.result.ParseInput
import android.content.pm.parsing.result.ParseInput.DeferredError
import android.content.pm.parsing.result.ParseResult
import android.content.res.TypedArray
import android.os.Build
import androidx.test.InstrumentationRegistry
import com.android.frameworks.servicestests.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * There are 2 known errors when parsing a manifest that were promoted to true failures in R:
 * 1. Missing an <application> or <instrumentation> tag
 * 2. An empty string action/category in an intent-filter
 *
 * This verifies these failures when the APK targets R.
 */
class PackageParsingDeferErrorTest {

    companion object {
        private const val TEST_ACTIVITY =
                "com.android.servicestests.pm.parsing.test.TestActivity"
        private const val TEST_ACTION =
                "com.android.servicestests.pm.parsing.test.TEST_ACTION"
        private const val TEST_CATEGORY =
                "com.android.servicestests.pm.parsing.test.TEST_CATEGORY"
        private const val TEST_PERMISSION =
                "com.android.servicestests.pm.parsing.missingapp.TEST_PERMISSION"
    }

    private val context: Context = InstrumentationRegistry.getContext()

    private val inputCallback = ParseInput.Callback { changeId, _, targetSdk ->
        when (changeId) {
            DeferredError.MISSING_APP_TAG -> targetSdk > Build.VERSION_CODES.Q
            DeferredError.EMPTY_INTENT_ACTION_CATEGORY -> targetSdk > Build.VERSION_CODES.Q
            else -> throw IllegalStateException("changeId $changeId is not mocked for test")
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder(context.filesDir)

    @Test
    fun emptyIntentFilterActionSdkQ() {
        val result = parseFile(R.raw.PackageParsingTestAppEmptyActionSdkQ)
        assertWithMessage(result.errorMessage).that(result.isError).isFalse()
        val activities = result.result.activities
        // 2 because of AppDetailsActivity
        assertThat(activities).hasSize(2)
        val first = activities.first()
        assertThat(first.name).isEqualTo(TEST_ACTIVITY)
        val intents = first.intents
        assertThat(intents).hasSize(1)
        assertThat(intents.first().hasCategory(TEST_CATEGORY)).isTrue()
        assertThat(intents.first().hasAction(TEST_ACTION)).isTrue()
    }

    @Test
    fun emptyIntentFilterActionSdkR() {
        val result = parseFile(R.raw.PackageParsingTestAppEmptyActionSdkR)
        assertThat(result.isError).isTrue()
    }

    @Test
    fun emptyIntentFilterCategorySdkQ() {
        val result = parseFile(R.raw.PackageParsingTestAppEmptyCategorySdkQ)
        assertWithMessage(result.errorMessage).that(result.isError).isFalse()
        val activities = result.result.activities
        // 2 because of AppDetailsActivity
        assertThat(activities).hasSize(2)
        val first = activities.first()
        assertThat(first.name).isEqualTo(TEST_ACTIVITY)
        val intents = first.intents
        assertThat(intents).hasSize(1)
        assertThat(intents.first().hasAction(TEST_ACTION)).isTrue()
    }

    @Test
    fun emptyIntentFilterCategorySdkR() {
        val result = parseFile(R.raw.PackageParsingTestAppEmptyCategorySdkR)
        assertThat(result.isError).isTrue()
    }

    @Test
    fun missingAppTagSdkQ() {
        val result = parseFile(R.raw.PackageParsingTestAppMissingAppSdkQ)
        assertWithMessage(result.errorMessage).that(result.isError).isFalse()
        val permissions = result.result.permissions
        assertThat(permissions).hasSize(1)
        assertThat(permissions.first().name).isEqualTo(TEST_PERMISSION)
    }

    @Test
    fun missingAppTagSdkR() {
        val result = parseFile(R.raw.PackageParsingTestAppMissingAppSdkR)
        assertThat(result.isError).isTrue()
    }

    private fun parseFile(@RawRes id: Int): ParseResult<ParsingPackage> {
        val file = tempFolder.newFile()
        context.resources.openRawResource(id).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return ParsingPackageUtils.parseDefaultOneTime(file, 0 /*flags*/,
                false /*collectCertificates*/)
    }
}
