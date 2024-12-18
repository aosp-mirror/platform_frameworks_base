/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.mediaprojection.devicepolicy

import android.app.admin.DevicePolicyManager
import android.os.UserHandle
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.runner.parameterized.Parameter
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any

abstract class BaseScreenCaptureDevicePolicyResolverTest(private val precondition: Preconditions) :
    SysuiTestCase() {

    abstract class Preconditions(
        val personalScreenCaptureDisabled: Boolean,
        val workScreenCaptureDisabled: Boolean,
        val disallowShareIntoManagedProfile: Boolean
    )

    protected val devicePolicyManager: DevicePolicyManager = mock()
    protected val userManager: UserManager = mock()

    protected val personalUserHandle: UserHandle = UserHandle.of(123)
    protected val workUserHandle: UserHandle = UserHandle.of(456)

    protected val policyResolver =
        ScreenCaptureDevicePolicyResolver(
            devicePolicyManager,
            userManager,
            personalUserHandle,
            workUserHandle
        )

    @Before
    fun setUp() {
        setUpPolicies()
    }

    private fun setUpPolicies() {
        whenever(
                devicePolicyManager.getScreenCaptureDisabled(
                    any(),
                    eq(personalUserHandle.identifier)
                )
            )
            .thenReturn(precondition.personalScreenCaptureDisabled)

        whenever(devicePolicyManager.getScreenCaptureDisabled(any(), eq(workUserHandle.identifier)))
            .thenReturn(precondition.workScreenCaptureDisabled)

        whenever(
                userManager.hasUserRestrictionForUser(
                    eq(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE),
                    eq(workUserHandle)
                )
            )
            .thenReturn(precondition.disallowShareIntoManagedProfile)
    }
}

@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
class IsAllowedScreenCaptureDevicePolicyResolverTest(
    private val test: IsScreenCaptureAllowedTestCase
) : BaseScreenCaptureDevicePolicyResolverTest(test.given) {

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            listOf(
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false,
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true,
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false,
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true,
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = true,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
                IsScreenCaptureAllowedTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            isTargetInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureAllowed = false,
                ),
            )
    }

    class Preconditions(
        personalScreenCaptureDisabled: Boolean,
        workScreenCaptureDisabled: Boolean,
        disallowShareIntoManagedProfile: Boolean,
        val isHostInWorkProfile: Boolean,
        val isTargetInWorkProfile: Boolean,
    ) :
        BaseScreenCaptureDevicePolicyResolverTest.Preconditions(
            personalScreenCaptureDisabled,
            workScreenCaptureDisabled,
            disallowShareIntoManagedProfile
        )

    data class IsScreenCaptureAllowedTestCase(
        val given: Preconditions,
        val expectedScreenCaptureAllowed: Boolean
    ) {
        override fun toString(): String =
            "isScreenCaptureAllowed: " +
                "host[${if (given.isHostInWorkProfile) "work" else "personal"} profile], " +
                "target[${if (given.isTargetInWorkProfile) "work" else "personal"} profile], " +
                "personal screen capture disabled = ${given.personalScreenCaptureDisabled}, " +
                "work screen capture disabled = ${given.workScreenCaptureDisabled}, " +
                "disallow share into managed profile = ${given.disallowShareIntoManagedProfile}, " +
                "expected screen capture allowed = $expectedScreenCaptureAllowed"
    }

    @Test
    fun test() {
        val targetAppUserHandle =
            if (test.given.isTargetInWorkProfile) workUserHandle else personalUserHandle
        val hostAppUserHandle =
            if (test.given.isHostInWorkProfile) workUserHandle else personalUserHandle

        val screenCaptureAllowed =
            policyResolver.isScreenCaptureAllowed(targetAppUserHandle, hostAppUserHandle)

        assertWithMessage("Screen capture policy resolved incorrectly")
            .that(screenCaptureAllowed)
            .isEqualTo(test.expectedScreenCaptureAllowed)
    }
}

@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
class IsCompletelyNotAllowedScreenCaptureDevicePolicyResolverTest(
    private val test: IsScreenCaptureCompletelyDisabledTestCase
) : BaseScreenCaptureDevicePolicyResolverTest(test.given) {

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            listOf(
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = false,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = false,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = false,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = false,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = false,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = false,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = false,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = false,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            personalScreenCaptureDisabled = false,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = false
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                ),
                IsScreenCaptureCompletelyDisabledTestCase(
                    given =
                        Preconditions(
                            isHostInWorkProfile = true,
                            personalScreenCaptureDisabled = true,
                            workScreenCaptureDisabled = true,
                            disallowShareIntoManagedProfile = true
                        ),
                    expectedScreenCaptureCompletelyDisabled = true,
                )
            )
    }

    class Preconditions(
        personalScreenCaptureDisabled: Boolean,
        workScreenCaptureDisabled: Boolean,
        disallowShareIntoManagedProfile: Boolean,
        val isHostInWorkProfile: Boolean,
    ) :
        BaseScreenCaptureDevicePolicyResolverTest.Preconditions(
            personalScreenCaptureDisabled,
            workScreenCaptureDisabled,
            disallowShareIntoManagedProfile
        )

    data class IsScreenCaptureCompletelyDisabledTestCase(
        val given: Preconditions,
        val expectedScreenCaptureCompletelyDisabled: Boolean
    ) {
        override fun toString(): String =
            "isScreenCaptureCompletelyDisabled: " +
                "host[${if (given.isHostInWorkProfile) "work" else "personal"} profile], " +
                "personal screen capture disabled = ${given.personalScreenCaptureDisabled}, " +
                "work screen capture disabled = ${given.workScreenCaptureDisabled}, " +
                "disallow share into managed profile = ${given.disallowShareIntoManagedProfile}, " +
                "expected screen capture completely disabled = " +
                "$expectedScreenCaptureCompletelyDisabled"
    }

    @Test
    fun test() {
        val hostAppUserHandle =
            if (test.given.isHostInWorkProfile) workUserHandle else personalUserHandle

        val completelyDisabled = policyResolver.isScreenCaptureCompletelyDisabled(hostAppUserHandle)

        assertWithMessage("Screen capture policy resolved incorrectly")
            .that(completelyDisabled)
            .isEqualTo(test.expectedScreenCaptureCompletelyDisabled)
    }
}
