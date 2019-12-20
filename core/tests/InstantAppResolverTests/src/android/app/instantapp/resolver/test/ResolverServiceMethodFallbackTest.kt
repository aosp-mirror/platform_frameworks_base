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

package android.app.instantapp.resolver.test

import android.app.InstantAppResolverService
import android.app.InstantAppResolverService.InstantAppResolutionCallback
import android.content.Intent
import android.content.pm.InstantAppRequestInfo
import android.net.Uri
import android.os.Bundle
import android.os.IRemoteCallback
import android.os.UserHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import java.util.UUID
import kotlin.random.Random

private typealias Method = InstantAppResolverService.(InstantAppRequestInfo) -> Unit

@Suppress("max-line-length")
@RunWith(Parameterized::class)
class ResolverServiceMethodFallbackTest @Suppress("UNUSED_PARAMETER") constructor(
    private val version: Int,
    private val methodList: List<Method>,
    private val info: InstantAppRequestInfo,
    // Remaining only used to print human-readable test name
    name: String,
    isWebIntent: Boolean
) {

    companion object {
        // Since the resolution callback class is final, mock the IRemoteCallback and have it throw
        // a unique exception to indicate it was called.
        class TestRemoteCallbackException : Exception()

        private val testIntentWeb = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://${this::class.java.canonicalName}.com"))
        private val testIntentNotWeb = Intent(Intent.ACTION_VIEW,
                Uri.parse("content://${this::class.java.canonicalName}"))

        private val testRemoteCallback = object : IRemoteCallback {
            override fun sendResult(data: Bundle?) = throw TestRemoteCallbackException()
            override fun asBinder() = throw UnsupportedOperationException()
        }
        private val testResolutionCallback = InstantAppResolutionCallback(0, testRemoteCallback)
        private val testArray = IntArray(10) { Random.nextInt() }
        private val testToken = UUID.randomUUID().toString()
        private val testUser = UserHandle(Integer.MAX_VALUE)
        private val testInfoWeb = InstantAppRequestInfo(testIntentWeb, testArray, testUser,
                false, testToken)
        private val testInfoNotWeb = InstantAppRequestInfo(testIntentNotWeb, testArray, testUser,
                false, testToken)

        // Each section defines methods versions with later definitions falling back to
        // earlier definitions. Each block receives an [InstantAppResolverService] and invokes
        // the appropriate version with the test data defined above.
        private val infoOne: Method = { onGetInstantAppResolveInfo(testArray, testToken,
                testResolutionCallback) }
        private val infoTwo: Method = { onGetInstantAppResolveInfo(it.intent, testArray, testToken,
                testResolutionCallback) }
        private val infoThree: Method = { onGetInstantAppResolveInfo(it.intent, testArray, testUser,
                testToken, testResolutionCallback) }
        private val infoFour: Method = { onGetInstantAppResolveInfo(it, testResolutionCallback) }

        private val filterOne: Method = { onGetInstantAppIntentFilter(testArray, testToken,
                testResolutionCallback) }
        private val filterTwo: Method = { onGetInstantAppIntentFilter(it.intent, testArray,
                testToken, testResolutionCallback) }
        private val filterThree: Method = { onGetInstantAppIntentFilter(it.intent, testArray,
                testUser, testToken, testResolutionCallback) }
        private val filterFour: Method = { onGetInstantAppIntentFilter(it, testResolutionCallback) }

        private val infoList = listOf(infoOne, infoTwo, infoThree, infoFour)
        private val filterList = listOf(filterOne, filterTwo, filterThree, filterFour)

        @JvmStatic
        @Parameterized.Parameters(name = "{3} version {0}, isWeb = {4}")
        fun parameters(): Array<Array<*>> {
            // Sanity check that web intent logic hasn't changed
            assertThat(testInfoWeb.intent.isWebIntent).isTrue()
            assertThat(testInfoNotWeb.intent.isWebIntent).isFalse()

            // Declare all the possible params
            val versions = Array(5) { it }
            val methods = arrayOf("ResolveInfo" to infoList, "IntentFilter" to filterList)
            val infos = arrayOf(testInfoWeb, testInfoNotWeb)

            // FlatMap params into every possible combination
            return infos.flatMap { info ->
                methods.flatMap { (name, methods) ->
                    versions.map { version ->
                        arrayOf(version, methods, info, name, info.intent.isWebIntent)
                    }
                }
            }.toTypedArray()
        }
    }

    @field:Mock(answer = Answers.CALLS_REAL_METHODS)
    lateinit var mockService: InstantAppResolverService

    @get:Rule
    val expectedException = ExpectedException.none()

    @Before
    fun setUpMocks() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun onGetInstantApp() {
        if (version == 0) {
            // No version of the API was implemented, so expect terminal case
            if (info.intent.isWebIntent) {
                // If web intent, terminal is total failure
                expectedException.expect(IllegalStateException::class.java)
            } else {
                // Otherwise, terminal is a fail safe by calling [testRemoteCallback]
                expectedException.expect(TestRemoteCallbackException::class.java)
            }
        } else if (version < 2 && !info.intent.isWebIntent) {
            // Starting from v2, if resolving a non-web intent and a v2+ method isn't implemented,
            // it fails safely by calling [testRemoteCallback]
            expectedException.expect(TestRemoteCallbackException::class.java)
        }

        // Version 1 is the first method (index 0)
        val methodIndex = version - 1

        // Implement a method if necessary
        methodList.getOrNull(methodIndex)?.invoke(doNothing().`when`(mockService), info)

        // Call the latest API
        methodList.last().invoke(mockService, info)

        // Check all methods before implemented method are never called
        (0 until methodIndex).forEach {
            methodList[it].invoke(verify(mockService, never()), info)
        }

        // Check all methods from implemented method are called
        (methodIndex until methodList.size).forEach {
            methodList[it].invoke(verify(mockService), info)
        }

        verifyNoMoreInteractions(mockService)
    }
}
