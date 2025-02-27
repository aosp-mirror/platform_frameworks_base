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

package com.android.systemui.statusbar.events

import android.platform.test.annotations.EnableFlags
import androidx.core.animation.AnimatorSet
import androidx.core.animation.ValueAnimator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.repository.systemEventChipAnimationControllerStore
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class MultiDisplaySystemEventChipAnimationControllerTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val displayRepository = kosmos.displayRepository
    private val store = kosmos.systemEventChipAnimationControllerStore

    // Lazy so that @EnableFlags has time to switch the flags before the instance is created.
    private val underTest by lazy { kosmos.multiDisplaySystemEventChipAnimationController }

    @Before
    fun installDisplays() = runBlocking {
        INSTALLED_DISPLAY_IDS.forEach { displayRepository.addDisplay(displayId = it) }
    }

    @Test
    fun init_forwardsToAllControllers() {
        underTest.init()

        INSTALLED_DISPLAY_IDS.forEach { verify(store.forDisplay(it)).init() }
    }

    @Test
    fun stop_forwardsToAllControllers() {
        underTest.stop()

        INSTALLED_DISPLAY_IDS.forEach { verify(store.forDisplay(it)).stop() }
    }

    @Test
    fun announceForAccessibility_forwardsToAllControllers() {
        val contentDescription = "test content description"
        underTest.announceForAccessibility(contentDescription)

        INSTALLED_DISPLAY_IDS.forEach {
            verify(store.forDisplay(it)).announceForAccessibility(contentDescription)
        }
    }

    @Test
    fun onSystemEventAnimationBegin_returnsAnimatorSetWithOneAnimatorPerDisplay() {
        INSTALLED_DISPLAY_IDS.forEach {
            val controller = store.forDisplay(it)
            whenever(controller.onSystemEventAnimationBegin()).thenReturn(ValueAnimator.ofInt(0, 1))
        }
        val animator = underTest.onSystemEventAnimationBegin() as AnimatorSet

        assertThat(animator.childAnimations).hasSize(INSTALLED_DISPLAY_IDS.size)
    }

    @Test
    fun onSystemEventAnimationFinish_returnsAnimatorSetWithOneAnimatorPerDisplay() {
        INSTALLED_DISPLAY_IDS.forEach {
            val controller = store.forDisplay(it)
            whenever(controller.onSystemEventAnimationFinish(any()))
                .thenReturn(ValueAnimator.ofInt(0, 1))
        }
        val animator =
            underTest.onSystemEventAnimationFinish(hasPersistentDot = true) as AnimatorSet

        assertThat(animator.childAnimations).hasSize(INSTALLED_DISPLAY_IDS.size)
    }

    companion object {
        private const val DISPLAY_ID_1 = 123
        private const val DISPLAY_ID_2 = 456
        private val INSTALLED_DISPLAY_IDS = listOf(DISPLAY_ID_1, DISPLAY_ID_2)
    }
}
