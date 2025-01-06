/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.appfunctions

import android.app.appfunctions.AppFunctionException
import android.app.appfunctions.ExecuteAppFunctionAidlRequest
import android.app.appfunctions.ExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse
import android.app.appfunctions.IAppFunctionService
import android.app.appfunctions.IExecuteAppFunctionCallback
import android.app.appfunctions.SafeOneTimeExecuteAppFunctionCallback
import android.app.appsearch.GenericDocument
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.server.LocalServices
import com.google.common.util.concurrent.MoreExecutors
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests that AppFunctionsStatsLog logs AppFunctionsRequestReported with the expected values. */
@RunWith(AndroidJUnit4::class)
class AppFunctionsLoggingTest {
    @get:Rule
    val mExtendedMockitoRule: ExtendedMockitoRule =
        ExtendedMockitoRule.Builder(this)
            .mockStatic(AppFunctionsStatsLog::class.java)
            .mockStatic(LocalServices::class.java)
            .build()
    private val mContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val mMockPackageManager = mock<PackageManager>()
    private val mAppFunctionsLoggerWrapper =
        AppFunctionsLoggerWrapper(
            mMockPackageManager,
            MoreExecutors.directExecutor(),
            { TEST_CURRENT_TIME_MILLIS },
        )
    private lateinit var mSafeCallback: SafeOneTimeExecuteAppFunctionCallback

    private val mServiceImpl =
        AppFunctionManagerServiceImpl(
            mContext,
            mock<RemoteServiceCaller<IAppFunctionService>>(),
            mock<CallerValidator>(),
            mock<ServiceHelper>(),
            ServiceConfigImpl(),
            mAppFunctionsLoggerWrapper,
            mock<PackageManagerInternal>(),
        )

    private val mRequestInternal =
        ExecuteAppFunctionAidlRequest(
            ExecuteAppFunctionRequest.Builder(TEST_TARGET_PACKAGE, TEST_FUNCTION_ID).build(),
            UserHandle.CURRENT,
            TEST_CALLING_PKG,
            TEST_INITIAL_REQUEST_TIME_MILLIS,
        )

    @Before
    fun setup() {
        whenever(mMockPackageManager.getPackageUid(eq(TEST_TARGET_PACKAGE), any<Int>()))
            .thenReturn(TEST_TARGET_UID)
        mSafeCallback =
            mServiceImpl.initializeSafeExecuteAppFunctionCallback(
                mRequestInternal,
                mock<IExecuteAppFunctionCallback>(),
                TEST_CALLING_UID,
            )
        mSafeCallback.setExecutionStartTimeAfterBindMillis(TEST_EXECUTION_TIME_AFTER_BIND_MILLIS)
    }

    @Test
    fun testOnSuccess_logsSuccessResponse() {
        val response =
            ExecuteAppFunctionResponse(
                GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                    .setPropertyLong("longProperty", 42L)
                    .setPropertyString("stringProperty", "text")
                    .build()
            )

        mSafeCallback.onResult(response)

        ExtendedMockito.verify {
            AppFunctionsStatsLog.write(
                /* atomId= */ eq<Int>(AppFunctionsStatsLog.APP_FUNCTIONS_REQUEST_REPORTED),
                /* callerPackageUid= */ eq<Int>(TEST_CALLING_UID),
                /* targetPackageUid= */ eq<Int>(TEST_TARGET_UID),
                /* errorCode= */ eq<Int>(AppFunctionsLoggerWrapper.SUCCESS_RESPONSE_CODE),
                /* requestSizeBytes= */ eq<Int>(mRequestInternal.clientRequest.requestDataSize),
                /* responseSizeBytes= */ eq<Int>(response.responseDataSize),
                /* requestDurationMs= */ eq<Long>(TEST_EXPECTED_E2E_DURATION_MILLIS),
                /* requestOverheadMs= */ eq<Long>(TEST_EXPECTED_OVERHEAD_DURATION_MILLIS),
            )
        }
    }

    @Test
    fun testOnError_logsFailureResponse() {
        mSafeCallback.onError(
            AppFunctionException(AppFunctionException.ERROR_DENIED, "Error: permission denied")
        )

        ExtendedMockito.verify {
            AppFunctionsStatsLog.write(
                /* atomId= */ eq<Int>(AppFunctionsStatsLog.APP_FUNCTIONS_REQUEST_REPORTED),
                /* callerPackageUid= */ eq<Int>(TEST_CALLING_UID),
                /* targetPackageUid= */ eq<Int>(TEST_TARGET_UID),
                /* errorCode= */ eq<Int>(AppFunctionException.ERROR_DENIED),
                /* requestSizeBytes= */ eq<Int>(mRequestInternal.clientRequest.requestDataSize),
                /* responseSizeBytes= */ eq<Int>(0),
                /* requestDurationMs= */ eq<Long>(TEST_EXPECTED_E2E_DURATION_MILLIS),
                /* requestOverheadMs= */ eq<Long>(TEST_EXPECTED_OVERHEAD_DURATION_MILLIS),
            )
        }
    }

    private companion object {
        const val TEST_CALLING_PKG = "com.android.trusted.caller"
        const val TEST_CALLING_UID = 12345
        const val TEST_TARGET_PACKAGE = "com.android.trusted.target"
        const val TEST_TARGET_UID = 54321
        const val TEST_FUNCTION_ID = "com.android.valid.target.doSomething"

        const val TEST_INITIAL_REQUEST_TIME_MILLIS = 10L
        const val TEST_EXECUTION_TIME_AFTER_BIND_MILLIS = 20L
        const val TEST_CURRENT_TIME_MILLIS = 50L
        const val TEST_EXPECTED_E2E_DURATION_MILLIS =
            TEST_CURRENT_TIME_MILLIS - TEST_INITIAL_REQUEST_TIME_MILLIS
        const val TEST_EXPECTED_OVERHEAD_DURATION_MILLIS =
            TEST_EXECUTION_TIME_AFTER_BIND_MILLIS - TEST_INITIAL_REQUEST_TIME_MILLIS
    }
}
