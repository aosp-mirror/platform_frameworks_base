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

package com.android.server

import java.util.concurrent.atomic.AtomicInteger

/**
 * A [NetIdManager] that generates ID starting from [NetIdManager.MAX_NET_ID] and decreasing, rather
 * than starting from [NetIdManager.MIN_NET_ID] and increasing.
 *
 * Useful for testing ConnectivityService, to minimize the risk of test ConnectivityService netIDs
 * overlapping with netIDs used by the real ConnectivityService on the device.
 *
 * IDs may still overlap if many networks have been used on the device (so the "real" netIDs
 * are close to MAX_NET_ID), but this is typically not the case when running unit tests. Also, there
 * is no way to fully solve the overlap issue without altering ID allocation in non-test code, as
 * the real ConnectivityService could start using netIds that have been used by the test in the
 * past.
 */
class TestNetIdManager : NetIdManager() {
    private val nextId = AtomicInteger(MAX_NET_ID)
    override fun reserveNetId() = nextId.decrementAndGet()
    override fun releaseNetId(id: Int) = Unit
    fun peekNextNetId() = nextId.get() - 1
}
