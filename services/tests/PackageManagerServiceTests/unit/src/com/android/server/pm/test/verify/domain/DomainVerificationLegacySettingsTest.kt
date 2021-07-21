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

import android.content.pm.IntentFilterVerificationInfo
import android.content.pm.PackageManager
import android.util.ArraySet
import com.android.server.pm.test.verify.domain.DomainVerificationPersistenceTest.Companion.readXml
import com.android.server.pm.test.verify.domain.DomainVerificationPersistenceTest.Companion.writeXml
import com.android.server.pm.verify.domain.DomainVerificationLegacySettings
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DomainVerificationLegacySettingsTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun writeAndReadBackNormal() {
        val settings = DomainVerificationLegacySettings().apply {
            add(
                "com.test.one",
                IntentFilterVerificationInfo(
                    "com.test.one",
                    ArraySet(setOf("example1.com", "example2.com"))
                ).apply {
                    status = PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK
                }
            )
            add(
                "com.test.one",
                0, PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS
            )
            add(
                "com.test.one",
                10, PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER
            )

            add(
                "com.test.two",
                IntentFilterVerificationInfo(
                    "com.test.two",
                    ArraySet(setOf("example3.com"))
                )
            )

            add(
                "com.test.three",
                11, PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS
            )
        }


        val file = tempFolder.newFile().writeXml(settings::writeSettings)
        val newSettings = file.readXml {
            DomainVerificationLegacySettings().apply {
                readSettings(it)
            }
        }

        val xml = file.readText()

        // Legacy migrated settings doesn't bother writing the legacy verification info
        assertWithMessage(xml).that(newSettings.remove("com.test.one")).isNull()
        assertWithMessage(xml).that(newSettings.getUserState("com.test.one", 0))
            .isEqualTo(PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS)
        assertWithMessage(xml).that(newSettings.getUserState("com.test.one", 10))
            .isEqualTo(PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER)

        val firstUserStates = newSettings.getUserStates("com.test.one")
        assertWithMessage(xml).that(firstUserStates).isNotNull()
        assertWithMessage(xml).that(firstUserStates!!.size()).isEqualTo(2)
        assertWithMessage(xml).that(firstUserStates[0])
            .isEqualTo(PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS)
        assertWithMessage(xml).that(firstUserStates[10])
            .isEqualTo(PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER)

        assertWithMessage(xml).that(newSettings.remove("com.test.two")).isNull()
        assertWithMessage(xml).that(newSettings.getUserStates("com.test.two")).isNull()

        assertWithMessage(xml).that(newSettings.remove("com.test.three")).isNull()
        assertWithMessage(xml).that(newSettings.getUserState("com.test.three", 11))
            .isEqualTo(PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS)
    }
}
