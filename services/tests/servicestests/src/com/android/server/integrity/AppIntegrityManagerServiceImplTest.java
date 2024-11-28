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

package com.android.server.integrity;

import static android.content.integrity.AppIntegrityManager.EXTRA_STATUS;
import static android.content.integrity.AppIntegrityManager.STATUS_FAILURE;
import static android.content.integrity.AppIntegrityManager.STATUS_SUCCESS;
import static android.content.integrity.InstallerAllowedByManifestFormula.INSTALLER_CERTIFICATE_NOT_EVALUATED;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_UID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.IntegrityFormula;
import android.content.integrity.Rule;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.compat.PlatformCompat;
import com.android.server.testutils.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Unit test for {@link com.android.server.integrity.AppIntegrityManagerServiceImpl} */
@RunWith(JUnit4.class)
public class AppIntegrityManagerServiceImplTest {
    private static final String TEST_APP_PATH =
            "AppIntegrityManagerServiceImplTest/AppIntegrityManagerServiceTestApp.apk";

    private static final String TEST_APP_TWO_CERT_PATH =
            "AppIntegrityManagerServiceImplTest/DummyAppTwoCerts.apk";

    private static final String TEST_APP_SOURCE_STAMP_PATH =
            "AppIntegrityManagerServiceImplTest/SourceStampTestApk.apk";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String VERSION = "version";
    private static final String TEST_FRAMEWORK_PACKAGE = "com.android.frameworks.servicestests";

    private static final String PACKAGE_NAME = "com.test.app";

    private static final long VERSION_CODE = 100;
    private static final String INSTALLER = "com.long.random.test.installer.name";

    // These are obtained by running the test and checking logcat.
    private static final String APP_CERT =
            "F14CFECF5070874C05D3D2FA98E046BE20BDE02A0DC74BAF6B59C6A0E4C06850";
    // We use SHA256 for package names longer than 32 characters.
    private static final String INSTALLER_SHA256 =
            "30F41A7CBF96EE736A54DD6DF759B50ED3CC126ABCEF694E167C324F5976C227";
    private static final String SOURCE_STAMP_CERTIFICATE_HASH =
            "C6E737809CEF2B08CC6694892215F82A5E8FBC3C2A0F6212770310B90622D2D9";

    private static final String DUMMY_APP_TWO_CERTS_CERT_1 =
            "C0369C2A1096632429DFA8433068AECEAD00BAC337CA92A175036D39CC9AFE94";
    private static final String DUMMY_APP_TWO_CERTS_CERT_2 =
            "94366E0A80F3A3F0D8171A15760B88E228CD6E1101F0414C98878724FBE70147";

    private static final String PLAY_STORE_PKG = "com.android.vending";
    private static final String ADB_INSTALLER = "adb";
    private static final String PLAY_STORE_CERT = "play_store_cert";

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock PackageManagerInternal mPackageManagerInternal;
    @Mock PlatformCompat mPlatformCompat;
    @Mock Context mMockContext;
    @Mock Resources mMockResources;
    @Mock Handler mHandler;

    private final Context mRealContext = InstrumentationRegistry.getTargetContext();

    private PackageManager mSpyPackageManager;
    private File mTestApk;
    private File mTestApkTwoCerts;
    private File mTestApkSourceStamp;

    // under test
    private AppIntegrityManagerServiceImpl mService;

    @Before
    public void setup() throws Exception {
        mTestApk = File.createTempFile("AppIntegrity", ".apk");
        try (InputStream inputStream = mRealContext.getAssets().open(TEST_APP_PATH)) {
            Files.copy(inputStream, mTestApk.toPath(), REPLACE_EXISTING);
        }

        mTestApkTwoCerts = File.createTempFile("AppIntegrityTwoCerts", ".apk");
        try (InputStream inputStream = mRealContext.getAssets().open(TEST_APP_TWO_CERT_PATH)) {
            Files.copy(inputStream, mTestApkTwoCerts.toPath(), REPLACE_EXISTING);
        }

        mTestApkSourceStamp = File.createTempFile("AppIntegritySourceStamp", ".apk");
        try (InputStream inputStream = mRealContext.getAssets().open(TEST_APP_SOURCE_STAMP_PATH)) {
            Files.copy(inputStream, mTestApkSourceStamp.toPath(), REPLACE_EXISTING);
        }

        mService =
                new AppIntegrityManagerServiceImpl(
                        mMockContext,
                        mPackageManagerInternal,
                        mHandler);

        mSpyPackageManager = spy(mRealContext.getPackageManager());
        // setup mocks to prevent NPE
        when(mMockContext.getPackageManager()).thenReturn(mSpyPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(anyInt())).thenReturn(new String[] {});
        // These are needed to override the Settings.Global.get result.
        when(mMockContext.getContentResolver()).thenReturn(mRealContext.getContentResolver());
        setIntegrityCheckIncludesRuleProvider(true);
    }

