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

package com.android.server.pm.parsing

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ConfigurationInfo
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageParser
import android.content.pm.PackageUserState
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.os.Debug
import android.os.Environment
import android.util.SparseArray
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.pm.PackageManagerService
import com.android.server.pm.PackageSetting
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateUnserialized
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import org.junit.BeforeClass
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import java.io.File

open class AndroidPackageParsingTestBase {

    companion object {

        private const val VERIFY_ALL_APKS = false

        /** For auditing memory usage differences */
        private const val DUMP_HPROF_TO_EXTERNAL = false

        val context: Context = InstrumentationRegistry.getInstrumentation().getContext()
        protected val packageParser = PackageParser().apply {
            setOnlyCoreApps(false)
            setDisplayMetrics(context.resources.displayMetrics)
            setCallback { false /* hasFeature */ }
        }

        protected val packageParser2 = PackageParser2.forParsingFileWithDefaults()

        /**
         * It would be difficult to mock all possibilities, so just use the APKs on device.
         * Unfortunately, this means the device must be bootable to verify potentially
         * boot-breaking behavior.
         */
        private val apks = mutableListOf(File(Environment.getRootDirectory(), "framework"))
                .apply {
                    @Suppress("ConstantConditionIf")
                    if (VERIFY_ALL_APKS) {
                        this += (PackageManagerService.SYSTEM_PARTITIONS)
                                .flatMap {
                                    listOfNotNull(it.privAppFolder, it.appFolder, it.overlayFolder)
                                }
                    }
                }
                .flatMap {
                    it.walkTopDown()
                            .filter { file -> file.name.endsWith(".apk") }
                            .toList()
                }

        private val dummyUserState = mock(PackageUserState::class.java).apply {
            installed = true
            Mockito.`when`(isAvailable(anyInt())).thenReturn(true)
        }

        lateinit var oldPackages: List<PackageParser.Package>

        lateinit var newPackages: List<AndroidPackage>

        @Suppress("ConstantConditionIf")
        @JvmStatic
        @BeforeClass
        fun setUpPackages() {
            this.oldPackages = apks.mapNotNull {
                try {
                    packageParser.parsePackage(it, PackageParser.PARSE_IS_SYSTEM_DIR, false)
                } catch (ignored: Exception) {
                    // Parsing issues will be caught by SystemPartitionParseTest
                    null
                }
            }

            this.newPackages = apks.mapNotNull {
                try {
                    packageParser2.parsePackage(it, PackageParser.PARSE_IS_SYSTEM_DIR, false)
                } catch (ignored: Exception) {
                    // Parsing issues will be caught by SystemPartitionParseTest
                    null
                }
            }

            if (DUMP_HPROF_TO_EXTERNAL) {
                System.gc()
                Environment.getExternalStorageDirectory()
                        .resolve(
                                "${AndroidPackageParsingTestBase::class.java.simpleName}.hprof")
                        .absolutePath
                        .run(Debug::dumpHprofData)
            }
        }

        fun oldAppInfo(pkg: PackageParser.Package, flags: Int = 0): ApplicationInfo? {
            return PackageParser.generateApplicationInfo(pkg, flags, dummyUserState, 0)
        }

        fun newAppInfo(pkg: AndroidPackage, flags: Int = 0): ApplicationInfo? {
            return PackageInfoUtils.generateApplicationInfo(pkg, flags, dummyUserState, 0,
                    mockPkgSetting(pkg))
        }

        fun oldPackageInfo(pkg: PackageParser.Package, flags: Int = 0): PackageInfo? {
            return PackageParser.generatePackageInfo(pkg, intArrayOf(), flags, 5, 6, emptySet(),
                    dummyUserState)
        }

        fun newPackageInfo(pkg: AndroidPackage, flags: Int = 0): PackageInfo? {
            return PackageInfoUtils.generate(pkg, intArrayOf(), flags, 5, 6, emptySet(),
                    dummyUserState, 0, mockPkgSetting(pkg))
        }

        private fun mockPkgSetting(aPkg: AndroidPackage) = mockThrowOnUnmocked<PackageSetting> {
            this.pkg = aPkg
            whenever(pkgState) { PackageStateUnserialized() }
        }
    }

