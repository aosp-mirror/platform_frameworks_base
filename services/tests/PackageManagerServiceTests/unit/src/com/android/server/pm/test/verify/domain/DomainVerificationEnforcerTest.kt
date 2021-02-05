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
import android.content.pm.PackageUserState
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.parsing.component.ParsedActivity
import android.content.pm.parsing.component.ParsedIntentInfo
import android.os.Build
import android.os.Process
import android.util.ArraySet
import android.util.Singleton
import android.util.SparseArray
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.pm.PackageSetting
import com.android.server.pm.verify.domain.DomainVerificationEnforcer
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal
import com.android.server.pm.verify.domain.DomainVerificationService
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy
import com.android.server.pm.parsing.pkg.AndroidPackage
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
import org.mockito.Mockito.verifyNoMoreInteractions
import org.testng.Assert.assertThrows
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private typealias Enforcer = DomainVerificationEnforcer

@RunWith(Parameterized::class)
class DomainVerificationEnforcerTest {

    val context: Context = InstrumentationRegistry.getInstrumentation().context

    companion object {
        private val INTERNAL_UIDS = listOf(Process.ROOT_UID, Process.SHELL_UID, Process.SYSTEM_UID)
        private const val VERIFIER_UID = Process.FIRST_APPLICATION_UID + 1
        private const val NON_VERIFIER_UID = Process.FIRST_APPLICATION_UID + 2

        private const val TEST_PKG = "com.test"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): Array<Any> {
            val makeEnforcer: (Context) -> DomainVerificationEnforcer = {
                DomainVerificationEnforcer(it)
            }

            val mockPkg = mockThrowOnUnmocked<AndroidPackage> {
                whenever(packageName) { TEST_PKG }
                whenever(targetSdkVersion) { Build.VERSION_CODES.S }
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

            val uuid = UUID.randomUUID()

            // TODO: PackageSetting field encapsulation to move to whenever(name)
            val mockPkgSetting = spyThrowOnUnmocked(
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
                    uuid
                )
            ) {
                whenever(getPkg()) { mockPkg }
                whenever(domainSetId) { uuid }
                whenever(userState) {
                    SparseArray<PackageUserState>().apply {
                        this[0] = PackageUserState()
                    }
                }
            }

            val makeService: (Context) -> Triple<AtomicInteger, AtomicInteger, DomainVerificationService> =
                {
                    val callingUidInt = AtomicInteger(-1)
                    val callingUserIdInt = AtomicInteger(-1)
                    Triple(
                        callingUidInt, callingUserIdInt, DomainVerificationService(
                            it,
                            mockThrowOnUnmocked { whenever(linkedApps) { ArraySet<String>() } },
                            mockThrowOnUnmocked {
                                whenever(
                                    isChangeEnabled(
                                        anyLong(),
                                        any()
                                    )
                                ) { true }
                            }).apply {
                                setConnection(mockThrowOnUnmocked {
                                    whenever(callingUid) { callingUidInt.get() }
                                    whenever(callingUserId) { callingUserIdInt.get() }
                                    whenever(getPackageSettingLocked(TEST_PKG)) { mockPkgSetting }
                                    whenever(getPackageLocked(TEST_PKG)) { mockPkg }
                                    whenever(schedule(anyInt(), any()))
                                    whenever(scheduleWriteSettings())
                                })
                        }
                    )
                }

            fun enforcer(
                type: Type,
                name: String,
                block: DomainVerificationEnforcer.(
                    callingUid: Int, callingUserId: Int, userId: Int, proxy: DomainVerificationProxy
                ) -> Unit
            ) = Params(
                type,
                makeEnforcer,
                name
            ) { enforcer, callingUid, callingUserId, userId, proxy ->
                enforcer.block(callingUid, callingUserId, userId, proxy)
            }

            fun service(
                type: Type,
                name: String,
                block: DomainVerificationService.(
                    callingUid: Int, callingUserId: Int, userId: Int
                ) -> Unit
            ) = Params(
                type,
                makeService,
                name
            ) { uidAndUserIdAndService, callingUid, callingUserId, userId, proxy ->
                val (callingUidInt, callingUserIdInt, service) = uidAndUserIdAndService
                callingUidInt.set(callingUid)
                callingUserIdInt.set(callingUserId)
                service.setProxy(proxy)
                service.addPackage(mockPkgSetting)
                service.block(callingUid, callingUserId, userId)
            }

            return arrayOf(
                enforcer(Type.INTERNAL, "internal") { callingUid, _, _, _ ->
                    assertInternal(callingUid)
                },
                enforcer(Type.QUERENT, "approvedQuerent") { callingUid, _, _, proxy ->
                    assertApprovedQuerent(callingUid, proxy)
                },
                enforcer(Type.VERIFIER, "approvedVerifier") { callingUid, _, _, proxy ->
                    assertApprovedVerifier(callingUid, proxy)
                },
                enforcer(
                    Type.SELECTOR,
                    "approvedUserSelector"
                ) { callingUid, callingUserId, userId, _ ->
                    assertApprovedUserSelector(callingUid, callingUserId, userId)
                },

                service(Type.INTERNAL, "setStatusInternalPackageName") { _, _, _ ->
                    setDomainVerificationStatusInternal(
                        TEST_PKG,
                        DomainVerificationManager.STATE_SUCCESS,
                        ArraySet(setOf("example.com"))
                    )
                },
                service(Type.INTERNAL, "setUserSelectionInternal") { _, _, userId ->
                    setDomainVerificationUserSelectionInternal(
                        userId,
                        TEST_PKG,
                        false,
                        ArraySet(setOf("example.com"))
                    )
                },
                service(Type.INTERNAL, "verifyPackages") { _, _, _ ->
                    verifyPackages(listOf(TEST_PKG), true)
                },
                service(Type.INTERNAL, "clearState") { _, _, _ ->
                    clearDomainVerificationState(listOf(TEST_PKG))
                },
                service(Type.INTERNAL, "clearUserSelections") { _, _, userId ->
                    clearUserSelections(listOf(TEST_PKG), userId)
                },
                service(Type.VERIFIER, "getPackageNames") { _, _, _ ->
                    validVerificationPackageNames
                },
                service(Type.QUERENT, "getInfo") { _, _, _ ->
                    getDomainVerificationInfo(TEST_PKG)
                },
                service(Type.VERIFIER, "setStatus") { _, _, _ ->
                    setDomainVerificationStatus(
                        uuid,
                        setOf("example.com"),
                        DomainVerificationManager.STATE_SUCCESS
                    )
                },
                service(Type.VERIFIER, "setStatusInternalUid") { callingUid, _, _ ->
                    setDomainVerificationStatusInternal(
                        callingUid,
                        uuid,
                        setOf("example.com"),
                        DomainVerificationManager.STATE_SUCCESS
                    )
                },
                service(Type.SELECTOR, "setLinkHandlingAllowed") { _, _, _ ->
                    setDomainVerificationLinkHandlingAllowed(TEST_PKG, true)
                },
                service(Type.SELECTOR_USER, "setLinkHandlingAllowedUserId") { _, _, userId ->
                    setDomainVerificationLinkHandlingAllowed(TEST_PKG, true, userId)
                },
                service(Type.SELECTOR, "getUserSelection") { _, _, _ ->
                    getDomainVerificationUserSelection(TEST_PKG)
                },
                service(Type.SELECTOR_USER, "getUserSelectionUserId") { _, _, userId ->
                    getDomainVerificationUserSelection(TEST_PKG, userId)
                },
                service(Type.SELECTOR, "setUserSelection") { _, _, _ ->
                    setDomainVerificationUserSelection(uuid, setOf("example.com"), true)
                },
                service(Type.SELECTOR_USER, "setUserSelectionUserId") { _, _, userId ->
                    setDomainVerificationUserSelection(uuid, setOf("example.com"), true, userId)
                },
            )
        }

