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
 * limitations under the License
 */

package android.content.res.loader.test

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.content.res.loader.ResourceLoader
import android.content.res.loader.ResourcesProvider
import com.google.common.truth.Truth.assertThat
import org.hamcrest.CoreMatchers.not
import org.junit.Assume.assumeThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(Parameterized::class)
class ResourceLoaderLayoutTest : ResourceLoaderTestBase() {

    companion object {

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data() = arrayOf(DataType.APK, DataType.ARSC)
    }

    @field:Parameterized.Parameter(0)
    override lateinit var dataType: DataType

    @Test
    fun singleLoader() {
        val original = getLayout(android.R.layout.activity_list_item)
        val mockXml = mock(XmlResourceParser::class.java)
        val loader = "layoutWithoutFile".openLoader()
        `when`(loader.first.loadXmlResourceParser(any(), anyInt()))
                .thenReturn(mockXml)

        addLoader(loader)

        val layout = getLayout(android.R.layout.activity_list_item)
        loader.verifyLoadLayoutCalled()

        assertThat(layout).isNotEqualTo(original)
        assertThat(layout).isSameAs(mockXml)
    }

    @Test
    fun multipleLoaders() {
        val original = getLayout(android.R.layout.activity_list_item)
        val loaderOne = "layoutWithoutFile".openLoader()
        val loaderTwo = "layoutWithoutFile".openLoader()

        val mockXml = mock(XmlResourceParser::class.java)
        `when`(loaderTwo.first.loadXmlResourceParser(any(), anyInt()))
                .thenReturn(mockXml)

        addLoader(loaderOne, loaderTwo)

        val layout = getLayout(android.R.layout.activity_list_item)
        loaderOne.verifyLoadLayoutNotCalled()
        loaderTwo.verifyLoadLayoutCalled()

        assertThat(layout).isNotEqualTo(original)
        assertThat(layout).isSameAs(mockXml)
    }

    @Test(expected = Resources.NotFoundException::class)
    fun multipleLoadersNoReturnWithoutFile() {
        val loaderOne = "layoutWithoutFile".openLoader()
        val loaderTwo = "layoutWithoutFile".openLoader()

        addLoader(loaderOne, loaderTwo)

        try {
            getLayout(android.R.layout.activity_list_item)
        } finally {
            // We expect the call to fail because at least one loader must resolve the overridden
            // layout, but we should still verify that both loaders were called before allowing
            // the exception to propagate.
            loaderOne.verifyLoadLayoutNotCalled()
            loaderTwo.verifyLoadLayoutCalled()
        }
    }

    @Test
    fun multipleLoadersReturnWithFile() {
        // Can't return a file if an ARSC
        assumeThat(dataType, not(DataType.ARSC))

        val loaderOne = "layoutWithFile".openLoader()
        val loaderTwo = "layoutWithFile".openLoader()

        addLoader(loaderOne, loaderTwo)

        val xml = getLayout(android.R.layout.activity_list_item)
        loaderOne.verifyLoadLayoutNotCalled()
        loaderTwo.verifyLoadLayoutCalled()

        assertThat(xml).isNotNull()
    }

    @Test
    fun unhandledResourceIgnoresLoaders() {
        val loader = "layoutWithoutFile".openLoader()
        val mockXml = mock(XmlResourceParser::class.java)
        `when`(loader.first.loadXmlResourceParser(any(), anyInt()))
                .thenReturn(mockXml)
        addLoader(loader)

        getLayout(android.R.layout.preference_category)

        verify(loader.first, never())
                .loadXmlResourceParser(anyString(), anyInt())

        getLayout(android.R.layout.activity_list_item)

        loader.verifyLoadLayoutCalled()
    }

    private fun Pair<ResourceLoader, ResourcesProvider>.verifyLoadLayoutCalled() {
        verify(first).loadXmlResourceParser(
                "res/layout/activity_list_item.xml",
                        android.R.layout.activity_list_item
        )
    }

    private fun Pair<ResourceLoader, ResourcesProvider>.verifyLoadLayoutNotCalled() {
        verify(first, never()).loadXmlResourceParser(
                anyString(),
                anyInt()
        )
    }
}
