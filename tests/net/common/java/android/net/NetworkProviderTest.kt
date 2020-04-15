/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net

import android.app.Instrumentation
import android.content.Context
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.net.NetworkProviderTest.TestNetworkCallback.CallbackEntry.OnUnavailable
import android.net.NetworkProviderTest.TestNetworkProvider.CallbackEntry.OnNetworkRequested
import android.net.NetworkProviderTest.TestNetworkProvider.CallbackEntry.OnNetworkRequestWithdrawn
import androidx.test.InstrumentationRegistry
import com.android.testutils.ArrayTrackRecord
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val DEFAULT_TIMEOUT_MS = 5000L
private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()
private val context: Context get() = InstrumentationRegistry.getContext()
private val PROVIDER_NAME = "NetworkProviderTest"

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.Q)
class NetworkProviderTest {
    private val mCm = context.getSystemService(ConnectivityManager::class.java)
    private val mHandlerThread = HandlerThread("${javaClass.simpleName} handler thread")

    @Before
    fun setUp() {
        instrumentation.getUiAutomation().adoptShellPermissionIdentity()
        mHandlerThread.start()
    }

    @After
    fun tearDown() {
        mHandlerThread.quitSafely()
        instrumentation.getUiAutomation().dropShellPermissionIdentity()
    }

    private class TestNetworkProvider(context: Context, looper: Looper) :
            NetworkProvider(context, looper, PROVIDER_NAME) {
        private val seenEvents = ArrayTrackRecord<CallbackEntry>().newReadHead()

        sealed class CallbackEntry {
            data class OnNetworkRequested(
                val request: NetworkRequest,
                val score: Int,
                val id: Int
            ) : CallbackEntry()
            data class OnNetworkRequestWithdrawn(val request: NetworkRequest) : CallbackEntry()
        }

        override fun onNetworkRequested(request: NetworkRequest, score: Int, id: Int) {
            seenEvents.add(OnNetworkRequested(request, score, id))
        }

        override fun onNetworkRequestWithdrawn(request: NetworkRequest) {
            seenEvents.add(OnNetworkRequestWithdrawn(request))
        }

        inline fun <reified T : CallbackEntry> expectCallback(
            crossinline predicate: (T) -> Boolean
        ) = seenEvents.poll(DEFAULT_TIMEOUT_MS) { it is T && predicate(it) }
    }

    private fun createNetworkProvider(): TestNetworkProvider {
        return TestNetworkProvider(context, mHandlerThread.looper)
    }

    @Test
    fun testOnNetworkRequested() {
        val provider = createNetworkProvider()
        assertEquals(provider.getProviderId(), NetworkProvider.ID_NONE)
        mCm.registerNetworkProvider(provider)
        assertNotEquals(provider.getProviderId(), NetworkProvider.ID_NONE)

        val specifier = StringNetworkSpecifier(UUID.randomUUID().toString())
        val nr: NetworkRequest = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(specifier)
                .build()
        val cb = ConnectivityManager.NetworkCallback()
        mCm.requestNetwork(nr, cb)
        provider.expectCallback<OnNetworkRequested>() {
            callback -> callback.request.getNetworkSpecifier() == specifier &&
            callback.request.hasTransport(TRANSPORT_TEST)
        }

        mCm.unregisterNetworkCallback(cb)
        provider.expectCallback<OnNetworkRequestWithdrawn>() {
            callback -> callback.request.getNetworkSpecifier() == specifier &&
            callback.request.hasTransport(TRANSPORT_TEST)
        }
        mCm.unregisterNetworkProvider(provider)
        // Provider id should be ID_NONE after unregister network provider
        assertEquals(provider.getProviderId(), NetworkProvider.ID_NONE)
        // unregisterNetworkProvider should not crash even if it's called on an
        // already unregistered provider.
        mCm.unregisterNetworkProvider(provider)
    }

    private class TestNetworkCallback : ConnectivityManager.NetworkCallback() {
        private val seenEvents = ArrayTrackRecord<CallbackEntry>().newReadHead()
        sealed class CallbackEntry {
            object OnUnavailable : CallbackEntry()
        }

        override fun onUnavailable() {
            seenEvents.add(OnUnavailable)
        }

        inline fun <reified T : CallbackEntry> expectCallback(
            crossinline predicate: (T) -> Boolean
        ) = seenEvents.poll(DEFAULT_TIMEOUT_MS) { it is T && predicate(it) }
    }

    @Test
    fun testDeclareNetworkRequestUnfulfillable() {
        val provider = createNetworkProvider()
        mCm.registerNetworkProvider(provider)

        val specifier = StringNetworkSpecifier(UUID.randomUUID().toString())
        val nr: NetworkRequest = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(specifier)
                .build()

        val cb = TestNetworkCallback()
        mCm.requestNetwork(nr, cb)
        provider.declareNetworkRequestUnfulfillable(nr)
        cb.expectCallback<OnUnavailable>() { nr.getNetworkSpecifier() == specifier }
        mCm.unregisterNetworkProvider(provider)
    }
}