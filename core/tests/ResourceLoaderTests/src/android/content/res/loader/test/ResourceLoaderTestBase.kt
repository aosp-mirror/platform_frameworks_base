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
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import java.io.Closeable
import java.io.FileOutputStream

abstract class ResourceLoaderTestBase {
    protected val PROVIDER_ONE: String = "FrameworksResourceLoaderTests_ProviderOne"
    protected val PROVIDER_TWO: String = "FrameworksResourceLoaderTests_ProviderTwo"
    protected val PROVIDER_THREE: String = "FrameworksResourceLoaderTests_ProviderThree"
    protected val PROVIDER_FOUR: String = "FrameworksResourceLoaderTests_ProviderFour"

    // Data type of the current test iteration
    open lateinit var dataType: DataType

    protected lateinit var context: Context
    protected open val resources: Resources
        get() = context.resources

    // Track opened streams and ResourcesProviders to close them after testing
    private val openedObjects = mutableListOf<Closeable>()

    @Before
    fun setUpBase() {
        context = InstrumentationRegistry.getTargetContext()
                .createConfigurationContext(Configuration())
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

    protected fun String.openProvider(dataType: DataType)
            :ResourcesProvider = when (dataType) {
        DataType.APK_DISK_FD -> {
            val file = context.copiedAssetFile("${this}.apk")
            ResourcesProvider.loadFromApk(ParcelFileDescriptor.fromFd(file.fd)).apply {
                file.close()
            }
        }
        DataType.APK_DISK_FD_OFFSETS -> {
            val asset = context.assets.openFd("${this}.apk")
            ResourcesProvider.loadFromApk(asset.parcelFileDescriptor, asset.startOffset,
                    asset.length, null).apply {
                asset.close()
            }
        }
        DataType.ARSC_DISK_FD -> {
            val file = context.copiedAssetFile("${this}.arsc")
            ResourcesProvider.loadFromTable(ParcelFileDescriptor.fromFd(file.fd), null).apply {
                file.close()
            }
        }
        DataType.ARSC_DISK_FD_OFFSETS -> {
            val asset = context.assets.openFd("${this}.arsc")
            ResourcesProvider.loadFromTable(asset.parcelFileDescriptor, asset.startOffset,
                    asset.length, null).apply {
                asset.close()
            }
        }
        DataType.APK_RAM_OFFSETS -> {
            val asset = context.assets.openFd("${this}.apk")
            val leadingGarbageSize = 100L
            val trailingGarbageSize = 55L
            val fd = loadAssetIntoMemory(asset, leadingGarbageSize.toInt(),
                    trailingGarbageSize.toInt())
            ResourcesProvider.loadFromApk(fd, leadingGarbageSize, asset.declaredLength,
                    null).apply {
                asset.close()
                fd.close()
            }
        }
        DataType.APK_RAM_FD -> {
            val asset = context.assets.openFd("${this}.apk")
            var fd = loadAssetIntoMemory(asset)
            ResourcesProvider.loadFromApk(fd).apply {
                asset.close()
                fd.close()
            }
        }
        DataType.ARSC_RAM_MEMORY -> {
            val asset = context.assets.openFd("${this}.arsc")
            var fd = loadAssetIntoMemory(asset)
            ResourcesProvider.loadFromTable(fd, null).apply {
                asset.close()
                fd.close()
            }
        }
        DataType.ARSC_RAM_MEMORY_OFFSETS -> {
            val asset = context.assets.openFd("${this}.arsc")
            val leadingGarbageSize = 100L
            val trailingGarbageSize = 55L
            val fd = loadAssetIntoMemory(asset, leadingGarbageSize.toInt(),
                    trailingGarbageSize.toInt())
            ResourcesProvider.loadFromTable(fd, leadingGarbageSize, asset.declaredLength,
                    null).apply {
                asset.close()
                fd.close()
            }
        }
        DataType.SPLIT -> {
            ResourcesProvider.loadFromSplit(context, "${this}_Split")
        }
    }

    /** Loads the asset into a temporary file stored in RAM. */
    private fun loadAssetIntoMemory(asset: AssetFileDescriptor,
                                    leadingGarbageSize: Int = 0,
                                    trailingGarbageSize: Int = 0
    ): ParcelFileDescriptor {
        val originalFd = Os.memfd_create(asset.toString(), 0 /* flags */);
        val fd = ParcelFileDescriptor.dup(originalFd)
        Os.close(originalFd)

        val input = asset.createInputStream()
        FileOutputStream(fd.fileDescriptor).use { output ->
            // Add garbage before the APK data
            for (i in 0 until leadingGarbageSize) {
                output.write(Math.random().toInt())
            }

            for (i in 0 until asset.length.toInt()) {
                output.write(input.read())
            }

            // Add garbage after the APK data
            for (i in 0 until trailingGarbageSize) {
                output.write(Math.random().toInt())
            }
        }

        return fd
    }

    enum class DataType {
        APK_DISK_FD,
        APK_DISK_FD_OFFSETS,
        APK_RAM_FD,
        APK_RAM_OFFSETS,
        ARSC_DISK_FD,
        ARSC_DISK_FD_OFFSETS,
        ARSC_RAM_MEMORY,
        ARSC_RAM_MEMORY_OFFSETS,
        SPLIT,
    }
}
