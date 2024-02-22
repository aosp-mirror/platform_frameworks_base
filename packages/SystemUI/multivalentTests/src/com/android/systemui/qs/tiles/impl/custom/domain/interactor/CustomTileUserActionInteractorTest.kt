/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.custom.domain.interactor

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserInfo
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.IWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.external.componentName
import com.android.systemui.qs.external.iQSTileService
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.intentInputs
import com.android.systemui.qs.tiles.base.actions.pendingIntentInputs
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.longClick
import com.android.systemui.qs.tiles.impl.custom.customTileServiceInteractor
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.impl.custom.qsTileLogger
import com.android.systemui.qs.tiles.impl.custom.tileSpec
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CustomTileUserActionInteractorTest : SysuiTestCase() {

    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val packageManagerFacade = FakePackageManagerFacade()
    private val windowManagerFacade = FakeWindowManagerFacade()
    private val kosmos =
        testKosmos().apply {
            componentName = TEST_COMPONENT
            tileSpec = TileSpec.create(componentName)
            testCase = this@CustomTileUserActionInteractorTest
        }

    private val underTest =
        with(kosmos) {
            CustomTileUserActionInteractor(
                context =
                    mock {
                        whenever(packageManager).thenReturn(packageManagerFacade.packageManager)
                    },
                tileSpec = tileSpec,
                qsTileLogger = qsTileLogger,
                windowManager = windowManagerFacade.windowManager,
                displayTracker = mock {},
                qsTileIntentUserInputHandler = inputHandler,
                backgroundContext = testDispatcher,
                serviceInteractor = customTileServiceInteractor,
            )
        }

    private suspend fun setup() {
        with(kosmos) {
            fakeUserRepository.setUserInfos(listOf(TEST_USER_1))
            fakeUserRepository.setSelectedUserInfo(TEST_USER_1)
        }
    }

    @Test
    fun clickStartsActivityWhenPossible() =
        with(kosmos) {
            testScope.runTest {
                setup()
                underTest.handleInput(
                    click(customTileModel(activityLaunchForClick = pendingIntent()))
                )

                assertThat(windowManagerFacade.isTokenGranted).isTrue()
                assertThat(inputHandler.pendingIntentInputs).hasSize(1)
                assertThat(iQSTileService.clicks).hasSize(0)
            }
        }

    @Test
    fun clickPassedToTheServiceWhenNoActivity() =
        with(kosmos) {
            testScope.runTest {
                setup()
                packageManagerFacade.resolutionResult = null
                underTest.handleInput(click(customTileModel(activityLaunchForClick = null)))

                assertThat(windowManagerFacade.isTokenGranted).isTrue()
                assertThat(inputHandler.pendingIntentInputs).hasSize(0)
                assertThat(iQSTileService.clicks).hasSize(1)
            }
        }

    @Test
    fun longClickOpensResolvedIntent() =
        with(kosmos) {
            testScope.runTest {
                setup()
                packageManagerFacade.resolutionResult =
                    ActivityInfo().apply {
                        packageName = "resolved.pkg"
                        name = "Test"
                    }
                underTest.handleInput(longClick(customTileModel()))

                assertThat(inputHandler.intentInputs).hasSize(1)
                with(inputHandler.intentInputs.first()) {
                    assertThat(intent.action).isEqualTo(TileService.ACTION_QS_TILE_PREFERENCES)
                    assertThat(intent.component).isEqualTo(ComponentName("resolved.pkg", "Test"))
                    assertThat(
                            intent.getParcelableExtra(
                                Intent.EXTRA_COMPONENT_NAME,
                                ComponentName::class.java
                            )
                        )
                        .isEqualTo(componentName)
                    assertThat(intent.getIntExtra(TileService.EXTRA_STATE, Int.MAX_VALUE))
                        .isEqualTo(111)
                }
            }
        }

    @Test
    fun longClickOpensDefaultIntentWhenNoResolved() =
        with(kosmos) {
            testScope.runTest {
                setup()
                underTest.handleInput(longClick(customTileModel()))

                assertThat(inputHandler.intentInputs).hasSize(1)
                with(inputHandler.intentInputs.first()) {
                    assertThat(intent.action)
                        .isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    assertThat(intent.data.toString()).isEqualTo("package:test.pkg")
                }
            }
        }

    @Test
    fun revokeTokenDoesntRevokeWhenShowingDialog() =
        with(kosmos) {
            testScope.runTest {
                setup()
                underTest.handleInput(click(customTileModel()))
                underTest.setShowingDialog(true)

                underTest.revokeToken(false)

                assertThat(windowManagerFacade.isTokenGranted).isTrue()
            }
        }

    @Test
    fun forceRevokeTokenRevokesWhenShowingDialog() =
        with(kosmos) {
            testScope.runTest {
                setup()
                underTest.handleInput(click(customTileModel()))
                underTest.setShowingDialog(true)

                underTest.revokeToken(true)

                assertThat(windowManagerFacade.isTokenGranted).isFalse()
            }
        }

    @Test
    fun revokeTokenRevokesWhenNotShowingDialog() =
        with(kosmos) {
            testScope.runTest {
                setup()
                underTest.handleInput(click(customTileModel()))
                underTest.setShowingDialog(false)

                underTest.revokeToken(false)

                assertThat(windowManagerFacade.isTokenGranted).isFalse()
            }
        }

    @Test
    fun startActivityDoesntStartWithNoToken() =
        with(kosmos) {
            testScope.runTest {
                setup()
                underTest.startActivityAndCollapse(mock())

                // Checking all types of inputs
                assertThat(inputHandler.handledInputs).isEmpty()
            }
        }

    private fun pendingIntent(): PendingIntent = mock { whenever(isActivity).thenReturn(true) }

    private fun Kosmos.customTileModel(
        componentName: ComponentName = tileSpec.componentName,
        activityLaunchForClick: PendingIntent? = null,
        tileState: Int = 111,
    ) =
        CustomTileDataModel(
            TEST_USER_1.userHandle,
            componentName,
            Tile().also {
                it.activityLaunchForClick = activityLaunchForClick
                it.state = tileState
            },
            callingAppUid = 0,
            hasPendingBind = false,
            isToggleable = false,
            defaultTileLabel = "default_label",
            defaultTileIcon = Icon.createWithContentUri("default_icon"),
        )

    private class FakePackageManagerFacade(val packageManager: PackageManager = mock()) {

        var resolutionResult: ActivityInfo? = null

        init {
            whenever(packageManager.resolveActivityAsUser(any(), any<Int>(), any())).then {
                ResolveInfo().apply { activityInfo = resolutionResult }
            }
        }
    }

    private class FakeWindowManagerFacade(val windowManager: IWindowManager = mock()) {

        var isTokenGranted: Boolean = false
            private set

        init {
            with(windowManager) {
                whenever(removeWindowToken(any(), any())).then {
                    isTokenGranted = false
                    Unit
                }
                whenever(addWindowToken(any(), any(), any(), nullable())).then {
                    isTokenGranted = true
                    Unit
                }
            }
        }
    }

    private companion object {

        val TEST_COMPONENT = ComponentName("test.pkg", "test.cls")
        val TEST_USER_1 = UserInfo(1, "first user", UserInfo.FLAG_MAIN)
    }
}
