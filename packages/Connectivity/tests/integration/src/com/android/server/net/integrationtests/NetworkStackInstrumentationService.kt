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

import android.app.Service
import android.content.Intent
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.ArrayList
import kotlin.test.fail

/**
 * An instrumentation interface for the NetworkStack that allows controlling behavior to
 * facilitate integration tests.
 */
class NetworkStackInstrumentationService : Service() {
    override fun onBind(intent: Intent) = InstrumentationConnector.asBinder()

    object InstrumentationConnector : INetworkStackInstrumentation.Stub() {
        private val httpResponses = ConcurrentHashMap<String, ConcurrentLinkedQueue<HttpResponse>>()
                .run {
                    withDefault { key -> getOrPut(key) { ConcurrentLinkedQueue() } }
                }
        private val httpRequestUrls = Collections.synchronizedList(ArrayList<String>())

        /**
         * Called when an HTTP request is being processed by NetworkMonitor. Returns the response
         * that should be simulated.
         */
        fun processRequest(url: URL): HttpResponse {
            val strUrl = url.toString()
            httpRequestUrls.add(strUrl)
            return httpResponses[strUrl]?.poll()
                    ?: fail("No mocked response for request: $strUrl. " +
                            "Mocked URL keys are: ${httpResponses.keys}")
        }

        /**
         * Clear all state of this connector. This is intended for use between two tests, so all
         * state should be reset as if the connector was just created.
         */
        override fun clearAllState() {
            httpResponses.clear()
            httpRequestUrls.clear()
        }

        /**
         * Add a response to a future HTTP request.
         *
         * <p>For any subsequent HTTP/HTTPS query, the first response with a matching URL will be
         * used to mock the query response.
         *
         * <p>All requests that are expected to be sent must have a mock response: if an unexpected
         * request is seen, the test will fail.
         */
        override fun addHttpResponse(response: HttpResponse) {
            httpResponses.getValue(response.requestUrl).add(response)
        }

        /**
         * Get the ordered list of request URLs that have been sent by NetworkMonitor, and were
         * answered based on mock responses.
         */
        override fun getRequestUrls(): List<String> {
            return ArrayList(httpRequestUrls)
        }
    }
}