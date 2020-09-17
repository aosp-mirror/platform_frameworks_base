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

package com.android.server.pm;

import static android.content.pm.SharedLibraryInfo.TYPE_DYNAMIC;
import static android.content.pm.SharedLibraryInfo.TYPE_STATIC;
import static android.content.pm.SharedLibraryInfo.VERSION_UNDEFINED;

import static com.android.server.pm.PackageManagerService.SCAN_AS_FULL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE;
import static com.android.server.pm.PackageManagerService.SCAN_NEW_INSTALL;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsArrayContainingInOrder.arrayContaining;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.ParsingPackage;
import android.content.res.TypedArray;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import com.android.server.compat.PlatformCompat;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.util.UUID;

@RunWith(MockitoJUnitRunner.class)
@Presubmit
// TODO: shared user tests
public class ScanTests {

    private static final String DUMMY_PACKAGE_NAME = "some.app.to.test";

    private static final UUID UUID_ONE = UUID.randomUUID();
    private static final UUID UUID_TWO = UUID.randomUUID();

    @Mock
    PackageAbiHelper mMockPackageAbiHelper;
    @Mock
    UserManagerInternal mMockUserManager;
    @Mock
    PlatformCompat mMockCompatibility;
    @Mock
    PackageManagerService.Injector mMockInjector;

    @Before
    public void setupInjector() {
        when(mMockInjector.getAbiHelper()).thenReturn(mMockPackageAbiHelper);
        when(mMockInjector.getUserManagerInternal()).thenReturn(mMockUserManager);
        when(mMockInjector.getCompatibility()).thenReturn(mMockCompatibility);
    }

    @Before
    public void setupDefaultUser() {
        when(mMockUserManager.getUserIds()).thenReturn(new int[]{0});
    }

    @Before
    public void setupDefaultAbiBehavior() throws Exception {
        when(mMockPackageAbiHelper.derivePackageAbi(
                any(AndroidPackage.class), anyBoolean(), nullable(String.class), anyBoolean()))
                .thenReturn(new Pair<>(
                        new PackageAbiHelper.Abis("derivedPrimary", "derivedSecondary"),
                        new PackageAbiHelper.NativeLibraryPaths(
                                "derivedRootDir", true, "derivedNativeDir", "derivedNativeDir2")));
        when(mMockPackageAbiHelper.getNativeLibraryPaths(
                any(AndroidPackage.class), any(PackageSetting.class), any(File.class)))
                .thenReturn(new PackageAbiHelper.NativeLibraryPaths(
                        "getRootDir", true, "getNativeDir", "getNativeDir2"
                ));
        when(mMockPackageAbiHelper.getBundledAppAbis(
                any(AndroidPackage.class)))
                .thenReturn(new PackageAbiHelper.Abis("bundledPrimary", "bundledSecondary"));
    }

    @Test
    public void newInstallSimpleAllNominal() throws Exception {
        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .addScanFlag(PackageManagerService.SCAN_NEW_INSTALL)
                        .addScanFlag(PackageManagerService.SCAN_AS_FULL_APP)
                        .build();

        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertBasicPackageScanResult(scanResult, DUMMY_PACKAGE_NAME, false /*isInstant*/);
        assertThat(scanResult.existingSettingCopied, is(false));
        assertPathsNotDerived(scanResult);
    }

    @Test
    public void newInstallForAllUsers() throws Exception {
        final int[] userIds = {0, 10, 11};
        when(mMockUserManager.getUserIds()).thenReturn(userIds);

        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .setRealPkgName(null)
                        .addScanFlag(PackageManagerService.SCAN_NEW_INSTALL)
                        .addScanFlag(PackageManagerService.SCAN_AS_FULL_APP)
                        .build();
        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        for (int uid : userIds) {
            assertThat(scanResult.pkgSetting.readUserState(uid).installed, is(true));
        }
    }

    @Test
    public void installRealPackageName() throws Exception {
        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .setRealPkgName("com.package.real")
                        .build();

        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertThat(scanResult.pkgSetting.realName, is("com.package.real"));

        final PackageManagerService.ScanRequest scanRequestNoRealPkg =
                createBasicScanRequestBuilder(
                        createBasicPackage(DUMMY_PACKAGE_NAME)
                                .setRealPackage("com.package.real"))
                        .build();

        final PackageManagerService.ScanResult scanResultNoReal = executeScan(scanRequestNoRealPkg);
        assertThat(scanResultNoReal.pkgSetting.realName, nullValue());
    }

