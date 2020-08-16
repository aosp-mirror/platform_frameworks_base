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

data class VerifyRequest(
    val id: Int = -1,
    val scheme: String,
    val hosts: List<String>,
    val packageName: String
) {

    companion object {
        fun deserialize(value: String?): VerifyRequest {
            val lines = value?.trim()?.lines()
                    ?: return VerifyRequest(scheme = "", hosts = emptyList(), packageName = "")
            return VerifyRequest(
                    lines[0].removePrefix("id=").toInt(),
                    lines[1].removePrefix("scheme="),
                    lines[2].removePrefix("hosts=").split(","),
                    lines[3].removePrefix("packageName=")
            )
        }
    }

    constructor(id: Int = -1, scheme: String, host: String, packageName: String) :
            this(id, scheme, listOf(host), packageName)

    fun serializeToString() = """
        id=$id
        scheme=$scheme
        hosts=${hosts.joinToString(separator = ",")}
        packageName=$packageName
    """.trimIndent() + "\n"
}
