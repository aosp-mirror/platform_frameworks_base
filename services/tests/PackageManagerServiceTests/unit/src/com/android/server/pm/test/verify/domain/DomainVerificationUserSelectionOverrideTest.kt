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
import android.content.pm.parsing.component.ParsedActivity
import android.content.pm.parsing.component.ParsedIntentInfo
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserSelection
import android.os.Build
import android.os.PatternMatcher
import android.os.Process
import android.util.ArraySet
import com.android.server.pm.PackageSetting
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.verify.domain.DomainVerificationService
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import java.util.UUID

class DomainVerificationUserSelectionOverrideTest {

    companion object {
        private const val PKG_ONE = "com.test.one"
        private const val PKG_TWO = "com.test.two"
        private val UUID_ONE = UUID.fromString("1b041c96-8d37-4932-a858-561bfac5947c")
        private val UUID_TWO = UUID.fromString("a3389c16-7f9f-4e86-85e3-500d1249c74c")

        private val DOMAIN_ONE =
            DomainVerificationUserSelectionOverrideTest::class.java.packageName

        private const val STATE_NONE = DomainVerificationUserSelection.DOMAIN_STATE_NONE
        private const val STATE_SELECTED = DomainVerificationUserSelection.DOMAIN_STATE_SELECTED
        private const val STATE_VERIFIED = DomainVerificationUserSelection.DOMAIN_STATE_VERIFIED

        private const val USER_ID = 0
    }

    private val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE)
    private val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO)

    fun makeService() =
        DomainVerificationService(mockThrowOnUnmocked {
            // Assume the test has every permission necessary
            whenever(enforcePermission(anyString(), anyInt(), anyInt(), anyString()))
            whenever(checkPermission(anyString(), anyInt(), anyInt())) {
                PackageManager.PERMISSION_GRANTED
            }
        }, mockThrowOnUnmocked {
            whenever(linkedApps) { ArraySet<String>() }
        }, mockThrowOnUnmocked {
            whenever(isChangeEnabled(anyLong(), any())) { true }
        }).apply {
            setConnection(mockThrowOnUnmocked {
                whenever(filterAppAccess(anyString(), anyInt(), anyInt())) { false }
                whenever(scheduleWriteSettings())

                // Need to provide an internal UID so some permission checks are ignored
                whenever(callingUid) { Process.ROOT_UID }
                whenever(callingUserId) { 0 }
                whenever(getPackageSettingLocked(PKG_ONE)) { pkg1 }
                whenever(getPackageSettingLocked(PKG_TWO)) { pkg2 }
                whenever(getPackageLocked(PKG_ONE)) { pkg1.getPkg() }
                whenever(getPackageLocked(PKG_TWO)) { pkg2.getPkg() }
            })
            addPackage(pkg1)
            addPackage(pkg2)

            // Starting state for all tests is to have domain 1 enabled for the first package
            setDomainVerificationUserSelection(UUID_ONE, setOf(DOMAIN_ONE), true, USER_ID)

            assertThat(stateFor(PKG_ONE, DOMAIN_ONE)).isEqualTo(STATE_SELECTED)
        }

    fun mockPkgSetting(pkgName: String, domainSetId: UUID) = mockThrowOnUnmocked<PackageSetting> {
        val pkg = mockThrowOnUnmocked<AndroidPackage> {
            whenever(packageName) { pkgName }
            whenever(targetSdkVersion) { Build.VERSION_CODES.S }

            val activityList = listOf(
                ParsedActivity().apply {
                    addIntent(
                        ParsedIntentInfo().apply {
                            autoVerify = true
                            addAction(Intent.ACTION_VIEW)
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addDataScheme("http")
                            addDataScheme("https")
                            addDataPath("/sub", PatternMatcher.PATTERN_LITERAL)
                            addDataAuthority(DOMAIN_ONE, null)
                        }
                    )
                    addIntent(
                        ParsedIntentInfo().apply {
                            autoVerify = true
                            addAction(Intent.ACTION_VIEW)
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addDataScheme("http")
                            addDataPath("/sub2", PatternMatcher.PATTERN_LITERAL)
                            addDataAuthority("example2.com", null)
                        }
                    )
                },
            )

            whenever(activities) { activityList }
        }

        whenever(getPkg()) { pkg }
        whenever(getName()) { pkgName }
        whenever(this.domainSetId) { domainSetId }
        whenever(getInstantApp(anyInt())) { false }
        whenever(firstInstallTime) { 0L }
    }

    @Test
    fun anotherPackageTakeoverSuccess() {
        val service = makeService()

        // Attempt override by package 2
        service.setDomainVerificationUserSelection(UUID_TWO, setOf(DOMAIN_ONE), true, USER_ID)

        // 1 loses approval
        assertThat(service.stateFor(PKG_ONE, DOMAIN_ONE)).isEqualTo(STATE_NONE)

        // 2 gains approval
        assertThat(service.stateFor(PKG_TWO, DOMAIN_ONE)).isEqualTo(STATE_SELECTED)

        // 2 is the only owner
        assertThat(service.getOwnersForDomain(DOMAIN_ONE, USER_ID).map { it.packageName })
            .containsExactly(PKG_TWO)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anotherPackageTakeoverFailure() {
        val service = makeService()

        // Verify 1 to give it a higher approval level
        service.setDomainVerificationStatus(UUID_ONE, setOf(DOMAIN_ONE),
            DomainVerificationManager.STATE_SUCCESS)
        assertThat(service.stateFor(PKG_ONE, DOMAIN_ONE)).isEqualTo(STATE_VERIFIED)
        assertThat(service.getOwnersForDomain(DOMAIN_ONE, USER_ID).map { it.packageName })
            .containsExactly(PKG_ONE)

        // Attempt override by package 2
        service.setDomainVerificationUserSelection(UUID_TWO, setOf(DOMAIN_ONE), true, USER_ID)
    }

    private fun DomainVerificationService.stateFor(pkgName: String, host: String) =
        getDomainVerificationUserSelection(pkgName, USER_ID)!!.hostToStateMap[host]
}
