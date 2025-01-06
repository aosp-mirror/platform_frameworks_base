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

package com.android.systemui.statusbar.pipeline.shared

import com.android.systemui.kosmos.Kosmos

val Kosmos.connectivityConstants by Kosmos.Fixture { FakeConnectivityConstnants() }

class FakeConnectivityConstnants : ConnectivityConstants {
    override var hasDataCapabilities: Boolean = true

    override var shouldShowActivityConfig: Boolean = false
}

val ConnectivityConstants.fake
    get() = this as FakeConnectivityConstnants
