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
import android.tools.common.Rotation
import android.platform.test.annotations.Postsubmit
import android.tools.common.Timestamp
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.flicker.subject.exceptions.ExceptionMessageBuilder
import android.tools.common.flicker.subject.exceptions.InvalidPropertyException
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
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
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class ShowImeOnAppStartWhenLaunchingAppFromFixedOrientationTest(flicker: FlickerTest) :
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

    @Postsubmit
    @Test
    fun imeLayerAlphaOneAfterSnapshotStartingWindowRemoval() {
        // Check if the snapshot appeared during the trace
        var imeSnapshotRemovedTimestamp: Timestamp? = null

        val layerTrace = flicker.reader.readLayersTrace()
        val layerTraceEntries = layerTrace?.entries?.toList() ?: emptyList()

        layerTraceEntries.zipWithNext { prev, next ->
            val prevSnapshotLayerVisible =
                    ComponentNameMatcher.SNAPSHOT.layerMatchesAnyOf(prev.visibleLayers)
            val nextSnapshotLayerVisible =
                    ComponentNameMatcher.SNAPSHOT.layerMatchesAnyOf(next.visibleLayers)

            if (imeSnapshotRemovedTimestamp == null &&
                    (prevSnapshotLayerVisible && !nextSnapshotLayerVisible)) {
                imeSnapshotRemovedTimestamp = next.timestamp
            }
        }

        // if so, make an assertion
        imeSnapshotRemovedTimestamp?.let { timestamp ->
            val stateAfterSnapshot = layerTrace?.getEntryAt(timestamp)
                    ?: error("State not found for $timestamp")

            val imeLayers = ComponentNameMatcher.IME
                    .filterLayers(stateAfterSnapshot.visibleLayers.toList())

            require(imeLayers.isNotEmpty()) { "IME layer not found" }
            if (imeLayers.any { it.color.a != 1.0f }) {
                val errorMsgBuilder = ExceptionMessageBuilder()
                        .setTimestamp(timestamp)
                        .forInvalidProperty("IME layer alpha")
                        .setExpected("is 1.0")
                        .setActual("not 1.0")
                        .addExtraDescription("Filter",
                                ComponentNameMatcher.IME.toLayerIdentifier())
                throw InvalidPropertyException(errorMsgBuilder)
            }
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_90)
            )
        }
    }
}