    // The following methods dump an exact set of fields from the object to compare, because
    // 1. comprehensive equals/toStrings do not exist on all of the Info objects, and
    // 2. the test must only verify fields that [PackageParser.Package] can actually fill, as
    // no new functionality will be added to it.

    // The following methods prepend "this." because @hide APIs can cause an IDE to auto-import
    // the R.attr constant instead of referencing the field in an attempt to fix the error.

    /**
     * Known exclusions:
     *   - [ApplicationInfo.credentialProtectedDataDir]
     *   - [ApplicationInfo.dataDir]
     *   - [ApplicationInfo.deviceProtectedDataDir]
     *   - [ApplicationInfo.processName]
     *   - [ApplicationInfo.publicSourceDir]
     *   - [ApplicationInfo.scanPublicSourceDir]
     *   - [ApplicationInfo.scanSourceDir]
     *   - [ApplicationInfo.sourceDir]
     * These attributes used to be assigned post-package-parsing as part of another component,
     * but are now adjusted directly inside [PackageImpl].
     */
    protected fun ApplicationInfo.dumpToString() = """
            appComponentFactory=${this.appComponentFactory}
            backupAgentName=${this.backupAgentName}
            banner=${this.banner}
            category=${this.category}
            classLoaderName=${this.classLoaderName}
            className=${this.className}
            compatibleWidthLimitDp=${this.compatibleWidthLimitDp}
            compileSdkVersion=${this.compileSdkVersion}
            compileSdkVersionCodename=${this.compileSdkVersionCodename}
            descriptionRes=${this.descriptionRes}
            enabled=${this.enabled}
            enabledSetting=${this.enabledSetting}
            flags=${Integer.toBinaryString(this.flags)}
            fullBackupContent=${this.fullBackupContent}
            hiddenUntilInstalled=${this.hiddenUntilInstalled}
            icon=${this.icon}
            iconRes=${this.iconRes}
            installLocation=${this.installLocation}
            largestWidthLimitDp=${this.largestWidthLimitDp}
            logo=${this.logo}
            longVersionCode=${this.longVersionCode}
            manageSpaceActivityName=${this.manageSpaceActivityName}
            maxAspectRatio.compareTo(that.maxAspectRatio)=${this.maxAspectRatio}
            metaData=${this.metaData}
            minAspectRatio.compareTo(that.minAspectRatio)=${this.minAspectRatio}
            minSdkVersion=${this.minSdkVersion}
            name=${this.name}
            nativeLibraryDir=${this.nativeLibraryDir}
            nativeLibraryRootDir=${this.nativeLibraryRootDir}
            nativeLibraryRootRequiresIsa=${this.nativeLibraryRootRequiresIsa}
            networkSecurityConfigRes=${this.networkSecurityConfigRes}
            nonLocalizedLabel=${this.nonLocalizedLabel}
            packageName=${this.packageName}
            permission=${this.permission}
            primaryCpuAbi=${this.primaryCpuAbi}
            privateFlags=${Integer.toBinaryString(this.privateFlags)}
            requiresSmallestWidthDp=${this.requiresSmallestWidthDp}
            resourceDirs=${this.resourceDirs?.contentToString()}
            roundIconRes=${this.roundIconRes}
            secondaryCpuAbi=${this.secondaryCpuAbi}
            secondaryNativeLibraryDir=${this.secondaryNativeLibraryDir}
            seInfo=${this.seInfo}
            seInfoUser=${this.seInfoUser}
            sharedLibraryFiles=${this.sharedLibraryFiles?.contentToString()}
            sharedLibraryInfos=${this.sharedLibraryInfos}
            showUserIcon=${this.showUserIcon}
            splitClassLoaderNames=${this.splitClassLoaderNames?.contentToString()}
            splitDependencies=${this.splitDependencies}
            splitNames=${this.splitNames?.contentToString()}
            splitPublicSourceDirs=${this.splitPublicSourceDirs?.contentToString()}
            splitSourceDirs=${this.splitSourceDirs?.contentToString()}
            storageUuid=${this.storageUuid}
            targetSandboxVersion=${this.targetSandboxVersion}
            targetSdkVersion=${this.targetSdkVersion}
            taskAffinity=${this.taskAffinity}
            theme=${this.theme}
            uid=${this.uid}
            uiOptions=${this.uiOptions}
            versionCode=${this.versionCode}
            volumeUuid=${this.volumeUuid}
            zygotePreloadName=${this.zygotePreloadName}
            """.trimIndent()

