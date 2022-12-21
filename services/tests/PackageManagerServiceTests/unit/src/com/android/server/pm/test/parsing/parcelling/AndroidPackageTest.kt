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

package com.android.server.pm.test.parsing.parcelling

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ConfigurationInfo
import android.content.pm.FeatureGroupInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.content.pm.SigningDetails
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.ArraySet
import android.util.SparseArray
import android.util.SparseIntArray
import com.android.internal.R
import com.android.server.pm.parsing.pkg.AndroidPackageUtils
import com.android.server.pm.parsing.pkg.PackageImpl
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.component.ParsedActivityImpl
import com.android.server.pm.pkg.component.ParsedApexSystemServiceImpl
import com.android.server.pm.pkg.component.ParsedAttributionImpl
import com.android.server.pm.pkg.component.ParsedComponentImpl
import com.android.server.pm.pkg.component.ParsedInstrumentationImpl
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl
import com.android.server.pm.pkg.component.ParsedPermissionGroupImpl
import com.android.server.pm.pkg.component.ParsedPermissionImpl
import com.android.server.pm.pkg.component.ParsedProcessImpl
import com.android.server.pm.pkg.component.ParsedProviderImpl
import com.android.server.pm.pkg.component.ParsedServiceImpl
import com.android.server.pm.pkg.component.ParsedUsesPermissionImpl
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.UUID
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class AndroidPackageTest : ParcelableComponentTest(AndroidPackage::class, PackageImpl::class) {

    companion object {
        private val TEST_UUID = UUID.fromString("57554103-df3e-4475-ae7a-8feba49353ac")
    }

    override val defaultImpl = PackageImpl.forTesting("com.example.test")
    override val creator = PackageImpl.CREATOR

    override val excludedMethods = listOf(
        // Internal methods
        "toAppInfoToString",
        "toAppInfoWithoutState",
        "toAppInfoWithoutStateWithoutFlags",
        "addMimeGroupsFromComponent",
        "assignDerivedFields",
        "assignDerivedFields2",
        "makeImmutable",
        "buildFakeForDeletion",
        "buildAppClassNamesByProcess",
        "capPermissionPriorities",
        "forParsing",
        "forTesting",
        "getBaseAppDataCredentialProtectedDirForSystemUser",
        "getBaseAppDataDeviceProtectedDirForSystemUser",
        "getBoolean",
        "getBoolean2",
        "setBoolean",
        "setBoolean2",
        "hideAsFinal",
        "hideAsParsed",
        "markNotActivitiesAsNotExportedIfSingleUser",
        "sortActivities",
        "sortReceivers",
        "sortServices",
        "setAllComponentsDirectBootAware",
        "getUsesLibrariesSorted",
        "getUsesOptionalLibrariesSorted",
        "getUsesSdkLibrariesSorted",
        "getUsesStaticLibrariesSorted",
        // Tested through setting minor/major manually
        "setLongVersionCode",
        "getLongVersionCode",
        // Tested through constructor
        "getManifestPackageName",
        // Removal not tested, irrelevant for parcelling concerns
        "removeUsesOptionalLibrary",
        "clearAdoptPermissions",
        "clearOriginalPackages",
        "clearProtectedBroadcasts",
        "removePermission",
        "removeUsesLibrary",
        "removeUsesOptionalNativeLibrary",
        // Tested manually
        "getMimeGroups",
        "getRequestedPermissions",
        "getStorageUuid",
        // Tested through asSplit
        "asSplit",
        "getSplits",
        "getSplitNames",
        "getSplitCodePaths",
        "getSplitRevisionCodes",
        "getSplitFlags",
        "getSplitClassLoaderNames",
        "getSplitDependencies",
        "setSplitCodePaths",
        "setSplitClassLoaderName",
        "setSplitHasCode",
        // Tested through addUsesSdkLibrary
        "addUsesSdkLibrary",
        "getUsesSdkLibraries",
        "getUsesSdkLibrariesVersionsMajor",
        "getUsesSdkLibrariesCertDigests",
        // Tested through addUsesStaticLibrary
        "addUsesStaticLibrary",
        "getUsesStaticLibraries",
        "getUsesStaticLibrariesVersions",
        "getUsesStaticLibrariesCertDigests",

        // Tested through getSetByValue via AndroidPackageHidden APIs, to be removed eventually
        "setOdm",
        "setOem",
        "setPrivileged",
        "setProduct",
        "setSystem",
        "setSystemExt",
        "setVendor",
        "isOdm",
        "isOem",
        "isPrivileged",
        "isProduct",
        "isSystem",
        "isSystemExt",
        "isVendor",
    )

    override val baseParams = listOf(
        AndroidPackage::getApplicationClassName,
        AndroidPackage::getAppComponentFactory,
        AndroidPackage::getAutoRevokePermissions,
        AndroidPackage::getBackupAgentName,
        AndroidPackage::getBannerRes,
        AndroidPackage::getBaseApkPath,
        AndroidPackage::getBaseRevisionCode,
        AndroidPackage::getCategory,
        AndroidPackage::getClassLoaderName,
        AndroidPackage::getCompatibleWidthLimitDp,
        AndroidPackage::getCompileSdkVersion,
        AndroidPackage::getCompileSdkVersionCodeName,
        AndroidPackage::getDataExtractionRulesRes,
        AndroidPackage::getDescriptionRes,
        AndroidPackage::getFullBackupContentRes,
        AndroidPackage::getGwpAsanMode,
        AndroidPackage::getIconRes,
        AndroidPackage::getInstallLocation,
        AndroidPackage::getLabelRes,
        AndroidPackage::getLargestWidthLimitDp,
        AndroidPackage::getLogoRes,
        AndroidPackage::getLocaleConfigRes,
        AndroidPackage::getManageSpaceActivityName,
        AndroidPackage::getMaxSdkVersion,
        AndroidPackage::getMemtagMode,
        AndroidPackage::getMinSdkVersion,
        AndroidPackage::getNativeHeapZeroInitialized,
        AndroidPackage::getNativeLibraryDir,
        AndroidPackage::getNativeLibraryRootDir,
        AndroidPackage::getNetworkSecurityConfigRes,
        AndroidPackage::getNonLocalizedLabel,
        AndroidPackage::getOverlayCategory,
        AndroidPackage::getOverlayPriority,
        AndroidPackage::getOverlayTarget,
        AndroidPackage::getOverlayTargetOverlayableName,
        AndroidPackage::getPackageName,
        AndroidPackage::getPath,
        AndroidPackage::getPermission,
        PackageImpl::getPrimaryCpuAbi,
        AndroidPackage::getProcessName,
        AndroidPackage::getRequiredAccountType,
        AndroidPackage::getRequiresSmallestWidthDp,
        AndroidPackage::getResizeableActivity,
        AndroidPackage::getRestrictedAccountType,
        AndroidPackage::getRoundIconRes,
        PackageImpl::getSecondaryCpuAbi,
        AndroidPackage::getSecondaryNativeLibraryDir,
        AndroidPackage::getSharedUserId,
        AndroidPackage::getSharedUserLabelRes,
        AndroidPackage::getSdkLibraryName,
        AndroidPackage::getSdkLibVersionMajor,
        AndroidPackage::getStaticSharedLibraryName,
        AndroidPackage::getStaticSharedLibraryVersion,
        AndroidPackage::getTargetSandboxVersion,
        AndroidPackage::getTargetSdkVersion,
        AndroidPackage::getTaskAffinity,
        AndroidPackage::getThemeRes,
        AndroidPackage::getUiOptions,
        AndroidPackage::getUid,
        AndroidPackage::getVersionName,
        AndroidPackage::getZygotePreloadName,
        AndroidPackage::isAllowAudioPlaybackCapture,
        AndroidPackage::isAllowBackup,
        AndroidPackage::isAllowClearUserData,
        AndroidPackage::isAllowClearUserDataOnFailedRestore,
        AndroidPackage::isAllowNativeHeapPointerTagging,
        AndroidPackage::isAllowTaskReparenting,
        AndroidPackage::isAllowUpdateOwnership,
        AndroidPackage::isBackupInForeground,
        AndroidPackage::isHardwareAccelerated,
        AndroidPackage::isCantSaveState,
        AndroidPackage::isCoreApp,
        AndroidPackage::isCrossProfile,
        AndroidPackage::isDebuggable,
        AndroidPackage::isDefaultToDeviceProtectedStorage,
        AndroidPackage::isDirectBootAware,
        AndroidPackage::isEnabled,
        AndroidPackage::isExternalStorage,
        AndroidPackage::isExtractNativeLibs,
        AndroidPackage::isFactoryTest,
        AndroidPackage::isApex,
        AndroidPackage::isForceQueryable,
        AndroidPackage::isFullBackupOnly,
        AndroidPackage::isGame,
        AndroidPackage::isHasCode,
        AndroidPackage::isHasDomainUrls,
        AndroidPackage::isHasFragileUserData,
        AndroidPackage::isIsolatedSplitLoading,
        AndroidPackage::isKillAfterRestore,
        AndroidPackage::isLargeHeap,
        AndroidPackage::isMultiArch,
        AndroidPackage::isNativeLibraryRootRequiresIsa,
        AndroidPackage::isOnBackInvokedCallbackEnabled,
        AndroidPackage::isResourceOverlay,
        AndroidPackage::isOverlayIsStatic,
        AndroidPackage::isPartiallyDirectBootAware,
        AndroidPackage::isPersistent,
        AndroidPackage::isProfileableByShell,
        AndroidPackage::isRequestLegacyExternalStorage,
        AndroidPackage::isRequiredForAllUsers,
        AndroidPackage::isResizeableActivityViaSdkVersion,
        AndroidPackage::isRestoreAnyVersion,
        AndroidPackage::isSignedWithPlatformKey,
        AndroidPackage::isSdkLibrary,
        AndroidPackage::isStaticSharedLibrary,
        AndroidPackage::isStub,
        AndroidPackage::isSupportsRtl,
        AndroidPackage::isTestOnly,
        AndroidPackage::isUse32BitAbi,
        AndroidPackage::isUseEmbeddedDex,
        AndroidPackage::isUsesCleartextTraffic,
        AndroidPackage::isUsesNonSdkApi,
        AndroidPackage::isVisibleToInstantApps,
        AndroidPackage::isVmSafeMode,
        AndroidPackage::isLeavingSharedUser,
        AndroidPackage::isResetEnabledSettingsOnAppDataCleared,
        AndroidPackage::getMaxAspectRatio,
        AndroidPackage::getMinAspectRatio,
        AndroidPackage::hasPreserveLegacyExternalStorage,
        AndroidPackage::hasRequestForegroundServiceExemption,
        AndroidPackage::hasRequestRawExternalStorageAccess
    )

    override fun extraParams() = listOf(
        getter(AndroidPackage::getVolumeUuid, TEST_UUID.toString()),
        getter(AndroidPackage::isProfileable, true),
        getter(PackageImpl::getVersionCode, 3),
        getter(PackageImpl::getVersionCodeMajor, 9),
        getter(AndroidPackage::getUpgradeKeySets, setOf("testUpgradeKeySet")),
        getter(AndroidPackage::isAnyDensity, false, 0),
        getter(AndroidPackage::isResizeable, false, 0),
        getter(AndroidPackage::isSupportsSmallScreens, false, 0),
        getter(AndroidPackage::isSupportsNormalScreens, false, 0),
        getter(AndroidPackage::isSupportsLargeScreens, false, 0),
        getter(AndroidPackage::isSupportsExtraLargeScreens, false, 0),
        adder(AndroidPackage::getAdoptPermissions, "test.adopt.PERMISSION"),
        adder(AndroidPackage::getOriginalPackages, "com.test.original"),
        adder(AndroidPackage::getImplicitPermissions, "test.implicit.PERMISSION"),
        adder(AndroidPackage::getLibraryNames, "testLibraryName"),
        adder(AndroidPackage::getProtectedBroadcasts, "test.protected.BROADCAST"),
        adder(AndroidPackage::getQueriesPackages, "com.test.package.queries"),
        adder(AndroidPackage::getQueriesProviders, "com.test.package.queries.provider"),
        adder(AndroidPackage::getUsesLibraries, "testUsesLibrary"),
        adder(AndroidPackage::getUsesNativeLibraries, "testUsesNativeLibrary"),
        adder(AndroidPackage::getUsesOptionalLibraries, "testUsesOptionalLibrary"),
        adder(AndroidPackage::getUsesOptionalNativeLibraries, "testUsesOptionalNativeLibrary"),
        getSetByValue(
            AndroidPackage::isAttributionsUserVisible,
            PackageImpl::setAttributionsAreUserVisible,
            true
        ),
        getSetByValue2(
            AndroidPackage::getOverlayables,
            PackageImpl::addOverlayable,
            "testOverlayableName" to "testActorName",
            transformGet = { "testOverlayableName" to it["testOverlayableName"] }
        ),
        getSetByValue(
            AndroidPackage::getMetaData,
            PackageImpl::setMetaData,
            "testBundleKey" to "testBundleValue",
            transformGet = { "testBundleKey" to it?.getString("testBundleKey") },
            transformSet = { Bundle().apply { putString(it.first, it.second) } }
        ),
        getSetByValue(
            AndroidPackage::getAttributions,
            PackageImpl::addAttribution,
            Triple("testTag", 13, listOf("testInherit")),
            transformGet = { it.singleOrNull()?.let { Triple(it.tag, it.label, it.inheritFrom) } },
            transformSet = { it?.let {
                ParsedAttributionImpl(
                    it.first,
                    it.second,
                    it.third
                )
            } }
        ),
        getSetByValue2(
            AndroidPackage::getKeySetMapping,
            PackageImpl::addKeySet,
            "testKeySetName" to testKey(),
            transformGet = { "testKeySetName" to it["testKeySetName"]?.singleOrNull() }
        ),
        getSetByValue(
            AndroidPackage::getPermissionGroups,
            PackageImpl::addPermissionGroup,
            "test.permission.GROUP",
            transformGet = { it.singleOrNull()?.name },
            transformSet = { ParsedPermissionGroupImpl()
                .apply { setName(it) } }
        ),
        getSetByValue2(
            AndroidPackage::getPreferredActivityFilters,
            PackageImpl::addPreferredActivityFilter,
            "TestClassName" to ParsedIntentInfoImpl()
                .apply {
                intentFilter.apply {
                    addDataScheme("http")
                    addDataAuthority("test.pm.server.android.com", null)
                }
            },
            transformGet = { it.singleOrNull()?.let { it.first to it.second } },
            compare = { first, second ->
                equalBy(
                    first, second,
                    { it.first },
                    { it.second.intentFilter.schemesIterator().asSequence().singleOrNull() },
                    { it.second.intentFilter.authoritiesIterator().asSequence()
                        .singleOrNull()?.host }
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getQueriesIntents,
            PackageImpl::addQueriesIntent,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://test.pm.server.android.com")),
            transformGet = { it.singleOrNull() },
            compare = { first, second -> first?.filterEquals(second) }
        ),
        getSetByValue(
            AndroidPackage::getRestrictUpdateHash,
            PackageImpl::setRestrictUpdateHash,
            byteArrayOf(0, 1, 2, 3, 4),
            compare = ByteArray::contentEquals
        ),
        getSetByValue(
            AndroidPackage::getSigningDetails,
            PackageImpl::setSigningDetails,
            testKey(),
            transformGet = { it.publicKeys?.takeIf { it.size > 0 }?.valueAt(0) },
            transformSet = {
                SigningDetails(
                    null,
                    SigningDetails.SignatureSchemeVersion.UNKNOWN,
                    ArraySet<PublicKey>().apply { add(it) },
                    null
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getActivities,
            PackageImpl::addActivity,
            "TestActivityName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedActivityImpl()
                .apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getApexSystemServices,
            PackageImpl::addApexSystemService,
            "TestApexSystemServiceName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedApexSystemServiceImpl()
                .apply { name = it } }
        ),
        getSetByValue(
            AndroidPackage::getReceivers,
            PackageImpl::addReceiver,
            "TestReceiverName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedActivityImpl()
                .apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getServices,
            PackageImpl::addService,
            "TestServiceName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedServiceImpl()
                .apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getProviders,
            PackageImpl::addProvider,
            "TestProviderName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedProviderImpl()
                .apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getInstrumentations,
            PackageImpl::addInstrumentation,
            "TestInstrumentationName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedInstrumentationImpl()
                .apply { name = it } }
        ),
        getSetByValue(
            AndroidPackage::getConfigPreferences,
            PackageImpl::addConfigPreference,
            testCounter++,
            transformGet = { it.singleOrNull()?.reqGlEsVersion ?: -1 },
            transformSet = { ConfigurationInfo().apply { reqGlEsVersion = it } }
        ),
        getSetByValue(
            AndroidPackage::getFeatureGroups,
            PackageImpl::addFeatureGroup,
            "test.feature.GROUP",
            transformGet = { it.singleOrNull()?.features?.singleOrNull()?.name.orEmpty() },
            transformSet = {
                FeatureGroupInfo().apply {
                    features = arrayOf(FeatureInfo().apply { name = it })
                }
            }
        ),
        getSetByValue(
            AndroidPackage::getPermissions,
            PackageImpl::addPermission,
            "test.PERMISSION",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedPermissionImpl()
                .apply { name = it } }
        ),
        getSetByValue(
            AndroidPackage::getUsesPermissions,
            PackageImpl::addUsesPermission,
            "test.USES_PERMISSION",
            transformGet = {
                // Need to strip implicit permission, which calls addUsesPermission when added
                it.filterNot { it.name == "test.implicit.PERMISSION" }
                    .singleOrNull()?.name.orEmpty()
            },
            transformSet = {
                ParsedUsesPermissionImpl(
                    it,
                    0
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getRequestedFeatures,
            PackageImpl::addReqFeature,
            "test.feature.INFO",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { FeatureInfo().apply { name = it } }
        ),
        getSetByValue(
            AndroidPackage::getMinExtensionVersions,
            PackageImpl::setMinExtensionVersions,
            SparseIntArray().apply { put(testCounter++, testCounter++) },
            compare = { first, second ->
                equalBy(
                    first, second,
                    { it.size() },
                    { it.keyAt(0) },
                    { it.valueAt(0) }
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getProcesses,
            PackageImpl::setProcesses,
            mapOf("testProcess" to ParsedProcessImpl()
                .apply { name = "testProcessName" }),
            compare = { first, second ->
                equalBy(
                    first, second,
                    { it["testProcess"]?.name }
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getProperties,
            PackageImpl::addProperty,
            PackageManager.Property(
                "testPropertyName",
                "testPropertyValue",
                "testPropertyClassName",
                "testPropertyPackageName"
            ),
            transformGet = { it["testPropertyName"] },
            compare = { first, second ->
                equalBy(
                    first, second,
                    PackageManager.Property::getName,
                    PackageManager.Property::getClassName,
                    PackageManager.Property::getPackageName,
                    PackageManager.Property::getString
                )
            }
        ),
        getter(AndroidPackage::getKnownActivityEmbeddingCerts, setOf("TESTEMBEDDINGCERT")),
        getSetByValue({ AndroidPackageUtils.isOdm(it) }, "isOdm", PackageImpl::setOdm, true),
        getSetByValue({ AndroidPackageUtils.isOem(it) }, "isOem", PackageImpl::setOem, true),
        getSetByValue(
            { AndroidPackageUtils.isPrivileged(it) },
            "isPrivileged",
            PackageImpl::setPrivileged,
            true
        ),
        getSetByValue(
            { AndroidPackageUtils.isProduct(it) },
            "isProduct",
            PackageImpl::setProduct,
            true
        ),
        getSetByValue(
            { AndroidPackageUtils.isVendor(it) },
            "isVendor",
            PackageImpl::setVendor,
            true
        ),
        getSetByValue(
            { AndroidPackageUtils.isSystem(it) },
            "isSystem",
            PackageImpl::setSystem,
            true
        ),
        getSetByValue(
            { AndroidPackageUtils.isSystemExt(it) },
            "isSystemExt",
            PackageImpl::setSystemExt,
            true
        ),
    )

    override fun initialObject() = PackageImpl.forParsing(
        "com.example.test",
        "/test/test/base.apk",
        "/test/test",
        mockThrowOnUnmocked {
            whenever(getInteger(R.styleable.AndroidManifest_revisionCode, 0)) { 4 }
            whenever(getBoolean(R.styleable.AndroidManifest_isolatedSplits, false)) { true }

            // Return invalid values here so that the getter/setter is tested properly
            whenever(getInteger(R.styleable.AndroidManifest_versionCode, 0)) { -1 }
            whenever(getInteger(R.styleable.AndroidManifest_versionCodeMajor, 0)) { -1 }
            whenever(
                getNonConfigurationString(
                    R.styleable.AndroidManifest_versionName,
                    0
                )
            ) { "" }
            whenever(getInteger(R.styleable.AndroidManifest_compileSdkVersion, 0)) { 31 }
            whenever(
                getNonConfigurationString(
                    R.styleable.AndroidManifest_compileSdkVersionCodename,
                    0
                )
            ) { "" }
        },
        true
    )
        .asSplit(
            arrayOf("testSplitNameZero", "testSplitNameOne"),
            arrayOf("/test/testSplitZero.apk", "/test/testSplitOne.apk"),
            intArrayOf(10, 11),
            SparseArray<IntArray>().apply {
                put(0, intArrayOf(-1))
                put(1, intArrayOf(0))
                put(2, intArrayOf(1))
            }
        )
        .setSplitHasCode(0, true)
        .setSplitHasCode(1, false)
        .setSplitClassLoaderName(0, "testSplitClassLoaderNameZero")
        .setSplitClassLoaderName(1, "testSplitClassLoaderNameOne")
        .addUsesSdkLibrary("testSdk", 2L, arrayOf("testCertDigest1"))
        .addUsesStaticLibrary("testStatic", 3L, arrayOf("testCertDigest2"))

    override fun finalizeObject(parcelable: Parcelable) {
        (parcelable as PackageImpl).hideAsParsed().hideAsFinal()
    }

    override fun extraAssertions(before: Parcelable, after: Parcelable) {
        super.extraAssertions(before, after)
        after as PackageImpl
        expect.that(after.manifestPackageName).isEqualTo("com.example.test")
        expect.that(after.isCoreApp).isTrue()
        expect.that(after.isIsolatedSplitLoading).isEqualTo(true)
        expect.that(after.longVersionCode).isEqualTo(38654705667)
        expect.that(after.requestedPermissions)
            .containsExactlyElementsIn(after.usesPermissions.map { it.name })
            .inOrder()

        expect.that(after.mimeGroups).containsExactly(
            "TestActivityName/mimeGroup",
            "TestReceiverName/mimeGroup",
            "TestServiceName/mimeGroup",
            "TestProviderName/mimeGroup"
        )

        expect.that(after.splitNames).asList()
            .containsExactly("testSplitNameZero", "testSplitNameOne")
            .inOrder()
        expect.that(after.splitCodePaths).asList()
            .containsExactly("/test/testSplitZero.apk", "/test/testSplitOne.apk")
            .inOrder()
        expect.that(after.splitRevisionCodes).asList()
            .containsExactly(10, 11)
            .inOrder()
        expect.that(after.splitFlags).asList()
            .containsExactly(ApplicationInfo.FLAG_HAS_CODE, 0)
            .inOrder()
        expect.that(after.splitClassLoaderNames).asList()
            .containsExactly("testSplitClassLoaderNameZero", "testSplitClassLoaderNameOne")
            .inOrder()

        expect.that(after.splitDependencies).isNotNull()
        after.splitDependencies?.let {
            expect.that(it.size()).isEqualTo(3)
            expect.that(it.get(0)).asList().containsExactly(-1)
            expect.that(it.get(1)).asList().containsExactly(0)
            expect.that(it.get(2)).asList().containsExactly(1)
        }

        expect.that(after.usesSdkLibraries).containsExactly("testSdk")
        expect.that(after.usesSdkLibrariesVersionsMajor).asList().containsExactly(2L)
        expect.that(after.usesSdkLibrariesCertDigests!!.size).isEqualTo(1)
        expect.that(after.usesSdkLibrariesCertDigests!![0]).asList()
                .containsExactly("testCertDigest1")

        expect.that(after.usesStaticLibraries).containsExactly("testStatic")
        expect.that(after.usesStaticLibrariesVersions).asList().containsExactly(3L)
        expect.that(after.usesStaticLibrariesCertDigests!!.size).isEqualTo(1)
        expect.that(after.usesStaticLibrariesCertDigests!![0]).asList()
                .containsExactly("testCertDigest2")

        expect.that(after.storageUuid).isEqualTo(TEST_UUID)
    }

    private fun testKey() = KeyPairGenerator.getInstance("RSA")
        .generateKeyPair()
        .public

    private fun <T : ParsedComponentImpl> T.withMimeGroups() = apply {
        val componentName = name
        addIntent(ParsedIntentInfoImpl().apply {
            intentFilter.apply {
                addMimeGroup("$componentName/mimeGroup")
            }
        })
    }
}
