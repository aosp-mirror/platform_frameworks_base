/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm.flicker

import android.platform.helpers.IAppHelper
import android.util.Log
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.helpers.AutomationUtils
import com.google.common.truth.Truth
import org.junit.After
import org.junit.AfterClass
import org.junit.Before

/**
 * Base class of all Flicker test that performs common functions for all flicker tests:
 *
 *
 * - Caches transitions so that a transition is run once and the transition results are used by
 * tests multiple times. This is needed for parameterized tests which call the BeforeClass methods
 * multiple times.
 * - Keeps track of all test artifacts and deletes ones which do not need to be reviewed.
 * - Fails tests if results are not available for any test due to jank.
 */
abstract class FlickerTestBase {
    lateinit var testApp: IAppHelper
    open val instrumentation by lazy {
        InstrumentationRegistry.getInstrumentation()
    }
    val uiDevice by lazy {
        UiDevice.getInstance(instrumentation)
    }
    lateinit var tesults: List<TransitionResult>
    private var lastResult: TransitionResult? = null

    /**
     * Runs a transition, returns a cached result if the transition has run before.
     */
    fun run(transition: TransitionRunner) {
        if (transitionResults.containsKey(transition.testTag)) {
            tesults = transitionResults[transition.testTag]
                    ?: throw IllegalStateException("Results do not contain test tag " +
                            transition.testTag)
            return
        }
        tesults = transition.run().results
        /* Fail if we don't have any results due to jank */
        Truth.assertWithMessage("No results to test because all transition runs were invalid " +
                "because of Jank").that(tesults).isNotEmpty()
        transitionResults[transition.testTag] = tesults
    }

    /**
     * Runs a transition, returns a cached result if the transition has run before.
     */
    @Before
    fun runTransition() {
        run(transitionToRun)
    }

    /**
     * Gets the transition that will be executed
     */
    abstract val transitionToRun: TransitionRunner

    /**
     * Goes through a list of transition results and checks assertions on each result.
     */
    fun checkResults(assertion: (TransitionResult) -> Unit) {
        for (result in tesults) {
            lastResult = result
            assertion(result)
        }
        lastResult = null
    }

    /**
     * Kludge to mark a file for saving. If `checkResults` fails, the last result is not
     * cleared. This indicates the assertion failed for the result, so mark it for saving.
     */
    @After
    fun markArtifactsForSaving() {
        lastResult?.flagForSaving()
    }

    companion object {
        const val TAG = "FLICKER"
        const val NAVIGATION_BAR_WINDOW_TITLE = "NavigationBar"
        const val STATUS_BAR_WINDOW_TITLE = "StatusBar"
        const val DOCKED_STACK_DIVIDER = "DockedStackDivider"
        private val transitionResults = mutableMapOf<String, List<TransitionResult>>()

        /**
         * Teardown any system settings and clean up test artifacts from the file system.
         *
         * Note: test artifacts for failed tests will remain on the device.
         */
        @AfterClass
        @JvmStatic
        fun teardown() {
            AutomationUtils.setDefaultWait()
            transitionResults.values
                .flatten()
                .forEach {
                    if (it.canDelete()) {
                        it.delete()
                    } else {
                        if (it.layersTraceExists()) {
                            Log.e(TAG, "Layers trace saved to ${it.layersTracePath}")
                        }
                        if (it.windowManagerTraceExists()) {
                            Log.e(TAG,
                                    "WindowManager trace saved to ${it.windowManagerTracePath}")
                        }
                        if (it.screenCaptureVideoExists()) {
                            Log.e(TAG,
                                    "Screen capture video saved to ${it.screenCaptureVideoPath()}")
                        }
                    }
                }
        }
    }
}
