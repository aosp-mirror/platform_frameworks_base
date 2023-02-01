/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.KeyguardBouncer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class BouncerCallbackInteractorTest : SysuiTestCase() {
    private val bouncerCallbackInteractor = BouncerCallbackInteractor()
    @Mock private lateinit var bouncerExpansionCallback: KeyguardBouncer.BouncerExpansionCallback
    @Mock private lateinit var keyguardResetCallback: KeyguardBouncer.KeyguardResetCallback

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        bouncerCallbackInteractor.addBouncerExpansionCallback(bouncerExpansionCallback)
        bouncerCallbackInteractor.addKeyguardResetCallback(keyguardResetCallback)
    }

    @Test
    fun testOnFullyShown() {
        bouncerCallbackInteractor.dispatchFullyShown()
        verify(bouncerExpansionCallback).onFullyShown()
    }

    @Test
    fun testOnFullyHidden() {
        bouncerCallbackInteractor.dispatchFullyHidden()
        verify(bouncerExpansionCallback).onFullyHidden()
    }

    @Test
    fun testOnExpansionChanged() {
        bouncerCallbackInteractor.dispatchExpansionChanged(5f)
        verify(bouncerExpansionCallback).onExpansionChanged(5f)
    }

    @Test
    fun testOnVisibilityChanged() {
        bouncerCallbackInteractor.dispatchVisibilityChanged(View.INVISIBLE)
        verify(bouncerExpansionCallback).onVisibilityChanged(false)
    }

    @Test
    fun testOnStartingToHide() {
        bouncerCallbackInteractor.dispatchStartingToHide()
        verify(bouncerExpansionCallback).onStartingToHide()
    }

    @Test
    fun testOnStartingToShow() {
        bouncerCallbackInteractor.dispatchStartingToShow()
        verify(bouncerExpansionCallback).onStartingToShow()
    }

    @Test
    fun testOnKeyguardReset() {
        bouncerCallbackInteractor.dispatchReset()
        verify(keyguardResetCallback).onKeyguardReset()
    }
}
