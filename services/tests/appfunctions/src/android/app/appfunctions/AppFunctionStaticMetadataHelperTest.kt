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

package android.app.appfunctions

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AppFunctionStaticMetadataHelperTest {

    @Test
    fun getStaticSchemaNameForPackage() {
        val actualSchemaName =
            AppFunctionStaticMetadataHelper.getStaticSchemaNameForPackage("com.example.app")

        assertThat(actualSchemaName).isEqualTo("AppFunctionStaticMetadata-com.example.app")
    }

    @Test
    fun getDocumentIdForAppFunction() {
        val packageName = "com.example.app"
        val functionId = "someFunction"

        val actualDocumentId =
            AppFunctionStaticMetadataHelper.getDocumentIdForAppFunction(packageName, functionId)

        assertThat(actualDocumentId).isEqualTo("com.example.app/someFunction")
    }

    @Test
    fun getStaticMetadataQualifiedId() {
        val packageName = "com.example.app"
        val functionId = "someFunction"

        val actualQualifiedId =
            AppFunctionStaticMetadataHelper.getStaticMetadataQualifiedId(packageName, functionId)

        assertThat(actualQualifiedId)
            .isEqualTo("android\$apps-db/app_functions#com.example.app/someFunction")
    }
}
