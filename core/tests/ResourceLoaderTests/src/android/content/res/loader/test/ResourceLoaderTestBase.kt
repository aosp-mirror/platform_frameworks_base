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

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.AssetsProvider
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import androidx.test.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.io.Closeable

abstract class ResourceLoaderTestBase {

    open lateinit var dataType: DataType

    protected lateinit var context: Context
    protected open val resources: Resources
        get() = context.resources
    protected open val assets: AssetManager
        get() = resources.assets

    // Track opened streams and ResourcesProviders to close them after testing
    private val openedObjects = mutableListOf<Closeable>()

    @Before
    fun setUpBase() {
        context = InstrumentationRegistry.getTargetContext()
    }

    @After
    fun removeAllLoaders() {
        resources.clearLoaders()
        context.applicationContext.resources.clearLoaders()
        openedObjects.forEach {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
        }
    }

    protected fun String.openProvider(
        dataType: DataType = this@ResourceLoaderTestBase.dataType
    ): ResourcesProvider = when (dataType) {
        DataType.APK -> {
            context.copiedRawFile("${this}Apk").use {
                ResourcesProvider.loadFromApk(it, mock(AssetsProvider::class.java))
            }.also { openedObjects += it }
        }
        DataType.ARSC -> {
            openArsc(this, mock(AssetsProvider::class.java))
        }
        DataType.SPLIT -> {
            ResourcesProvider.loadFromSplit(context, this)
        }
        DataType.ASSET -> {
            val assetsProvider = mock(AssetsProvider::class.java)
            doAnswer { byteInputStream() }.`when`(assetsProvider)
                    .loadAsset(eq("assets/Asset.txt"), anyInt())
            ResourcesProvider.empty(assetsProvider)
        }
        DataType.ASSET_FD -> {
            val assetsProvider = mock(AssetsProvider::class.java)
            doAnswer {
                val file = context.filesDir.resolve("Asset.txt")
                file.writeText(this)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.`when`(assetsProvider).loadAssetParcelFd("assets/Asset.txt")
            ResourcesProvider.empty(assetsProvider)
        }
        DataType.NON_ASSET -> {
            val assetsProvider = mock(AssetsProvider::class.java)
            doAnswer {
                val file = context.filesDir.resolve("NonAsset.txt")
                file.writeText(this)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.`when`(assetsProvider).loadAssetParcelFd("NonAsset.txt")
            ResourcesProvider.empty(assetsProvider)
        }
        DataType.NON_ASSET_DRAWABLE -> {
            val assetsProvider = mock(AssetsProvider::class.java)
            doAnswer { context.copiedRawFile(this) }.`when`(assetsProvider)
                    .loadAssetParcelFd("res/drawable-nodpi-v4/non_asset_drawable.xml")
            openArsc(this, assetsProvider)
        }
        DataType.NON_ASSET_BITMAP -> {
            val assetsProvider = mock(AssetsProvider::class.java)
            doAnswer { resources.openRawResource(rawFile(this)) }
                    .`when`(assetsProvider)
                    .loadAsset(eq("res/drawable-nodpi-v4/non_asset_bitmap.png"), anyInt())
            openArsc(this, assetsProvider)
        }
        DataType.NON_ASSET_LAYOUT -> {
            val assetsProvider = mock(AssetsProvider::class.java)
            doAnswer { resources.openRawResource(rawFile(this)) }.`when`(assetsProvider)
                    .loadAsset(eq("res/layout/layout.xml"), anyInt())
            doAnswer { context.copiedRawFile(this) }.`when`(assetsProvider)
                    .loadAssetParcelFd("res/layout/layout.xml")
            openArsc(this, assetsProvider)
        }
    }

    protected fun openArsc(rawName: String, assetsProvider: AssetsProvider): ResourcesProvider {
        return context.copiedRawFile("${rawName}Arsc")
                .use { ResourcesProvider.loadFromTable(it, assetsProvider) }
                .also { openedObjects += it }
    }

    enum class DataType {
        APK,
        ARSC,
        SPLIT,
        ASSET,
        ASSET_FD,
        NON_ASSET,
        NON_ASSET_DRAWABLE,
        NON_ASSET_BITMAP,
        NON_ASSET_LAYOUT,
    }
}
