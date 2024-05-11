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
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.LocaleList;
import android.os.Process;
import android.os.RemoteException;
import android.os.SimpleClock;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.locales.LocaleManagerBackupHelper.LocalesInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for the {@link LocaleManagerInternal}.
 */
@RunWith(AndroidJUnit4.class)
public class LocaleManagerBackupRestoreTest {
    private static final String TAG = "LocaleManagerBackupRestoreTest";
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
    private static final Map<String, LocalesInfo> DEFAULT_PACKAGE_LOCALES_INFO_MAP = Map.of(
            DEFAULT_PACKAGE_NAME, new LocalesInfo(DEFAULT_LOCALE_TAGS, false));
    private static final SparseArray<LocaleManagerBackupHelper.StagedData> STAGE_DATA =
            new SparseArray<>();

    private LocaleManagerBackupHelper mBackupHelper;
    private long mCurrentTimeMillis;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private LocaleManagerService mMockLocaleManagerService;
    @Mock
    private SharedPreferences mMockDelegateAppLocalePackages;
    @Mock
    private SharedPreferences.Editor mMockSpEditor;

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
        mMockPackageManager = mock(PackageManager.class);
        mMockLocaleManagerService = mock(LocaleManagerService.class);
        mMockDelegateAppLocalePackages = mock(SharedPreferences.class);
        mMockSpEditor = mock(SharedPreferences.Editor.class);
        SystemAppUpdateTracker systemAppUpdateTracker = mock(SystemAppUpdateTracker.class);

        doReturn(mMockPackageManager).when(mMockContext).getPackageManager();
        doReturn(mMockSpEditor).when(mMockDelegateAppLocalePackages).edit();