    @Test
    public void updateSimpleNominal() throws Exception {
        when(mMockUserManager.getUserIds()).thenReturn(new int[]{0});

        final PackageSetting pkgSetting = createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME)
                .setPrimaryCpuAbiString("primaryCpuAbi")
                .setSecondaryCpuAbiString("secondaryCpuAbi")
                .build();
        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .addScanFlag(PackageManagerService.SCAN_AS_FULL_APP)
                        .setPkgSetting(pkgSetting)
                        .build();


        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertThat(scanResult.existingSettingCopied, is(true));

        // ensure we don't overwrite the existing pkgSetting, in case something post-scan fails
        assertNotSame(pkgSetting, scanResult.pkgSetting);

        assertBasicPackageScanResult(scanResult, DUMMY_PACKAGE_NAME, false /*isInstant*/);

        assertThat(scanResult.pkgSetting.primaryCpuAbiString, is("primaryCpuAbi"));
        assertThat(scanResult.pkgSetting.secondaryCpuAbiString, is("secondaryCpuAbi"));
        assertThat(scanResult.pkgSetting.cpuAbiOverrideString, nullValue());

        assertPathsNotDerived(scanResult);
    }

    @Test
    public void updateInstantSimpleNominal() throws Exception {
        when(mMockUserManager.getUserIds()).thenReturn(new int[]{0});

        final PackageSetting existingPkgSetting =
                createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME)
                        .setInstantAppUserState(0, true)
                        .build();

        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .setPkgSetting(existingPkgSetting)
                        .build();


        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertBasicPackageScanResult(scanResult, DUMMY_PACKAGE_NAME, true /*isInstant*/);
    }

    @Test
    public void installStaticSharedLibrary() throws Exception {
        final ParsedPackage pkg = ((ParsedPackage) createBasicPackage("static.lib.pkg")
                .setStaticSharedLibName("static.lib")
                .setStaticSharedLibVersion(123L)
                .hideAsParsed())
                .setPackageName("static.lib.pkg.123")
                .setVersionCodeMajor(1)
                .setVersionCode(234)
                .setBaseCodePath("/some/path.apk")
                .setSplitCodePaths(new String[] {"/some/other/path.apk"});

        final PackageManagerService.ScanRequest scanRequest = new ScanRequestBuilder(pkg)
                .setUser(UserHandle.of(0)).build();


        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertThat(scanResult.staticSharedLibraryInfo.getPackageName(), is("static.lib.pkg.123"));
        assertThat(scanResult.staticSharedLibraryInfo.getName(), is("static.lib"));
        assertThat(scanResult.staticSharedLibraryInfo.getLongVersion(), is(123L));
        assertThat(scanResult.staticSharedLibraryInfo.getType(), is(TYPE_STATIC));
        assertThat(scanResult.staticSharedLibraryInfo.getDeclaringPackage().getPackageName(),
                is("static.lib.pkg"));
        assertThat(scanResult.staticSharedLibraryInfo.getDeclaringPackage().getLongVersionCode(),
                is(pkg.getLongVersionCode()));
        assertThat(scanResult.staticSharedLibraryInfo.getAllCodePaths(),
                hasItems("/some/path.apk", "/some/other/path.apk"));
        assertThat(scanResult.staticSharedLibraryInfo.getDependencies(), nullValue());
        assertThat(scanResult.staticSharedLibraryInfo.getDependentPackages(), empty());
    }

    @Test
    public void installDynamicLibraries() throws Exception {
        final ParsedPackage pkg = ((ParsedPackage) createBasicPackage(
                "dynamic.lib.pkg")
                .addLibraryName("liba")
                .addLibraryName("libb")
                .hideAsParsed())
                .setVersionCodeMajor(1)
                .setVersionCode(234)
                .setBaseCodePath("/some/path.apk")
                .setSplitCodePaths(new String[] {"/some/other/path.apk"});

        final PackageManagerService.ScanRequest scanRequest =
                new ScanRequestBuilder(pkg).setUser(UserHandle.of(0)).build();


        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        final SharedLibraryInfo dynamicLib0 = scanResult.dynamicSharedLibraryInfos.get(0);
        assertThat(dynamicLib0.getPackageName(), is("dynamic.lib.pkg"));
        assertThat(dynamicLib0.getName(), is("liba"));
        assertThat(dynamicLib0.getLongVersion(), is((long) VERSION_UNDEFINED));
        assertThat(dynamicLib0.getType(), is(TYPE_DYNAMIC));
        assertThat(dynamicLib0.getDeclaringPackage().getPackageName(), is("dynamic.lib.pkg"));
        assertThat(dynamicLib0.getDeclaringPackage().getLongVersionCode(),
                is(pkg.getLongVersionCode()));
        assertThat(dynamicLib0.getAllCodePaths(),
                hasItems("/some/path.apk", "/some/other/path.apk"));
        assertThat(dynamicLib0.getDependencies(), nullValue());
        assertThat(dynamicLib0.getDependentPackages(), empty());

        final SharedLibraryInfo dynamicLib1 = scanResult.dynamicSharedLibraryInfos.get(1);
        assertThat(dynamicLib1.getPackageName(), is("dynamic.lib.pkg"));
        assertThat(dynamicLib1.getName(), is("libb"));
        assertThat(dynamicLib1.getLongVersion(), is((long) VERSION_UNDEFINED));
        assertThat(dynamicLib1.getType(), is(TYPE_DYNAMIC));
        assertThat(dynamicLib1.getDeclaringPackage().getPackageName(), is("dynamic.lib.pkg"));
        assertThat(dynamicLib1.getDeclaringPackage().getLongVersionCode(),
                is(pkg.getLongVersionCode()));
        assertThat(dynamicLib1.getAllCodePaths(),
                hasItems("/some/path.apk", "/some/other/path.apk"));
        assertThat(dynamicLib1.getDependencies(), nullValue());
        assertThat(dynamicLib1.getDependentPackages(), empty());
    }

    @Test
    public void volumeUuidChangesOnUpdate() throws Exception {
        final PackageSetting pkgSetting =
                createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME)
                        .setVolumeUuid("someUuid")
                        .build();

        final ParsedPackage basicPackage = ((ParsedPackage) createBasicPackage(DUMMY_PACKAGE_NAME)
                .setVolumeUuid(UUID_TWO.toString())
                .hideAsParsed());


        final PackageManagerService.ScanResult scanResult = executeScan(
                new ScanRequestBuilder(basicPackage).setPkgSetting(pkgSetting).build());

        assertThat(scanResult.pkgSetting.volumeUuid, is(UUID_TWO.toString()));
    }

    @Test
    public void scanFirstBoot_derivesAbis() throws Exception {
        final PackageSetting pkgSetting =
                createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME).build();

        final ParsedPackage basicPackage =
                ((ParsedPackage) createBasicPackage(DUMMY_PACKAGE_NAME)
                        .hideAsParsed());


        final PackageManagerService.ScanResult scanResult = executeScan(new ScanRequestBuilder(
                basicPackage)
                .setPkgSetting(pkgSetting)
                .addScanFlag(SCAN_FIRST_BOOT_OR_UPGRADE)
                .build());

        assertAbiAndPathssDerived(scanResult);
    }

    @Test
    public void scanWithOriginalPkgSetting_packageNameChanges() throws Exception {
        final PackageSetting originalPkgSetting =
                createBasicPackageSettingBuilder("original.package").build();

        final ParsedPackage basicPackage =
                (ParsedPackage) createBasicPackage(DUMMY_PACKAGE_NAME)
                        .hideAsParsed();


        final PackageManagerService.ScanResult result =
                executeScan(new ScanRequestBuilder(basicPackage)
                        .setOriginalPkgSetting(originalPkgSetting)
                        .build());

        assertThat(result.request.parsedPackage.getPackageName(), is("original.package"));
    }

    @Test
    public void updateInstant_changeToFull() throws Exception {
        when(mMockUserManager.getUserIds()).thenReturn(new int[]{0});

        final PackageSetting existingPkgSetting =
                createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME)
                        .setInstantAppUserState(0, true)
                        .build();

        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .setPkgSetting(existingPkgSetting)
                        .addScanFlag(SCAN_AS_FULL_APP)
                        .build();


        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertBasicPackageScanResult(scanResult, DUMMY_PACKAGE_NAME, false /*isInstant*/);
    }

    @Test
    public void updateFull_changeToInstant() throws Exception {
        when(mMockUserManager.getUserIds()).thenReturn(new int[]{0});

        final PackageSetting existingPkgSetting =
                createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME)
                        .setInstantAppUserState(0, false)
                        .build();

        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .setPkgSetting(existingPkgSetting)
                        .addScanFlag(SCAN_AS_INSTANT_APP)
                        .build();


        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertBasicPackageScanResult(scanResult, DUMMY_PACKAGE_NAME, true /*isInstant*/);
    }

    @Test
    public void updateSystemApp_applicationInfoFlagSet() throws Exception {
        final PackageSetting existingPkgSetting =
                createBasicPackageSettingBuilder(DUMMY_PACKAGE_NAME)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM)
                        .build();

        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(createBasicPackage(DUMMY_PACKAGE_NAME))
                        .setPkgSetting(existingPkgSetting)
                        .setDisabledPkgSetting(existingPkgSetting)
                        .addScanFlag(SCAN_NEW_INSTALL)
                        .build();

        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        int appInfoFlags = PackageInfoUtils.appInfoFlags(scanResult.request.parsedPackage,
                scanResult.pkgSetting);
        assertThat(appInfoFlags, hasFlag(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
    }

    @Test
    public void factoryTestFlagSet() throws Exception {
        final ParsingPackage basicPackage = createBasicPackage(DUMMY_PACKAGE_NAME)
                .addRequestedPermission(Manifest.permission.FACTORY_TEST);

        final PackageManagerService.ScanResult scanResult = PackageManagerService.scanPackageOnlyLI(
                createBasicScanRequestBuilder(basicPackage).build(),
                mMockInjector,
                true /*isUnderFactoryTest*/,
                System.currentTimeMillis());

        int appInfoFlags = PackageInfoUtils.appInfoFlags(scanResult.request.parsedPackage,
                scanResult.request.pkgSetting);
        assertThat(appInfoFlags, hasFlag(ApplicationInfo.FLAG_FACTORY_TEST));
    }

    @Test
    public void scanSystemApp_isOrphanedTrue() throws Exception {
        final ParsedPackage pkg = ((ParsedPackage) createBasicPackage(DUMMY_PACKAGE_NAME)
                .hideAsParsed())
                .setSystem(true);

        final PackageManagerService.ScanRequest scanRequest =
                createBasicScanRequestBuilder(pkg)
                        .build();

        final PackageManagerService.ScanResult scanResult = executeScan(scanRequest);

        assertThat(scanResult.pkgSetting.installSource.isOrphaned, is(true));
    }

    private static Matcher<Integer> hasFlag(final int flag) {
        return new BaseMatcher<Integer>() {
            @Override public void describeTo(Description description) {
                description.appendText("flags ");
            }

            @Override public boolean matches(Object item) {
                return ((int) item & flag) != 0;
            }

            @Override
            public void describeMismatch(Object item, Description mismatchDescription) {
                mismatchDescription
                        .appendValue(item)
                        .appendText(" does not contain flag ")
                        .appendValue(flag);
            }
        };
    }

    private PackageManagerService.ScanResult executeScan(
            PackageManagerService.ScanRequest scanRequest) throws PackageManagerException {
        return PackageManagerService.scanPackageOnlyLI(
                scanRequest,
                mMockInjector,
                false /*isUnderFactoryTest*/,
                System.currentTimeMillis());
    }

    private static String createCodePath(String packageName) {
        return "/data/app/" + packageName + "-randompath";
    }

    private static PackageSettingBuilder createBasicPackageSettingBuilder(String packageName) {
        return new PackageSettingBuilder()
                .setName(packageName)
                .setCodePath(createCodePath(packageName))
                .setResourcePath(createCodePath(packageName));
    }

    private static ScanRequestBuilder createBasicScanRequestBuilder(ParsingPackage pkg) {
        return new ScanRequestBuilder((ParsedPackage) pkg.hideAsParsed())
                .setUser(UserHandle.of(0));
    }

    private static ScanRequestBuilder createBasicScanRequestBuilder(ParsedPackage pkg) {
        return new ScanRequestBuilder(pkg)
                .setUser(UserHandle.of(0));
    }

    private static ParsingPackage createBasicPackage(String packageName) {
        // TODO(b/135203078): Make this use PackageImpl.forParsing and separate the steps
        return (ParsingPackage) ((ParsedPackage) new PackageImpl(packageName,
                "/data/tmp/randompath/base.apk", createCodePath(packageName),
                mock(TypedArray.class), false)
                .setVolumeUuid(UUID_ONE.toString())
                .addUsesStaticLibrary("some.static.library")
                .addUsesStaticLibraryVersion(234L)
                .addUsesStaticLibrary("some.other.static.library")
                .addUsesStaticLibraryVersion(456L)
                .hideAsParsed())
                .setNativeLibraryRootDir("/data/tmp/randompath/base.apk:/lib")
                .setVersionCodeMajor(1)
                .setVersionCode(2345);
    }

    private static void assertBasicPackageScanResult(
            PackageManagerService.ScanResult scanResult, String packageName, boolean isInstant) {
        assertThat(scanResult.success, is(true));

        final PackageSetting pkgSetting = scanResult.pkgSetting;
        assertBasicPackageSetting(scanResult, packageName, isInstant, pkgSetting);

        final ApplicationInfo applicationInfo = PackageInfoUtils.generateApplicationInfo(
                pkgSetting.pkg, 0, pkgSetting.readUserState(0), 0, pkgSetting);
        assertBasicApplicationInfo(scanResult, applicationInfo);
    }

    private static void assertBasicPackageSetting(PackageManagerService.ScanResult scanResult,
            String packageName, boolean isInstant, PackageSetting pkgSetting) {
        assertThat(pkgSetting.pkg.getPackageName(), is(packageName));
        assertThat(pkgSetting.getInstantApp(0), is(isInstant));
        assertThat(pkgSetting.usesStaticLibraries,
                arrayContaining("some.static.library", "some.other.static.library"));
        assertThat(pkgSetting.usesStaticLibrariesVersions, is(new long[]{234L, 456L}));
        assertThat(pkgSetting.pkg, is(scanResult.request.parsedPackage));
        assertThat(pkgSetting.codePath, is(new File(createCodePath(packageName))));
        assertThat(pkgSetting.resourcePath, is(new File(createCodePath(packageName))));
        assertThat(pkgSetting.versionCode, is(PackageInfo.composeLongVersionCode(1, 2345)));
    }

    private static void assertBasicApplicationInfo(PackageManagerService.ScanResult scanResult,
            ApplicationInfo applicationInfo) {
        assertThat(applicationInfo.processName,
                is(scanResult.request.parsedPackage.getPackageName()));

        final int uid = applicationInfo.uid;
        assertThat(UserHandle.getUserId(uid), is(UserHandle.USER_SYSTEM));

        final String calculatedCredentialId = Environment.getDataUserCePackageDirectory(
                applicationInfo.volumeUuid, UserHandle.USER_SYSTEM,
                scanResult.request.parsedPackage.getPackageName()).getAbsolutePath();
        assertThat(applicationInfo.credentialProtectedDataDir, is(calculatedCredentialId));
        assertThat(applicationInfo.dataDir, is(applicationInfo.credentialProtectedDataDir));
    }

    private static void assertAbiAndPathssDerived(PackageManagerService.ScanResult scanResult) {
        PackageSetting pkgSetting = scanResult.pkgSetting;
        final ApplicationInfo applicationInfo = PackageInfoUtils.generateApplicationInfo(
                pkgSetting.pkg, 0, pkgSetting.readUserState(0), 0, pkgSetting);
        assertThat(applicationInfo.primaryCpuAbi, is("derivedPrimary"));
        assertThat(applicationInfo.secondaryCpuAbi, is("derivedSecondary"));

        assertThat(applicationInfo.nativeLibraryRootDir, is("derivedRootDir"));
        assertThat(pkgSetting.legacyNativeLibraryPathString, is("derivedRootDir"));
        assertThat(applicationInfo.nativeLibraryRootRequiresIsa, is(true));
        assertThat(applicationInfo.nativeLibraryDir, is("derivedNativeDir"));
        assertThat(applicationInfo.secondaryNativeLibraryDir, is("derivedNativeDir2"));
    }

    private static void assertPathsNotDerived(PackageManagerService.ScanResult scanResult) {
        PackageSetting pkgSetting = scanResult.pkgSetting;
        final ApplicationInfo applicationInfo = PackageInfoUtils.generateApplicationInfo(
                pkgSetting.pkg, 0, pkgSetting.readUserState(0), 0, pkgSetting);
        assertThat(applicationInfo.nativeLibraryRootDir, is("getRootDir"));
        assertThat(pkgSetting.legacyNativeLibraryPathString, is("getRootDir"));
        assertThat(applicationInfo.nativeLibraryRootRequiresIsa, is(true));
        assertThat(applicationInfo.nativeLibraryDir, is("getNativeDir"));
        assertThat(applicationInfo.secondaryNativeLibraryDir, is("getNativeDir2"));
    }
}