        data class Params<T : Any>(
            val type: Type,
            val construct: (context: Context) -> T,
            val name: String,
            private val method: (
                T, callingUid: Int, callingUserId: Int, userId: Int, proxy: DomainVerificationProxy
            ) -> Unit
        ) {
            override fun toString() = "${type}_$name"

            fun runMethod(
                target: Any,
                callingUid: Int,
                callingUserId: Int,
                userId: Int,
                proxy: DomainVerificationProxy
            ) {
                @Suppress("UNCHECKED_CAST")
                method(target as T, callingUid, callingUserId, userId, proxy)
            }
        }
    }

    @Parameterized.Parameter(0)
    lateinit var params: Params<*>

    private val proxy: DomainVerificationProxy = mockThrowOnUnmocked {
        whenever(isCallerVerifier(VERIFIER_UID)) { true }
        whenever(isCallerVerifier(NON_VERIFIER_UID)) { false }
        whenever(sendBroadcastForPackages(any()))
    }

    @Test
    fun verify() {
        when (params.type) {
            Type.INTERNAL -> internal()
            Type.QUERENT -> approvedQuerent()
            Type.VERIFIER -> approvedVerifier()
            Type.SELECTOR -> approvedUserSelector(verifyCrossUser = false)
            Type.SELECTOR_USER -> approvedUserSelector(verifyCrossUser = true)
        }.run { /*exhaust*/ }
    }

    fun internal() {
        val context: Context = mockThrowOnUnmocked()
        val target = params.construct(context)

        INTERNAL_UIDS.forEach { runMethod(target, it) }
        assertThrows(SecurityException::class.java) { runMethod(target, VERIFIER_UID) }
        assertThrows(SecurityException::class.java) { runMethod(target, NON_VERIFIER_UID) }
    }

    fun approvedQuerent() {
        val allowUserSelection = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            whenever(
                enforcePermission(
                    eq(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION),
                    anyInt(), anyInt(), anyString()
                )
            ) {
                if (!allowUserSelection.get()) {
                    throw SecurityException()
                }
            }
        }
        val target = params.construct(context)

        INTERNAL_UIDS.forEach { runMethod(target, it) }

        verifyNoMoreInteractions(context)

        runMethod(target, VERIFIER_UID)
        assertThrows(SecurityException::class.java) { runMethod(target, NON_VERIFIER_UID) }

        allowUserSelection.set(true)

        runMethod(target, NON_VERIFIER_UID)
    }

    fun approvedVerifier() {
        val shouldThrow = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            whenever(
                enforcePermission(
                    eq(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT),
                    anyInt(), anyInt(), anyString()
                )
            ) {
                if (shouldThrow.get()) {
                    throw SecurityException()
                }
            }
        }
        val target = params.construct(context)

        INTERNAL_UIDS.forEach { runMethod(target, it) }

        verifyNoMoreInteractions(context)

        runMethod(target, VERIFIER_UID)
        assertThrows(SecurityException::class.java) { runMethod(target, NON_VERIFIER_UID) }

        shouldThrow.set(true)

        assertThrows(SecurityException::class.java) { runMethod(target, VERIFIER_UID) }
        assertThrows(SecurityException::class.java) { runMethod(target, NON_VERIFIER_UID) }
    }

    fun approvedUserSelector(verifyCrossUser: Boolean) {
        val allowUserSelection = AtomicBoolean(true)
        val allowInteractAcrossUsers = AtomicBoolean(true)
        val context: Context = mockThrowOnUnmocked {
            whenever(
                enforcePermission(
                    eq(android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION),
                    anyInt(), anyInt(), anyString()
                )
            ) {
                if (!allowUserSelection.get()) {
                    throw SecurityException()
                }
            }
            whenever(
                enforcePermission(
                    eq(android.Manifest.permission.INTERACT_ACROSS_USERS),
                    anyInt(), anyInt(), anyString()
                )
            ) {
                if (!allowInteractAcrossUsers.get()) {
                    throw SecurityException()
                }
            }
        }
        val target = params.construct(context)

        fun runEachTestCaseWrapped(
            callingUserId: Int,
            targetUserId: Int,
            block: (testCase: () -> Unit) -> Unit = { it.invoke() }
        ) {
            block { runMethod(target, VERIFIER_UID, callingUserId, targetUserId) }
            block { runMethod(target, NON_VERIFIER_UID, callingUserId, targetUserId) }
        }

        val callingUserId = 0
        val notCallingUserId = 1

        runEachTestCaseWrapped(callingUserId, callingUserId)
        if (verifyCrossUser) {
            runEachTestCaseWrapped(callingUserId, notCallingUserId)
        }

        allowInteractAcrossUsers.set(false)

        runEachTestCaseWrapped(callingUserId, callingUserId)

        if (verifyCrossUser) {
            runEachTestCaseWrapped(callingUserId, notCallingUserId) {
                assertThrows(SecurityException::class.java, it)
            }
        }

        allowUserSelection.set(false)

        runEachTestCaseWrapped(callingUserId, callingUserId) {
            assertThrows(SecurityException::class.java, it)
        }
        if (verifyCrossUser) {
            runEachTestCaseWrapped(callingUserId, notCallingUserId) {
                assertThrows(SecurityException::class.java, it)
            }
        }

        allowInteractAcrossUsers.set(true)

        runEachTestCaseWrapped(callingUserId, callingUserId) {
            assertThrows(SecurityException::class.java, it)
        }
        if (verifyCrossUser) {
            runEachTestCaseWrapped(callingUserId, notCallingUserId) {
                assertThrows(SecurityException::class.java, it)
            }
        }
    }

    private fun runMethod(target: Any, callingUid: Int, callingUserId: Int = 0, userId: Int = 0) {
        params.runMethod(target, callingUid, callingUserId, userId, proxy)
    }

    enum class Type {
        // System/shell only
        INTERNAL,

        // INTERNAL || domain verification agent || user setting permission holder
        QUERENT,

        // INTERNAL || domain verification agent
        VERIFIER,

        // Holding the user setting permission
        SELECTOR,

        // Holding the user setting permission, but targeting cross user
        SELECTOR_USER
    }
}