        HandlerThread broadcastHandlerThread = new HandlerThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND);
        broadcastHandlerThread.start();

        mBackupHelper = spy(new ShadowLocaleManagerBackupHelper(mMockContext,
                mMockLocaleManagerService, mMockPackageManager, mClock, STAGE_DATA,
                broadcastHandlerThread, mMockDelegateAppLocalePackages));
        doNothing().when(mBackupHelper).notifyBackupManager();

        mUserMonitor = mBackupHelper.getUserMonitor();
        mPackageMonitor = new LocaleManagerServicePackageMonitor(mBackupHelper,
            systemAppUpdateTracker, mMockLocaleManagerService);
        setCurrentTimeMillis(DEFAULT_CREATION_TIME_MILLIS);
    }

    @After
    public void tearDown() throws Exception {
        STAGE_DATA.clear();
    }

    @Test
    public void testBackupPayload_noAppsInstalled_returnsNull() throws Exception {
        doReturn(List.of()).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());

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
        setUpPackageNamesForSp(Collections.<String>emptySet());

        byte[] payload = mBackupHelper.getBackupPayload(DEFAULT_USER_ID);
        verifyPayloadForAppLocales(DEFAULT_PACKAGE_LOCALES_INFO_MAP, payload);
    }

    @Test
    public void testBackupPayload_appLocalesSet_fromDelegateSelector() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        setUpDummyAppForPackageManager(DEFAULT_PACKAGE_NAME);
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));
        Map<String, LocalesInfo> expectPackageLocalePack = Map.of(DEFAULT_PACKAGE_NAME,
                new LocalesInfo(DEFAULT_LOCALE_TAGS, true));

        byte[] payload = mBackupHelper.getBackupPayload(DEFAULT_USER_ID);

        verifyPayloadForAppLocales(expectPackageLocalePack, payload);
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
        doReturn(List.of(defaultAppInfo, anotherAppInfo)).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        setUpPackageNamesForSp(Collections.<String>emptySet());
        // Exception when getting locales for anotherApp.
        doThrow(new RemoteException("mock")).when(mMockLocaleManagerService).getApplicationLocales(
                eq(anotherAppInfo.packageName), anyInt());

        byte[] payload = mBackupHelper.getBackupPayload(DEFAULT_USER_ID);

        verifyPayloadForAppLocales(DEFAULT_PACKAGE_LOCALES_INFO_MAP, payload);
    }

    @Test
    public void testRestore_nullPayload_nothingRestoredAndNoStageData() throws Exception {
        mBackupHelper.stageAndApplyRestoredPayload(/* payload= */ null, DEFAULT_USER_ID);

        verifyNothingRestored();
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_zeroLengthPayload_nothingRestoredAndNoStageData() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mBackupHelper.stageAndApplyRestoredPayload(/* payload= */ out.toByteArray(),
                DEFAULT_USER_ID);

        verifyNothingRestored();
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_allAppsInstalled_noStageDataCreated() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Locales were restored
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, DEFAULT_LOCALES, false, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_allAppsInstalled_nothingToSp() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Locales were restored
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, DEFAULT_LOCALES, false, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);
        checkStageDataDoesNotExist(DEFAULT_USER_ID);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, DEFAULT_PACKAGE_NAME, false,
                false);

        verify(mMockSpEditor, times(0)).putStringSet(anyString(), any());
    }

    @Test
    public void testRestore_allAppsInstalled_storeInfoToSp() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, LocalesInfo> pkgLocalesMap = Map.of(DEFAULT_PACKAGE_NAME,
                new LocalesInfo(DEFAULT_LOCALE_TAGS, true));
        writeTestPayload(out, pkgLocalesMap);
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Locales were restored
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, DEFAULT_LOCALES, true, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);
        checkStageDataDoesNotExist(DEFAULT_USER_ID);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, DEFAULT_PACKAGE_NAME, true,
                false);

        verify(mMockSpEditor, times(1)).putStringSet(Integer.toString(DEFAULT_USER_ID),
                new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));
    }

    @Test
    public void testRestore_allAppsInstalled_InfoHasExistedInSp() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, LocalesInfo> pkgLocalesMap = Map.of(DEFAULT_PACKAGE_NAME,
                new LocalesInfo(DEFAULT_LOCALE_TAGS, true));
        writeTestPayload(out, pkgLocalesMap);
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Locales were restored
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID, DEFAULT_LOCALES, true,
                FrameworkStatsLog.APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);
        checkStageDataDoesNotExist(DEFAULT_USER_ID);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, DEFAULT_PACKAGE_NAME, true,
                false);

        verify(mMockSpEditor, times(0)).putStringSet(anyString(), any());
    }

    @Test
    public void testRestore_noAppsInstalled_everythingStaged() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageDataForUser(DEFAULT_PACKAGE_LOCALES_INFO_MAP,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_someAppsInstalled_partiallyStaged() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, LocalesInfo> pkgLocalesMap = new HashMap<>();
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        String langTagsA = "ru";
        String langTagsB = "hi,fr";
        LocalesInfo localesInfoA = new LocalesInfo(langTagsA, true);
        LocalesInfo localesInfoB = new LocalesInfo(langTagsB, true);
        pkgLocalesMap.put(pkgNameA, localesInfoA);
        pkgLocalesMap.put(pkgNameB, localesInfoB);
        writeTestPayload(out, pkgLocalesMap);
        setUpPackageInstalled(pkgNameA);
        setUpPackageNotInstalled(pkgNameB);
        setUpLocalesForPackage(pkgNameA, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameA, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsA), true, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);

        pkgLocalesMap.remove(pkgNameA);

        verifyStageDataForUser(pkgLocalesMap,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appLocalesAlreadySet_nothingRestoredAndNoStageData() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.forLanguageTags("hi,mr"));

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Since locales are already set, we should not restore anything for it.
        verifyNothingRestored();
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appLocalesSetForSomeApps_restoresOnlyForAppsHavingNoLocalesSet()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, LocalesInfo> pkgLocalesMap = new HashMap<>();
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        String pkgNameC = "com.android.myAppC";
        String langTagsA = "ru";
        String langTagsB = "hi,fr";
        String langTagsC = "zh,es";
        LocalesInfo localesInfoA = new LocalesInfo(langTagsA, true);
        LocalesInfo localesInfoB = new LocalesInfo(langTagsB, true);
        LocalesInfo localesInfoC = new LocalesInfo(langTagsC, true);
        pkgLocalesMap.put(pkgNameA, localesInfoA);
        pkgLocalesMap.put(pkgNameB, localesInfoB);
        pkgLocalesMap.put(pkgNameC, localesInfoC);
        writeTestPayload(out, pkgLocalesMap);
        // Both app A & B are installed on the device but A has locales already set.
        setUpPackageInstalled(pkgNameA);
        setUpPackageInstalled(pkgNameB);
        setUpPackageNotInstalled(pkgNameC);
        setUpLocalesForPackage(pkgNameA, LocaleList.forLanguageTags("mr,fr"));
        setUpLocalesForPackage(pkgNameB, LocaleList.getEmptyLocaleList());
        setUpLocalesForPackage(pkgNameC, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(pkgNameA, pkgNameB, pkgNameC)));

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        // Restore locales only for myAppB.
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(eq(pkgNameA), anyInt(),
                any(), anyBoolean(), anyInt());
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameB, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsB), true, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(eq(pkgNameC), anyInt(),
                any(), anyBoolean(), anyInt());

        // App C is staged.
        pkgLocalesMap.remove(pkgNameA);
        pkgLocalesMap.remove(pkgNameB);

        verifyStageDataForUser(pkgLocalesMap,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_restoreInvokedAgain_creationTimeChanged() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyStageDataForUser(DEFAULT_PACKAGE_LOCALES_INFO_MAP,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        final long newCreationTime = DEFAULT_CREATION_TIME_MILLIS + 100;
        setCurrentTimeMillis(newCreationTime);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyStageDataForUser(DEFAULT_PACKAGE_LOCALES_INFO_MAP,
                newCreationTime, DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appInstalledAfterSUW_restoresFromStage() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, LocalesInfo> pkgLocalesMap = new HashMap<>();
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        String langTagsA = "ru";
        String langTagsB = "hi,fr";
        LocalesInfo localesInfoA = new LocalesInfo(langTagsA, false);
        LocalesInfo localesInfoB = new LocalesInfo(langTagsB, true);
        pkgLocalesMap.put(pkgNameA, localesInfoA);
        pkgLocalesMap.put(pkgNameB, localesInfoB);
        writeTestPayload(out, pkgLocalesMap);
        setUpPackageNotInstalled(pkgNameA);
        setUpPackageNotInstalled(pkgNameB);
        setUpLocalesForPackage(pkgNameA, LocaleList.getEmptyLocaleList());
        setUpLocalesForPackage(pkgNameB, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();

        setUpPackageInstalled(pkgNameA);

        mPackageMonitor.onPackageAddedWithExtras(pkgNameA, DEFAULT_UID, new Bundle());

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameA, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsA), false, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, pkgNameA, false, false);

        verify(mMockSpEditor, times(0)).putStringSet(anyString(), any());

        pkgLocalesMap.remove(pkgNameA);

        verifyStageDataForUser(pkgLocalesMap, DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        setUpPackageInstalled(pkgNameB);

        mPackageMonitor.onPackageAddedWithExtras(pkgNameB, DEFAULT_UID, new Bundle());

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameB, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsB), true, FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, pkgNameB, true, false);

        verify(mMockSpEditor, times(1)).putStringSet(Integer.toString(DEFAULT_USER_ID),
                new ArraySet<>(Arrays.asList(pkgNameB)));
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appInstalledAfterSUW_restoresFromStage_ArchiveEnabled()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, LocalesInfo> pkgLocalesMap = new HashMap<>();
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        String langTagsA = "ru";
        String langTagsB = "hi,fr";
        LocalesInfo localesInfoA = new LocalesInfo(langTagsA, false);
        LocalesInfo localesInfoB = new LocalesInfo(langTagsB, true);
        pkgLocalesMap.put(pkgNameA, localesInfoA);
        pkgLocalesMap.put(pkgNameB, localesInfoB);
        writeTestPayload(out, pkgLocalesMap);
        setUpPackageNotInstalled(pkgNameA);
        setUpPackageNotInstalled(pkgNameB);
        setUpLocalesForPackage(pkgNameA, LocaleList.getEmptyLocaleList());
        setUpLocalesForPackage(pkgNameB, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>());

        Bundle bundle = new Bundle();
        bundle.putBoolean(Intent.EXTRA_ARCHIVAL, true);
        mPackageMonitor.onPackageAddedWithExtras(pkgNameA, DEFAULT_UID, bundle);
        mPackageMonitor.onPackageAddedWithExtras(pkgNameB, DEFAULT_UID, bundle);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();

        setUpPackageInstalled(pkgNameA);

        mPackageMonitor.onPackageUpdateFinished(pkgNameA, DEFAULT_UID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameA, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsA), false, FrameworkStatsLog
                .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, pkgNameA, false, false);

        verify(mMockSpEditor, times(0)).putStringSet(anyString(), any());

        pkgLocalesMap.remove(pkgNameA);

        verifyStageDataForUser(pkgLocalesMap, DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        setUpPackageInstalled(pkgNameB);

        mPackageMonitor.onPackageUpdateFinished(pkgNameB, DEFAULT_UID);

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(pkgNameB, DEFAULT_USER_ID,
                LocaleList.forLanguageTags(langTagsB), true, FrameworkStatsLog
                .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);

        mBackupHelper.persistLocalesModificationInfo(DEFAULT_USER_ID, pkgNameB, true, false);

        verify(mMockSpEditor, times(1)).putStringSet(Integer.toString(DEFAULT_USER_ID),
            new ArraySet<>(Arrays.asList(pkgNameB)));
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testRestore_appInstalledAfterSUWAndLocalesAlreadySet_restoresNothing()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        // Package is not present on the device when the SUW restore is going on.
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageDataForUser(DEFAULT_PACKAGE_LOCALES_INFO_MAP,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        // App is installed later (post SUW).
        setUpPackageInstalled(DEFAULT_PACKAGE_NAME);
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.forLanguageTags("hi,mr"));

        mPackageMonitor.onPackageAddedWithExtras(DEFAULT_PACKAGE_NAME, DEFAULT_UID, new Bundle());

        // Since locales are already set, we should not restore anything for it.
        verifyNothingRestored();
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testStageDataDeletion_backupPassRunAfterRetentionPeriod_stageDataDeleted()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestPayload(out, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageDataForUser(DEFAULT_PACKAGE_LOCALES_INFO_MAP,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        // Retention period has not elapsed.
        setCurrentTimeMillis(
                DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.minusHours(1).toMillis());
        doReturn(List.of()).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());

        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
        checkStageDataExists(DEFAULT_USER_ID);

        // Exactly RETENTION_PERIOD amount of time has passed so stage data should still not be
        // removed.
        setCurrentTimeMillis(DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.toMillis());
        doReturn(List.of()).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());

        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
        checkStageDataExists(DEFAULT_USER_ID);

        // Retention period has now expired, stage data should be deleted.
        setCurrentTimeMillis(
                DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.plusSeconds(1).toMillis());
        doReturn(List.of()).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());

        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testStageDataDeletion_lazyRestoreAfterRetentionPeriod_stageDataDeleted()
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashMap<String, LocalesInfo> pkgLocalesMap = new HashMap<>();
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        String langTagsA = "ru";
        String langTagsB = "hi,fr";
        LocalesInfo localesInfoA = new LocalesInfo(langTagsA, false);
        LocalesInfo localesInfoB = new LocalesInfo(langTagsB, false);
        pkgLocalesMap.put(pkgNameA, localesInfoA);
        pkgLocalesMap.put(pkgNameB, localesInfoB);
        writeTestPayload(out, pkgLocalesMap);
        setUpPackageNotInstalled(pkgNameA);
        setUpPackageNotInstalled(pkgNameB);
        setUpLocalesForPackage(pkgNameA, LocaleList.getEmptyLocaleList());
        setUpLocalesForPackage(pkgNameB, LocaleList.getEmptyLocaleList());

        mBackupHelper.stageAndApplyRestoredPayload(out.toByteArray(), DEFAULT_USER_ID);

        verifyNothingRestored();
        verifyStageDataForUser(pkgLocalesMap, DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        // Retention period has not elapsed.
        setCurrentTimeMillis(
                DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.minusHours(1).toMillis());
        setUpPackageInstalled(pkgNameA);

        mPackageMonitor.onPackageAddedWithExtras(pkgNameA, DEFAULT_UID, new Bundle());

        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(
                pkgNameA, DEFAULT_USER_ID, LocaleList.forLanguageTags(langTagsA), false,
                FrameworkStatsLog.APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);

        pkgLocalesMap.remove(pkgNameA);

        verifyStageDataForUser(pkgLocalesMap, DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);

        // Retention period has now expired, stage data should be deleted.
        setCurrentTimeMillis(
                DEFAULT_CREATION_TIME_MILLIS + RETENTION_PERIOD.plusSeconds(1).toMillis());
        setUpPackageInstalled(pkgNameB);

        mPackageMonitor.onPackageAddedWithExtras(pkgNameB, DEFAULT_UID, new Bundle());

        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(eq(pkgNameB), anyInt(),
                any(), anyBoolean(), anyInt());
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
    }

    @Test
    public void testUserRemoval_userRemoved_stageDataDeleted() throws Exception {
        final ByteArrayOutputStream outDefault = new ByteArrayOutputStream();
        writeTestPayload(outDefault, DEFAULT_PACKAGE_LOCALES_INFO_MAP);
        final ByteArrayOutputStream outWorkProfile = new ByteArrayOutputStream();
        String anotherPackage = "com.android.anotherapp";
        String anotherLangTags = "mr,zh";
        LocalesInfo localesInfo = new LocalesInfo(anotherLangTags, false);
        HashMap<String, LocalesInfo> pkgLocalesMapWorkProfile = new HashMap<>();
        pkgLocalesMapWorkProfile.put(anotherPackage, localesInfo);
        writeTestPayload(outWorkProfile, pkgLocalesMapWorkProfile);
        // DEFAULT_PACKAGE_NAME is NOT installed on the device.
        setUpPackageNotInstalled(DEFAULT_PACKAGE_NAME);
        setUpPackageNotInstalled(anotherPackage);

        mBackupHelper.stageAndApplyRestoredPayload(outDefault.toByteArray(), DEFAULT_USER_ID);
        mBackupHelper.stageAndApplyRestoredPayload(outWorkProfile.toByteArray(),
                WORK_PROFILE_USER_ID);

        verifyNothingRestored();
        verifyStageDataForUser(DEFAULT_PACKAGE_LOCALES_INFO_MAP,
                DEFAULT_CREATION_TIME_MILLIS, DEFAULT_USER_ID);
        verifyStageDataForUser(pkgLocalesMapWorkProfile,
                DEFAULT_CREATION_TIME_MILLIS, WORK_PROFILE_USER_ID);

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, DEFAULT_USER_ID);
        mUserMonitor.onReceive(mMockContext, intent);

        // Stage data should be removed only for DEFAULT_USER_ID.
        checkStageDataDoesNotExist(DEFAULT_USER_ID);
        verifyStageDataForUser(pkgLocalesMapWorkProfile,
                DEFAULT_CREATION_TIME_MILLIS, WORK_PROFILE_USER_ID);
    }

    @Test
    public void testPackageRemoved_noInfoInSp() throws Exception {
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(pkgNameA, pkgNameB)));

        mBackupHelper.onPackageRemoved(DEFAULT_PACKAGE_NAME, DEFAULT_UID);

        verify(mMockSpEditor, times(0)).putStringSet(anyString(), any());
    }

    @Test
    public void testPackageRemoved_removeInfoFromSp() throws Exception {
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        Set<String> pkgNames = new ArraySet<>(Arrays.asList(pkgNameA, pkgNameB));
        setUpPackageNamesForSp(pkgNames);

        mBackupHelper.onPackageRemoved(pkgNameA, DEFAULT_UID);
        pkgNames.remove(pkgNameA);

        verify(mMockSpEditor, times(1)).putStringSet(
                Integer.toString(DEFAULT_USER_ID), pkgNames);
    }

    @Test
    public void testPackageDataCleared_removeInfoFromSp() throws Exception {
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        Set<String> pkgNames = new ArraySet<>(Arrays.asList(pkgNameA, pkgNameB));
        setUpPackageNamesForSp(pkgNames);

        mBackupHelper.onPackageDataCleared(pkgNameB, DEFAULT_UID);
        pkgNames.remove(pkgNameB);

        verify(mMockSpEditor, times(1)).putStringSet(
                Integer.toString(DEFAULT_USER_ID), pkgNames);
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
        doReturn(List.of(dummyApp)).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());
    }

    private void setUpPackageNamesForSp(Set<String> packageNames) {
        doReturn(packageNames).when(mMockDelegateAppLocalePackages).getStringSet(anyString(),
                any());
    }

    /**
     * Verifies that nothing was restored for any package.
     *
     * <p>If {@link LocaleManagerService#setApplicationLocales} is not invoked, we can conclude
     * that nothing was restored.
     */
    private void verifyNothingRestored() throws Exception {
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(anyString(), anyInt(),
                any(), anyBoolean(), anyInt());
    }

    private static void verifyPayloadForAppLocales(Map<String, LocalesInfo> expectedPkgLocalesMap,
            byte[] payload)
            throws IOException, XmlPullParserException {
        final ByteArrayInputStream stream = new ByteArrayInputStream(payload);
        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());

        Map<String, LocalesInfo> backupDataMap = new HashMap<>();
        XmlUtils.beginDocument(parser, TEST_LOCALES_XML_TAG);
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (parser.getName().equals("package")) {
                String packageName = parser.getAttributeValue(null, "name");
                String languageTags = parser.getAttributeValue(null, "locales");
                boolean delegateSelector = parser.getAttributeBoolean(null, "delegate_selector");
                LocalesInfo localesInfo = new LocalesInfo(languageTags, delegateSelector);
                backupDataMap.put(packageName, localesInfo);
            }
        }

        verifyStageData(expectedPkgLocalesMap, backupDataMap);
    }

    private static void writeTestPayload(OutputStream stream,
            Map<String, LocalesInfo> pkgLocalesMap)
            throws IOException {
        if (pkgLocalesMap.isEmpty()) {
            return;
        }

        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(/* encoding= */ null, /* standalone= */ true);
        out.startTag(/* namespace= */ null, TEST_LOCALES_XML_TAG);

        for (String pkg : pkgLocalesMap.keySet()) {
            out.startTag(/* namespace= */ null, "package");
            out.attribute(/* namespace= */ null, "name", pkg);
            out.attribute(/* namespace= */ null, "locales", pkgLocalesMap.get(pkg).mLocales);
            out.attributeBoolean(/* namespace= */ null, "delegate_selector",
                    pkgLocalesMap.get(pkg).mSetFromDelegate);
            out.endTag(/*namespace= */ null, "package");
        }

        out.endTag(/* namespace= */ null, TEST_LOCALES_XML_TAG);
        out.endDocument();
    }

    private void verifyStageDataForUser(Map<String, LocalesInfo> expectedPkgLocalesMap,
            long expectedCreationTimeMillis, int userId) {
        LocaleManagerBackupHelper.StagedData stagedDataForUser = STAGE_DATA.get(userId);
        assertNotNull(stagedDataForUser);
        assertEquals(expectedCreationTimeMillis, stagedDataForUser.mCreationTimeMillis);
        verifyStageData(expectedPkgLocalesMap, stagedDataForUser.mPackageStates);
    }

    private static void verifyStageData(Map<String, LocalesInfo> expectedPkgLocalesMap,
            Map<String, LocalesInfo> stageData) {
        assertEquals(expectedPkgLocalesMap.size(), stageData.size());
        for (String pkg : expectedPkgLocalesMap.keySet()) {
            assertTrue(stageData.containsKey(pkg));
            assertEquals(expectedPkgLocalesMap.get(pkg).mLocales, stageData.get(pkg).mLocales);
            assertEquals(expectedPkgLocalesMap.get(pkg).mSetFromDelegate,
                    stageData.get(pkg).mSetFromDelegate);
        }
    }

    private static void checkStageDataExists(int userId) {
        assertNotNull(STAGE_DATA.get(userId));
    }

    private static void checkStageDataDoesNotExist(int userId) {
        assertNull(STAGE_DATA.get(userId));
    }
}
