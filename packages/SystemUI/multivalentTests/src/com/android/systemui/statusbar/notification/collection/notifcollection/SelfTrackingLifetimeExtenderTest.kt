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
package com.android.systemui.statusbar.notification.collection.notifcollection

import android.os.Handler
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks
import java.util.function.Consumer
import java.util.function.Predicate

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class SelfTrackingLifetimeExtenderTest : SysuiTestCase() {
    private lateinit var extender: TestableSelfTrackingLifetimeExtender

    private lateinit var entry1: NotificationEntry
    private lateinit var entry2: NotificationEntry

    @Mock
    private lateinit var callback: OnEndLifetimeExtensionCallback
    @Mock
    private lateinit var mainHandler: Handler
    @Mock
    private lateinit var shouldExtend: Predicate<NotificationEntry>
    @Mock
    private lateinit var onStarted: Consumer<NotificationEntry>
    @Mock
    private lateinit var onCanceled: Consumer<NotificationEntry>

    @Before
    fun setUp() {
        initMocks(this)
        extender = TestableSelfTrackingLifetimeExtender()
        extender.setCallback(callback)
        entry1 = NotificationEntryBuilder().setId(1).build()
        entry2 = NotificationEntryBuilder().setId(2).build()
    }

    @Test
    fun testName() {
        assertThat(extender.name).isEqualTo("Testable")
    }

    @Test
    fun testNoExtend() {
        `when`(shouldExtend.test(entry1)).thenReturn(false)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(extender.isExtending(entry1.key)).isFalse()
        verify(onStarted, never()).accept(entry1)
        verify(onCanceled, never()).accept(entry1)
    }

    @Test
    fun testExtendThenCancelForRepost() {
        `when`(shouldExtend.test(entry1)).thenReturn(true)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted).accept(entry1)
        verify(onCanceled, never()).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isTrue()
        extender.cancelLifetimeExtension(entry1)
        verify(onCanceled).accept(entry1)
    }

    @Test
    fun testExtendThenCancel_thenEndDoesNothing() {
        testExtendThenCancelForRepost()
        assertThat(extender.isExtending(entry1.key)).isFalse()

        extender.endLifetimeExtension(entry1.key)
        extender.endLifetimeExtensionAfterDelay(entry1.key, 1000)
        verify(callback, never()).onEndLifetimeExtension(any(), any())
        verify(mainHandler, never()).postDelayed(any(), anyLong())
    }

    @Test
    fun testExtendThenEnd() {
        `when`(shouldExtend.test(entry1)).thenReturn(true)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isTrue()
        extender.endLifetimeExtension(entry1.key)
        verify(callback).onEndLifetimeExtension(extender, entry1)
        verify(onCanceled, never()).accept(entry1)
    }

    @Test
    fun testExtendThenEndAfterDelay() {
        `when`(shouldExtend.test(entry1)).thenReturn(true)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isTrue()

        // Call the method and capture the posted runnable
        extender.endLifetimeExtensionAfterDelay(entry1.key, 1234)
        val runnable = withArgCaptor<Runnable> {
            verify(mainHandler).postDelayed(capture(), eq(1234.toLong()))
        }
        assertThat(extender.isExtending(entry1.key)).isTrue()
        verify(callback, never()).onEndLifetimeExtension(any(), any())

        // now run the posted runnable and ensure it works as expected
        runnable.run()
        verify(callback).onEndLifetimeExtension(extender, entry1)
        assertThat(extender.isExtending(entry1.key)).isFalse()
        verify(onCanceled, never()).accept(entry1)
    }

    @Test
    fun testExtendThenEndAll() {
        `when`(shouldExtend.test(entry1)).thenReturn(true)
        `when`(shouldExtend.test(entry2)).thenReturn(true)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isTrue()
        assertThat(extender.isExtending(entry2.key)).isFalse()
        assertThat(extender.maybeExtendLifetime(entry2, 0)).isTrue()
        verify(onStarted).accept(entry2)
        assertThat(extender.isExtending(entry1.key)).isTrue()
        assertThat(extender.isExtending(entry2.key)).isTrue()
        extender.endAllLifetimeExtensions()
        verify(callback).onEndLifetimeExtension(extender, entry1)
        verify(callback).onEndLifetimeExtension(extender, entry2)
        verify(onCanceled, never()).accept(entry1)
        verify(onCanceled, never()).accept(entry2)
    }

    @Test
    fun testExtendWithinEndCanReExtend() {
        `when`(shouldExtend.test(entry1)).thenReturn(true)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted, times(1)).accept(entry1)

        `when`(callback.onEndLifetimeExtension(extender, entry1)).thenAnswer {
            assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        }
        extender.endLifetimeExtension(entry1.key)
        verify(onStarted, times(2)).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isTrue()
    }

    @Test
    fun testExtendWithinEndCanNotReExtend() {
        `when`(shouldExtend.test(entry1)).thenReturn(true, false)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted, times(1)).accept(entry1)

        `when`(callback.onEndLifetimeExtension(extender, entry1)).thenAnswer {
            assertThat(extender.maybeExtendLifetime(entry1, 0)).isFalse()
        }
        extender.endLifetimeExtension(entry1.key)
        verify(onStarted, times(1)).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isFalse()
    }

    @Test
    fun testExtendWithinEndAllCanReExtend() {
        `when`(shouldExtend.test(entry1)).thenReturn(true)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted, times(1)).accept(entry1)

        `when`(callback.onEndLifetimeExtension(extender, entry1)).thenAnswer {
            assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        }
        extender.endAllLifetimeExtensions()
        verify(onStarted, times(2)).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isTrue()
    }

    @Test
    fun testExtendWithinEndAllCanNotReExtend() {
        `when`(shouldExtend.test(entry1)).thenReturn(true, false)
        assertThat(extender.maybeExtendLifetime(entry1, 0)).isTrue()
        verify(onStarted, times(1)).accept(entry1)

        `when`(callback.onEndLifetimeExtension(extender, entry1)).thenAnswer {
            assertThat(extender.maybeExtendLifetime(entry1, 0)).isFalse()
        }
        extender.endAllLifetimeExtensions()
        verify(onStarted, times(1)).accept(entry1)
        assertThat(extender.isExtending(entry1.key)).isFalse()
    }

    inner class TestableSelfTrackingLifetimeExtender(debug: Boolean = false) :
            SelfTrackingLifetimeExtender("Test", "Testable", debug, mainHandler) {

        override fun queryShouldExtendLifetime(entry: NotificationEntry) =
                shouldExtend.test(entry)

        override fun onStartedLifetimeExtension(entry: NotificationEntry) {
            onStarted.accept(entry)
        }

        override fun onCanceledLifetimeExtension(entry: NotificationEntry) {
            onCanceled.accept(entry)
        }
    }
}
