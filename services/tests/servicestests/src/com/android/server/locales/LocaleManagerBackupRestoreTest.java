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

package com.android.server.locales;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Environment;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.SimpleClock;
import android.util.AtomicFile;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.XmlUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the {@link LocaleManagerInternal}.
 */
@RunWith(AndroidJUnit4.class)
public class LocaleManagerBackupRestoreTest {
    private static final String DEFAULT_PACKAGE_NAME = "com.android.myapp";
    private static final String DEFAULT_LOCALE_TAGS = "en-XC,ar-XB";
    private static final String TEST_LOCALES_XML_TAG = "locales";
    private static final int DEFAULT_USER_ID = 0;
    private static final int WORK_PROFILE_USER_ID = 10;
    private static final int DEFAULT_UID = Binder.getCallingUid() + 100;
    private static final long DEFAULT_CREATION_TIME_MILLIS = 1000;
    private static final Duration RETENTION_PERIOD = Duration.ofDays(3);
    private static final LocaleList DEFAULT_LOCALES =
            LocaleList.forLanguageTags(DEFAULT_LOCALE_TAGS);
    private static final Map<String, String> DEFAULT_PACKAGE_LOCALES_MAP = Map.of(
            DEFAULT_PACKAGE_NAME, DEFAULT_LOCALE_TAGS);
    private static final File STAGED_LOCALES_DIR = new File(
            Environment.getExternalStorageDirectory(), "lmsUnitTests");


    private LocaleManagerBackupHelper mBackupHelper;
    private long mCurrentTimeMillis;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManagerInternal mMockPackageManagerInternal;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private LocaleManagerService mMockLocaleManagerService;
    BroadcastReceiver mUserMonitor;
    PackageMonitor mPackageMonitor;

    private final Clock mClock = new SimpleClock(ZoneOffset.UTC) {
        @Override
        public long millis() {
            return currentTimeMillis();
        }
    };

    private long currentTimeMillis() {
        return mCurrentTimeMillis;
    }

    private void setCurrentTimeMillis(long currentTimeMillis) {
        mCurrentTimeMillis = currentTimeMillis;
    }

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
        mMockPackageManager = mock(PackageManager.class);
        mMockLocaleManagerService = mock(LocaleManagerService.class);

        doReturn(mMockPackageManager).when(mMockContext).getPackageManager();

        mBackupHelper = spy(new ShadowLocaleManagerBackupHelper(mMockContext,
                mMockLocaleManagerService, mMockPackageManagerInternal,
                new File(Environment.getExternalStorageDirectory(), "lmsUnitTests"), mClock));
        doNothing().when(mBackupHelper).notifyBackupManager();

