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

package com.android.systemui.temporarydisplay

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class TouchableRegionViewControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: View
    @Mock private lateinit var viewTreeObserver: ViewTreeObserver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(view.viewTreeObserver).thenReturn(viewTreeObserver)
    }

    @Test
    fun viewAttached_listenerAdded() {
        val controller = TouchableRegionViewController(view) { _, _ -> }

        controller.onViewAttached()

        verify(viewTreeObserver).addOnComputeInternalInsetsListener(any())
    }

    @Test
    fun viewDetached_listenerRemoved() {
        val controller = TouchableRegionViewController(view) { _, _ -> }

        controller.onViewDetached()

        verify(viewTreeObserver).removeOnComputeInternalInsetsListener(any())
    }

    @Test
    fun listener_usesPassedInFunction() {
        val controller =
            TouchableRegionViewController(view) { _, outRect -> outRect.set(1, 2, 3, 4) }

        controller.onViewAttached()

        val captor =
            ArgumentCaptor.forClass(ViewTreeObserver.OnComputeInternalInsetsListener::class.java)
        verify(viewTreeObserver).addOnComputeInternalInsetsListener(captor.capture())
        val listener = captor.value!!

        val inoutInfo = ViewTreeObserver.InternalInsetsInfo()
        listener.onComputeInternalInsets(inoutInfo)

        assertThat(inoutInfo.touchableRegion.bounds).isEqualTo(Rect(1, 2, 3, 4))
    }
}
