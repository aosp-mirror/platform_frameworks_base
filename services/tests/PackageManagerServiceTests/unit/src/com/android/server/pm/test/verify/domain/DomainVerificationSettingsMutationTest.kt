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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageUserState
import android.content.pm.parsing.component.ParsedActivity
import android.content.pm.parsing.component.ParsedIntentInfo
import android.content.pm.verify.domain.DomainVerificationState
import android.os.Build
import android.os.Process
import android.util.ArraySet
import android.util.SparseArray
import com.android.server.pm.PackageSetting
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.test.verify.domain.DomainVerificationTestUtils.mockPackageSettings
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal
import com.android.server.pm.verify.domain.DomainVerificationService
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.spyThrowOnUnmocked
import com.android.server.testutils.whenever
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import java.io.File
import java.util.UUID

@RunWith(Parameterized::class)
class DomainVerificationSettingsMutationTest {

    companion object {
        private const val TEST_PKG = "com.test"

        // Pretend to be the system. This class doesn't verify any enforcement behavior.
        private const val TEST_UID = Process.SYSTEM_UID
        private const val TEST_USER_ID = 10
        private val TEST_UUID = UUID.fromString("5168e42e-327e-432b-b562-cfb553518a70")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): Array<Any> {
            val context: Context = mockThrowOnUnmocked {
                whenever(
                    enforcePermission(
                        eq(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT),
                        anyInt(), anyInt(), anyString()
                    )
                )
                whenever(
                    enforcePermission(
                        eq(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION),
                        anyInt(), anyInt(), anyString()
                    )
                )
                whenever(
                    enforcePermission(
                        eq(android.Manifest.permission.INTERACT_ACROSS_USERS),
                        anyInt(), anyInt(), anyString()
                    )
                )
                whenever(
                    enforcePermission(
                        eq(android.Manifest.permission.SET_PREFERRED_APPLICATIONS),
                        anyInt(), anyInt(), anyString()
                    )
                )
            }
            val proxy: DomainVerificationProxy = mockThrowOnUnmocked {
                whenever(isCallerVerifier(anyInt())) { true }
                whenever(sendBroadcastForPackages(any()))
            }

            val makeService: (DomainVerificationManagerInternal.Connection) -> DomainVerificationService =
                { connection ->
                    DomainVerificationService(
                        context,
                        mockThrowOnUnmocked { whenever(linkedApps) { ArraySet<String>() } },
                        mockThrowOnUnmocked {
                            whenever(isChangeEnabledInternalNoLogging(anyLong(), any())) { true }
                        }).apply {
                        setConnection(connection)
                    }
                }

            fun service(name: String, block: DomainVerificationService.() -> Unit) =
                Params(makeService, name) { service ->
                    service.proxy = proxy
                    service.addPackage(mockPkgSetting())
                    service.block()
                }

            return arrayOf(
                service("clearPackage") {
                    clearPackage(TEST_PKG)
                },
                service("clearUser") {
                    clearUser(TEST_USER_ID)
                },
                service("clearState") {
                    clearDomainVerificationState(listOf(TEST_PKG))
                },
                service("clearUserStates") {
                    clearUserStates(listOf(TEST_PKG), TEST_USER_ID)
                },
                service("setStatus") {
                    setDomainVerificationStatus(
                        TEST_UUID,
                        setOf("example.com"),
                        DomainVerificationState.STATE_SUCCESS
                    )
                },
                service("setStatusInternalPackageName") {
                    setDomainVerificationStatusInternal(
                        TEST_PKG,
                        DomainVerificationState.STATE_SUCCESS,
                        ArraySet(setOf("example.com"))
                    )
                },
                service("setStatusInternalUid") {
                    setDomainVerificationStatusInternal(
                        TEST_UID,
                        TEST_UUID,
                        setOf("example.com"),
                        DomainVerificationState.STATE_SUCCESS
                    )
                },
                service("setLinkHandlingAllowedUserId") {
                    setDomainVerificationLinkHandlingAllowed(TEST_PKG, true, TEST_USER_ID)
                },
                service("setLinkHandlingAllowedInternal") {
                    setDomainVerificationLinkHandlingAllowedInternal(TEST_PKG, true, TEST_USER_ID)
                },
                service("setUserStateUserId") {
                    setDomainVerificationUserSelection(
                        TEST_UUID,
                        setOf("example.com"),
                        true,
                        TEST_USER_ID
                    )
                },
                service("setUserStateInternal") {
                    setDomainVerificationUserSelectionInternal(
                        TEST_USER_ID,
                        TEST_PKG,
                        true,
                        ArraySet(setOf("example.com")),
                    )
                },
                service("setLegacyUserState") {
                    setLegacyUserState(
                        TEST_PKG,
                        TEST_USER_ID,
                        PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER
                    )
                },
            )
        }

        data class Params(
            val construct: (
                DomainVerificationManagerInternal.Connection
            ) -> DomainVerificationService,
            val name: String,
            val method: (DomainVerificationService) -> Unit
        ) {
            override fun toString() = name
        }


        fun mockPkg() = mockThrowOnUnmocked<AndroidPackage> {
            whenever(packageName) { TEST_PKG }
            whenever(targetSdkVersion) { Build.VERSION_CODES.S }
            whenever(isEnabled) { true }
            whenever(activities) {
                listOf(
                    ParsedActivity().apply {
                        addIntent(
                            ParsedIntentInfo().apply {
                                autoVerify = true
                                addAction(Intent.ACTION_VIEW)
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                addCategory(Intent.CATEGORY_DEFAULT)
                                addDataScheme("https")
                                addDataAuthority("example.com", null)
                            }
                        )
                    }
                )
            }
        }

        // TODO: PackageSetting field encapsulation to move to whenever(name)
        fun mockPkgSetting() = spyThrowOnUnmocked(
            PackageSetting(
                TEST_PKG,
                TEST_PKG,
                File("/test"),
                null,
                null,
                null,
                null,
                1,
                0,
                0,
                0,
                null,
                null,
                null,
                TEST_UUID
            )
        ) {
            whenever(getName()) { TEST_PKG }
            whenever(getPkg()) { mockPkg() }
            whenever(domainSetId) { TEST_UUID }
            whenever(readUserState(0)) { PackageUserState() }
            whenever(readUserState(10)) { PackageUserState() }
            whenever(getInstantApp(anyInt())) { false }
            whenever(isSystem()) { false }
        }
    }

    @Parameterized.Parameter(0)
    lateinit var params: Params

    @Test
    fun writeScheduled() {
        val connection = mockConnection()
        val service = params.construct(connection)
        params.method(service)

        verify(connection).scheduleWriteSettings()
    }

    private fun mockConnection(): DomainVerificationManagerInternal.Connection =
        mockThrowOnUnmocked {
            whenever(callingUid) { TEST_UID }
            whenever(callingUserId) { TEST_USER_ID }
            mockPackageSettings {
                when (it) {
                    TEST_PKG -> mockPkgSetting()
                    else -> null
                }
            }
            whenever(schedule(anyInt(), any()))
            whenever(scheduleWriteSettings())

            // This doesn't check for visibility; that's done in the enforcer test
            whenever(filterAppAccess(anyString(), anyInt(), anyInt())) { false }
            whenever(doesUserExist(0)) { true }
            whenever(doesUserExist(10)) { true }
        }
}
