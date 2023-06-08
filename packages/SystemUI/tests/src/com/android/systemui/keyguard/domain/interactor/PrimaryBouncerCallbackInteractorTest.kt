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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerCallbackInteractorTest : SysuiTestCase() {
    private val mPrimaryBouncerCallbackInteractor = PrimaryBouncerCallbackInteractor()
    @Mock
    private lateinit var mPrimaryBouncerExpansionCallback:
        PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback
    @Mock
    private lateinit var keyguardResetCallback:
        PrimaryBouncerCallbackInteractor.KeyguardResetCallback

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mPrimaryBouncerCallbackInteractor.addBouncerExpansionCallback(
            mPrimaryBouncerExpansionCallback
        )
        mPrimaryBouncerCallbackInteractor.addKeyguardResetCallback(keyguardResetCallback)
    }

    @Test
    fun testOnFullyShown() {
        mPrimaryBouncerCallbackInteractor.dispatchFullyShown()
        verify(mPrimaryBouncerExpansionCallback).onFullyShown()
    }

    @Test
    fun testOnFullyHidden() {
        mPrimaryBouncerCallbackInteractor.dispatchFullyHidden()
        verify(mPrimaryBouncerExpansionCallback).onFullyHidden()
    }

    @Test
    fun testOnExpansionChanged() {
        mPrimaryBouncerCallbackInteractor.dispatchExpansionChanged(5f)
        verify(mPrimaryBouncerExpansionCallback).onExpansionChanged(5f)
    }

    @Test
    fun testOnVisibilityChanged() {
        mPrimaryBouncerCallbackInteractor.dispatchVisibilityChanged(View.INVISIBLE)
        verify(mPrimaryBouncerExpansionCallback).onVisibilityChanged(false)
    }

    @Test
    fun testOnStartingToHide() {
        mPrimaryBouncerCallbackInteractor.dispatchStartingToHide()
        verify(mPrimaryBouncerExpansionCallback).onStartingToHide()
    }

    @Test
    fun testOnStartingToShow() {
        mPrimaryBouncerCallbackInteractor.dispatchStartingToShow()
        verify(mPrimaryBouncerExpansionCallback).onStartingToShow()
    }

    @Test
    fun testOnKeyguardReset() {
        mPrimaryBouncerCallbackInteractor.dispatchReset()
        verify(keyguardResetCallback).onKeyguardReset()
    }
}
