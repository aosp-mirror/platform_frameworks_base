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
 * limitations under the License
 */

package com.android.server.appwidget

import android.content.ComponentName
import com.android.server.appwidget.AppWidgetServiceImpl.ApiCounter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiCounterTest {
    private companion object {
        const val RESET_INTERVAL_MS = 10L
        const val MAX_CALLS_PER_INTERVAL = 2
        const val MAX_PROVIDERS = 10
    }

    private var currentTime = 0L

    private val id =
        AppWidgetServiceImpl.ProviderId(
            /* uid= */ 123,
            ComponentName("com.android.server.appwidget", "FakeProviderClass")
        )
    private val counter = ApiCounter(RESET_INTERVAL_MS, MAX_CALLS_PER_INTERVAL, MAX_PROVIDERS) {
        currentTime
    }

    @Test
    fun tryApiCall() {
        for (i in 0 until MAX_CALLS_PER_INTERVAL) {
            assertThat(counter.tryApiCall(id)).isTrue()
        }
        assertThat(counter.tryApiCall(id)).isFalse()
        currentTime = 5L
        assertThat(counter.tryApiCall(id)).isFalse()
        currentTime = 11L
        assertThat(counter.tryApiCall(id)).isTrue()
    }

    @Test
    fun remove() {
        for (i in 0 until MAX_CALLS_PER_INTERVAL) {
            assertThat(counter.tryApiCall(id)).isTrue()
        }
        assertThat(counter.tryApiCall(id)).isFalse()
        // remove should cause the call count to be 0 on the next tryApiCall
        counter.remove(id)
        assertThat(counter.tryApiCall(id)).isTrue()
    }

    @Test
    fun maxProviders() {
        for (i in 0 until MAX_PROVIDERS) {
            for (j in 0 until MAX_CALLS_PER_INTERVAL) {
                assertThat(counter.tryApiCall(providerId(i))).isTrue()
            }
        }
        assertThat(counter.tryApiCall(providerId(MAX_PROVIDERS))).isFalse()
        // remove will allow another provider to be added
        counter.remove(providerId(0))
        assertThat(counter.tryApiCall(providerId(MAX_PROVIDERS))).isTrue()
    }

    private fun providerId(i: Int) =
        AppWidgetServiceImpl.ProviderId(/* uid= */ i, id.componentName)
}
