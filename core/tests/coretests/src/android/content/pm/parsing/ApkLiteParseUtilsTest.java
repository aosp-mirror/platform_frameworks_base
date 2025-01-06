/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.parsing;

import static android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
import static android.content.pm.PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.SuppressLint;
import android.app.UiAutomation;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.util.PackageUtils;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;

import com.android.internal.pm.parsing.PackageParser2;
import com.android.server.pm.pkg.AndroidPackage;

import libcore.util.HexEncoding;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Presubmit
public class ApkLiteParseUtilsTest {

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private static final String PUSH_FILE_DIR = "/data/local/tmp/tests/coretests/pm/";
    private static final String TEST_APP_USING_SDK1_AND_SDK2 = "HelloWorldUsingSdk1And2.apk";
    private static final String TEST_APP_USING_SDK1_AND_SDK1 = "HelloWorldUsingSdk1AndSdk1.apk";
    private static final String TEST_APP_USING_SDK_MALFORMED_VERSION =
            "HelloWorldUsingSdkMalformedNegativeVersion.apk";
    private static final String TEST_STATIC_LIB_APP = "CtsStaticSharedLibProviderApp1.apk";
    private static final String TEST_APP_USING_STATIC_LIB = "CtsStaticSharedLibConsumerApp1.apk";
    private static final String TEST_APP_USING_STATIC_LIB_TWO_CERTS =
            "CtsStaticSharedLibConsumerApp3.apk";
    private static final String STATIC_LIB_CERT_1 =
            "70fbd440503ec0bf41f3f21fcc83ffd39880133c27deb0945ed677c6f31d72fb";
    private static final String STATIC_LIB_CERT_2 =
            "e49582ff3a0aa4c5589fc5feaac6b7d6e757199dd0c6742df7bf37c2ffef95f5";
    private static final String TEST_SDK1 = "HelloWorldSdk1.apk";
    private static final String TEST_SDK1_PACKAGE = "com.test.sdk1_1";
    private static final String TEST_SDK1_NAME = "com.test.sdk1";
    private static final long TEST_SDK1_VERSION = 1;
    private static final String TEST_SDK2_NAME = "com.test.sdk2";
    private static final long TEST_SDK2_VERSION = 2;

    private final PackageParser2 mPackageParser2 = new PackageParser2(
            null, null, null, new FakePackageParser2Callback());

    private File mTmpDir = null;

    @Before
    public void setUp() throws IOException {
        mTmpDir = mTemporaryFolder.newFolder("ApkLiteParseUtilsTest");
    }

    @After
    public void tearDown() throws Exception {
        setSystemProperty("debug.pm.uses_sdk_library_default_cert_digest", "invalid");
        uninstallPackageSilently(TEST_SDK1_NAME);
    }