        mUserMonitor = mBackupHelper.getUserMonitor();
        mPackageMonitor = mBackupHelper.getPackageMonitor();
        setCurrentTimeMillis(DEFAULT_CREATION_TIME_MILLIS);
        cleanStagedFiles();
    }

    @Test
    public void testBackupPayload_noAppsInstalled_returnsNull() throws Exception {
        doReturn(List.of()).when(mMockPackageManagerInternal)
                .getInstalledApplications(anyLong(), anyInt(), anyInt());

        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
    }

    @Test
    public void testBackupPayload_noAppLocalesSet_returnsNull() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());
        setUpDummyAppForPackageManager(DEFAULT_PACKAGE_NAME);

        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
    }

    @Test
    public void testBackupPayload_appLocalesSet_returnsNonNullBlob() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        setUpDummyAppForPackageManager(DEFAULT_PACKAGE_NAME);

        byte[] payload = mBackupHelper.getBackupPayload(DEFAULT_USER_ID);
        verifyPayloadForAppLocales(DEFAULT_PACKAGE_LOCALES_MAP, payload);
    }

    @Test
    public void testBackupPayload_exceptionInGetLocalesAllPackages_returnsNull() throws Exception {
        setUpDummyAppForPackageManager(DEFAULT_PACKAGE_NAME);
        doThrow(new RemoteException("mock")).when(mMockLocaleManagerService).getApplicationLocales(
                anyString(), anyInt());

        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
    }

    @Test
    public void testBackupPayload_exceptionInGetLocalesSomePackages_appsWithExceptionNotBackedUp()
            throws Exception {
        // Set up two apps.
        ApplicationInfo defaultAppInfo = new ApplicationInfo();
        ApplicationInfo anotherAppInfo = new ApplicationInfo();
        defaultAppInfo.packageName = DEFAULT_PACKAGE_NAME;
        anotherAppInfo.packageName = "com.android.anotherapp";
        doReturn(List.of(defaultAppInfo, anotherAppInfo)).when(mMockPackageManagerInternal)
                .getInstalledApplications(anyLong(), anyInt(), anyInt());

        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        // Exception when getting locales for anotherApp.
        doThrow(new RemoteException("mock")).when(mMockLocaleManagerService).getApplicationLocales(
                eq(anotherAppInfo.packageName), anyInt());

        byte[] payload = mBackupHelper.getBackupPayload(DEFAULT_USER_ID);
        verifyPayloadForAppLocales(DEFAULT_PACKAGE_LOCALES_MAP, payload);
    }

    @Test
    public void testRestore_nullPayload_nothingRestoredAndNoStageFile() throws Exception {
        mBackupHelper.stageAndApplyRestoredPayload(/* payload= */ null, DEFAULT_USER_ID);

        verifyNothingRestored();
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_zeroLengthPayload_nothingRestoredAndNoStageFile() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mBackupHelper.stageAndApplyRestoredPayload(/* payload= */ out.toByteArray(),
                DEFAULT_USER_ID);

        verifyNothingRestored();
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_allAppsInstalled_noStageFileCreated() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP);

        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Locales were restored
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, DEFAULT_LOCALES);

        // Stage file wasn't created.
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_noAppsInstalled_everythingStaged() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP);

        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP,
                getStageFileIfExists(DEFAULT_USER_ID), DEFAULT_CREATION_TIME_MILLIS);
    }

    @Test
    public void testRestore_someAppsInstalled_partiallyStaged() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, String> pkgLocalesMap = new HashMap<>();

        String pkgNameA = "com.android.myAppA", pkgNameB = "com.android.myAppB";
        String langTagsA = "ru", langTagsB = "hi,fr";
        pkgLocalesMap.put(pkgNameA, langTagsA);
        pkgLocalesMap.put(pkgNameB, langTagsB);
        writeTestPayload(out, pkgLocalesMap);

        setUpPackageInstalled(pkgNameA);
        setUpPackageNotInstalled(pkgNameB);
        setUpLocalesForPackage(pkgNameA, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameA, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsA));

        pkgLocalesMap.remove(pkgNameA);
        verifyStageFileContent(pkgLocalesMap, getStageFileIfExists(DEFAULT_USER_ID),
                DEFAULT_CREATION_TIME_MILLIS);
    }

    @Test
    public void testRestore_appLocalesAlreadySet_nothingRestoredAndNoStageFile() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP);

        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.forLanguageTags("hi,mr"));

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Since locales are already set, we should not restore anything for it.
        verifyNothingRestored();
        // Stage file wasn't created
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appLocalesSetForSomeApps_restoresOnlyForAppsHavingNoLocalesSet()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, String> pkgLocalesMap = new HashMap<>();

        String pkgNameA = "com.android.myAppA", pkgNameB = "com.android.myAppB", pkgNameC =
                "com.android.myAppC";
        String langTagsA = "ru", langTagsB = "hi,fr", langTagsC = "zh,es";
        pkgLocalesMap.put(pkgNameA, langTagsA);
        pkgLocalesMap.put(pkgNameB, langTagsB);
        pkgLocalesMap.put(pkgNameC, langTagsC);
        writeTestPayload(out, pkgLocalesMap);

        // Both app A & B are installed on the device but A has locales already set.
        setUpPackageInstalled(pkgNameA);
        setUpPackageInstalled(pkgNameB);
        setUpPackageNotInstalled(pkgNameC);
        setUpLocalesForPackage(pkgNameA, LocaleList.forLanguageTags("mr,fr"));
        setUpLocalesForPackage(pkgNameB, LocaleList.getEmptyLocaleList());
        setUpLocalesForPackage(pkgNameC, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Restore locales only for myAppB.
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(eq(pkgNameA), anyInt(),
                any());
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameB, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsB));
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(eq(pkgNameC), anyInt(),
                any());

        // App C is staged.
        pkgLocalesMap.remove(pkgNameA);
        pkgLocalesMap.remove(pkgNameB);
        verifyStageFileContent(pkgLocalesMap, getStageFileIfExists(DEFAULT_USER_ID),
                DEFAULT_CREATION_TIME_MILLIS);
    }

    @Test
    public void testRestore_restoreInvokedAgain_creationTimeChanged() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP);

        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP, getStageFileIfExists(DEFAULT_USER_ID),
                DEFAULT_CREATION_TIME_MILLIS);

        final long newCreationTime = DEFAULT_CREATION_TIME_MILLIS + 100;
        setCurrentTimeMillis(newCreationTime);
        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP, getStageFileIfExists(DEFAULT_USER_ID),
                newCreationTime);
    }

    @Test
    public void testRestore_appInstalledAfterSUW_restoresFromStage() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, String> pkgLocalesMap = new HashMap<>();

        String pkgNameA = "com.android.myAppA", pkgNameB = "com.android.myAppB";
        String langTagsA = "ru", langTagsB = "hi,fr";
        pkgLocalesMap.put(pkgNameA, langTagsA);
        pkgLocalesMap.put(pkgNameB, langTagsB);
        writeTestPayload(out, pkgLocalesMap);

        setUpPackageNotInstalled(pkgNameA);
        setUpPackageNotInstalled(pkgNameB);
        setUpLocalesForPackage(pkgNameA, LocaleList.getEmptyLocaleList());
        setUpLocalesForPackage(pkgNameB, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();

        setUpPackageInstalled(pkgNameA);

        mPackageMonitor.onPackageAdded(pkgNameA, DEFAULT_UID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameA, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsA));

        pkgLocalesMap.remove(pkgNameA);
        verifyStageFileContent(pkgLocalesMap, getStageFileIfExists(DEFAULT_USER_ID),
                DEFAULT_CREATION_TIME_MILLIS);

        setUpPackageInstalled(pkgNameB);

        mPackageMonitor.onPackageAdded(pkgNameB, DEFAULT_UID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameB, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsB));
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appInstalledAfterSUWAndLocalesAlreadySet_restoresNothing()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP);

        // Package is not present on the device when the SUW restore is going on.
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP, getStageFileIfExists(DEFAULT_USER_ID),
                DEFAULT_CREATION_TIME_MILLIS);

        // App is installed later (post SUW).
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.forLanguageTags("hi,mr"));

        mPackageMonitor.onPackageAdded(DEFAULT_PACKAGE_NAME, DEFAULT_UID);

        // Since locales are already set, we should not restore anything for it.
        verifyNothingRestored();
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testStageFileDeletion_backupPassRunAfterRetentionPeriod_stageFileDeleted()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP);

        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP, getStageFileIfExists(DEFAULT_USER_ID),
                DEFAULT_CREATION_TIME_MILLIS);

        // Retention period has not elapsed.
        setCurrentTimeMillis(
                DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.minusHours(1).toMillis());
        doReturn(List.of()).when(mMockPackageManagerInternal)
                .getInstalledApplications(anyLong(), anyInt(), anyInt());
        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));

        // Stage file should NOT be deleted.
        checkStageFileExists(DEFAULT_USER_ID);

        // Exactly RETENTION_PERIOD amount of time has passed so stage file should still not be
        // removed.
        setCurrentTimeMillis(DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.toMillis());
        doReturn(List.of()).when(mMockPackageManagerInternal)
                .getInstalledApplications(anyLong(), anyInt(), anyInt());
        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));

        // Stage file should NOT be deleted.
        checkStageFileExists(DEFAULT_USER_ID);

        // Retention period has now expired, stage file should be deleted.
        setCurrentTimeMillis(
                DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.plusSeconds(1).toMillis());
        doReturn(List.of()).when(mMockPackageManagerInternal)
                .getInstalledApplications(anyLong(), anyInt(), anyInt());
        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));

        // Stage file should be deleted.
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testUserRemoval_userRemoved_stageFileDeleted() throws Exception {
        final ByteArrayOutputStream outDefault = new ByteArrayOutputStream();
        writeTestPayload(outDefault, DEFAULT_PACKAGE_LOCALES_MAP);

        final ByteArrayOutputStream outWorkProfile = new ByteArrayOutputStream();
        String anotherPackage = "com.android.anotherapp";
        String anotherLangTags = "mr,zh";
        HashMap<String, String> pkgLocalesMapWorkProfile = new HashMap<>();
        pkgLocalesMapWorkProfile.put(anotherPackage, anotherLangTags);
        writeTestPayload(outWorkProfile, pkgLocalesMapWorkProfile);

        // DEFAULT_PACKAGE_NAME is NOT installed on the device.
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);
        setUpPackageNotInstalled(anotherPackage);

        mBackupHelper.stageAndApplyRestoredPayload(outDefault.toByteArray(), DEFAULT_USER_ID);
        mBackupHelper.stageAndApplyRestoredPayload(outWorkProfile.toByteArray(),
                WORK_PROFILE_USER_ID);

        verifyNothingRestored();

        // Verify stage file contents.
        AtomicFile stageFileDefaultUser = getStageFileIfExists(DEFAULT_USER_ID);
        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP, stageFileDefaultUser,
                DEFAULT_CREATION_TIME_MILLIS);

        AtomicFile stageFileWorkProfile = getStageFileIfExists(WORK_PROFILE_USER_ID);
        verifyStageFileContent(pkgLocalesMapWorkProfile, stageFileWorkProfile,
                DEFAULT_CREATION_TIME_MILLIS);

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, DEFAULT_USER_ID);
        mUserMonitor.onReceive(mMockContext, intent);

        // Stage file should be removed only for DEFAULT_USER_ID.
        checkStageFileDoesNotExist(DEFAULT_USER_ID);
        verifyStageFileContent(pkgLocalesMapWorkProfile, stageFileWorkProfile,
                DEFAULT_CREATION_TIME_MILLIS);
    }

    @Test
    public void testLoadStageFiles_invalidNameFormat_stageFileDeleted() throws Exception {
        // Stage file name should be : staged_locales_<user_id_int>.xml
        File stageFile = new File(STAGED_LOCALES_DIR, "xyz.xml");
        assertTrue(stageFile.createNewFile());
        assertTrue(stageFile.isFile());

        // Putting valid xml data in file.
        FileOutputStream out = new FileOutputStream(stageFile);
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP, /* forStage= */
                true, /* creationTimeMillis= */ 0);
        out.flush();
        out.close();

        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP,
                new AtomicFile(stageFile), /* creationTimeMillis= */ 0);

        mBackupHelper = new LocaleManagerBackupHelper(mMockContext, mMockLocaleManagerService,
                mMockPackageManagerInternal, STAGED_LOCALES_DIR, mClock);
        assertFalse(stageFile.isFile());
    }

    @Test
    public void testLoadStageFiles_userIdNotParseable_stageFileDeleted() throws Exception {
        // Stage file name should be : staged_locales_<user_id_int>.xml
        File stageFile = new File(STAGED_LOCALES_DIR, "staged_locales_abc.xml");
        assertTrue(stageFile.createNewFile());
        assertTrue(stageFile.isFile());

        // Putting valid xml data in file.
        FileOutputStream out = new FileOutputStream(stageFile);
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP, /* forStage= */
                true, /* creationTimeMillis= */ 0);
        out.flush();
        out.close();

        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP,
                new AtomicFile(stageFile), /* creationTimeMillis= */ 0);

        mBackupHelper = new LocaleManagerBackupHelper(mMockContext, mMockLocaleManagerService,
                mMockPackageManagerInternal, STAGED_LOCALES_DIR, mClock);
        assertFalse(stageFile.isFile());
    }

    @Test
    public void testLoadStageFiles_invalidContent_stageFileDeleted() throws Exception {
        File stageFile = new File(STAGED_LOCALES_DIR, "staged_locales_0.xml");
        assertTrue(stageFile.createNewFile());
        assertTrue(stageFile.isFile());

        FileOutputStream out = new FileOutputStream(stageFile);
        out.write("some_non_xml_string".getBytes());
        out.close();

        mBackupHelper = new LocaleManagerBackupHelper(mMockContext, mMockLocaleManagerService,
                mMockPackageManagerInternal, STAGED_LOCALES_DIR, mClock);
        assertFalse(stageFile.isFile());
    }

    @Test
    public void testLoadStageFiles_validContent_doesLazyRestore() throws Exception {
        File stageFile = new File(STAGED_LOCALES_DIR, "staged_locales_0.xml");
        assertTrue(stageFile.createNewFile());
        assertTrue(stageFile.isFile());

        // Putting valid xml data in file.
        FileOutputStream out = new FileOutputStream(stageFile);
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_MAP, /* forStage= */
                true, DEFAULT_CREATION_TIME_MILLIS);
        out.flush();
        out.close();

        verifyStageFileContent(DEFAULT_PACKAGE_LOCALES_MAP,
                new AtomicFile(stageFile), DEFAULT_CREATION_TIME_MILLIS);

        mBackupHelper = new LocaleManagerBackupHelper(mMockContext, mMockLocaleManagerService,
                mMockPackageManagerInternal, STAGED_LOCALES_DIR, mClock);
        mPackageMonitor = mBackupHelper.getPackageMonitor();

        // Stage file still exists.
        assertTrue(stageFile.isFile());

        // App is installed later.
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());

        mPackageMonitor.onPackageAdded(DEFAULT_PACKAGE_NAME, DEFAULT_UID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, DEFAULT_LOCALES);

        // Stage file gets deleted here because all staged locales have been applied.
        assertFalse(stageFile.isFile());
    }

    private void setUpPackageInstalled(String packageName) throws Exception {
        doReturn(new PackageInfo()).when(mMockPackageManager).getPackageInfoAsUser(
                eq(packageName), anyInt(), anyInt());
    }

    private void setUpPackageNotInstalled(String packageName) throws Exception {
        doReturn(null).when(mMockPackageManager).getPackageInfoAsUser(eq(packageName),
                anyInt(), anyInt());
    }

    private void setUpLocalesForPackage(String packageName, LocaleList locales) throws Exception {
        doReturn(locales).when(mMockLocaleManagerService).getApplicationLocales(
                eq(packageName), anyInt());
    }

    private void setUpDummyAppForPackageManager(String packageName) {
        ApplicationInfo dummyApp = new ApplicationInfo();
        dummyApp.packageName = packageName;
        doReturn(List.of(dummyApp)).when(mMockPackageManagerInternal)
                .getInstalledApplications(anyLong(), anyInt(), anyInt());
    }

    /**
     * Verifies that nothing was restored for any package.
     *
     * <p>If {@link LocaleManagerService#setApplicationLocales} is not invoked, we can conclude
     * that nothing was restored.
     */
    private void verifyNothingRestored() throws Exception {
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(anyString(), anyInt(),
                any());
    }


    private static void verifyPayloadForAppLocales(Map<String, String> expectedPkgLocalesMap,
            byte[] payload)
            throws IOException, XmlPullParserException {
        verifyPayloadForAppLocales(expectedPkgLocalesMap, payload, /* forStage= */ false, -1);
    }

    private static void verifyPayloadForAppLocales(Map<String, String> expectedPkgLocalesMap,
            byte[] payload, boolean forStage, long expectedCreationTime)
            throws IOException, XmlPullParserException {
        final ByteArrayInputStream stream = new ByteArrayInputStream(payload);
        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());

        Map<String, String> backupDataMap = new HashMap<>();
        XmlUtils.beginDocument(parser, TEST_LOCALES_XML_TAG);
        if (forStage) {
            long actualCreationTime = parser.getAttributeLong(/* namespace= */ null,
                    "creationTimeMillis");
            assertEquals(expectedCreationTime, actualCreationTime);
        }
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (parser.getName().equals("package")) {
                String packageName = parser.getAttributeValue(null, "name");
                String languageTags = parser.getAttributeValue(null, "locales");
                backupDataMap.put(packageName, languageTags);
            }
        }

        assertEquals(expectedPkgLocalesMap, backupDataMap);
    }

    private static void writeTestPayload(OutputStream stream, Map<String, String> pkgLocalesMap)
            throws IOException {
        writeTestPayload(stream, pkgLocalesMap, /* forStage= */ false, /* creationTimeMillis= */
                -1);
    }

    private static void writeTestPayload(OutputStream stream, Map<String, String> pkgLocalesMap,
            boolean forStage, long creationTimeMillis)
            throws IOException {
        if (pkgLocalesMap.isEmpty()) {
            return;
        }

        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(/* encoding= */ null, /* standalone= */ true);
        out.startTag(/* namespace= */ null, TEST_LOCALES_XML_TAG);

        if (forStage) {
            out.attribute(/* namespace= */ null, "creationTimeMillis",
                    Long.toString(creationTimeMillis));
        }

        for (String pkg : pkgLocalesMap.keySet()) {
            out.startTag(/* namespace= */ null, "package");
            out.attribute(/* namespace= */ null, "name", pkg);
            out.attribute(/* namespace= */ null, "locales", pkgLocalesMap.get(pkg));
            out.endTag(/*namespace= */ null, "package");
        }

        out.endTag(/* namespace= */ null, TEST_LOCALES_XML_TAG);
        out.endDocument();
    }

    private static void verifyStageFileContent(Map<String, String> expectedPkgLocalesMap,
            AtomicFile stageFile,
            long creationTimeMillis)
            throws Exception {
        assertNotNull(stageFile);
        try (InputStream stagedDataInputStream = stageFile.openRead()) {
            verifyPayloadForAppLocales(expectedPkgLocalesMap, stagedDataInputStream.readAllBytes(),
                    /* forStage= */ true, creationTimeMillis);
        } catch (IOException | XmlPullParserException e) {
            throw e;
        }
    }

    private static void checkStageFileDoesNotExist(int userId) {
        assertNull(getStageFileIfExists(userId));
    }

    private static void checkStageFileExists(int userId) {
        assertNotNull(getStageFileIfExists(userId));
    }

    private static AtomicFile getStageFileIfExists(int userId) {
        File file = new File(STAGED_LOCALES_DIR, String.format("staged_locales_%d.xml", userId));
        if (file.isFile()) {
            return new AtomicFile(file);
        }
        return null;
    }

    private static void cleanStagedFiles() {
        File[] files = STAGED_LOCALES_DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }
}
