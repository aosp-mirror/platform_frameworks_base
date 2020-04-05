/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.pm.PackageManager
import android.platform.test.annotations.Presubmit
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test

/**
 * Collects APKs from the device and verifies that the new parsing behavior outputs
 * the same exposed Info object as the old parsing logic.
 */
@Presubmit
class AndroidPackageParsingEquivalenceTest : AndroidPackageParsingTestBase() {

    @get:Rule
    val expect = Expect.create()

    @Test
    fun applicationInfoEquality() {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES
        val oldAppInfo = oldPackages.asSequence().map { oldAppInfo(it, flags) }
        val newAppInfo = newPackages.asSequence().map { newAppInfo(it, flags) }
        oldAppInfo.zip(newAppInfo).forEach {
            val firstName = it.first?.packageName
            val secondName = it.second?.packageName
            val packageName = if (firstName == secondName) {
                "$firstName"
            } else {
                "$firstName | $secondName"
            }
            expect.withMessage("${it.first?.sourceDir} $packageName")
                    .that(it.first?.dumpToString())
                    .isEqualTo(it.second?.dumpToString())
        }
    }

    @Test
    fun packageInfoEquality() {
        val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_CONFIGURATIONS or
                PackageManager.GET_GIDS or
                PackageManager.GET_INSTRUMENTATION or
                PackageManager.GET_META_DATA or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_SHARED_LIBRARY_FILES or
                PackageManager.GET_SIGNATURES or
                PackageManager.GET_SIGNING_CERTIFICATES
        val oldPackageInfo = oldPackages.asSequence().map { oldPackageInfo(it, flags) }
        val newPackageInfo = newPackages.asSequence().map { newPackageInfo(it, flags) }

        oldPackageInfo.zip(newPackageInfo).forEach {
            val firstName = it.first?.packageName
            val secondName = it.second?.packageName
            val packageName = if (firstName == secondName) {
                "$firstName"
            } else {
                "$firstName | $secondName"
            }
            expect.withMessage("${it.first?.applicationInfo?.sourceDir} $packageName")
                    .that(it.first?.dumpToString())
                    .isEqualTo(it.second?.dumpToString())
        }
    }
}


