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

package com.android.server.pm.dex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.FileUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.PackageManagerException;
import com.android.server.pm.parsing.TestPackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DexMetadataHelperTest {
    private static final String APK_FILE_EXTENSION = ".apk";
    private static final String DEX_METADATA_FILE_EXTENSION = ".dm";
    private static final String DEX_METADATA_PACKAGE_NAME =
            "com.android.frameworks.servicestests.install_split";
    private static final long DEX_METADATA_VERSION_CODE = 9001;

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mTmpDir = null;

    @Before
    public void setUp() throws IOException {
        mTmpDir = mTemporaryFolder.newFolder("DexMetadataHelperTest");
    }

    private File createDexMetadataFile(String apkFileName) throws IOException {
        return createDexMetadataFile(apkFileName, /*validManifest=*/true);
    }

    private File createDexMetadataFile(String apkFileName, boolean validManifest) throws IOException
            {
        return createDexMetadataFile(apkFileName,DEX_METADATA_PACKAGE_NAME,
                DEX_METADATA_VERSION_CODE, /*emptyManifest=*/false, validManifest);
    }

    private File createDexMetadataFile(String apkFileName, String packageName, Long versionCode,
            boolean emptyManifest, boolean validManifest) throws IOException {
        File dmFile = new File(mTmpDir, apkFileName.replace(APK_FILE_EXTENSION,
                DEX_METADATA_FILE_EXTENSION));
        try (FileOutputStream fos = new FileOutputStream(dmFile)) {
            try (ZipOutputStream zipOs = new ZipOutputStream(fos)) {
                zipOs.putNextEntry(new ZipEntry("primary.prof"));
                zipOs.closeEntry();

                if (validManifest) {
                    zipOs.putNextEntry(new ZipEntry("manifest.json"));
                    if (!emptyManifest) {
                      String manifestStr = "{";

                      if (packageName != null) {
                          manifestStr += "\"packageName\": " + "\"" + packageName + "\"";

                          if (versionCode != null) {
                            manifestStr += ", ";
                          }
                      }
                      if (versionCode != null) {
                        manifestStr += " \"versionCode\": " + versionCode;
                      }

                      manifestStr += "}";
                      byte[] bytes = manifestStr.getBytes(StandardCharsets.UTF_8);
                      zipOs.write(bytes, /*off=*/0, /*len=*/bytes.length);
                    }
                    zipOs.closeEntry();
                }
            }
        }
        return dmFile;
    }

    private File copyApkToToTmpDir(String apkFileName, int apkResourceId) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File outFile = new File(mTmpDir, apkFileName);
        try (InputStream is = context.getResources().openRawResource(apkResourceId)) {
            FileUtils.copyToFileOrThrow(is, outFile);
        }
        return outFile;
    }

    private static void validatePackageDexMetadata(AndroidPackage pkg, boolean requireManifest)
            throws PackageManagerException {
        Collection<String> apkToDexMetadataList =
                AndroidPackageUtils.getPackageDexMetadata(pkg).values();
        String packageName = pkg.getPackageName();
        long versionCode = pkg.toAppInfoWithoutState().longVersionCode;
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        for (String dexMetadata : apkToDexMetadataList) {
            final ParseResult result = DexMetadataHelper.validateDexMetadataFile(
                    input.reset(), dexMetadata, packageName, versionCode, requireManifest);
            if (result.isError()) {
                throw new PackageManagerException(
                        result.getErrorCode(), result.getErrorMessage(), result.getException());
            }
        }
    }

    private static void validatePackageDexMetatadataVaryingRequireManifest(ParsedPackage pkg)
            throws PackageManagerException {
        validatePackageDexMetadata(pkg, /*requireManifest=*/true);
        validatePackageDexMetadata(pkg, /*requireManifest=*/false);
    }

    @Test
    public void testParsePackageWithDmFileValid() throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk");
        ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);

        Map<String, String> packageDexMetadata = AndroidPackageUtils.getPackageDexMetadata(pkg);
        assertEquals(1, packageDexMetadata.size());
        String baseDexMetadata = packageDexMetadata.get(pkg.getBaseApkPath());
        assertNotNull(baseDexMetadata);
        assertTrue(isDexMetadataForApk(baseDexMetadata, pkg.getBaseApkPath()));

        // Should throw no exceptions.
        validatePackageDexMetatadataVaryingRequireManifest(pkg);
    }

    @Test
    public void testParsePackageSplitsWithDmFileValid()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_base.apk");
        createDexMetadataFile("install_split_feature_a.apk");
        ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);

        Map<String, String> packageDexMetadata = AndroidPackageUtils.getPackageDexMetadata(pkg);
        assertEquals(2, packageDexMetadata.size());
        String baseDexMetadata = packageDexMetadata.get(pkg.getBaseApkPath());
        assertNotNull(baseDexMetadata);
        assertTrue(isDexMetadataForApk(baseDexMetadata, pkg.getBaseApkPath()));

        String splitDexMetadata = packageDexMetadata.get(pkg.getSplitCodePaths()[0]);
        assertNotNull(splitDexMetadata);
        assertTrue(isDexMetadataForApk(splitDexMetadata, pkg.getSplitCodePaths()[0]));

        // Should throw no exceptions.
        validatePackageDexMetatadataVaryingRequireManifest(pkg);
    }

    @Test
    public void testParsePackageSplitsNoBaseWithDmFileValid()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_feature_a.apk");
        ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);

        Map<String, String> packageDexMetadata = AndroidPackageUtils.getPackageDexMetadata(pkg);
        assertEquals(1, packageDexMetadata.size());

        String splitDexMetadata = packageDexMetadata.get(pkg.getSplitCodePaths()[0]);
        assertNotNull(splitDexMetadata);
        assertTrue(isDexMetadataForApk(splitDexMetadata, pkg.getSplitCodePaths()[0]));

        // Should throw no exceptions.
        validatePackageDexMetatadataVaryingRequireManifest(pkg);
    }

    @Test
    public void testParsePackageWithDmFileInvalid() throws IOException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        File invalidDmFile = new File(mTmpDir, "install_split_base.dm");
        Files.createFile(invalidDmFile.toPath());
        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: empty .dm file");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/false);
            fail("Should fail validation: empty .dm file");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageSplitsWithDmFileInvalid()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_base.apk");
        File invalidDmFile = new File(mTmpDir, "install_split_feature_a.dm");
        Files.createFile(invalidDmFile.toPath());

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: empty .dm file");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/false);
            fail("Should fail validation: empty .dm file");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageWithDmFileInvalidManifest()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk", /*validManifest=*/false);

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: missing manifest.json in the .dm archive");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageWithDmFileEmptyManifest()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk", /*packageName=*/"doesn't matter",
                /*versionCode=*/-12345L, /*emptyManifest=*/true, /*validManifest=*/true);

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: empty manifest.json in the .dm archive");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageWithDmFileBadPackageName()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk", /*packageName=*/"bad package name",
                DEX_METADATA_VERSION_CODE, /*emptyManifest=*/false, /*validManifest=*/true);

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: bad package name in the .dm archive");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageWithDmFileBadVersionCode()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk", DEX_METADATA_PACKAGE_NAME,
                /*versionCode=*/12345L, /*emptyManifest=*/false, /*validManifest=*/true);

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: bad version code in the .dm archive");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageWithDmFileMissingPackageName()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk", /*packageName=*/null,
                DEX_METADATA_VERSION_CODE, /*emptyManifest=*/false, /*validManifest=*/true);

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: missing package name in the .dm archive");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageWithDmFileMissingVersionCode()
            throws IOException, PackageManagerException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk", DEX_METADATA_PACKAGE_NAME,
                /*versionCode=*/null, /*emptyManifest=*/false, /*validManifest=*/true);

        try {
            ParsedPackage pkg = new TestPackageParser2().parsePackage(mTmpDir, /*flags=*/0, false);
            validatePackageDexMetadata(pkg, /*requireManifest=*/true);
            fail("Should fail validation: missing version code in the .dm archive");
        } catch (PackageManagerException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testPackageWithDmFileNoMatch() throws IOException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("non_existent.apk");

        try {
            DexMetadataHelper.validateDexPaths(mTmpDir.list());
            fail("Should fail validation: .dm filename has no match against .apk");
        } catch (IllegalStateException e) {
            // expected.
        }
    }

    @Test
    public void testPackageSplitsWithDmFileNoMatch()
            throws IOException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_base.apk");
        createDexMetadataFile("install_split_feature_a.mistake.apk");

        try {
            DexMetadataHelper.validateDexPaths(mTmpDir.list());
            fail("Should fail validation: split .dm filename unmatched against .apk");
        } catch (IllegalStateException e) {
            // expected.
        }
    }

    @Test
    public void testPackageSizeWithDmFile() throws IOException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        final File dm = createDexMetadataFile("install_split_base.apk");
        final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                ParseTypeImpl.forDefaultParsing().reset(), mTmpDir, /*flags=*/0);
        if (result.isError()) {
            throw new IllegalStateException(result.getErrorMessage(), result.getException());
        }
        final PackageLite pkg = result.getResult();
        Assert.assertEquals(dm.length(), DexMetadataHelper.getPackageDexMetadataSize(pkg));
    }

    // This simulates the 'adb shell pm install' flow.
    @Test
    public void testPackageSizeWithPartialPackageLite() throws IOException,
            PackageManagerException {
        final File base = copyApkToToTmpDir("install_split_base", R.raw.install_split_base);
        final File dm = createDexMetadataFile("install_split_base.apk");
        try (FileInputStream is = new FileInputStream(base)) {
            final ParseResult<ApkLite> result = ApkLiteParseUtils.parseApkLite(
                    ParseTypeImpl.forDefaultParsing().reset(), is.getFD(),
                    base.getAbsolutePath(), /*flags=*/0);
            if (result.isError()) {
                throw new PackageManagerException(result.getErrorCode(),
                        result.getErrorMessage(), result.getException());
            }
            final ApkLite baseApk = result.getResult();
            final PackageLite pkgLite = new PackageLite(null, baseApk.getPath(), baseApk,
                    null /* splitNames */, null /* isFeatureSplits */, null /* usesSplitNames */,
                    null /* configForSplit */, null /* splitApkPaths */,
                    null /* splitRevisionCodes */, baseApk.getTargetSdkVersion(),
                    null /* requiredSplitTypes */, null /* splitTypes */);
            Assert.assertEquals(dm.length(), DexMetadataHelper.getPackageDexMetadataSize(pkgLite));
        }

    }

    private static boolean isDexMetadataForApk(String dmaPath, String apkPath) {
        return apkPath.substring(0, apkPath.length() - APK_FILE_EXTENSION.length()).equals(
                dmaPath.substring(0, dmaPath.length() - DEX_METADATA_FILE_EXTENSION.length()));
    }
}
