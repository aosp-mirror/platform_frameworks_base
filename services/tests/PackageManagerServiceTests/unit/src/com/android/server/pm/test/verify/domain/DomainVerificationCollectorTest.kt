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

package com.android.server.pm.test.verify.domain

import android.content.Intent
import android.content.pm.ApplicationInfo
import com.android.server.pm.pkg.component.ParsedActivityImpl
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl
import android.os.Build
import android.os.PatternMatcher
import android.util.ArraySet
import com.android.server.SystemConfig
import com.android.server.compat.PlatformCompat
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.verify.domain.DomainVerificationCollector
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.eq

class DomainVerificationCollectorTest {

    companion object {
        private const val TEST_PKG_NAME = "com.test.pkg"
    }

    private val platformCompat: PlatformCompat = mockThrowOnUnmocked {
        whenever(
            isChangeEnabledInternalNoLogging(
                eq(DomainVerificationCollector.RESTRICT_DOMAINS),
                any()
            )
        ) {
            (arguments[1] as ApplicationInfo).targetSdkVersion >= Build.VERSION_CODES.S
        }
    }

    @Test
    fun verifyV1() {
        val pkg = mockPkg(useV2 = false, autoVerify = true)
        val collector = mockCollector()
        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com", "example4.com")
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg))
                .containsExactly("invalid1", "invalid2", "invalid3", "invalid4")
    }

    @Test
    fun verifyV1NoAutoVerify() {
        val pkg = mockPkg(useV2 = false, autoVerify = false)
        val collector = mockCollector()
        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg)).isEmpty()
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg)).isEmpty()
    }

    @Test
    fun verifyV1ForceAutoVerify() {
        val pkg = mockPkg(useV2 = false, autoVerify = false)
        val collector = mockCollector(linkedApps = setOf(TEST_PKG_NAME))
        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com", "example4.com")
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg))
                .containsExactly("invalid1", "invalid2", "invalid3", "invalid4")
    }

    @Test
    fun verifyV1NoValidIntentFilter() {
        val pkg = mockThrowOnUnmocked<AndroidPackage> {
            whenever(packageName) { TEST_PKG_NAME }
            whenever(targetSdkVersion) { Build.VERSION_CODES.R }

            val activityList = listOf(
                ParsedActivityImpl().apply {
                    addIntent(
                        ParsedIntentInfoImpl()
                            .apply {
                            intentFilter.apply {
                                addAction(Intent.ACTION_VIEW)
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                addCategory(Intent.CATEGORY_DEFAULT)
                                addDataScheme("http")
                                addDataScheme("https")
                                addDataPath("/sub", PatternMatcher.PATTERN_LITERAL)
                                addDataAuthority("example1.com", null)
                                addDataAuthority("invalid1", null)
                            }
                        }
                    )
                },
                ParsedActivityImpl().apply {
                    addIntent(
                        ParsedIntentInfoImpl()
                            .apply {
                            intentFilter.apply {
                                setAutoVerify(true)
                                addAction(Intent.ACTION_VIEW)
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                addCategory(Intent.CATEGORY_DEFAULT)
                                addDataScheme("http")
                                addDataScheme("https")

                                // The presence of a non-web-scheme as the only autoVerify
                                // intent-filter, when non-forced, means that v1 will not pick
                                // up the package for verification.
                                addDataScheme("nonWebScheme")
                                addDataPath("/sub", PatternMatcher.PATTERN_LITERAL)
                                addDataAuthority("example2.com", null)
                                addDataAuthority("invalid2", null)
                            }
                        }
                    )
                },
            )

            whenever(activities) { activityList }
        }

        val collector = mockCollector()
        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg)).isEmpty()
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg)).isEmpty()
    }

    @Test
    fun verifyV2() {
        val pkg = mockPkg(useV2 = true, autoVerify = true)
        val collector = mockCollector()

        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg))
                .containsExactly("example1.com", "example3.com")
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg))
                .containsExactly("invalid1", "invalid3")
    }

    @Test
    fun verifyV2NoAutoVerify() {
        val pkg = mockPkg(useV2 = true, autoVerify = false)
        val collector = mockCollector()

        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg)).isEmpty()
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg)).isEmpty()
    }

    @Test
    fun verifyV2ForceAutoVerifyIgnored() {
        val pkg = mockPkg(useV2 = true, autoVerify = false)
        val collector = mockCollector(linkedApps = setOf(TEST_PKG_NAME))

        assertThat(collector.collectAllWebDomains(pkg))
                .containsExactly("example1.com", "example2.com", "example3.com")
        assertThat(collector.collectValidAutoVerifyDomains(pkg)).isEmpty()
        assertThat(collector.collectInvalidAutoVerifyDomains(pkg)).isEmpty()
    }

    private fun mockCollector(linkedApps: Set<String> = emptySet()): DomainVerificationCollector {
        val systemConfig = mockThrowOnUnmocked<SystemConfig> {
            whenever(this.linkedApps) { ArraySet(linkedApps) }
        }

        return DomainVerificationCollector(platformCompat, systemConfig)
    }

    private fun mockPkg(useV2: Boolean, autoVerify: Boolean): AndroidPackage {
        // Translate equivalent of the following manifest declaration. This string isn't actually
        // parsed, but it's a far easier to read representation of the test data.
        // language=XML
        """
            <xml>
                <intent-filter android:autoVerify="$autoVerify">
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="http"/>
                    <data android:scheme="https"/>
                    <data android:path="/sub"/>
                    <data android:host="example1.com"/>
                    <data android:host="invalid1"/>
                </intent-filter>
                <intent-filter>
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="http"/>
                    <data android:path="/sub2"/>
                    <data android:host="example2.com"/>
                    <data android:host="invalid2."/>
                </intent-filter>
                <intent-filter android:autoVerify="$autoVerify">
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="https"/>
                    <data android:path="/sub3"/>
                    <data android:host="example3.com"/>
                    <data android:host=".invalid3"/>
                </intent-filter>
                <intent-filter android:autoVerify="$autoVerify">
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <data android:scheme="https"/>
                    <data android:path="/sub4"/>
                    <data android:host="example4.com"/>
                    <data android:host="invalid4"/>
                </intent-filter>
                <intent-filter android:autoVerify="$autoVerify">
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="https"/>
                    <data android:path="/sub5"/>
                    <data android:host="example5.com"/>
                    <data android:host="invalid5"/>
                </intent-filter>
                <intent-filter android:autoVerify="$autoVerify">
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="https"/>
                    <data android:path="/sub6"/>
                    <data android:host="example6.com"/>
                    <data android:host="invalid6"/>
                </intent-filter>
                <intent-filter android:autoVerify="$autoVerify">
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="example7.com"/>
                <intent-filter android:autoVerify="$autoVerify">
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:scheme="https"/>
                </intent-filter>
                <intent-filter android:autoVerify="$autoVerify">
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <data android:path="/sub7"/>
                </intent-filter>
            </xml>
        """.trimIndent()

        return mockThrowOnUnmocked {
            whenever(packageName) { TEST_PKG_NAME }
            whenever(targetSdkVersion) {
                if (useV2) Build.VERSION_CODES.S else Build.VERSION_CODES.R
            }

            // The intents are split into separate Activities to test that multiple are collected
            val activityList = listOf(
                    ParsedActivityImpl().apply {
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addAction(Intent.ACTION_VIEW)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("http")
                                    addDataScheme("https")
                                    addDataPath("/sub", PatternMatcher.PATTERN_LITERAL)
                                    addDataAuthority("example1.com", null)
                                    addDataAuthority("invalid1", null)
                                }
                            }
                        )
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    addAction(Intent.ACTION_VIEW)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("http")
                                    addDataPath("/sub2", PatternMatcher.PATTERN_LITERAL)
                                    addDataAuthority("example2.com", null)
                                    addDataAuthority("invalid2", null)
                                }
                            }
                        )
                    },
                    ParsedActivityImpl().apply {
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addAction(Intent.ACTION_VIEW)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("https")
                                    addDataPath("/sub3", PatternMatcher.PATTERN_LITERAL)
                                    addDataAuthority("example3.com", null)
                                    addDataAuthority("invalid3", null)
                                }
                            }
                        )
                    },
                    ParsedActivityImpl().apply {
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addAction(Intent.ACTION_VIEW)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addDataScheme("https")
                                    addDataPath("/sub4", PatternMatcher.PATTERN_LITERAL)
                                    addDataAuthority("example4.com", null)
                                    addDataAuthority("invalid4", null)
                                }
                            }
                        )
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addAction(Intent.ACTION_VIEW)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("https")
                                    addDataPath("/sub5", PatternMatcher.PATTERN_LITERAL)
                                    addDataAuthority("example5.com", null)
                                    addDataAuthority("invalid5", null)
                                }
                            }
                        )
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("https")
                                    addDataPath("/sub6", PatternMatcher.PATTERN_LITERAL)
                                    addDataAuthority("example6.com", null)
                                    addDataAuthority("invalid6", null)
                                }
                            }
                        )
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataAuthority("example7.com", null)
                                }
                            }
                        )
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("https")
                                }
                            }
                        )
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    setAutoVerify(autoVerify)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataPath("/sub7", PatternMatcher.PATTERN_LITERAL)
                                }
                            }
                        )
                    },
            )

            whenever(activities) { activityList }
        }
    }
}
