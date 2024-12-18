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

package com.android.server.devicepolicy

import android.annotation.ArrayRes
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertWithMessage
import com.google.errorprone.annotations.CanIgnoreReturnValue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever


/**
 * Run this test with:
 * `atest FrameworksServicesTests:com.android.server.devicepolicy.RecursiveStringArrayResourceResolverTest`
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class RecursiveStringArrayResourceResolverTest {
    private companion object {
        const val PACKAGE = "com.android.test"
        const val ROOT_RESOURCE = "my_root_resource"
        const val SUB_RESOURCE = "my_sub_resource"
        const val EXTERNAL_PACKAGE = "com.external.test"
        const val EXTERNAL_RESOURCE = "my_external_resource"
    }

    private val mResources = mock<Resources>()
    private val mTarget = RecursiveStringArrayResourceResolver(mResources)

    /**
     * Mocks [Resources.getIdentifier] and [Resources.getStringArray] to return [values] and reference under a generated ID.
     * @receiver mocked [Resources] container to configure
     * @param pkg package name to "contain" mocked resource
     * @param name mocked resource name
     * @param values string-array resource values to return when mock is queried
     * @return generated resource ID
     */
    @ArrayRes
    @CanIgnoreReturnValue
    private fun Resources.mockStringArrayResource(pkg: String, name: String, vararg values: String): Int {
        val anId = (pkg + name).hashCode()
        println("Mocking Resources::getIdentifier(name=\"$name\", defType=\"array\", defPackage=\"$pkg\") -> $anId")
        whenever(getIdentifier(eq(name), eq("array"), eq(pkg))).thenReturn(anId)
        println("Mocking Resources::getStringArray(id=$anId) -> ${values.asList()}")
        whenever(getStringArray(eq(anId))).thenReturn(values)
        return anId
    }

    @Test
    fun testCanResolveTheArrayWithoutImports() {
        val values = arrayOf("app.a", "app.b")
        val mockId = mResources.mockStringArrayResource(pkg = PACKAGE, name = ROOT_RESOURCE, values = values)

        val actual = mTarget.resolve(/* pkg= */ PACKAGE, /* rootId = */ mockId)

        assertWithMessage("Values are resolved correctly")
                .that(actual).containsExactlyElementsIn(values)
    }

    @Test
    fun testCanResolveTheArrayWithImports() {
        val externalValues = arrayOf("ext.a", "ext.b", "#import:$PACKAGE/$SUB_RESOURCE")
        mResources.mockStringArrayResource(pkg = EXTERNAL_PACKAGE, name = EXTERNAL_RESOURCE, values = externalValues)
        val subValues = arrayOf("sub.a", "sub.b")
        mResources.mockStringArrayResource(pkg = PACKAGE, name = SUB_RESOURCE, values = subValues)
        val values = arrayOf("app.a", "#import:./$SUB_RESOURCE", "app.b", "#import:$EXTERNAL_PACKAGE/$EXTERNAL_RESOURCE", "app.c")
        val mockId = mResources.mockStringArrayResource(pkg = PACKAGE, name = ROOT_RESOURCE, values = values)

        val actual = mTarget.resolve(/* pkg= */ PACKAGE, /* rootId= */ mockId)

        assertWithMessage("Values are resolved correctly")
                .that(actual).containsExactlyElementsIn((externalValues + subValues + values)
                        .filterNot { it.startsWith("#import:") }
                        .toSet())
    }
}
