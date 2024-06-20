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

package com.android.systemui.qs.panels.data.repository

import android.content.ComponentName
import android.content.packageManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.pm.UserInfo
import android.graphics.drawable.TestStubDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.kosmos.mainCoroutineContext
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.shared.model.EditTileData
import com.android.systemui.qs.pipeline.data.repository.FakeInstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.data.repository.fakeInstalledTilesRepository
import com.android.systemui.qs.pipeline.data.repository.installedTilesRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class IconAndNameCustomRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val packageManager: PackageManager = kosmos.packageManager
    private val userTracker: FakeUserTracker =
        kosmos.fakeUserTracker.apply {
            whenever(userContext.packageManager).thenReturn(packageManager)
        }

    private val service1 =
        FakeInstalledTilesComponentRepository.ServiceInfo(
            component1,
            tileService1,
            drawable1,
            appName1,
        )

    private val service2 =
        FakeInstalledTilesComponentRepository.ServiceInfo(
            component2,
            tileService2,
            drawable2,
            appName2,
        )

    private val underTest =
        with(kosmos) {
            IconAndNameCustomRepository(
                installedTilesRepository,
                userTracker,
                mainCoroutineContext,
            )
        }

    @Before
    fun setUp() {
        kosmos.fakeInstalledTilesRepository.setInstalledServicesForUser(
            userTracker.userId,
            listOf(service1, service2)
        )
    }

    @Test
    fun loadDataForCurrentServices() =
        with(kosmos) {
            testScope.runTest {
                val editTileDataList = underTest.getCustomTileData()
                val expectedData1 =
                    EditTileData(
                        TileSpec.create(component1),
                        Icon.Loaded(drawable1, ContentDescription.Loaded(tileService1)),
                        Text.Loaded(tileService1),
                        Text.Loaded(appName1),
                    )
                val expectedData2 =
                    EditTileData(
                        TileSpec.create(component2),
                        Icon.Loaded(drawable2, ContentDescription.Loaded(tileService2)),
                        Text.Loaded(tileService2),
                        Text.Loaded(appName2),
                    )

                assertThat(editTileDataList).containsExactly(expectedData1, expectedData2)
            }
        }

    @Test
    fun loadDataForCurrentServices_otherCurrentUser_empty() =
        with(kosmos) {
            testScope.runTest {
                userTracker.set(listOf(UserInfo(11, "", 0)), 0)
                val editTileDataList = underTest.getCustomTileData()

                assertThat(editTileDataList).isEmpty()
            }
        }

    @Test
    fun loadDataForCurrentServices_serviceInfoWithNullIcon_notInList() =
        with(kosmos) {
            testScope.runTest {
                val serviceNullIcon =
                    FakeInstalledTilesComponentRepository.ServiceInfo(
                        component2,
                        tileService2,
                    )
                fakeInstalledTilesRepository.setInstalledServicesForUser(
                    userTracker.userId,
                    listOf(service1, serviceNullIcon)
                )

                val expectedData1 =
                    EditTileData(
                        TileSpec.create(component1),
                        Icon.Loaded(drawable1, ContentDescription.Loaded(tileService1)),
                        Text.Loaded(tileService1),
                        Text.Loaded(appName1),
                    )

                val editTileDataList = underTest.getCustomTileData()
                assertThat(editTileDataList).containsExactly(expectedData1)
            }
        }

    private companion object {
        val drawable1 = TestStubDrawable("drawable1")
        val appName1 = "App1"
        val tileService1 = "Tile Service 1"
        val component1 = ComponentName("pkg1", "srv1")

        val drawable2 = TestStubDrawable("drawable2")
        val appName2 = "App2"
        val tileService2 = "Tile Service 2"
        val component2 = ComponentName("pkg2", "srv2")
    }
}
