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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Environment;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.SimpleClock;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.XmlUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
    private static final String STAGE_LOCALES_XML_TAG = "locales";
    private static final int DEFAULT_USER_ID = 0;
    private static final int INVALID_UID = -1;
    private static final LocaleList DEFAULT_LOCALES =
            LocaleList.forLanguageTags(DEFAULT_LOCALE_TAGS);
    private static final Map<String, String> DEFAULT_PACKAGE_LOCALES_MAP = Map.of(
            DEFAULT_PACKAGE_NAME, DEFAULT_LOCALE_TAGS);

    private LocaleManagerBackupHelper mBackupHelper;
    private long mCurrentTimeMillis;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManagerInternal mMockPackageManagerInternal;
    @Mock
    private LocaleManagerService mMockLocaleManagerService;

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

        mMockLocaleManagerService = mock(LocaleManagerService.class);

        mBackupHelper = new LocaleManagerBackupHelper(mMockContext, mMockLocaleManagerService,
                mMockPackageManagerInternal,
                new File(Environment.getExternalStorageDirectory(), "lmsUnitTests"),
                mClock);
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

    private void verifyPayloadForAppLocales(Map<String, String> expectedPkgLocalesMap,
            byte[] payload)
            throws IOException, XmlPullParserException {
        final ByteArrayInputStream stream = new ByteArrayInputStream(payload);
        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());

        Map<String, String> backupDataMap = new HashMap<>();
        XmlUtils.beginDocument(parser, STAGE_LOCALES_XML_TAG);
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
}
