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
import android.content.res.loader.ResourceLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.google.common.truth.Truth.assertThat
import org.hamcrest.CoreMatchers.not
import org.junit.Assume.assumeThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.argThat
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(Parameterized::class)
class ResourceLoaderDrawableTest : ResourceLoaderTestBase() {

    companion object {

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data() = arrayOf(DataType.APK, DataType.ARSC)
    }

    @field:Parameterized.Parameter(0)
    override lateinit var dataType: DataType

    @Test
    fun matchingConfig() {
        val original = getDrawable(android.R.drawable.ic_delete)
        val loader = "drawableMdpiWithoutFile".openLoader()
        `when`(loader.first.loadDrawable(any(), anyInt(), anyInt(), any()))
                .thenReturn(ColorDrawable(Color.BLUE))

        addLoader(loader)

        updateConfiguration { densityDpi = 160 /* mdpi */ }

        val drawable = getDrawable(android.R.drawable.ic_delete)

        loader.verifyLoadDrawableCalled()

        assertThat(drawable).isNotEqualTo(original)
        assertThat(drawable).isInstanceOf(ColorDrawable::class.java)
        assertThat((drawable as ColorDrawable).color).isEqualTo(Color.BLUE)
    }

    @Test
    fun worseConfig() {
        val loader = "drawableMdpiWithoutFile".openLoader()
        addLoader(loader)

        updateConfiguration { densityDpi = 480 /* xhdpi */ }

        getDrawable(android.R.drawable.ic_delete)

        verify(loader.first, never()).loadDrawable(any(), anyInt(), anyInt(), any())
    }

    @Test
    fun multipleLoaders() {
        val original = getDrawable(android.R.drawable.ic_delete)
        val loaderOne = "drawableMdpiWithoutFile".openLoader()
        val loaderTwo = "drawableMdpiWithoutFile".openLoader()

        `when`(loaderTwo.first.loadDrawable(any(), anyInt(), anyInt(), any()))
                .thenReturn(ColorDrawable(Color.BLUE))

        addLoader(loaderOne, loaderTwo)

        updateConfiguration { densityDpi = 160 /* mdpi */ }

        val drawable = getDrawable(android.R.drawable.ic_delete)
        loaderOne.verifyLoadDrawableNotCalled()
        loaderTwo.verifyLoadDrawableCalled()

        assertThat(drawable).isNotEqualTo(original)
        assertThat(drawable).isInstanceOf(ColorDrawable::class.java)
        assertThat((drawable as ColorDrawable).color).isEqualTo(Color.BLUE)
    }

    @Test(expected = Resources.NotFoundException::class)
    fun multipleLoadersNoReturnWithoutFile() {
        val loaderOne = "drawableMdpiWithoutFile".openLoader()
        val loaderTwo = "drawableMdpiWithoutFile".openLoader()

        addLoader(loaderOne, loaderTwo)

        updateConfiguration { densityDpi = 160 /* mdpi */ }

        try {
            getDrawable(android.R.drawable.ic_delete)
        } finally {
            // We expect the call to fail because at least the loader won't resolve the overridden
            // drawable, but we should still verify that both loaders were called before allowing
            // the exception to propagate.
            loaderOne.verifyLoadDrawableNotCalled()
            loaderTwo.verifyLoadDrawableCalled()
        }
    }

    @Test
    fun multipleLoadersReturnWithFile() {
        // Can't return a file if an ARSC
        assumeThat(dataType, not(DataType.ARSC))

        val original = getDrawable(android.R.drawable.ic_delete)
        val loaderOne = "drawableMdpiWithFile".openLoader()
        val loaderTwo = "drawableMdpiWithFile".openLoader()

        addLoader(loaderOne, loaderTwo)

        updateConfiguration { densityDpi = 160 /* mdpi */ }

        val drawable = getDrawable(android.R.drawable.ic_delete)
        loaderOne.verifyLoadDrawableNotCalled()
        loaderTwo.verifyLoadDrawableCalled()

        assertThat(drawable).isNotNull()
        assertThat(drawable).isInstanceOf(original.javaClass)
    }

    @Test
    fun unhandledResourceIgnoresLoaders() {
        val loader = "drawableMdpiWithoutFile".openLoader()
        `when`(loader.first.loadDrawable(any(), anyInt(), anyInt(), any()))
                .thenReturn(ColorDrawable(Color.BLUE))
        addLoader(loader)

        getDrawable(android.R.drawable.ic_menu_add)

        loader.verifyLoadDrawableNotCalled()

        getDrawable(android.R.drawable.ic_delete)

        loader.verifyLoadDrawableCalled()
    }

    private fun Pair<ResourceLoader, ResourcesProvider>.verifyLoadDrawableCalled() {
        verify(first).loadDrawable(
                argThat {
                    it.density == 160 &&
                            it.resourceId == android.R.drawable.ic_delete &&
                            it.string == "res/drawable-mdpi-v4/ic_delete.png"
                },
                eq(android.R.drawable.ic_delete),
                eq(0),
                any()
        )
    }

    private fun Pair<ResourceLoader, ResourcesProvider>.verifyLoadDrawableNotCalled() {
        verify(first, never()).loadDrawable(
                any(),
                anyInt(),
                anyInt(),
                any()
        )
    }
}
