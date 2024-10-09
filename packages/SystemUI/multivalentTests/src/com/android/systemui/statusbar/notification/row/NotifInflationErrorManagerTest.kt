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
 */

package com.android.systemui.statusbar.notification.row

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager.NotifInflationErrorListener
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class NotifInflationErrorManagerTest : SysuiTestCase() {
    private lateinit var manager: NotifInflationErrorManager

    private val listener1 = mock(NotifInflationErrorListener::class.java)
    private val listener2 = mock(NotifInflationErrorListener::class.java)

    private val foo: NotificationEntry = NotificationEntryBuilder().setPkg("foo").build()
    private val bar: NotificationEntry = NotificationEntryBuilder().setPkg("bar").build()
    private val baz: NotificationEntry = NotificationEntryBuilder().setPkg("baz").build()

    private val fooException = Exception("foo")
    private val barException = Exception("bar")

    @Before
    fun setUp() {
        // Reset manager instance before each test.
        manager = NotifInflationErrorManager()
    }

    @Test
    fun testTracksInflationErrors() {
        manager.setInflationError(foo, fooException)
        manager.setInflationError(bar, barException)

        assertThat(manager.hasInflationError(foo)).isTrue()
        assertThat(manager.hasInflationError(bar)).isTrue()
        assertThat(manager.hasInflationError(baz)).isFalse()

        manager.clearInflationError(bar)

        assertThat(manager.hasInflationError(bar)).isFalse()
    }

    @Test
    fun testNotifiesListeners() {
        manager.addInflationErrorListener(listener1)
        manager.setInflationError(foo, fooException)

        verify(listener1).onNotifInflationError(foo, fooException)

        manager.addInflationErrorListener(listener2)
        manager.setInflationError(bar, barException)

        verify(listener1).onNotifInflationError(bar, barException)
        verify(listener2).onNotifInflationError(bar, barException)

        manager.clearInflationError(foo)

        verify(listener1).onNotifInflationErrorCleared(foo)
        verify(listener2).onNotifInflationErrorCleared(foo)
    }

    @Test
    fun testClearUnknownEntry() {
        manager.addInflationErrorListener(listener1)
        manager.clearInflationError(foo)

        verify(listener1, never()).onNotifInflationErrorCleared(any())
    }
}
