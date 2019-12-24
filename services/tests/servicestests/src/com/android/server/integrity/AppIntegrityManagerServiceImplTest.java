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
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
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

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.integrity.engine.RuleEvaluationEngine;
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.testutils.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit test for {@link com.android.server.integrity.AppIntegrityManagerServiceImpl} */
@RunWith(AndroidJUnit4.class)
public class AppIntegrityManagerServiceImplTest {
    private static final String TEST_DIR = "AppIntegrityManagerServiceImplTest";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String VERSION = "version";
    private static final String TEST_FRAMEWORK_PACKAGE = "com.android.frameworks.servicestests";

    private static final String PACKAGE_NAME = "com.test.app";
    private static final int VERSION_CODE = 100;
    private static final String INSTALLER = TEST_FRAMEWORK_PACKAGE;
    // These are obtained by running the test and checking logcat.
    private static final String APP_CERT =
            "949ADC6CB92FF09E3784D6E9504F26F9BEAC06E60D881D55A6A81160F9CD6FD1";
    private static final String INSTALLER_CERT =
            "301AA3CB081134501C45F1422ABC66C24224FD5DED5FDC8F17E697176FD866AA";
    // We use SHA256 for package names longer than 32 characters.
    private static final String INSTALLER_SHA256 =
            "786933C28839603EB48C50B2A688DC6BE52C833627CB2731FF8466A2AE9F94CD";

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock PackageManagerInternal mPackageManagerInternal;
    @Mock Context mMockContext;
    @Mock Resources mMockResources;
    @Mock RuleEvaluationEngine mRuleEvaluationEngine;
    @Mock IntegrityFileManager mIntegrityFileManager;
    @Mock Handler mHandler;

    private PackageManager mSpyPackageManager;
    private File mTestApk;

    private final Context mRealContext = InstrumentationRegistry.getTargetContext();
    // under test
    private AppIntegrityManagerServiceImpl mService;

    @Before
    public void setup() throws Exception {
        mTestApk = File.createTempFile("TestApk", /* suffix= */ null);
        mTestApk.deleteOnExit();
        try (InputStream inputStream = mRealContext.getAssets().open(TEST_DIR + "/test.apk")) {
            Files.copy(inputStream, mTestApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        mService =
                new AppIntegrityManagerServiceImpl(
                        mMockContext,
                        mPackageManagerInternal,
                        mRuleEvaluationEngine,
                        mIntegrityFileManager,
                        mHandler);

        mSpyPackageManager = spy(mRealContext.getPackageManager());
        // setup mocks to prevent NPE
        when(mMockContext.getPackageManager()).thenReturn(mSpyPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(anyInt())).thenReturn(new String[] {});
    }

    @After
    public void tearDown() throws Exception {
        mTestApk.delete();
    }

    // This is not a test of the class, but more of a safeguard that we don't block any install in
    // the default case. This is needed because we don't have any emergency kill switch to disable
    // this component.
    @Test
    public void default_allow() throws Exception {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);
        mService = AppIntegrityManagerServiceImpl.create(mMockContext);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext, times(2))
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);

        // Since we are not mocking handler in this case, we must wait.
        // 2 seconds should be a sensible timeout.
        Thread.sleep(2000);
        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void updateRuleSet_notAuthorized() throws Exception {
        makeUsSystemApp();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        TestUtils.assertExpectException(
                SecurityException.class,
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.",
                () ->
                        mService.updateRuleSet(
                                VERSION,
                                new ParceledListSlice<>(Arrays.asList(rule)),
                                /* statusReceiver= */ null));
    }

    @Test
    public void updateRuleSet_notSystemApp() throws Exception {
        whitelistUsAsRuleProvider();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        TestUtils.assertExpectException(
                SecurityException.class,
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.",
                () ->
                        mService.updateRuleSet(
                                VERSION,
                                new ParceledListSlice<>(Arrays.asList(rule)),
                                /* statusReceiver= */ null));
    }

