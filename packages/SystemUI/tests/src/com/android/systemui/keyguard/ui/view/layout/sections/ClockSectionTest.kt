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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.pm.PackageManager
import android.content.res.Resources
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.customization.R as customR
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardSmartspaceInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.ui.viewmodel.keyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.keyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.keyguardSmartspaceViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.policy.fakeConfigurationController
import com.android.systemui.statusbar.ui.fakeSystemBarUtilsProxy
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class ClockSectionTest : SysuiTestCase() {
    private lateinit var underTest: ClockSection

    private val LARGE_CLOCK_TOP_WITHOUT_SMARTSPACE: Int
        get() =
            kosmos.fakeSystemBarUtilsProxy.getStatusBarHeight() +
                context.resources.getDimensionPixelSize(customR.dimen.small_clock_padding_top) +
                context.resources.getDimensionPixelSize(R.dimen.keyguard_smartspace_top_offset)

    private val LARGE_CLOCK_TOP
        get() =
            LARGE_CLOCK_TOP_WITHOUT_SMARTSPACE +
                SMART_SPACE_DATE_WEATHER_HEIGHT +
                ENHANCED_SMART_SPACE_HEIGHT

    private val CLOCK_FADE_TRANSLATION_Y
        get() = context.resources.getDimensionPixelSize(customR.dimen.small_clock_height)

    private var DIMENSION_BY_IDENTIFIER: List<Pair<String, Int>> = listOf()
    private lateinit var kosmos: Kosmos

    @Before
    fun setup() {
        DIMENSION_BY_IDENTIFIER =
            listOf(
                "date_weather_view_height" to SMART_SPACE_DATE_WEATHER_HEIGHT,
                "enhanced_smartspace_height" to ENHANCED_SMART_SPACE_HEIGHT,
            )

        MockitoAnnotations.initMocks(this)
        val remoteResources =
            mock<Resources>().apply {
                whenever(getIdentifier(anyString(), eq("dimen"), anyString())).then { invocation ->
                    val name = invocation.arguments[0] as String
                    val index = DIMENSION_BY_IDENTIFIER.indexOfFirst { (key, _) -> key == name }
                    // increment index so that the not-found sentinel value lines up w/ what is
                    // returned by getIdentifier when a resource is not found
                    index + 1
                }
                whenever(getDimensionPixelSize(anyInt())).then { invocation ->
                    val id = invocation.arguments[0] as Int
                    DIMENSION_BY_IDENTIFIER[id - 1].second
                }
            }
        mContext.setMockPackageManager(
            mock<PackageManager>().apply {
                whenever(getResourcesForApplication(anyString())).thenReturn(remoteResources)
            }
        )

        kosmos = testKosmos()
        with(kosmos) {
            underTest =
                ClockSection(
                    keyguardClockInteractor,
                    keyguardClockViewModel,
                    context,
                    keyguardSmartspaceViewModel,
                    { keyguardBlueprintInteractor },
                    keyguardRootViewModel,
                )
        }
    }

    @Test
    fun testApplyDefaultConstraints_LargeClock_SplitShade() =
        kosmos.testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Split)
                keyguardClockInteractor.setClockSize(ClockSize.LARGE)
                advanceUntilIdle()
            }

            val cs = ConstraintSet()
            underTest.applyDefaultConstraints(cs)

            assertLargeClockTop(cs, LARGE_CLOCK_TOP)
            assertSmallClockTop(cs)
        }

    @Test
    fun testApplyDefaultConstraints_LargeClock_NonSplitShade() =
        kosmos.testScope.runTest {
            with(kosmos) {
                val collectedShadeMode by collectLastValue(shadeRepository.shadeMode)
                val isLargeClockVisible by
                    collectLastValue(keyguardClockViewModel.isLargeClockVisible)

                shadeRepository.setShadeMode(ShadeMode.Single)
                keyguardClockInteractor.setClockSize(ClockSize.LARGE)
                fakeKeyguardRepository.setClockShouldBeCentered(true)
                notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(VISIBLE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()

                val cs = ConstraintSet()
                underTest.applyDefaultConstraints(cs)

                assertLargeClockTop(cs, LARGE_CLOCK_TOP)
                assertSmallClockTop(cs)
            }
        }

    @Test
    fun testApplyDefaultConstraints_LargeClock_MissingSmartspace_SplitShade() =
        kosmos.testScope.runTest {
            with(kosmos) {
                DIMENSION_BY_IDENTIFIER = listOf() // Remove Smartspace from mock
                val collectedShadeMode by collectLastValue(shadeRepository.shadeMode)
                val isLargeClockVisible by
                    collectLastValue(keyguardClockViewModel.isLargeClockVisible)

                shadeRepository.setShadeMode(ShadeMode.Split)
                keyguardClockInteractor.setClockSize(ClockSize.LARGE)
                fakeKeyguardRepository.setClockShouldBeCentered(true)
                notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(VISIBLE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()

                val cs = ConstraintSet()
                underTest.applyDefaultConstraints(cs)

                assertLargeClockTop(cs, LARGE_CLOCK_TOP_WITHOUT_SMARTSPACE)
                assertSmallClockTop(cs)
            }
        }

    @Test
    fun testApplyDefaultConstraints_LargeClock_MissingSmartspace_NonSplitShade() =
        kosmos.testScope.runTest {
            with(kosmos) {
                DIMENSION_BY_IDENTIFIER = listOf() // Remove Smartspace from mock
                val collectedShadeMode by collectLastValue(shadeRepository.shadeMode)
                val isLargeClockVisible by
                    collectLastValue(keyguardClockViewModel.isLargeClockVisible)

                shadeRepository.setShadeMode(ShadeMode.Single)
                keyguardClockInteractor.setClockSize(ClockSize.LARGE)
                fakeKeyguardRepository.setClockShouldBeCentered(true)
                notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(VISIBLE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()

                val cs = ConstraintSet()
                underTest.applyDefaultConstraints(cs)

                assertLargeClockTop(cs, LARGE_CLOCK_TOP_WITHOUT_SMARTSPACE)
                assertSmallClockTop(cs)
            }
        }

    @Test
    fun testApplyDefaultConstraints_SmallClock_SplitShade() =
        kosmos.testScope.runTest {
            with(kosmos) {
                val collectedShadeMode by collectLastValue(shadeRepository.shadeMode)
                val isLargeClockVisible by
                    collectLastValue(keyguardClockViewModel.isLargeClockVisible)

                shadeRepository.setShadeMode(ShadeMode.Split)
                keyguardClockInteractor.setClockSize(ClockSize.SMALL)
                fakeKeyguardRepository.setClockShouldBeCentered(true)
                notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(VISIBLE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()

                val cs = ConstraintSet()
                underTest.applyDefaultConstraints(cs)

                assertLargeClockTop(cs, LARGE_CLOCK_TOP)
                assertSmallClockTop(cs)
            }
        }

    @Test
    fun testApplyDefaultConstraints_SmallClock_NonSplitShade() =
        kosmos.testScope.runTest {
            with(kosmos) {
                val collectedShadeMode by collectLastValue(shadeRepository.shadeMode)
                val isLargeClockVisible by
                    collectLastValue(keyguardClockViewModel.isLargeClockVisible)

                shadeRepository.setShadeMode(ShadeMode.Single)
                keyguardClockInteractor.setClockSize(ClockSize.SMALL)
                fakeKeyguardRepository.setClockShouldBeCentered(true)
                notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(VISIBLE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()

                val cs = ConstraintSet()
                underTest.applyDefaultConstraints(cs)
                assertLargeClockTop(cs, LARGE_CLOCK_TOP)
                assertSmallClockTop(cs)
            }
        }

    @Test
    fun testSmartspaceVisible_weatherClockDateAndIconsBarrierBottomBelowBCSmartspace() =
        kosmos.testScope.runTest {
            with(kosmos) {
                notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(VISIBLE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()
            }

            val cs = ConstraintSet()
            underTest.applyDefaultConstraints(cs)
            val referencedIds =
                cs.getReferencedIds(R.id.weather_clock_date_and_icons_barrier_bottom)
            referencedIds.contentEquals(
                intArrayOf(com.android.systemui.shared.R.id.bc_smartspace_view)
            )
        }

    @Test
    fun testSmartspaceGone_weatherClockDateAndIconsBarrierBottomBelowSmartspaceDateWeather() =
        kosmos.testScope.runTest {
            with(kosmos) {
                notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(GONE)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()
            }

            val cs = ConstraintSet()
            underTest.applyDefaultConstraints(cs)
            val referencedIds =
                cs.getReferencedIds(R.id.weather_clock_date_and_icons_barrier_bottom)
            referencedIds.contentEquals(intArrayOf(R.id.lockscreen_clock_view))
        }

    @Test
    fun testHasAodIcons_weatherClockDateAndIconsBarrierBottomBelowSmartspaceDateWeather() =
        kosmos.testScope.runTest {
            with(kosmos) {
                notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
                fakeConfigurationController.notifyConfigurationChanged()
                advanceUntilIdle()
            }

            val cs = ConstraintSet()
            underTest.applyDefaultConstraints(cs)
            val referencedIds =
                cs.getReferencedIds(R.id.weather_clock_date_and_icons_barrier_bottom)
            referencedIds.contentEquals(
                intArrayOf(
                    com.android.systemui.shared.R.id.bc_smartspace_view,
                    R.id.aod_notification_icon_container
                )
            )
        }

    private fun assertLargeClockTop(cs: ConstraintSet, expectedLargeClockTopMargin: Int) {
        val largeClockConstraint = cs.getConstraint(R.id.lockscreen_clock_view_large)
        assertThat(largeClockConstraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(largeClockConstraint.layout.topMargin).isEqualTo(expectedLargeClockTopMargin)
    }

    private fun assertSmallClockTop(cs: ConstraintSet) {
        val smallClockGuidelineConstraint = cs.getConstraint(R.id.small_clock_guideline_top)
        assertThat(smallClockGuidelineConstraint.layout.topToTop).isEqualTo(-1)

        val smallClockConstraint = cs.getConstraint(R.id.lockscreen_clock_view)
        assertThat(smallClockConstraint.layout.topToBottom)
            .isEqualTo(R.id.small_clock_guideline_top)
        assertThat(smallClockConstraint.layout.topMargin).isEqualTo(0)
    }

    companion object {
        private val SMART_SPACE_DATE_WEATHER_HEIGHT = 10
        private val ENHANCED_SMART_SPACE_HEIGHT = 11
    }
}
