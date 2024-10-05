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

import android.app.appfunctions.AppFunctionManager.APP_FUNCTION_STATE_DEFAULT
import android.app.appfunctions.AppFunctionManager.APP_FUNCTION_STATE_DISABLED
import android.app.appfunctions.AppFunctionManager.APP_FUNCTION_STATE_ENABLED
import android.app.appsearch.AppSearchSchema
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AppFunctionRuntimeMetadataTest {

    @Test
    fun getRuntimeSchemaNameForPackage() {
        val actualSchemaName =
            AppFunctionRuntimeMetadata.getRuntimeSchemaNameForPackage("com.example.app")

        assertThat(actualSchemaName).isEqualTo("AppFunctionRuntimeMetadata-com.example.app")
    }

    @Test
    fun testCreateChildRuntimeSchema() {
        val runtimeSchema: AppSearchSchema =
            AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema("com.example.app")

        assertThat(runtimeSchema.schemaType).isEqualTo("AppFunctionRuntimeMetadata-com.example.app")
        val propertyNameSet = runtimeSchema.properties.map { it.name }.toSet()
        assertThat(propertyNameSet.contains(AppFunctionRuntimeMetadata.PROPERTY_FUNCTION_ID))
            .isTrue()
        assertThat(propertyNameSet.contains(AppFunctionRuntimeMetadata.PROPERTY_PACKAGE_NAME))
            .isTrue()
        assertThat(propertyNameSet.contains(AppFunctionRuntimeMetadata.PROPERTY_ENABLED)).isTrue()
        assertThat(
                propertyNameSet.contains(
                    AppFunctionRuntimeMetadata.PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID
                )
            )
            .isTrue()
    }

    @Test
    fun testCreateParentRuntimeSchema() {
        val runtimeSchema: AppSearchSchema =
            AppFunctionRuntimeMetadata.createParentAppFunctionRuntimeSchema()

        assertThat(runtimeSchema.schemaType).isEqualTo("AppFunctionRuntimeMetadata")
        val propertyNameSet = runtimeSchema.properties.map { it.name }.toSet()
        assertThat(propertyNameSet.contains(AppFunctionRuntimeMetadata.PROPERTY_FUNCTION_ID))
            .isTrue()
        assertThat(propertyNameSet.contains(AppFunctionRuntimeMetadata.PROPERTY_PACKAGE_NAME))
            .isTrue()
        assertThat(propertyNameSet.contains(AppFunctionRuntimeMetadata.PROPERTY_ENABLED)).isTrue()
        assertThat(
                propertyNameSet.contains(
                    AppFunctionRuntimeMetadata.PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID
                )
            )
            .isTrue()
    }

    @Test
    fun testGetPackageNameFromSchema() {
        val expectedPackageName = "com.foo.test"
        val expectedPackageName2 = "com.bar.test"
        val testSchemaType =
            AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema(expectedPackageName)
                .schemaType
        val testSchemaType2 =
            AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema(expectedPackageName2)
                .schemaType

        val actualPackageName = AppFunctionRuntimeMetadata.getPackageNameFromSchema(testSchemaType)
        val actualPackageName2 =
            AppFunctionRuntimeMetadata.getPackageNameFromSchema(testSchemaType2)

        assertThat(actualPackageName).isEqualTo(expectedPackageName)
        assertThat(actualPackageName2).isEqualTo(expectedPackageName2)
    }

    @Test
    fun testGetPackageNameFromParentSchema() {
        val expectedPackageName = AppFunctionRuntimeMetadata.APP_FUNCTION_INDEXER_PACKAGE
        val testSchemaType =
            AppFunctionRuntimeMetadata.createParentAppFunctionRuntimeSchema().schemaType

        val actualPackageName = AppFunctionRuntimeMetadata.getPackageNameFromSchema(testSchemaType)

        assertThat(actualPackageName).isEqualTo(expectedPackageName)
    }

    @Test
    fun testBuild() {
        val runtimeMetadata = AppFunctionRuntimeMetadata.Builder("com.pkg", "funcId").build()

        assertThat(runtimeMetadata.packageName).isEqualTo("com.pkg")
        assertThat(runtimeMetadata.functionId).isEqualTo("funcId")
        assertThat(runtimeMetadata.enabled).isEqualTo(APP_FUNCTION_STATE_DEFAULT)
        assertThat(runtimeMetadata.appFunctionStaticMetadataQualifiedId)
            .isEqualTo("android\$apps-db/app_functions#com.pkg/funcId")
    }

    @Test
    fun setEnabled_enabled() {
        val runtimeMetadata =
            AppFunctionRuntimeMetadata.Builder("com.pkg", "funcId").setEnabled(APP_FUNCTION_STATE_ENABLED).build()

        assertThat(runtimeMetadata.enabled).isEqualTo(APP_FUNCTION_STATE_ENABLED)
    }

    @Test
    fun setEnabled_disabled() {
        val runtimeMetadata =
            AppFunctionRuntimeMetadata.Builder("com.pkg", "funcId").setEnabled(
                APP_FUNCTION_STATE_DISABLED).build()

        assertThat(runtimeMetadata.enabled).isEqualTo(APP_FUNCTION_STATE_DISABLED)
    }

    @Test
    fun setEnabled_default() {
        val runtimeMetadata =
            AppFunctionRuntimeMetadata.Builder("com.pkg", "funcId").setEnabled(
                APP_FUNCTION_STATE_DEFAULT).build()

        assertThat(runtimeMetadata.enabled).isEqualTo(APP_FUNCTION_STATE_DEFAULT)
    }

    @Test
    fun setEnabled_illegalArgument() {
        val runtimeMetadataBuilder =
            AppFunctionRuntimeMetadata.Builder("com.pkg", "funcId")
        assertFailsWith<IllegalArgumentException>("Value of EnabledState is unsupported.") {
            runtimeMetadataBuilder.setEnabled(-1)
        }
    }
}
