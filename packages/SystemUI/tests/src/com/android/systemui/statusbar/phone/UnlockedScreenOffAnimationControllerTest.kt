/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.animation.Animator
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.StatusBarStateControllerImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.GlobalSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.spy
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class UnlockedScreenOffAnimationControllerTest : SysuiTestCase() {

    private lateinit var controller: UnlockedScreenOffAnimationController
    @Mock
    private lateinit var keyguardViewMediator: KeyguardViewMediator
    @Mock
    private lateinit var dozeParameters: DozeParameters
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var globalSettings: GlobalSettings
    @Mock
    private lateinit var statusbar: StatusBar
    @Mock
    private lateinit var lightRevealScrim: LightRevealScrim
    @Mock
    private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock
    private lateinit var statusBarStateController: StatusBarStateControllerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controller = UnlockedScreenOffAnimationController(
                context,
                wakefulnessLifecycle,
                statusBarStateController,
                dagger.Lazy<KeyguardViewMediator> { keyguardViewMediator },
                keyguardStateController,
                dagger.Lazy<DozeParameters> { dozeParameters },
                globalSettings
        )
        controller.initialize(statusbar, lightRevealScrim)
    }

    @Test
    fun testAnimClearsEndListener() {
        val keyguardView = View(context)
        val animator = spy(keyguardView.animate())
        val keyguardSpy = spy(keyguardView)
        Mockito.`when`(keyguardSpy.animate()).thenReturn(animator)
        val listener = ArgumentCaptor.forClass(Animator.AnimatorListener::class.java)
        controller.animateInKeyguard(keyguardSpy, Runnable {})
        Mockito.verify(animator).setListener(listener.capture())
        // Verify that the listener is cleared when it ends
        listener.value.onAnimationEnd(null)
        Mockito.verify(animator).setListener(null)
    }
}