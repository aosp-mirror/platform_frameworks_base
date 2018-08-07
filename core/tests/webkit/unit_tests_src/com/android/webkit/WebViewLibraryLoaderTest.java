/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.webkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import android.support.test.filters.MediumTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.InstrumentationRegistry;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link WebViewLibraryLoader}.
 * Use the following command to run these tests:
 * make WebViewLoadingTests \
 * && adb install -r -d \
 * ${ANDROID_PRODUCT_OUT}/data/app/WebViewLoadingTests/WebViewLoadingTests.apk \
 * && adb shell am instrument -e class 'android.webkit.WebViewLibraryLoaderTest' -w \
 * 'com.android.webkit.tests/android.support.test.runner.AndroidJUnitRunner'
 */
@RunWith(AndroidJUnit4.class)
public final class WebViewLibraryLoaderTest {
    private static final String WEBVIEW_LIBS_ON_DISK_TEST_APK =
            "com.android.webviewloading_test_on_disk";
    private static final String WEBVIEW_LIBS_IN_APK_TEST_APK =
            "com.android.webviewloading_test_from_apk";
    private static final String WEBVIEW_LOADING_TEST_NATIVE_LIB = "libwebviewtest_jni.so";

    private PackageInfo webviewOnDiskPackageInfo;
    private PackageInfo webviewFromApkPackageInfo;

    @Before public void setUp() throws PackageManager.NameNotFoundException {
        PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
        webviewOnDiskPackageInfo =
                pm.getPackageInfo(WEBVIEW_LIBS_ON_DISK_TEST_APK, PackageManager.GET_META_DATA);
        webviewFromApkPackageInfo =
                pm.getPackageInfo(WEBVIEW_LIBS_IN_APK_TEST_APK, PackageManager.GET_META_DATA);
    }

    private static boolean is64BitDevice() {
        return Build.SUPPORTED_64_BIT_ABIS.length > 0;
    }

    // We test the getWebViewNativeLibraryDirectory method here because it handled several different
    // cases/combinations and it seems unnecessary to create one test-apk for each such combination
    // and arch.

    /**
     * Ensure we fetch the correct native library directories in the multi-arch case where
     * the primary ABI is 64-bit.
     */
    @SmallTest
    @Test public void testGetWebViewLibDirMultiArchPrimary64bit() {
        final String nativeLib = "nativeLib";
        final String secondaryNativeLib = "secondaryNativeLib";
        PackageInfo packageInfo = new PackageInfo();
        ApplicationInfo ai = new ApplicationInfoBuilder().
                // See VMRuntime.ABI_TO_INSTRUCTION_SET_MAP
                setPrimaryCpuAbi("arm64-v8a").
                setNativeLibraryDir(nativeLib).
                setSecondaryCpuAbi("armeabi").
                setSecondaryNativeLibraryDir(secondaryNativeLib).
                create();
        packageInfo.applicationInfo = ai;
        String actual32Lib =
                WebViewLibraryLoader.getWebViewNativeLibraryDirectory(ai, false /* is64bit */);
        String actual64Lib =
                WebViewLibraryLoader.getWebViewNativeLibraryDirectory(ai, true /* is64bit */);
        assertEquals(nativeLib, actual64Lib);
        assertEquals(secondaryNativeLib, actual32Lib);
    }

    /**
     * Ensure we fetch the correct native library directory in the 64-bit single-arch case.
     */
    @SmallTest
    @Test public void testGetWebViewLibDirSingleArch64bit() {
        final String nativeLib = "nativeLib";
        PackageInfo packageInfo = new PackageInfo();
        ApplicationInfo ai = new ApplicationInfoBuilder().
                // See VMRuntime.ABI_TO_INSTRUCTION_SET_MAP
                setPrimaryCpuAbi("arm64-v8a").
                setNativeLibraryDir(nativeLib).
                create();
        packageInfo.applicationInfo = ai;
        String actual64Lib =
                WebViewLibraryLoader.getWebViewNativeLibraryDirectory(ai, true /* is64bit */);
        assertEquals(nativeLib, actual64Lib);
    }