    @SuppressLint("CheckResult")
    @Test
    public void testParseApkLite_getUsesSdkLibrary() throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_SDK1_AND_SDK2);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isFalse();

        ApkLite baseApk = result.getResult();
        assertThat(baseApk.getUsesSdkLibraries()).containsExactly(TEST_SDK1_NAME, TEST_SDK2_NAME);
        assertThat(baseApk.getUsesSdkLibrariesVersionsMajor()).asList().containsExactly(
                TEST_SDK1_VERSION, TEST_SDK2_VERSION
        );
        String[][] expectedCerts = {{""}, {""}};
        assertThat(baseApk.getUsesSdkLibrariesCertDigests()).isEqualTo(expectedCerts);
    }

    @SuppressLint("CheckResult")
    @Test
    public void testParseApkLite_getUsesSdkLibrary_overrideCertDigest() throws Exception {
        installPackage(TEST_SDK1);
        String certDigest = getPackageCertDigest(TEST_SDK1_PACKAGE);
        overrideUsesSdkLibraryCertificateDigest(certDigest);

        File apkFile = copyApkToTmpDir(TEST_APP_USING_SDK1_AND_SDK2);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        ApkLite baseApk = result.getResult();

        String[][] liteCerts = baseApk.getUsesSdkLibrariesCertDigests();
        String[][] expectedCerts = {{certDigest}, {certDigest}};
        assertThat(liteCerts).isEqualTo(expectedCerts);

        // Same for package parser
        AndroidPackage pkg = mPackageParser2.parsePackage(apkFile, 0, true).hideAsFinal();
        String[][] pkgCerts = pkg.getUsesSdkLibrariesCertDigests();
        assertThat(liteCerts).isEqualTo(pkgCerts);
    }


    @SuppressLint("CheckResult")
    @Test
    public void testParseApkLite_getUsesSdkLibrary_sameAsPackageParser() throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_SDK1_AND_SDK2);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isFalse();
        ApkLite baseApk = result.getResult();

        AndroidPackage pkg = mPackageParser2.parsePackage(apkFile, 0, true).hideAsFinal();
        assertThat(baseApk.getUsesSdkLibraries())
                .containsExactlyElementsIn(pkg.getUsesSdkLibraries());
        List<Long> versionsBoxed = Arrays.stream(pkg.getUsesSdkLibrariesVersionsMajor()).boxed()
                .toList();
        assertThat(baseApk.getUsesSdkLibrariesVersionsMajor()).asList()
                .containsExactlyElementsIn(versionsBoxed);

        String[][] liteCerts = baseApk.getUsesSdkLibrariesCertDigests();
        String[][] pkgCerts = pkg.getUsesSdkLibrariesCertDigests();
        assertThat(liteCerts).isEqualTo(pkgCerts);
    }

    @SuppressLint("CheckResult")
    @Test
    public void testParseApkLite_getUsesStaticLibrary_sameAsPackageParser() throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_STATIC_LIB);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isFalse();
        ApkLite baseApk = result.getResult();

        AndroidPackage pkg = mPackageParser2.parsePackage(apkFile, 0, true).hideAsFinal();
        assertThat(baseApk.getUsesStaticLibraries())
                .containsExactlyElementsIn(pkg.getUsesStaticLibraries());
        List<Long> versionsBoxed = Arrays.stream(pkg.getUsesStaticLibrariesVersions()).boxed()
                .toList();
        assertThat(baseApk.getUsesStaticLibrariesVersions()).asList()
                .containsExactlyElementsIn(versionsBoxed);

        String[][] liteCerts = baseApk.getUsesStaticLibrariesCertDigests();
        String[][] pkgCerts = pkg.getUsesStaticLibrariesCertDigests();
        assertThat(liteCerts).isEqualTo(pkgCerts);
    }

    @Test
    public void testParseApkLite_getUsesStaticLibrary_twoCerts()
            throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_STATIC_LIB_TWO_CERTS);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isFalse();
        ApkLite baseApk = result.getResult();

        // There are two certs.
        String[][] expectedCerts = {{STATIC_LIB_CERT_1, STATIC_LIB_CERT_2}};
        String[][] liteCerts = baseApk.getUsesStaticLibrariesCertDigests();
        assertThat(liteCerts).isEqualTo(expectedCerts);

        // And they are same as package parser.
        AndroidPackage pkg = mPackageParser2.parsePackage(apkFile, 0, true).hideAsFinal();
        String[][] pkgCerts = pkg.getUsesStaticLibrariesCertDigests();
        assertThat(liteCerts).isEqualTo(pkgCerts);
    }

    @Test
    public void testParseApkLite_isIsStaticLibrary() throws Exception {
        File apkFile = copyApkToTmpDir(TEST_STATIC_LIB_APP);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isFalse();
        ApkLite baseApk = result.getResult();

        assertThat(baseApk.isIsStaticLibrary()).isTrue();
    }

    @SuppressLint("CheckResult")
    @Test
    public void testParseApkLite_malformedUsesSdkLibrary_duplicate() throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_SDK1_AND_SDK1);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).contains("Bad uses-sdk-library declaration");
        assertThat(result.getErrorMessage()).contains(
                "Depending on multiple versions of SDK library");
    }

    @SuppressLint("CheckResult")
    @Test
    public void testParseApkLite_malformedUsesSdkLibrary_missingVersion() throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_SDK_MALFORMED_VERSION);
        ParseResult<ApkLite> result = ApkLiteParseUtils
                .parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).contains("Bad uses-sdk-library declaration");
    }

    private String getPackageCertDigest(String packageName) throws Exception {
        getUiAutomation().adoptShellPermissionIdentity();
        try {
            PackageInfo sdkPackageInfo = getPackageManager().getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(
                            GET_SIGNING_CERTIFICATES | MATCH_STATIC_SHARED_AND_SDK_LIBRARIES));
            SigningInfo signingInfo = sdkPackageInfo.signingInfo;
            Signature[] signatures =
                    signingInfo != null ? signingInfo.getSigningCertificateHistory() : null;
            byte[] digest = PackageUtils.computeSha256DigestBytes(signatures[0].toByteArray());
            return new String(HexEncoding.encode(digest));
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static PackageManager getPackageManager() {
        return InstrumentationRegistry.getContext().getPackageManager();
    }

    /**
     * SDK package is signed by build system. In theory we could try to extract the signature,
     * and patch the app manifest. This property allows us to override in runtime, which is much
     * easier.
     */
    private void overrideUsesSdkLibraryCertificateDigest(String sdkCertDigest) throws Exception {
        setSystemProperty("debug.pm.uses_sdk_library_default_cert_digest", sdkCertDigest);
    }

    private void setSystemProperty(String name, String value) throws Exception {
        assertThat(executeShellCommand("setprop " + name + " " + value)).isEmpty();
    }

    private static String executeShellCommand(String command) throws IOException {
        final ParcelFileDescriptor stdout = getUiAutomation().executeShellCommand(command);
        try (InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(stdout)) {
            return readFullStream(inputStream);
        }
    }

    private static String readFullStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        writeFullStream(inputStream, result, -1);
        return result.toString("UTF-8");
    }

    private static void writeFullStream(InputStream inputStream, OutputStream outputStream,
            long expected) throws IOException {
        byte[] buffer = new byte[1024];
        long total = 0;
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
            total += length;
        }
        if (expected > 0) {
            assertThat(expected).isEqualTo(total);
        }
    }

    private static UiAutomation getUiAutomation() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    private File copyApkToTmpDir(String apkFileName) throws Exception {
        File outFile = new File(mTmpDir, apkFileName);
        String apkFilePath = PUSH_FILE_DIR + apkFileName;
        File apkFile = new File(apkFilePath);
        assertThat(apkFile.exists()).isTrue();
        try (InputStream is = new FileInputStream(apkFile)) {
            FileUtils.copyToFileOrThrow(is, outFile);
        }
        return outFile;
    }

    static String createApkPath(String baseName) {
        return PUSH_FILE_DIR + baseName;
    }

    /* Install for all the users */
    private void installPackage(String baseName) throws IOException {
        File file = new File(createApkPath(baseName));
        assertThat(executeShellCommand("pm install -t -g " + file.getPath()))
                .isEqualTo("Success\n");
    }

    private static String uninstallPackageSilently(String packageName) throws IOException {
        return executeShellCommand("pm uninstall " + packageName);
    }

    static class FakePackageParser2Callback extends PackageParser2.Callback {

        @Override
        public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
            return true;
        }

        @Override
        public boolean hasFeature(String feature) {
            return true;
        }

        @Override
        public @NonNull Set<String> getHiddenApiWhitelistedApps() {
            return new ArraySet<>();
        }

        @Override
        public @NonNull Set<String> getInstallConstraintsAllowlist() {
            return new ArraySet<>();
        }
    }
}
