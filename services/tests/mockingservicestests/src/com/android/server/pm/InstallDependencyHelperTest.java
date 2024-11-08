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

package com.android.server.pm;

import static android.content.pm.Flags.FLAG_SDK_DEPENDENCY_INSTALLER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
@RunWith(JUnit4.class)
@RequiresFlagsEnabled(FLAG_SDK_DEPENDENCY_INSTALLER)
public class InstallDependencyHelperTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule public final CheckFlagsRule checkFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String PUSH_FILE_DIR = "/data/local/tmp/tests/smockingservicestest/pm/";
    private static final String TEST_APP_USING_SDK1_AND_SDK2 = "HelloWorldUsingSdk1And2.apk";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Mock private SharedLibrariesImpl mSharedLibraries;
    @Mock private Context mContext;
    @Mock private Computer mComputer;
    @Mock private PackageInstallerService mPackageInstallerService;
    private InstallDependencyHelper mInstallDependencyHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInstallDependencyHelper = new InstallDependencyHelper(mContext, mSharedLibraries,
                mPackageInstallerService);
    }

    @Test
    public void testResolveLibraryDependenciesIfNeeded_errorInSharedLibrariesImpl()
            throws Exception {
        doThrow(new PackageManagerException(new Exception("xyz")))
                .when(mSharedLibraries).collectMissingSharedLibraryInfos(any());

        PackageLite pkg = getPackageLite(TEST_APP_USING_SDK1_AND_SDK2);
        CallbackHelper callback = new CallbackHelper(/*expectSuccess=*/ false);
        mInstallDependencyHelper.resolveLibraryDependenciesIfNeeded(pkg, mComputer,
                0, mHandler, callback);
        callback.assertFailure();

        assertThat(callback.error).hasMessageThat().contains("xyz");
    }

    @Test
    public void testResolveLibraryDependenciesIfNeeded_failsToBind() throws Exception {
        // Return a non-empty list as missing dependency
        PackageLite pkg = getPackageLite(TEST_APP_USING_SDK1_AND_SDK2);
        List<SharedLibraryInfo> missingDependency = Collections.singletonList(
                mock(SharedLibraryInfo.class));
        when(mSharedLibraries.collectMissingSharedLibraryInfos(eq(pkg)))
                .thenReturn(missingDependency);

        CallbackHelper callback = new CallbackHelper(/*expectSuccess=*/ false);
        mInstallDependencyHelper.resolveLibraryDependenciesIfNeeded(pkg, mComputer,
                0, mHandler, callback);
        callback.assertFailure();

        assertThat(callback.error).hasMessageThat().contains(
                "Dependency Installer Service not found");
    }


    @Test
    public void testResolveLibraryDependenciesIfNeeded_allDependenciesInstalled() throws Exception {
        // Return an empty list as missing dependency
        PackageLite pkg = getPackageLite(TEST_APP_USING_SDK1_AND_SDK2);
        List<SharedLibraryInfo> missingDependency = Collections.emptyList();
        when(mSharedLibraries.collectMissingSharedLibraryInfos(eq(pkg)))
                .thenReturn(missingDependency);

        CallbackHelper callback = new CallbackHelper(/*expectSuccess=*/ true);
        mInstallDependencyHelper.resolveLibraryDependenciesIfNeeded(pkg, mComputer,
                0, mHandler, callback);
        callback.assertSuccess();
    }

    private static class CallbackHelper implements OutcomeReceiver<Void, PackageManagerException> {
        public PackageManagerException error;

        private final CountDownLatch mWait = new CountDownLatch(1);
        private final boolean mExpectSuccess;

        CallbackHelper(boolean expectSuccess) {
            mExpectSuccess = expectSuccess;
        }

        @Override
        public void onResult(Void result) {
            if (!mExpectSuccess) {
                fail("Expected to fail");
            }
            mWait.countDown();
        }

        @Override
        public void onError(@NonNull PackageManagerException e) {
            if (mExpectSuccess) {
                fail("Expected success but received: " + e);
            }
            error = e;
            mWait.countDown();
        }

        void assertSuccess() throws Exception {
            assertThat(mWait.await(1000, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(error).isNull();
        }

        void assertFailure() throws Exception {
            assertThat(mWait.await(1000, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(error).isNotNull();
        }

    }

    private PackageLite getPackageLite(String apkFileName) throws Exception {
        File apkFile = copyApkToTmpDir(TEST_APP_USING_SDK1_AND_SDK2);
        ParseResult<ApkLite> result = ApkLiteParseUtils.parseApkLite(
                ParseTypeImpl.forDefaultParsing().reset(), apkFile, 0);
        assertThat(result.isError()).isFalse();
        ApkLite baseApk = result.getResult();

        return new PackageLite(/*path=*/ null, baseApk.getPath(), baseApk,
                /*splitNames=*/ null, /*isFeatureSplits=*/ null, /*usesSplitNames=*/ null,
                /*configForSplit=*/ null, /*splitApkPaths=*/ null,
                /*splitRevisionCodes=*/ null, baseApk.getTargetSdkVersion(),
                /*requiredSplitTypes=*/ null, /*splitTypes=*/ null);
    }

    private File copyApkToTmpDir(String apkFileName) throws Exception {
        File outFile = temporaryFolder.newFile(apkFileName);
        String apkFilePath = PUSH_FILE_DIR + apkFileName;
        File apkFile = new File(apkFilePath);
        assertThat(apkFile.exists()).isTrue();
        try (InputStream is = new FileInputStream(apkFile)) {
            FileUtils.copyToFileOrThrow(is, outFile);
        }
        return outFile;
    }

}
