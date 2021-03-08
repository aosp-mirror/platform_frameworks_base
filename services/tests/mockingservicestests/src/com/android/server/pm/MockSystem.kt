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
package com.android.server.pm

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.FallbackCategoryProvider
import android.content.pm.FeatureInfo
import android.content.pm.PackageParser.SigningDetails
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.content.pm.UserInfo
import android.content.pm.parsing.ParsingPackage
import android.content.pm.parsing.ParsingPackageUtils
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.SystemProperties
import android.os.UserHandle
import android.os.UserManager
import android.os.incremental.IncrementalManager
import android.util.ArrayMap
import android.util.DisplayMetrics
import android.util.EventLog
import android.view.Display
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.dx.mockito.inline.extended.ExtendedMockito.any
import com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean
import com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt
import com.android.dx.mockito.inline.extended.ExtendedMockito.anyString
import com.android.dx.mockito.inline.extended.ExtendedMockito.argThat
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.eq
import com.android.dx.mockito.inline.extended.ExtendedMockito.spy
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder
import com.android.internal.R
import com.android.server.LocalServices
import com.android.server.LockGuard
import com.android.server.SystemConfig
import com.android.server.SystemServerInitThreadPool
import com.android.server.compat.PlatformCompat
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.pm.dex.DexManager
import com.android.server.pm.parsing.PackageParser2
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.parsing.pkg.PackageImpl
import com.android.server.pm.parsing.pkg.ParsedPackage
import com.android.server.pm.permission.PermissionManagerServiceInternal
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal
import com.android.server.testutils.TestHandler
import com.android.server.testutils.mock
import com.android.server.testutils.nullable
import com.android.server.testutils.whenever
import com.android.server.utils.WatchedArrayMap
import org.junit.Assert
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.mockito.AdditionalMatchers.or
import org.mockito.quality.Strictness
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.PublicKey
import java.security.cert.CertificateException
import java.util.Arrays
import java.util.Random
import java.util.concurrent.FutureTask

/**
 * A utility for mocking behavior of the system and dependencies when testing PackageManagerService
 *
 * Create one of these and call [stageNominalSystemState] as a basis for additional behavior in most
 * tests.
 */
class MockSystem(withSession: (StaticMockitoSessionBuilder) -> Unit = {}) {
    private val random = Random()
    val mocks = Mocks()
    val packageCacheDirectory: File =
            Files.createTempDirectory("packageCache").toFile()
    val rootDirectory: File =
            Files.createTempDirectory("root").toFile()
    val dataAppDirectory: File =
            File(Files.createTempDirectory("data").toFile(), "app")
    val frameworkSignature: SigningDetails = SigningDetails(arrayOf(generateSpySignature()), 3)
    val systemPartitions: List<PackageManagerService.ScanPartition> =
            redirectScanPartitions(PackageManagerService.SYSTEM_PARTITIONS)
    val session: StaticMockitoSession

    /** Tracks temporary files created by this class during the running of a test.  */
    private val createdFiles = ArrayList<File>()

    /** Settings that are expected to be added as part of the test  */
    private val mPendingPackageAdds: MutableList<Pair<String, PackageSetting>> = ArrayList()

    /** Settings simulated to be stored on disk  */
    private val mPreExistingSettings = ArrayMap<String, PackageSetting>()

    /** The active map simulating the in memory storage of Settings  */
    private val mSettingsMap = WatchedArrayMap<String, PackageSetting>()

