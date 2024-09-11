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

package com.android.egg.landroid

import android.service.dreams.DreamService
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.layout.FoldingFeature
import kotlin.random.Random

class DreamUniverse : DreamService() {
    private var foldState = mutableStateOf<FoldingFeature?>(null) // unused

    private val lifecycleOwner =
        object : SavedStateRegistryOwner {
            override val lifecycle = LifecycleRegistry(this)
            override val savedStateRegistry
                get() = savedStateRegistryController.savedStateRegistry

            private val savedStateRegistryController =
                SavedStateRegistryController.create(this).apply { performAttach() }

            fun onCreate() {
                savedStateRegistryController.performRestore(null)
                lifecycle.currentState = Lifecycle.State.CREATED
            }

            fun onStart() {
                lifecycle.currentState = Lifecycle.State.STARTED
            }

            fun onStop() {
                lifecycle.currentState = Lifecycle.State.CREATED
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        val universe = VisibleUniverse(namer = Namer(resources), randomSeed = randomSeed())

        isInteractive = false

        if (TEST_UNIVERSE) {
            universe.initTest()
        } else {
            universe.initRandom()

            // We actually don't want the deterministic random position of the ship, we want
            // true randomness to keep things interesting. So use Random (not universe.rng).
            universe.ship.pos =
                universe.star.pos +
                    Vec2.makeWithAngleMag(
                        Random.nextFloat() * PI2f,
                        Random.nextFloatInRange(
                            PLANET_ORBIT_RANGE.start,
                            PLANET_ORBIT_RANGE.endInclusive
                        )
                    )
        }

        // enable autopilot in screensaver mode
        val autopilot = Autopilot(universe.ship, universe)
        universe.ship.autopilot = autopilot
        universe.add(autopilot)
        autopilot.enabled = true

        // much more visually interesting in a screensaver context
        DYNAMIC_ZOOM = true

        val composeView = ComposeView(this)
        composeView.setContent {
            Spaaaace(modifier = Modifier.fillMaxSize(), u = universe, foldState = foldState)
            DebugText(DEBUG_TEXT)
            Telemetry(universe)
        }

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        setContentView(composeView)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner.onCreate()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleOwner.onStart()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        lifecycleOwner.onStop()
    }
}
