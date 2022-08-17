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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.Postsubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeEditorPopupDialogAppHelper
import com.android.server.wm.flicker.traces.region.RegionSubject
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class CloseImeEditorPopupDialogTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val imeTestApp = ImeEditorPopupDialogAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                tapl.setExpectedRotationCheckEnabled(false)
            }
            eachRun {
                imeTestApp.launchViaIntent(wmHelper)
                imeTestApp.openIME(wmHelper)
            }
        }
        transitions {
            imeTestApp.dismissDialog(wmHelper)
            wmHelper.StateSyncBuilder()
                .withImeGone()
                .waitForAndVerify()
        }
        teardown {
            eachRun {
                tapl.goHome()
                wmHelper.StateSyncBuilder()
                    .withHomeActivityVisible()
                    .waitForAndVerify()
                imeTestApp.exit(wmHelper)
            }
        }
    }

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Postsubmit
    @Test
    fun imeWindowBecameInvisible() = testSpec.imeWindowBecomesInvisible()

    @Postsubmit
    @Test
    fun imeLayerAndImeSnapshotVisibleOnScreen() {
        testSpec.assertLayers {
            this.isVisible(ComponentNameMatcher.IME)
                .then()
                .isVisible(ComponentNameMatcher.IME_SNAPSHOT)
                .then()
                .isInvisible(ComponentNameMatcher.IME_SNAPSHOT, isOptional = true)
                .isInvisible(ComponentNameMatcher.IME)
        }
    }

    @Postsubmit
    @Test
    fun imeSnapshotAssociatedOnAppVisibleRegion() {
        testSpec.assertLayers {
            this.invoke("imeSnapshotAssociatedOnAppVisibleRegion") {
                val imeSnapshotLayers = it.subjects.filter { subject ->
                    subject.name.contains(
                        ComponentNameMatcher.IME_SNAPSHOT.toLayerName()
                    ) && subject.isVisible
                }
                if (imeSnapshotLayers.isNotEmpty()) {
                    val visibleAreas = imeSnapshotLayers.mapNotNull { imeSnapshotLayer ->
                        imeSnapshotLayer.layer?.visibleRegion
                    }.toTypedArray()
                    val imeVisibleRegion = RegionSubject.assertThat(visibleAreas, this, timestamp)
                    val appVisibleRegion = it.visibleRegion(imeTestApp)
                    if (imeVisibleRegion.region.isNotEmpty) {
                        imeVisibleRegion.coversAtMost(appVisibleRegion.region)
                    }
                }
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                    repetitions = 2,
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    ),
                    supportedRotations = listOf(Surface.ROTATION_0)
                )
        }
    }
}
