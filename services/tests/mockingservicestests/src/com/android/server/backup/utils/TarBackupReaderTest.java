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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_ID;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_APK_NOT_INSTALLED;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_RESTORE_ANY_VERSION;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_SYSTEM_APP_NO_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_VERSIONS_MATCH;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_V_TO_U_RESTORE_PKG_ELIGIBLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.app.backup.IBackupManagerMonitor;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.mockingservicestests.R;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.Flags;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.restore.PerformAdbRestoreTask;
import com.android.server.backup.restore.RestorePolicy;
import com.android.server.backup.testutils.PackageManagerStub;

import com.google.common.hash.Hashing;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TarBackupReaderTest {
    private static final String TELEPHONY_PACKAGE_NAME = "com.android.providers.telephony";
    private static final String TELEPHONY_PACKAGE_SIGNATURE_SHA256 =
            "301aa3cb081134501c45f1422abc66c24224fd5ded5fdc8f17e697176fd866aa";
    private static final int TELEPHONY_PACKAGE_VERSION = 25;
    private static final String TEST_PACKAGE_NAME = "com.android.backup.testing";
    private static final Signature FAKE_SIGNATURE_1 = new Signature("1234");
    private static final Signature FAKE_SIGNATURE_2 = new Signature("5678");

    @Mock private BytesReadListener mBytesReadListenerMock;
    @Mock private IBackupManagerMonitor mBackupManagerMonitorMock;
    @Mock private PackageManagerInternal mMockPackageManagerInternal;
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final PackageManagerStub mPackageManagerStub = new PackageManagerStub();
    private Context mContext;
    private int mUserId;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = new TestableContext(ApplicationProvider.getApplicationContext());
        mUserId = UserHandle.USER_SYSTEM;
    }

    @Test
    public void readTarHeaders_backupEncrypted_correctlyParsesFileMetadata() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, "123");
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        assertThat(fileMetadata.packageName).isEqualTo(TELEPHONY_PACKAGE_NAME);
        assertThat(fileMetadata.mode).isEqualTo(0600);
        assertThat(fileMetadata.size).isEqualTo(2438);
        assertThat(fileMetadata.domain).isEqualTo(null);
        assertThat(fileMetadata.path).isEqualTo("_manifest");
    }

    @Test
    public void readTarHeaders_backupNotEncrypted_correctlyParsesFileMetadata() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        assertThat(fileMetadata.packageName).isEqualTo(TELEPHONY_PACKAGE_NAME);
        assertThat(fileMetadata.mode).isEqualTo(0600);
        assertThat(fileMetadata.size).isEqualTo(2438);
        assertThat(fileMetadata.domain).isEqualTo(null);
        assertThat(fileMetadata.path).isEqualTo("_manifest");
    }

    @Test
    public void readTarHeaders_backupNotEncrypted_correctlyReadsPaxHeader() throws Exception {
        // Files with long names (>100 chars) will force backup to add PAX header.
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_file_with_long_name);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);

        // Read manifest file.
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();
        Signature[] signatures = tarBackupReader.readAppManifestAndReturnSignatures(
                fileMetadata);
        RestorePolicy restorePolicy = tarBackupReader.chooseRestorePolicy(
                mPackageManagerStub, false /* allowApks */, fileMetadata, signatures,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(restorePolicy).isEqualTo(RestorePolicy.IGNORE);
        assertThat(fileMetadata.packageName).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(fileMetadata.path).isEqualTo(UserBackupManagerService.BACKUP_MANIFEST_FILENAME);

        tarBackupReader.skipTarPadding(fileMetadata.size);

        // Read actual file (PAX header will only exist here).
        fileMetadata = tarBackupReader.readTarHeaders();
        signatures = tarBackupReader.readAppManifestAndReturnSignatures(
                fileMetadata);
        restorePolicy = tarBackupReader.chooseRestorePolicy(
                mPackageManagerStub, false /* allowApks */, fileMetadata, signatures,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(restorePolicy).isEqualTo(RestorePolicy.IGNORE);
        assertThat(fileMetadata.packageName).isEqualTo(TEST_PACKAGE_NAME);
        char[] expectedFileNameChars = new char[200];
        Arrays.fill(expectedFileNameChars, '1');
        String expectedFileName = new String(expectedFileNameChars);
        assertThat(fileMetadata.path).isEqualTo(expectedFileName);
    }

    @Test
    public void readAppManifest_backupEncrypted_correctlyParsesAppManifest() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, "123");
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        Signature[] signatures = tarBackupReader.readAppManifestAndReturnSignatures(fileMetadata);

        assertThat(fileMetadata.version).isEqualTo(TELEPHONY_PACKAGE_VERSION);
        assertThat(fileMetadata.hasApk).isFalse();
        assertThat(signatures).isNotNull();
        assertThat(signatures).hasLength(1);

        String signatureSha256 = Hashing.sha256().hashBytes(signatures[0].toByteArray()).toString();
        assertThat(signatureSha256).isEqualTo(TELEPHONY_PACKAGE_SIGNATURE_SHA256);
    }

    @Test
    public void readAppManifest_backupNotEncrypted_correctlyParsesAppManifest() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        Signature[] signatures = tarBackupReader.readAppManifestAndReturnSignatures(fileMetadata);

        assertThat(fileMetadata.version).isEqualTo(TELEPHONY_PACKAGE_VERSION);
        assertThat(fileMetadata.hasApk).isFalse();
        assertThat(signatures).isNotNull();
        assertThat(signatures).hasLength(1);

        String signatureSha256 = Hashing.sha256().hashBytes(signatures[0].toByteArray()).toString();
        assertThat(signatureSha256).isEqualTo(TELEPHONY_PACKAGE_SIGNATURE_SHA256);
    }

    @Test
    public void chooseRestorePolicy_signaturesIsNull_returnsIgnore() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                true /* allowApks */, new FileMetadata(), null /* signatures */,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        verifyZeroInteractions(mBackupManagerMonitorMock);
    }

    @Test
    public void chooseRestorePolicy_packageDoesNotExistAndAllowApksAndHasApk_returnsAcceptIfApk()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata info = new FileMetadata();
        info.hasApk = true;
        PackageManagerStub.sPackageInfo = null;

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                true /* allowApks */, info, new Signature[0] /* signatures */,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT_IF_APK);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_APK_NOT_INSTALLED);
    }

    @Test
    public void
    chooseRestorePolicy_packageDoesNotExistAndAllowApksAndDoesNotHaveApk_returnsAcceptIfApkLogsCannotRestore()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata info = new FileMetadata();
        info.hasApk = false;
        PackageManagerStub.sPackageInfo = null;

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                true /* allowApks */, info, new Signature[0] /* signatures */,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT_IF_APK);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock, times(2)).onEvent(bundleCaptor.capture());
        List<Bundle> eventBundles = bundleCaptor.getAllValues();
        assertThat(eventBundles).hasSize(2);
        assertThat(eventBundles.get(0).get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_APK_NOT_INSTALLED);
        assertThat(eventBundles.get(1).get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK);
    }

    @Test
    public void chooseRestorePolicy_packageDoesNotExistAndDoesNotAllowApks_returnsIgnore()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        PackageManagerStub.sPackageInfo = null;

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, new FileMetadata(), new Signature[0] /* signatures */,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_APK_NOT_INSTALLED);
    }

    @Test
    public void chooseRestorePolicy_doesNotAllowsBackup_returnsIgnore() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags = ~ApplicationInfo.FLAG_ALLOW_BACKUP;
        PackageManagerStub.sPackageInfo = packageInfo;

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, new FileMetadata(), new Signature[0] /* signatures */,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE);
    }

    @Test
    public void chooseRestorePolicy_systemAppWithNoAgent_returnsIgnore() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        packageInfo.applicationInfo.uid = Process.SYSTEM_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        PackageManagerStub.sPackageInfo = packageInfo;

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, new FileMetadata(), new Signature[0] /* signatures */,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_SYSTEM_APP_NO_AGENT);
    }

    @Test
    public void chooseRestorePolicy_nonSystemAppSignaturesDoNotMatch_returnsIgnore()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        packageInfo.applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {FAKE_SIGNATURE_2},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        PackageManagerStub.sPackageInfo = packageInfo;

        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, new FileMetadata(), signatures,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH);
    }

    @Test
    public void chooseRestorePolicy_systemAppWithBackupAgentAndRestoreAnyVersion_returnsAccept()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |=
                ApplicationInfo.FLAG_ALLOW_BACKUP | ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        packageInfo.applicationInfo.uid = Process.SYSTEM_UID;
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {FAKE_SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, new FileMetadata(), signatures,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_RESTORE_ANY_VERSION);
    }

    @Test
    public void chooseRestorePolicy_restoreAnyVersion_returnsAccept() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |=
                ApplicationInfo.FLAG_ALLOW_BACKUP | ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        packageInfo.applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {FAKE_SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, new FileMetadata(), signatures,
                mMockPackageManagerInternal, mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_RESTORE_ANY_VERSION);
    }

    @Test
    public void chooseRestorePolicy_notRestoreAnyVersionButVersionMatch_returnsAccept()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = 1;

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        packageInfo.applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {FAKE_SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.versionCode = 2;
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_VERSIONS_MATCH);
    }

    @Test
    public void
    chooseRestorePolicy_flagOnNotRestoreAnyVersionVToURestoreAndInAllowlist_returnsAccept()
            throws Exception {

        mSetFlagsRule.enableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        TarBackupReader tarBackupReader = createTarBackupReader();

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.V_TO_U_RESTORE_ALLOWLIST, "test");

        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 1;

        PackageInfo packageInfo = createNonRestoreAnyVersionPackage(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_V_TO_U_RESTORE_PKG_ELIGIBLE);
    }


    @Test
    public void
    chooseRestorePolicy_flagOffNotRestoreAnyVersionVToURestoreAndInAllowlist_returnsIgnore()
            throws Exception {

        mSetFlagsRule.disableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        TarBackupReader tarBackupReader = createTarBackupReader();

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.V_TO_U_RESTORE_ALLOWLIST, "test");

        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 1;

        PackageInfo packageInfo = createNonRestoreAnyVersionPackage(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER);

    }

    @Test
    public void
    chooseRestorePolicy_flagOnNotRestoreAnyVersionVToURestoreAndNotInAllowlist_returnsIgnore()
            throws Exception {

        mSetFlagsRule.enableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        TarBackupReader tarBackupReader = createTarBackupReader();

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.V_TO_U_RESTORE_ALLOWLIST, "pkg");

        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 1;

        PackageInfo packageInfo = createNonRestoreAnyVersionPackage(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER);
    }

    @Test
    public void
    chooseRestorePolicy_notRestoreAnyVersionAndVersionMismatchButAllowApksAndHasApk_returnsAcceptIfApk()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.V_TO_U_RESTORE_ALLOWLIST, "pkg");

        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = 2;
        info.hasApk = true;

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        packageInfo.applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {FAKE_SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.versionCode = 1;
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                true /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.ACCEPT_IF_APK);
        verifyNoMoreInteractions(mBackupManagerMonitorMock);
    }

    @Test
    public void
    chooseRestorePolicy_notRestoreAnyVersionAndVersionMismatchAndDoesNotAllowApks_returnsIgnore()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.V_TO_U_RESTORE_ALLOWLIST, "pkg");

        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = 2;

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        packageInfo.applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {FAKE_SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.versionCode = 1;
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER);
    }

    @Test
    public void
    chooseRestorePolicy_allowlistNotSetNotRestoreAnyVersionVersionMismatch_returnsIgnore()
            throws Exception {
        mSetFlagsRule.disableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        TarBackupReader tarBackupReader = createTarBackupReader();

        Signature[] signatures = new Signature[]{FAKE_SIGNATURE_1};
        FileMetadata info = new FileMetadata();
        info.version = Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 2;

        PackageInfo packageInfo = createNonRestoreAnyVersionPackage(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 1);
        PackageManagerStub.sPackageInfo = packageInfo;

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(FAKE_SIGNATURE_1,
                packageInfo.packageName);
        RestorePolicy policy = tarBackupReader.chooseRestorePolicy(mPackageManagerStub,
                false /* allowApks */, info, signatures, mMockPackageManagerInternal,
                mUserId, mContext);

        assertThat(policy).isEqualTo(RestorePolicy.IGNORE);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mBackupManagerMonitorMock).onEvent(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().get(EXTRA_LOG_EVENT_ID)).isEqualTo(
                LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER);
    }

    private TarBackupReader createTarBackupReader() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        return tarBackupReader;
    }

    private PackageInfo createNonRestoreAnyVersionPackage(int versionCode) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        packageInfo.applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        packageInfo.applicationInfo.backupAgentName = null;
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[]{FAKE_SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.versionCode = versionCode;
        return packageInfo;
    }
}

