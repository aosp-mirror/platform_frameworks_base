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
import android.os.UserHandle
import android.os.storage.StorageManager
import android.util.ArrayMap
import android.util.PackageUtils
import com.android.internal.pm.parsing.pkg.PackageImpl
import com.android.internal.pm.parsing.pkg.ParsedPackage
import com.android.server.SystemConfig.SharedLibraryEntry
import com.android.server.compat.PlatformCompat
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.mock
import com.android.server.testutils.nullable
import com.android.server.testutils.spy
import com.android.server.testutils.whenever
import com.android.server.utils.WatchedLongSparseArray
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import libcore.util.HexEncoding
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
class SharedLibrariesImplTest {

    companion object {
        const val TEST_LIB_NAME = "test.lib"
        const val TEST_LIB_PACKAGE_NAME = "com.android.lib.test"
        const val BUILTIN_LIB_NAME = "builtin.lib"
        const val STATIC_LIB_NAME = "static.lib"
        const val STATIC_LIB_VERSION = 7L
        const val STATIC_LIB_DECLARING_PACKAGE_NAME = "com.android.lib.static.provider"
        const val STATIC_LIB_PACKAGE_NAME = "com.android.lib.static.provider_7"
        const val DYNAMIC_LIB_NAME = "dynamic.lib"
        const val DYNAMIC_LIB_PACKAGE_NAME = "com.android.lib.dynamic.provider"
        const val CONSUMER_PACKAGE_NAME = "com.android.lib.consumer"
        const val VERSION_UNDEFINED = SharedLibraryInfo.VERSION_UNDEFINED.toLong()
    }

    private val mMockSystem = MockSystemRule()
    private val mTempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val mRules = RuleChain.outerRule(mTempFolder).around(mMockSystem)!!

    private lateinit var mSharedLibrariesImpl: SharedLibrariesImpl
    private lateinit var mPms: PackageManagerService
    private lateinit var mSettings: Settings
    private lateinit var mComputer: Computer
    private lateinit var mExistingPackages: ArrayMap<String, AndroidPackage>
    private lateinit var builtinLibDirectory: File

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
        mMockSystem.system().stageNominalSystemState()
        stageBuiltinLibrary(mTempFolder.newFolder())
        stageScanExistingPackages()

        mPms = spy(PackageManagerService(mMockSystem.mocks().injector,
            false /*factoryTest*/,
            MockSystem.DEFAULT_VERSION_INFO.fingerprint,
            false /*isEngBuild*/,
            false /*isUserDebugBuild*/,
            Build.VERSION_CODES.CUR_DEVELOPMENT,
            Build.VERSION.INCREMENTAL))
        mMockSystem.system().validateFinalState()
        mSettings = mMockSystem.mocks().injector.settings
        mSharedLibrariesImpl = mMockSystem.mocks().injector.sharedLibrariesImpl
        mSharedLibrariesImpl.setDeletePackageHelper(mDeletePackageHelper)
        mComputer = spy(mPms.snapshotComputer())
        mExistingPackages = getExistingPackages(
            DYNAMIC_LIB_PACKAGE_NAME, STATIC_LIB_PACKAGE_NAME, CONSUMER_PACKAGE_NAME)

        whenever(mMockSystem.mocks().injector.getSystemService(StorageManager::class.java))
            .thenReturn(mStorageManager)
        whenever(mStorageManager.findPathForUuid(nullable())).thenReturn(mFile)
        doAnswer { mComputer }.`when`(mPms).snapshotComputer()
        doAnswer { STATIC_LIB_PACKAGE_NAME }.`when`(mComputer).resolveInternalPackageName(
            eq(STATIC_LIB_DECLARING_PACKAGE_NAME), eq(STATIC_LIB_VERSION))
        whenever(mDeletePackageHelper.deletePackageX(any(), any(), any(), any(), any()))
            .thenReturn(PackageManager.DELETE_SUCCEEDED)
        whenever(mMockSystem.mocks().injector.compatibility).thenReturn(mPlatformCompat)
        wheneverStatic { HexEncoding.decode(STATIC_LIB_NAME, false) }
                .thenReturn(PackageUtils.computeSha256DigestBytes(
                        mSettings.getPackageLPr(STATIC_LIB_PACKAGE_NAME)
                            .pkg!!.signingDetails.signatures!![0].toByteArray()))
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
        val staticInfo = mSharedLibrariesImpl
            .getSharedLibraryInfo(STATIC_LIB_NAME, STATIC_LIB_VERSION)!!