    protected fun FeatureInfo.dumpToString() = """
            flags=${Integer.toBinaryString(this.flags)}
            name=${this.name}
            reqGlEsVersion=${this.reqGlEsVersion}
            version=${this.version}
            """.trimIndent()

    protected fun InstrumentationInfo.dumpToString() = """
            credentialProtectedDataDir=${this.credentialProtectedDataDir}
            dataDir=${this.dataDir}
            deviceProtectedDataDir=${this.deviceProtectedDataDir}
            functionalTest=${this.functionalTest}
            handleProfiling=${this.handleProfiling}
            nativeLibraryDir=${this.nativeLibraryDir}
            primaryCpuAbi=${this.primaryCpuAbi}
            publicSourceDir=${this.publicSourceDir}
            secondaryCpuAbi=${this.secondaryCpuAbi}
            secondaryNativeLibraryDir=${this.secondaryNativeLibraryDir}
            sourceDir=${this.sourceDir}
            splitDependencies=${this.splitDependencies.sequence()
            .map { it.first to it.second?.contentToString() }.joinToString()}
            splitNames=${this.splitNames?.contentToString()}
            splitPublicSourceDirs=${this.splitPublicSourceDirs?.contentToString()}
            splitSourceDirs=${this.splitSourceDirs?.contentToString()}
            targetPackage=${this.targetPackage}
            targetProcesses=${this.targetProcesses}
            """.trimIndent()

    protected fun ActivityInfo.dumpToString() = """
            colorMode=${this.colorMode}
            configChanges=${this.configChanges}
            documentLaunchMode=${this.documentLaunchMode}
            flags=${Integer.toBinaryString(this.flags)}
            launchMode=${this.launchMode}
            launchToken=${this.launchToken}
            lockTaskLaunchMode=${this.lockTaskLaunchMode}
            maxAspectRatio=${this.maxAspectRatio}
            maxRecents=${this.maxRecents}
            minAspectRatio=${this.minAspectRatio}
            parentActivityName=${this.parentActivityName}
            permission=${this.permission}
            persistableMode=${this.persistableMode}
            privateFlags=${Integer.toBinaryString(this.privateFlags)}
            requestedVrComponent=${this.requestedVrComponent}
            resizeMode=${this.resizeMode}
            rotationAnimation=${this.rotationAnimation}
            screenOrientation=${this.screenOrientation}
            softInputMode=${this.softInputMode}
            targetActivity=${this.targetActivity}
            taskAffinity=${this.taskAffinity}
            theme=${this.theme}
            uiOptions=${this.uiOptions}
            windowLayout=${this.windowLayout?.dumpToString()}
            """.trimIndent()

    protected fun ActivityInfo.WindowLayout.dumpToString() = """
            gravity=${this.gravity}
            height=${this.height}
            heightFraction=${this.heightFraction}
            minHeight=${this.minHeight}
            minWidth=${this.minWidth}
            width=${this.width}
            widthFraction=${this.widthFraction}
            """.trimIndent()

    protected fun PermissionInfo.dumpToString() = """
            backgroundPermission=${this.backgroundPermission}
            descriptionRes=${this.descriptionRes}
            flags=${Integer.toBinaryString(this.flags)}
            group=${this.group}
            nonLocalizedDescription=${this.nonLocalizedDescription}
            protectionLevel=${this.protectionLevel}
            requestRes=${this.requestRes}
            """.trimIndent()

