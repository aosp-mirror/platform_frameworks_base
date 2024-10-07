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

package com.android.wm.shell.flicker

import android.tools.PlatformConsts.DESKTOP_MODE_MINIMUM_WINDOW_HEIGHT
import android.tools.PlatformConsts.DESKTOP_MODE_MINIMUM_WINDOW_WIDTH
import android.tools.flicker.AssertionInvocationGroup
import android.tools.flicker.assertors.assertions.AppLayerIncreasesInSize
import android.tools.flicker.assertors.assertions.AppLayerIsInvisibleAtEnd
import android.tools.flicker.assertors.assertions.AppLayerIsVisibleAlways
import android.tools.flicker.assertors.assertions.AppLayerIsVisibleAtStart
import android.tools.flicker.assertors.assertions.AppWindowBecomesVisible
import android.tools.flicker.assertors.assertions.AppWindowAlignsWithOnlyOneDisplayCornerAtEnd
import android.tools.flicker.assertors.assertions.AppWindowCoversLeftHalfScreenAtEnd
import android.tools.flicker.assertors.assertions.AppWindowCoversRightHalfScreenAtEnd
import android.tools.flicker.assertors.assertions.AppWindowHasDesktopModeInitialBoundsAtTheEnd
import android.tools.flicker.assertors.assertions.AppWindowHasMaxBoundsInOnlyOneDimension
import android.tools.flicker.assertors.assertions.AppWindowHasMaxDisplayHeight
import android.tools.flicker.assertors.assertions.AppWindowHasMaxDisplayWidth
import android.tools.flicker.assertors.assertions.AppWindowHasSizeOfAtLeast
import android.tools.flicker.assertors.assertions.AppWindowInsideDisplayBoundsAtEnd
import android.tools.flicker.assertors.assertions.AppWindowIsInvisibleAtEnd
import android.tools.flicker.assertors.assertions.AppWindowIsVisibleAlways
import android.tools.flicker.assertors.assertions.AppWindowMaintainsAspectRatioAlways
import android.tools.flicker.assertors.assertions.AppWindowOnTopAtEnd
import android.tools.flicker.assertors.assertions.AppWindowOnTopAtStart
import android.tools.flicker.assertors.assertions.AppWindowRemainInsideDisplayBounds
import android.tools.flicker.assertors.assertions.AppWindowReturnsToStartBoundsAndPosition
import android.tools.flicker.assertors.assertions.LauncherWindowReplacesAppAsTopWindow
import android.tools.flicker.config.AssertionTemplates
import android.tools.flicker.config.FlickerConfigEntry
import android.tools.flicker.config.ScenarioId
import android.tools.flicker.config.desktopmode.Components.DESKTOP_MODE_APP
import android.tools.flicker.config.desktopmode.Components.DESKTOP_WALLPAPER
import android.tools.flicker.config.desktopmode.Components.NON_RESIZABLE_APP
import android.tools.flicker.extractors.ITransitionMatcher
import android.tools.flicker.extractors.ShellTransitionScenarioExtractor
import android.tools.flicker.extractors.TaggedCujTransitionMatcher
import android.tools.flicker.extractors.TaggedScenarioExtractorBuilder
import android.tools.traces.events.CujType
import android.tools.traces.wm.Transition
import android.tools.traces.wm.TransitionType

