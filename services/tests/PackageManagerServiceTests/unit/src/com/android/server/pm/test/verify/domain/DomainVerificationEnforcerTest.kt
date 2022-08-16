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
import android.content.pm.SigningDetails
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationState
import android.os.Build
import android.os.Process
import android.util.ArraySet
import android.util.IndentingPrintWriter
import android.util.SparseArray
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.pm.Computer
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.pm.pkg.PackageUserStateInternal
import com.android.server.pm.pkg.component.ParsedActivityImpl
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl
import com.android.server.pm.verify.domain.DomainVerificationEnforcer
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal
import com.android.server.pm.verify.domain.DomainVerificationService
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoMoreInteractions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.fail

@RunWith(Parameterized::class)
class DomainVerificationEnforcerTest {

    val context: Context = InstrumentationRegistry.getInstrumentation().context

    companion object {
        private val INTERNAL_UIDS = listOf(Process.ROOT_UID, Process.SHELL_UID, Process.SYSTEM_UID)
        private const val VERIFIER_UID = Process.FIRST_APPLICATION_UID + 1
        private const val NON_VERIFIER_UID = Process.FIRST_APPLICATION_UID + 2

        private const val VISIBLE_PKG = "com.test.visible"
        private val VISIBLE_UUID = UUID.fromString("8db01272-270d-4606-a3db-bb35228ff9a2")
        private const val INVISIBLE_PKG = "com.test.invisible"
        private val INVISIBLE_UUID = UUID.fromString("16dcb029-d96c-4a19-833a-4c9d72e2ebc3")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): Array<Any> {
            val visiblePkg = mockPkg(VISIBLE_PKG)
            val visiblePkgState = mockPkgState(VISIBLE_PKG, VISIBLE_UUID)
            val invisiblePkg = mockPkg(INVISIBLE_PKG)
            val invisiblePkgState = mockPkgState(INVISIBLE_PKG, INVISIBLE_UUID)

            val makeEnforcer: (Context) -> DomainVerificationEnforcer = {
                DomainVerificationEnforcer(it).apply {
                    setCallback(mockThrowOnUnmocked {
                        whenever(filterAppAccess(eq(VISIBLE_PKG), anyInt(), anyInt())) { false }
                        whenever(filterAppAccess(eq(INVISIBLE_PKG), anyInt(), anyInt())) {
                            true
                        }
                        whenever(doesUserExist(anyInt())) { (arguments[0] as Int) <= 1 }
                    })
                }
            }

            val makeService: (Context) -> Triple<AtomicInteger, AtomicInteger,
                    DomainVerificationService> = {
                val callingUidInt = AtomicInteger(-1)
                val callingUserIdInt = AtomicInteger(-1)

                val connection: DomainVerificationManagerInternal.Connection =
                    mockThrowOnUnmocked {
                        whenever(callingUid) { callingUidInt.get() }
                        whenever(callingUserId) { callingUserIdInt.get() }
                        whenever(snapshot()) {
                            mockThrowOnUnmocked {
                                whenever(getPackageStateInternal(anyString())) {
                                    when (getArgument<String>(0)) {
                                        VISIBLE_PKG -> visiblePkgState
                                        INVISIBLE_PKG -> invisiblePkgState
                                        else -> null
                                    }
                                }
                            }
                        }
                        whenever(schedule(anyInt(), any()))
                        whenever(scheduleWriteSettings())
                        whenever(filterAppAccess(eq(VISIBLE_PKG), anyInt(), anyInt())) { false }
                        whenever(filterAppAccess(eq(INVISIBLE_PKG), anyInt(), anyInt())) {
                            true
                        }
                        whenever(doesUserExist(anyInt())) { (arguments[0] as Int) <= 1 }
                    }
                val service = DomainVerificationService(
                    it,
                    mockThrowOnUnmocked { whenever(linkedApps) { ArraySet<String>() } },
                    mockThrowOnUnmocked {
                        whenever(isChangeEnabledInternalNoLogging(anyLong(), any())) { true }
                    }).apply {
                    setConnection(connection)
                }

                Triple(callingUidInt, callingUserIdInt, service)
            }

            fun enforcer(
                type: Type,
                name: String,
                block: DomainVerificationEnforcer.(Params.Input<DomainVerificationEnforcer>) -> Any?
            ) = Params(type, makeEnforcer, name) {
                it.target.block(it)
            }

            fun service(
                type: Type,
                name: String,
                block: DomainVerificationService.(Params.Input<Triple<AtomicInteger, AtomicInteger, DomainVerificationService>>) -> Any?
            ) = Params(type, makeService, name) {
                val (callingUidInt, callingUserIdInt, service) = it.target
                callingUidInt.set(it.callingUid)
                callingUserIdInt.set(it.callingUserId)
                service.proxy = it.proxy
                service.addPackage(visiblePkgState)
                service.addPackage(invisiblePkgState)
                service.block(it)
            }

            return arrayOf(
                enforcer(Type.INTERNAL, "internal") {
                    assertInternal(it.callingUid)
                },
                enforcer(Type.QUERENT, "approvedQuerent") {
                    assertApprovedQuerent(it.callingUid, it.proxy)
                },
                enforcer(Type.VERIFIER, "approvedVerifier") {
                    assertApprovedVerifier(it.callingUid, it.proxy)
                },
                enforcer(
                    Type.SELECTION_QUERENT,
                    "approvedUserStateQuerent"
                ) {
                    assertApprovedUserStateQuerent(
                        it.callingUid, it.callingUserId,
                        it.targetPackageName, it.userId
                    )
                },
                enforcer(
                    Type.SELECTOR,
                    "approvedUserSelector"
                ) {
                    assertApprovedUserSelector(
                        it.callingUid, it.callingUserId,
                        it.targetPackageName, it.userId
                    )
                },
                service(Type.INTERNAL, "setStatusInternalPackageName") {
                    setDomainVerificationStatusInternal(
                        it.targetPackageName,
                        DomainVerificationState.STATE_SUCCESS,
                        ArraySet(setOf("example.com"))
                    )
                },
                service(Type.INTERNAL, "setUserStateInternal") {
                    setDomainVerificationUserSelectionInternal(
                        it.userId,
                        it.targetPackageName,
                        false,
                        ArraySet(setOf("example.com"))
                    )
                },
                service(Type.INTERNAL, "verifyPackages") {
                    verifyPackages(listOf(it.targetPackageName), true)
                },
                service(Type.INTERNAL, "clearState") {
                    clearDomainVerificationState(listOf(it.targetPackageName))
                },
                service(Type.INTERNAL, "clearUserStates") {
                    clearUserStates(listOf(it.targetPackageName), it.userId)
                },
                service(Type.VERIFIER, "queryValidPackageNames") {
                    queryValidVerificationPackageNames()
                },
                service(Type.QUERENT, "getInfo") {
                    getDomainVerificationInfo(it.targetPackageName)
                },
                service(Type.QUERENT, "printState") {
                    printState(mock(IndentingPrintWriter::class.java), null, null)
                },
                service(Type.QUERENT, "printStateInternal") {
                    printState(mock(Computer::class.java), mock(IndentingPrintWriter::class.java),
                        null, null)
                },
                service(Type.VERIFIER, "setStatus") {
                    setDomainVerificationStatus(
                        it.targetDomainSetId,
                        setOf("example.com"),
                        DomainVerificationState.STATE_SUCCESS
                    )
                },
                service(Type.VERIFIER, "setStatusInternalUid") {
                    setDomainVerificationStatusInternal(
                        it.callingUid,
                        it.targetDomainSetId,
                        setOf("example.com"),
                        DomainVerificationState.STATE_SUCCESS
                    )
                },
                service(Type.SELECTOR_USER, "setLinkHandlingAllowedUserId") {
                    setDomainVerificationLinkHandlingAllowed(it.targetPackageName, true, it.userId)
                },
                service(Type.SELECTION_QUERENT, "getUserStateUserId") {
                    getDomainVerificationUserState(it.targetPackageName, it.userId)
                },
                service(Type.SELECTOR_USER, "setUserStateUserId") {
                    setDomainVerificationUserSelection(
                        it.targetDomainSetId,
                        setOf("example.com"),
                        true,
                        it.userId
                    )
                },
                service(Type.LEGACY_SELECTOR, "setLegacyUserState") {
                    setLegacyUserState(
                        it.targetPackageName, it.userId,
                        PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER
                    )
                },
                service(Type.LEGACY_QUERENT, "getLegacyUserState") {
                    getLegacyState(it.targetPackageName, it.userId)
                },
                service(Type.OWNER_QUERENT_USER, "getOwnersForDomainUserId") {
                    // Re-use package name, since the result itself isn't relevant
                    getOwnersForDomain(it.targetPackageName, it.userId)
                },
            )
        }