    protected fun ProviderInfo.dumpToString() = """
            authority=${this.authority}
            flags=${Integer.toBinaryString(this.flags)}
            forceUriPermissions=${this.forceUriPermissions}
            grantUriPermissions=${this.grantUriPermissions}
            initOrder=${this.initOrder}
            isSyncable=${this.isSyncable}
            multiprocess=${this.multiprocess}
            pathPermissions=${this.pathPermissions?.joinToString {
        "readPermission=${it.readPermission}\nwritePermission=${it.writePermission}"
    }}
            readPermission=${this.readPermission}
            uriPermissionPatterns=${this.uriPermissionPatterns?.contentToString()}
            writePermission=${this.writePermission}
            """.trimIndent()

    protected fun ConfigurationInfo.dumpToString() = """
            reqGlEsVersion=${this.reqGlEsVersion}
            reqInputFeatures=${this.reqInputFeatures}
            reqKeyboardType=${this.reqKeyboardType}
            reqNavigation=${this.reqNavigation}
            reqTouchScreen=${this.reqTouchScreen}
            """.trimIndent()

    protected fun PackageInfo.dumpToString() = """
            activities=${this.activities?.joinToString { it.dumpToString() }}
            applicationInfo=${this.applicationInfo.dumpToString()}
            baseRevisionCode=${this.baseRevisionCode}
            compileSdkVersion=${this.compileSdkVersion}
            compileSdkVersionCodename=${this.compileSdkVersionCodename}
            configPreferences=${this.configPreferences?.joinToString { it.dumpToString() }}
            coreApp=${this.coreApp}
            featureGroups=${this.featureGroups?.joinToString {
        it.features?.joinToString { featureInfo -> featureInfo.dumpToString() }.orEmpty()
    }}
            firstInstallTime=${this.firstInstallTime}
            gids=${gids?.contentToString()}
            installLocation=${this.installLocation}
            instrumentation=${instrumentation?.joinToString { it.dumpToString() }}
            isApex=${this.isApex}
            isStub=${this.isStub}
            lastUpdateTime=${this.lastUpdateTime}
            mOverlayIsStatic=${this.mOverlayIsStatic}
            overlayCategory=${this.overlayCategory}
            overlayPriority=${this.overlayPriority}
            overlayTarget=${this.overlayTarget}
            packageName=${this.packageName}
            permissions=${this.permissions?.joinToString { it.dumpToString() }}
            providers=${this.providers?.joinToString { it.dumpToString() }}
            receivers=${this.receivers?.joinToString { it.dumpToString() }}
            reqFeatures=${this.reqFeatures?.joinToString { it.dumpToString() }}
            requestedPermissions=${this.requestedPermissions?.contentToString()}
            requestedPermissionsFlags=${this.requestedPermissionsFlags?.contentToString()}
            requiredAccountType=${this.requiredAccountType}
            requiredForAllUsers=${this.requiredForAllUsers}
            restrictedAccountType=${this.restrictedAccountType}
            services=${this.services?.contentToString()}
            sharedUserId=${this.sharedUserId}
            sharedUserLabel=${this.sharedUserLabel}
            signatures=${this.signatures?.joinToString { it.toCharsString() }}
            signingInfo=${this.signingInfo?.signingCertificateHistory
            ?.joinToString { it.toCharsString() }.orEmpty()}
            splitNames=${this.splitNames?.contentToString()}
            splitRevisionCodes=${this.splitRevisionCodes?.contentToString()}
            targetOverlayableName=${this.targetOverlayableName}
            versionCode=${this.versionCode}
            versionCodeMajor=${this.versionCodeMajor}
            versionName=${this.versionName}
            """.trimIndent()

    @Suppress("unused")
    private fun <T> SparseArray<T>.sequence(): Sequence<Pair<Int, T>> {
        var index = 0
        return generateSequence {
            index++.takeIf { it < size() }?.let { keyAt(it) to valueAt(index) }
        }
    }
}
