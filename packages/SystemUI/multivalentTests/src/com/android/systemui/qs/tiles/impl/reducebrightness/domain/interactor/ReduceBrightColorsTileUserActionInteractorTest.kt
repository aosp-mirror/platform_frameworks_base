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

package com.android.systemui.qs.tiles.impl.reducebrightness.domain.interactor

import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.server.display.feature.flags.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.extradim.ExtraDimDialogManager
import com.android.systemui.accessibility.reduceBrightColorsController
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class ReduceBrightColorsTileUserActionInteractorTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val controller = kosmos.reduceBrightColorsController

    @Mock private lateinit var mExtraDimDialogManager: ExtraDimDialogManager

    @get:Rule val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    private lateinit var underTest: ReduceBrightColorsTileUserActionInteractor
    private lateinit var underTestEvenDimmerEnabled: ReduceBrightColorsTileUserActionInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            ReduceBrightColorsTileUserActionInteractor(
                context.resources,
                inputHandler,
                controller,
                mExtraDimDialogManager,
            )

        underTestEvenDimmerEnabled =
            ReduceBrightColorsTileUserActionInteractor(
                context.orCreateTestableResources
                    .apply { addOverride(R.bool.config_evenDimmerEnabled, true) }
                    .resources,
                inputHandler,
                controller,
                mExtraDimDialogManager,
            )
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_EVEN_DIMMER)
    fun handleClickWhenEnabled() = runTest {
        val wasEnabled = true
        controller.isReduceBrightColorsActivated = wasEnabled

        underTest.handleInput(QSTileInputTestKtx.click(ReduceBrightColorsTileModel(wasEnabled)))

        assertThat(controller.isReduceBrightColorsActivated).isEqualTo(!wasEnabled)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_EVEN_DIMMER)
    fun handleClickWhenDisabled() = runTest {
        val wasEnabled = false
        controller.isReduceBrightColorsActivated = wasEnabled

        underTest.handleInput(QSTileInputTestKtx.click(ReduceBrightColorsTileModel(wasEnabled)))

        assertThat(controller.isReduceBrightColorsActivated).isEqualTo(!wasEnabled)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_EVEN_DIMMER)
    fun handleLongClickWhenDisabled() = runTest {
        val enabled = false

        underTest.handleInput(QSTileInputTestKtx.longClick(ReduceBrightColorsTileModel(enabled)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_REDUCE_BRIGHT_COLORS_SETTINGS)
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_EVEN_DIMMER)
    fun handleLongClickWhenEnabled() = runTest {
        val enabled = true

        underTest.handleInput(QSTileInputTestKtx.longClick(ReduceBrightColorsTileModel(enabled)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_REDUCE_BRIGHT_COLORS_SETTINGS)
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun handleClickWhenEnabledEvenDimmer() = runTest {
        val wasEnabled = true
        controller.isReduceBrightColorsActivated = wasEnabled

        underTestEvenDimmerEnabled.handleInput(
            QSTileInputTestKtx.click(ReduceBrightColorsTileModel(wasEnabled))
        )

        assertThat(controller.isReduceBrightColorsActivated).isEqualTo(wasEnabled)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun handleClickWhenDisabledEvenDimmer() = runTest {
        val wasEnabled = false
        controller.isReduceBrightColorsActivated = wasEnabled

        underTestEvenDimmerEnabled.handleInput(
            QSTileInputTestKtx.click(ReduceBrightColorsTileModel(wasEnabled))
        )

        assertThat(controller.isReduceBrightColorsActivated).isEqualTo(wasEnabled)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun handleLongClickWhenDisabledEvenDimmer() = runTest {
        val enabled = false

        underTestEvenDimmerEnabled.handleInput(
            QSTileInputTestKtx.longClick(ReduceBrightColorsTileModel(enabled))
        )

        verify(mExtraDimDialogManager).dismissKeyguardIfNeededAndShowDialog(anyOrNull())
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun handleLongClickWhenEnabledEvenDimmer() = runTest {
        val enabled = true

        underTestEvenDimmerEnabled.handleInput(
            QSTileInputTestKtx.longClick(ReduceBrightColorsTileModel(enabled))
        )
        verify(mExtraDimDialogManager).dismissKeyguardIfNeededAndShowDialog(anyOrNull())
    }
}