        data class Params<T : Any>(
            val type: Type,
            val construct: (context: Context) -> T,
            val name: String,
            private val method: (Input<T>) -> Any?
        ) {
            override fun toString() = "${type}_$name"

            fun runMethod(
                target: Any,
                callingUid: Int,
                callingUserId: Int,
                userId: Int,
                targetPackageName: String,
                targetDomainSetId: UUID,
                proxy: DomainVerificationProxy
            ): Any? = method(
                Input(
                    @Suppress("UNCHECKED_CAST")
                    target as T,
                    callingUid,
                    callingUserId,
                    userId,
                    targetPackageName,
                    targetDomainSetId,
                    proxy
                )
            )

            data class Input<T>(
                val target: T,
                val callingUid: Int,
                val callingUserId: Int,
                val userId: Int,
                val targetPackageName: String,
                val targetDomainSetId: UUID,
                val proxy: DomainVerificationProxy
            )
        }

        fun mockPkg(packageName: String) = mockThrowOnUnmocked<AndroidPackage> {
            whenever(this.packageName) { packageName }
            whenever(targetSdkVersion) { Build.VERSION_CODES.S }
            whenever(isEnabled) { true }
            whenever(activities) {
                listOf(
                    ParsedActivityImpl().apply {
                        addIntent(
                            ParsedIntentInfoImpl()
                                .apply {
                                intentFilter.apply {
                                    autoVerify = true
                                    addAction(Intent.ACTION_VIEW)
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    addDataScheme("https")
                                    addDataAuthority("example.com", null)
                                }
                            }
                        )
                    }
                )
            }
            whenever(signingDetails) { SigningDetails.UNKNOWN }
        }

