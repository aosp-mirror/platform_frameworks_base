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

package com.android.systemui.settings.brightness.ui.viewmodel

import android.content.res.mainResources
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.domain.interactor.brightnessMirrorShowingInteractor
import com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel
import com.android.systemui.settings.brightness.ui.viewModel.LocationAndSize
import com.android.systemui.settings.brightnessSliderControllerFactory
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessMirrorViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest =
        with(kosmos) {
            BrightnessMirrorViewModel(
                brightnessMirrorShowingInteractor,
                mainResources,
                brightnessSliderControllerFactory,
            )
        }

    @Test
    fun showHideMirror_isShowing() =
        with(kosmos) {
            testScope.runTest {
                val showing by collectLastValue(underTest.isShowing)

                assertThat(showing).isFalse()

                underTest.showMirror()
                assertThat(showing).isTrue()

                underTest.hideMirror()
                assertThat(showing).isFalse()
            }
        }

    @Test
    fun locationInWindowAndContainer_correctLocationAndSize() =
        with(kosmos) {
            testScope.runTest {
                val locationAndSize by collectLastValue(underTest.locationAndSize)

                val x = 20
                val y = 100
                val height = 50
                val width = 200
                val padding =
                    mainResources.getDimensionPixelSize(R.dimen.rounded_slider_background_padding)

                val mockView =
                    mock<View> {
                        whenever(getLocationInWindow(any())).then {
                            it.getArgument<IntArray>(0).apply {
                                this[0] = x
                                this[1] = y
                            }
                            Unit
                        }

                        whenever(measuredHeight).thenReturn(height)
                        whenever(measuredWidth).thenReturn(width)
                    }
                val yOffsetFromContainer = setContainerViewHierarchy(mockView)

                underTest.setLocationAndSize(mockView)

                assertThat(locationAndSize)
                    .isEqualTo(
                        // Adjust for padding around the view
                        LocationAndSize(
                            yOffsetFromWindow = y - padding,
                            yOffsetFromContainer = yOffsetFromContainer - padding,
                            width = width + 2 * padding,
                            height = height + 2 * padding,
                        )
                    )
            }
        }

    private fun setContainerViewHierarchy(mockView: View): Int {
        val rootView = FrameLayout(context)
        val containerView = FrameLayout(context).apply { id = R.id.quick_settings_container }
        val otherView = FrameLayout(context)

        rootView.addView(containerView)
        containerView.addView(otherView)
        otherView.addView(mockView)

        containerView.setLeftTopRightBottom(1, /* top= */ 1, 1, 1)
        otherView.setLeftTopRightBottom(0, /* top= */ 2, 0, 0)
        whenever(mockView.parent).thenReturn(otherView)
        whenever(mockView.top).thenReturn(3)

        return 2 + 3
    }
}
