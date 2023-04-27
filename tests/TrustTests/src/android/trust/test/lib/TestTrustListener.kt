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

package android.trust.test.lib

import android.app.trust.TrustManager.TrustListener

/**
 * A listener that has default empty implementations for all [TrustListener] methods.
 */
open class TestTrustListener : TrustListener {
        override fun onTrustChanged(
            enabled: Boolean,
            newlyUnlocked: Boolean,
            userId: Int,
            flags: Int,
            trustGrantedMessages: MutableList<String>
        ) {
        }

        override fun onTrustManagedChanged(enabled: Boolean, userId: Int) {
        }

        override fun onTrustError(message: CharSequence) {
        }

        override fun onEnabledTrustAgentsChanged(userId: Int) {
        }

        override fun onIsActiveUnlockRunningChanged(isRunning: Boolean, userId: Int) {
        }
}
