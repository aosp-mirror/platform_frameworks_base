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

data class SetActivityAsAlwaysParams(
    val uri: String,
    val packageName: String,
    val activityName: String,
    override val methodName: String = "setActivityAsAlways"
) : IntentVerifyTestParams {

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_PACKAGE_NAME = "packageName"
        private const val KEY_ACTIVITY_NAME = "activityName"

        fun fromArgs(args: Map<String, String>) = SetActivityAsAlwaysParams(
                args.getValue(KEY_URI),
                args.getValue(KEY_PACKAGE_NAME),
                args.getValue(KEY_ACTIVITY_NAME)
        )
    }

    override fun toArgsMap() = mapOf(
            KEY_URI to uri,
            KEY_PACKAGE_NAME to packageName,
            KEY_ACTIVITY_NAME to activityName
    )
}