    /**
     * Ensure we fetch the correct native library directory in the 32-bit single-arch case.
     */
    @SmallTest
    @Test public void testGetWebViewLibDirSingleArch32bit() {
        final String nativeLib = "nativeLib";
        PackageInfo packageInfo = new PackageInfo();
        ApplicationInfo ai = new ApplicationInfoBuilder().
                // See VMRuntime.ABI_TO_INSTRUCTION_SET_MAP
                setPrimaryCpuAbi("armeabi-v7a").
                setNativeLibraryDir(nativeLib).
                create();
        packageInfo.applicationInfo = ai;
        String actual32Lib =
                WebViewLibraryLoader.getWebViewNativeLibraryDirectory(ai, false /* is64bit */);
        assertEquals(nativeLib, actual32Lib);
    }

    /**
     * Ensure we fetch the correct 32-bit library path from an APK with 32-bit and 64-bit
     * libraries unzipped onto disk.
     */
    @MediumTest
    @Test public void testGetWebViewLibraryPathOnDisk32Bit()
            throws WebViewFactory.MissingWebViewPackageException {
        WebViewLibraryLoader.WebViewNativeLibrary actualNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewOnDiskPackageInfo, false /* is64bit */);
        String expectedLibaryDirectory = is64BitDevice() ?
                webviewOnDiskPackageInfo.applicationInfo.secondaryNativeLibraryDir :
                webviewOnDiskPackageInfo.applicationInfo.nativeLibraryDir;
        String lib32Path = expectedLibaryDirectory + "/" + WEBVIEW_LOADING_TEST_NATIVE_LIB;
        assertEquals("Fetched incorrect 32-bit path from WebView library.",
                lib32Path, actualNativeLib.path);
    }

    /**
     * Ensure we fetch the correct 64-bit library path from an APK with 32-bit and 64-bit
     * libraries unzipped onto disk.
     */
    @MediumTest
    @Test public void testGetWebViewLibraryPathOnDisk64Bit()
            throws WebViewFactory.MissingWebViewPackageException {
        // A 32-bit device will not unpack 64-bit libraries.
        if (!is64BitDevice()) return;

        WebViewLibraryLoader.WebViewNativeLibrary actualNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewOnDiskPackageInfo, true /* is64bit */);
        String lib64Path = webviewOnDiskPackageInfo.applicationInfo.nativeLibraryDir
                + "/" + WEBVIEW_LOADING_TEST_NATIVE_LIB;
        assertEquals("Fetched incorrect 64-bit path from WebView library.",
                lib64Path, actualNativeLib.path);
    }

    /**
     * Check the size of the 32-bit library fetched from an APK with both 32-bit and 64-bit
     * libraries unzipped onto disk.
     */
    @MediumTest
    @Test public void testGetWebView32BitLibrarySizeOnDiskIsNonZero()
            throws WebViewFactory.MissingWebViewPackageException {
        WebViewLibraryLoader.WebViewNativeLibrary actual32BitNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewOnDiskPackageInfo, false /* is64bit */);
        assertTrue(actual32BitNativeLib.size > 0);
    }

    /**
     * Check the size of the 64-bit library fetched from an APK with both 32-bit and 64-bit
     * libraries unzipped onto disk.
     */
    @MediumTest
    @Test public void testGetWebView64BitLibrarySizeOnDiskIsNonZero()
            throws WebViewFactory.MissingWebViewPackageException {
        // A 32-bit device will not unpack 64-bit libraries.
        if (!is64BitDevice()) return;
        WebViewLibraryLoader.WebViewNativeLibrary actual64BitNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewOnDiskPackageInfo, true /* is64bit */);
        assertTrue(actual64BitNativeLib.size > 0);
    }

    /**
     * Ensure we fetch the correct 32-bit library path from an APK with both 32-bit and 64-bit
     * libraries stored uncompressed in the APK.
     */
    @MediumTest
    @Test public void testGetWebView32BitLibraryPathFromApk()
            throws WebViewFactory.MissingWebViewPackageException, IOException {
        WebViewLibraryLoader.WebViewNativeLibrary actualNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewFromApkPackageInfo, false /* is64bit */);
        // The device might have ignored the app's request to not extract native libs, so first
        // check whether the library paths match those of extracted libraries.
        String expectedLibaryDirectory = is64BitDevice() ?
                webviewFromApkPackageInfo.applicationInfo.secondaryNativeLibraryDir :
                webviewFromApkPackageInfo.applicationInfo.nativeLibraryDir;
        String lib32Path = expectedLibaryDirectory + "/" + WEBVIEW_LOADING_TEST_NATIVE_LIB;
        if (lib32Path.equals(actualNativeLib.path)) {
            // If the libraries were extracted to disk, ensure that they're actually there.
            assertTrue("The given WebView library doesn't exist.",
                    new File(actualNativeLib.path).exists());
        } else { // The libraries were not extracted to disk.
            assertIsValidZipEntryPath(actualNativeLib.path,
                    webviewFromApkPackageInfo.applicationInfo.sourceDir);
        }
    }

    /**
     * Ensure we fetch the correct 32-bit library path from an APK with both 32-bit and 64-bit
     * libraries stored uncompressed in the APK.
     */
    @MediumTest
    @Test public void testGetWebView64BitLibraryPathFromApk()
            throws WebViewFactory.MissingWebViewPackageException, IOException {
        // A 32-bit device will not unpack 64-bit libraries.
        if (!is64BitDevice()) return;

        WebViewLibraryLoader.WebViewNativeLibrary actualNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewFromApkPackageInfo, true /* is64bit */);
        assertIsValidZipEntryPath(actualNativeLib.path,
                webviewFromApkPackageInfo.applicationInfo.sourceDir);
    }

    private static void assertIsValidZipEntryPath(String path, String zipFilePath)
            throws IOException {
        assertTrue("The path to a zip entry must start with the path to the zip file itself."
            + "Expected zip path: " + zipFilePath + ", actual zip entry: " + path,
            path.startsWith(zipFilePath + "!/"));
        String[] pathSplit = path.split("!/");
        assertEquals("A zip file path should have two parts, the zip path, and the zip entry path.",
                2, pathSplit.length);
        ZipFile zipFile = new ZipFile(pathSplit[0]);
        assertNotNull("Path doesn't point to a valid zip entry: " + path,
                zipFile.getEntry(pathSplit[1]));
    }


    /**
     * Check the size of the 32-bit library fetched from an APK with both 32-bit and 64-bit
     * libraries stored uncompressed in the APK.
     */
    @MediumTest
    @Test public void testGetWebView32BitLibrarySizeFromApkIsNonZero()
            throws WebViewFactory.MissingWebViewPackageException {
        WebViewLibraryLoader.WebViewNativeLibrary actual32BitNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewFromApkPackageInfo, false /* is64bit */);
        assertTrue(actual32BitNativeLib.size > 0);
    }

    /**
     * Check the size of the 64-bit library fetched from an APK with both 32-bit and 64-bit
     * libraries stored uncompressed in the APK.
     */
    @MediumTest
    @Test public void testGetWebView64BitLibrarySizeFromApkIsNonZero()
            throws WebViewFactory.MissingWebViewPackageException {
        // A 32-bit device will not unpack 64-bit libraries.
        if (!is64BitDevice()) return;

        WebViewLibraryLoader.WebViewNativeLibrary actual64BitNativeLib =
                WebViewLibraryLoader.getWebViewNativeLibrary(
                        webviewFromApkPackageInfo, true /* is64bit */);
        assertTrue(actual64BitNativeLib.size > 0);
    }

    private static class ApplicationInfoBuilder {
        ApplicationInfo ai;

        public ApplicationInfoBuilder setPrimaryCpuAbi(String primaryCpuAbi) {
            ai.primaryCpuAbi = primaryCpuAbi;
            return this;
        }

        public ApplicationInfoBuilder setSecondaryCpuAbi(String secondaryCpuAbi) {
            ai.secondaryCpuAbi = secondaryCpuAbi;
            return this;
        }

        public ApplicationInfoBuilder setNativeLibraryDir(String nativeLibraryDir) {
            ai.nativeLibraryDir = nativeLibraryDir;
            return this;
        }

        public ApplicationInfoBuilder setSecondaryNativeLibraryDir(
                String secondaryNativeLibraryDir) {
            ai.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
            return this;
        }

        public ApplicationInfoBuilder setMetaData(Bundle metaData) {
            ai.metaData = metaData;
            return this;
        }

        public ApplicationInfoBuilder() {
            ai = new android.content.pm.ApplicationInfo();
        }

        public ApplicationInfo create() {
            return ai;
        }
    }
}
