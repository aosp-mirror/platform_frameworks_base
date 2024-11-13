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
package com.android.systemui.util.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.dump.dumpManager
import com.android.systemui.testKosmos
import com.android.systemui.util.service.ObservableServiceConnection.DISCONNECT_REASON_DISCONNECTED
import com.android.systemui.util.time.fakeSystemClock
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersistentConnectionManagerTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val fakeClock = kosmos.fakeSystemClock
    private val fakeExecutor = kosmos.fakeExecutor

    private class Proxy {
        // Fake proxy class
    }

    private val connection: ObservableServiceConnection<Proxy> = mock()
    private val observer: Observer = mock()

    private val underTest: PersistentConnectionManager<Proxy> by lazy {
        PersistentConnectionManager(
            /* clock = */ fakeClock,
            /* bgExecutor = */ fakeExecutor,
            /* dumpManager = */ kosmos.dumpManager,
            /* dumpsysName = */ DUMPSYS_NAME,
            /* serviceConnection = */ connection,
            /* maxReconnectAttempts = */ MAX_RETRIES,
            /* baseReconnectDelayMs = */ RETRY_DELAY_MS,
            /* minConnectionDurationMs = */ CONNECTION_MIN_DURATION_MS,
            /* observer = */ observer
        )
    }

    /** Validates initial connection. */
    @Test
    fun testConnect() {
        underTest.start()
        captureCallbackAndVerifyBind(connection).onConnected(connection, mock<Proxy>())
    }

    /** Ensures reconnection on disconnect. */
    @Test
    fun testExponentialRetryOnDisconnect() {
        underTest.start()

        // IF service is connected...
        val captor = argumentCaptor<ObservableServiceConnection.Callback<Proxy>>()
        verify(connection, times(1)).bind()
        verify(connection).addCallback(captor.capture())
        val callback = captor.lastValue
        callback.onConnected(connection, mock<Proxy>())

        // ...AND service becomes disconnected within CONNECTION_MIN_DURATION_MS
        callback.onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)

        // THEN verify we retry to bind after the retry delay. (RETRY #1)
        verify(connection, times(1)).bind()
        fakeClock.advanceTime(RETRY_DELAY_MS.toLong())
        verify(connection, times(2)).bind()

        // IF service becomes disconnected for a second time after first retry...
        callback.onConnected(connection, mock<Proxy>())
        callback.onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)

        // THEN verify we retry after a longer delay of 2 * RETRY_DELAY_MS (RETRY #2)
        fakeClock.advanceTime(RETRY_DELAY_MS.toLong())
        verify(connection, times(2)).bind()
        fakeClock.advanceTime(RETRY_DELAY_MS.toLong())
        verify(connection, times(3)).bind()

        // IF service becomes disconnected for a third time after the second retry...
        callback.onConnected(connection, mock<Proxy>())
        callback.onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)

        // THEN verify we retry after a longer delay of 4 * RETRY_DELAY_MS (RETRY #3)
        fakeClock.advanceTime(3 * RETRY_DELAY_MS.toLong())
        verify(connection, times(3)).bind()
        fakeClock.advanceTime(RETRY_DELAY_MS.toLong())
        verify(connection, times(4)).bind()
    }

    @Test
    fun testDoesNotRetryAfterMaxRetries() {
        underTest.start()

        val captor = argumentCaptor<ObservableServiceConnection.Callback<Proxy>>()
        verify(connection).addCallback(captor.capture())
        val callback = captor.lastValue

        // IF we retry MAX_TRIES times...
        for (attemptCount in 0 until MAX_RETRIES + 1) {
            verify(connection, times(attemptCount + 1)).bind()
            callback.onConnected(connection, mock<Proxy>())
            callback.onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)
            fakeClock.advanceTime(Math.scalb(RETRY_DELAY_MS.toDouble(), attemptCount).toLong())
        }

        // THEN we should not retry again after the last attempt.
        fakeExecutor.advanceClockToLast()
        verify(connection, times(MAX_RETRIES + 1)).bind()
    }

    @Test
    fun testEnsureNoRetryIfServiceNeverConnectsAfterRetry() {
        underTest.start()

        with(captureCallbackAndVerifyBind(connection)) {
            // IF service initially connects and then disconnects...
            onConnected(connection, mock<Proxy>())
            onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)
            fakeExecutor.advanceClockToLast()
            fakeExecutor.runAllReady()

            // ...AND we retry once.
            verify(connection, times(1)).bind()

            // ...AND service disconnects after initial retry without ever connecting again.
            onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)
            fakeExecutor.advanceClockToLast()
            fakeExecutor.runAllReady()

            // THEN verify another retry is not triggered.
            verify(connection, times(1)).bind()
        }
    }

    @Test
    fun testEnsureNoRetryIfServiceNeverInitiallyConnects() {
        underTest.start()

        with(captureCallbackAndVerifyBind(connection)) {
            // IF service never connects and we just receive the disconnect signal...
            onDisconnected(connection, DISCONNECT_REASON_DISCONNECTED)
            fakeExecutor.advanceClockToLast()
            fakeExecutor.runAllReady()

            // THEN do not retry
            verify(connection, never()).bind()
        }
    }

    /** Ensures manual unbind does not reconnect. */
    @Test
    fun testStopDoesNotReconnect() {
        underTest.start()

        val connectionCallbackCaptor = argumentCaptor<ObservableServiceConnection.Callback<Proxy>>()
        verify(connection).addCallback(connectionCallbackCaptor.capture())
        verify(connection).bind()
        clearInvocations(connection)

        underTest.stop()
        fakeExecutor.advanceClockToNext()
        fakeExecutor.runAllReady()
        verify(connection, never()).bind()
    }

    /** Ensures rebind on package change. */
    @Test
    fun testAttemptOnPackageChange() {
        underTest.start()

        verify(connection).bind()

        val callbackCaptor = argumentCaptor<Observer.Callback>()
        captureCallbackAndVerifyBind(connection).onConnected(connection, mock<Proxy>())

        verify(observer).addCallback(callbackCaptor.capture())
        callbackCaptor.lastValue.onSourceChanged()
        verify(connection).bind()
    }

    @Test
    fun testAddConnectionCallback() {
        val connectionCallback: ObservableServiceConnection.Callback<Proxy> = mock()
        underTest.addConnectionCallback(connectionCallback)
        verify(connection).addCallback(connectionCallback)
    }

    @Test
    fun testRemoveConnectionCallback() {
        val connectionCallback: ObservableServiceConnection.Callback<Proxy> = mock()
        underTest.removeConnectionCallback(connectionCallback)
        verify(connection).removeCallback(connectionCallback)
    }

    /** Helper method to capture the [ObservableServiceConnection.Callback] */
    private fun captureCallbackAndVerifyBind(
        mConnection: ObservableServiceConnection<Proxy>,
    ): ObservableServiceConnection.Callback<Proxy> {

        val connectionCallbackCaptor = argumentCaptor<ObservableServiceConnection.Callback<Proxy>>()
        verify(mConnection).addCallback(connectionCallbackCaptor.capture())
        verify(mConnection).bind()
        clearInvocations(mConnection)

        return connectionCallbackCaptor.lastValue
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000
        private const val CONNECTION_MIN_DURATION_MS = 5000
        private const val DUMPSYS_NAME = "dumpsys_name"
    }
}
