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

import android.content.pm.ApplicationInfo
import android.content.pm.PackageParser
import android.os.Environment
import android.os.UserHandle
import android.platform.test.annotations.Presubmit
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * As a performance optimization, the new parsing code builds the user data directories manually
 * using string concatenation. This tries to mirror the logic that [Environment] uses, but it is
 * still fragile to changes and potentially different device configurations.
 *
 * This compares the resultant values against the old [PackageParser] outputs as well as
 * [ApplicationInfo]'s own [ApplicationInfo.initForUser].
 */
@Presubmit
class PackageInfoUserFieldsTest : AndroidPackageParsingTestBase() {

    @Test
    fun userEnvironmentValues() {
        // Specifically use a large user ID to test assumptions about single character IDs
        val userId = 110

        oldPackages.zip(newPackages)
                .map { (old, new) ->
                    (old to oldAppInfo(pkg = old, userId = userId)!!) to
                            (new to newAppInfo(pkg = new, userId = userId)!!)
                }
                .forEach { (oldPair, newPair) ->
                    val (oldPkg, oldInfo) = oldPair
                    val (newPkg, newInfo) = newPair

                    val oldValuesActual = extractActual(oldInfo)
                    val newValuesActual = extractActual(newInfo)
                    val oldValuesExpected: Values
                    val newValuesExpected: Values

                    val packageName = oldPkg.packageName
                    if (packageName == "android") {
                        val systemDataDir = Environment.getDataSystemDirectory().absolutePath
                        oldValuesExpected = Values(
                                uid = UserHandle.getUid(userId,
                                        UserHandle.getAppId(oldPkg.applicationInfo.uid)),
                                userDe = null,
                                userCe = null,
                                dataDir = systemDataDir
                        )
                        newValuesExpected = Values(
                                uid = UserHandle.getUid(userId, UserHandle.getAppId(newPkg.uid)),
                                userDe = null,
                                userCe = null,
                                dataDir = systemDataDir
                        )
                    } else {
                        oldValuesExpected = extractExpected(oldInfo, oldInfo.uid, userId)
                        newValuesExpected = extractExpected(newInfo, newPkg.uid, userId)
                    }

                    // Calls the internal ApplicationInfo logic to compare against. This must be
                    // done after saving the original values, since this will overwrite them.
                    oldInfo.initForUser(userId)
                    newInfo.initForUser(userId)

                    val oldInitValues = extractActual(oldInfo)
                    val newInitValues = extractActual(newInfo)

                    // The optimization is also done for the no state API that isn't used by the
                    // system. This API is still exposed publicly, so for this test we should
                    // verify it.
                    val newNoStateValues = extractActual(
                            newAppInfoWithoutState(newPkg, 0, userId)!!)

                    assertAllEquals(packageName,
                            oldValuesActual, oldValuesExpected, oldInitValues,
                            newValuesActual, newValuesExpected, newInitValues, newNoStateValues)
                }
    }

    private fun assertAllEquals(packageName: String, vararg values: Values) {
        // Local function to avoid accidentally calling wrong type
        fun assertAllEquals(message: String, vararg values: Any?) {
            values.forEachIndexed { index, value ->
                if (index == 0) return@forEachIndexed
                assertWithMessage("$message $index").that(values[0]).isEqualTo(value)
            }
        }

        assertAllEquals("$packageName mismatched uid", values.map { it.uid })
        assertAllEquals("$packageName mismatched userDe", values.map { it.userDe })
        assertAllEquals("$packageName mismatched userCe", values.map { it.userCe })
        assertAllEquals("$packageName mismatched dataDir", values.map { it.dataDir })
    }

    private fun extractActual(appInfo: ApplicationInfo) = Values(
            uid = appInfo.uid,
            userDe = appInfo.deviceProtectedDataDir,
            userCe = appInfo.credentialProtectedDataDir,
            dataDir = appInfo.dataDir
    )

    private fun extractExpected(appInfo: ApplicationInfo, appIdUid: Int, userId: Int): Values {
        val userDe = Environment.getDataUserDePackageDirectory(appInfo.volumeUuid, userId,
                appInfo.packageName).absolutePath
        val userCe = Environment.getDataUserCePackageDirectory(appInfo.volumeUuid, userId,
                appInfo.packageName).absolutePath
        val dataDir = if (appInfo.isDefaultToDeviceProtectedStorage) {
            appInfo.deviceProtectedDataDir
        } else {
            appInfo.credentialProtectedDataDir
        }

        return Values(
                uid = UserHandle.getUid(userId, UserHandle.getAppId(appIdUid)),
                userDe = userDe,
                userCe = userCe,
                dataDir = dataDir
        )
    }

    data class Values(
        val uid: Int,
        val userDe: String?,
        val userCe: String?,
        val dataDir: String?
    )
}
