/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.animation

import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import kotlin.test.Test

@SmallTest
@RunWith(AndroidJUnit4::class)
class ViewTransitionRegistryTest : SysuiTestCase() {

    private lateinit var view: View
    private lateinit var underTest: ViewTransitionRegistry
    private var token: ViewTransitionToken = ViewTransitionToken()

    @Before
    fun setup() {
        view = FrameLayout(mContext)
        underTest = ViewTransitionRegistry()
        token = ViewTransitionToken()
    }

    @Test
    fun testSuccessfulRegisterInViewTransitionRegistry() {
        underTest.register(token, view)
        assertThat(underTest.getView(token)).isNotNull()
    }

    @Test
    fun testSuccessfulUnregisterInViewTransitionRegistry() {
        underTest.register(token, view)
        assertThat(underTest.getView(token)).isNotNull()

        underTest.unregister(token)
        assertThat(underTest.getView(token)).isNull()
    }

    @Test
    fun testSuccessfulUnregisterOnViewDetachedFromWindow() {
        val view: View = mock {
            on { getTag(R.id.tag_view_transition_token) } doReturn token
        }

        underTest.register(token, view)
        assertThat(underTest.getView(token)).isNotNull()

        argumentCaptor<View.OnAttachStateChangeListener>()
            .apply { verify(view).addOnAttachStateChangeListener(capture()) }
            .firstValue
            .onViewDetachedFromWindow(view)

        assertThat(underTest.getView(token)).isNull()
    }
}
