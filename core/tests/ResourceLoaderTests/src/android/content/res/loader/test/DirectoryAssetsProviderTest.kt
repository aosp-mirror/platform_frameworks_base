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

import android.content.res.loader.AssetsProvider
import android.content.res.loader.DirectoryAssetsProvider
import android.content.res.loader.ResourcesLoader
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

class DirectoryAssetsProviderTest : ResourceLoaderTestBase() {

    @get:Rule
    val testName = TestName()

    private lateinit var testDir: File
    private lateinit var assetsProvider: AssetsProvider
    private lateinit var loader: ResourcesLoader

    @Before
    fun setUpTestDir() {
        testDir = context.filesDir.resolve("DirectoryAssetsProvider_${testName.methodName}")
        assetsProvider = DirectoryAssetsProvider(testDir)
        loader = ResourcesLoader()
        resources.addLoaders(loader)
    }

    @After
    fun deleteTestFiles() {
        testDir.deleteRecursively()
    }

    @Test
    fun loadDrawableXml() {
        "nonAssetDrawableOne" writeTo "res/drawable-nodpi-v4/non_asset_drawable.xml"
        val provider = openArsc("nonAssetDrawableOne", assetsProvider)

        fun getValue() = (resources.getDrawable(R.drawable.non_asset_drawable) as ColorDrawable)
                .color

        assertThat(getValue()).isEqualTo(Color.parseColor("#B2D2F2"))

        loader.addProvider(provider)

        assertThat(getValue()).isEqualTo(Color.parseColor("#000001"))
    }

    @Test
    fun loadDrawableBitmap() {
        "nonAssetBitmapGreen" writeTo "res/drawable-nodpi-v4/non_asset_bitmap.png"
        val provider = openArsc("nonAssetBitmapGreen", assetsProvider)

        fun getValue() = (resources.getDrawable(R.drawable.non_asset_bitmap) as BitmapDrawable)
                .bitmap.getColor(0, 0).toArgb()

        assertThat(getValue()).isEqualTo(Color.MAGENTA)

        loader.addProvider(provider)

        assertThat(getValue()).isEqualTo(Color.GREEN)
    }

    @Test
    fun loadXml() {
        "layoutOne" writeTo "res/layout/layout.xml"
        val provider = openArsc("layoutOne", assetsProvider)

        fun getValue() = resources.getLayout(R.layout.layout).advanceToRoot().name

        assertThat(getValue()).isEqualTo("MysteryLayout")

        loader.addProvider(provider)

        assertThat(getValue()).isEqualTo("RelativeLayout")
    }

    private infix fun String.writeTo(path: String) {
        val testFile = testDir.resolve(path)
        testFile.parentFile!!.mkdirs()
        resources.openRawResource(rawFile(this))
                .copyTo(testFile.outputStream())
    }
}
