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

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageParser
import android.platform.test.annotations.Presubmit
import com.android.server.pm.parsing.AndroidPackageInfoFlagBehaviorTest.Companion.Param.Companion.appInfo
import com.android.server.pm.parsing.AndroidPackageInfoFlagBehaviorTest.Companion.Param.Companion.pkgInfo
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Verifies that missing/adding [PackageManager] flags adds/remove the appropriate fields from the
 * [PackageInfo] or [ApplicationInfo] results.
 *
 * This test has to be updated manually whenever the info generation behavior changes, since
 * there's no single place where flag -> field is defined besides this test.
 */
@Presubmit
@RunWith(Parameterized::class)
class AndroidPackageInfoFlagBehaviorTest : AndroidPackageParsingTestBase() {

    companion object {

        data class Param<T> constructor(
            val flag: Int,
            val logTag: String,
            val oldPkgFunction: (pkg: PackageParser.Package, flags: Int) -> T?,
            val newPkgFunction: (pkg: AndroidPackage, flags: Int) -> T?,
            val fieldFunction: (T) -> List<Any?>
        ) {
            companion object {
                fun pkgInfo(flag: Int, fieldFunction: (PackageInfo) -> List<Any?>) = Param(
                        flag, PackageInfo::class.java.simpleName,
                        ::oldPackageInfo, ::newPackageInfo, fieldFunction
                )

                fun appInfo(flag: Int, fieldFunction: (ApplicationInfo) -> List<Any?>) = Param(
                        flag, ApplicationInfo::class.java.simpleName,
                        ::oldAppInfo, ::newAppInfo, fieldFunction
                )
            }

            override fun toString(): String {
                val hex = Integer.toHexString(flag)
                val fromRight = Integer.toBinaryString(flag).reversed().indexOf('1')
                return "$logTag $hex | 1 shl $fromRight"
            }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = arrayOf(
                pkgInfo(PackageManager.GET_ACTIVITIES) { listOf(it.activities) },
                pkgInfo(PackageManager.GET_GIDS) { listOf(it.gids) },
                pkgInfo(PackageManager.GET_INSTRUMENTATION) { listOf(it.instrumentation) },
                pkgInfo(PackageManager.GET_META_DATA) { listOf(it.applicationInfo.metaData) },
                pkgInfo(PackageManager.GET_PROVIDERS) { listOf(it.providers) },
                pkgInfo(PackageManager.GET_RECEIVERS) { listOf(it.receivers) },
                pkgInfo(PackageManager.GET_SERVICES) { listOf(it.services) },
                pkgInfo(PackageManager.GET_SIGNATURES) { listOf(it.signatures) },
                pkgInfo(PackageManager.GET_SIGNING_CERTIFICATES) { listOf(it.signingInfo) },
                pkgInfo(PackageManager.GET_SHARED_LIBRARY_FILES) {
                    it.applicationInfo.run { listOf(sharedLibraryFiles, sharedLibraryFiles) }
                },
                pkgInfo(PackageManager.GET_CONFIGURATIONS) {
                    listOf(it.configPreferences, it.reqFeatures, it.featureGroups)
                },
                pkgInfo(PackageManager.GET_PERMISSIONS) {
                    listOf(it.permissions, it.requestedPermissions, it.requestedPermissionsFlags)
                },

                appInfo(PackageManager.GET_META_DATA) { listOf(it.metaData) },
                appInfo(PackageManager.GET_SHARED_LIBRARY_FILES) {
                    listOf(it.sharedLibraryFiles, it.sharedLibraryFiles)
                }
        )
    }

    @Parameterized.Parameter(0)
    lateinit var param: Param<Any>

    @Test
    fun fieldPresence() {
        oldPackages.asSequence().zip(newPackages.asSequence())
                .forEach { (old, new) ->
                    val oldWithFlag = param.oldPkgFunction(old, param.flag)
                    val newWithFlag = param.newPkgFunction(new, param.flag)
                    val oldFieldList = oldWithFlag?.let(param.fieldFunction).orEmpty()
                    val newFieldList = newWithFlag?.let(param.fieldFunction).orEmpty()

                    oldFieldList.zip(newFieldList).forEach {
                        assertWithMessage(new.packageName).that(it.second).apply {
                            // Assert same null-ness as old logic
                            if (it.first == null) {
                                isNull()
                            } else {
                                isNotNull()
                            }
                        }
                    }
                }
    }

    @Test
    fun fieldAbsence() {
        newPackages.forEach {
            val newWithoutFlag = param.newPkgFunction(it, 0)
            val newFieldListWithoutFlag = newWithoutFlag?.let(param.fieldFunction).orEmpty()
            assertThat(newFieldListWithoutFlag.filterNotNull()).isEmpty()
        }
    }
}
