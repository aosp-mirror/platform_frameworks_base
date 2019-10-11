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
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourceLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.test.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
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
        resources.setLoaders(null)
        openedObjects.forEach {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
        }
    }

    protected fun getString(@StringRes stringRes: Int, debugLog: Boolean = false) =
            logResolution(debugLog) { getString(stringRes) }

    protected fun getDrawable(@DrawableRes drawableRes: Int, debugLog: Boolean = false) =
            logResolution(debugLog) { getDrawable(drawableRes) }

    protected fun getLayout(@LayoutRes layoutRes: Int, debugLog: Boolean = false) =
            logResolution(debugLog) { getLayout(layoutRes) }

    protected fun getDimensionPixelSize(@DimenRes dimenRes: Int, debugLog: Boolean = false) =
            logResolution(debugLog) { getDimensionPixelSize(dimenRes) }

    private fun <T> logResolution(debugLog: Boolean = false, block: Resources.() -> T): T {
        if (debugLog) {
            resources.assets.setResourceResolutionLoggingEnabled(true)
        }

        var thrown = false

        try {
            return resources.block()
        } catch (t: Throwable) {
            // No good way to log to test output other than throwing an exception
            if (debugLog) {
                thrown = true
                throw IllegalStateException(resources.assets.lastResourceResolution, t)
            } else {
                throw t
            }
        } finally {
            if (!thrown && debugLog) {
                throw IllegalStateException(resources.assets.lastResourceResolution)
            }
        }
    }

    protected fun updateConfiguration(block: Configuration.() -> Unit) {
        val configuration = Configuration().apply {
            setTo(resources.configuration)
            block()
        }

        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    protected fun String.openLoader(
        dataType: DataType = this@ResourceLoaderTestBase.dataType
    ): Pair<ResourceLoader, ResourcesProvider> = when (dataType) {
        DataType.APK -> {
            mock(ResourceLoader::class.java) to context.copiedRawFile("${this}Apk").use {
                ResourcesProvider.loadFromApk(it)
            }.also { openedObjects += it }
        }
        DataType.ARSC -> {
            mock(ResourceLoader::class.java) to openArsc(this)
        }
        DataType.SPLIT -> {
            mock(ResourceLoader::class.java) to ResourcesProvider.loadFromSplit(context, this)
        }
        DataType.ASSET -> mockLoader {
            doAnswer { byteInputStream() }.`when`(it)
                    .loadAsset(eq("assets/Asset.txt"), anyInt())
        }
        DataType.ASSET_FD -> mockLoader {
            doAnswer {
                val file = context.filesDir.resolve("Asset.txt")
                file.writeText(this)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.`when`(it).loadAssetFd("assets/Asset.txt")
        }
        DataType.NON_ASSET -> mockLoader {
            doAnswer {
                val file = context.filesDir.resolve("NonAsset.txt")
                file.writeText(this)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.`when`(it).loadAssetFd("NonAsset.txt")
        }
        DataType.NON_ASSET_DRAWABLE -> mockLoader(openArsc(this)) {
            doReturn(null).`when`(it).loadDrawable(argThat { value ->
                value.type == TypedValue.TYPE_STRING &&
                        value.resourceId == 0x7f010001 &&
                        value.string == "res/drawable-nodpi-v4/non_asset_drawable.xml"
            }, eq(0x7f010001), anyInt(), ArgumentMatchers.any())

            doAnswer { context.copiedRawFile(this) }.`when`(it)
                    .loadAssetFd("res/drawable-nodpi-v4/non_asset_drawable.xml")
        }
        DataType.NON_ASSET_BITMAP -> mockLoader(openArsc(this)) {
            doReturn(null).`when`(it).loadDrawable(argThat { value ->
                value.type == TypedValue.TYPE_STRING &&
                        value.resourceId == 0x7f010000 &&
                        value.string == "res/drawable-nodpi-v4/non_asset_bitmap.png"
            }, eq(0x7f010000), anyInt(), ArgumentMatchers.any())

            doAnswer { resources.openRawResourceFd(rawFile(this)).createInputStream() }
                    .`when`(it)
                    .loadAsset(eq("res/drawable-nodpi-v4/non_asset_bitmap.png"), anyInt())
        }
        DataType.NON_ASSET_LAYOUT -> mockLoader(openArsc(this)) {
            doReturn(null).`when`(it)
                    .loadXmlResourceParser("res/layout/layout.xml", 0x7f020000)

            doAnswer { context.copiedRawFile(this) }.`when`(it)
                    .loadAssetFd("res/layout/layout.xml")
        }
    }

    protected fun mockLoader(
        provider: ResourcesProvider = ResourcesProvider.empty(),
        block: (ResourceLoader) -> Unit = {}
    ): Pair<ResourceLoader, ResourcesProvider> {
        return mock(ResourceLoader::class.java, Utils.ANSWER_THROWS)
                .apply(block) to provider
    }

    protected fun openArsc(rawName: String): ResourcesProvider {
        return context.copiedRawFile("${rawName}Arsc")
                .use { ResourcesProvider.loadFromArsc(it) }
                .also { openedObjects += it }
    }

    // This specifically uses addLoader so both behaviors are tested
    protected fun addLoader(vararg pairs: Pair<out ResourceLoader, ResourcesProvider>) {
        pairs.forEach { resources.addLoader(it.first, it.second) }
    }

    protected fun setLoaders(vararg pairs: Pair<out ResourceLoader, ResourcesProvider>) {
        resources.setLoaders(pairs.map { android.util.Pair(it.first, it.second) })
    }

    protected fun addLoader(pair: Pair<out ResourceLoader, ResourcesProvider>, index: Int) {
        resources.addLoader(pair.first, pair.second, index)
    }

    protected fun removeLoader(vararg pairs: Pair<out ResourceLoader, ResourcesProvider>) {
        pairs.forEach { resources.removeLoader(it.first) }
    }

    protected fun getLoaders(): MutableList<Pair<ResourceLoader, ResourcesProvider>> {
        // Cast instead of toMutableList to maintain the same object
        return resources.getLoaders() as MutableList<Pair<ResourceLoader, ResourcesProvider>>
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