    @After
    public void tearDown() throws Exception {
        mTestApk.delete();
        mTestApkTwoCerts.delete();
        mTestApkSourceStamp.delete();
    }

    @Test
    public void broadcastReceiverRegistration() throws Exception {
        allowlistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        verify(mMockContext).registerReceiver(any(), intentFilterCaptor.capture(), any(), any());
        assertEquals(1, intentFilterCaptor.getValue().countActions());
        assertEquals(
                Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION,
                intentFilterCaptor.getValue().getAction(0));
        assertEquals(1, intentFilterCaptor.getValue().countDataTypes());
        assertEquals(PACKAGE_MIME_TYPE, intentFilterCaptor.getValue().getDataType(0));
    }

    @Test
    public void handleBroadcast_allow() throws Exception {
        allowlistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    private void allowlistUsAsRuleProvider() {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(R.array.config_integrityRuleProviderPackages))
                .thenReturn(new String[] {TEST_FRAMEWORK_PACKAGE});
        when(mMockContext.getResources()).thenReturn(mockResources);
    }

    private void runJobInHandler() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        // sendMessageAtTime is the first non-final method in the call chain when "post" is invoked.
        verify(mHandler).sendMessageAtTime(messageCaptor.capture(), anyLong());
        messageCaptor.getValue().getCallback().run();
    }

    private void makeUsSystemApp() throws Exception {
        makeUsSystemApp(true);
    }

    private void makeUsSystemApp(boolean isSystemApp) throws Exception {
        PackageInfo packageInfo =
                mRealContext.getPackageManager().getPackageInfo(TEST_FRAMEWORK_PACKAGE, 0);
        if (isSystemApp) {
            packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        } else {
            packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;
        }
        doReturn(packageInfo)
                .when(mSpyPackageManager)
                .getPackageInfo(eq(TEST_FRAMEWORK_PACKAGE), anyInt());
        when(mMockContext.getPackageManager()).thenReturn(mSpyPackageManager);
    }

    private Intent makeVerificationIntent() throws Exception {
        PackageInfo packageInfo =
                mRealContext
                        .getPackageManager()
                        .getPackageInfo(
                                TEST_FRAMEWORK_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        doReturn(packageInfo).when(mSpyPackageManager).getPackageInfo(eq(INSTALLER), anyInt());
        doReturn(1).when(mSpyPackageManager).getPackageUid(eq(INSTALLER), anyInt());
        doReturn(new String[]{INSTALLER}).when(mSpyPackageManager).getPackagesForUid(anyInt());
        return makeVerificationIntent(INSTALLER);
    }

    private Intent makeVerificationIntent(String installer) throws Exception {
        Intent intent = new Intent();
        intent.setDataAndType(Uri.fromFile(mTestApk), PACKAGE_MIME_TYPE);
        intent.setAction(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        intent.putExtra(EXTRA_VERIFICATION_ID, 1);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, PACKAGE_NAME);
        intent.putExtra(EXTRA_VERIFICATION_INSTALLER_PACKAGE, installer);
        intent.putExtra(
                EXTRA_VERIFICATION_INSTALLER_UID,
                mMockContext.getPackageManager().getPackageUid(installer, /* flags= */ 0));
        intent.putExtra(Intent.EXTRA_LONG_VERSION_CODE, VERSION_CODE);
        return intent;
    }

    private void setIntegrityCheckIncludesRuleProvider(boolean shouldInclude) throws Exception {
        int value = shouldInclude ? 1 : 0;
        Settings.Global.putInt(
                mRealContext.getContentResolver(),
                Settings.Global.INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER,
                value);
        assertThat(
                        Settings.Global.getInt(
                                        mRealContext.getContentResolver(),
                                        Settings.Global.INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER,
                                        -1)
                                == 1)
                .isEqualTo(shouldInclude);
    }
}
