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

package android.content.pm.parsing.result

import android.content.pm.PackageManager
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.io.IOException

class ParseInputAndResultTest {

    companion object {

        private const val TEST_PACKAGE = "com.android.test"

        private const val ENABLED_ERROR = 11L
        private const val DISABLED_ERROR = 22L

        @JvmStatic
        @BeforeClass
        fun assumeNotDebug() {
            // None of these tests consider cases where debugging logic is enabled
            assumeFalse(ParseTypeImpl.DEBUG_FILL_STACK_TRACE)
            assumeFalse(ParseTypeImpl.DEBUG_LOG_ON_ERROR)
            assumeFalse(ParseTypeImpl.DEBUG_THROW_ALL_ERRORS)
        }
    }

    private lateinit var mockCallback: ParseInput.Callback
    private lateinit var input: ParseInput

    @Before
    fun createInput() {
        // Use an open class instead off a lambda so it can be spied
        open class TestCallback : ParseInput.Callback {
            override fun isChangeEnabled(changeId: Long, pkgName: String, targetSdk: Int): Boolean {
                return when (changeId) {
                    ENABLED_ERROR -> targetSdk > Build.VERSION_CODES.Q
                    DISABLED_ERROR -> false
                    else -> throw IllegalStateException("changeId $changeId is not mocked for test")
                }
            }
        }

        mockCallback = spy(TestCallback())
        input = ParseTypeImpl(mockCallback)
    }

    @Test
    fun errorCode() {
        val errorCode = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE
        val result = input.error<Any?>(errorCode)
        assertError(result)
        assertThat(result.errorCode).isEqualTo(errorCode)
        assertThat(result.errorMessage).isNull()
        assertThat(result.exception).isNull()
    }

    @Test
    fun errorMessage() {
        val errorMessage = "Test error"
        val result = input.error<Any?>(errorMessage)
        assertError(result)
        assertThat(result.errorCode).isNotEqualTo(PackageManager.INSTALL_SUCCEEDED)
        assertThat(result.errorMessage).isEqualTo(errorMessage)
        assertThat(result.exception).isNull()
    }

    @Test
    fun errorCodeAndMessage() {
        val errorCode = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE
        val errorMessage = "Test error"
        val result = input.error<Any?>(errorCode, errorMessage)
        assertError(result)
        assertThat(result.errorCode).isEqualTo(errorCode)
        assertThat(result.errorMessage).isEqualTo(errorMessage)
        assertThat(result.exception).isNull()
    }

    @Test
    fun errorCodeAndMessageAndException() {
        val errorCode = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE
        val errorMessage = "Test error"
        val exception = IOException()
        val result = input.error<Any?>(errorCode, errorMessage, exception)
        assertError(result)
        assertThat(result.errorCode).isEqualTo(errorCode)
        assertThat(result.errorMessage).isEqualTo(errorMessage)
        assertThat(result.exception).isSameAs(exception)
    }

    @Test
    fun errorCarryResult() {
        val errorCode = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE
        val errorMessage = "Test error"
        val exception = IOException()
        val result = input.error<Any?>(errorCode, errorMessage, exception)
        assertError(result)
        assertThat(result.errorCode).isEqualTo(errorCode)
        assertThat(result.errorMessage).isEqualTo(errorMessage)
        assertThat(result.exception).isSameAs(exception)

        val carriedResult = input.error<Int>(result)
        assertError(carriedResult)
        assertThat(carriedResult.errorCode).isEqualTo(errorCode)
        assertThat(carriedResult.errorMessage).isEqualTo(errorMessage)
        assertThat(carriedResult.exception).isSameAs(exception)
    }

    @Test
    fun success() {
        val value = "Test success"
        assertSuccess(value, input.success(value))
    }

