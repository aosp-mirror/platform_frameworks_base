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

package com.android.systemui.qs.external

import android.app.Dialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.DialogInterface
import android.graphics.drawable.Icon
import android.platform.test.annotations.EnableFlags
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.IAddTileResultCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.qs.external.ui.dialog.FakeTileRequestDialogComposeDelegateFactory
import com.android.systemui.qs.external.ui.dialog.fake
import com.android.systemui.qs.external.ui.dialog.tileRequestDialogComposeDelegateFactory
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.pipeline.data.repository.fakeInstalledTilesRepository
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.runOnMainThreadAndWaitForIdleSync
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(QSComposeFragment.FLAG_NAME)
class TileServiceRequestControllerTestComposeOn : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val userId: Int
        get() = kosmos.currentTilesInteractor.userId.value

    private val mockIcon: Icon
        get() = mock()

    private val Kosmos.underTest by Kosmos.Fixture { tileServiceRequestController }

    @Before
    fun setup() {
        kosmos.fakeInstalledTilesRepository.setInstalledPackagesForUser(
            userId,
            setOf(TEST_COMPONENT),
        )
        // Start with some tiles, so adding tiles is possible (adding tiles waits until there's
        // at least one tile, to wait for setup).
        kosmos.currentTilesInteractor.setTiles(listOf(TileSpec.create("a")))
        kosmos.runCurrent()
    }

    @Test
    fun tileAlreadyAdded_correctResult() =
        kosmos.runTest {
            // An existing tile
            currentTilesInteractor.setTiles(listOf(TILE_SPEC))
            runCurrent()

            val callback = Callback()
            runOnMainThreadAndWaitForIdleSync {
                val dialog =
                    underTest.requestTileAdd(
                        TEST_UID,
                        TEST_COMPONENT,
                        TEST_APP_NAME,
                        TEST_LABEL,
                        mockIcon,
                        callback,
                    )
                assertThat(dialog).isNull()
            }

            assertThat(callback.lastAccepted).isEqualTo(TILE_ALREADY_ADDED)
            assertThat(currentTilesInteractor.currentTilesSpecs.count { it == TILE_SPEC })
                .isEqualTo(1)
        }

    @Test
    fun cancelDialog_dismissResult_tileNotAdded() =
        kosmos.runTest {
            val callback = Callback()
            val dialog = runOnMainThreadAndWaitForIdleSync {
                underTest.requestTileAdd(
                    TEST_UID,
                    TEST_COMPONENT,
                    TEST_APP_NAME,
                    TEST_LABEL,
                    mockIcon,
                    callback,
                )!!
            }

            runOnMainThreadAndWaitForIdleSync { dialog.cancel() }

            assertThat(callback.lastAccepted).isEqualTo(DISMISSED)
            assertThat(currentTilesInteractor.currentTilesSpecs).doesNotContain(TILE_SPEC)
        }

    @Test
    fun cancelAndThenDismissSendsOnlyOnce() =
        kosmos.runTest {
            // After cancelling, the dialog is dismissed. This tests that only one response
            // is sent.
            val callback = Callback()
            val dialog = runOnMainThreadAndWaitForIdleSync {
                underTest.requestTileAdd(
                    TEST_UID,
                    TEST_COMPONENT,
                    TEST_APP_NAME,
                    TEST_LABEL,
                    mockIcon,
                    callback,
                )!!
            }

            runOnMainThreadAndWaitForIdleSync {
                dialog.cancel()
                dialog.dismiss()
            }

            assertThat(callback.lastAccepted).isEqualTo(DISMISSED)
            assertThat(callback.timesCalled).isEqualTo(1)
        }

    @Test
    fun showAllUsers_set() =
        kosmos.runTest {
            val dialog = runOnMainThreadAndWaitForIdleSync {
                underTest.requestTileAdd(
                    TEST_UID,
                    TEST_COMPONENT,
                    TEST_APP_NAME,
                    TEST_LABEL,
                    mockIcon,
                    Callback(),
                )!!
            }
            onTeardown { dialog.cancel() }

            assertThat(dialog.isShowForAllUsers).isTrue()
        }

    @Test
    fun cancelOnTouchOutside_set() =
        kosmos.runTest {
            val dialog = runOnMainThreadAndWaitForIdleSync {
                underTest.requestTileAdd(
                    TEST_UID,
                    TEST_COMPONENT,
                    TEST_APP_NAME,
                    TEST_LABEL,
                    mockIcon,
                    Callback(),
                )!!
            }
            onTeardown { dialog.cancel() }

            assertThat(dialog.isCancelOnTouchOutside).isTrue()
        }

    @Test
    fun positiveAction_tileAdded() =
        kosmos.runTest {
            // Not using a real dialog
            tileRequestDialogComposeDelegateFactory = FakeTileRequestDialogComposeDelegateFactory()

            val callback = Callback()
            val dialog =
                underTest.requestTileAdd(
                    TEST_UID,
                    TEST_COMPONENT,
                    TEST_APP_NAME,
                    TEST_LABEL,
                    mockIcon,
                    callback,
                )

            tileRequestDialogComposeDelegateFactory.fake.clickListener.onClick(
                dialog,
                DialogInterface.BUTTON_POSITIVE,
            )
            runCurrent()

            assertThat(callback.lastAccepted).isEqualTo(ADD_TILE)
            assertThat(currentTilesInteractor.currentTilesSpecs).hasSize(2)
            assertThat(currentTilesInteractor.currentTilesSpecs.last()).isEqualTo(TILE_SPEC)
        }

    @Test
    fun negativeAction_tileNotAdded() =
        kosmos.runTest {
            // Not using a real dialog
            tileRequestDialogComposeDelegateFactory = FakeTileRequestDialogComposeDelegateFactory()

            val callback = Callback()
            val dialog =
                underTest.requestTileAdd(
                    TEST_UID,
                    TEST_COMPONENT,
                    TEST_APP_NAME,
                    TEST_LABEL,
                    mockIcon,
                    callback,
                )

            tileRequestDialogComposeDelegateFactory.fake.clickListener.onClick(
                dialog,
                DialogInterface.BUTTON_NEGATIVE,
            )
            runCurrent()

            assertThat(callback.lastAccepted).isEqualTo(DONT_ADD_TILE)
            assertThat(currentTilesInteractor.currentTilesSpecs).doesNotContain(TILE_SPEC)
        }

    companion object {
        private val TEST_COMPONENT = ComponentName("test_pkg", "test_cls")
        private val TILE_SPEC = TileSpec.create(TEST_COMPONENT)
        private const val TEST_APP_NAME = "App"
        private const val TEST_LABEL = "Label"
        private const val TEST_UID = 12345

        const val ADD_TILE = StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
        const val DONT_ADD_TILE = StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED
        const val TILE_ALREADY_ADDED = StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        const val DISMISSED = StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED
    }

    private class Callback : IAddTileResultCallback.Stub(), Consumer<Int> {
        var lastAccepted: Int? = null
            private set

        var timesCalled = 0
            private set

        override fun accept(t: Int) {
            lastAccepted = t
            timesCalled++
        }

        override fun onTileRequest(r: Int) {
            accept(r)
        }
    }
}

private val Dialog.isShowForAllUsers: Boolean
    get() =
        window!!.attributes.privateFlags and
            WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS != 0

private val Dialog.isCancelOnTouchOutside: Boolean
    get() = window!!.shouldCloseOnTouchOutside()
