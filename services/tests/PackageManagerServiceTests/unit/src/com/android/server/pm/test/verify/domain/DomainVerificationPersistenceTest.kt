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

import android.content.pm.verify.domain.DomainVerificationManager
import android.util.ArrayMap
import android.util.TypedXmlPullParser
import android.util.TypedXmlSerializer
import android.util.Xml
import com.android.server.pm.verify.domain.DomainVerificationPersistence
import com.android.server.pm.verify.domain.models.DomainVerificationInternalUserState
import com.android.server.pm.verify.domain.models.DomainVerificationPkgState
import com.android.server.pm.verify.domain.models.DomainVerificationStateMap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class DomainVerificationPersistenceTest {

    companion object {
        private val PKG_PREFIX = DomainVerificationPersistenceTest::class.java.`package`!!.name

        internal fun File.writeXml(block: (serializer: TypedXmlSerializer) -> Unit) = apply {
            outputStream().use {
                // Explicitly use string based XML so it can printed in the test failure output
                Xml.newFastSerializer()
                    .apply {
                        setOutput(it, StandardCharsets.UTF_8.name())
                        startDocument(null, true)
                        setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
                    }
                    .apply(block)
                    .endDocument()
            }
        }

        internal fun <T> File.readXml(block: (parser: TypedXmlPullParser) -> T) =
            inputStream().use {
                block(Xml.resolvePullParser(it))
            }
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun writeAndReadBackNormal() {
        val attached = DomainVerificationStateMap<DomainVerificationPkgState>().apply {
            mockPkgState(0).let { put(it.packageName, it.id, it) }
            mockPkgState(1).let { put(it.packageName, it.id, it) }
        }
        val pending = ArrayMap<String, DomainVerificationPkgState>().apply {
            mockPkgState(2).let { put(it.packageName, it) }
            mockPkgState(3).let { put(it.packageName, it) }
        }
        val restored = ArrayMap<String, DomainVerificationPkgState>().apply {
            mockPkgState(4).let { put(it.packageName, it) }
            mockPkgState(5).let { put(it.packageName, it) }
        }

        val file = tempFolder.newFile().writeXml {
            DomainVerificationPersistence.writeToXml(it, attached, pending, restored)
        }

        val xml = file.readText()

        val (readActive, readRestored) = file.readXml {
            DomainVerificationPersistence.readFromXml(it)
        }

        assertWithMessage(xml).that(readActive.values)
            .containsExactlyElementsIn(attached.values() + pending.values)
        assertWithMessage(xml).that(readRestored.values).containsExactlyElementsIn(restored.values)
    }

    @Test
    fun readMalformed() {
        val stateZero = mockEmptyPkgState(0).apply {
            stateMap["example.com"] = DomainVerificationManager.STATE_SUCCESS
            stateMap["example.org"] = DomainVerificationManager.STATE_FIRST_VERIFIER_DEFINED

            // A domain without a written state falls back to default
            stateMap["missing-state.com"] = DomainVerificationManager.STATE_NO_RESPONSE

            userSelectionStates[1] = DomainVerificationInternalUserState(1).apply {
                addHosts(setOf("example-user1.com", "example-user1.org"))
                isLinkHandlingAllowed = true
            }
        }
        val stateOne = mockEmptyPkgState(1).apply {
            // It's valid to have a user selection without any autoVerify domains
            userSelectionStates[1] = DomainVerificationInternalUserState(1).apply {
                addHosts(setOf("example-user1.com", "example-user1.org"))
                isLinkHandlingAllowed = false
            }
        }

        // Also valid to have neither autoVerify domains nor any active user states
        val stateTwo = mockEmptyPkgState(2, hasAutoVerifyDomains = false)

        // language=XML
        val xml = """
            <?xml?>
            <domain-verifications>
                <active>
                    <package-state
                        packageName="${stateZero.packageName}"
                        id="${stateZero.id}"
                        >
                        <state>
                            <domain name="duplicate-takes-last.com" state="1"/>
                        </state>
                    </package-state>
                    <package-state
                        packageName="${stateZero.packageName}"
                        id="${stateZero.id}"
                        hasAutoVerifyDomains="true"
                        >
                        <state>
                            <domain name="example.com" state="${
                                DomainVerificationManager.STATE_SUCCESS}"/>
                            <domain name="example.org" state="${
                                DomainVerificationManager.STATE_FIRST_VERIFIER_DEFINED}"/>
                            <not-domain name="not-domain.com" state="1"/>
                            <domain name="missing-state.com"/>
                        </state>
                        <user-states>
                            <user-state userId="1" allowLinkHandling="true">
                                <enabled-hosts>
                                    <host name="example-user1.com"/>
                                    <not-host name="not-host.com"/>
                                    <host/>
                                </enabled-hosts>
                                <enabled-hosts>
                                    <host name="example-user1.org"/>
                                </enabled-hosts>
                                <enabled-hosts/>
                            </user-state>
                            <user-state>
                                <enabled-hosts>
                                    <host name="no-user-id.com"/>
                                </enabled-hosts>
                            </user-state>
                        </user-states>
                    </package-state>
                </active>
                <not-active/>
                <restored>
                    <package-state
                        packageName="${stateOne.packageName}"
                        id="${stateOne.id}"
                        hasAutoVerifyDomains="true"
                        >
                        <state/>
                        <user-states>
                            <user-state userId="1" allowLinkHandling="false">
                                <enabled-hosts>
                                    <host name="example-user1.com"/>
                                    <host name="example-user1.org"/>
                                </enabled-hosts>
                            </user-state>
                        </user-states>
                    </package-state>
                    <package-state packageName="${stateTwo.packageName}"/>
                    <package-state id="${stateTwo.id}"/>
                    <package-state
                        packageName="${stateTwo.packageName}"
                        id="${stateTwo.id}"
                        hasAutoVerifyDomains="false"
                        >
                        <state/>
                        <user-states/>
                    </package-state>
                </restore>
                <not-restored/>
            </domain-verifications>
        """.trimIndent()

        val (active, restored) = DomainVerificationPersistence
            .readFromXml(Xml.resolvePullParser(xml.byteInputStream()))

        assertThat(active.values).containsExactly(stateZero)
        assertThat(restored.values).containsExactly(stateOne, stateTwo)
    }

    private fun mockEmptyPkgState(
        id: Int,
        hasAutoVerifyDomains: Boolean = true
    ): DomainVerificationPkgState {
        val pkgName = pkgName(id)
        val domainSetId = UUID(0L, id.toLong())
        return DomainVerificationPkgState(pkgName, domainSetId, hasAutoVerifyDomains)
    }

    private fun mockPkgState(id: Int) = mockEmptyPkgState(id).apply {
        stateMap["$packageName.com"] = id
        userSelectionStates[id] = DomainVerificationInternalUserState(id).apply {
            addHosts(setOf("$packageName-user.com"))
            isLinkHandlingAllowed = true
        }
    }

    private fun pkgName(id: Int) = "$PKG_PREFIX.pkg$id"
}
