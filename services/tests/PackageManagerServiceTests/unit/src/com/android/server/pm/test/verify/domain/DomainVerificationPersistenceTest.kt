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

import android.content.pm.verify.domain.DomainVerificationState
import android.util.ArrayMap
import android.util.SparseArray
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
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class DomainVerificationPersistenceTest {

    companion object {
        private val PKG_PREFIX = DomainVerificationPersistenceTest::class.java.`package`!!.name

        internal fun File.writeXml(block: (serializer: TypedXmlSerializer) -> Unit) = apply {
            outputStream().use {
                // This must use the binary serializer the mirror the production behavior, as
                // there are slight differences with the string based one.
                Xml.newBinarySerializer()
                    .apply {
                        setOutput(it, StandardCharsets.UTF_8.name())
                        startDocument(null, true)
                        setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
                        // Write a wrapping tag to ensure the domain verification settings didn't
                        // close out the document, allowing other settings to be written
                        startTag(null, "wrapper-tag")
                    }
                    .apply(block)
                    .apply {
                        startTag(null, "trailing-tag")
                        endTag(null, "trailing-tag")
                        endTag(null, "wrapper-tag")
                    }
                    .endDocument()
            }
        }

        internal fun <T> File.readXml(block: (parser: TypedXmlPullParser) -> T) =
            inputStream().use {
                val parser = Xml.resolvePullParser(it)
                assertThat(parser.nextTag()).isEqualTo(XmlPullParser.START_TAG)
                assertThat(parser.name).isEqualTo("wrapper-tag")
                assertThat(parser.nextTag()).isEqualTo(XmlPullParser.START_TAG)
                block(parser).also {
                    assertThat(parser.nextTag()).isEqualTo(XmlPullParser.START_TAG)
                    assertThat(parser.name).isEqualTo("trailing-tag")
                    assertThat(parser.nextTag()).isEqualTo(XmlPullParser.END_TAG)
                    assertThat(parser.name).isEqualTo("trailing-tag")
                    assertThat(parser.nextTag()).isEqualTo(XmlPullParser.END_TAG)
                    assertThat(parser.name).isEqualTo("wrapper-tag")
                }
            }
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun mockWriteValues(
        pkgNameToSignature: (String) -> String? = { null }
    ): Triple<DomainVerificationStateMap<DomainVerificationPkgState>,
            ArrayMap<String, DomainVerificationPkgState>,
            ArrayMap<String, DomainVerificationPkgState>> {
        val attached = DomainVerificationStateMap<DomainVerificationPkgState>().apply {
            mockPkgState(0, pkgNameToSignature).let { put(it.packageName, it.id, it) }
            mockPkgState(1, pkgNameToSignature).let { put(it.packageName, it.id, it) }
        }
        val pending = ArrayMap<String, DomainVerificationPkgState>().apply {
            mockPkgState(2, pkgNameToSignature).let { put(it.packageName, it) }
            mockPkgState(3, pkgNameToSignature).let { put(it.packageName, it) }
        }
        val restored = ArrayMap<String, DomainVerificationPkgState>().apply {
            mockPkgState(4, pkgNameToSignature).let { put(it.packageName, it) }
            mockPkgState(5, pkgNameToSignature).let { put(it.packageName, it) }
        }

        return Triple(attached, pending, restored)
    }

    @Test
    fun writeAndReadBackNormal() {
        val (attached, pending, restored) = mockWriteValues()
        val file = tempFolder.newFile().writeXml {
            DomainVerificationPersistence.writeToXml(it, attached, pending, restored, null)
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
    fun writeAndReadBackWithSignature() {
        val (attached, pending, restored) = mockWriteValues()
        val file = tempFolder.newFile().writeXml {
            DomainVerificationPersistence.writeToXml(it, attached, pending, restored) {
                "SIGNATURE_$it"
            }
        }

        val (readActive, readRestored) = file.readXml {
            DomainVerificationPersistence.readFromXml(it)
        }

        // Assign the signatures to a fresh set of data structures, to ensure the previous write
        // call did not use the signatures from the data structure. This is because the method is
        // intended to optionally append signatures, regardless of if the existing data structures
        // contain them or not.
        val (attached2, pending2, restored2) = mockWriteValues { "SIGNATURE_$it" }

        assertThat(readActive.values)
            .containsExactlyElementsIn(attached2.values() + pending2.values)
        assertThat(readRestored.values).containsExactlyElementsIn(restored2.values)

        (readActive + readRestored).forEach { (_, value) ->
            assertThat(value.backupSignatureHash).isEqualTo("SIGNATURE_${value.packageName}")
        }
    }

    @Test
    fun writeStateSignatureIfFunctionReturnsNull() {
        val (attached, pending, restored) = mockWriteValues  { "SIGNATURE_$it" }
        val file = tempFolder.newFile().writeXml {
            DomainVerificationPersistence.writeToXml(it, attached, pending, restored) { null }
        }

        val (readActive, readRestored) = file.readXml {
            DomainVerificationPersistence.readFromXml(it)
        }

        assertThat(readActive.values)
            .containsExactlyElementsIn(attached.values() + pending.values)
        assertThat(readRestored.values).containsExactlyElementsIn(restored.values)

        (readActive + readRestored).forEach { (_, value) ->
            assertThat(value.backupSignatureHash).isEqualTo("SIGNATURE_${value.packageName}")
        }
    }

    @Test
    fun readMalformed() {
        val stateZero = mockEmptyPkgState(0, pkgNameToSignature = { "ACTIVE" }).apply {
            stateMap["example.com"] = DomainVerificationState.STATE_SUCCESS
            stateMap["example.org"] = DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED

            // A domain without a written state falls back to default
            stateMap["missing-state.com"] = DomainVerificationState.STATE_NO_RESPONSE

            userStates[1] = DomainVerificationInternalUserState(1).apply {
                addHosts(setOf("example-user1.com", "example-user1.org"))
                isLinkHandlingAllowed = true
            }
        }
        val stateOne = mockEmptyPkgState(1, pkgNameToSignature = { "RESTORED" }).apply {
            // It's valid to have a user selection without any autoVerify domains
            userStates[1] = DomainVerificationInternalUserState(1).apply {
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
                        signature="ACTIVE"
                        >
                        <state>
                            <domain name="example.com" state="${
                                DomainVerificationState.STATE_SUCCESS}"/>
                            <domain name="example.org" state="${
                                DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED}"/>
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
                        signature="RESTORED"
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
        hasAutoVerifyDomains: Boolean = true,
        pkgNameToSignature: (String) -> String? = { null }
    ): DomainVerificationPkgState {
        val pkgName = pkgName(id)
        val domainSetId = UUID(0L, id.toLong())
        return DomainVerificationPkgState(
            pkgName,
            domainSetId,
            hasAutoVerifyDomains,
            ArrayMap(),
            SparseArray(),
            pkgNameToSignature(pkgName)
        )
    }

    private fun mockPkgState(id: Int, pkgNameToSignature: (String) -> String? = { null }) =
        mockEmptyPkgState(id, pkgNameToSignature = pkgNameToSignature)
            .apply {
                stateMap["$packageName.com"] = id
                userStates[id] = DomainVerificationInternalUserState(id).apply {
                    addHosts(setOf("$packageName-user.com"))
                    isLinkHandlingAllowed = true
                }
            }

    private fun pkgName(id: Int) = "$PKG_PREFIX.pkg$id"
}