    @Test
    public void updateRuleSet_authorized() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);

        // no SecurityException
        mService.updateRuleSet(
                VERSION, new ParceledListSlice<>(Arrays.asList(rule)), mock(IntentSender.class));
    }

    @Test
    public void updateRuleSet_correctMethodCall() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        IntentSender mockReceiver = mock(IntentSender.class);
        List<Rule> rules =
                Arrays.asList(
                        new Rule(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME,
                                        PACKAGE_NAME,
                                        /* isHashedValue= */ false),
                                Rule.DENY));

        mService.updateRuleSet(VERSION, new ParceledListSlice<>(rules), mockReceiver);
        runJobInHandler();

        verify(mIntegrityFileManager).writeRules(VERSION, TEST_FRAMEWORK_PACKAGE, rules);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockReceiver).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any());
        assertEquals(STATUS_SUCCESS, intentCaptor.getValue().getIntExtra(EXTRA_STATUS, -1));
    }

    @Test
    public void updateRuleSet_fail() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        doThrow(new IOException()).when(mIntegrityFileManager).writeRules(any(), any(), any());
        IntentSender mockReceiver = mock(IntentSender.class);
        List<Rule> rules =
                Arrays.asList(
                        new Rule(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME,
                                        PACKAGE_NAME,
                                        /* isHashedValue= */ false),
                                Rule.DENY));

        mService.updateRuleSet(VERSION, new ParceledListSlice<>(rules), mockReceiver);
        runJobInHandler();

        verify(mIntegrityFileManager).writeRules(VERSION, TEST_FRAMEWORK_PACKAGE, rules);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockReceiver).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any());
        assertEquals(STATUS_FAILURE, intentCaptor.getValue().getIntExtra(EXTRA_STATUS, -1));
    }

    @Test
    public void broadcastReceiverRegistration() throws Exception {
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
    public void handleBroadcast_correctArgs() throws Exception {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any(), any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        ArgumentCaptor<AppInstallMetadata> metadataCaptor =
                ArgumentCaptor.forClass(AppInstallMetadata.class);
        Map<String, String> allowedInstallers = new HashMap<>();
        ArgumentCaptor<Map<String, String>> allowedInstallersCaptor =
                ArgumentCaptor.forClass(allowedInstallers.getClass());
        verify(mRuleEvaluationEngine)
                .evaluate(metadataCaptor.capture(), allowedInstallersCaptor.capture());
        AppInstallMetadata appInstallMetadata = metadataCaptor.getValue();
        allowedInstallers = allowedInstallersCaptor.getValue();
        assertEquals(PACKAGE_NAME, appInstallMetadata.getPackageName());
        assertEquals(APP_CERT, appInstallMetadata.getAppCertificate());
        assertEquals(INSTALLER_SHA256, appInstallMetadata.getInstallerName());
        assertEquals(INSTALLER_CERT, appInstallMetadata.getInstallerCertificate());
        assertEquals(VERSION_CODE, appInstallMetadata.getVersionCode());
        assertFalse(appInstallMetadata.isPreInstalled());
        // These are hardcoded in the test apk
        assertEquals(2, allowedInstallers.size());
        assertEquals("cert_1", allowedInstallers.get("store_1"));
        assertEquals("cert_2", allowedInstallers.get("store_2"));
    }

    @Test
    public void handleBroadcast_allow() throws Exception {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any(), any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void handleBroadcast_reject() throws Exception {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        when(mRuleEvaluationEngine.evaluate(any(), any()))
                .thenReturn(
                        IntegrityCheckResult.deny(
                                new Rule(
                                        new AtomicFormula.BooleanAtomicFormula(
                                                AtomicFormula.PRE_INSTALLED, false),
                                        Rule.DENY)));
        Intent intent = makeVerificationIntent();

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);
    }

    private void whitelistUsAsRuleProvider() {
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
        PackageInfo packageInfo =
                mRealContext.getPackageManager().getPackageInfo(TEST_FRAMEWORK_PACKAGE, 0);
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        doReturn(packageInfo)
                .when(mSpyPackageManager)
                .getPackageInfo(eq(TEST_FRAMEWORK_PACKAGE), anyInt());
    }

    private Intent makeVerificationIntent() throws Exception {
        Intent intent = new Intent();
        intent.setDataAndType(Uri.fromFile(mTestApk), PACKAGE_MIME_TYPE);
        intent.setAction(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        intent.putExtra(EXTRA_VERIFICATION_ID, 1);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, PACKAGE_NAME);
        intent.putExtra(EXTRA_VERIFICATION_INSTALLER_PACKAGE, INSTALLER);
        intent.putExtra(
                EXTRA_VERIFICATION_INSTALLER_UID,
                mRealContext.getPackageManager().getPackageUid(INSTALLER, /* flags= */ 0));
        intent.putExtra(Intent.EXTRA_VERSION_CODE, VERSION_CODE);
        return intent;
    }
}