class DesktopModeFlickerScenarios {
    companion object {
        val END_DRAG_TO_DESKTOP =
            FlickerConfigEntry(
                scenarioId = ScenarioId("END_DRAG_TO_DESKTOP"),
                extractor =
                ShellTransitionScenarioExtractor(
                    transitionMatcher =
                    object : ITransitionMatcher {
                        override fun findAll(
                            transitions: Collection<Transition>
                        ): Collection<Transition> {
                            return transitions.filter {
                                // TODO(351168217) Use jank CUJ to extract a longer trace
                                it.type == TransitionType.DESKTOP_MODE_END_DRAG_TO_DESKTOP
                            }
                        }
                    }
                ),
                assertions =
                AssertionTemplates.COMMON_ASSERTIONS +
                        listOf(
                            AppLayerIsVisibleAlways(DESKTOP_MODE_APP),
                            AppWindowOnTopAtEnd(DESKTOP_MODE_APP),
                            AppWindowHasDesktopModeInitialBoundsAtTheEnd(DESKTOP_MODE_APP),
                            AppWindowBecomesVisible(DESKTOP_WALLPAPER)
                        )
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        // Use this scenario for closing an app in desktop windowing, except the last app. For the
        // last app use CLOSE_LAST_APP scenario
        val CLOSE_APP =
            FlickerConfigEntry(
                scenarioId = ScenarioId("CLOSE_APP"),
                extractor =
                ShellTransitionScenarioExtractor(
                    transitionMatcher =
                    object : ITransitionMatcher {
                        override fun findAll(
                            transitions: Collection<Transition>
                        ): Collection<Transition> {
                            // In case there are multiple windows closing, filter out the
                            // last window closing. It should use the CLOSE_LAST_APP
                            // scenario below.
                            return transitions
                                .filter { it.type == TransitionType.CLOSE }
                                .sortedByDescending { it.id }
                                .drop(1)
                        }
                    }
                ),
                assertions =
                AssertionTemplates.COMMON_ASSERTIONS +
                        listOf(
                            AppWindowOnTopAtStart(DESKTOP_MODE_APP),
                            AppLayerIsVisibleAtStart(DESKTOP_MODE_APP),
                            AppLayerIsInvisibleAtEnd(DESKTOP_MODE_APP)
                        ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val CLOSE_LAST_APP =
            FlickerConfigEntry(
                scenarioId = ScenarioId("CLOSE_LAST_APP"),
                extractor =
                ShellTransitionScenarioExtractor(
                    transitionMatcher =
                    object : ITransitionMatcher {
                        override fun findAll(
                            transitions: Collection<Transition>
                        ): Collection<Transition> {
                            val lastTransition =
                                transitions
                                    .filter { it.type == TransitionType.CLOSE }
                                    .maxByOrNull { it.id }!!
                            return listOf(lastTransition)
                        }
                    }
                ),
                assertions =
                AssertionTemplates.COMMON_ASSERTIONS +
                        listOf(
                            AppWindowIsInvisibleAtEnd(DESKTOP_MODE_APP),
                            LauncherWindowReplacesAppAsTopWindow(DESKTOP_MODE_APP),
                            AppWindowIsInvisibleAtEnd(DESKTOP_WALLPAPER)
                        )
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val CORNER_RESIZE =
            FlickerConfigEntry(
                scenarioId = ScenarioId("CORNER_RESIZE"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS
            )

        val EDGE_RESIZE =
            FlickerConfigEntry(
                scenarioId = ScenarioId("EDGE_RESIZE"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(
                            AppLayerIncreasesInSize(DESKTOP_MODE_APP),
                        ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val CORNER_RESIZE_TO_MINIMUM_SIZE =
            FlickerConfigEntry(
                scenarioId = ScenarioId("CORNER_RESIZE_TO_MINIMUM_SIZE"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions =
                AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(
                            AppWindowHasSizeOfAtLeast(
                                DESKTOP_MODE_APP,
                                DESKTOP_MODE_MINIMUM_WINDOW_WIDTH,
                                DESKTOP_MODE_MINIMUM_WINDOW_HEIGHT
                            )
                        )
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val CORNER_RESIZE_TO_MAXIMUM_SIZE =
            FlickerConfigEntry(
                scenarioId = ScenarioId("CORNER_RESIZE_TO_MAXIMUM_SIZE"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions =
                AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(
                            AppLayerIncreasesInSize(DESKTOP_MODE_APP),
                            AppWindowHasMaxDisplayHeight(DESKTOP_MODE_APP),
                            AppWindowHasMaxDisplayWidth(DESKTOP_MODE_APP)
                        ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val SNAP_RESIZE_LEFT_WITH_BUTTON =
            FlickerConfigEntry(
                scenarioId = ScenarioId("SNAP_RESIZE_LEFT_WITH_BUTTON"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_SNAP_RESIZE)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(AppWindowCoversLeftHalfScreenAtEnd(DESKTOP_MODE_APP))
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val SNAP_RESIZE_RIGHT_WITH_BUTTON =
            FlickerConfigEntry(
                scenarioId = ScenarioId("SNAP_RESIZE_RIGHT_WITH_BUTTON"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_SNAP_RESIZE)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(AppWindowCoversRightHalfScreenAtEnd(DESKTOP_MODE_APP))
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val SNAP_RESIZE_LEFT_WITH_DRAG =
            FlickerConfigEntry(
                scenarioId = ScenarioId("SNAP_RESIZE_LEFT_WITH_DRAG"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_SNAP_RESIZE)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(AppWindowCoversLeftHalfScreenAtEnd(DESKTOP_MODE_APP))
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val SNAP_RESIZE_RIGHT_WITH_DRAG =
            FlickerConfigEntry(
                scenarioId = ScenarioId("SNAP_RESIZE_RIGHT_WITH_DRAG"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_SNAP_RESIZE)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(AppWindowCoversRightHalfScreenAtEnd(DESKTOP_MODE_APP))
                            .associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val SNAP_RESIZE_WITH_DRAG_NON_RESIZABLE =
            FlickerConfigEntry(
                scenarioId = ScenarioId("SNAP_RESIZE_WITH_DRAG_NON_RESIZABLE"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_SNAP_RESIZE)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = listOf(
                    AppWindowIsVisibleAlways(NON_RESIZABLE_APP),
                    AppWindowOnTopAtEnd(NON_RESIZABLE_APP),
                    AppWindowRemainInsideDisplayBounds(NON_RESIZABLE_APP),
                    AppWindowMaintainsAspectRatioAlways(NON_RESIZABLE_APP),
                    AppWindowReturnsToStartBoundsAndPosition(NON_RESIZABLE_APP)
                ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val MAXIMIZE_APP =
            FlickerConfigEntry(
                scenarioId = ScenarioId("MAXIMIZE_APP"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_MAXIMIZE_WINDOW)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions = AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(
                            AppLayerIncreasesInSize(DESKTOP_MODE_APP),
                            AppWindowHasMaxDisplayHeight(DESKTOP_MODE_APP),
                            AppWindowHasMaxDisplayWidth(DESKTOP_MODE_APP)
                        ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val MAXIMIZE_APP_NON_RESIZABLE =
            FlickerConfigEntry(
                scenarioId = ScenarioId("MAXIMIZE_APP_NON_RESIZABLE"),
                extractor =
                TaggedScenarioExtractorBuilder()
                    .setTargetTag(CujType.CUJ_DESKTOP_MODE_MAXIMIZE_WINDOW)
                    .setTransitionMatcher(
                        TaggedCujTransitionMatcher(associatedTransitionRequired = false)
                    )
                    .build(),
                assertions =
                AssertionTemplates.DESKTOP_MODE_APP_VISIBILITY_ASSERTIONS +
                        listOf(
                            AppLayerIncreasesInSize(DESKTOP_MODE_APP),
                            AppWindowMaintainsAspectRatioAlways(DESKTOP_MODE_APP),
                            AppWindowHasMaxBoundsInOnlyOneDimension(DESKTOP_MODE_APP)
                        ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )

        val CASCADE_APP =
            FlickerConfigEntry(
                scenarioId = ScenarioId("CASCADE_APP"),
                extractor =
                ShellTransitionScenarioExtractor(
                    transitionMatcher =
                    object : ITransitionMatcher {
                        override fun findAll(
                            transitions: Collection<Transition>
                        ): Collection<Transition> {
                                return transitions.filter { it.type == TransitionType.OPEN }
                        }
                    }
                ),
                assertions =
                        listOf(
                            AppWindowInsideDisplayBoundsAtEnd(DESKTOP_MODE_APP),
                            AppWindowOnTopAtEnd(DESKTOP_MODE_APP),
                            AppWindowBecomesVisible(DESKTOP_MODE_APP),
                            AppWindowAlignsWithOnlyOneDisplayCornerAtEnd(DESKTOP_MODE_APP)
                        ).associateBy({ it }, { AssertionInvocationGroup.BLOCKING }),
            )
    }
}
