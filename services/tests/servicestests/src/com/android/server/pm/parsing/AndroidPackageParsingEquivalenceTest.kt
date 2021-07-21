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
import android.platform.test.annotations.Postsubmit
import androidx.test.filters.LargeTest
import com.google.common.truth.Expect
import org.junit.Rule
import org.junit.Test

/**
 * Collects APKs from the device and verifies that the new parsing behavior outputs
 * the same exposed Info object as the old parsing logic.
 */
@Postsubmit
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

    @LargeTest
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
                PackageManager.GET_SIGNING_CERTIFICATES or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
                PackageManager.MATCH_DIRECT_BOOT_AWARE
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

            // Main components are asserted independently to separate the failures. Otherwise the
            // comparison would include every component in one massive string.

            val prefix = "${it.first?.applicationInfo?.sourceDir} $packageName"

            expect.withMessage("$prefix PackageInfo")
                    .that(it.second?.dumpToString())
                    .isEqualTo(it.first?.dumpToString())

            expect.withMessage("$prefix ApplicationInfo")
                    .that(it.second?.applicationInfo?.dumpToString())
                    .isEqualTo(it.first?.applicationInfo?.dumpToString())

            val firstActivityNames = it.first?.activities?.map { it.name } ?: emptyList()
            val secondActivityNames = it.second?.activities?.map { it.name } ?: emptyList()
            expect.withMessage("$prefix activities")
                    .that(secondActivityNames)
                    .containsExactlyElementsIn(firstActivityNames)
                    .inOrder()

            if (!it.first?.activities.isNullOrEmpty() && !it.second?.activities.isNullOrEmpty()) {
                it.first?.activities?.zip(it.second?.activities!!)?.forEach {
                    expect.withMessage("$prefix ${it.first.name}")
                            .that(it.second.dumpToString())
                            .isEqualTo(it.first.dumpToString())
                }
            }

            val firstReceiverNames = it.first?.receivers?.map { it.name } ?: emptyList()
            val secondReceiverNames = it.second?.receivers?.map { it.name } ?: emptyList()
            expect.withMessage("$prefix receivers")
                    .that(secondReceiverNames)
                    .containsExactlyElementsIn(firstReceiverNames)
                    .inOrder()

            if (!it.first?.receivers.isNullOrEmpty() && !it.second?.receivers.isNullOrEmpty()) {
                it.first?.receivers?.zip(it.second?.receivers!!)?.forEach {
                    expect.withMessage("$prefix ${it.first.name}")
                            .that(it.second.dumpToString())
                            .isEqualTo(it.first.dumpToString())
                }
            }

            val firstProviderNames = it.first?.providers?.map { it.name } ?: emptyList()
            val secondProviderNames = it.second?.providers?.map { it.name } ?: emptyList()
            expect.withMessage("$prefix providers")
                    .that(secondProviderNames)
                    .containsExactlyElementsIn(firstProviderNames)
                    .inOrder()

            if (!it.first?.providers.isNullOrEmpty() && !it.second?.providers.isNullOrEmpty()) {
                it.first?.providers?.zip(it.second?.providers!!)?.forEach {
                    expect.withMessage("$prefix ${it.first.name}")
                            .that(it.second.dumpToString())
                            .isEqualTo(it.first.dumpToString())
                }
            }

            val firstServiceNames = it.first?.services?.map { it.name } ?: emptyList()
            val secondServiceNames = it.second?.services?.map { it.name } ?: emptyList()
            expect.withMessage("$prefix services")
                    .that(secondServiceNames)
                    .containsExactlyElementsIn(firstServiceNames)
                    .inOrder()

            if (!it.first?.services.isNullOrEmpty() && !it.second?.services.isNullOrEmpty()) {
                it.first?.services?.zip(it.second?.services!!)?.forEach {
                    expect.withMessage("$prefix ${it.first.name}")
                            .that(it.second.dumpToString())
                            .isEqualTo(it.first.dumpToString())
                }
            }
        }
    }
}
