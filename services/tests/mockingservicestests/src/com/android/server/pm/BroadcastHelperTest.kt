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

package com.android.server.pm

import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
class BroadcastHelperTest {

    companion object {
        const val TEST_PACKAGE_1 = "com.android.test.package1"
        const val TEST_PACKAGE_2 = "com.android.test.package2"
        const val TEST_UID_1 = 10100
        const val TEST_UID_2 = 10101
        const val TEST_USER_ID = 0
    }

    lateinit var broadcastHelper: BroadcastHelper
    lateinit var packagesToChange: Array<String>
    lateinit var uidsToChange: IntArray

    @Mock
    lateinit var snapshot: Computer

    @Rule
    @JvmField
    val rule = MockSystemRule()

    @Before
    open fun setup() {
        MockitoAnnotations.initMocks(this)
        rule.system().stageNominalSystemState()
        broadcastHelper = BroadcastHelper(rule.mocks().injector)
        packagesToChange = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        uidsToChange = intArrayOf(TEST_UID_1, TEST_UID_2)
    }

    @Test
    fun getBroadcastParams_withSameVisibilityAllowList_shouldGroup() {
        val allowList = intArrayOf(10001, 10002, 10003)
        mockVisibilityAllowList(TEST_PACKAGE_1, allowList)
        mockVisibilityAllowList(TEST_PACKAGE_2, allowList)

        val broadcastParams: List<BroadcastParams> = broadcastHelper.getBroadcastParams(
                snapshot, packagesToChange, uidsToChange, TEST_USER_ID)

        assertThat(broadcastParams).hasSize(1)
        assertThat(broadcastParams[0].packageNames).containsExactlyElementsIn(
                packagesToChange.toCollection(ArrayList()))
        assertThat(broadcastParams[0].uids.toArray()).asList().containsExactlyElementsIn(
                uidsToChange.toCollection(ArrayList()))
    }

    @Test
    fun getBroadcastParams_withDifferentVisibilityAllowList_shouldNotGroup() {
        val allowList1 = intArrayOf(10001, 10002, 10003)
        val allowList2 = intArrayOf(10001, 10002, 10007)
        mockVisibilityAllowList(TEST_PACKAGE_1, allowList1)
        mockVisibilityAllowList(TEST_PACKAGE_2, allowList2)

        val broadcastParams: List<BroadcastParams> = broadcastHelper.getBroadcastParams(
                snapshot, packagesToChange, uidsToChange, TEST_USER_ID)

        assertThat(broadcastParams).hasSize(2)
        broadcastParams.forEachIndexed { i, params ->
            val changedPackages = params.packageNames
            val changedUids = params.uids
            assertThat(changedPackages[0]).isEqualTo(packagesToChange[i])
            assertThat(changedUids[0]).isEqualTo(uidsToChange[i])
        }
    }

    @Test
    fun getBroadcastParams_withNullVisibilityAllowList_shouldNotGroup() {
        val allowList = intArrayOf(10001, 10002, 10003)
        mockVisibilityAllowList(TEST_PACKAGE_1, allowList)
        mockVisibilityAllowList(TEST_PACKAGE_2, null)

        val broadcastParams: List<BroadcastParams> = broadcastHelper.getBroadcastParams(
                snapshot, packagesToChange, uidsToChange, TEST_USER_ID)

        assertThat(broadcastParams).hasSize(2)
        broadcastParams.forEachIndexed { i, params ->
            val changedPackages = params.packageNames
            val changedUids = params.uids
            assertThat(changedPackages[0]).isEqualTo(packagesToChange[i])
            assertThat(changedUids[0]).isEqualTo(uidsToChange[i])
        }
    }

    private fun mockVisibilityAllowList(pkgName: String, list: IntArray?) {
        whenever(snapshot.getVisibilityAllowList(pkgName, TEST_USER_ID))
                .thenReturn(list ?: IntArray(0))
    }
}