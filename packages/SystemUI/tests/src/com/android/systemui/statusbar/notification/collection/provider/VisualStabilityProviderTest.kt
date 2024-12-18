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
package com.android.systemui.statusbar.notification.collection.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidJUnit4::class)
class VisualStabilityProviderTest : SysuiTestCase() {
    private val visualStabilityProvider = VisualStabilityProvider()
    private val listener: OnReorderingAllowedListener = mock()

    @After
    fun tearDown() {
        // Verify that every interaction is verified in every test
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testPersistentListenerIgnoredIfStateNotChanged() {
        visualStabilityProvider.addPersistentReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, never()).onReorderingAllowed()
    }

    @Test
    fun testPersistentListenerCalledTwice() {
        visualStabilityProvider.addPersistentReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(1)).onReorderingAllowed()

        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(2)).onReorderingAllowed()
    }

    @Test
    fun testTemporaryListenerCalledOnce() {
        visualStabilityProvider.addTemporaryReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(1)).onReorderingAllowed()

        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(1)).onReorderingAllowed()
    }

    @Test
    fun testPersistentListenerCanBeRemoved() {
        visualStabilityProvider.addPersistentReorderingAllowedListener(listener)
        visualStabilityProvider.removeReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, never()).onReorderingAllowed()
    }

    @Test
    fun testTemporaryListenerCanBeRemoved() {
        visualStabilityProvider.addTemporaryReorderingAllowedListener(listener)
        visualStabilityProvider.removeReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, never()).onReorderingAllowed()
    }

    @Test
    fun testPersistentListenerStaysPersistent() {
        visualStabilityProvider.addPersistentReorderingAllowedListener(listener)
        visualStabilityProvider.addTemporaryReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(1)).onReorderingAllowed()

        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(2)).onReorderingAllowed()
    }

    @Test
    fun testTemporaryListenerBecomesPersistent() {
        visualStabilityProvider.addTemporaryReorderingAllowedListener(listener)
        visualStabilityProvider.addPersistentReorderingAllowedListener(listener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(1)).onReorderingAllowed()

        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(listener, times(2)).onReorderingAllowed()
    }

    @Test
    fun testPersistentListenerCanRemoveSelf() {
        val selfRemovingListener = spy(object : OnReorderingAllowedListener {
            override fun onReorderingAllowed() {
                visualStabilityProvider.removeReorderingAllowedListener(this)
            }
        })
        visualStabilityProvider.addPersistentReorderingAllowedListener(selfRemovingListener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(selfRemovingListener, times(1)).onReorderingAllowed()

        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(selfRemovingListener, times(1)).onReorderingAllowed()
    }

    @Test
    fun testTemporaryListenerCanReAddSelf() {
        val selfAddingListener = spy(object : OnReorderingAllowedListener {
            override fun onReorderingAllowed() {
                visualStabilityProvider.addTemporaryReorderingAllowedListener(this)
            }
        })
        visualStabilityProvider.addTemporaryReorderingAllowedListener(selfAddingListener)
        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(selfAddingListener, times(1)).onReorderingAllowed()

        visualStabilityProvider.isReorderingAllowed = false
        visualStabilityProvider.isReorderingAllowed = true
        verify(selfAddingListener, times(2)).onReorderingAllowed()
    }
}
