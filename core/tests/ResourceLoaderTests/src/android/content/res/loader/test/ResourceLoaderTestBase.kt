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
import android.content.res.loader.AssetsProvider
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.ArrayMap
import androidx.test.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import java.io.Closeable
import java.io.FileOutputStream
import java.io.File
import java.io.FileDescriptor
import java.util.zip.ZipInputStream

abstract class ResourceLoaderTestBase {
    protected val PROVIDER_ONE: String = "FrameworksResourceLoaderTests_ProviderOne"
    protected val PROVIDER_TWO: String = "FrameworksResourceLoaderTests_ProviderTwo"
    protected val PROVIDER_THREE: String = "FrameworksResourceLoaderTests_ProviderThree"
    protected val PROVIDER_FOUR: String = "FrameworksResourceLoaderTests_ProviderFour"
    protected val PROVIDER_EMPTY: String = "empty"

    companion object {
        /** Converts the map to a stable JSON string representation. */
        fun mapToString(m: Map<String, String>): String {
            return JSONObject(ArrayMap<String, String>().apply { putAll(m) }).toString()
        }

        /** Creates a lambda that runs multiple resources queries and concatenates the results. */
        fun query(queries: Map<String, (Resources) -> String>): Resources.() -> String {
            return {
                val resultMap = ArrayMap<String, String>()
                queries.forEach { q ->
                    resultMap[q.key] = try {
                        q.value.invoke(this)
                    } catch (e: Exception) {
                        e.javaClass.simpleName
                    }
                }
                mapToString(resultMap)
            }
        }
    }

    // Data type of the current test iteration
    open lateinit var dataType: DataType

    protected lateinit var context: Context
    protected lateinit var resources: Resources

    // Track opened streams and ResourcesProviders to close them after testing
    private val openedObjects = mutableListOf<Closeable>()