    init {
        val apply = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .mockStatic(SystemProperties::class.java)
                .mockStatic(SystemConfig::class.java)
                .mockStatic(SELinuxMMAC::class.java)
                .mockStatic(FallbackCategoryProvider::class.java)
                .mockStatic(PackageManagerServiceUtils::class.java)
                .mockStatic(Environment::class.java)
                .mockStatic(SystemServerInitThreadPool::class.java)
                .mockStatic(ParsingPackageUtils::class.java)
                .mockStatic(LockGuard::class.java)
                .mockStatic(EventLog::class.java)
                .mockStatic(LocalServices::class.java)
                .apply(withSession)
        session = apply.startMocking()
        whenever(mocks.settings.insertPackageSettingLPw(
                any(PackageSetting::class.java), any(AndroidPackage::class.java))) {
            val name: String = (getArgument<Any>(0) as PackageSetting).name
            val pendingAdd =
                    mPendingPackageAdds.firstOrNull { it.first == name } ?: return@whenever null
            mPendingPackageAdds.remove(pendingAdd)
            mSettingsMap[name] = pendingAdd.second
            null
        }
        whenever(mocks.settings.addPackageLPw(nullable(), nullable(), nullable(), nullable(),
                nullable(), nullable(), nullable(), nullable(), nullable(), nullable(), nullable(),
                nullable(), nullable(), nullable(), nullable())) {
            val name: String = getArgument(0)
            val pendingAdd = mPendingPackageAdds.firstOrNull { it.first == name }
                    ?: return@whenever null
            mPendingPackageAdds.remove(pendingAdd)
            mSettingsMap[name] = pendingAdd.second
            pendingAdd.second
        }
        whenever(mocks.settings.packagesLocked).thenReturn(mSettingsMap)
        whenever(mocks.settings.getPackageLPr(anyString())) { mSettingsMap[getArgument<Any>(0)] }
        whenever(mocks.settings.readLPw(nullable())) {
            mSettingsMap.putAll(mPreExistingSettings)
            !mPreExistingSettings.isEmpty()
        }
    }

    /** Collection of mocks used for PackageManagerService tests. */

    class Mocks {
        val lock = Any()
        val installLock = Any()
        val injector: PackageManagerService.Injector = mock()
        val systemWrapper: PackageManagerService.SystemWrapper = mock()
        val context: Context = mock()
        val userManagerService: UserManagerService = mock()
        val componentResolver: ComponentResolver = mock()
        val permissionManagerInternal: PermissionManagerServiceInternal = mock()
        val incrementalManager: IncrementalManager = mock()
        val platformCompat: PlatformCompat = mock()
        val settings: Settings = mock()
        val resources: Resources = mock()
        val systemConfig: SystemConfig = mock()
        val apexManager: ApexManager = mock()
        val userManagerInternal: UserManagerInternal = mock()
        val packageParser: PackageParser2 = mock()
        val keySetManagerService: KeySetManagerService = mock()
        val packageAbiHelper: PackageAbiHelper = mock()
        val appsFilter: AppsFilter = mock()
        val dexManager: DexManager = mock()
        val installer: Installer = mock()
        val displayMetrics: DisplayMetrics = mock()
        val domainVerificationManagerInternal: DomainVerificationManagerInternal = mock()
        val handler = TestHandler(null)
    }

    companion object {
        private const val DEVICE_PROVISIONING_PACKAGE_NAME =
                "com.example.android.device.provisioning"
        private val DEFAULT_AVAILABLE_FEATURES_MAP = ArrayMap<String, FeatureInfo>()
        private val DEFAULT_ACTIVE_APEX_INFO_LIST = emptyList<ApexManager.ActiveApexInfo>()
        private val DEFAULT_SHARED_LIBRARIES_LIST =
                ArrayMap<String, SystemConfig.SharedLibraryEntry>()
        private val DEFAULT_USERS = Arrays.asList(
                UserInfo(UserHandle.USER_SYSTEM, "primary", "",
                        UserInfo.FLAG_PRIMARY or UserInfo.FLAG_SYSTEM or UserInfo.FLAG_FULL,
                        UserManager.USER_TYPE_FULL_SYSTEM))
        public val DEFAULT_VERSION_INFO = Settings.VersionInfo()

        init {
            DEFAULT_VERSION_INFO.fingerprint = "abcdef"
            DEFAULT_VERSION_INFO.sdkVersion = Build.VERSION_CODES.R
            DEFAULT_VERSION_INFO.databaseVersion = Settings.CURRENT_DATABASE_VERSION
        }
    }

