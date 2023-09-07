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

package com.android.server.pm.test

import android.os.UserHandle
import android.util.ArrayMap
import com.android.server.pm.Computer
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.PackageManagerService
import com.android.server.pm.local.PackageManagerLocalImpl
import com.android.server.pm.pkg.PackageState
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import kotlin.test.assertFailsWith

class PackageManagerLocalSnapshotTest {

    private val service = mockThrowOnUnmocked<PackageManagerService> {
        @Suppress("DEPRECATION")
        whenever(snapshotComputer(false)) { mockSnapshot() }
    }

    private val packageStateAll = mockPackageState("com.package.all")
    private val packageStateUser0 = mockPackageState("com.package.zero")
    private val packageStateUser10 = mockPackageState("com.package.ten")

    @Test
    fun unfiltered() {
        val pmLocal = pmLocal()
        val snapshot = pmLocal.withUnfilteredSnapshot()
        val filteredOne: PackageManagerLocal.FilteredSnapshot
        val filteredTwo: PackageManagerLocal.FilteredSnapshot
        snapshot.use {
            val packageStates = it.packageStates

            // Check for unmodifiable
            assertFailsWith(UnsupportedOperationException::class) {
                it.packageStates.clear()
            }

            // Check contents
            assertThat(packageStates).containsExactly(
                packageStateAll.packageName, packageStateAll,
                packageStateUser0.packageName, packageStateUser0,
                packageStateUser10.packageName, packageStateUser10,
            )

            // Check further calls get the same object
            assertThat(it.packageStates).isSameInstanceAs(packageStates)

            // Generate 3 filtered children (2 for the same caller, 1 for different)
            filteredOne = it.filtered(1000, UserHandle.getUserHandleForUid(1000))
            filteredTwo = it.filtered(1000, UserHandle.getUserHandleForUid(1000))
            val filteredThree = it.filtered(20000, UserHandle.getUserHandleForUid(1001000))

            // Check that siblings, even for the same input, are isolated
            assertThat(filteredOne).isNotSameInstanceAs(filteredTwo)

            assertThat(filteredOne.getPackageState(packageStateAll.packageName))
                .isEqualTo(packageStateAll)
            assertThat(filteredOne.getPackageState(packageStateUser0.packageName))
                .isEqualTo(packageStateUser0)
            assertThat(filteredOne.getPackageState(packageStateUser10.packageName)).isNull()

            filteredThree.use {
                // Check for unmodifiable
                assertFailsWith(UnsupportedOperationException::class) {
                    it.packageStates.clear()
                }
                assertThat(it.packageStates).containsExactly(
                    packageStateAll.packageName, packageStateAll,
                    packageStateUser10.packageName, packageStateUser10,
                )
            }

            // Call after child close, parent open fails
            assertClosedFailure {
                filteredThree.getPackageState(packageStateAll.packageName)
            }

            // Manually close first sibling and check that second still works
            filteredOne.close()
            assertThat(filteredTwo.getPackageState(packageStateAll.packageName))
                .isEqualTo(packageStateAll)
        }

        // Call after close fails
        assertClosedFailure { snapshot.packageStates }
        assertClosedFailure { filteredOne.packageStates }
        assertClosedFailure {
            filteredTwo.getPackageState(packageStateAll.packageName)
        }

        // Check newly taken snapshot is different
        assertThat(pmLocal.withUnfilteredSnapshot()).isNotSameInstanceAs(snapshot)
    }

    @Test
    fun filtered() {
        val pmLocal = pmLocal()
        val snapshot = pmLocal.withFilteredSnapshot(1000, UserHandle.getUserHandleForUid(1000))
        snapshot.use {
            assertThat(it.getPackageState(packageStateAll.packageName))
                .isEqualTo(packageStateAll)
            assertThat(it.getPackageState(packageStateUser0.packageName))
                .isEqualTo(packageStateUser0)
            assertThat(it.getPackageState(packageStateUser10.packageName)).isNull()

            // Check for unmodifiable
            assertFailsWith(UnsupportedOperationException::class) {
                it.packageStates.clear()
            }

            assertThat(it.packageStates).containsExactly(
                packageStateAll.packageName, packageStateAll,
                packageStateUser0.packageName, packageStateUser0,
            )
        }

        // Call after close fails
        assertClosedFailure {
            snapshot.getPackageState(packageStateAll.packageName)
        }

        // Check newly taken snapshot is different
        assertThat(pmLocal.withFilteredSnapshot()).isNotSameInstanceAs(snapshot)
    }

    private fun pmLocal(): PackageManagerLocal = PackageManagerLocalImpl(service)

    private fun mockSnapshot() = mockThrowOnUnmocked<Computer> {
        val packageStates = ArrayMap<String, PackageStateInternal>().apply {
            put(packageStateAll.packageName, packageStateAll)
            put(packageStateUser0.packageName, packageStateUser0)
            put(packageStateUser10.packageName, packageStateUser10)
        }
        doReturn(packageStates).whenever(this).packageStates
        whenever(getPackageStateFiltered(anyString(), anyInt(), anyInt())) {
            packageStates[arguments[0]]?.takeUnless {
                shouldFilterApplication(it, arguments[1] as Int, arguments[2] as Int)
            }
        }

        whenever(
            shouldFilterApplication(any(PackageStateInternal::class.java), anyInt(), anyInt())
        ) {
            val packageState = arguments[0] as PackageState
            val user = arguments[2] as Int

            when (packageState) {
                packageStateAll -> false
                packageStateUser0 -> user != 0
                packageStateUser10 -> user != 10
                else -> true
            }
        }
    }

    private fun mockPackageState(packageName: String) = mockThrowOnUnmocked<PackageStateInternal> {
        whenever(this.packageName) { packageName }
        whenever(toString()) { packageName }
    }

    private fun assertClosedFailure(block: () -> Unit) =
        assertFailsWith(IllegalStateException::class, block)
            .run { assertThat(message).isEqualTo("Snapshot already closed") }
}
