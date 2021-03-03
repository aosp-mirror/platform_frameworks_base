/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.os

import android.content.pm.PackageParser
import android.content.pm.PackageParserCacheHelper.ReadHelper
import android.content.pm.PackageParserCacheHelper.WriteHelper
import android.content.pm.parsing.ParsingPackageImpl
import android.content.pm.parsing.ParsingPackageRead
import android.content.pm.parsing.ParsingPackageUtils
import android.content.pm.parsing.result.ParseInput
import android.content.pm.parsing.result.ParseTypeImpl
import android.content.res.TypedArray
import android.perftests.utils.BenchmarkState
import android.perftests.utils.PerfStatusReporter
import androidx.test.filters.LargeTest
import com.android.internal.util.ConcurrentUtils
import libcore.io.IoUtils
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@LargeTest
@RunWith(Parameterized::class)
class PackageParsingPerfTest {

    companion object {
        private const val PARALLEL_QUEUE_CAPACITY = 10
        private const val PARALLEL_MAX_THREADS = 4

        private const val QUEUE_POLL_TIMEOUT_SECONDS = 5L

        // TODO: Replace this with core version of SYSTEM_PARTITIONS
        val FOLDERS_TO_TEST = listOf(
            Environment.getRootDirectory(),
            Environment.getVendorDirectory(),
            Environment.getOdmDirectory(),
            Environment.getOemDirectory(),
            Environment.getOemDirectory(),
            Environment.getSystemExtDirectory()
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): Array<Params> {
            val apks = FOLDERS_TO_TEST
                .filter(File::exists)
                .map(File::walkTopDown)
                .flatMap(Sequence<File>::asIterable)
                .filter { it.name.endsWith(".apk") }

            return arrayOf(
                Params(1, apks) { ParallelParser1(it?.let(::PackageCacher1)) },
                Params(2, apks) { ParallelParser2(it?.let(::PackageCacher2)) }
            )
        }

        data class Params(
            val version: Int,
            val apks: List<File>,
            val cacheDirToParser: (File?) -> ParallelParser<*>
        ) {
            // For test name formatting
            override fun toString() = "v$version"
        }
    }

    @get:Rule
    var perfStatusReporter = PerfStatusReporter()

    @get:Rule
    var testFolder = TemporaryFolder()

    @Parameterized.Parameter(0)
    lateinit var params: Params

    private val state: BenchmarkState get() = perfStatusReporter.benchmarkState
    private val apks: List<File> get() = params.apks

    private fun safeParse(parser: ParallelParser<*>, file: File) {
        try {
            parser.parse(file)
        } catch (e: Exception) {
            // ignore
        }
    }

    @Test
    fun sequentialNoCache() {
        params.cacheDirToParser(null).use { parser ->
            while (state.keepRunning()) {
                apks.forEach {
                    safeParse(parser, it)
                }
            }
        }
    }

    @Test
    fun sequentialCached() {
        params.cacheDirToParser(testFolder.newFolder()).use { parser ->
            // Fill the cache
            apks.forEach { safeParse(parser, it) }

            while (state.keepRunning()) {
                apks.forEach { safeParse(parser, it) }
            }
        }
    }

    @Test
    fun parallelNoCache() {
        params.cacheDirToParser(null).use { parser ->
            while (state.keepRunning()) {
                apks.forEach { parser.submit(it) }
                repeat(apks.size) { parser.take() }
            }
        }
    }

    @Test
    fun parallelCached() {
        params.cacheDirToParser(testFolder.newFolder()).use { parser ->
            // Fill the cache
            apks.forEach { safeParse(parser, it) }

            while (state.keepRunning()) {
                apks.forEach { parser.submit(it) }
                repeat(apks.size) { parser.take() }
            }
        }
    }

