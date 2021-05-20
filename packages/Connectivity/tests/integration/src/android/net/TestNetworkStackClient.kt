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

package android.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.android.server.net.integrationtests.TestNetworkStackService
import org.mockito.Mockito.any
import org.mockito.Mockito.spy
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import kotlin.test.fail

const val TEST_ACTION_SUFFIX = ".Test"

class TestNetworkStackClient(context: Context) : NetworkStackClient(TestDependencies(context)) {
    // TODO: consider switching to TrackRecord for more expressive checks
    private val lastCallbacks = HashMap<Network, INetworkMonitorCallbacks>()

    private class TestDependencies(private val context: Context) : Dependencies {
        override fun addToServiceManager(service: IBinder) = Unit
        override fun checkCallerUid() = Unit

        override fun getConnectivityModuleConnector(): ConnectivityModuleConnector {
            return ConnectivityModuleConnector { _, _, _, inSystemProcess ->
                getNetworkStackIntent(inSystemProcess)
            }.also { it.init(context) }
        }

        private fun getNetworkStackIntent(inSystemProcess: Boolean): Intent? {
            // Simulate out-of-system-process config: in-process service not found (null intent)
            if (inSystemProcess) return null
            val intent = Intent(INetworkStackConnector::class.qualifiedName + TEST_ACTION_SUFFIX)
            val serviceName = TestNetworkStackService::class.qualifiedName
                    ?: fail("TestNetworkStackService name not found")
            intent.component = ComponentName(context.packageName, serviceName)
            return intent
        }
    }

    // base may be an instance of an inaccessible subclass, so non-spyable.
    // Use a known open class that delegates to the original instance for all methods except
    // asBinder. asBinder needs to use its own non-delegated implementation as otherwise it would
    // return a binder token to a class that is not spied on.
    open class NetworkMonitorCallbacksWrapper(private val base: INetworkMonitorCallbacks) :
            INetworkMonitorCallbacks.Stub(), INetworkMonitorCallbacks by base {
        // asBinder is implemented by both base class and delegate: specify explicitly
        override fun asBinder(): IBinder {
            return super.asBinder()
        }
    }

    override fun makeNetworkMonitor(network: Network, name: String?, cb: INetworkMonitorCallbacks) {
        val cbSpy = spy(NetworkMonitorCallbacksWrapper(cb))
        lastCallbacks[network] = cbSpy
        super.makeNetworkMonitor(network, name, cbSpy)
    }

    fun verifyNetworkMonitorCreated(network: Network, timeoutMs: Long) {
        val cb = lastCallbacks[network]
                ?: fail("NetworkMonitor for network $network not requested")
        verify(cb, timeout(timeoutMs)).onNetworkMonitorCreated(any())
    }
}