        fun mockPkgState(packageName: String, domainSetId: UUID) =
            mockThrowOnUnmocked<PackageStateInternal> {
                whenever(this.packageName) { packageName }
                whenever(pkg) { mockPkg(packageName) }
                whenever(this.domainSetId) { domainSetId }
                whenever(getUserStateOrDefault(0)) { PackageUserStateInternal.DEFAULT }
                whenever(getUserStateOrDefault(1)) { PackageUserStateInternal.DEFAULT }
                whenever(userStates) {
                    SparseArray<PackageUserStateInternal>().apply {
                        this[0] = PackageUserStateInternal.DEFAULT
                        this[1] = PackageUserStateInternal.DEFAULT
                    }
                }
                whenever(isSystem) { false }
                whenever(signingDetails) { SigningDetails.UNKNOWN }
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
            Type.SELECTION_QUERENT -> approvedUserStateQuerent(verifyCrossUser = true)
            Type.SELECTOR -> approvedUserSelector(verifyCrossUser = false)
            Type.SELECTOR_USER -> approvedUserSelector(verifyCrossUser = true)
            Type.LEGACY_QUERENT -> legacyQuerent()
            Type.LEGACY_SELECTOR -> legacyUserSelector()
            Type.OWNER_QUERENT -> ownerQuerent(verifyCrossUser = false)
            Type.OWNER_QUERENT_USER -> ownerQuerent(verifyCrossUser = true)
        }.run { /*exhaust*/ }
    }

    private fun internal() {
        val context: Context = mockThrowOnUnmocked()
        val target = params.construct(context)

        // Internal doesn't care about visibility
        listOf(true, false).forEach { visible ->
            INTERNAL_UIDS.forEach { runMethod(target, it, visible) }
            assertFails { runMethod(target, VERIFIER_UID, visible) }
            assertFails {
                runMethod(target, NON_VERIFIER_UID, visible)
            }
        }
    }

    private fun approvedQuerent() {
        val allowUserState = AtomicBoolean(false)
        val allowPreferredApps = AtomicBoolean(false)
        val allowQueryAll = AtomicBoolean(false)
        val allowDump = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowUserState,
                android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION
            )
            initPermission(
                allowPreferredApps,
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS
            )
            initPermission(allowQueryAll, android.Manifest.permission.QUERY_ALL_PACKAGES)
            initPermission(allowDump, android.Manifest.permission.DUMP)
        }
        val target = params.construct(context)

        INTERNAL_UIDS.forEach { runMethod(target, it) }

        verifyNoMoreInteractions(context)

        assertFails { runMethod(target, VERIFIER_UID) }
        assertFails { runMethod(target, NON_VERIFIER_UID) }

        // Check that the verifier only needs QUERY_ALL to pass
        allowQueryAll.set(true)
        runMethod(target, VERIFIER_UID)
        allowQueryAll.set(false)

        allowPreferredApps.set(true)

        assertFails { runMethod(target, NON_VERIFIER_UID) }

        allowUserState.set(true)

        assertFails { runMethod(target, NON_VERIFIER_UID) }

        allowQueryAll.set(true)

        assertFails { runMethod(target, NON_VERIFIER_UID) }

        allowDump.set(true)

        runMethod(target, NON_VERIFIER_UID)
    }

    private fun approvedVerifier() {
        val allowDomainVerificationAgent = AtomicBoolean(false)
        val allowIntentVerificationAgent = AtomicBoolean(false)
        val allowQueryAll = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowDomainVerificationAgent,
                android.Manifest.permission.DOMAIN_VERIFICATION_AGENT
            )
            initPermission(
                allowIntentVerificationAgent,
                android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT
            )
            initPermission(allowQueryAll, android.Manifest.permission.QUERY_ALL_PACKAGES)
        }
        val target = params.construct(context)

        INTERNAL_UIDS.forEach { runMethod(target, it) }

        verifyNoMoreInteractions(context)

        assertFails { runMethod(target, VERIFIER_UID) }
        assertFails { runMethod(target, NON_VERIFIER_UID) }

        allowDomainVerificationAgent.set(true)

        assertFails { runMethod(target, VERIFIER_UID) }
        assertFails { runMethod(target, NON_VERIFIER_UID) }

        allowQueryAll.set(true)

        runMethod(target, VERIFIER_UID)
        assertFails { runMethod(target, NON_VERIFIER_UID) }

        // Check that v1 verifiers are also allowed through
        allowDomainVerificationAgent.set(false)
        allowIntentVerificationAgent.set(true)

        runMethod(target, VERIFIER_UID)
        assertFails { runMethod(target, NON_VERIFIER_UID) }
    }

    private fun approvedUserStateQuerent(verifyCrossUser: Boolean) {
        val allowInteractAcrossUsers = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowInteractAcrossUsers,
                android.Manifest.permission.INTERACT_ACROSS_USERS
            )
        }
        val target = params.construct(context)

        fun runTestCases(callingUserId: Int, targetUserId: Int, throws: Boolean) {
            // User selector makes no distinction by UID
            val allUids = INTERNAL_UIDS + VERIFIER_UID + NON_VERIFIER_UID
            runCrossUserMethod(allUids, target, callingUserId, targetUserId, throws)
        }

        val callingUserId = 0
        val notCallingUserId = 1

        runTestCases(callingUserId, callingUserId, throws = false)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowInteractAcrossUsers.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = false)
        }
    }

    private fun approvedUserSelector(verifyCrossUser: Boolean) {
        val allowUserState = AtomicBoolean(false)
        val allowInteractAcrossUsers = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowUserState,
                android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION
            )
            initPermission(
                allowInteractAcrossUsers,
                android.Manifest.permission.INTERACT_ACROSS_USERS
            )
        }
        val target = params.construct(context)

        fun runTestCases(callingUserId: Int, targetUserId: Int, throws: Boolean) {
            // User selector makes no distinction by UID
            val allUids = INTERNAL_UIDS + VERIFIER_UID + NON_VERIFIER_UID
            runCrossUserMethod(allUids, target, callingUserId, targetUserId, throws)
        }

        val callingUserId = 0
        val notCallingUserId = 1

        runTestCases(callingUserId, callingUserId, throws = true)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowUserState.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowInteractAcrossUsers.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = false)
        }
    }

    private fun legacyUserSelector() {
        val allowInteractAcrossUsers = AtomicBoolean(false)
        val allowPreferredApps = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowInteractAcrossUsers,
                android.Manifest.permission.INTERACT_ACROSS_USERS
            )
            initPermission(
                allowPreferredApps,
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS
            )
        }
        val target = params.construct(context)

        fun runTestCases(callingUserId: Int, targetUserId: Int, throws: Boolean) {
            // Legacy makes no distinction by UID
            val allUids = INTERNAL_UIDS + VERIFIER_UID + NON_VERIFIER_UID
            // The legacy selector does a silent failure when the user IDs don't match, so it
            // cannot verify the non-existent user ID check, as it will not throw an Exception.
            runCrossUserMethod(allUids, target, callingUserId, targetUserId, throws,
                verifyUserIdCheck = false)
        }

        val callingUserId = 0
        val notCallingUserId = 1

        runTestCases(callingUserId, callingUserId, throws = true)
        runTestCases(callingUserId, notCallingUserId, throws = true)

        allowPreferredApps.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        runTestCases(callingUserId, notCallingUserId, throws = true)

        allowInteractAcrossUsers.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        runTestCases(callingUserId, notCallingUserId, throws = false)
    }

    private fun legacyQuerent() {
        val allowInteractAcrossUsers = AtomicBoolean(false)
        val allowInteractAcrossUsersFull = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowInteractAcrossUsers,
                android.Manifest.permission.INTERACT_ACROSS_USERS
            )
            initPermission(
                allowInteractAcrossUsersFull,
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
            )
        }
        val target = params.construct(context)

        // Legacy code can return PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED
        // as an error code. This is distinct from the class level assertFails as unfortunately
        // the same number, 0, is used in opposite contexts, where it does represent a failure
        // for this legacy case, but not for the modern APIs.
        fun assertFailsLegacy(block: () -> Any?) {
            try {
                val value = block()
                if ((value as? Int)
                        != PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED
                ) {
                    throw AssertionError("Expected call to return false, was $value")
                }
            } catch (e: SecurityException) {
            } catch (e: PackageManager.NameNotFoundException) {
                // Any of these 2 exceptions are considered failures, which is expected
            }
        }

        fun runTestCases(callingUserId: Int, targetUserId: Int, throws: Boolean) {
            // Legacy makes no distinction by UID
            val allUids = INTERNAL_UIDS + VERIFIER_UID + NON_VERIFIER_UID
            runCrossUserMethod(allUids, target, callingUserId, targetUserId, throws,
                    assertFailsMethod = ::assertFailsLegacy)
        }

        val callingUserId = 0
        val notCallingUserId = 1

        runTestCases(callingUserId, callingUserId, throws = false)
        runTestCases(callingUserId, notCallingUserId, throws = true)

        // Legacy requires the _FULL permission, so this should continue to fail
        allowInteractAcrossUsers.set(true)
        runTestCases(callingUserId, callingUserId, throws = false)
        runTestCases(callingUserId, notCallingUserId, throws = true)

        allowInteractAcrossUsersFull.set(true)
        runTestCases(callingUserId, callingUserId, throws = false)
        runTestCases(callingUserId, notCallingUserId, throws = false)
    }

    private fun ownerQuerent(verifyCrossUser: Boolean) {
        val allowQueryAll = AtomicBoolean(false)
        val allowUserState = AtomicBoolean(false)
        val allowInteractAcrossUsers = AtomicBoolean(false)
        val context: Context = mockThrowOnUnmocked {
            initPermission(
                allowQueryAll,
                android.Manifest.permission.QUERY_ALL_PACKAGES
            )
            initPermission(
                allowUserState,
                android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION
            )
            initPermission(
                allowInteractAcrossUsers,
                android.Manifest.permission.INTERACT_ACROSS_USERS
            )
        }
        val target = params.construct(context)

        fun runTestCases(callingUserId: Int, targetUserId: Int, throws: Boolean) {
            // Owner querent makes no distinction by UID
            val allUids = INTERNAL_UIDS + VERIFIER_UID + NON_VERIFIER_UID
            runCrossUserMethod(allUids, target, callingUserId, targetUserId, throws,
                verifyInvisiblePkg = false)
        }

        val callingUserId = 0
        val notCallingUserId = 1

        runTestCases(callingUserId, callingUserId, throws = true)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowQueryAll.set(true)

        runTestCases(callingUserId, callingUserId, throws = true)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowUserState.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowQueryAll.set(false)

        runTestCases(callingUserId, callingUserId, throws = true)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = true)
        }

        allowQueryAll.set(true)
        allowInteractAcrossUsers.set(true)

        runTestCases(callingUserId, callingUserId, throws = false)
        if (verifyCrossUser) {
            runTestCases(callingUserId, notCallingUserId, throws = false)
        }
    }

    private fun Context.initPermission(boolean: AtomicBoolean, permission: String) {
        whenever(enforcePermission(eq(permission), anyInt(), anyInt(), anyString())) {
            if (!boolean.get()) {
                throw SecurityException()
            }
        }
        whenever(checkPermission(eq(permission), anyInt(), anyInt())) {
            if (boolean.get()) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }
    }

    private fun runMethod(
        target: Any,
        callingUid: Int,
        visible: Boolean = true,
        callingUserId: Int = 0,
        userId: Int = 0
    ): Any? {
        val packageName = if (visible) VISIBLE_PKG else INVISIBLE_PKG
        val uuid = if (visible) VISIBLE_UUID else INVISIBLE_UUID
        return params.runMethod(target, callingUid, callingUserId, userId, packageName, uuid, proxy)
    }

    private fun runCrossUserMethod(
        allUids: Iterable<Int>,
        target: Any,
        callingUserId: Int,
        targetUserId: Int,
        throws: Boolean,
        verifyUserIdCheck: Boolean = true,
        verifyInvisiblePkg: Boolean = true,
        assertFailsMethod: (() -> Any?) -> Unit = ::assertFails,
    ) {
        if (throws) {
            allUids.forEach {
                assertFailsMethod {
                    // When testing a non-user ID failure, send an invalid user ID.
                    // This ensures the failure occurs before the user ID check is run.
                    try {
                        runMethod(target, it, visible = true, callingUserId, 100)
                    } catch (e: SecurityException) {
                        if (verifyUserIdCheck) {
                            e.message?.let {
                                if (it.contains("user ID", ignoreCase = true)
                                    || it.contains("100")) {
                                    fail(
                                        "Method should not check user existence before permissions"
                                    )
                                }
                            }
                        }

                        // Rethrow to allow normal fail checking logic to run
                        throw e
                    }
                }
            }
        } else {
            allUids.forEach {
                runMethod(target, it, visible = true, callingUserId, targetUserId)
            }
        }

        if (verifyInvisiblePkg) {
            allUids.forEach {
                assertFailsMethod {
                    runMethod(target, it, visible = false, callingUserId, targetUserId)
                }
            }
        }

        if (verifyUserIdCheck) {
            // An invalid target user ID should always fail
            allUids.forEach {
                assertFailsWith(SecurityException::class) {
                    runMethod(target, it, visible = true, callingUserId, 100)
                }
            }

            // An invalid calling user ID should always fail, although this cannot happen in prod
            allUids.forEach {
                assertFailsWith(SecurityException::class) {
                    runMethod(target, it, visible = true, 100, targetUserId)
                }
            }
        }
    }

    private fun assertFails(block: () -> Any?) {
        try {
            val value = block()
            // Some methods return false or an error rather than throwing, so check that as well
            val valueAsBoolean = value as? Boolean
            if (valueAsBoolean == false) {
                // Expected failure, do not throw
                return
            }

            val valueAsInt = value as? Int
            if (valueAsInt != null) {
                if (valueAsInt == DomainVerificationManager.STATUS_OK) {
                    throw AssertionError("Expected call to return false, was $value")
                }
            } else {
                throw AssertionError("Expected call to fail")
            }
        } catch (e: SecurityException) {
        } catch (e: PackageManager.NameNotFoundException) {
            // Any of these 2 exceptions are considered failures, which is expected
        }
    }

    enum class Type {
        // System/shell only
        INTERNAL,

        // INTERNAL || non-legacy domain verification agent || DUMP permission
        QUERENT,

        // INTERNAL || domain verification agent
        VERIFIER,

        // No permissions, allows all apps to view domain state for visible packages
        SELECTION_QUERENT,

        // Holding the user setting permission
        SELECTOR,

        // Holding the user setting permission, but targeting cross user
        SELECTOR_USER,

        // Legacy required no permissions except when cross-user
        LEGACY_QUERENT,

        // Holding the legacy preferred apps permission
        LEGACY_SELECTOR,

        // Holding user setting permission, but not targeting a package
        OWNER_QUERENT,

        // Holding user setting permission, but not targeting a package, but targeting cross user
        OWNER_QUERENT_USER,
    }
}
