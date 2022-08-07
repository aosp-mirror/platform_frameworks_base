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
package com.android.systemui.flags

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

/**
 * NOTE: This test is for the version of FeatureFlagManager in src-release, which should not allow
 * overriding, and should never return any value other than the one provided as the default.
 */
@SmallTest
class FlagManagerTest : SysuiTestCase() {
    private lateinit var mFlagManager: FlagManager

    @Mock private lateinit var mMockContext: Context
    @Mock private lateinit var mFlagSettingsHelper: FlagSettingsHelper
    @Mock private lateinit var mHandler: Handler

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mFlagManager = FlagManager(mMockContext, mFlagSettingsHelper, mHandler)
    }

    @Test
    fun testContentObserverAddedAndRemoved() {
        val listener1 = mock<FlagListenable.Listener>()
        val listener2 = mock<FlagListenable.Listener>()

        // no interactions before adding listener
        verifyNoMoreInteractions(mFlagSettingsHelper)

        // adding the first listener registers the observer
        mFlagManager.addListener(ReleasedFlag(1), listener1)
        val observer = withArgCaptor<ContentObserver> {
            verify(mFlagSettingsHelper).registerContentObserver(any(), any(), capture())
        }
        verifyNoMoreInteractions(mFlagSettingsHelper)

        // adding another listener does nothing
        mFlagManager.addListener(ReleasedFlag(2), listener2)
        verifyNoMoreInteractions(mFlagSettingsHelper)

        // removing the original listener does nothing with second one still present
        mFlagManager.removeListener(listener1)
        verifyNoMoreInteractions(mFlagSettingsHelper)

        // removing the final listener unregisters the observer
        mFlagManager.removeListener(listener2)
        verify(mFlagSettingsHelper).unregisterContentObserver(eq(observer))
        verifyNoMoreInteractions(mFlagSettingsHelper)
    }

    @Test
    fun testObserverClearsCache() {
        val listener = mock<FlagListenable.Listener>()
        val clearCacheAction = mock<Consumer<Int>>()
        mFlagManager.clearCacheAction = clearCacheAction
        mFlagManager.addListener(ReleasedFlag(1), listener)
        val observer = withArgCaptor<ContentObserver> {
            verify(mFlagSettingsHelper).registerContentObserver(any(), any(), capture())
        }
        observer.onChange(false, flagUri(1))
        verify(clearCacheAction).accept(eq(1))
    }

    @Test
    fun testObserverInvokesListeners() {
        val listener1 = mock<FlagListenable.Listener>()
        val listener10 = mock<FlagListenable.Listener>()
        mFlagManager.addListener(ReleasedFlag(1), listener1)
        mFlagManager.addListener(ReleasedFlag(10), listener10)
        val observer = withArgCaptor<ContentObserver> {
            verify(mFlagSettingsHelper).registerContentObserver(any(), any(), capture())
        }
        observer.onChange(false, flagUri(1))
        val flagEvent1 = withArgCaptor<FlagListenable.FlagEvent> {
            verify(listener1).onFlagChanged(capture())
        }
        assertThat(flagEvent1.flagId).isEqualTo(1)
        verifyNoMoreInteractions(listener1, listener10)

        observer.onChange(false, flagUri(10))
        val flagEvent10 = withArgCaptor<FlagListenable.FlagEvent> {
            verify(listener10).onFlagChanged(capture())
        }
        assertThat(flagEvent10.flagId).isEqualTo(10)
        verifyNoMoreInteractions(listener1, listener10)
    }

    fun flagUri(id: Int): Uri = Uri.parse("content://settings/system/systemui/flags/$id")

    @Test
    fun testOnlySpecificFlagListenerIsInvoked() {
        val listener1 = mock<FlagListenable.Listener>()
        val listener10 = mock<FlagListenable.Listener>()
        mFlagManager.addListener(ReleasedFlag(1), listener1)
        mFlagManager.addListener(ReleasedFlag(10), listener10)

        mFlagManager.dispatchListenersAndMaybeRestart(1, null)
        val flagEvent1 = withArgCaptor<FlagListenable.FlagEvent> {
            verify(listener1).onFlagChanged(capture())
        }
        assertThat(flagEvent1.flagId).isEqualTo(1)
        verifyNoMoreInteractions(listener1, listener10)

        mFlagManager.dispatchListenersAndMaybeRestart(10, null)
        val flagEvent10 = withArgCaptor<FlagListenable.FlagEvent> {
            verify(listener10).onFlagChanged(capture())
        }
        assertThat(flagEvent10.flagId).isEqualTo(10)
        verifyNoMoreInteractions(listener1, listener10)
    }

    @Test
    fun testSameListenerCanBeUsedForMultipleFlags() {
        val listener = mock<FlagListenable.Listener>()
        mFlagManager.addListener(ReleasedFlag(1), listener)
        mFlagManager.addListener(ReleasedFlag(10), listener)

        mFlagManager.dispatchListenersAndMaybeRestart(1, null)
        val flagEvent1 = withArgCaptor<FlagListenable.FlagEvent> {
            verify(listener).onFlagChanged(capture())
        }
        assertThat(flagEvent1.flagId).isEqualTo(1)
        verifyNoMoreInteractions(listener)

        mFlagManager.dispatchListenersAndMaybeRestart(10, null)
        val flagEvent10 = withArgCaptor<FlagListenable.FlagEvent> {
            verify(listener, times(2)).onFlagChanged(capture())
        }
        assertThat(flagEvent10.flagId).isEqualTo(10)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testRestartWithNoListeners() {
        val restartAction = mock<Consumer<Boolean>>()
        mFlagManager.dispatchListenersAndMaybeRestart(1, restartAction)
        verify(restartAction).accept(eq(false))
        verifyNoMoreInteractions(restartAction)
    }

    @Test
    fun testListenerCanSuppressRestart() {
        val restartAction = mock<Consumer<Boolean>>()
        mFlagManager.addListener(ReleasedFlag(1)) { event ->
            event.requestNoRestart()
        }
        mFlagManager.dispatchListenersAndMaybeRestart(1, restartAction)
        verify(restartAction).accept(eq(true))
        verifyNoMoreInteractions(restartAction)
    }

    @Test
    fun testListenerOnlySuppressesRestartForOwnFlag() {
        val restartAction = mock<Consumer<Boolean>>()
        mFlagManager.addListener(ReleasedFlag(10)) { event ->
            event.requestNoRestart()
        }
        mFlagManager.dispatchListenersAndMaybeRestart(1, restartAction)
        verify(restartAction).accept(eq(false))
        verifyNoMoreInteractions(restartAction)
    }

    @Test
    fun testRestartWhenNotAllListenersRequestSuppress() {
        val restartAction = mock<Consumer<Boolean>>()
        mFlagManager.addListener(ReleasedFlag(10)) { event ->
            event.requestNoRestart()
        }
        mFlagManager.addListener(ReleasedFlag(10)) {
            // do not request
        }
        mFlagManager.dispatchListenersAndMaybeRestart(1, restartAction)
        verify(restartAction).accept(eq(false))
        verifyNoMoreInteractions(restartAction)
    }

    @Test
    fun testReadBooleanFlag() {
        // test that null string returns null
        whenever(mFlagSettingsHelper.getString(any())).thenReturn(null)
        assertThat(mFlagManager.readFlagValue(1, BooleanFlagSerializer)).isNull()

        // test that empty string returns null
        whenever(mFlagSettingsHelper.getString(any())).thenReturn("")
        assertThat(mFlagManager.readFlagValue(1, BooleanFlagSerializer)).isNull()

        // test false
        whenever(mFlagSettingsHelper.getString(any()))
            .thenReturn("{\"type\":\"boolean\",\"value\":false}")
        assertThat(mFlagManager.readFlagValue(1, BooleanFlagSerializer)).isFalse()

        // test true
        whenever(mFlagSettingsHelper.getString(any()))
            .thenReturn("{\"type\":\"boolean\",\"value\":true}")
        assertThat(mFlagManager.readFlagValue(1, BooleanFlagSerializer)).isTrue()

        // Reading a value of a different type should just return null
        whenever(mFlagSettingsHelper.getString(any()))
            .thenReturn("{\"type\":\"string\",\"value\":\"foo\"}")
        assertThat(mFlagManager.readFlagValue(1, BooleanFlagSerializer)).isNull()

        // Reading a value that isn't json should throw an exception
        assertThrows(InvalidFlagStorageException::class.java) {
            whenever(mFlagSettingsHelper.getString(any())).thenReturn("1")
            mFlagManager.readFlagValue(1, BooleanFlagSerializer)
        }
    }

    @Test
    fun testSerializeBooleanFlag() {
        // test false
        assertThat(BooleanFlagSerializer.toSettingsData(false))
            .isEqualTo("{\"type\":\"boolean\",\"value\":false}")

        // test true
        assertThat(BooleanFlagSerializer.toSettingsData(true))
            .isEqualTo("{\"type\":\"boolean\",\"value\":true}")
    }

    @Test
    fun testReadStringFlag() {
        // test that null string returns null
        whenever(mFlagSettingsHelper.getString(any())).thenReturn(null)
        assertThat(mFlagManager.readFlagValue(1, StringFlagSerializer)).isNull()

        // test that empty string returns null
        whenever(mFlagSettingsHelper.getString(any())).thenReturn("")
        assertThat(mFlagManager.readFlagValue(1, StringFlagSerializer)).isNull()

        // test json with the empty string value returns empty string
        whenever(mFlagSettingsHelper.getString(any()))
            .thenReturn("{\"type\":\"string\",\"value\":\"\"}")
        assertThat(mFlagManager.readFlagValue(1, StringFlagSerializer)).isEqualTo("")

        // test string with value is returned
        whenever(mFlagSettingsHelper.getString(any()))
            .thenReturn("{\"type\":\"string\",\"value\":\"foo\"}")
        assertThat(mFlagManager.readFlagValue(1, StringFlagSerializer)).isEqualTo("foo")

        // Reading a value of a different type should just return null
        whenever(mFlagSettingsHelper.getString(any()))
            .thenReturn("{\"type\":\"boolean\",\"value\":false}")
        assertThat(mFlagManager.readFlagValue(1, StringFlagSerializer)).isNull()

        // Reading a value that isn't json should throw an exception
        assertThrows(InvalidFlagStorageException::class.java) {
            whenever(mFlagSettingsHelper.getString(any())).thenReturn("1")
            mFlagManager.readFlagValue(1, StringFlagSerializer)
        }
    }

    @Test
    fun testSerializeStringFlag() {
        // test empty string
        assertThat(StringFlagSerializer.toSettingsData(""))
            .isEqualTo("{\"type\":\"string\",\"value\":\"\"}")

        // test string "foo"
        assertThat(StringFlagSerializer.toSettingsData("foo"))
            .isEqualTo("{\"type\":\"string\",\"value\":\"foo\"}")
    }
}
