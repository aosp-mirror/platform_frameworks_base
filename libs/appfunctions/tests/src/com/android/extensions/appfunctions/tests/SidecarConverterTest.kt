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

package com.android.extensions.appfunctions.tests

import android.app.appfunctions.AppFunctionException
import android.app.appfunctions.ExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse
import android.app.appsearch.GenericDocument
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.extensions.appfunctions.SidecarConverter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidecarConverterTest {
    @Test
    fun getSidecarExecuteAppFunctionRequest_sameContents() {
        val extras = Bundle()
        extras.putString("extra", "value")
        val parameters: GenericDocument =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyLong("testLong", 23)
                .build()
        val platformRequest: ExecuteAppFunctionRequest =
            ExecuteAppFunctionRequest.Builder("targetPkg", "targetFunctionId")
                .setExtras(extras)
                .setParameters(parameters)
                .build()

        val sidecarRequest = SidecarConverter.getSidecarExecuteAppFunctionRequest(platformRequest)

        assertThat(sidecarRequest.targetPackageName).isEqualTo("targetPkg")
        assertThat(sidecarRequest.functionIdentifier).isEqualTo("targetFunctionId")
        assertThat(sidecarRequest.parameters).isEqualTo(parameters)
        assertThat(sidecarRequest.extras.size()).isEqualTo(1)
        assertThat(sidecarRequest.extras.getString("extra")).isEqualTo("value")
    }

    @Test
    fun getPlatformExecuteAppFunctionRequest_sameContents() {
        val extras = Bundle()
        extras.putString("extra", "value")
        val parameters: GenericDocument =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyLong("testLong", 23)
                .build()
        val sidecarRequest =
            com.android.extensions.appfunctions.ExecuteAppFunctionRequest.Builder(
                "targetPkg",
                "targetFunctionId"
            )
                .setExtras(extras)
                .setParameters(parameters)
                .build()

        val platformRequest = SidecarConverter.getPlatformExecuteAppFunctionRequest(sidecarRequest)

        assertThat(platformRequest.targetPackageName).isEqualTo("targetPkg")
        assertThat(platformRequest.functionIdentifier).isEqualTo("targetFunctionId")
        assertThat(platformRequest.parameters).isEqualTo(parameters)
        assertThat(platformRequest.extras.size()).isEqualTo(1)
        assertThat(platformRequest.extras.getString("extra")).isEqualTo("value")
    }

    @Test
    fun getSidecarExecuteAppFunctionResponse_successResponse_sameContents() {
        val resultGd: GenericDocument =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyBoolean(ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE, true)
                .build()
        val platformResponse = ExecuteAppFunctionResponse(resultGd)

        val sidecarResponse = SidecarConverter.getSidecarExecuteAppFunctionResponse(
            platformResponse
        )

        assertThat(
            sidecarResponse.resultDocument.getProperty(
                ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE
            )
        )
            .isEqualTo(booleanArrayOf(true))
    }

    @Test
    fun getSidecarAppFunctionException_sameContents() {
        val bundle = Bundle()
        bundle.putString("key", "value")
        val platformException =
            AppFunctionException(
                AppFunctionException.ERROR_SYSTEM_ERROR,
                "error",
                bundle
            )

        val sidecarException = SidecarConverter.getSidecarAppFunctionException(
            platformException
        )

        assertThat(sidecarException.errorCode).isEqualTo(AppFunctionException.ERROR_SYSTEM_ERROR)
        assertThat(sidecarException.errorMessage).isEqualTo("error")
        assertThat(sidecarException.extras.getString("key")).isEqualTo("value")
    }

    @Test
    fun getPlatformExecuteAppFunctionResponse_successResponse_sameContents() {
        val resultGd: GenericDocument =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyBoolean(ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE, true)
                .build()
        val sidecarResponse =
            com.android.extensions.appfunctions.ExecuteAppFunctionResponse(resultGd)

        val platformResponse = SidecarConverter.getPlatformExecuteAppFunctionResponse(
            sidecarResponse
        )

        assertThat(
            platformResponse.resultDocument.getProperty(
                ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE
            )
        )
            .isEqualTo(booleanArrayOf(true))
    }

    @Test
    fun getPlatformAppFunctionException_sameContents() {
        val bundle = Bundle()
        bundle.putString("key", "value")
        val sidecarException =
            com.android.extensions.appfunctions.AppFunctionException(
                AppFunctionException.ERROR_SYSTEM_ERROR,
                "error",
                bundle
            )

        val platformException = SidecarConverter.getPlatformAppFunctionException(
            sidecarException
        )

        assertThat(platformException.errorCode)
            .isEqualTo(AppFunctionException.ERROR_SYSTEM_ERROR)
        assertThat(platformException.errorMessage).isEqualTo("error")
        assertThat(platformException.extras.getString("key")).isEqualTo("value")
    }
}
