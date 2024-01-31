/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.common.domain.interactor

import android.content.pm.UserInfo
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.data.repository.fakePackageChangeRepository
import com.android.systemui.common.data.repository.packageChangeRepository
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class PackageChangeInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var underTest: PackageChangeInteractor

    @Before
    fun setUp() =
        with(kosmos) {
            underTest =
                PackageChangeInteractor(
                    packageChangeRepository = packageChangeRepository,
                    userInteractor = selectedUserInteractor,
                )
            fakeUserRepository.setUserInfos(listOf(MAIN_USER, SECONDARY_USER))
        }

    @Test
    fun packageChanges() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(underTest.packageChanged(MAIN_USER_HANDLE))
                assertThat(packageChange).isNull()

                // Even if secondary user is active, we should still receive changes for the
                // primary user.
                setUser(SECONDARY_USER)

                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "pkg",
                        packageUid = UserHandle.getUid(MAIN_USER.id, /* appId = */ 10),
                    )
                )

                assertThat(packageChange).isInstanceOf(PackageChangeModel.Installed::class.java)
                assertThat(packageChange?.packageName).isEqualTo("pkg")
            }
        }

    @Test
    fun packageChanges_ignoresUpdatesFromOtherUsers() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(underTest.packageChanged(MAIN_USER_HANDLE))
                assertThat(packageChange).isNull()

                setUser(SECONDARY_USER)
                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "pkg",
                        packageUid = UserHandle.getUid(SECONDARY_USER.id, /* appId = */ 10),
                    )
                )

                assertThat(packageChange).isNull()
            }
        }

    @Test
    fun packageChanges_forCurrentUser() =
        with(kosmos) {
            testScope.runTest {
                val packageChanges by collectValues(underTest.packageChanged(UserHandle.CURRENT))
                assertThat(packageChanges).isEmpty()

                setUser(SECONDARY_USER)
                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "first",
                        packageUid = UserHandle.getUid(SECONDARY_USER.id, /* appId = */ 10),
                    )
                )
                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "second",
                        packageUid = UserHandle.getUid(MAIN_USER.id, /* appId = */ 10),
                    )
                )
                setUser(MAIN_USER)
                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "third",
                        packageUid = UserHandle.getUid(MAIN_USER.id, /* appId = */ 10),
                    )
                )

                assertThat(packageChanges.map { it.packageName })
                    .containsExactly("first", "third")
                    .inOrder()
            }
        }

    @Test
    fun packageChanges_forSpecificPackageName() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by
                    collectLastValue(underTest.packageChanged(MAIN_USER_HANDLE, "mypkg"))
                assertThat(packageChange).isNull()

                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "other",
                        packageUid = UserHandle.getUid(MAIN_USER.id, /* appId = */ 10),
                    )
                )
                assertThat(packageChange).isNull()

                fakePackageChangeRepository.notifyChange(
                    PackageChangeModel.Installed(
                        packageName = "mypkg",
                        packageUid = UserHandle.getUid(MAIN_USER.id, /* appId = */ 10),
                    )
                )
                assertThat(packageChange).isInstanceOf(PackageChangeModel.Installed::class.java)
                assertThat(packageChange?.packageName).isEqualTo("mypkg")
            }
        }

    private suspend fun TestScope.setUser(user: UserInfo) {
        kosmos.fakeUserRepository.setSelectedUserInfo(user)
        runCurrent()
    }

    private companion object {
        val MAIN_USER_HANDLE = UserHandle.of(1)
        val MAIN_USER = UserInfo(MAIN_USER_HANDLE.identifier, "main", UserInfo.FLAG_MAIN)
        val SECONDARY_USER_HANDLE = UserHandle.of(2)
        val SECONDARY_USER = UserInfo(SECONDARY_USER_HANDLE.identifier, "secondary", 0)
    }
}
