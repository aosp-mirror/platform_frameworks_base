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

import android.platform.test.annotations.Presubmit
import android.tools.Rotation
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.flicker.subject.region.RegionSubject
import android.tools.traces.component.ComponentNameMatcher
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeEditorPopupDialogAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * To run this test: `atest FlickerTestsIme:CloseImeOnDismissPopupDialogTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloseImeOnDismissPopupDialogTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val imeTestApp = ImeEditorPopupDialogAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            imeTestApp.launchViaIntent(wmHelper)
            imeTestApp.openIME(wmHelper)
        }
        transitions {
            imeTestApp.dismissDialog(wmHelper)
            wmHelper.StateSyncBuilder().withImeGone().waitForAndVerify()
        }
        teardown {
            tapl.goHome()
            wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
            imeTestApp.exit(wmHelper)
        }
    }

    @Presubmit @Test fun imeWindowBecameInvisible() = flicker.imeWindowBecomesInvisible()

    @Presubmit
    @Test
    fun imeLayerAndImeSnapshotVisibleOnScreen() {
        flicker.assertLayers {
            this.isVisible(ComponentNameMatcher.IME)
                .then()
                .isVisible(ComponentNameMatcher.IME_SNAPSHOT, isOptional = true)
                .then()
                .isInvisible(ComponentNameMatcher.IME_SNAPSHOT, isOptional = true)
                .isInvisible(ComponentNameMatcher.IME)
        }
    }

    @Presubmit
    @Test
    fun imeSnapshotAssociatedOnAppVisibleRegion() {
        flicker.assertLayers {
            this.invoke("imeSnapshotAssociatedOnAppVisibleRegion") {
                val imeSnapshotLayers =
                    it.subjects.filter { subject ->
                        subject.name.contains(ComponentNameMatcher.IME_SNAPSHOT.toLayerName()) &&
                            subject.isVisible
                    }
                if (imeSnapshotLayers.isNotEmpty()) {
                    val visibleAreas =
                        imeSnapshotLayers.mapNotNull { imeSnapshotLayer ->
                            imeSnapshotLayer.layer.visibleRegion
                        }
                    val imeVisibleRegion = RegionSubject(visibleAreas, timestamp)
                    val appVisibleRegion = it.visibleRegion(imeTestApp)
                    if (!imeVisibleRegion.region.isEmpty) {
                        imeVisibleRegion.coversAtMost(appVisibleRegion.region)
                    }
                }
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
