/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om

import android.content.om.OverlayInfo
import android.content.om.OverlayableInfo
import android.os.Process
import android.util.ArrayMap
import com.android.server.om.OverlayActorEnforcer.ActorState
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.spy
import java.io.IOException

@RunWith(Parameterized::class)
class OverlayActorEnforcerTests {

    companion object {
        private const val TARGET_PKG = "com.test.target"
        private const val OVERLAY_PKG = "com.test.overlay"

        private const val VALID_NAMESPACE = "testNamespaceValid"
        private const val INVALID_NAMESPACE = "testNamespaceInvalid"
        private const val VALID_ACTOR_NAME = "testActorOne"
        private const val INVALID_ACTOR_NAME = "testActorTwo"
        private const val VALID_ACTOR_PKG = "com.test.actor.valid"
        private const val INVALID_ACTOR_PKG = "com.test.actor.invalid"
        private const val OVERLAYABLE_NAME = "TestOverlayable"
        private const val NULL_UID = 3536
        private const val EMPTY_UID = NULL_UID + 1
        private const val INVALID_ACTOR_UID = NULL_UID + 2
        private const val VALID_ACTOR_UID = NULL_UID + 3
        private const val TARGET_UID = NULL_UID + 4
        private const val USER_ID = 55

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = CASES.mapIndexed { caseIndex, testCase ->
            fun param(pair: Pair<String, TestState.() -> Unit>, type: Params.Type): Params {
                val expectedState = testCase.state.takeUnless { type == Params.Type.ALLOWED }
                        ?: ActorState.ALLOWED
                val (caseName, case) = pair
                val testName = makeTestName(testCase, caseName, type)
                return Params(caseIndex, expectedState, testName, type, case)
            }

            testCase.failures.map { param(it, Params.Type.FAILURE) } +
                    testCase.allowed.map { param(it, Params.Type.ALLOWED) }
        }.flatten()

        @BeforeClass
        @JvmStatic
        fun checkAllCasesHandled() {
            // Assert that all states have been tested at least once.
            assertThat(CASES.map { it.state }.distinct())
                    .containsAtLeastElementsIn(ActorState.values())
        }

        @BeforeClass
        @JvmStatic
        fun checkAllCasesUniquelyNamed() {
            val duplicateCaseNames = CASES.mapIndexed { _, testCase ->
                testCase.failures.map {
                    makeTestName(testCase, it.first, Params.Type.FAILURE)
                } + testCase.allowed.map {
                    makeTestName(testCase, it.first, Params.Type.ALLOWED)
                }
            }
                    .flatten()
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys

            assertThat(duplicateCaseNames).isEmpty()
        }

        /*
            The pattern in this block is a result of the incredible number of branches in
            enforcement logic. It serves to verify failures with the assumption that all errors
            are checked in order. The idea is to emulate the if-else branches from code, but using
            actual test data instead of if statements.

            Each state is verified by providing a failure or exclusive set of failures which cause
            a failure state to be returned. Each state also provides a success case which will
            "skip" the state. This allows subsequent failure cases to cascade from the first case
            by calling all the skip branches for preceding states and then choosing only 1 of
            the failures to test.

            Given the failure states A, B, and C: testA calls A.failure + assert, testB calls
            A.skip + B.failure + assert, testC calls A.skip + B.skip + C.failure + assert, etc.

            Calling `allowed` is a special case for when there is a combination of parameters that
            skips the remaining checks and immediately allows the actor through. For these cases,
            the first failure branch will be run, assert that it's not allowed, and the
            allowed branch will run, asserting that it now results in ALLOWED, skipping all
            remaining functions.

            This is an ordered list of TestCase objects, with the possibility to repeat failure
            states if any can occur multiple times in the logic tree.

            Each failure must be handled at least once.
         */
        private val CASES = listOf(
                ActorState.TARGET_NOT_FOUND withCases {
                    failure("nullPkgInfo") { targetPkgState = null }
                    allowed("debuggable") {
                        targetPkgState = packageState(TARGET_PKG, debuggable = true)
                    }
                    skip { targetPkgState = packageState(TARGET_PKG) }
                },
                ActorState.NO_PACKAGES_FOR_UID withCases {
                    failure("empty") { callingUid = EMPTY_UID }
                    failure("null") { callingUid = NULL_UID }
                    failure("shell") { callingUid = Process.SHELL_UID }
                    allowed("targetUid") { callingUid = TARGET_UID }
                    allowed("rootUid") { callingUid = Process.ROOT_UID }
                    allowed("systemUid") { callingUid = Process.SYSTEM_UID }
                    skip { callingUid = INVALID_ACTOR_UID }
                },
                ActorState.MISSING_TARGET_OVERLAYABLE_NAME withCases {
                    failure("nullTargetOverlayableName") {
                        overlayInfoParams.targetOverlayableName = null
                        targetOverlayableInfo = OverlayableInfo(OVERLAYABLE_NAME,
                                "overlay://$VALID_NAMESPACE/$VALID_ACTOR_NAME")
                    }
                    skip { overlayInfoParams.targetOverlayableName = OVERLAYABLE_NAME }
                },
                ActorState.MISSING_LEGACY_PERMISSION withCases {
                    failure("noPermission") {
                        overlayInfoParams.targetOverlayableName = null
                        targetOverlayableInfo = null
                        hasPermission = false
                    }
                    allowed("hasPermission") { hasPermission = true }
                    skip { overlayInfoParams.targetOverlayableName = OVERLAYABLE_NAME }
                },
                ActorState.ERROR_READING_OVERLAYABLE withCases {
                    failure("doesTargetDefineOverlayableIOException") {
                        overlayInfoParams.targetOverlayableName = null
                        whenever(doesTargetDefineOverlayable(TARGET_PKG, USER_ID))
                                .thenThrow(IOException::class.java)
                    }
                    skip { overlayInfoParams.targetOverlayableName = OVERLAYABLE_NAME }
                },
                ActorState.UNABLE_TO_GET_TARGET_OVERLAYABLE withCases {
                    failure("getOverlayableForTargetIOException") {
                        whenever(getOverlayableForTarget(TARGET_PKG, OVERLAYABLE_NAME,
                                USER_ID)).thenThrow(IOException::class.java)
                    }
                },
                ActorState.MISSING_OVERLAYABLE withCases {
                    failure("nullTargetOverlayableInfo") { targetOverlayableInfo = null }
                    skip {
                        targetOverlayableInfo = OverlayableInfo(OVERLAYABLE_NAME,
                                "overlay://$VALID_NAMESPACE/$VALID_ACTOR_NAME")
                    }
                },
                ActorState.MISSING_LEGACY_PERMISSION withCases {
                    failure("noPermissionNullActor") {
                        targetOverlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, null)
                        hasPermission = false
                    }
                    failure("noPermissionEmptyActor") {
                        targetOverlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, "")
                        hasPermission = false
                    }
                    allowed("hasPermissionNullActor") {
                        hasPermission = true
                    }
                    skip {
                        targetOverlayableInfo = OverlayableInfo(OVERLAYABLE_NAME,
                                "overlay://$VALID_NAMESPACE/$VALID_ACTOR_NAME")
                    }
                },
                ActorState.INVALID_OVERLAYABLE_ACTOR_NAME withCases {
                    fun TestState.mockActor(actorUri: String) {
                        targetOverlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, actorUri)
                    }
                    failure("wrongScheme") {
                        mockActor("notoverlay://$VALID_NAMESPACE/$VALID_ACTOR_NAME")
                    }
                    failure("extraPath") {
                        mockActor("overlay://$VALID_NAMESPACE/$VALID_ACTOR_NAME/extraPath")
                    }
                    failure("missingPath") { mockActor("overlay://$VALID_NAMESPACE") }
                    failure("missingAuthority") { mockActor("overlay://") }
                    skip { mockActor("overlay://$VALID_NAMESPACE/$VALID_ACTOR_NAME") }
                },
                ActorState.NO_NAMED_ACTORS withCases {
                    failure("empty") { namedActorsMap = emptyMap() }
                    skip {
                        namedActorsMap = mapOf(INVALID_NAMESPACE to
                                mapOf(INVALID_ACTOR_NAME to VALID_ACTOR_PKG))
                    }
                },
                ActorState.MISSING_NAMESPACE withCases {
                    failure("invalidNamespace") {
                        namedActorsMap = mapOf(INVALID_NAMESPACE to
                                mapOf(INVALID_ACTOR_NAME to VALID_ACTOR_PKG))
                    }
                    skip {
                        namedActorsMap = mapOf(VALID_NAMESPACE to
                                mapOf(INVALID_ACTOR_NAME to VALID_ACTOR_PKG))
                    }
                },
                ActorState.MISSING_ACTOR_NAME withCases {
                    failure("invalidActorName") {
                        namedActorsMap = mapOf(VALID_NAMESPACE to
                                mapOf(INVALID_ACTOR_NAME to VALID_ACTOR_PKG))
                    }
                    skip {
                        namedActorsMap = mapOf(VALID_NAMESPACE to
                                mapOf(VALID_ACTOR_NAME to VALID_ACTOR_PKG))
                    }
                },
                ActorState.ACTOR_NOT_FOUND withCases {
                    failure("nullActorPkgInfo") { actorPkgState = null }
                    failure("nullActorAppInfo") {
                        actorPkgState = null
                    }
                    skip { actorPkgState = packageState(VALID_ACTOR_PKG) }
                },
                ActorState.ACTOR_NOT_PREINSTALLED withCases {
                    failure("notSystem") { actorPkgState = packageState(VALID_ACTOR_PKG) }
                    skip { actorPkgState = packageState(VALID_ACTOR_PKG, isSystem = true) }
                },
                ActorState.INVALID_ACTOR withCases {
                    failure("invalidUid") { callingUid = INVALID_ACTOR_UID }
                    skip { callingUid = VALID_ACTOR_UID }
                },
                ActorState.ALLOWED withCases {
                    // No point making an exception for this case in all of the test code, so
                    // just pretend this is a failure that results in a success result code.
                    failure("allowed") { /* Do nothing */ }
                }
        )

        data class OverlayInfoParams(
            var targetPackageName: String = TARGET_PKG,
            var targetOverlayableName: String? = null
        ) {
            fun toOverlayInfo() = OverlayInfo(
                    OVERLAY_PKG,
                    "",
                    targetPackageName,
                    targetOverlayableName,
                    null,
                    "/path",
                    OverlayInfo.STATE_UNKNOWN, 0,
                    0, false, false)
        }

        private infix fun ActorState.withCases(block: TestCase.() -> Unit) =
                TestCase(this).apply(block)

        private fun packageState(
            pkgName: String,
            debuggable: Boolean = false,
            isSystem: Boolean = false
        ) = mockThrowOnUnmocked<PackageState> {
            whenever(this.packageName).thenReturn(pkgName)
            whenever(this.isSystem).thenReturn(isSystem)
            val androidPackage = mockThrowOnUnmocked<AndroidPackage> {
                whenever(this.packageName).thenReturn(pkgName)
                whenever(this.isDebuggable).thenReturn(debuggable)
            }
            whenever(this.androidPackage).thenReturn(androidPackage)
        }

        private fun makeTestName(testCase: TestCase, caseName: String, type: Params.Type): String {
            val resultSuffix = if (type == Params.Type.ALLOWED) "allowed" else "failed"
            return "${testCase.state}_${resultSuffix}_$caseName"
        }
    }

    @Parameterized.Parameter(0)
    lateinit var params: Params

    @Test
    fun verify() {
        // Apply all the skip states before the failure to be verified
        val testState = CASES.take(params.index)
                .fold(TestState.create()) { testState, case ->
                    testState.apply(case.skip)
                }

        // If testing an allowed branch, first apply a failure to ensure it fails
        if (params.type == Params.Type.ALLOWED) {
            CASES[params.index].failures.firstOrNull()?.second?.run(testState::apply)
            assertThat(testState.toResult()).isNotEqualTo(ActorState.ALLOWED)
        }

        // Apply the test case in the params to the collected state
        testState.apply(params.function)

        // Assert the result matches the expected state
        assertThat(testState.toResult()).isEqualTo(params.expectedState)
    }

    private fun TestState.toResult() = OverlayActorEnforcer(this)
            .isAllowedActor("test", overlayInfoParams.toOverlayInfo(), callingUid, USER_ID)

    data class Params(
        var index: Int,
        var expectedState: ActorState,
        val testName: String,
        val type: Type,
        val function: TestState.() -> Unit
    ) {
        override fun toString() = testName

        enum class Type {
            FAILURE,
            ALLOWED
        }
    }

    data class TestCase(
        val state: ActorState,
        val failures: MutableList<Pair<String, TestState.() -> Unit>> = mutableListOf(),
        var allowed: MutableList<Pair<String, TestState.() -> Unit>> = mutableListOf(),
        var skip: (TestState.() -> Unit) = {}
    ) {
        fun failure(caseName: String, block: TestState.() -> Unit) {
            failures.add(caseName to block)
        }

        fun allowed(caseName: String, block: TestState.() -> Unit) {
            allowed.add(caseName to block)
        }

        fun skip(block: TestState.() -> Unit) {
            this.skip = block
        }
    }

    open class TestState private constructor(
        var callingUid: Int = NULL_UID,
        val overlayInfoParams: OverlayInfoParams = OverlayInfoParams(),
        var namedActorsMap: Map<String, Map<String, String>> = emptyMap(),
        var hasPermission: Boolean = false,
        var targetOverlayableInfo: OverlayableInfo? = null,
        var targetPkgState: PackageState? = null,
        var actorPkgState: PackageState? = null,
        vararg val packageNames: String = arrayOf("com.test.actor.one")
    ) : PackageManagerHelper {

        companion object {
            // Enforce that new instances are spied
            fun create() = spy(TestState())!!
        }

        override fun getNamedActors() = namedActorsMap

        override fun isInstantApp(packageName: String, userId: Int): Boolean {
            throw UnsupportedOperationException()
        }

        override fun initializeForUser(userId: Int): ArrayMap<String, PackageState> {
            throw UnsupportedOperationException()
        }

        @Throws(IOException::class)
        override fun getOverlayableForTarget(
            packageName: String,
            targetOverlayableName: String,
            userId: Int
        ) = targetOverlayableInfo?.takeIf {
            // Protect against this method being called with the wrong package name
            targetPkgState == null || targetPkgState?.packageName == packageName
        }

        override fun getPackagesForUid(uid: Int) = when (uid) {
            EMPTY_UID -> emptyArray()
            INVALID_ACTOR_UID -> arrayOf(INVALID_ACTOR_PKG)
            VALID_ACTOR_UID -> arrayOf(VALID_ACTOR_PKG)
            TARGET_UID -> arrayOf(TARGET_PKG)
            NULL_UID -> null
            else -> null
        }

        @Throws(IOException::class) // Mockito requires this checked exception to be declared
        override fun doesTargetDefineOverlayable(targetPackageName: String?, userId: Int): Boolean {
            return targetOverlayableInfo?.takeIf {
                // Protect against this method being called with the wrong package name
                targetPkgState == null || targetPkgState?.packageName == targetPackageName
            } != null
        }

        override fun enforcePermission(permission: String?, message: String?) {
            if (!hasPermission) {
                throw SecurityException()
            }
        }

        override fun getPackageStateForUser(packageName: String, userId: Int) =
            listOfNotNull(targetPkgState, actorPkgState).find { it.packageName == packageName }

        override fun getConfigSignaturePackage(): String {
            throw UnsupportedOperationException()
        }

        override fun signaturesMatching(pkgName1: String, pkgName2: String, userId: Int): Boolean {
            throw UnsupportedOperationException()
        }
    }
}
