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

package android.content.res.loader.test

import android.content.res.loader.DirectoryResourceLoader
import android.content.res.loader.ResourceLoader
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File

class DirectoryResourceLoaderTest : ResourceLoaderTestBase() {

    @get:Rule
    val testName = TestName()

    private lateinit var testDir: File
    private lateinit var loader: ResourceLoader

    @Before
    fun setUpTestDir() {
        testDir = context.filesDir.resolve("DirectoryResourceLoaderTest_${testName.methodName}")
        loader = DirectoryResourceLoader(testDir)
    }

    @After
    fun deleteTestFiles() {
        testDir.deleteRecursively()
    }

    @Test
    fun loadDrawableXml() {
        "nonAssetDrawableOne" writeTo "res/drawable-nodpi-v4/non_asset_drawable.xml"
        val provider = openArsc("nonAssetDrawableOne")

        fun getValue() = (resources.getDrawable(R.drawable.non_asset_drawable) as ColorDrawable)
                .color

        assertThat(getValue()).isEqualTo(Color.parseColor("#B2D2F2"))

        addLoader(loader to provider)

        assertThat(getValue()).isEqualTo(Color.parseColor("#A3C3E3"))
    }

    @Test
    fun loadDrawableBitmap() {
        "nonAssetBitmapGreen" writeTo "res/drawable-nodpi-v4/non_asset_bitmap.png"
        val provider = openArsc("nonAssetBitmapGreen")

        fun getValue() = (resources.getDrawable(R.drawable.non_asset_bitmap) as BitmapDrawable)
                .bitmap.getColor(0, 0).toArgb()

        assertThat(getValue()).isEqualTo(Color.RED)

        addLoader(loader to provider)

        assertThat(getValue()).isEqualTo(Color.GREEN)
    }

    @Test
    fun loadXml() {
        "layoutOne" writeTo "res/layout/layout.xml"
        val provider = openArsc("layoutOne")

        fun getValue() = resources.getLayout(R.layout.layout).advanceToRoot().name

        assertThat(getValue()).isEqualTo("FrameLayout")

        addLoader(loader to provider)

        assertThat(getValue()).isEqualTo("RelativeLayout")
    }

    private infix fun String.writeTo(path: String) {
        val testFile = testDir.resolve(path)
        testFile.parentFile!!.mkdirs()
        resources.openRawResource(rawFile(this))
                .copyTo(testFile.outputStream())
    }
}