    /**
     * Clean up any potentially dangling state. This should be run at the end of every test to
     * account for changes to static memory, such as [LocalServices]
     */
    fun cleanup() {
        createdFiles.forEach(File::delete)
        createdFiles.clear()
        mSettingsMap.clear()
        mPendingPackageAdds.clear()
        mPreExistingSettings.clear()
        session.finishMocking()
    }

    /**
     * Run this method to ensure that all expected actions were executed, such as pending
     * [Settings] adds.
     */
    fun validateFinalState() {
        if (mPendingPackageAdds.isNotEmpty()) {
            Assert.fail(
                    "Not all expected settings were added: ${mPendingPackageAdds.map { it.first }}")
        }
    }

    /**
     * This method stages enough of system startup to execute the PackageManagerService constructor
     * successfullly.
     */
    @Throws(Exception::class)
    fun stageNominalSystemState() {
        whenever(mocks.injector.context).thenReturn(mocks.context)
        whenever(mocks.injector.lock).thenReturn(mocks.lock)
        whenever(mocks.injector.installLock).thenReturn(mocks.installLock)
        whenever(mocks.injector.systemWrapper).thenReturn(mocks.systemWrapper)
        whenever(mocks.injector.userManagerService).thenReturn(mocks.userManagerService)
        whenever(mocks.injector.componentResolver).thenReturn(mocks.componentResolver)
        whenever(mocks.injector.permissionManagerServiceInternal) {
            mocks.permissionManagerInternal
        }
        whenever(mocks.injector.incrementalManager).thenReturn(mocks.incrementalManager)
        whenever(mocks.injector.compatibility).thenReturn(mocks.platformCompat)
        whenever(mocks.injector.settings).thenReturn(mocks.settings)
        whenever(mocks.injector.dexManager).thenReturn(mocks.dexManager)
        whenever(mocks.injector.systemConfig).thenReturn(mocks.systemConfig)
        whenever(mocks.injector.apexManager).thenReturn(mocks.apexManager)
        whenever(mocks.injector.scanningCachingPackageParser).thenReturn(mocks.packageParser)
        whenever(mocks.injector.scanningPackageParser).thenReturn(mocks.packageParser)
        whenever(mocks.injector.systemPartitions).thenReturn(systemPartitions)
        whenever(mocks.injector.appsFilter).thenReturn(mocks.appsFilter)
        whenever(mocks.injector.abiHelper).thenReturn(mocks.packageAbiHelper)
        whenever(mocks.injector.userManagerInternal).thenReturn(mocks.userManagerInternal)
        whenever(mocks.injector.installer).thenReturn(mocks.installer)
        whenever(mocks.injector.displayMetrics).thenReturn(mocks.displayMetrics)
        whenever(mocks.injector.domainVerificationManagerInternal)
            .thenReturn(mocks.domainVerificationManagerInternal)
        whenever(mocks.injector.handler) { mocks.handler }
        wheneverStatic { SystemConfig.getInstance() }.thenReturn(mocks.systemConfig)
        whenever(mocks.systemConfig.availableFeatures).thenReturn(DEFAULT_AVAILABLE_FEATURES_MAP)
        whenever(mocks.systemConfig.sharedLibraries).thenReturn(DEFAULT_SHARED_LIBRARIES_LIST)
        wheneverStatic { SystemProperties.getBoolean("fw.free_cache_v2", true) }.thenReturn(true)
        wheneverStatic { Environment.getPackageCacheDirectory() }.thenReturn(packageCacheDirectory)
        wheneverStatic { SystemProperties.digestOf("ro.build.fingerprint") }.thenReturn("cacheName")
        wheneverStatic { Environment.getRootDirectory() }.thenReturn(rootDirectory)
        wheneverStatic { SystemServerInitThreadPool.submit(any(Runnable::class.java), anyString()) }
                .thenAnswer { FutureTask<Any?>(it.getArgument(0), null) }

        wheneverStatic { Environment.getDataDirectory() }.thenReturn(dataAppDirectory.parentFile)
        wheneverStatic { Environment.getDataSystemDirectory() }
                .thenReturn(File(dataAppDirectory.parentFile, "system"))
        whenever(mocks.context.resources).thenReturn(mocks.resources)
        whenever(mocks.resources.getString(R.string.config_deviceProvisioningPackage)) {
            DEVICE_PROVISIONING_PACKAGE_NAME
        }
        whenever(mocks.apexManager.activeApexInfos).thenReturn(DEFAULT_ACTIVE_APEX_INFO_LIST)
        whenever(mocks.settings.packagesLocked).thenReturn(mSettingsMap)
        whenever(mocks.settings.internalVersion).thenReturn(DEFAULT_VERSION_INFO)
        whenever(mocks.settings.keySetManagerService).thenReturn(mocks.keySetManagerService)
        whenever(mocks.settings.keySetManagerService).thenReturn(mocks.keySetManagerService)
        whenever(mocks.settings.snapshot()).thenReturn(mocks.settings)
        whenever(mocks.packageAbiHelper.derivePackageAbi(
                any(AndroidPackage::class.java), anyBoolean(), nullable(), any(File::class.java))) {
            android.util.Pair(PackageAbiHelper.Abis("", ""),
                    PackageAbiHelper.NativeLibraryPaths("", false, "", ""))
        }
        whenever(mocks.userManagerInternal.getUsers(true, false, false)).thenReturn(DEFAULT_USERS)
        whenever(mocks.userManagerService.userIds).thenReturn(intArrayOf(0))
        whenever(mocks.userManagerService.exists(0)).thenReturn(true)
        whenever(mocks.packageAbiHelper.deriveNativeLibraryPaths(
                any(AndroidPackage::class.java), anyBoolean(), any(File::class.java))) {
            PackageAbiHelper.NativeLibraryPaths("", false, "", "")
        }
        // everything visible by default
        whenever(mocks.appsFilter.shouldFilterApplication(
                anyInt(), nullable(), nullable(), anyInt())) { false }

        val displayManager: DisplayManager = mock()
        whenever(mocks.context.getSystemService(DisplayManager::class.java))
                .thenReturn(displayManager)
        val display: Display = mock()
        whenever(displayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(display)

        stageFrameworkScan()
        stageInstallerScan()
        stageServicesExtensionScan()
        stageSystemSharedLibraryScan()
        stagePermissionsControllerScan()
        stageInstantAppResolverScan()
    }

    /**
     * This method will stage the parsing and scanning of a package as well as add it to the
     * [PackageSetting]s read from disk.
     */
    @Throws(Exception::class)
    fun stageScanExistingPackage(
        packageName: String,
        versionCode: Long,
        parent: File?,
        withPackage: (PackageImpl) -> PackageImpl = { it },
        withSetting:
        (PackageSettingBuilder) -> PackageSettingBuilder = { it },
        withExistingSetting:
        (PackageSettingBuilder) -> PackageSettingBuilder = { it }
    ) {
        val existingSettingBuilderRef = arrayOfNulls<PackageSettingBuilder>(1)
        stageScanNewPackage(packageName, versionCode, parent, withPackage,
                withSetting = { settingBuilder ->
                    withSetting(settingBuilder)
                    existingSettingBuilderRef[0] = settingBuilder
                    settingBuilder
                })
        existingSettingBuilderRef[0]?.setPackage(null)
        val packageSetting = existingSettingBuilderRef[0]?.let { withExistingSetting(it) }!!.build()
        addPreExistingSetting(packageName, packageSetting)
    }

    /**
     * This method will stage a [PackageSetting] read from disk, but does not stage any scanning
     * or parsing of the package.
     */
    fun addPreExistingSetting(packageName: String, packageSetting: PackageSetting) {
        mPreExistingSettings[packageName] = packageSetting
    }

    /**
     * This method will stage the parsing and scanning of a package but will not add it to the set
     * of [PackageSetting]s read from disk.
     */
    @Throws(Exception::class)
    fun stageScanNewPackage(
        packageName: String,
        versionCode: Long,
        parent: File?,
        withPackage: (PackageImpl) -> PackageImpl = { it },
        withSetting: (PackageSettingBuilder) -> PackageSettingBuilder = { it }
    ) {
        val pair = createBasicAndroidPackage(parent, packageName, versionCode)
        val apkPath = pair.first
        val pkg = withPackage(pair.second)
        stageParse(apkPath, pkg)
        val parentFile = apkPath.parentFile
        val settingBuilder = withSetting(createBasicSettingBuilder(parentFile, pkg))
        stageSettingInsert(packageName, settingBuilder.build())
    }

    /**
     * Creates a simple package that should reasonably parse for scan operations. This can be used
     * as a basis for more complicated packages.
     */
    fun createBasicAndroidPackage(
        parent: File?,
        packageName: String,
        versionCode: Long,
        signingDetails: SigningDetails =
                createRandomSigningDetails()
    ): Pair<File, PackageImpl> {
        val apkPath = File(File(parent, packageName), "base.apk")
        val pkg = PackageImpl.forTesting(packageName, apkPath.parentFile.path) as PackageImpl
        pkg.signingDetails = signingDetails
        wheneverStatic { ParsingPackageUtils.getSigningDetails(eq(pkg), anyBoolean()) }
                .thenReturn(signingDetails)
        pkg.versionCode = versionCode.toInt()
        pkg.versionCodeMajor = (versionCode shr 32).toInt()
        pkg.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT
        return Pair(apkPath, pkg)
    }

    /**
     * This method will create a spy of a [SigningDetails] object to be used when simulating the
     * collection of signatures.
     */
    fun createRandomSigningDetails(): SigningDetails {
        val signingDetails = spy(SigningDetails(arrayOf(generateSpySignature()),
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3))
        doReturn(true).whenever(signingDetails).checkCapability(
                anyString(), anyInt())
        doReturn(true).whenever(signingDetails).checkCapability(
                any(SigningDetails::class.java), anyInt())
        return signingDetails
    }

    /**
     * This method will create a basic [PackageSettingBuilder] from an [AndroidPackage] with all of
     * the necessary parameters to be returned by a simple scan. This can be used as a basis for
     * more complicated settings.
     */
    fun createBasicSettingBuilder(parentFile: File, pkg: AndroidPackage): PackageSettingBuilder {
        return createBasicSettingBuilder(parentFile, pkg.packageName, pkg.longVersionCode,
                pkg.signingDetails)
                .setPackage(pkg)
    }

    /**
     * This method will create a basic [PackageSettingBuilder] with all of the necessary parameters
     * to be returned by a simple scan. This can be used as a basis for more complicated settings.
     */
    fun createBasicSettingBuilder(
        parentFile: File,
        packageName: String,
        versionCode: Long,
        signingDetails: SigningDetails
    ): PackageSettingBuilder {
        return PackageSettingBuilder()
                .setCodePath(parentFile.path)
                .setName(packageName)
                .setPVersionCode(versionCode)
                .setSigningDetails(signingDetails)
    }

    fun createBasicApplicationInfo(pkg: ParsingPackage): ApplicationInfo {
        val applicationInfo: ApplicationInfo = mock()
        applicationInfo.packageName = pkg.packageName
        return applicationInfo
    }

    fun createBasicActivityInfo(
        pkg: ParsingPackage,
        applicationInfo: ApplicationInfo?,
        className: String?
    ):
            ActivityInfo {
        val activityInfo = ActivityInfo()
        activityInfo.applicationInfo = applicationInfo
        activityInfo.packageName = pkg.packageName
        activityInfo.name = className
        return activityInfo
    }

    fun createBasicServiceInfo(
        pkg: ParsingPackage,
        applicationInfo: ApplicationInfo?,
        className: String?
    ):
            ServiceInfo {
        val serviceInfo = ServiceInfo()
        serviceInfo.applicationInfo = applicationInfo
        serviceInfo.packageName = pkg.packageName
        serviceInfo.name = className
        return serviceInfo
    }

    /** Finds the appropriate partition, if available, based on a scan flag unique to it.  */
    fun getPartitionFromFlag(scanFlagMask: Int): PackageManagerService.ScanPartition =
            systemPartitions.first { (it.scanFlag and scanFlagMask) != 0 }

    @Throws(Exception::class)
    private fun stageParse(path: File, parseResult: ParsingPackage): ParsedPackage {
        val basePath = path.parentFile
        basePath.mkdirs()
        path.createNewFile()
        createdFiles.add(path)
        val parsedPackage = parseResult.hideAsParsed() as ParsedPackage
        whenever(mocks.packageParser.parsePackage(
                or(eq(path), eq(basePath)), anyInt(), anyBoolean())) { parsedPackage }
        return parsedPackage
    }

    private fun stageSettingInsert(name: String, setting: PackageSetting): PackageSetting {
        mPendingPackageAdds.add(Pair(name, setting))
        return setting
    }

    @Throws(Exception::class)
    private fun stageFrameworkScan() {
        val apk = File(File(rootDirectory, "framework"), "framework-res.apk")
        val frameworkPkg = PackageImpl.forTesting("android",
                apk.parentFile.path) as PackageImpl
        wheneverStatic { ParsingPackageUtils.getSigningDetails(frameworkPkg, true) }
                .thenReturn(frameworkSignature)
        stageParse(apk, frameworkPkg)
        stageSettingInsert("android",
                PackageSettingBuilder().setCodePath(apk.path).setName(
                        "android").setPackage(frameworkPkg).build())
    }

    @Throws(Exception::class)
    private fun stageInstantAppResolverScan() {
        whenever(mocks.resources.getStringArray(R.array.config_ephemeralResolverPackage)) {
            arrayOf("com.android.test.ephemeral.resolver")
        }
        stageScanNewPackage("com.android.test.ephemeral.resolver",
                1L, getPartitionFromFlag(PackageManagerService.SCAN_AS_PRODUCT).privAppFolder,
                withPackage = { pkg: PackageImpl ->
                    val applicationInfo: ApplicationInfo = createBasicApplicationInfo(pkg)
                    whenever(applicationInfo.isPrivilegedApp).thenReturn(true)
                    mockQueryServices(Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE,
                            createBasicServiceInfo(pkg, applicationInfo, "test.EphemeralService"))
                    mockQueryActivities(Intent.ACTION_INSTANT_APP_RESOLVER_SETTINGS,
                            createBasicActivityInfo(pkg, applicationInfo, "test.SettingsActivity"))
                    pkg
                },
                withSetting = { setting: PackageSettingBuilder ->
                    setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
                })
    }

    @Throws(Exception::class)
    private fun stagePermissionsControllerScan() {
        stageScanNewPackage("com.android.permissions.controller",
                1L, systemPartitions[0].privAppFolder,
                withPackage = { pkg: PackageImpl ->
                    val applicationInfo: ApplicationInfo = createBasicApplicationInfo(pkg)
                    whenever(applicationInfo.isPrivilegedApp).thenReturn(true)
                    mockQueryActivities(Intent.ACTION_MANAGE_PERMISSIONS,
                            createBasicActivityInfo(
                                    pkg, applicationInfo, "test.PermissionActivity"))
                    pkg
                },
                withSetting = { setting: PackageSettingBuilder ->
                    setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
                })
    }

    @Throws(Exception::class)
    private fun stageSystemSharedLibraryScan() {
        stageScanNewPackage("android.ext.shared",
                1L, systemPartitions[0].appFolder,
                withPackage = { it.addLibraryName("android.ext.shared") as PackageImpl },
                withSetting = { setting: PackageSettingBuilder ->
                    setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
                }
        )
    }

    @Throws(Exception::class)
    private fun stageServicesExtensionScan() {
        whenever(mocks.context.getString(R.string.config_servicesExtensionPackage)) {
            "com.android.test.services.extension"
        }
        stageScanNewPackage("com.android.test.services.extension",
                1L, getPartitionFromFlag(PackageManagerService.SCAN_AS_SYSTEM_EXT).privAppFolder,
                withSetting = { setting: PackageSettingBuilder ->
                    setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
                })
    }

    @Throws(Exception::class)
    private fun stageInstallerScan() {
        stageScanNewPackage(
                "com.android.test.installer",
                1L, getPartitionFromFlag(PackageManagerService.SCAN_AS_PRODUCT).privAppFolder,
                withPackage = { pkg: PackageImpl ->
                    val applicationInfo: ApplicationInfo = createBasicApplicationInfo(pkg)
                    whenever(applicationInfo.isPrivilegedApp).thenReturn(true)
                    val installerActivity: ActivityInfo = createBasicActivityInfo(
                            pkg, applicationInfo, "test.InstallerActivity")
                    mockQueryActivities(Intent.ACTION_INSTALL_PACKAGE, installerActivity)
                    mockQueryActivities(Intent.ACTION_UNINSTALL_PACKAGE, installerActivity)
                    mockQueryActivities(Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE,
                            installerActivity)
                    pkg
                },
                withSetting = { setting: PackageSettingBuilder ->
                    setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
                }
        )
    }

    private fun mockQueryActivities(action: String, vararg activities: ActivityInfo) {
        whenever(mocks.componentResolver.queryActivities(
                argThat { intent: Intent? -> intent != null && (action == intent.action) },
                nullable(), anyInt(), anyInt())) {
            ArrayList(activities.asList().map { info: ActivityInfo? ->
                ResolveInfo().apply { activityInfo = info }
            })
        }
    }

    private fun mockQueryServices(action: String, vararg services: ServiceInfo) {
        whenever(mocks.componentResolver.queryServices(
                argThat { intent: Intent? -> intent != null && (action == intent.action) },
                nullable(), anyInt(), anyInt())) {
            ArrayList(services.asList().map { info ->
                ResolveInfo().apply { serviceInfo = info }
            })
        }
    }

    fun generateSpySignature(): Signature {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val signature = spy(Signature(bytes))
        try {
            val mockPublicKey: PublicKey = mock()
            doReturn(mockPublicKey).whenever(signature).getPublicKey()
        } catch (e: CertificateException) {
            throw RuntimeException(e)
        }
        return signature
    }

    /** Override get*Folder methods to point to temporary local directories  */

    @Throws(IOException::class)
    private fun redirectScanPartitions(partitions: List<PackageManagerService.ScanPartition>):
            List<PackageManagerService.ScanPartition> {
        val spiedPartitions: MutableList<PackageManagerService.ScanPartition> =
                ArrayList(partitions.size)
        for (partition: PackageManagerService.ScanPartition in partitions) {
            val spy = spy(partition)
            val newRoot = Files.createTempDirectory(partition.folder.name).toFile()
            whenever(spy.overlayFolder).thenReturn(File(newRoot, "overlay"))
            whenever(spy.appFolder).thenReturn(File(newRoot, "app"))
            whenever(spy.privAppFolder).thenReturn(File(newRoot, "priv-app"))
            whenever(spy.folder).thenReturn(newRoot)
            spiedPartitions.add(spy)
        }
        return spiedPartitions
    }
}

/**
 * Sets up a basic [MockSystem] for use in a test method. This will create a MockSystem before the
 * test method and any [org.junit.Before] annotated methods. It can then be used to access the
 * MockSystem via the [system] method or the mocks directly via [mocks].
 */
class MockSystemRule : TestRule {
    var mockSystem: MockSystem? = null
    override fun apply(base: Statement?, description: Description?) = object : Statement() {
        @Throws(Throwable::class)
        override fun evaluate() {
            mockSystem = MockSystem()
            try {
                base!!.evaluate()
            } finally {
                mockSystem?.cleanup()
                mockSystem = null
            }
        }
    }

    /** Fetch the [MockSystem] instance prepared for this test */
    fun system(): MockSystem = mockSystem!!
    /** Fetch the [MockSystem.Mocks] prepared for this test */
    fun mocks(): MockSystem.Mocks = mockSystem!!.mocks
}
