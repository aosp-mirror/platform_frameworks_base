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

package com.android.systemui.qs.external.ui.viewmodel

import android.content.applicationContext
import android.content.res.mainResources
import android.graphics.drawable.Icon
import android.graphics.drawable.TestStubDrawable
import android.service.quicksettings.Tile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.iUriGrantsManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.TileData
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

@SmallTest
@RunWith(AndroidJUnit4::class)
class TileRequestDialogViewModelTest : SysuiTestCase() {

    @get:Rule val expect: Expect = Expect.create()

    private val kosmos = testKosmos()

    private val icon: Icon = mock {
        on {
            loadDrawableCheckingUriGrant(
                kosmos.applicationContext,
                kosmos.iUriGrantsManager,
                TEST_UID,
                TEST_PACKAGE,
            )
        } doReturn (loadedDrawable)
    }

    private val tileData = TileData(TEST_UID, TEST_APP_NAME, TEST_LABEL, icon, TEST_PACKAGE)

    private val Kosmos.underTest by
        Kosmos.Fixture { tileRequestDialogViewModelFactory.create(applicationContext, tileData) }

    private val baseResultLegacyState =
        QSTile.State().apply {
            label = TEST_LABEL
            state = Tile.STATE_ACTIVE
            handlesLongClick = false
        }

    @Test
    fun uiState_beforeActivation_hasDefaultIcon_andCorrectData() =
        kosmos.runTest {
            val expectedState =
                baseResultLegacyState.apply { icon = defaultIcon }.toUiState(mainResources)

            with(underTest.uiState) {
                expect.that(label).isEqualTo(TEST_LABEL)
                expect.that(secondaryLabel).isEmpty()
                expect.that(state).isEqualTo(expectedState.state)
                expect.that(handlesLongClick).isFalse()
                expect.that(handlesSecondaryClick).isFalse()
                expect.that(icon.get()).isEqualTo(defaultIcon)
                expect.that(sideDrawable).isNull()
                expect.that(accessibilityUiState).isEqualTo(expectedState.accessibilityUiState)
            }
        }

    @Test
    fun uiState_afterActivation_hasCorrectIcon_andCorrectData() =
        kosmos.runTest {
            val expectedState =
                baseResultLegacyState
                    .apply { icon = QSTileImpl.DrawableIcon(loadedDrawable) }
                    .toUiState(mainResources)

            underTest.activateIn(testScope)
            runCurrent()

            with(underTest.uiState) {
                expect.that(label).isEqualTo(TEST_LABEL)
                expect.that(secondaryLabel).isEmpty()
                expect.that(state).isEqualTo(expectedState.state)
                expect.that(handlesLongClick).isFalse()
                expect.that(handlesSecondaryClick).isFalse()
                expect.that(icon.get()).isEqualTo(QSTileImpl.DrawableIcon(loadedDrawable))
                expect.that(sideDrawable).isNull()
                expect.that(accessibilityUiState).isEqualTo(expectedState.accessibilityUiState)
            }
        }

    @Test
    fun uiState_afterActivation_iconNotLoaded_usesDefault() =
        kosmos.runTest {
            icon.stub {
                on {
                    loadDrawableCheckingUriGrant(
                        kosmos.applicationContext,
                        kosmos.iUriGrantsManager,
                        TEST_UID,
                        TEST_PACKAGE,
                    )
                } doReturn (null)
            }

            underTest.activateIn(testScope)
            runCurrent()

            assertThat(underTest.uiState.icon.get()).isEqualTo(defaultIcon)
        }

    companion object {
        private val defaultIcon: QSTile.Icon = ResourceIcon.get(R.drawable.android)
        private val loadedDrawable = TestStubDrawable("loaded")

        private const val TEST_PACKAGE = "test_pkg"
        private const val TEST_APP_NAME = "App"
        private const val TEST_LABEL = "Label"
        private const val TEST_UID = 12345
    }
}
