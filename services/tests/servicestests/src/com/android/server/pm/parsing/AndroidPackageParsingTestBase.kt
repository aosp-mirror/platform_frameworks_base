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
import android.content.pm.ComponentInfo
import android.content.pm.ConfigurationInfo
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageParser
import android.content.pm.PackageUserState
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.pm.parsing.ParsingPackageUtils
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.util.SparseArray
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.pm.PackageManagerService
import com.android.server.pm.PackageSetting
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateUnserialized
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import org.junit.BeforeClass
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import java.io.File

open class AndroidPackageParsingTestBase {

    companion object {

        private const val VERIFY_ALL_APKS = true

        // For auditing memory usage differences to /sdcard/AndroidPackageParsingTestBase.hprof
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
                .distinct()

        private val dummyUserState = mock(PackageUserState::class.java).apply {
            installed = true
            whenever(isAvailable(anyInt())) { true }
            whenever(isMatch(any<ComponentInfo>(), anyInt())) { true }
            whenever(isMatch(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                    anyString(), anyInt())) { true }
        }

        val oldPackages = mutableListOf<PackageParser.Package>()

        val newPackages = mutableListOf<AndroidPackage>()

        @Suppress("ConstantConditionIf")
        @JvmStatic
        @BeforeClass
        fun setUpPackages() {
            var uid = Process.FIRST_APPLICATION_UID
            apks.mapNotNull {
                try {
                    packageParser.parsePackage(it, PackageParser.PARSE_IS_SYSTEM_DIR, false) to
                            packageParser2.parsePackage(it, ParsingPackageUtils.PARSE_IS_SYSTEM_DIR,
                                    false)
                } catch (ignored: Exception) {
                    // It is intentional that a failure of either call here will result in failing
                    // both. Having null on one side would mean nothing to compare. Due to the
                    // nature of presubmit, this may not be caused by the change being tested, so
                    // it's unhelpful to consider it a failure. Actual parsing issues will be
                    // reported by SystemPartitionParseTest in postsubmit.
                    null
                }
            }.forEach { (old, new) ->
                // Assign an arbitrary UID. This is normally done after parsing completes, inside
                // PackageManagerService, but since that code isn't run here, need to mock it. This
                // is equivalent to what the system would assign.
                old.applicationInfo.uid = uid
                new.uid = uid
                uid++

                oldPackages += old
                newPackages += new.hideAsFinal()
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

        fun oldAppInfo(
            pkg: PackageParser.Package,
            flags: Int = 0,
            userId: Int = 0
        ): ApplicationInfo? {
            return PackageParser.generateApplicationInfo(pkg, flags, dummyUserState, userId)
        }

        fun newAppInfo(
            pkg: AndroidPackage,
            flags: Int = 0,
            userId: Int = 0
        ): ApplicationInfo? {
            return PackageInfoUtils.generateApplicationInfo(pkg, flags, dummyUserState, userId,
                    mockPkgSetting(pkg))
        }

        fun newAppInfoWithoutState(
            pkg: AndroidPackage,
            flags: Int = 0,
            userId: Int = 0
        ): ApplicationInfo? {
            return PackageInfoUtils.generateApplicationInfo(pkg, flags, dummyUserState, userId,
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
            this.appId = aPkg.uid
            whenever(pkgState) { PackageStateUnserialized() }
            whenever(readUserState(anyInt())) { dummyUserState }
            whenever(categoryOverride) { ApplicationInfo.CATEGORY_UNDEFINED }
            whenever(primaryCpuAbi) { null }
            whenever(secondaryCpuAbi) { null }
        }
    }

    // The following methods dump an exact set of fields from the object to compare, because
    // 1. comprehensive equals/toStrings do not exist on all of the Info objects, and
    // 2. the test must only verify fields that [PackageParser.Package] can actually fill, as
    // no new functionality will be added to it.

    // The following methods prepend "this." because @hide APIs can cause an IDE to auto-import
    // the R.attr constant instead of referencing the field in an attempt to fix the error.

    // It's difficult to comment out a line in a triple quoted string, so this is used instead
    // to ignore specific fields. A comment is required to explain why a field was ignored.
    private fun Any?.ignored(comment: String): String = "IGNORED"

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
            credentialProtectedDataDir=${this.credentialProtectedDataDir
            .ignored("Deferred pre-R, but assigned immediately in R")}
            crossProfile=${this.crossProfile.ignored("Added in R")}
            dataDir=${this.dataDir.ignored("Deferred pre-R, but assigned immediately in R")}
            descriptionRes=${this.descriptionRes}
            deviceProtectedDataDir=${this.deviceProtectedDataDir
            .ignored("Deferred pre-R, but assigned immediately in R")}
            enabled=${this.enabled}
            enabledSetting=${this.enabledSetting}
            flags=${Integer.toBinaryString(this.flags)}
            fullBackupContent=${this.fullBackupContent}
            gwpAsanMode=${this.gwpAsanMode.ignored("Added in R")}
            hiddenUntilInstalled=${this.hiddenUntilInstalled}
            icon=${this.icon}
            iconRes=${this.iconRes}
            installLocation=${this.installLocation}
            labelRes=${this.labelRes}
            largestWidthLimitDp=${this.largestWidthLimitDp}
            logo=${this.logo}
            longVersionCode=${this.longVersionCode}
            ${"".ignored("mHiddenApiPolicy is a private field")}
            manageSpaceActivityName=${this.manageSpaceActivityName}
            maxAspectRatio=${this.maxAspectRatio}
            metaData=${this.metaData.dumpToString()}
            minAspectRatio=${this.minAspectRatio}
            minSdkVersion=${this.minSdkVersion}
            name=${this.name}
            nativeLibraryDir=${this.nativeLibraryDir}
            nativeLibraryRootDir=${this.nativeLibraryRootDir}
            nativeLibraryRootRequiresIsa=${this.nativeLibraryRootRequiresIsa}
            networkSecurityConfigRes=${this.networkSecurityConfigRes}
            nonLocalizedLabel=${
                // Per b/184574333, v1 mistakenly trimmed the label. v2 fixed this, but for test
                // comparison, trim both so they can be matched.
                this.nonLocalizedLabel?.trim()
            }
            packageName=${this.packageName}
            permission=${this.permission}
            primaryCpuAbi=${this.primaryCpuAbi}
            privateFlags=${Integer.toBinaryString(this.privateFlags)}
            processName=${this.processName.ignored("Deferred pre-R, but assigned immediately in R")}
            publicSourceDir=${this.publicSourceDir
            .ignored("Deferred pre-R, but assigned immediately in R")}
            requiresSmallestWidthDp=${this.requiresSmallestWidthDp}
            resourceDirs=${this.resourceDirs?.contentToString()}
            overlayPaths=${this.overlayPaths?.contentToString()}
            roundIconRes=${this.roundIconRes}
            scanPublicSourceDir=${this.scanPublicSourceDir
            .ignored("Deferred pre-R, but assigned immediately in R")}
            scanSourceDir=${this.scanSourceDir
            .ignored("Deferred pre-R, but assigned immediately in R")}
            seInfo=${this.seInfo}
            seInfoUser=${this.seInfoUser}
            secondaryCpuAbi=${this.secondaryCpuAbi}
            secondaryNativeLibraryDir=${this.secondaryNativeLibraryDir}
            sharedLibraryFiles=${this.sharedLibraryFiles?.contentToString()}
            sharedLibraryInfos=${this.sharedLibraryInfos}
            showUserIcon=${this.showUserIcon}
            sourceDir=${this.sourceDir
            .ignored("Deferred pre-R, but assigned immediately in R")}
            splitClassLoaderNames=${this.splitClassLoaderNames?.contentToString()}
            splitDependencies=${this.splitDependencies.dumpToString()}
            splitNames=${this.splitNames?.contentToString()}
            splitPublicSourceDirs=${this.splitPublicSourceDirs?.contentToString()}
            splitSourceDirs=${this.splitSourceDirs?.contentToString()}
            storageUuid=${this.storageUuid}
            targetSandboxVersion=${this.targetSandboxVersion}
            targetSdkVersion=${this.targetSdkVersion}
            taskAffinity=${this.taskAffinity}
            theme=${this.theme}
            uiOptions=${this.uiOptions}
            uid=${this.uid}
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
            banner=${this.banner}
            credentialProtectedDataDir=${this.credentialProtectedDataDir}
            dataDir=${this.dataDir}
            deviceProtectedDataDir=${this.deviceProtectedDataDir}
            functionalTest=${this.functionalTest}
            handleProfiling=${this.handleProfiling}
            icon=${this.icon}
            labelRes=${this.labelRes}
            logo=${this.logo}
            metaData=${this.metaData}
            name=${this.name}
            nativeLibraryDir=${this.nativeLibraryDir}
            nonLocalizedLabel=${
                // Per b/184574333, v1 mistakenly trimmed the label. v2 fixed this, but for test
                // comparison, trim both so they can be matched.
                this.nonLocalizedLabel?.trim()
            }
            packageName=${this.packageName}
            primaryCpuAbi=${this.primaryCpuAbi}
            publicSourceDir=${this.publicSourceDir}
            secondaryCpuAbi=${this.secondaryCpuAbi}
            secondaryNativeLibraryDir=${this.secondaryNativeLibraryDir}
            showUserIcon=${this.showUserIcon}
            sourceDir=${this.sourceDir}
            splitDependencies=${this.splitDependencies.dumpToString()}
            splitNames=${this.splitNames?.contentToString()}
            splitPublicSourceDirs=${this.splitPublicSourceDirs?.contentToString()}
            splitSourceDirs=${this.splitSourceDirs?.contentToString()}
            targetPackage=${this.targetPackage}
            targetProcesses=${this.targetProcesses}
            """.trimIndent()

    protected fun ActivityInfo.dumpToString() = """
            banner=${this.banner}
            colorMode=${this.colorMode}
            configChanges=${this.configChanges}
            descriptionRes=${this.descriptionRes}
            directBootAware=${this.directBootAware}
            documentLaunchMode=${this.documentLaunchMode
            .ignored("Update for fixing b/128526493 and the testing is no longer valid")}
            enabled=${this.enabled}
            exported=${this.exported}
            flags=${Integer.toBinaryString(this.flags)}
            icon=${this.icon}
            labelRes=${this.labelRes}
            launchMode=${this.launchMode}
            launchToken=${this.launchToken}
            lockTaskLaunchMode=${this.lockTaskLaunchMode}
            logo=${this.logo}
            maxAspectRatio=${this.maxAspectRatio}
            maxRecents=${this.maxRecents}
            metaData=${this.metaData.dumpToString()}
            minAspectRatio=${this.minAspectRatio}
            name=${this.name}
            nonLocalizedLabel=${
                // Per b/184574333, v1 mistakenly trimmed the label. v2 fixed this, but for test
                // comparison, trim both so they can be matched.
                this.nonLocalizedLabel?.trim()
            }
            packageName=${this.packageName}
            parentActivityName=${this.parentActivityName}
            permission=${this.permission}
            persistableMode=${this.persistableMode.ignored("Could be dropped pre-R, fixed in R")}
            privateFlags=${
                // Strip flag added in S
                this.privateFlags and (ActivityInfo.PRIVATE_FLAG_HOME_TRANSITION_SOUND.inv())
            }
            processName=${this.processName.ignored("Deferred pre-R, but assigned immediately in R")}
            requestedVrComponent=${this.requestedVrComponent}
            resizeMode=${this.resizeMode}
            rotationAnimation=${this.rotationAnimation}
            screenOrientation=${this.screenOrientation}
            showUserIcon=${this.showUserIcon}
            softInputMode=${this.softInputMode}
            splitName=${this.splitName}
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
            banner=${this.banner}
            descriptionRes=${this.descriptionRes}
            flags=${Integer.toBinaryString(this.flags)}
            group=${this.group}
            icon=${this.icon}
            labelRes=${this.labelRes}
            logo=${this.logo}
            metaData=${this.metaData.dumpToString()}
            name=${this.name}
            nonLocalizedDescription=${this.nonLocalizedDescription}
            nonLocalizedLabel=${
                // Per b/184574333, v1 mistakenly trimmed the label. v2 fixed this, but for test
                // comparison, trim both so they can be matched.
                this.nonLocalizedLabel?.trim()
            }
            packageName=${this.packageName}
            protectionLevel=${this.protectionLevel}
            requestRes=${this.requestRes}
            showUserIcon=${this.showUserIcon}
            """.trimIndent()

    protected fun ProviderInfo.dumpToString() = """
            applicationInfo=${this.applicationInfo.ignored("Already checked")}
            authority=${this.authority}
            banner=${this.banner}
            descriptionRes=${this.descriptionRes}
            directBootAware=${this.directBootAware}
            enabled=${this.enabled}
            exported=${this.exported}
            flags=${Integer.toBinaryString(this.flags)}
            forceUriPermissions=${this.forceUriPermissions}
            grantUriPermissions=${this.grantUriPermissions}
            icon=${this.icon}
            initOrder=${this.initOrder}
            isSyncable=${this.isSyncable}
            labelRes=${this.labelRes}
            logo=${this.logo}
            metaData=${this.metaData.dumpToString()}
            multiprocess=${this.multiprocess}
            name=${this.name}
            nonLocalizedLabel=${
                // Per b/184574333, v1 mistakenly trimmed the label. v2 fixed this, but for test
                // comparison, trim both so they can be matched.
                this.nonLocalizedLabel?.trim()
            }
            packageName=${this.packageName}
            pathPermissions=${this.pathPermissions?.joinToString {
        "readPermission=${it.readPermission}\nwritePermission=${it.writePermission}"
    }}
            processName=${this.processName.ignored("Deferred pre-R, but assigned immediately in R")}
            readPermission=${this.readPermission}
            showUserIcon=${this.showUserIcon}
            splitName=${this.splitName}
            uriPermissionPatterns=${this.uriPermissionPatterns?.contentToString()}
            writePermission=${this.writePermission}
            """.trimIndent()

    protected fun ServiceInfo.dumpToString() = """
            applicationInfo=${this.applicationInfo.ignored("Already checked")}
            banner=${this.banner}
            descriptionRes=${this.descriptionRes}
            directBootAware=${this.directBootAware}
            enabled=${this.enabled}
            exported=${this.exported}
            flags=${Integer.toBinaryString(this.flags)}
            icon=${this.icon}
            labelRes=${this.labelRes}
            logo=${this.logo}
            mForegroundServiceType"${this.mForegroundServiceType}
            metaData=${this.metaData.dumpToString()}
            name=${this.name}
            nonLocalizedLabel=${
                // Per b/184574333, v1 mistakenly trimmed the label. v2 fixed this, but for test
                // comparison, trim both so they can be matched.
                this.nonLocalizedLabel?.trim()
            }
            packageName=${this.packageName}
            permission=${this.permission}
            processName=${this.processName.ignored("Deferred pre-R, but assigned immediately in R")}
            showUserIcon=${this.showUserIcon}
            splitName=${this.splitName}
            """.trimIndent()

    protected fun ConfigurationInfo.dumpToString() = """
            reqGlEsVersion=${this.reqGlEsVersion}
            reqInputFeatures=${this.reqInputFeatures}
            reqKeyboardType=${this.reqKeyboardType}
            reqNavigation=${this.reqNavigation}
            reqTouchScreen=${this.reqTouchScreen}
            """.trimIndent()

    protected fun PackageInfo.dumpToString() = """
            activities=${this.activities?.joinToString { it.dumpToString() }
            .ignored("Checked separately in test")}
            applicationInfo=${this.applicationInfo.dumpToString()
            .ignored("Checked separately in test")}
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
            providers=${this.providers?.joinToString { it.dumpToString() }
            .ignored("Checked separately in test")}
            receivers=${this.receivers?.joinToString { it.dumpToString() }
            .ignored("Checked separately in test")}
            reqFeatures=${this.reqFeatures?.joinToString { it.dumpToString() }}
            requestedPermissions=${this.requestedPermissions?.contentToString()}
            requestedPermissionsFlags=${
                this.requestedPermissionsFlags?.map {
                    // Newer flags are stripped
                    it and (PackageInfo.REQUESTED_PERMISSION_REQUIRED
                            or PackageInfo.REQUESTED_PERMISSION_GRANTED)
                }?.joinToString()
            }
            requiredAccountType=${this.requiredAccountType}
            requiredForAllUsers=${this.requiredForAllUsers}
            restrictedAccountType=${this.restrictedAccountType}
            services=${this.services?.joinToString { it.dumpToString() }
            .ignored("Checked separately in test")}
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

    private fun Bundle?.dumpToString() = this?.keySet()?.associateWith { get(it) }?.toString()

    private fun <T> SparseArray<T>?.dumpToString(): String {
        if (this == null) {
            return "EMPTY"
        }

        val list = mutableListOf<Pair<Int, T>>()
        for (index in (0 until size())) {
            list += keyAt(index) to valueAt(index)
        }
        return list.toString()
    }
}