    @Test
    fun deferErrorEnableFirstSdkQ() {
        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.Q))

        assertSuccess(input.deferError("Test error", ENABLED_ERROR))
    }

    @Test
    fun deferErrorEnableLastSdkQ() {
        assertSuccess(input.deferError("Test error", ENABLED_ERROR))

        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.Q))
    }

    @Test
    fun deferErrorEnableFirstSdkR() {
        val error = "Test error"
        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R))

        val deferResult = input.deferError(error, ENABLED_ERROR)
        assertError(deferResult)
        assertThat(deferResult.errorCode).isNotEqualTo(PackageManager.INSTALL_SUCCEEDED)
        assertThat(deferResult.errorMessage).isEqualTo(error)
        assertThat(deferResult.exception).isNull()
    }

    @Test
    fun deferErrorEnableLastSdkR() {
        val error = "Test error"
        assertSuccess(input.deferError(error, ENABLED_ERROR))

        val result = input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R)
        assertError(result)
        assertThat(result.errorCode).isNotEqualTo(PackageManager.INSTALL_SUCCEEDED)
        assertThat(result.errorMessage).isEqualTo(error)
        assertThat(result.exception).isNull()
    }

    @Test
    fun enableDeferredErrorAndSuccessSdkQ() {
        val value = "Test success"
        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.Q))

        assertSuccess(value, input.success(value))
    }

    @Test
    fun enableDeferredErrorAndSuccessSdkR() {
        val value = "Test success"
        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R))

        assertSuccess(value, input.success(value))
    }

    @Test
    fun multipleDeferErrorKeepsFirst() {
        val errorOne = "Test error one"
        val errorTwo = "Test error two"

        assertSuccess(input.deferError(errorOne, ENABLED_ERROR))
        assertSuccess(input.deferError(errorTwo, ENABLED_ERROR))

        val result = input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R)
        assertError(result)
        assertThat(result.errorCode).isNotEqualTo(PackageManager.INSTALL_SUCCEEDED)
        assertThat(result.errorMessage).isEqualTo(errorOne)
        assertThat(result.exception).isNull()
    }

    @Test
    fun multipleDisabledErrorsQueriesOnceEnableFirst() {
        val errorOne = "Test error one"
        val errorTwo = "Test error two"

        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R))

        assertSuccess(input.deferError(errorOne, DISABLED_ERROR))

        verify(mockCallback, times(1)).isChangeEnabled(anyLong(), anyString(), anyInt())

        assertSuccess(input.deferError(errorTwo, DISABLED_ERROR))

        verifyNoMoreInteractions(mockCallback)
    }

    @Test
    fun multipleDisabledErrorsQueriesOnceEnableSecond() {
        val errorOne = "Test error one"
        val errorTwo = "Test error two"

        assertSuccess(input.deferError(errorOne, DISABLED_ERROR))

        verify(mockCallback, never()).isChangeEnabled(anyLong(), anyString(), anyInt())

        assertSuccess(input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R))

        verify(mockCallback, times(1)).isChangeEnabled(anyLong(), anyString(), anyInt())

        assertSuccess(input.deferError(errorTwo, DISABLED_ERROR))

        verifyNoMoreInteractions(mockCallback)
    }

    @After
    fun verifyReset() {
        var result = (input as ParseTypeImpl).reset() as ParseResult<*>
        result.assertReset()

        // The deferred error is not directly accessible, so attempt to re-enable the deferred
        // error and assert it was also reset.
        result = input.enableDeferredError(TEST_PACKAGE, Build.VERSION_CODES.R)
        result.assertReset()
    }

    private fun assertSuccess(result: ParseResult<*>) = assertSuccess(null, result)

    private fun assertSuccess(expected: Any? = null, result: ParseResult<*>) {
        assertThat(result.isError).isFalse()
        assertThat(result.isSuccess).isTrue()
        assertThat(result.result).isSameAs(expected)
        assertThat(result.errorCode).isEqualTo(PackageManager.INSTALL_SUCCEEDED)
        assertThat(result.errorMessage).isNull()
        assertThat(result.exception).isNull()
    }

    private fun assertError(result: ParseResult<*>) {
        assertThat(result.isError).isTrue()
        assertThat(result.isSuccess).isFalse()
        assertThat(result.result).isNull()
    }

    private fun ParseResult<*>.assertReset() {
        assertThat(this.isSuccess).isTrue()
        assertThat(this.isError).isFalse()
        assertThat(this.errorCode).isEqualTo(PackageManager.INSTALL_SUCCEEDED)
        assertThat(this.errorMessage).isNull()
        assertThat(this.exception).isNull()
    }
}
