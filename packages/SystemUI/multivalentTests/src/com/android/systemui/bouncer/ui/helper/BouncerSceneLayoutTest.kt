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

package com.android.systemui.bouncer.ui.helper

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout.BELOW_USER_SWITCHER
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout.BESIDE_USER_SWITCHER
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout.SPLIT_BOUNCER
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout.STANDARD_BOUNCER
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameter
import platform.test.runner.parameterized.Parameters
import org.junit.runner.RunWith

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class BouncerSceneLayoutTest : SysuiTestCase() {

    data object Phone :
        Device(
            name = "phone",
            width = SizeClass.COMPACT,
            height = SizeClass.EXPANDED,
            naturallyHeld = Vertically,
        )
    data object Tablet :
        Device(
            name = "tablet",
            width = SizeClass.EXPANDED,
            height = SizeClass.MEDIUM,
            naturallyHeld = Horizontally,
        )
    data object Folded :
        Device(
            name = "folded",
            width = SizeClass.COMPACT,
            height = SizeClass.MEDIUM,
            naturallyHeld = Vertically,
        )
    data object Unfolded :
        Device(
            name = "unfolded",
            width = SizeClass.EXPANDED,
            height = SizeClass.MEDIUM,
            naturallyHeld = Vertically,
            widthWhenUnnaturallyHeld = SizeClass.MEDIUM,
            heightWhenUnnaturallyHeld = SizeClass.MEDIUM,
        )
    data object TallerFolded :
        Device(
            name = "taller folded",
            width = SizeClass.COMPACT,
            height = SizeClass.EXPANDED,
            naturallyHeld = Vertically,
        )
    data object TallerUnfolded :
        Device(
            name = "taller unfolded",
            width = SizeClass.EXPANDED,
            height = SizeClass.EXPANDED,
            naturallyHeld = Vertically,
        )

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun testCases() =
            listOf(
                    Phone to
                        Expected(
                            whenNaturallyHeld = STANDARD_BOUNCER,
                            whenUnnaturallyHeld = SPLIT_BOUNCER,
                        ),
                    Tablet to
                        Expected(
                            whenNaturallyHeld = BESIDE_USER_SWITCHER,
                            whenUnnaturallyHeld = BELOW_USER_SWITCHER,
                        ),
                    Folded to
                        Expected(
                            whenNaturallyHeld = STANDARD_BOUNCER,
                            whenUnnaturallyHeld = SPLIT_BOUNCER,
                        ),
                    Unfolded to
                        Expected(
                            whenNaturallyHeld = BESIDE_USER_SWITCHER,
                            whenUnnaturallyHeld = STANDARD_BOUNCER,
                        ),
                    TallerFolded to
                        Expected(
                            whenNaturallyHeld = STANDARD_BOUNCER,
                            whenUnnaturallyHeld = SPLIT_BOUNCER,
                        ),
                    TallerUnfolded to
                        Expected(
                            whenNaturallyHeld = BESIDE_USER_SWITCHER,
                            whenUnnaturallyHeld = BESIDE_USER_SWITCHER,
                        ),
                )
                .flatMap { (device, expected) ->
                    buildList {
                        // Holding the device in its natural orientation (vertical or horizontal):
                        add(
                            TestCase(
                                device = device,
                                held = device.naturallyHeld,
                                expected = expected.layout(heldNaturally = true),
                            )
                        )

                        if (expected.whenNaturallyHeld == BESIDE_USER_SWITCHER) {
                            add(
                                TestCase(
                                    device = device,
                                    held = device.naturallyHeld,
                                    isSideBySideSupported = false,
                                    expected = STANDARD_BOUNCER,
                                )
                            )
                        }

                        // Holding the device the other way:
                        add(
                            TestCase(
                                device = device,
                                held = device.naturallyHeld.flip(),
                                expected = expected.layout(heldNaturally = false),
                            )
                        )

                        if (expected.whenUnnaturallyHeld == BESIDE_USER_SWITCHER) {
                            add(
                                TestCase(
                                    device = device,
                                    held = device.naturallyHeld.flip(),
                                    isSideBySideSupported = false,
                                    expected = STANDARD_BOUNCER,
                                )
                            )
                        }
                    }
                }
    }

    @Parameter @JvmField var testCase: TestCase? = null

    @Test
    fun calculateLayout() {
        testCase?.let { nonNullTestCase ->
            with(nonNullTestCase) {
                assertThat(
                        calculateLayoutInternal(
                            width = device.width(whenHeld = held),
                            height = device.height(whenHeld = held),
                            isSideBySideSupported = isSideBySideSupported,
                        )
                    )
                    .isEqualTo(expected)
            }
        }
    }

    data class TestCase(
        val device: Device,
        val held: Held,
        val expected: BouncerSceneLayout,
        val isSideBySideSupported: Boolean = true,
    ) {
        override fun toString(): String {
            return buildString {
                append(device.name)
                append(" width: ${device.width(held).name.lowercase(Locale.US)}")
                append(" height: ${device.height(held).name.lowercase(Locale.US)}")
                append(" when held $held")
                if (!isSideBySideSupported) {
                    append(" (side-by-side not supported)")
                }
            }
        }
    }

    data class Expected(
        val whenNaturallyHeld: BouncerSceneLayout,
        val whenUnnaturallyHeld: BouncerSceneLayout,
    ) {
        fun layout(heldNaturally: Boolean): BouncerSceneLayout {
            return if (heldNaturally) {
                whenNaturallyHeld
            } else {
                whenUnnaturallyHeld
            }
        }
    }

    sealed class Device(
        val name: String,
        private val width: SizeClass,
        private val height: SizeClass,
        val naturallyHeld: Held,
        private val widthWhenUnnaturallyHeld: SizeClass = height,
        private val heightWhenUnnaturallyHeld: SizeClass = width,
    ) {
        fun width(whenHeld: Held): SizeClass {
            return if (isHeldNaturally(whenHeld)) {
                width
            } else {
                widthWhenUnnaturallyHeld
            }
        }

        fun height(whenHeld: Held): SizeClass {
            return if (isHeldNaturally(whenHeld)) {
                height
            } else {
                heightWhenUnnaturallyHeld
            }
        }

        private fun isHeldNaturally(whenHeld: Held): Boolean {
            return whenHeld == naturallyHeld
        }
    }

    sealed class Held {
        abstract fun flip(): Held
    }
    data object Vertically : Held() {
        override fun flip(): Held {
            return Horizontally
        }
    }
    data object Horizontally : Held() {
        override fun flip(): Held {
            return Vertically
        }
    }
}