    abstract class ParallelParser<PackageType : Parcelable>(
        private val cacher: PackageCacher<PackageType>? = null
    ) : AutoCloseable {
        private val queue = ArrayBlockingQueue<Any>(PARALLEL_QUEUE_CAPACITY)
        private val service = ConcurrentUtils.newFixedThreadPool(
            PARALLEL_MAX_THREADS, "package-parsing-test",
            Process.THREAD_PRIORITY_FOREGROUND)

        fun submit(file: File) = service.submit { queue.put(parse(file)) }

        fun take() = queue.poll(QUEUE_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        override fun close() {
            service.shutdownNow()
        }

        fun parse(file: File) = cacher?.getCachedResult(file)
            ?: parseImpl(file).also { cacher?.cacheResult(file, it) }

        protected abstract fun parseImpl(file: File): PackageType
    }

    class ParallelParser1(private val cacher: PackageCacher1? = null)
        : ParallelParser<PackageParser.Package>(cacher) {
        val parser = PackageParser().apply {
            setCallback { true }
        }

        override fun parseImpl(file: File) = parser.parsePackage(file, 0, cacher != null)
    }

    class ParallelParser2(cacher: PackageCacher2? = null)
        : ParallelParser<ParsingPackageRead>(cacher) {
        val input = ThreadLocal.withInitial {
            // For testing, just disable enforcement to avoid hooking up to compat framework
            ParseTypeImpl(ParseInput.Callback { _, _, _ -> false })
        }
        val parser = ParsingPackageUtils(false, null, null, emptyList(),
            object : ParsingPackageUtils.Callback {
                override fun hasFeature(feature: String) = true

                override fun startParsingPackage(
                    packageName: String,
                    baseApkPath: String,
                    path: String,
                    manifestArray: TypedArray,
                    isCoreApp: Boolean
                ) = ParsingPackageImpl(packageName, baseApkPath, path, manifestArray)
            })

        override fun parseImpl(file: File) =
                parser.parsePackage(input.get()!!.reset(), file, 0).result
    }

    abstract class PackageCacher<PackageType : Parcelable>(private val cacheDir: File) {

        fun getCachedResult(file: File): PackageType? {
            val cacheFile = File(cacheDir, file.name)
            if (!cacheFile.exists()) {
                return null
            }

            val bytes = IoUtils.readFileAsByteArray(cacheFile.absolutePath)
            val parcel = Parcel.obtain().apply {
                unmarshall(bytes, 0, bytes.size)
                setDataPosition(0)
            }
            ReadHelper(parcel).apply { startAndInstall() }
            return fromParcel(parcel).also {
                parcel.recycle()
            }
        }

        fun cacheResult(file: File, parsed: Parcelable) {
            val cacheFile = File(cacheDir, file.name)
            if (cacheFile.exists()) {
                if (!cacheFile.delete()) {
                    throw IllegalStateException("Unable to delete cache file: $cacheFile")
                }
            }
            val cacheEntry = toCacheEntry(parsed)
            return FileOutputStream(cacheFile).use { fos -> fos.write(cacheEntry) }
        }

        private fun toCacheEntry(pkg: Parcelable): ByteArray {
            val parcel = Parcel.obtain()
            val helper = WriteHelper(parcel)
            pkg.writeToParcel(parcel, 0 /* flags */)
            helper.finishAndUninstall()
            return parcel.marshall().also {
                parcel.recycle()
            }
        }

        protected abstract fun fromParcel(parcel: Parcel): PackageType
    }

    /**
     * Re-implementation of v1's cache, since that's gone in R+.
     */
    class PackageCacher1(cacheDir: File) : PackageCacher<PackageParser.Package>(cacheDir) {
        override fun fromParcel(parcel: Parcel) = PackageParser.Package(parcel)
    }

    /**
     * Re-implementation of the server side PackageCacher, as it's inaccessible here.
     */
    class PackageCacher2(cacheDir: File) : PackageCacher<ParsingPackageRead>(cacheDir) {
        override fun fromParcel(parcel: Parcel) = ParsingPackageImpl(parcel)
    }
}
