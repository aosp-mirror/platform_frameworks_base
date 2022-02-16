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
import android.content.pm.PackageUserState
import android.content.pm.Signature
import android.content.pm.parsing.component.ParsedActivity
import android.content.pm.parsing.component.ParsedIntentInfo
import android.content.pm.verify.domain.DomainOwner
import android.content.pm.verify.domain.DomainVerificationInfo.STATE_MODIFIABLE_VERIFIED
import android.content.pm.verify.domain.DomainVerificationInfo.STATE_NO_RESPONSE
import android.content.pm.verify.domain.DomainVerificationInfo.STATE_SUCCESS
import android.content.pm.verify.domain.DomainVerificationInfo.STATE_UNMODIFIABLE
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationState
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
import android.os.Build
import android.os.PatternMatcher
import android.os.Process
import android.util.ArraySet
import android.util.Xml
import com.android.server.pm.PackageSetting
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.test.verify.domain.DomainVerificationTestUtils.mockPackageSettings
import com.android.server.pm.verify.domain.DomainVerificationService
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class DomainVerificationPackageTest {

    companion object {
        private const val PKG_ONE = "com.test.one"
        private const val PKG_TWO = "com.test.two"
        private val UUID_ONE = UUID.fromString("1b041c96-8d37-4932-a858-561bfac5947c")
        private val UUID_TWO = UUID.fromString("a3389c16-7f9f-4e86-85e3-500d1249c74c")
        private const val SIGNATURE_ONE = "AA"
        private const val DIGEST_ONE =
            "BCEEF655B5A034911F1C3718CE056531B45EF03B4C7B1F15629E867294011A7D"
        private const val SIGNATURE_TWO = "BB"

        private val DOMAIN_BASE = DomainVerificationPackageTest::class.java.packageName
        private val DOMAIN_1 = "one.$DOMAIN_BASE"
        private val DOMAIN_2 = "two.$DOMAIN_BASE"
        private val DOMAIN_3 = "three.$DOMAIN_BASE"
        private val DOMAIN_4 = "four.$DOMAIN_BASE"

        private const val USER_ID = 0
    }

    private val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, SIGNATURE_ONE)
    private val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, SIGNATURE_TWO)

    @Test
    fun addPackageFirstTime() {
        val service = makeService(pkg1, pkg2)
        service.addPackage(pkg1)
        val info = service.getInfo(pkg1.getName())
        assertThat(info.packageName).isEqualTo(pkg1.getName())
        assertThat(info.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(info.hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_NO_RESPONSE,
                DOMAIN_2 to STATE_NO_RESPONSE,
        ))

        val userState = service.getUserState(pkg1.getName())
        assertThat(userState.packageName).isEqualTo(pkg1.getName())
        assertThat(userState.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(userState.isLinkHandlingAllowed).isEqualTo(true)
        assertThat(userState.user.identifier).isEqualTo(USER_ID)
        assertThat(userState.hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_NONE,
                DOMAIN_2 to DOMAIN_STATE_NONE,
        ))

        assertThat(service.queryValidVerificationPackageNames())
                .containsExactly(pkg1.getName())
    }

    @Test
    fun addPackageSystemConfigured() {
        val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, SIGNATURE_ONE, isSystemApp = false)
        val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, SIGNATURE_TWO, isSystemApp = true)

        val service = makeService(
            systemConfiguredPackageNames = ArraySet(setOf(pkg1.getName(), pkg2.getName())),
            pkg1, pkg2
        )
        service.addPackage(pkg1)
        service.addPackage(pkg2)

        service.getInfo(pkg1.getName()).apply {
            assertThat(packageName).isEqualTo(pkg1.getName())
            assertThat(identifier).isEqualTo(pkg1.domainSetId)
            assertThat(hostToStateMap).containsExactlyEntriesIn(
                mapOf(
                    DOMAIN_1 to STATE_NO_RESPONSE,
                    DOMAIN_2 to STATE_NO_RESPONSE,
                )
            )
        }

        service.getUserState(pkg1.getName()).apply {
            assertThat(packageName).isEqualTo(pkg1.getName())
            assertThat(identifier).isEqualTo(pkg1.domainSetId)
            assertThat(isLinkHandlingAllowed).isEqualTo(true)
            assertThat(user.identifier).isEqualTo(USER_ID)
            assertThat(hostToStateMap).containsExactlyEntriesIn(
                mapOf(
                    DOMAIN_1 to DOMAIN_STATE_NONE,
                    DOMAIN_2 to DOMAIN_STATE_NONE,
                )
            )
        }

        service.getInfo(pkg2.getName()).apply {
            assertThat(packageName).isEqualTo(pkg2.getName())
            assertThat(identifier).isEqualTo(pkg2.domainSetId)
            assertThat(hostToStateMap).containsExactlyEntriesIn(
                mapOf(
                    DOMAIN_1 to STATE_UNMODIFIABLE,
                    DOMAIN_2 to STATE_UNMODIFIABLE,
                )
            )
        }

        service.getUserState(pkg2.getName()).apply {
            assertThat(packageName).isEqualTo(pkg2.getName())
            assertThat(identifier).isEqualTo(pkg2.domainSetId)
            assertThat(isLinkHandlingAllowed).isEqualTo(true)
            assertThat(user.identifier).isEqualTo(USER_ID)
            assertThat(hostToStateMap).containsExactlyEntriesIn(
                mapOf(
                    DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                    DOMAIN_2 to DOMAIN_STATE_VERIFIED,
                )
            )
        }

        assertThat(service.queryValidVerificationPackageNames())
                .containsExactly(pkg1.getName(), pkg2.getName())
    }

    @Test
    fun addPackageRestoredMatchingSignature() {
        // language=XML
        val xml = """
            <?xml?>
            <domain-verifications>
                <active>
                    <package-state
                        packageName="${pkg1.getName()}"
                        id="${pkg1.domainSetId}"
                        signature="$DIGEST_ONE"
                        >
                        <state>
                            <domain name="$DOMAIN_1" state="1"/>
                        </state>
                    </package-state>
                </active>
            </domain-verifications>
        """

        val service = makeService(pkg1, pkg2)
        service.restoreSettings(Xml.resolvePullParser(xml.byteInputStream()))
        service.addPackage(pkg1)
        val info = service.getInfo(pkg1.getName())
        assertThat(info.packageName).isEqualTo(pkg1.getName())
        assertThat(info.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(info.hostToStateMap).containsExactlyEntriesIn(
            mapOf(
                DOMAIN_1 to STATE_MODIFIABLE_VERIFIED,
                DOMAIN_2 to STATE_NO_RESPONSE,
            )
        )

        val userState = service.getUserState(pkg1.getName())
        assertThat(userState.packageName).isEqualTo(pkg1.getName())
        assertThat(userState.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(userState.isLinkHandlingAllowed).isEqualTo(true)
        assertThat(userState.user.identifier).isEqualTo(USER_ID)
        assertThat(userState.hostToStateMap).containsExactlyEntriesIn(
            mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_NONE,
            )
        )

        assertThat(service.queryValidVerificationPackageNames())
            .containsExactly(pkg1.getName())
    }

    @Test
    fun addPackageRestoredMismatchSignature() {
        // language=XML
        val xml = """
            <?xml?>
            <domain-verifications>
                <active>
                    <package-state
                        packageName="${pkg1.getName()}"
                        id="${pkg1.domainSetId}"
                        signature="INVALID_SIGNATURE"
                        >
                        <state>
                            <domain name="$DOMAIN_1" state="1"/>
                        </state>
                    </package-state>
                </active>
            </domain-verifications>
        """

        val service = makeService(pkg1, pkg2)
        service.restoreSettings(Xml.resolvePullParser(xml.byteInputStream()))
        service.addPackage(pkg1)
        val info = service.getInfo(pkg1.getName())
        assertThat(info.packageName).isEqualTo(pkg1.getName())
        assertThat(info.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(info.hostToStateMap).containsExactlyEntriesIn(
            mapOf(
                DOMAIN_1 to STATE_NO_RESPONSE,
                DOMAIN_2 to STATE_NO_RESPONSE,
            )
        )

        val userState = service.getUserState(pkg1.getName())
        assertThat(userState.packageName).isEqualTo(pkg1.getName())
        assertThat(userState.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(userState.isLinkHandlingAllowed).isEqualTo(true)
        assertThat(userState.user.identifier).isEqualTo(USER_ID)
        assertThat(userState.hostToStateMap).containsExactlyEntriesIn(
            mapOf(
                DOMAIN_1 to DOMAIN_STATE_NONE,
                DOMAIN_2 to DOMAIN_STATE_NONE,
            )
        )

        assertThat(service.queryValidVerificationPackageNames())
            .containsExactly(pkg1.getName())
    }

    @Test
    fun addPackageActive() {
        // language=XML
        val xml = """
            <?xml?>
            <domain-verifications>
                <active>
                    <package-state
                        packageName="${pkg1.getName()}"
                        id="${pkg1.domainSetId}"
                        >
                        <state>
                            <domain name="$DOMAIN_1" state="$STATE_SUCCESS"/>
                        </state>
                        <user-states>
                            <user-state userId="$USER_ID" allowLinkHandling="false">
                                <enabled-hosts>
                                    <host name="$DOMAIN_2"/>
                                </enabled-hosts>
                            </user-state>
                        </user-states>
                    </package-state>
                </active>
            </domain-verifications>
        """.trimIndent()

        val service = makeService(pkg1, pkg2)
        xml.byteInputStream().use {
            service.readSettings(Xml.resolvePullParser(it))
        }

        service.addPackage(pkg1)

        assertAddPackageActivePendingRestoredState(service)
    }

    @Test
    fun addPackagePendingStripInvalidDomains() {
        val xml = addPackagePendingOrRestoredWithInvalidDomains()
        val service = makeService(pkg1, pkg2)
        xml.byteInputStream().use {
            service.readSettings(Xml.resolvePullParser(it))
        }

        service.addPackage(pkg1)

        val userState = service.getUserState(pkg1.getName())
        assertThat(userState.packageName).isEqualTo(pkg1.getName())
        assertThat(userState.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(userState.isLinkHandlingAllowed).isEqualTo(false)
        assertThat(userState.user.identifier).isEqualTo(USER_ID)
        assertThat(userState.hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_SELECTED,
        ))

        assertAddPackageActivePendingRestoredState(service)
    }

    @Test
    fun addPackageRestoredStripInvalidDomains() {
        val xml = addPackagePendingOrRestoredWithInvalidDomains()
        val service = makeService(pkg1, pkg2)
        xml.byteInputStream().use {
            service.restoreSettings(Xml.resolvePullParser(it))
        }

        service.addPackage(pkg1)

        assertAddPackageActivePendingRestoredState(service, expectRestore = true)
    }

    /**
     * Shared string that contains invalid [DOMAIN_3] and [DOMAIN_4] which should be stripped from
     * the final state.
     */
    private fun addPackagePendingOrRestoredWithInvalidDomains(): String =
        // language=XML
        """
            <?xml?>
            <domain-verifications>
                <active>
                    <package-state
                        packageName="${pkg1.getName()}"
                        id="${pkg1.domainSetId}"
                        signature="$DIGEST_ONE"
                        >
                        <state>
                            <domain name="$DOMAIN_1" state="$STATE_SUCCESS"/>
                            <domain name="$DOMAIN_3" state="$STATE_SUCCESS"/>
                        </state>
                        <user-states>
                            <user-state userId="$USER_ID" allowLinkHandling="false">
                                <enabled-hosts>
                                    <host name="$DOMAIN_2"/>
                                    <host name="$DOMAIN_4"/>
                                </enabled-hosts>
                            </user-state>
                            <user-state userId="${USER_ID + 10}" allowLinkHandling="true">
                                <enabled-hosts>
                                    <host name="$DOMAIN_4"/>
                                </enabled-hosts>
                            </user-state>
                        </user-states>
                    </package-state>
                </active>
            </domain-verifications>
        """.trimIndent()

    /**
     * Shared method to assert the same output when testing adding pkg1.
     */
    private fun assertAddPackageActivePendingRestoredState(
            service: DomainVerificationService,
            expectRestore: Boolean = false
    ) {
        val info = service.getInfo(pkg1.getName())
        assertThat(info.packageName).isEqualTo(pkg1.getName())
        assertThat(info.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(info.hostToStateMap).containsExactlyEntriesIn(mapOf(
                // To share the majority of code, special case restoration to check a different int
                DOMAIN_1 to if (expectRestore) STATE_MODIFIABLE_VERIFIED else STATE_SUCCESS,
                DOMAIN_2 to STATE_NO_RESPONSE,
        ))

        val userState = service.getUserState(pkg1.getName())
        assertThat(userState.packageName).isEqualTo(pkg1.getName())
        assertThat(userState.identifier).isEqualTo(pkg1.domainSetId)
        assertThat(userState.isLinkHandlingAllowed).isEqualTo(false)
        assertThat(userState.user.identifier).isEqualTo(USER_ID)
        assertThat(userState.hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_SELECTED,
        ))

        assertThat(service.queryValidVerificationPackageNames())
                .containsExactly(pkg1.getName())

        // Re-enable link handling to check that the 3/4 domains were stripped
        service.setDomainVerificationLinkHandlingAllowed(pkg1.getName(), true, USER_ID)

        assertThat(service.getOwnersForDomain(DOMAIN_1, USER_ID))
                .containsExactly(DomainOwner(PKG_ONE, false))

        assertThat(service.getOwnersForDomain(DOMAIN_2, USER_ID))
                .containsExactly(DomainOwner(PKG_ONE, true))

        assertThat(service.getOwnersForDomain(DOMAIN_2, USER_ID + 10)).isEmpty()

        listOf(DOMAIN_3, DOMAIN_4).forEach { domain ->
            listOf(USER_ID, USER_ID + 10).forEach {  userId ->
                assertThat(service.getOwnersForDomain(domain, userId)).isEmpty()
            }
        }
    }

    @Test
    fun migratePackageDropDomain() {
        val pkgName = PKG_ONE
        val pkgBefore = mockPkgSetting(pkgName, UUID_ONE, SIGNATURE_ONE,
            listOf(DOMAIN_1, DOMAIN_2, DOMAIN_3, DOMAIN_4))
        val pkgAfter = mockPkgSetting(pkgName, UUID_TWO, SIGNATURE_TWO, listOf(DOMAIN_1, DOMAIN_2))

        // Test 4 domains:
        // 1 will be approved and preserved, 2 will be selected and preserved,
        // 3 will be denied and dropped, 4 will be selected and dropped

        val map = mutableMapOf<String, PackageSetting>()
        val service = makeService { map[it] }
        service.addPackage(pkgBefore)

        // Only insert the package after addPackage call to ensure the service doesn't access
        // a live package inside the addPackage logic. It should only use the provided input.
        map[pkgName] = pkgBefore

        // To test the approve/denial states, use the internal methods for this variant
        service.setDomainVerificationStatusInternal(pkgName, DomainVerificationState.STATE_APPROVED,
                ArraySet(setOf(DOMAIN_1)))
        service.setDomainVerificationStatusInternal(pkgName, DomainVerificationState.STATE_DENIED,
                ArraySet(setOf(DOMAIN_3)))
        service.setUserSelection(
                UUID_ONE, setOf(DOMAIN_2, DOMAIN_4), true, USER_ID)

        // Check the verifier cannot change the shell approve/deny states
        service.setStatus(UUID_ONE, setOf(DOMAIN_1, DOMAIN_3), STATE_SUCCESS)

        assertThat(service.getInfo(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_UNMODIFIABLE,
                DOMAIN_2 to STATE_NO_RESPONSE,
                DOMAIN_3 to STATE_UNMODIFIABLE,
                DOMAIN_4 to STATE_NO_RESPONSE,
        ))
        assertThat(service.getUserState(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_SELECTED,
                DOMAIN_3 to DOMAIN_STATE_NONE,
                DOMAIN_4 to DOMAIN_STATE_SELECTED,
        ))

        // Now remove the package because migrateState shouldn't use it either
        map.remove(pkgName)

        map[pkgName] = pkgAfter

        service.migrateState(pkgBefore, pkgAfter)

        assertThat(service.getInfo(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_UNMODIFIABLE,
                DOMAIN_2 to STATE_NO_RESPONSE,
        ))
        assertThat(service.getUserState(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_SELECTED,
        ))
        assertThat(service.queryValidVerificationPackageNames()).containsExactly(pkgName)
    }

    @Test
    fun migratePackageDropAll() {
        val pkgName = PKG_ONE
        val pkgBefore = mockPkgSetting(pkgName, UUID_ONE, SIGNATURE_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkgAfter = mockPkgSetting(pkgName, UUID_TWO, SIGNATURE_TWO, emptyList())

        val map = mutableMapOf<String, PackageSetting>()
        val service = makeService { map[it] }
        service.addPackage(pkgBefore)

        // Only insert the package after addPackage call to ensure the service doesn't access
        // a live package inside the addPackage logic. It should only use the provided input.
        map[pkgName] = pkgBefore

        assertThat(service.getInfo(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_NO_RESPONSE,
                DOMAIN_2 to STATE_NO_RESPONSE,
        ))
        assertThat(service.getUserState(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_NONE,
                DOMAIN_2 to DOMAIN_STATE_NONE,
        ))
        assertThat(service.queryValidVerificationPackageNames()).containsExactly(pkgName)

        // Now remove the package because migrateState shouldn't use it either
        map.remove(pkgName)

        service.migrateState(pkgBefore, pkgAfter)

        map[pkgName] = pkgAfter

        assertThat(service.setStatus(UUID_ONE, setOf(DOMAIN_1), STATE_SUCCESS))
            .isNotEqualTo(DomainVerificationManager.STATUS_OK)

        assertThat(service.setUserSelection(UUID_ONE, setOf(DOMAIN_2), true, USER_ID))
            .isNotEqualTo(DomainVerificationManager.STATUS_OK)

        assertThat(service.getDomainVerificationInfo(pkgName)).isNull()
        assertThat(service.getUserState(pkgName).hostToStateMap).isEmpty()
        assertThat(service.queryValidVerificationPackageNames()).isEmpty()
    }

    @Test
    fun migratePackageAddDomain() {
        val pkgName = PKG_ONE
        val pkgBefore = mockPkgSetting(pkgName, UUID_ONE, SIGNATURE_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkgAfter = mockPkgSetting(pkgName, UUID_TWO, SIGNATURE_TWO,
            listOf(DOMAIN_1, DOMAIN_2, DOMAIN_3))

        // Test 3 domains:
        // 1 will be verified and preserved, 2 will be selected and preserved,
        // 3 will be new and default

        val map = mutableMapOf<String, PackageSetting>()
        val service = makeService { map[it] }
        service.addPackage(pkgBefore)

        // Only insert the package after addPackage call to ensure the service doesn't access
        // a live package inside the addPackage logic. It should only use the provided input.
        map[pkgName] = pkgBefore

        service.setStatus(UUID_ONE, setOf(DOMAIN_1), STATE_SUCCESS)
        service.setUserSelection(UUID_ONE, setOf(DOMAIN_2), true, USER_ID)

        assertThat(service.getInfo(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_SUCCESS,
                DOMAIN_2 to STATE_NO_RESPONSE,
        ))
        assertThat(service.getUserState(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_SELECTED,
        ))

        // Now remove the package because migrateState shouldn't use it either
        map.remove(pkgName)

        service.migrateState(pkgBefore, pkgAfter)

        map[pkgName] = pkgAfter

        assertThat(service.getInfo(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_SUCCESS,
                DOMAIN_2 to STATE_NO_RESPONSE,
                DOMAIN_3 to STATE_NO_RESPONSE,
        ))
        assertThat(service.getUserState(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_SELECTED,
                DOMAIN_3 to DOMAIN_STATE_NONE,
        ))
        assertThat(service.queryValidVerificationPackageNames()).containsExactly(pkgName)
    }

    @Test
    fun migratePackageAddAll() {
        val pkgName = PKG_ONE
        val pkgBefore = mockPkgSetting(pkgName, UUID_ONE, SIGNATURE_ONE, emptyList())
        val pkgAfter = mockPkgSetting(pkgName, UUID_TWO, SIGNATURE_TWO, listOf(DOMAIN_1, DOMAIN_2))

        val map = mutableMapOf<String, PackageSetting>()
        val service = makeService { map[it] }
        service.addPackage(pkgBefore)

        // Only insert the package after addPackage call to ensure the service doesn't access
        // a live package inside the addPackage logic. It should only use the provided input.
        map[pkgName] = pkgBefore

        assertThat(service.setStatus(UUID_ONE, setOf(DOMAIN_1), STATE_SUCCESS))
            .isNotEqualTo(DomainVerificationManager.STATUS_OK)

        assertThat(service.setUserSelection(UUID_ONE, setOf(DOMAIN_2), true, USER_ID))
            .isNotEqualTo(DomainVerificationManager.STATUS_OK)

        assertThat(service.getDomainVerificationInfo(pkgName)).isNull()
        assertThat(service.getUserState(pkgName).hostToStateMap).isEmpty()
        assertThat(service.queryValidVerificationPackageNames()).isEmpty()

        // Now remove the package because migrateState shouldn't use it either
        map.remove(pkgName)

        service.migrateState(pkgBefore, pkgAfter)

        map[pkgName] = pkgAfter

        assertThat(service.getInfo(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to STATE_NO_RESPONSE,
                DOMAIN_2 to STATE_NO_RESPONSE,
        ))
        assertThat(service.getUserState(pkgName).hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DOMAIN_STATE_NONE,
                DOMAIN_2 to DOMAIN_STATE_NONE,
        ))
        assertThat(service.queryValidVerificationPackageNames()).containsExactly(pkgName)
    }

    @Test
    fun backupAndRestore() {
        // This test acts as a proxy for true user restore through PackageManager,
        // as that's much harder to test for real.

        val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, SIGNATURE_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, SIGNATURE_TWO,
            listOf(DOMAIN_1, DOMAIN_2, DOMAIN_3))
        val serviceBefore = makeService(pkg1, pkg2)
        serviceBefore.addPackage(pkg1)
        serviceBefore.addPackage(pkg2)

        serviceBefore.setStatus(pkg1.domainSetId, setOf(DOMAIN_1), STATE_SUCCESS)
        serviceBefore.setDomainVerificationLinkHandlingAllowed(pkg1.getName(), false, 10)
        serviceBefore.setUserSelection(pkg2.domainSetId, setOf(DOMAIN_2), true, 0)
        serviceBefore.setUserSelection(pkg2.domainSetId, setOf(DOMAIN_3), true, 10)

        fun assertExpectedState(service: DomainVerificationService) {
            service.assertState(
                pkg1, userId = 0, hostToStateMap = mapOf(
                    DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                    DOMAIN_2 to DOMAIN_STATE_NONE,
                )
            )

            service.assertState(
                pkg1, userId = 10, linkHandingAllowed = false, hostToStateMap = mapOf(
                    DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                    DOMAIN_2 to DOMAIN_STATE_NONE,
                )
            )

            service.assertState(
                pkg2, userId = 0, hostToStateMap = mapOf(
                    DOMAIN_1 to DOMAIN_STATE_NONE,
                    DOMAIN_2 to DOMAIN_STATE_SELECTED,
                    DOMAIN_3 to DOMAIN_STATE_NONE
                )
            )

            service.assertState(
                pkg2, userId = 10, hostToStateMap = mapOf(
                    DOMAIN_1 to DOMAIN_STATE_NONE,
                    DOMAIN_2 to DOMAIN_STATE_NONE,
                    DOMAIN_3 to DOMAIN_STATE_SELECTED,
                )
            )
        }

        assertExpectedState(serviceBefore)

        val backupUser0 = ByteArrayOutputStream().use {
            serviceBefore.writeSettings(Xml.resolveSerializer(it), true, 0)
            it.toByteArray()
        }

        val backupUser1 = ByteArrayOutputStream().use {
            serviceBefore.writeSettings(Xml.resolveSerializer(it), true, 10)
            it.toByteArray()
        }

        val serviceAfter = makeService(pkg1, pkg2)
        serviceAfter.addPackage(pkg1)
        serviceAfter.addPackage(pkg2)

        // Check the state is default before the restoration applies
        listOf(0, 10).forEach {
            serviceAfter.assertState(
                pkg1, userId = it, hostToStateMap = mapOf(
                    DOMAIN_1 to DOMAIN_STATE_NONE,
                    DOMAIN_2 to DOMAIN_STATE_NONE,
                )
            )
        }

        listOf(0, 10).forEach {
            serviceAfter.assertState(
                pkg2, userId = it, hostToStateMap = mapOf(
                    DOMAIN_1 to DOMAIN_STATE_NONE,
                    DOMAIN_2 to DOMAIN_STATE_NONE,
                    DOMAIN_3 to DOMAIN_STATE_NONE,
                )
            )
        }

        ByteArrayInputStream(backupUser1).use {
            serviceAfter.restoreSettings(Xml.resolvePullParser(it))
        }

        // Assert user 1 was restored
        serviceAfter.assertState(
            pkg1, userId = 10, linkHandingAllowed = false, hostToStateMap = mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_NONE,
            )
        )

        serviceAfter.assertState(
            pkg2, userId = 10, hostToStateMap = mapOf(
                DOMAIN_1 to DOMAIN_STATE_NONE,
                DOMAIN_2 to DOMAIN_STATE_NONE,
                DOMAIN_3 to DOMAIN_STATE_SELECTED,
            )
        )

        // User 0 has domain verified (since that's not user-specific)
        serviceAfter.assertState(
            pkg1, userId = 0, hostToStateMap = mapOf(
                DOMAIN_1 to DOMAIN_STATE_VERIFIED,
                DOMAIN_2 to DOMAIN_STATE_NONE,
            )
        )

        // But user 0 is missing any user selected state
        serviceAfter.assertState(
            pkg2, userId = 0, hostToStateMap = mapOf(
                DOMAIN_1 to DOMAIN_STATE_NONE,
                DOMAIN_2 to DOMAIN_STATE_NONE,
                DOMAIN_3 to DOMAIN_STATE_NONE,
            )
        )

        ByteArrayInputStream(backupUser0).use {
            serviceAfter.restoreSettings(Xml.resolvePullParser(it))
        }

        assertExpectedState(serviceAfter)
    }

    private fun DomainVerificationService.getInfo(pkgName: String) =
            getDomainVerificationInfo(pkgName)
                    .also { assertThat(it).isNotNull() }!!

    private fun DomainVerificationService.getUserState(pkgName: String, userId: Int = USER_ID) =
            getDomainVerificationUserState(pkgName, userId)
                    .also { assertThat(it).isNotNull() }!!

    private fun makeService(
        systemConfiguredPackageNames: ArraySet<String> = ArraySet(),
        vararg pkgSettings: PackageSetting
    ) = makeService(systemConfiguredPackageNames = systemConfiguredPackageNames) {
        pkgName -> pkgSettings.find { pkgName == it.getName() }
    }

    private fun makeService(vararg pkgSettings: PackageSetting) =
        makeService { pkgName -> pkgSettings.find { pkgName == it.getName() } }

    private fun makeService(
        systemConfiguredPackageNames: ArraySet<String> = ArraySet(),
        pkgSettingFunction: (String) -> PackageSetting? = { null }
    ) = DomainVerificationService(mockThrowOnUnmocked {
            // Assume the test has every permission necessary
            whenever(enforcePermission(anyString(), anyInt(), anyInt(), anyString()))
            whenever(checkPermission(anyString(), anyInt(), anyInt())) {
                PackageManager.PERMISSION_GRANTED
            }
        }, mockThrowOnUnmocked {
            whenever(this.linkedApps) { systemConfiguredPackageNames }
        }, mockThrowOnUnmocked {
            whenever(isChangeEnabledInternalNoLogging(anyLong(), any())) { true }
        }).apply {
            setConnection(mockThrowOnUnmocked {
                whenever(filterAppAccess(anyString(), anyInt(), anyInt())) { false }
                whenever(doesUserExist(0)) { true }
                whenever(doesUserExist(10)) { true }
                whenever(scheduleWriteSettings())

                // Need to provide an internal UID so some permission checks are ignored
                whenever(callingUid) { Process.ROOT_UID }
                whenever(callingUserId) { 0 }

                mockPackageSettings {
                    pkgSettingFunction(it)
                }
            })
        }

    private fun mockPkgSetting(
        pkgName: String,
        domainSetId: UUID,
        signature: String,
        domains: List<String> = listOf(DOMAIN_1, DOMAIN_2),
        isSystemApp: Boolean = false
    ) = mockThrowOnUnmocked<PackageSetting> {
        val pkg = mockThrowOnUnmocked<AndroidPackage> {
            whenever(packageName) { pkgName }
            whenever(targetSdkVersion) { Build.VERSION_CODES.S }
            whenever(isEnabled) { true }

            val activityList = listOf(
                    ParsedActivity().apply {
                        domains.forEach {
                            addIntent(
                                    ParsedIntentInfo().apply {
                                        autoVerify = true
                                        addAction(Intent.ACTION_VIEW)
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                        addCategory(Intent.CATEGORY_DEFAULT)
                                        addDataScheme("http")
                                        addDataScheme("https")
                                        addDataPath("/sub", PatternMatcher.PATTERN_LITERAL)
                                        addDataAuthority(it, null)
                                    }
                            )
                        }
                    },
            )

            whenever(activities) { activityList }
        }

        whenever(getPkg()) { pkg }
        whenever(getName()) { pkgName }
        whenever(this.domainSetId) { domainSetId }
        whenever(getInstantApp(anyInt())) { false }
        whenever(firstInstallTime) { 0L }
        whenever(readUserState(0)) { PackageUserState() }
        whenever(readUserState(10)) { PackageUserState() }
        whenever(signatures) { arrayOf(Signature(signature)) }
        whenever(isSystem) { isSystemApp }
    }

    private fun DomainVerificationService.assertState(
        pkg: PackageSetting,
        userId: Int,
        linkHandingAllowed: Boolean = true,
        hostToStateMap: Map<String, Int>
    ) {
        getUserState(pkg.getName(), userId).apply {
            assertThat(this.packageName).isEqualTo(pkg.getName())
            assertThat(this.identifier).isEqualTo(pkg.domainSetId)
            assertThat(this.isLinkHandlingAllowed).isEqualTo(linkHandingAllowed)
            assertThat(this.user.identifier).isEqualTo(userId)
            assertThat(this.hostToStateMap).containsExactlyEntriesIn(hostToStateMap)
        }
    }
}
