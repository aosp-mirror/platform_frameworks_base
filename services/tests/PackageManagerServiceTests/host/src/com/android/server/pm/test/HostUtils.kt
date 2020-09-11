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

package com.android.server.pm.test

import com.android.internal.util.test.SystemPreparer
import com.android.tradefed.device.ITestDevice
import com.google.common.truth.Truth
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

internal fun SystemPreparer.pushApk(javaResourceName: String, partition: Partition) =
        pushResourceFile(javaResourceName, HostUtils.makePathForApk(javaResourceName, partition)
                .toString())

internal fun SystemPreparer.deleteApkFolders(
    partition: Partition,
    vararg javaResourceNames: String
) = apply {
    javaResourceNames.forEach {
        deleteFile(partition.baseAppFolder.resolve(it.removeSuffix(".apk")).toString())
    }
}

internal fun ITestDevice.installJavaResourceApk(
    tempFolder: TemporaryFolder,
    javaResource: String,
    reinstall: Boolean = true,
    extraArgs: Array<String> = emptyArray()
): String? {
    val file = HostUtils.copyResourceToHostFile(javaResource, tempFolder.newFile())
    return installPackage(file, reinstall, *extraArgs)
}

internal fun ITestDevice.uninstallPackages(vararg pkgNames: String) =
        pkgNames.forEach { uninstallPackage(it) }

/**
 * Retry [block] a total of [maxAttempts] times, waiting [millisBetweenAttempts] milliseconds
 * between each iteration, until a non-null result is returned, providing that result back to the
 * caller.
 *
 * If an [AssertionError] is thrown by the [block] and a non-null result is never returned, that
 * error will be re-thrown. This allows the use of [Truth.assertThat] to indicate success while
 * providing a meaningful error message in case of failure.
 */
internal fun <T> retryUntilNonNull(
    maxAttempts: Int = 10,
    millisBetweenAttempts: Long = 1000,
    block: () -> T?
): T {
    var attempt = 0
    var failure: AssertionError? = null
    while (attempt++ < maxAttempts) {
        val result = try {
            block()
        } catch (e: AssertionError) {
            failure = e
            null
        }

        if (result != null) {
            return result
        } else {
            Thread.sleep(millisBetweenAttempts)
        }
    }

    throw failure ?: AssertionError("Never succeeded")
}

internal fun retryUntilSuccess(block: () -> Boolean) {
    retryUntilNonNull { block().takeIf { it } }
}

internal object HostUtils {

    fun getDataDir(device: ITestDevice, pkgName: String) =
            device.executeShellCommand("dumpsys package $pkgName")
                    .lineSequence()
                    .map(String::trim)
                    .single { it.startsWith("dataDir=") }
                    .removePrefix("dataDir=")

    fun makePathForApk(fileName: String, partition: Partition) =
            makePathForApk(File(fileName), partition)

    fun makePathForApk(file: File, partition: Partition) =
            partition.baseAppFolder
                    .resolve(file.nameWithoutExtension)
                    .resolve(file.name)

    fun copyResourceToHostFile(javaResourceName: String, file: File): File {
        javaClass.classLoader!!.getResource(javaResourceName).openStream().use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    /**
     * dumpsys package and therefore device.getAppPackageInfo doesn't work immediately after reboot,
     * so the following methods parse the package dump directly to see if the path matches.
     */

    /**
     * Reads the pm dump for a package name starting from the Packages: metadata section until
     * the following section.
     */
    fun packageSection(
        device: ITestDevice,
        pkgName: String,
        sectionName: String = "Packages"
    ) = device.executeShellCommand("pm dump $pkgName")
            .lineSequence()
            .dropWhile { !it.startsWith(sectionName) } // Wait until the header
            .drop(1) // Drop the header itself
            .takeWhile {
                // Until next top level header, a non-empty line that doesn't start with whitespace
                it.isEmpty() || it.first().isWhitespace()
            }
            .map(String::trim)

    fun getCodePaths(device: ITestDevice, pkgName: String) =
            device.executeShellCommand("pm dump $pkgName")
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith("codePath=") }
                    .map { it.removePrefix("codePath=") }
                    .toList()

    private fun userIdLineSequence(device: ITestDevice, pkgName: String) =
            packageSection(device, pkgName)
                    .filter { it.startsWith("User ") }

    fun getUserIdToPkgEnabledState(device: ITestDevice, pkgName: String) =
            userIdLineSequence(device, pkgName).associate {
                val userId = it.removePrefix("User ")
                        .takeWhile(Char::isDigit)
                        .toInt()
                val enabled = it.substringAfter("enabled=")
                        .takeWhile(Char::isDigit)
                        .toInt()
                        .let {
                            when (it) {
                                0, 1 -> true
                                else -> false
                            }
                        }
                userId to enabled
            }

    fun getUserIdToPkgInstalledState(device: ITestDevice, pkgName: String) =
            userIdLineSequence(device, pkgName).associate {
                val userId = it.removePrefix("User ")
                        .takeWhile(Char::isDigit)
                        .toInt()
                val installed = it.substringAfter("installed=")
                        .takeWhile { !it.isWhitespace() }
                        .toBoolean()
                userId to installed
            }
}
