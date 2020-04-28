/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.content.pm.dex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.Package;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.os.FileUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import libcore.testing.io.TestIoUtils;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DexMetadataHelperTest {
    private static final String APK_FILE_EXTENSION = ".apk";
    private static final String DEX_METADATA_FILE_EXTENSION = ".dm";

    private File mTmpDir = null;

    @Before
    public void setUp() {
        mTmpDir = TestIoUtils.createTemporaryDirectory("DexMetadataHelperTest");
    }

    @After
    public void tearDown() {
        if (mTmpDir != null) {
            File[] files = mTmpDir.listFiles();
            for (File f : files) {
                f.delete();
            }
        }
    }

    private File createDexMetadataFile(String apkFileName) throws IOException {
        File dmFile = new File(mTmpDir, apkFileName.replace(APK_FILE_EXTENSION,
                DEX_METADATA_FILE_EXTENSION));
        try (FileOutputStream fos = new FileOutputStream(dmFile)) {
            try (ZipOutputStream zipOs = new ZipOutputStream(fos)) {
                zipOs.putNextEntry(new ZipEntry("primary.prof"));
                zipOs.closeEntry();
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

    @Test
    public void testParsePackageWithDmFileValid() throws IOException, PackageParserException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("install_split_base.apk");
        Package pkg = new PackageParser().parsePackage(mTmpDir, 0 /* flags */);

        Map<String, String> packageDexMetadata = DexMetadataHelper.getPackageDexMetadata(pkg);
        assertEquals(1, packageDexMetadata.size());
        String baseDexMetadata = packageDexMetadata.get(pkg.baseCodePath);
        assertNotNull(baseDexMetadata);
        assertTrue(isDexMetadataForApk(baseDexMetadata, pkg.baseCodePath));
    }

    @Test
    public void testParsePackageSplitsWithDmFileValid()
            throws IOException, PackageParserException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_base.apk");
        createDexMetadataFile("install_split_feature_a.apk");
        Package pkg = new PackageParser().parsePackage(mTmpDir, 0 /* flags */);

        Map<String, String> packageDexMetadata = DexMetadataHelper.getPackageDexMetadata(pkg);
        assertEquals(2, packageDexMetadata.size());
        String baseDexMetadata = packageDexMetadata.get(pkg.baseCodePath);
        assertNotNull(baseDexMetadata);
        assertTrue(isDexMetadataForApk(baseDexMetadata, pkg.baseCodePath));

        String splitDexMetadata = packageDexMetadata.get(pkg.splitCodePaths[0]);
        assertNotNull(splitDexMetadata);
        assertTrue(isDexMetadataForApk(splitDexMetadata, pkg.splitCodePaths[0]));
    }

    @Test
    public void testParsePackageSplitsNoBaseWithDmFileValid()
            throws IOException, PackageParserException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_feature_a.apk");
        Package pkg = new PackageParser().parsePackage(mTmpDir, 0 /* flags */);

        Map<String, String> packageDexMetadata = DexMetadataHelper.getPackageDexMetadata(pkg);
        assertEquals(1, packageDexMetadata.size());

        String splitDexMetadata = packageDexMetadata.get(pkg.splitCodePaths[0]);
        assertNotNull(splitDexMetadata);
        assertTrue(isDexMetadataForApk(splitDexMetadata, pkg.splitCodePaths[0]));
    }

    @Test
    public void testParsePackageWithDmFileInvalid() throws IOException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        File invalidDmFile = new File(mTmpDir, "install_split_base.dm");
        Files.createFile(invalidDmFile.toPath());
        try {
            PackageParser.Package pkg = new PackageParser().parsePackage(mTmpDir, 0 /* flags */);
            DexMetadataHelper.validatePackageDexMetadata(pkg);
        } catch (PackageParserException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testParsePackageSplitsWithDmFileInvalid()
            throws IOException, PackageParserException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_base.apk");
        File invalidDmFile = new File(mTmpDir, "install_split_feature_a.dm");
        Files.createFile(invalidDmFile.toPath());

        try {
            PackageParser.Package pkg = new PackageParser().parsePackage(mTmpDir, 0 /* flags */);
            DexMetadataHelper.validatePackageDexMetadata(pkg);
        } catch (PackageParserException e) {
            assertEquals(e.error, PackageManager.INSTALL_FAILED_BAD_DEX_METADATA);
        }
    }

    @Test
    public void testPackageWithDmFileNoMatch() throws IOException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        createDexMetadataFile("non_existent.apk");

        try {
            DexMetadataHelper.validateDexPaths(mTmpDir.list());
            fail("Should fail validation");
        } catch (IllegalStateException e) {
            // expected.
        }
    }

    @Test
    public void testPackageSplitsWithDmFileNoMatch()
            throws IOException, PackageParserException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        copyApkToToTmpDir("install_split_feature_a.apk", R.raw.install_split_feature_a);
        createDexMetadataFile("install_split_base.apk");
        createDexMetadataFile("install_split_feature_a.mistake.apk");

        try {
            DexMetadataHelper.validateDexPaths(mTmpDir.list());
            fail("Should fail validation");
        } catch (IllegalStateException e) {
            // expected.
        }
    }

    @Test
    public void testPackageSizeWithDmFile()
            throws IOException, PackageParserException {
        copyApkToToTmpDir("install_split_base.apk", R.raw.install_split_base);
        File dm = createDexMetadataFile("install_split_base.apk");
        PackageParser.PackageLite pkg = new PackageParser().parsePackageLite(mTmpDir,
                0 /* flags */);

        Assert.assertEquals(dm.length(), DexMetadataHelper.getPackageDexMetadataSize(pkg));
    }

    // This simulates the 'adb shell pm install' flow.
    @Test
    public void testPackageSizeWithPartialPackageLite() throws IOException, PackageParserException {
        File base = copyApkToToTmpDir("install_split_base", R.raw.install_split_base);
        File dm = createDexMetadataFile("install_split_base.apk");
        try (FileInputStream is = new FileInputStream(base)) {
            ApkLite baseApk = PackageParser.parseApkLite(is.getFD(), base.getAbsolutePath(), 0);
            PackageLite pkgLite = new PackageLite(null, baseApk, null, null, null, null,
                    null, null);
            Assert.assertEquals(dm.length(), DexMetadataHelper.getPackageDexMetadataSize(pkgLite));
        }

    }

    private static boolean isDexMetadataForApk(String dmaPath, String apkPath) {
        return apkPath.substring(0, apkPath.length() - APK_FILE_EXTENSION.length()).equals(
                dmaPath.substring(0, dmaPath.length() - DEX_METADATA_FILE_EXTENSION.length()));
    }
}
