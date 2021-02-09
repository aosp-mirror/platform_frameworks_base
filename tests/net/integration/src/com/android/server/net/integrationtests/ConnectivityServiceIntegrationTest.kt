/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.net.integrationtests

import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Context.BIND_IMPORTANT
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.IDnsResolver
import android.net.INetd
import android.net.INetworkPolicyManager
import android.net.INetworkStatsService
import android.net.LinkProperties
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkRequest
import android.net.TestNetworkStackClient
import android.net.metrics.IpConnectivityLog
import android.os.ConditionVariable
import android.os.IBinder
import android.os.INetworkManagementService
import android.testing.TestableContext
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.ConnectivityService
import com.android.server.LocalServices
import com.android.server.NetworkAgentWrapper
import com.android.server.TestNetIdManager
import com.android.server.connectivity.DefaultNetworkMetrics
import com.android.server.connectivity.IpConnectivityMetrics
import com.android.server.connectivity.MockableSystemProperties
import com.android.server.connectivity.ProxyTracker
import com.android.server.net.NetworkPolicyManagerInternal
import com.android.testutils.TestableNetworkCallback
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.MockitoAnnotations
import org.mockito.Spy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

const val SERVICE_BIND_TIMEOUT_MS = 5_000L
const val TEST_TIMEOUT_MS = 1_000L

/**
 * Test that exercises an instrumented version of ConnectivityService against an instrumented
 * NetworkStack in a different test process.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityServiceIntegrationTest {
    // lateinit used here for mocks as they need to be reinitialized between each test and the test
    // should crash if they are used before being initialized.
    @Mock
    private lateinit var netManager: INetworkManagementService
    @Mock
    private lateinit var statsService: INetworkStatsService
    @Mock
    private lateinit var policyManager: INetworkPolicyManager
    @Mock
    private lateinit var log: IpConnectivityLog
    @Mock
    private lateinit var netd: INetd
    @Mock
    private lateinit var dnsResolver: IDnsResolver
    @Mock
    private lateinit var metricsLogger: IpConnectivityMetrics.Logger
    @Mock
    private lateinit var defaultMetrics: DefaultNetworkMetrics
    @Spy
    private var context = TestableContext(realContext)

    // lateinit for these three classes under test, as they should be reset to a different instance
    // for every test but should always be initialized before use (or the test should crash).
    private lateinit var networkStackClient: TestNetworkStackClient
    private lateinit var service: ConnectivityService
    private lateinit var cm: ConnectivityManager

    companion object {
        // lateinit for this binder token, as it must be initialized before any test code is run
        // and use of it before init should crash the test.
        private lateinit var nsInstrumentation: INetworkStackInstrumentation
        private val bindingCondition = ConditionVariable(false)

        private val realContext get() = InstrumentationRegistry.getInstrumentation().context

        private class InstrumentationServiceConnection : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i("TestNetworkStack", "Service connected")
                try {
                    if (service == null) fail("Error binding to NetworkStack instrumentation")
                    if (::nsInstrumentation.isInitialized) fail("Service already connected")
                    nsInstrumentation = INetworkStackInstrumentation.Stub.asInterface(service)
                } finally {
                    bindingCondition.open()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val intent = Intent(realContext, NetworkStackInstrumentationService::class.java)
            intent.action = INetworkStackInstrumentation::class.qualifiedName
            assertTrue(realContext.bindService(intent, InstrumentationServiceConnection(),
                    BIND_AUTO_CREATE or BIND_IMPORTANT),
                    "Error binding to instrumentation service")
            assertTrue(bindingCondition.block(SERVICE_BIND_TIMEOUT_MS),
                    "Timed out binding to instrumentation service " +
                            "after $SERVICE_BIND_TIMEOUT_MS ms")
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doReturn(defaultMetrics).`when`(metricsLogger).defaultNetworkMetrics()
        doNothing().`when`(context).sendStickyBroadcastAsUser(any(), any(), any())

        networkStackClient = TestNetworkStackClient(realContext)
        networkStackClient.init()
        networkStackClient.start()

        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal::class.java)
        LocalServices.addService(NetworkPolicyManagerInternal::class.java,
                mock(NetworkPolicyManagerInternal::class.java))

        service = TestConnectivityService(makeDependencies())
        cm = ConnectivityManager(context, service)
        context.addMockSystemService(Context.CONNECTIVITY_SERVICE, cm)

        service.systemReady()
    }

    private inner class TestConnectivityService(deps: Dependencies) : ConnectivityService(
            context, netManager, statsService, policyManager, dnsResolver, log, netd, deps)

    private fun makeDependencies(): ConnectivityService.Dependencies {
        val deps = spy(ConnectivityService.Dependencies())
        doReturn(networkStackClient).`when`(deps).networkStack
        doReturn(metricsLogger).`when`(deps).metricsLogger
        doReturn(mock(ProxyTracker::class.java)).`when`(deps).makeProxyTracker(any(), any())
        doReturn(mock(MockableSystemProperties::class.java)).`when`(deps).systemProperties
        doReturn(TestNetIdManager()).`when`(deps).makeNetIdManager()
        return deps
    }

    @After
    fun tearDown() {
        nsInstrumentation.clearAllState()
    }

    @Test
    fun testValidation() {
        val request = NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val testCallback = TestableNetworkCallback()

        cm.registerNetworkCallback(request, testCallback)
        nsInstrumentation.addHttpResponse(HttpResponse(
                "http://test.android.com",
                responseCode = 204, contentLength = 42, redirectUrl = null))
        nsInstrumentation.addHttpResponse(HttpResponse(
                "https://secure.test.android.com",
                responseCode = 204, contentLength = 42, redirectUrl = null))

        val na = NetworkAgentWrapper(TRANSPORT_CELLULAR, LinkProperties(), null /* ncTemplate */,
                context)
        networkStackClient.verifyNetworkMonitorCreated(na.network, TEST_TIMEOUT_MS)

        na.addCapability(NET_CAPABILITY_INTERNET)
        na.connect()

        testCallback.expectAvailableThenValidatedCallbacks(na.network, TEST_TIMEOUT_MS)
        assertEquals(2, nsInstrumentation.getRequestUrls().size)
    }
}
