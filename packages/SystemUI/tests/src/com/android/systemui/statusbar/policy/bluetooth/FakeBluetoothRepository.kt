/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy.bluetooth

import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/**
 * Fake [BluetoothRepository] that delegates to the real [BluetoothRepositoryImpl].
 *
 * We only need this because [BluetoothRepository] is called from Java, which can't use [TestScope],
 * [StandardTestDispatcher], etc. to create a test version of the repo. This class uses those test
 * items under-the-hood so Java classes can indirectly access them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeBluetoothRepository(localBluetoothManager: LocalBluetoothManager) : BluetoothRepository {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)

    private val impl =
        BluetoothRepositoryImpl(testScope.backgroundScope, dispatcher, localBluetoothManager)

    override fun fetchConnectionStatusInBackground(
        currentDevices: Collection<CachedBluetoothDevice>,
        callback: ConnectionStatusFetchedCallback
    ) {
        impl.fetchConnectionStatusInBackground(currentDevices, callback)
        scheduler.runCurrent()
    }
}
