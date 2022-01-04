/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.os.Build
import android.os.storage.StorageManager
import android.util.ArrayMap
import android.util.PackageUtils
import com.android.server.SystemConfig.SharedLibraryEntry
import com.android.server.compat.PlatformCompat
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.parsing.pkg.PackageImpl
import com.android.server.pm.parsing.pkg.ParsedPackage
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.mock
import com.android.server.testutils.nullable
import com.android.server.testutils.spy
import com.android.server.testutils.whenever
import com.android.server.utils.WatchedLongSparseArray
import com.google.common.truth.Truth.assertThat
import libcore.util.HexEncoding
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class SharedLibrariesImplTest {

    companion object {
        const val TEST_LIB_NAME = "test.lib"
        const val TEST_LIB_PACKAGE_NAME = "com.android.lib.test"
        const val BUILTIN_LIB_NAME = "builtin.lib"
        const val STATIC_LIB_NAME = "static.lib"
        const val STATIC_LIB_VERSION = 7L
        const val STATIC_LIB_PACKAGE_NAME = "com.android.lib.static.provider"
        const val DYNAMIC_LIB_NAME = "dynamic.lib"
        const val DYNAMIC_LIB_PACKAGE_NAME = "com.android.lib.dynamic.provider"
        const val CONSUMER_PACKAGE_NAME = "com.android.lib.consumer"
        const val VERSION_UNDEFINED = SharedLibraryInfo.VERSION_UNDEFINED.toLong()
    }

    @Rule
    @JvmField
    val mRule = MockSystemRule()

    private val mExistingPackages: ArrayMap<String, AndroidPackage> = ArrayMap()
    private val mExistingSettings: MutableMap<String, PackageSetting> = mutableMapOf()

    private lateinit var mSharedLibrariesImpl: SharedLibrariesImpl
    private lateinit var mPms: PackageManagerService
    private lateinit var mSettings: Settings

    @Mock
    private lateinit var mDeletePackageHelper: DeletePackageHelper
    @Mock
    private lateinit var mStorageManager: StorageManager
    @Mock
    private lateinit var mFile: File
    @Mock
    private lateinit var mPlatformCompat: PlatformCompat

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mRule.system().stageNominalSystemState()
        addExistingPackages()

        val testParams = PackageManagerServiceTestParams().apply {
            packages = mExistingPackages
        }
        mPms = spy(PackageManagerService(mRule.mocks().injector, testParams))
        mSettings = mRule.mocks().injector.settings
        mSharedLibrariesImpl = SharedLibrariesImpl(mPms, mRule.mocks().injector)
        mSharedLibrariesImpl.setDeletePackageHelper(mDeletePackageHelper)
        addExistingSharedLibraries()

        whenever(mSettings.getPackageLPr(any())) { mExistingSettings[arguments[0]] }
        whenever(mRule.mocks().injector.getSystemService(StorageManager::class.java))
            .thenReturn(mStorageManager)
        whenever(mStorageManager.findPathForUuid(nullable())).thenReturn(mFile)
        doAnswer { it.arguments[0] }.`when`(mPms).resolveInternalPackageNameLPr(any(), any())
        whenever(mDeletePackageHelper.deletePackageX(any(), any(), any(), any(), any()))
            .thenReturn(PackageManager.DELETE_SUCCEEDED)
        whenever(mRule.mocks().injector.compatibility).thenReturn(mPlatformCompat)
        wheneverStatic { HexEncoding.decode(STATIC_LIB_NAME, false) }
                .thenReturn(PackageUtils.computeSha256DigestBytes(
                        mExistingSettings[STATIC_LIB_PACKAGE_NAME]!!
                                .pkg.signingDetails.signatures!![0].toByteArray()))
    }

    @Test
    fun snapshot_shouldSealed() {
        val builtinLibs = mSharedLibrariesImpl.snapshot().all[BUILTIN_LIB_NAME]
        assertThat(builtinLibs).isNotNull()

        assertFailsWith(IllegalStateException::class) {
            mSharedLibrariesImpl.snapshot().all[BUILTIN_LIB_NAME] = WatchedLongSparseArray()
        }
        assertFailsWith(IllegalStateException::class) {
            builtinLibs!!.put(VERSION_UNDEFINED, libOfBuiltin(BUILTIN_LIB_NAME))
        }
    }

    @Test
    fun addBuiltInSharedLibrary() {
        mSharedLibrariesImpl.addBuiltInSharedLibraryLPw(libEntry(TEST_LIB_NAME))

        assertThat(mSharedLibrariesImpl.getSharedLibraryInfos(TEST_LIB_NAME)).isNotNull()
        assertThat(mSharedLibrariesImpl.getSharedLibraryInfo(TEST_LIB_NAME, VERSION_UNDEFINED))
            .isNotNull()
    }

    @Test
    fun addBuiltInSharedLibrary_withDuplicateLibName() {
        val duplicate = libEntry(BUILTIN_LIB_NAME, "duplicate.path")
        mSharedLibrariesImpl.addBuiltInSharedLibraryLPw(duplicate)
        val sharedLibInfo = mSharedLibrariesImpl
            .getSharedLibraryInfo(BUILTIN_LIB_NAME, VERSION_UNDEFINED)

        assertThat(sharedLibInfo).isNotNull()
        assertThat(sharedLibInfo!!.path).isNotEqualTo(duplicate.filename)
    }

    @Test
    fun commitSharedLibraryInfo_withStaticSharedLib() {
        val testInfo = libOfStatic(TEST_LIB_PACKAGE_NAME, TEST_LIB_NAME, 1L)
        mSharedLibrariesImpl.commitSharedLibraryInfoLPw(testInfo)
        val sharedLibInfos = mSharedLibrariesImpl
            .getStaticLibraryInfos(testInfo.declaringPackage.packageName)

        assertThat(mSharedLibrariesImpl.getSharedLibraryInfos(TEST_LIB_NAME))
            .isNotNull()
        assertThat(mSharedLibrariesImpl.getSharedLibraryInfo(testInfo.name, testInfo.longVersion))
            .isNotNull()
        assertThat(sharedLibInfos).isNotNull()
        assertThat(sharedLibInfos.get(testInfo.longVersion)).isNotNull()
    }

    @Test
    fun removeSharedLibrary() {
        doAnswer { mutableListOf(VersionedPackage(CONSUMER_PACKAGE_NAME, 1L)) }.`when`(mPms)
            .getPackagesUsingSharedLibrary(any(), any(), any(), any())
        val staticInfo = mSharedLibrariesImpl
            .getSharedLibraryInfo(STATIC_LIB_NAME, STATIC_LIB_VERSION)!!

        mSharedLibrariesImpl.removeSharedLibraryLPw(STATIC_LIB_NAME, STATIC_LIB_VERSION)

        assertThat(mSharedLibrariesImpl.getSharedLibraryInfos(STATIC_LIB_NAME)).isNull()
        assertThat(mSharedLibrariesImpl
            .getStaticLibraryInfos(staticInfo.declaringPackage.packageName)).isNull()
        verify(mExistingSettings[CONSUMER_PACKAGE_NAME]!!)
            .setOverlayPathsForLibrary(any(), nullable(), any())
    }

    @Test
    fun pruneUnusedStaticSharedLibraries() {
        mSharedLibrariesImpl.pruneUnusedStaticSharedLibraries(Long.MAX_VALUE, 0)

        verify(mDeletePackageHelper)
            .deletePackageX(eq(STATIC_LIB_PACKAGE_NAME), any(), any(), any(), any())
    }

    @Test
    fun getLatestSharedLibraVersion() {
        val newLibSetting = addPackage(STATIC_LIB_PACKAGE_NAME + "_" + 10, 10L,
            staticLibrary = STATIC_LIB_NAME, staticLibraryVersion = 10L)

        val latestInfo = mSharedLibrariesImpl.getLatestSharedLibraVersionLPr(newLibSetting.pkg)!!

        assertThat(latestInfo).isNotNull()
        assertThat(latestInfo.name).isEqualTo(STATIC_LIB_NAME)
        assertThat(latestInfo.longVersion).isEqualTo(STATIC_LIB_VERSION)
    }

    @Test
    fun getStaticSharedLibLatestVersionSetting() {
        val pair = createBasicAndroidPackage(STATIC_LIB_PACKAGE_NAME + "_" + 10, 10L,
            staticLibrary = STATIC_LIB_NAME, staticLibraryVersion = 10L)
        val parsedPackage = pair.second as ParsedPackage
        val scanRequest = ScanRequest(parsedPackage, null, null, null,
            null, null, null, 0, 0, false, null, null)
        val scanResult = ScanResult(scanRequest, true, null, null, false, 0, null, null, null)

        val latestInfoSetting =
            mSharedLibrariesImpl.getStaticSharedLibLatestVersionSetting(scanResult)!!

        assertThat(latestInfoSetting).isNotNull()
        assertThat(latestInfoSetting.packageName).isEqualTo(STATIC_LIB_PACKAGE_NAME)
    }

    @Test
    fun updateSharedLibraries_withDynamicLibPackage() {
        val testPackageSetting = mExistingSettings[DYNAMIC_LIB_PACKAGE_NAME]!!
        assertThat(testPackageSetting.usesLibraryFiles).isEmpty()

        mSharedLibrariesImpl.updateSharedLibrariesLPw(testPackageSetting.pkg, testPackageSetting,
                null /* changingLib */, null /* changingLibSetting */, mExistingPackages)

        assertThat(testPackageSetting.usesLibraryFiles).hasSize(1)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
    }

    @Test
    fun updateSharedLibraries_withStaticLibPackage() {
        val testPackageSetting = mExistingSettings[STATIC_LIB_PACKAGE_NAME]!!
        assertThat(testPackageSetting.usesLibraryFiles).isEmpty()

        mSharedLibrariesImpl.updateSharedLibrariesLPw(testPackageSetting.pkg, testPackageSetting,
                null /* changingLib */, null /* changingLibSetting */, mExistingPackages)

        assertThat(testPackageSetting.usesLibraryFiles).hasSize(1)
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))
    }

    @Test
    fun updateSharedLibraries_withConsumerPackage() {
        val testPackageSetting = mExistingSettings[CONSUMER_PACKAGE_NAME]!!
        assertThat(testPackageSetting.usesLibraryFiles).isEmpty()

        mSharedLibrariesImpl.updateSharedLibrariesLPw(testPackageSetting.pkg, testPackageSetting,
                null /* changingLib */, null /* changingLibSetting */, mExistingPackages)

        assertThat(testPackageSetting.usesLibraryFiles).hasSize(2)
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(STATIC_LIB_PACKAGE_NAME))
    }

    @Test
    fun updateAllSharedLibraries() {
        mExistingSettings.forEach {
            assertThat(it.value.usesLibraryFiles).isEmpty()
        }

        mSharedLibrariesImpl.updateAllSharedLibrariesLPw(
                null /* updatedPkg */, null /* updatedPkgSetting */, mExistingPackages)

        var testPackageSetting = mExistingSettings[DYNAMIC_LIB_PACKAGE_NAME]!!
        assertThat(testPackageSetting.usesLibraryFiles).hasSize(1)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))

        testPackageSetting = mExistingSettings[STATIC_LIB_PACKAGE_NAME]!!
        assertThat(testPackageSetting.usesLibraryFiles).hasSize(2)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))

        testPackageSetting = mExistingSettings[CONSUMER_PACKAGE_NAME]!!
        assertThat(testPackageSetting.usesLibraryFiles).hasSize(3)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(STATIC_LIB_PACKAGE_NAME))
    }

    @Test
    fun getAllowedSharedLibInfos_withStaticSharedLibInfo() {
        val testInfo = libOfStatic(TEST_LIB_PACKAGE_NAME, TEST_LIB_NAME, 1L)
        val scanResult = ScanResult(mock(), true, null, null,
            false, 0, null, testInfo, null)

        val allowedInfos = mSharedLibrariesImpl.getAllowedSharedLibInfos(scanResult)

        assertThat(allowedInfos).hasSize(1)
        assertThat(allowedInfos[0].name).isEqualTo(TEST_LIB_NAME)
    }

    @Test
    fun getAllowedSharedLibInfos_withDynamicSharedLibInfo() {
        val testInfo = libOfDynamic(TEST_LIB_PACKAGE_NAME, TEST_LIB_NAME)
        val pair = createBasicAndroidPackage(
            TEST_LIB_PACKAGE_NAME, 10L, libraries = arrayOf(TEST_LIB_NAME))
        val parsedPackage = pair.second.apply {
            isSystem = true
        } as ParsedPackage
        val packageSetting = mRule.system()
            .createBasicSettingBuilder(pair.first.parentFile, parsedPackage.hideAsFinal())
            .setPkgFlags(ApplicationInfo.FLAG_SYSTEM).build()
        val scanRequest = ScanRequest(parsedPackage, null, null, null,
            null, null, null, 0, 0, false, null, null)
        val scanResult = ScanResult(scanRequest, true, packageSetting, null,
            false, 0, null, null, listOf(testInfo))

        val allowedInfos = mSharedLibrariesImpl.getAllowedSharedLibInfos(scanResult)

        assertThat(allowedInfos).hasSize(1)
        assertThat(allowedInfos[0].name).isEqualTo(TEST_LIB_NAME)
    }

    private fun addExistingPackages() {
        // add a dynamic shared library that is using the builtin library
        addPackage(DYNAMIC_LIB_PACKAGE_NAME, 1L,
            libraries = arrayOf(DYNAMIC_LIB_NAME),
            usesLibraries = arrayOf(BUILTIN_LIB_NAME))

        // add a static shared library v7 that is using the dynamic shared library
        addPackage(STATIC_LIB_PACKAGE_NAME, STATIC_LIB_VERSION,
            staticLibrary = STATIC_LIB_NAME, staticLibraryVersion = STATIC_LIB_VERSION,
            usesLibraries = arrayOf(DYNAMIC_LIB_NAME))

        // add a consumer package that is using the dynamic and static shared library
        addPackage(CONSUMER_PACKAGE_NAME, 1L,
            usesLibraries = arrayOf(DYNAMIC_LIB_NAME),
            usesStaticLibraries = arrayOf(STATIC_LIB_NAME),
            usesStaticLibraryVersions = arrayOf(STATIC_LIB_VERSION))
    }

    private fun addExistingSharedLibraries() {
        mSharedLibrariesImpl.addBuiltInSharedLibraryLPw(libEntry(BUILTIN_LIB_NAME))
        mSharedLibrariesImpl.commitSharedLibraryInfoLPw(
            libOfDynamic(DYNAMIC_LIB_PACKAGE_NAME, DYNAMIC_LIB_NAME))
        mSharedLibrariesImpl.commitSharedLibraryInfoLPw(
            libOfStatic(STATIC_LIB_PACKAGE_NAME, STATIC_LIB_NAME, STATIC_LIB_VERSION))
    }

    private fun addPackage(
        packageName: String,
        version: Long,
        libraries: Array<String>? = null,
        staticLibrary: String? = null,
        staticLibraryVersion: Long = 0L,
        usesLibraries: Array<String>? = null,
        usesStaticLibraries: Array<String>? = null,
        usesStaticLibraryVersions: Array<Long>? = null
    ): PackageSetting {
        val pair = createBasicAndroidPackage(packageName, version, libraries, staticLibrary,
            staticLibraryVersion, usesLibraries, usesStaticLibraries, usesStaticLibraryVersions)
        val apkPath = pair.first
        val parsingPackage = pair.second
        val spyPkg = spy((parsingPackage as ParsedPackage).hideAsFinal())
        mExistingPackages[packageName] = spyPkg

        val spyPackageSetting = spy(mRule.system()
            .createBasicSettingBuilder(apkPath.parentFile, spyPkg).build())
        mExistingSettings[spyPackageSetting.packageName] = spyPackageSetting

        return spyPackageSetting
    }

    private fun createBasicAndroidPackage(
        packageName: String,
        version: Long,
        libraries: Array<String>? = null,
        staticLibrary: String? = null,
        staticLibraryVersion: Long = 0L,
        usesLibraries: Array<String>? = null,
        usesStaticLibraries: Array<String>? = null,
        usesStaticLibraryVersions: Array<Long>? = null
    ): Pair<File, PackageImpl> {
        assertFalse { libraries != null && staticLibrary != null }
        assertTrue { (usesStaticLibraries?.size ?: -1) == (usesStaticLibraryVersions?.size ?: -1) }

        val pair = mRule.system()
            .createBasicAndroidPackage(mRule.system().dataAppDirectory, packageName, version)
        pair.second.apply {
            setTargetSdkVersion(Build.VERSION_CODES.S)
            libraries?.forEach { addLibraryName(it) }
            staticLibrary?.let {
                setStaticSharedLibName(it)
                setStaticSharedLibVersion(staticLibraryVersion)
                setStaticSharedLibrary(true)
            }
            usesLibraries?.forEach { addUsesLibrary(it) }
            usesStaticLibraries?.forEachIndexed { index, s ->
                addUsesStaticLibrary(s,
                    usesStaticLibraryVersions?.get(index) ?: 0L,
                        arrayOf(s))
            }
        }
        return pair
    }

    private fun libEntry(libName: String, path: String? = null): SharedLibraryEntry =
        SharedLibraryEntry(libName, path ?: builtinLibPath(libName),
            arrayOfNulls(0), false /* isNative */)

    private fun libOfBuiltin(libName: String): SharedLibraryInfo =
        SharedLibraryInfo(builtinLibPath(libName),
            null /* packageName */,
            null /* codePaths */,
            libName,
            VERSION_UNDEFINED,
            SharedLibraryInfo.TYPE_BUILTIN,
            VersionedPackage(PLATFORM_PACKAGE_NAME, 0L /* versionCode */),
            null /* dependentPackages */,
            null /* dependencies */,
            false /* isNative */)

    private fun libOfStatic(
        packageName: String,
        libName: String,
        version: Long
    ): SharedLibraryInfo =
        SharedLibraryInfo(null /* path */,
            packageName,
            listOf(apkPath(packageName)),
            libName,
            version,
            SharedLibraryInfo.TYPE_STATIC,
            VersionedPackage(packageName, version /* versionCode */),
            null /* dependentPackages */,
            null /* dependencies */,
            false /* isNative */)

    private fun libOfDynamic(packageName: String, libName: String): SharedLibraryInfo =
        SharedLibraryInfo(null /* path */,
            packageName,
            listOf(apkPath(packageName)),
            libName,
            VERSION_UNDEFINED,
            SharedLibraryInfo.TYPE_DYNAMIC,
            VersionedPackage(packageName, 1L /* versionCode */),
            null /* dependentPackages */,
            null /* dependencies */,
            false /* isNative */)

    private fun builtinLibPath(libName: String): String = "/system/app/$libName/$libName.jar"

    private fun apkPath(packageName: String): String =
            File(mRule.system().dataAppDirectory, packageName).path
}
