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

package com.android.systemui.statusbar.core

import android.app.FragmentManager
import android.app.FragmentTransaction
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.fragments.FragmentHostManager
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarInitializerTest : SysuiTestCase() {
    private val windowController = mock(StatusBarWindowController::class.java)
    private val windowControllerStore = mock(StatusBarWindowControllerStore::class.java)
    private val transaction = mock(FragmentTransaction::class.java)
    private val fragmentManager = mock(FragmentManager::class.java)
    private val fragmentHostManager = mock(FragmentHostManager::class.java)
    private val backgroundView = mock(ViewGroup::class.java)

    @Before
    fun setup() {
        // TODO(b/364360986) this will go away once the fragment is deprecated. Hence, there is no
        // need right now for moving this to kosmos
        whenever(fragmentHostManager.addTagListener(any(), any())).thenReturn(fragmentHostManager)
        whenever(fragmentHostManager.fragmentManager).thenReturn(fragmentManager)
        whenever(fragmentManager.beginTransaction()).thenReturn(transaction)
        whenever(transaction.replace(any(), any(), any())).thenReturn(transaction)
        whenever(windowControllerStore.defaultDisplay).thenReturn(windowController)
        whenever(windowController.fragmentHostManager).thenReturn(fragmentHostManager)
        whenever(windowController.backgroundView).thenReturn(backgroundView)
    }

    val underTest =
        StatusBarInitializerImpl(
            statusBarWindowController = windowController,
            collapsedStatusBarFragmentProvider = { mock(CollapsedStatusBarFragment::class.java) },
            statusBarRootFactory = mock(StatusBarRootFactory::class.java),
            componentFactory = mock(HomeStatusBarComponent.Factory::class.java),
            creationListeners = setOf(),
        )

    @Test
    @EnableFlags(Flags.FLAG_STATUS_BAR_SIMPLE_FRAGMENT)
    fun simpleFragment_startsFromCoreStartable() {
        underTest.start()
        assertThat(underTest.initialized).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_STATUS_BAR_SIMPLE_FRAGMENT)
    fun simpleFragment_throwsIfInitializeIsCalled() {
        assertThrows(IllegalStateException::class.java) { underTest.initializeStatusBar() }
    }

    @Test
    @EnableFlags(Flags.FLAG_STATUS_BAR_SIMPLE_FRAGMENT)
    fun simpleFragment_flagEnabled_doesNotCreateFragment() {
        underTest.start()

        verify(fragmentManager, never()).beginTransaction()
        verify(transaction, never()).replace(any(), any(), any())
    }

    @Test
    @DisableFlags(Flags.FLAG_STATUS_BAR_SIMPLE_FRAGMENT)
    fun flagOff_doesNotInitializeViaCoreStartable() {
        underTest.start()
        assertThat(underTest.initialized).isFalse()
    }

    @Test
    @DisableFlags(Flags.FLAG_STATUS_BAR_SIMPLE_FRAGMENT)
    fun flagOff_doesNotThrowIfInitializeIsCalled() {
        underTest.initializeStatusBar()
        assertThat(underTest.initialized).isTrue()
    }
}
