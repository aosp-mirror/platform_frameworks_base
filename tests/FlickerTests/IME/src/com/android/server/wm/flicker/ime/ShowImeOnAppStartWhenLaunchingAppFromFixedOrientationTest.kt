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
import android.platform.test.annotations.Presubmit
import android.tools.common.Rotation
import android.tools.common.flicker.subject.layers.LayerTraceEntrySubject
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.snapshotStartingWindowLayerCoversExactlyOnApp
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will become visible when switching from the fixed orientation activity
 * (e.g. Launcher activity). To run this test: `atest
 * FlickerTests:ShowImeOnAppStartWhenLaunchingAppFromFixedOrientationTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ShowImeOnAppStartWhenLaunchingAppFromFixedOrientationTest(flicker: LegacyFlickerTest) :
    BaseTest(flicker) {
    private val imeTestApp =
        ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)

            // Launch the activity with expecting IME will be shown.
            imeTestApp.launchViaIntent(wmHelper)

            // Swiping out the IME activity to home.
            tapl.goHome()
            wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
        }
        transitions {
            // Bring the existing IME activity to the front in landscape mode device rotation.
            setRotation(Rotation.ROTATION_90)
            imeTestApp.launchViaIntent(wmHelper)
        }
        teardown { imeTestApp.exit(wmHelper) }
    }

    @Presubmit @Test fun imeWindowBecomesVisible() = flicker.imeWindowBecomesVisible()

    @Presubmit @Test fun imeLayerBecomesVisible() = flicker.imeLayerBecomesVisible()

    @Presubmit
    @Test
    fun snapshotStartingWindowLayerCoversExactlyOnApp() {
        flicker.snapshotStartingWindowLayerCoversExactlyOnApp(imeTestApp)
    }

    @FlakyTest(bugId = 290767483)
    @Postsubmit
    @Test
    fun imeLayerAlphaOneAfterSnapshotStartingWindowRemoval() {
        val layerTrace = flicker.reader.readLayersTrace() ?: error("Unable to read layers trace")

        // Find the entries immediately after the IME snapshot has disappeared
        val imeSnapshotRemovedEntries =
            layerTrace.entries
                .asSequence()
                .zipWithNext { prev, next ->
                    if (
                        ComponentNameMatcher.SNAPSHOT.layerMatchesAnyOf(prev.visibleLayers) &&
                            !ComponentNameMatcher.SNAPSHOT.layerMatchesAnyOf(next.visibleLayers)
                    ) {
                        next
                    } else {
                        null
                    }
                }
                .filterNotNull()

        // If we find it, make sure the IME is visible and fully animated in.
        imeSnapshotRemovedEntries.forEach { entry ->
            val entrySubject = LayerTraceEntrySubject(entry)
            val imeLayerSubjects =
                entrySubject.subjects.filter {
                    ComponentNameMatcher.IME.layerMatchesAnyOf(it.layer) && it.isVisible
                }

            entrySubject
                .check { "InputMethod must exist and be visible" }
                .that(imeLayerSubjects.isNotEmpty())
                .isEqual(true)

            imeLayerSubjects.forEach { imeLayerSubject ->
                imeLayerSubject.check { "alpha" }.that(imeLayerSubject.layer.color.a).isEqual(1.0f)
            }
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_90)
            )
    }
}
