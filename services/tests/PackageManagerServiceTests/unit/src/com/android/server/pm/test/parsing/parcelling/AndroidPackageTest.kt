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
import android.content.pm.parsing.ParsingPackage
import android.content.pm.parsing.component.ParsedActivity
import android.content.pm.parsing.component.ParsedAttribution
import android.content.pm.parsing.component.ParsedComponent
import android.content.pm.parsing.component.ParsedInstrumentation
import android.content.pm.parsing.component.ParsedIntentInfo
import android.content.pm.parsing.component.ParsedPermission
import android.content.pm.parsing.component.ParsedPermissionGroup
import android.content.pm.parsing.component.ParsedProcess
import android.content.pm.parsing.component.ParsedProvider
import android.content.pm.parsing.component.ParsedService
import android.content.pm.parsing.component.ParsedUsesPermission
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.ArraySet
import android.util.SparseArray
import android.util.SparseIntArray
import com.android.internal.R
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.parsing.pkg.PackageImpl
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import java.security.KeyPairGenerator
import java.security.PublicKey
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class AndroidPackageTest : ParcelableComponentTest(AndroidPackage::class, PackageImpl::class) {

    override val defaultImpl = PackageImpl.forTesting("com.example.test")
    override val creator = PackageImpl.CREATOR

    override val excludedMethods = listOf(
        // Internal methods
        "toAppInfoToString",
        "toAppInfoWithoutState",
        "toAppInfoWithoutStateWithoutFlags",
        "assignDerivedFields",
        "buildFakeForDeletion",
        "capPermissionPriorities",
        "forParsing",
        "forTesting",
        "getBaseAppDataCredentialProtectedDirForSystemUser",
        "getBaseAppDataDeviceProtectedDirForSystemUser",
        "getBoolean",
        "setBoolean",
        "hideAsFinal",
        "hideAsParsed",
        "markNotActivitiesAsNotExportedIfSingleUser",
        "sortActivities",
        "sortReceivers",
        "sortServices",
        "setAllComponentsDirectBootAware",
        // Tested through setting minor/major manually
        "setLongVersionCode",
        "getLongVersionCode",
        // Tested through constructor
        "getManifestPackageName",
        "setManifestPackageName",
        // Utility methods
        "getStorageUuid",
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
        // Tested through asSplit
        "asSplit",
        "getSplitNames",
        "getSplitCodePaths",
        "getSplitRevisionCodes",
        "getSplitFlags",
        "getSplitClassLoaderNames",
        "getSplitDependencies",
        "setSplitCodePaths",
        "setSplitClassLoaderName",
        "setSplitHasCode",
    )

    override val baseParams = listOf(
        AndroidPackage::getAppComponentFactory,
        AndroidPackage::getAutoRevokePermissions,
        AndroidPackage::getBackupAgentName,
        AndroidPackage::getBanner,
        AndroidPackage::getBaseApkPath,
        AndroidPackage::getBaseRevisionCode,
        AndroidPackage::getCategory,
        AndroidPackage::getClassLoaderName,
        AndroidPackage::getClassName,
        AndroidPackage::getCompatibleWidthLimitDp,
        AndroidPackage::getCompileSdkVersion,
        AndroidPackage::getCompileSdkVersionCodeName,
        AndroidPackage::getDataExtractionRules,
        AndroidPackage::getDescriptionRes,
        AndroidPackage::getFullBackupContent,
        AndroidPackage::getGwpAsanMode,
        AndroidPackage::getIconRes,
        AndroidPackage::getInstallLocation,
        AndroidPackage::getLabelRes,
        AndroidPackage::getLargestWidthLimitDp,
        AndroidPackage::getLogo,
        AndroidPackage::getManageSpaceActivityName,
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
        AndroidPackage::getOverlayTargetName,
        AndroidPackage::getPackageName,
        AndroidPackage::getPath,
        AndroidPackage::getPermission,
        AndroidPackage::getPrimaryCpuAbi,
        AndroidPackage::getProcessName,
        AndroidPackage::getRealPackage,
        AndroidPackage::getRequiredAccountType,
        AndroidPackage::getRequiresSmallestWidthDp,
        AndroidPackage::getResizeableActivity,
        AndroidPackage::getRestrictedAccountType,
        AndroidPackage::getRoundIconRes,
        AndroidPackage::getSeInfo,
        AndroidPackage::getSeInfoUser,
        AndroidPackage::getSecondaryCpuAbi,
        AndroidPackage::getSecondaryNativeLibraryDir,
        AndroidPackage::getSharedUserId,
        AndroidPackage::getSharedUserLabel,
        AndroidPackage::getStaticSharedLibName,
        AndroidPackage::getStaticSharedLibVersion,
        AndroidPackage::getTargetSandboxVersion,
        AndroidPackage::getTargetSdkVersion,
        AndroidPackage::getTaskAffinity,
        AndroidPackage::getTheme,
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
        AndroidPackage::isBackupInForeground,
        AndroidPackage::isBaseHardwareAccelerated,
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
        AndroidPackage::isOdm,
        AndroidPackage::isOem,
        AndroidPackage::isOverlay,
        AndroidPackage::isOverlayIsStatic,
        AndroidPackage::isPartiallyDirectBootAware,
        AndroidPackage::isPersistent,
        AndroidPackage::isPrivileged,
        AndroidPackage::isProduct,
        AndroidPackage::isProfileableByShell,
        AndroidPackage::isRequestLegacyExternalStorage,
        AndroidPackage::isRequiredForAllUsers,
        AndroidPackage::isResizeableActivityViaSdkVersion,
        AndroidPackage::isRestoreAnyVersion,
        AndroidPackage::isSignedWithPlatformKey,
        AndroidPackage::isStaticSharedLibrary,
        AndroidPackage::isStub,
        AndroidPackage::isSupportsRtl,
        AndroidPackage::isSystem,
        AndroidPackage::isSystemExt,
        AndroidPackage::isTestOnly,
        AndroidPackage::isUse32BitAbi,
        AndroidPackage::isUseEmbeddedDex,
        AndroidPackage::isUsesCleartextTraffic,
        AndroidPackage::isUsesNonSdkApi,
        AndroidPackage::isVendor,
        AndroidPackage::isVisibleToInstantApps,
        AndroidPackage::isVmSafeMode,
        AndroidPackage::getMaxAspectRatio,
        AndroidPackage::getMinAspectRatio,
        AndroidPackage::hasPreserveLegacyExternalStorage,
        AndroidPackage::hasRequestForegroundServiceExemption,
        AndroidPackage::hasRequestRawExternalStorageAccess,
    )

    override fun extraParams() = listOf(
        getter(AndroidPackage::getVolumeUuid, "57554103-df3e-4475-ae7a-8feba49353ac"),
        getter(AndroidPackage::isProfileable, true),
        getter(AndroidPackage::getVersionCode, 3),
        getter(AndroidPackage::getVersionCodeMajor, 9),
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
        adder(AndroidPackage::getUsesStaticLibraries, "testUsesStaticLibrary"),
        getSetByValue(
            AndroidPackage::getUsesStaticLibrariesVersions,
            PackageImpl::addUsesStaticLibraryVersion,
            (testCounter++).toLong(),
            transformGet = { it?.singleOrNull() }
        ),
        getSetByValue(
            AndroidPackage::areAttributionsUserVisible,
            ParsingPackage::setAttributionsAreUserVisible,
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
            transformSet = { it?.let { ParsedAttribution(it.first, it.second, it.third) } }
        ),
        getSetByValue2(
            AndroidPackage::getKeySetMapping,
            PackageImpl::addKeySet,
            "testKeySetName" to testKey(),
            transformGet = { "testKeySetName" to it["testKeySetName"]?.singleOrNull() },
        ),
        getSetByValue(
            AndroidPackage::getPermissionGroups,
            PackageImpl::addPermissionGroup,
            "test.permission.GROUP",
            transformGet = { it.singleOrNull()?.name },
            transformSet = { ParsedPermissionGroup().apply { setName(it) } }
        ),
        getSetByValue2(
            AndroidPackage::getPreferredActivityFilters,
            PackageImpl::addPreferredActivityFilter,
            "TestClassName" to ParsedIntentInfo().apply {
                addDataScheme("http")
                addDataAuthority("test.pm.server.android.com", null)
            },
            transformGet = { it.singleOrNull()?.let { it.first to it.second } },
            compare = { first, second ->
                equalBy(
                    first, second,
                    { it.first },
                    { it.second.schemesIterator().asSequence().singleOrNull() },
                    { it.second.authoritiesIterator().asSequence().singleOrNull()?.host },
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getQueriesIntents,
            PackageImpl::addQueriesIntent,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://test.pm.server.android.com")),
            transformGet = { it.singleOrNull() },
            compare = { first, second -> first?.filterEquals(second) },
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
            AndroidPackage::getUsesStaticLibrariesCertDigests,
            PackageImpl::addUsesStaticLibraryCertDigests,
            arrayOf("testCertDigest"),
            transformGet = { it?.singleOrNull() },
            compare = Array<String?>?::contentEquals
        ),
        getSetByValue(
            AndroidPackage::getActivities,
            PackageImpl::addActivity,
            "TestActivityName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedActivity().apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getReceivers,
            PackageImpl::addReceiver,
            "TestReceiverName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedActivity().apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getServices,
            PackageImpl::addService,
            "TestServiceName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedService().apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getProviders,
            PackageImpl::addProvider,
            "TestProviderName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedProvider().apply { name = it }.withMimeGroups() }
        ),
        getSetByValue(
            AndroidPackage::getInstrumentations,
            PackageImpl::addInstrumentation,
            "TestInstrumentationName",
            transformGet = { it.singleOrNull()?.name.orEmpty() },
            transformSet = { ParsedInstrumentation().apply { name = it } }
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
            transformSet = { ParsedPermission().apply { name = it } }
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
            transformSet = { ParsedUsesPermission(it, 0) }
        ),
        getSetByValue(
            AndroidPackage::getReqFeatures,
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
                    { it.valueAt(0) },
                )
            }
        ),
        getSetByValue(
            AndroidPackage::getProcesses,
            PackageImpl::setProcesses,
            mapOf("testProcess" to ParsedProcess().apply { name = "testProcessName" }),
            compare = { first, second ->
                equalBy(
                    first, second,
                    { it["testProcess"]?.name },
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
                    PackageManager.Property::getString,
                )
            }
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
            }
        )
        .setSplitHasCode(0, true)
        .setSplitHasCode(1, false)
        .setSplitClassLoaderName(0, "testSplitClassLoaderNameZero")
        .setSplitClassLoaderName(1, "testSplitClassLoaderNameOne")

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
            expect.that(it.size()).isEqualTo(2)
            expect.that(it.get(0)).asList().containsExactly(-1)
            expect.that(it.get(1)).asList().containsExactly(0)
        }
    }

    private fun testKey() = KeyPairGenerator.getInstance("RSA")
        .generateKeyPair()
        .public

    private fun <T : ParsedComponent> T.withMimeGroups() = apply {
        val componentName = name
        addIntent(ParsedIntentInfo().apply {
            addMimeGroup("$componentName/mimeGroup")
        })
    }
}
