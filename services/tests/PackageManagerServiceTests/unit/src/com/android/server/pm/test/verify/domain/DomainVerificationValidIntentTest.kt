/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.verify.domain

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.android.server.pm.verify.domain.DomainVerificationUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DomainVerificationValidIntentTest {

    companion object {

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun parameters(): Array<Params> {
            val succeeding = mutableListOf<Params>()
            val failing = mutableListOf<Params>()

            // Start with the base intent
            val base = Params(categorySet = emptySet()).also { succeeding += it }

            // Add all explicit supported categorySet
            succeeding += base.copy(
                categorySet = setOf(Intent.CATEGORY_BROWSABLE),
                matchDefaultOnly = true
            )

            failing += base.copy(
                categorySet = setOf(Intent.CATEGORY_BROWSABLE),
                matchDefaultOnly = false
            )

            succeeding += listOf(true, false).map {
                base.copy(
                    categorySet = setOf(Intent.CATEGORY_DEFAULT),
                    matchDefaultOnly = it
                )
            }

            succeeding += listOf(true, false).map {
                base.copy(
                    categorySet = setOf(Intent.CATEGORY_BROWSABLE, Intent.CATEGORY_DEFAULT),
                    matchDefaultOnly = it
                )
            }

            // Fail on unsupported category
            failing += listOf(
                emptySet(),
                setOf(Intent.CATEGORY_BROWSABLE),
                setOf(Intent.CATEGORY_DEFAULT),
                setOf(Intent.CATEGORY_BROWSABLE, Intent.CATEGORY_DEFAULT)
            ).map { base.copy(categorySet = it + "invalid.CATEGORY") }

            // Fail on unsupported action
            failing += base.copy(action = Intent.ACTION_SEND)

            // Fail on unsupported domain
            failing += base.copy(domain = "invalid")

            // Fail on empty domains
            failing += base.copy(domain = "")

            // Fail on missing scheme
            failing += base.copy(
                uriFunction = { Uri.Builder().authority("test.com").build() }
            )

            // Fail on missing host
            failing += base.copy(
                domain = "",
                uriFunction = { Uri.Builder().scheme("https").build() }
            )

            succeeding.forEach { it.expected = true }
            failing.forEach { it.expected = false }
            return (succeeding + failing).toTypedArray()
        }

        data class Params(
            val action: String = Intent.ACTION_VIEW,
            val categorySet: Set<String> = mutableSetOf(),
            val domain: String = "test.com",
            val matchDefaultOnly: Boolean = true,
            var expected: Boolean? = null,
            val uriFunction: (domain: String) -> Uri = { Uri.parse("https://$it") }
        ) {
            val intent = Intent(action, uriFunction(domain)).apply {
                categorySet.forEach(::addCategory)
            }

            override fun toString() = intent.toShortString(false, false, false, false) +
                    ", matchDefaultOnly = $matchDefaultOnly, expected = $expected"
        }
    }

    @Parameterized.Parameter(0)
    lateinit var params: Params

    @Test
    fun verify() {
        val flags = if (params.matchDefaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0
        assertThat(DomainVerificationUtils.isDomainVerificationIntent(params.intent,
            flags.toLong())).isEqualTo(params.expected)
    }
}
