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

package com.android.systemui.statusbar.phone.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.configureKeyguardBypass
import com.android.systemui.keyguard.domain.interactor.KeyguardBypassInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardBypassInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.interactor.pulseExpansionInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class KeyguardBypassInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var underTest: KeyguardBypassInteractor

    @Test
    fun canBypassFalseWhenBypassAvailableFalse() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipIsBypassAvailableCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            runCurrent()
            assertThat(canBypass).isFalse()
        }

    @Test
    fun canBypassTrueOnPrimaryBouncerShowing() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipBouncerShowingCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            runCurrent()
            assertThat(canBypass).isTrue()
        }

    @Test
    fun canBypassTrueOnAlternateBouncerShowing() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipAlternateBouncerShowingCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            runCurrent()
            assertThat(canBypass).isTrue()
        }

    @Test
    fun canBypassFalseWhenNotOnLockscreenScene() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipOnLockscreenSceneCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            runCurrent()
            assertThat(currentScene).isNotEqualTo(Scenes.Lockscreen)
            assertThat(canBypass).isFalse()
        }

    @Test
    fun canBypassFalseOnLaunchingAffordance() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipLaunchingAffordanceCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            runCurrent()
            assertThat(canBypass).isFalse()
        }

    @Test
    fun canBypassFalseOnPulseExpanding() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipPulseExpandingCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            runCurrent()
            assertThat(canBypass).isFalse()
        }

    @Test
    fun canBypassFalseOnQsExpanded() =
        testScope.runTest {
            initializeDependenciesForCanBypass(skipQsExpandedCheck = false)
            val canBypass by collectLastValue(underTest.canBypass)
            runCurrent()
            assertThat(canBypass).isFalse()
        }

    // Initializes all canBypass dependencies to opposite of value needed to return
    private fun initializeDependenciesForCanBypass(
        skipIsBypassAvailableCheck: Boolean = true,
        skipBouncerShowingCheck: Boolean = true,
        skipAlternateBouncerShowingCheck: Boolean = true,
        skipOnLockscreenSceneCheck: Boolean = true,
        skipLaunchingAffordanceCheck: Boolean = true,
        skipPulseExpandingCheck: Boolean = true,
        skipQsExpandedCheck: Boolean = true,
    ) {
        // !isBypassAvailable false
        kosmos.configureKeyguardBypass(isBypassAvailable = skipIsBypassAvailableCheck)
        underTest = kosmos.keyguardBypassInteractor

        // bouncerShowing false, !onLockscreenScene false
        // !onLockscreenScene false
        setScene(
            bouncerShowing = !skipBouncerShowingCheck,
            onLockscreenScene = skipOnLockscreenSceneCheck,
        )
        // alternateBouncerShowing false
        setAlternateBouncerShowing(!skipAlternateBouncerShowingCheck)
        // launchingAffordance false
        setLaunchingAffordance(!skipLaunchingAffordanceCheck)
        // pulseExpanding false
        setPulseExpanding(!skipPulseExpandingCheck)
        // qsExpanding false
        setQsExpanded(!skipQsExpandedCheck)
    }

    private fun setAlternateBouncerShowing(alternateBouncerVisible: Boolean) {
        kosmos.keyguardBouncerRepository.setAlternateVisible(alternateBouncerVisible)
    }

    private fun setScene(bouncerShowing: Boolean, onLockscreenScene: Boolean) {
        if (bouncerShowing) {
            kosmos.sceneInteractor.changeScene(Scenes.Bouncer, "reason")
        } else if (onLockscreenScene) {
            kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
        } else {
            kosmos.sceneInteractor.changeScene(Scenes.Shade, "reason")
        }
    }

    private fun setLaunchingAffordance(launchingAffordance: Boolean) {
        kosmos.keyguardQuickAffordanceInteractor.setLaunchingAffordance(launchingAffordance)
    }

    private fun setPulseExpanding(pulseExpanding: Boolean) {
        kosmos.pulseExpansionInteractor.setPulseExpanding(pulseExpanding)
    }

    private fun setQsExpanded(qsExpanded: Boolean) {
        if (qsExpanded) {
            kosmos.shadeTestUtil.setQsExpansion(1f)
        } else {
            kosmos.shadeTestUtil.setQsExpansion(0f)
        }
    }
}
