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

package com.android.systemui.keyguard.ui.binder

import android.testing.TestableLooper.RunWithLooper
import android.view.RemoteAnimationTarget
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardViewController
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.keyguard.domain.interactor.KeyguardSurfaceBehindInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.MockitoAnnotations

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardSurfaceBehindParamsApplierTest : SysuiTestCase() {
    @get:Rule val animatorTestRule = AnimatorTestRule()

    private lateinit var underTest: KeyguardSurfaceBehindParamsApplier
    private lateinit var executor: FakeExecutor

    @Mock private lateinit var keyguardViewController: KeyguardViewController

    @Mock private lateinit var interactor: KeyguardSurfaceBehindInteractor

    @Mock private lateinit var remoteAnimationTarget: RemoteAnimationTarget

    private var isAnimatingSurface: Boolean? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
        underTest =
            KeyguardSurfaceBehindParamsApplier(
                executor = executor,
                keyguardViewController = keyguardViewController,
                interactor = interactor,
            )

        doAnswer {
                (it.arguments[0] as Boolean).let { animating -> isAnimatingSurface = animating }
            }
            .whenever(interactor)
            .setAnimatingSurface(anyBoolean())
    }

    @After
    fun tearDown() {
        animatorTestRule.advanceTimeBy(1000.toLong())
    }

    @Test
    fun testNotAnimating_setParamsWithNoAnimation() {
        underTest.viewParams =
            KeyguardSurfaceBehindModel(
                alpha = 0.3f,
                translationY = 300f,
            )

        // A surface has not yet been provided, so we shouldn't have set animating to false OR true
        // just yet.
        assertNull(isAnimatingSurface)

        underTest.applyParamsToSurface(remoteAnimationTarget)

        // We should now explicitly not be animating the surface.
        assertFalse(checkNotNull(isAnimatingSurface))
    }

    @Test
    fun testAnimating_paramsThenSurfaceProvided() {
        underTest.viewParams =
            KeyguardSurfaceBehindModel(
                animateFromAlpha = 0f,
                alpha = 0.3f,
                animateFromTranslationY = 0f,
                translationY = 300f,
            )

        // A surface has not yet been provided, so we shouldn't have set animating to false OR true
        // just yet.
        assertNull(isAnimatingSurface)

        underTest.applyParamsToSurface(remoteAnimationTarget)

        // We should now be animating the surface.
        assertTrue(checkNotNull(isAnimatingSurface))
    }

    @Test
    fun testAnimating_surfaceThenParamsProvided() {
        underTest.applyParamsToSurface(remoteAnimationTarget)

        // The default params (which do not animate) should have been applied, so we're explicitly
        // NOT animating yet.
        assertFalse(checkNotNull(isAnimatingSurface))

        underTest.viewParams =
            KeyguardSurfaceBehindModel(
                animateFromAlpha = 0f,
                alpha = 0.3f,
                animateFromTranslationY = 0f,
                translationY = 300f,
            )

        // We should now be animating the surface.
        assertTrue(checkNotNull(isAnimatingSurface))
    }

    @Test
    fun testAnimating_thenReleased_animatingIsFalse() {
        underTest.viewParams =
            KeyguardSurfaceBehindModel(
                animateFromAlpha = 0f,
                alpha = 0.3f,
                animateFromTranslationY = 0f,
                translationY = 300f,
            )
        underTest.applyParamsToSurface(remoteAnimationTarget)

        assertTrue(checkNotNull(isAnimatingSurface))

        underTest.notifySurfaceReleased()

        // Releasing the surface should immediately cancel animators.
        assertFalse(checkNotNull(isAnimatingSurface))
    }
}