    @Before
    fun setUpBase() {
        context = InstrumentationRegistry.getTargetContext()
                .createConfigurationContext(Configuration())
        resources = context.resources
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

    protected fun String.openProvider(dataType: DataType,
                                      assetsProvider: MemoryAssetsProvider?): ResourcesProvider {
        if (assetsProvider != null) {
            openedObjects += assetsProvider
        }
        return when (dataType) {
            DataType.APK_DISK_FD -> {
                val file = context.copiedAssetFile("$this.apk")
                ResourcesProvider.loadFromApk(ParcelFileDescriptor.fromFd(file.fd),
                        assetsProvider).apply {
                    file.close()
                }
            }
            DataType.APK_DISK_FD_OFFSETS -> {
                val asset = context.assets.openFd("$this.apk")
                ResourcesProvider.loadFromApk(asset.parcelFileDescriptor, asset.startOffset,
                        asset.length, assetsProvider).apply {
                    asset.close()
                }
            }
            DataType.ARSC_DISK_FD -> {
                val file = context.copiedAssetFile("$this.arsc")
                ResourcesProvider.loadFromTable(ParcelFileDescriptor.fromFd(file.fd),
                        assetsProvider).apply {
                    file.close()
                }
            }
            DataType.ARSC_DISK_FD_OFFSETS -> {
                val asset = context.assets.openFd("$this.arsc")
                ResourcesProvider.loadFromTable(asset.parcelFileDescriptor, asset.startOffset,
                        asset.length, assetsProvider).apply {
                    asset.close()
                }
            }
            DataType.APK_RAM_OFFSETS -> {
                val asset = context.assets.openFd("$this.apk")
                val leadingGarbageSize = 100L
                val trailingGarbageSize = 55L
                val fd = loadAssetIntoMemory(asset, leadingGarbageSize.toInt(),
                        trailingGarbageSize.toInt())
                ResourcesProvider.loadFromApk(fd, leadingGarbageSize, asset.declaredLength,
                        assetsProvider).apply {
                    asset.close()
                    fd.close()
                }
            }
            DataType.APK_RAM_FD -> {
                val asset = context.assets.openFd("$this.apk")
                var fd = loadAssetIntoMemory(asset)
                ResourcesProvider.loadFromApk(fd, assetsProvider).apply {
                    asset.close()
                    fd.close()
                }
            }
            DataType.ARSC_RAM_MEMORY -> {
                val asset = context.assets.openFd("$this.arsc")
                var fd = loadAssetIntoMemory(asset)
                ResourcesProvider.loadFromTable(fd, assetsProvider).apply {
                    asset.close()
                    fd.close()
                }
            }
            DataType.ARSC_RAM_MEMORY_OFFSETS -> {
                val asset = context.assets.openFd("$this.arsc")
                val leadingGarbageSize = 100L
                val trailingGarbageSize = 55L
                val fd = loadAssetIntoMemory(asset, leadingGarbageSize.toInt(),
                        trailingGarbageSize.toInt())
                ResourcesProvider.loadFromTable(fd, leadingGarbageSize, asset.declaredLength,
                        assetsProvider).apply {
                    asset.close()
                    fd.close()
                }
            }
            DataType.EMPTY -> {
                if (equals(PROVIDER_EMPTY)) {
                    ResourcesProvider.empty(EmptyAssetsProvider())
                } else {
                    if (assetsProvider == null) ResourcesProvider.empty(ZipAssetsProvider(this))
                        else ResourcesProvider.empty(assetsProvider)
                }
            }
            DataType.DIRECTORY -> {
                ResourcesProvider.loadFromDirectory(zipToDir("$this.apk").absolutePath,
                        assetsProvider)
            }
            DataType.SPLIT -> {
                ResourcesProvider.loadFromSplit(context, "${this}_Split")
            }
        }
    }

    class EmptyAssetsProvider : AssetsProvider

    /** An AssetsProvider that reads from a zip asset. */
    inner class ZipAssetsProvider(val providerName: String) : AssetsProvider {
        val root: File = zipToDir("$providerName.apk")

        override fun loadAssetFd(path: String, accessMode: Int): AssetFileDescriptor? {
            val f = File(root, path)
            return if (f.exists()) AssetFileDescriptor(
                    ParcelFileDescriptor.open(File(root, path),
                            ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH) else null
        }
    }

    /** AssetsProvider for testing that returns file descriptors to files in RAM. */
    class MemoryAssetsProvider : AssetsProvider, Closeable {
        var loadAssetResults = HashMap<String, FileDescriptor>()

        fun addLoadAssetFdResult(path: String, value: String) = apply {
            val fd = Os.memfd_create(path, 0)
            val valueBytes = value.toByteArray()
            Os.write(fd, valueBytes, 0, valueBytes.size)
            loadAssetResults[path] = fd
        }

        override fun loadAssetFd(path: String, accessMode: Int): AssetFileDescriptor? {
            return if (loadAssetResults.containsKey(path)) AssetFileDescriptor(
                    ParcelFileDescriptor.dup(loadAssetResults[path]), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH) else null
        }

        override fun close() {
            for (f in loadAssetResults.values) {
                Os.close(f)
            }
        }
    }

    /** Extracts an archive-based asset into a directory on disk. */
    private fun zipToDir(name: String): File {
        val root = File(context.filesDir, name.split('.')[0])
        if (root.exists()) {
            return root
        }

        root.mkdir()
        ZipInputStream(context.assets.open(name)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val file = File(root, entry.name)
                if (entry.isDirectory) {
                    continue
                }

                file.parentFile.mkdirs()
                file.outputStream().use { output ->
                    var b = zis.read()
                    while (b != -1) {
                        output.write(b)
                        b = zis.read()
                    }
                }
            }
        }
        return root
    }

    /** Loads the asset into a temporary file stored in RAM. */
    private fun loadAssetIntoMemory(
        asset: AssetFileDescriptor,
        leadingGarbageSize: Int = 0,
        trailingGarbageSize: Int = 0
    ): ParcelFileDescriptor {
        val originalFd = Os.memfd_create(asset.toString(), 0 /* flags */)
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
        EMPTY,
        DIRECTORY,
        SPLIT
    }
}
