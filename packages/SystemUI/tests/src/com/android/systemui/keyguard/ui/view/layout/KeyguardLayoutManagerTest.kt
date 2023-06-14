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

package com.android.systemui.keyguard.ui.view.layout

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.DefaultLockscreenLayout.Companion.DEFAULT
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class KeyguardLayoutManagerTest : SysuiTestCase() {
    private lateinit var keyguardLayoutManager: KeyguardLayoutManager
    @Mock lateinit var configurationController: ConfigurationController
    @Mock lateinit var defaultLockscreenLayout: DefaultLockscreenLayout
    @Mock lateinit var keyguardRootView: KeyguardRootView

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(defaultLockscreenLayout.id).thenReturn(DEFAULT)
        keyguardLayoutManager =
            KeyguardLayoutManager(
                configurationController,
                setOf(defaultLockscreenLayout),
                keyguardRootView
            )
    }

    @Test
    fun testDefaultLayout() {
        keyguardLayoutManager.transitionToLayout(DEFAULT)
        verify(defaultLockscreenLayout).layoutViews(keyguardRootView)
    }

    @Test
    fun testTransitionToLayout_validId() {
        assertThat(keyguardLayoutManager.transitionToLayout(DEFAULT)).isTrue()
    }
    @Test
    fun testTransitionToLayout_invalidId() {
        assertThat(keyguardLayoutManager.transitionToLayout("abc")).isFalse()
    }
}