        mSharedLibrariesImpl.removeSharedLibrary(STATIC_LIB_NAME, STATIC_LIB_VERSION)

        assertThat(mSharedLibrariesImpl.getSharedLibraryInfos(STATIC_LIB_NAME)).isNull()
        assertThat(mSharedLibrariesImpl
            .getStaticLibraryInfos(staticInfo.declaringPackage.packageName)).isNull()
    }

    @Test
    fun pruneUnusedStaticSharedLibraries() {
        mSharedLibrariesImpl.pruneUnusedStaticSharedLibraries(mPms.snapshotComputer(),
            Long.MAX_VALUE, 0)

        verify(mDeletePackageHelper)
            .deletePackageX(eq(STATIC_LIB_PACKAGE_NAME), any(), any(), any(), any())
    }

    @Test
    fun getLatestSharedLibraVersion() {
        val pair = createBasicAndroidPackage(STATIC_LIB_PACKAGE_NAME + "_" + 10, 10L,
            staticLibrary = STATIC_LIB_NAME, staticLibraryVersion = 10L)

        val latestInfo =
            mSharedLibrariesImpl.getLatestStaticSharedLibraVersion(pair.second)!!

        assertThat(latestInfo).isNotNull()
        assertThat(latestInfo.name).isEqualTo(STATIC_LIB_NAME)
        assertThat(latestInfo.longVersion).isEqualTo(STATIC_LIB_VERSION)
    }

    @Test
    fun getStaticSharedLibLatestVersionSetting() {
        val pair = createBasicAndroidPackage(STATIC_LIB_PACKAGE_NAME + "_" + 10, 10L,
            staticLibrary = STATIC_LIB_NAME, staticLibraryVersion = 10L)
        val parsedPackage = pair.second as ParsedPackage
        val scanRequest = ScanRequest(parsedPackage, null, null, null, null,
            null, null, null, 0, 0, false, null, null)
        val scanResult = ScanResult(scanRequest, null, null, false, 0, null, null, null)
        var installRequest = InstallRequest(parsedPackage, 0, 0, UserHandle(0), scanResult, null)

        val latestInfoSetting =
            mSharedLibrariesImpl.getStaticSharedLibLatestVersionSetting(installRequest)!!

        assertThat(latestInfoSetting).isNotNull()
        assertThat(latestInfoSetting.packageName).isEqualTo(STATIC_LIB_PACKAGE_NAME)
    }

    @Test
    fun updateSharedLibraries_withDynamicLibPackage() {
        val testPackageSetting = mSettings.getPackageLPr(DYNAMIC_LIB_PACKAGE_NAME)
        testPackageSetting.setPkgStateLibraryFiles(listOf())
        assertThat(testPackageSetting.usesLibraryFiles).isEmpty()

        mSharedLibrariesImpl.updateSharedLibraries(testPackageSetting.pkg!!, testPackageSetting,
                null /* changingLib */, null /* changingLibSetting */, mExistingPackages)

        assertThat(testPackageSetting.usesLibraryFiles).hasSize(1)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
    }

    @Test
    fun updateSharedLibraries_withStaticLibPackage() {
        val testPackageSetting = mSettings.getPackageLPr(STATIC_LIB_PACKAGE_NAME)
        testPackageSetting.setPkgStateLibraryFiles(listOf())
        assertThat(testPackageSetting.usesLibraryFiles).isEmpty()

        mSharedLibrariesImpl.updateSharedLibraries(testPackageSetting.pkg!!, testPackageSetting,
                null /* changingLib */, null /* changingLibSetting */, mExistingPackages)

        assertThat(testPackageSetting.usesLibraryFiles).hasSize(2)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))
    }

    @Test
    fun updateSharedLibraries_withConsumerPackage() {
        val testPackageSetting = mSettings.getPackageLPr(CONSUMER_PACKAGE_NAME)
        testPackageSetting.setPkgStateLibraryFiles(listOf())
        assertThat(testPackageSetting.usesLibraryFiles).isEmpty()

        mSharedLibrariesImpl.updateSharedLibraries(testPackageSetting.pkg!!, testPackageSetting,
                null /* changingLib */, null /* changingLibSetting */, mExistingPackages)

        assertThat(testPackageSetting.usesLibraryFiles).hasSize(3)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))
        assertThat(testPackageSetting.usesLibraryFiles)
            .contains(apkPath(STATIC_LIB_DECLARING_PACKAGE_NAME))
    }

    @Test
    fun updateAllSharedLibraries() {
        mExistingPackages.forEach {
            val setting = mSettings.getPackageLPr(it.key)
            setting.setPkgStateLibraryFiles(listOf())
            assertThat(setting.usesLibraryFiles).isEmpty()
        }

        mSharedLibrariesImpl.updateAllSharedLibrariesLPw(
                null /* updatedPkg */, null /* updatedPkgSetting */, mExistingPackages)

        var testPackageSetting = mSettings.getPackageLPr(DYNAMIC_LIB_PACKAGE_NAME)
        assertThat(testPackageSetting.usesLibraryFiles).hasSize(1)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))

        testPackageSetting = mSettings.getPackageLPr(STATIC_LIB_PACKAGE_NAME)
        assertThat(testPackageSetting.usesLibraryFiles).hasSize(2)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))

        testPackageSetting = mSettings.getPackageLPr(CONSUMER_PACKAGE_NAME)
        assertThat(testPackageSetting.usesLibraryFiles).hasSize(3)
        assertThat(testPackageSetting.usesLibraryFiles).contains(builtinLibPath(BUILTIN_LIB_NAME))
        assertThat(testPackageSetting.usesLibraryFiles).contains(apkPath(DYNAMIC_LIB_PACKAGE_NAME))
        assertThat(testPackageSetting.usesLibraryFiles)
            .contains(apkPath(STATIC_LIB_DECLARING_PACKAGE_NAME))
    }

    @Test
    fun getAllowedSharedLibInfos_withStaticSharedLibInfo() {
        val testInfo = libOfStatic(TEST_LIB_PACKAGE_NAME, TEST_LIB_NAME, 1L)
        val scanResult = ScanResult(mock(), null, null,
            false, 0, null, testInfo, null)
        var installRequest = InstallRequest(mock(), 0, 0, UserHandle(0), scanResult, null)

        val allowedInfos = mSharedLibrariesImpl.getAllowedSharedLibInfos(installRequest)

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
        val packageSetting = mMockSystem.system()
            .createBasicSettingBuilder(pair.first.parentFile, parsedPackage.hideAsFinal())
            .setPkgFlags(ApplicationInfo.FLAG_SYSTEM).build()
        val scanRequest = ScanRequest(parsedPackage, null, null, null, null,
            null, null, null, 0, 0, false, null, null)
        val scanResult = ScanResult(scanRequest, packageSetting, null,
            false, 0, null, null, listOf(testInfo))
        var installRequest = InstallRequest(parsedPackage, 0, 0, UserHandle(0), scanResult, null)

        val allowedInfos = mSharedLibrariesImpl.getAllowedSharedLibInfos(installRequest)

        assertThat(allowedInfos).hasSize(1)
        assertThat(allowedInfos[0].name).isEqualTo(TEST_LIB_NAME)
    }

    private fun getExistingPackages(vararg args: String): ArrayMap<String, AndroidPackage> {
        val existingPackages = ArrayMap<String, AndroidPackage>()
        args.forEach {
            existingPackages[it] = mSettings.getPackageLPr(it).pkg
        }
        return existingPackages
    }

    private fun stageBuiltinLibrary(folder: File) {
        builtinLibDirectory = folder
        val libPath = File(builtinLibPath(BUILTIN_LIB_NAME))
        libPath.createNewFile()
        MockSystem.addDefaultSharedLibrary(BUILTIN_LIB_NAME, libEntry(BUILTIN_LIB_NAME))
    }

    private fun stageScanExistingPackages() {
        // add a dynamic shared library that is using the builtin library
        stageScanExistingPackage(DYNAMIC_LIB_PACKAGE_NAME, 1L,
            libraries = arrayOf(DYNAMIC_LIB_NAME),
            usesLibraries = arrayOf(BUILTIN_LIB_NAME))

        // add a static shared library v7 that is using the dynamic shared library
        stageScanExistingPackage(STATIC_LIB_DECLARING_PACKAGE_NAME, STATIC_LIB_VERSION,
            staticLibrary = STATIC_LIB_NAME, staticLibraryVersion = STATIC_LIB_VERSION,
            usesLibraries = arrayOf(DYNAMIC_LIB_NAME))

        // add a consumer package that is using the dynamic and static shared library
        stageScanExistingPackage(CONSUMER_PACKAGE_NAME, 1L,
            isSystem = true,
            usesLibraries = arrayOf(DYNAMIC_LIB_NAME),
            usesStaticLibraries = arrayOf(STATIC_LIB_NAME),
            usesStaticLibraryVersions = arrayOf(STATIC_LIB_VERSION))
    }

    private fun stageScanExistingPackage(
        packageName: String,
        version: Long,
        isSystem: Boolean = false,
        libraries: Array<String>? = null,
        staticLibrary: String? = null,
        staticLibraryVersion: Long = 0L,
        usesLibraries: Array<String>? = null,
        usesStaticLibraries: Array<String>? = null,
        usesStaticLibraryVersions: Array<Long>? = null
    ) {
        val withPackage = { pkg: PackageImpl ->
            pkg.setSystem(isSystem || libraries != null)
            pkg.setTargetSdkVersion(Build.VERSION_CODES.S)
            libraries?.forEach { pkg.addLibraryName(it) }
            staticLibrary?.let {
                pkg.setStaticSharedLibraryName(it)
                pkg.setStaticSharedLibraryVersion(staticLibraryVersion)
                pkg.setStaticSharedLibrary(true)
            }
            usesLibraries?.forEach { pkg.addUsesLibrary(it) }
            usesStaticLibraries?.forEachIndexed { index, s ->
                pkg.addUsesStaticLibrary(s,
                    usesStaticLibraryVersions?.get(index) ?: 0L,
                    arrayOf(s))
            }
            pkg
        }
        val withSetting = { settingBuilder: PackageSettingBuilder ->
            if (staticLibrary != null) {
                settingBuilder.setName(getStaticSharedLibInternalPackageName(packageName, version))
            }
            if (isSystem || libraries != null) {
                settingBuilder.setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
            }
            settingBuilder
        }
        mMockSystem.system().stageScanExistingPackage(
            packageName, version, mMockSystem.system().dataAppDirectory, withPackage, withSetting)
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

        val pair = mMockSystem.system()
            .createBasicAndroidPackage(mMockSystem.system().dataAppDirectory, packageName, version)
        pair.second.apply {
            setTargetSdkVersion(Build.VERSION_CODES.S)
            libraries?.forEach { addLibraryName(it) }
            staticLibrary?.let {
                setStaticSharedLibraryName(it)
                setStaticSharedLibraryVersion(staticLibraryVersion)
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
        declaringPackageName: String,
        libName: String,
        version: Long
    ): SharedLibraryInfo =
        SharedLibraryInfo(null /* path */,
            getStaticSharedLibInternalPackageName(declaringPackageName, version),
            listOf(apkPath(declaringPackageName)),
            libName,
            version,
            SharedLibraryInfo.TYPE_STATIC,
            VersionedPackage(declaringPackageName, version /* versionCode */),
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

    private fun builtinLibPath(libName: String) =
        File(builtinLibDirectory, "$libName.jar").path

    private fun apkPath(packageName: String) =
        File(mMockSystem.system().dataAppDirectory, packageName).path

    private fun getStaticSharedLibInternalPackageName(
        declaringPackageName: String,
        version: Long
    ) = "${declaringPackageName}_$version"
}
