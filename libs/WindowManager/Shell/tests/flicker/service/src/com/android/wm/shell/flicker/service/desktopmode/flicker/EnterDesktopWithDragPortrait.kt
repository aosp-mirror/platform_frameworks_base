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

package com.android.wm.shell.flicker.service.desktopmode.flicker

import android.tools.Rotation
import android.tools.flicker.AssertionInvocationGroup
import android.tools.flicker.FlickerConfig
import android.tools.flicker.annotation.ExpectedScenarios
import android.tools.flicker.annotation.FlickerConfigProvider
import android.tools.flicker.assertors.assertions.AppLayerIsVisibleAlways
import android.tools.flicker.assertors.assertions.AppWindowHasDesktopModeInitialBoundsAtTheEnd
import android.tools.flicker.assertors.assertions.AppWindowOnTopAtEnd
import android.tools.flicker.config.AssertionTemplates
import android.tools.flicker.config.FlickerConfig
import android.tools.flicker.config.FlickerConfigEntry
import android.tools.flicker.config.FlickerServiceConfig
import android.tools.flicker.config.ScenarioId
import android.tools.flicker.config.desktopmode.Components
import android.tools.flicker.extractors.ITransitionMatcher
import android.tools.flicker.extractors.ShellTransitionScenarioExtractor
import android.tools.flicker.junit.FlickerServiceJUnit4ClassRunner
import android.tools.traces.wm.Transition
import android.tools.traces.wm.TransitionType
import com.android.wm.shell.flicker.service.desktopmode.scenarios.EnterDesktopWithDrag
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(FlickerServiceJUnit4ClassRunner::class)
class EnterDesktopWithDragPortrait : EnterDesktopWithDrag(Rotation.ROTATION_0) {
    @ExpectedScenarios(["END_DRAG_TO_DESKTOP"]) @Test override fun enterDesktopWithDrag() =
        super.enterDesktopWithDrag()

    companion object {
        private val END_DRAG_TO_DESKTOP = FlickerConfigEntry(
            scenarioId = ScenarioId("END_DRAG_TO_DESKTOP"),
            extractor = ShellTransitionScenarioExtractor(
                transitionMatcher = object : ITransitionMatcher {
                    override fun findAll(
                        transitions: Collection<Transition>
                    ): Collection<Transition> {
                        return transitions.filter {
                            it.type == TransitionType.DESKTOP_MODE_END_DRAG_TO_DESKTOP}
                    }
                }),
            assertions = AssertionTemplates.COMMON_ASSERTIONS +
                    listOf(
                        AppLayerIsVisibleAlways(Components.DESKTOP_MODE_APP),
                        AppWindowOnTopAtEnd(Components.DESKTOP_MODE_APP),
                        AppWindowHasDesktopModeInitialBoundsAtTheEnd(Components.DESKTOP_MODE_APP)
                    ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
        )

        @JvmStatic
        @FlickerConfigProvider
        fun flickerConfigProvider(): FlickerConfig =
            FlickerConfig()
                    .use(FlickerServiceConfig.DEFAULT)
                    .use(END_DRAG_TO_DESKTOP)
    }
}
