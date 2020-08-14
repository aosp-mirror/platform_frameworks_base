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

package com.android.server.pm.test.intent.verify

data class StartActivityParams(
    val uri: String,
    val expected: List<String>,
    val withBrowsers: Boolean = false,
    override val methodName: String = "verifyActivityStart"
) : IntentVerifyTestParams {
    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_EXPECTED = "expected"
        private const val KEY_BROWSER = "browser"

        fun fromArgs(args: Map<String, String>) = StartActivityParams(
                args.getValue(KEY_URI),
                args.getValue(KEY_EXPECTED).split(","),
                args.getValue(KEY_BROWSER).toBoolean()
        )
    }

    constructor(
        uri: String,
        expected: String,
        withBrowsers: Boolean = false
    ) : this(uri, listOf(expected), withBrowsers)

    override fun toArgsMap() = mapOf(
            KEY_URI to uri,
            KEY_EXPECTED to expected.joinToString(separator = ","),
            KEY_BROWSER to withBrowsers.toString()
    )
}
