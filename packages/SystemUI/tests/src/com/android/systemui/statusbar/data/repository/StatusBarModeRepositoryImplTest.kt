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

package com.android.systemui.statusbar.data.repository

import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS
import android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS
import android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.phone.BoundsPair
import com.android.systemui.statusbar.phone.LetterboxAppearance
import com.android.systemui.statusbar.phone.LetterboxAppearanceCalculator
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.OngoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarModeRepositoryImplTest : SysuiTestCase() {
    private val testScope = TestScope()
    private val commandQueue = mock<CommandQueue>()
    private val letterboxAppearanceCalculator = mock<LetterboxAppearanceCalculator>()
    private val statusBarBoundsProvider = mock<StatusBarBoundsProvider>()
    private val statusBarFragmentComponent =
        mock<StatusBarFragmentComponent>().also {
            whenever(it.boundsProvider).thenReturn(statusBarBoundsProvider)
        }
    private val ongoingCallRepository = OngoingCallRepository()

    private val underTest =
        StatusBarModePerDisplayRepositoryImpl(
                testScope.backgroundScope,
                DISPLAY_ID,
                commandQueue,
                letterboxAppearanceCalculator,
                ongoingCallRepository,
            )
            .apply {
                this.start()
                this.onStatusBarViewInitialized(statusBarFragmentComponent)
            }

    private val commandQueueCallback: CommandQueue.Callbacks
        get() {
            val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
            verify(commandQueue).addCallback(callbackCaptor.capture())
            return callbackCaptor.value
        }

    private val statusBarBoundsChangeListener: StatusBarBoundsProvider.BoundsChangeListener
        get() {
            val callbackCaptor = argumentCaptor<StatusBarBoundsProvider.BoundsChangeListener>()
            verify(statusBarBoundsProvider).addChangeListener(capture(callbackCaptor))
            return callbackCaptor.value
        }

    @Before fun setUp() {}

    @Test
    fun isTransientShown_commandQueueShow_wrongDisplayId_notUpdated() {
        commandQueueCallback.showTransient(
            DISPLAY_ID + 1,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_commandQueueShow_notStatusBarType_notUpdated() {
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.navigationBars(),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_commandQueueShow_true() {
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueShow_statusBarAndOtherTypes_true() {
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars().or(WindowInsets.Type.navigationBars()),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueAbort_wrongDisplayId_notUpdated() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        // GIVEN the wrong display ID
        commandQueueCallback.abortTransient(DISPLAY_ID + 1, WindowInsets.Type.statusBars())

        // THEN the old value remains
        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueAbort_notStatusBarType_notUpdated() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        // GIVEN the wrong type
        commandQueueCallback.abortTransient(DISPLAY_ID, WindowInsets.Type.navigationBars())

        // THEN the old value remains
        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueAbort_false() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        commandQueueCallback.abortTransient(DISPLAY_ID, WindowInsets.Type.statusBars())

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_commandQueueAbort_statusBarAndOtherTypes_false() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        commandQueueCallback.abortTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars().or(WindowInsets.Type.captionBar()),
        )

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_showTransient_true() {
        underTest.showTransient()

        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_clearTransient_false() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        underTest.clearTransient()

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isInFullscreenMode_visibleTypesHasStatusBar_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isInFullscreenMode)

            onSystemBarAttributesChanged(
                requestedVisibleTypes = WindowInsets.Type.statusBars(),
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun isInFullscreenMode_visibleTypesDoesNotHaveStatusBar_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isInFullscreenMode)

            onSystemBarAttributesChanged(
                requestedVisibleTypes = WindowInsets.Type.navigationBars(),
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun isInFullscreenMode_wrongDisplayId_notUpdated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isInFullscreenMode)

            onSystemBarAttributesChanged(
                requestedVisibleTypes = WindowInsets.Type.navigationBars(),
            )
            assertThat(latest).isTrue()

            onSystemBarAttributesChanged(
                displayId = DISPLAY_ID + 1,
                requestedVisibleTypes = WindowInsets.Type.statusBars(),
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun statusBarAppearance_navBarColorManaged_matchesCallbackValue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            onSystemBarAttributesChanged(navbarColorManagedByIme = true)

            assertThat(latest!!.navbarColorManagedByIme).isTrue()

            onSystemBarAttributesChanged(navbarColorManagedByIme = false)

            assertThat(latest!!.navbarColorManagedByIme).isFalse()
        }

    @Test
    fun statusBarAppearance_appearanceRegions_noLetterboxDetails_usesCallbackValues() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            whenever(
                    letterboxAppearanceCalculator.getLetterboxAppearance(
                        eq(APPEARANCE),
                        eq(APPEARANCE_REGIONS),
                        eq(LETTERBOX_DETAILS),
                        any(),
                    )
                )
                .thenReturn(CALCULATOR_LETTERBOX_APPEARANCE)

            // WHEN the letterbox details are empty
            onSystemBarAttributesChanged(
                appearance = APPEARANCE,
                appearanceRegions = APPEARANCE_REGIONS.toTypedArray(),
                letterboxDetails = emptyArray(),
            )

            // THEN the appearance regions passed to the callback are used, *not*
            // REGIONS_FROM_LETTERBOX_CALCULATOR
            assertThat(latest!!.appearanceRegions).isEqualTo(APPEARANCE_REGIONS)
            assertThat(latest!!.appearanceRegions).isNotEqualTo(REGIONS_FROM_LETTERBOX_CALCULATOR)
        }

    @Test
    fun statusBarAppearance_appearanceRegions_letterboxDetails_usesLetterboxCalculator() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            whenever(
                    letterboxAppearanceCalculator.getLetterboxAppearance(
                        eq(APPEARANCE),
                        eq(APPEARANCE_REGIONS),
                        eq(LETTERBOX_DETAILS),
                        any(),
                    )
                )
                .thenReturn(CALCULATOR_LETTERBOX_APPEARANCE)

            onSystemBarAttributesChanged(
                appearance = APPEARANCE,
                appearanceRegions = APPEARANCE_REGIONS.toTypedArray(),
                letterboxDetails = LETTERBOX_DETAILS.toTypedArray(),
            )

            assertThat(latest!!.appearanceRegions).isEqualTo(REGIONS_FROM_LETTERBOX_CALCULATOR)
        }

    @Test
    fun statusBarAppearance_boundsChanged_appearanceReFetched() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            // First, start with some appearances
            val startingLetterboxAppearance =
                LetterboxAppearance(
                    APPEARANCE_LIGHT_STATUS_BARS,
                    listOf(AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, Rect(0, 0, 1, 1)))
                )
            whenever(
                    letterboxAppearanceCalculator.getLetterboxAppearance(
                        eq(APPEARANCE),
                        eq(APPEARANCE_REGIONS),
                        eq(LETTERBOX_DETAILS),
                        any(),
                    )
                )
                .thenReturn(startingLetterboxAppearance)
            onSystemBarAttributesChanged(
                appearance = APPEARANCE,
                appearanceRegions = APPEARANCE_REGIONS.toTypedArray(),
                letterboxDetails = LETTERBOX_DETAILS.toTypedArray(),
            )
            assertThat(latest!!.mode).isEqualTo(StatusBarMode.TRANSPARENT)
            assertThat(latest!!.appearanceRegions)
                .isEqualTo(startingLetterboxAppearance.appearanceRegions)

            // WHEN there's a new appearance and we get new status bar bounds
            val newLetterboxAppearance =
                LetterboxAppearance(
                    APPEARANCE_LOW_PROFILE_BARS,
                    listOf(AppearanceRegion(APPEARANCE_LOW_PROFILE_BARS, Rect(10, 20, 30, 40)))
                )
            whenever(
                    letterboxAppearanceCalculator.getLetterboxAppearance(
                        eq(APPEARANCE),
                        eq(APPEARANCE_REGIONS),
                        eq(LETTERBOX_DETAILS),
                        any(),
                    )
                )
                .thenReturn(newLetterboxAppearance)
            statusBarBoundsChangeListener.onStatusBarBoundsChanged(
                BoundsPair(Rect(0, 0, 50, 50), Rect(0, 0, 60, 60))
            )

            // THEN the new appearances are used
            assertThat(latest!!.mode).isEqualTo(StatusBarMode.LIGHTS_OUT_TRANSPARENT)
            assertThat(latest!!.appearanceRegions)
                .isEqualTo(newLetterboxAppearance.appearanceRegions)
        }

    @Test
    fun statusBarMode_ongoingCallAndFullscreen_semiTransparent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            ongoingCallRepository.setOngoingCallState(
                OngoingCallModel.InCall(startTimeMs = 34, intent = null)
            )
            onSystemBarAttributesChanged(
                requestedVisibleTypes = WindowInsets.Type.navigationBars(),
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.SEMI_TRANSPARENT)
        }

    @Test
    fun statusBarMode_ongoingCallButNotFullscreen_matchesAppearance() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            ongoingCallRepository.setOngoingCallState(
                OngoingCallModel.InCall(startTimeMs = 789, intent = null)
            )
            onSystemBarAttributesChanged(
                requestedVisibleTypes = WindowInsets.Type.statusBars(),
                appearance = APPEARANCE_OPAQUE_STATUS_BARS,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.OPAQUE)
        }

    @Test
    fun statusBarMode_fullscreenButNotOngoingCall_matchesAppearance() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            ongoingCallRepository.setOngoingCallState(OngoingCallModel.NoCall)
            onSystemBarAttributesChanged(
                requestedVisibleTypes = WindowInsets.Type.navigationBars(),
                appearance = APPEARANCE_OPAQUE_STATUS_BARS,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.OPAQUE)
        }

    @Test
    fun statusBarMode_transientShown_semiTransparent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)
            onSystemBarAttributesChanged(
                appearance = APPEARANCE_OPAQUE_STATUS_BARS,
            )

            underTest.showTransient()

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.SEMI_TRANSPARENT)
        }

    @Test
    fun statusBarMode_appearanceLowProfileAndOpaque_lightsOut() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            onSystemBarAttributesChanged(
                appearance = APPEARANCE_LOW_PROFILE_BARS or APPEARANCE_OPAQUE_STATUS_BARS,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.LIGHTS_OUT)
        }

    @Test
    fun statusBarMode_appearanceLowProfile_lightsOutTransparent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            onSystemBarAttributesChanged(
                appearance = APPEARANCE_LOW_PROFILE_BARS,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.LIGHTS_OUT_TRANSPARENT)
        }

    @Test
    fun statusBarMode_appearanceOpaque_opaque() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            onSystemBarAttributesChanged(
                appearance = APPEARANCE_OPAQUE_STATUS_BARS,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.OPAQUE)
        }

    @Test
    fun statusBarMode_appearanceSemiTransparent_semiTransparent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            onSystemBarAttributesChanged(
                appearance = APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.SEMI_TRANSPARENT)
        }

    @Test
    fun statusBarMode_appearanceNone_transparent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.statusBarAppearance)

            onSystemBarAttributesChanged(
                appearance = 0,
            )

            assertThat(latest!!.mode).isEqualTo(StatusBarMode.TRANSPARENT)
        }

    private fun onSystemBarAttributesChanged(
        displayId: Int = DISPLAY_ID,
        @WindowInsetsController.Appearance appearance: Int = APPEARANCE_OPAQUE_STATUS_BARS,
        appearanceRegions: Array<AppearanceRegion> = emptyArray(),
        navbarColorManagedByIme: Boolean = false,
        @WindowInsetsController.Behavior behavior: Int = WindowInsetsController.BEHAVIOR_DEFAULT,
        @WindowInsets.Type.InsetsType
        requestedVisibleTypes: Int = WindowInsets.Type.defaultVisible(),
        packageName: String = "package name",
        letterboxDetails: Array<LetterboxDetails> = emptyArray(),
    ) {
        commandQueueCallback.onSystemBarAttributesChanged(
            displayId,
            appearance,
            appearanceRegions,
            navbarColorManagedByIme,
            behavior,
            requestedVisibleTypes,
            packageName,
            letterboxDetails,
        )
    }

    private companion object {
        const val DISPLAY_ID = 5
        private const val APPEARANCE = APPEARANCE_LIGHT_STATUS_BARS
        private val APPEARANCE_REGION = AppearanceRegion(APPEARANCE, Rect(0, 0, 150, 300))
        private val APPEARANCE_REGIONS = listOf(APPEARANCE_REGION)
        private val LETTERBOX_DETAILS =
            listOf(
                LetterboxDetails(
                    /* letterboxInnerBounds= */ Rect(0, 0, 10, 10),
                    /* letterboxFullBounds= */ Rect(0, 0, 20, 20),
                    /* appAppearance= */ 0
                )
            )
        private val REGIONS_FROM_LETTERBOX_CALCULATOR =
            listOf(AppearanceRegion(APPEARANCE, Rect(0, 0, 10, 20)))
        private const val LETTERBOXED_APPEARANCE = APPEARANCE_LOW_PROFILE_BARS
        private val CALCULATOR_LETTERBOX_APPEARANCE =
            LetterboxAppearance(LETTERBOXED_APPEARANCE, REGIONS_FROM_LETTERBOX_CALCULATOR)
    }
}
