/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.LocaleList;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Set;

/**
 * Unit tests for {@link AppUpdateTracker}.
 */
@RunWith(AndroidJUnit4.class)
public class AppUpdateTrackerTest {
    private static final String DEFAULT_PACKAGE_NAME = "com.android.myapp";
    private static final int DEFAULT_UID = Binder.getCallingUid() + 100;
    private static final int DEFAULT_USER_ID = 0;
    private static final String DEFAULT_LOCALE_TAGS = "en-XC,ar-XB";
    private static final LocaleList DEFAULT_LOCALES = LocaleList.forLanguageTags(
            DEFAULT_LOCALE_TAGS);
    private AppUpdateTracker mAppUpdateTracker;

    @Mock
    private Context mMockContext;
    @Mock
    private LocaleManagerService mMockLocaleManagerService;
    @Mock
    private ShadowLocaleManagerBackupHelper mMockBackupHelper;

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockLocaleManagerService = mock(LocaleManagerService.class);
        mMockBackupHelper = mock(ShadowLocaleManagerBackupHelper.class);
        mAppUpdateTracker = spy(
                new AppUpdateTracker(mMockContext, mMockLocaleManagerService, mMockBackupHelper));
    }

    @Test
    public void testPackageUpgraded_localeEmpty_doNothing() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, LocaleList.getEmptyLocaleList());
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));
        setUpPackageLocaleConfig(null, DEFAULT_PACKAGE_NAME);
        setUpAppLocalesOptIn(true);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verifyNoLocalesCleared();
    }

    @Test
    public void testPackageUpgraded_pkgNotInSp_doNothing() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        String pkgNameA = "com.android.myAppA";
        String pkgNameB = "com.android.myAppB";
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(pkgNameA, pkgNameB)));
        setUpPackageLocaleConfig(null, DEFAULT_PACKAGE_NAME);
        setUpAppLocalesOptIn(true);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verifyNoLocalesCleared();
    }

    @Test
    public void testPackageUpgraded_appLocalesSupported_doNothing() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));
        setUpPackageLocaleConfig(DEFAULT_LOCALES, DEFAULT_PACKAGE_NAME);

        setUpAppLocalesOptIn(true);
        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verifyNoLocalesCleared();

        setUpAppLocalesOptIn(false);
        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verifyNoLocalesCleared();

        setUpAppLocalesOptIn(false);
        setUpPackageLocaleConfig(null, DEFAULT_PACKAGE_NAME);
        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verifyNoLocalesCleared();
    }

    @Test
    public void testPackageUpgraded_appLocalesNotSupported_clearAppLocale() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));
        setUpPackageLocaleConfig(null, DEFAULT_PACKAGE_NAME);
        setUpAppLocalesOptIn(true);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, LocaleList.forLanguageTags(""), false);

        setUpPackageLocaleConfig(LocaleList.getEmptyLocaleList(), DEFAULT_PACKAGE_NAME);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verify(mMockLocaleManagerService, times(2)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, LocaleList.forLanguageTags(""), false);

        setUpAppLocalesOptIn(false);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verify(mMockLocaleManagerService, times(3)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, LocaleList.forLanguageTags(""), false);
    }

    @Test
    public void testPackageUpgraded_appLocalesNotInLocaleConfig_clearAppLocale() throws Exception {
        setUpLocalesForPackage(DEFAULT_PACKAGE_NAME, DEFAULT_LOCALES);
        setUpPackageNamesForSp(new ArraySet<>(Arrays.asList(DEFAULT_PACKAGE_NAME)));
        setUpPackageLocaleConfig(LocaleList.forLanguageTags("hi,fr"), DEFAULT_PACKAGE_NAME);
        setUpAppLocalesOptIn(true);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verify(mMockLocaleManagerService, times(1)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, LocaleList.forLanguageTags(""), false);

        setUpAppLocalesOptIn(false);

        mAppUpdateTracker.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME, DEFAULT_UID);
        verify(mMockLocaleManagerService, times(2)).setApplicationLocales(DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_ID, LocaleList.forLanguageTags(""), false);
    }

    private void setUpLocalesForPackage(String packageName, LocaleList locales) throws Exception {
        doReturn(locales).when(mMockLocaleManagerService).getApplicationLocales(eq(packageName),
                anyInt());
    }

    private void setUpPackageNamesForSp(Set<String> packageNames) {
        SharedPreferences mockSharedPreference = mock(SharedPreferences.class);
        doReturn(mockSharedPreference).when(mMockBackupHelper).getPersistedInfo();
        doReturn(packageNames).when(mockSharedPreference).getStringSet(anyString(), any());
    }

    private void setUpPackageLocaleConfig(LocaleList locales, String packageName) {
        doReturn(locales).when(mAppUpdateTracker).getPackageLocales(eq(packageName), anyInt());
    }

    private void setUpAppLocalesOptIn(boolean optIn) {
        doReturn(optIn).when(mAppUpdateTracker).isSettingsAppLocalesOptIn();
    }

    /**
     * Verifies that no app locales needs to be cleared for any package.
     *
     * <p>If {@link LocaleManagerService#setApplicationLocales} is not invoked when receiving the
     * callback of package upgraded, we can conclude that no app locales needs to be cleared.
     */
    private void verifyNoLocalesCleared() throws Exception {
        verify(mMockLocaleManagerService, times(0)).setApplicationLocales(anyString(), anyInt(),
                any(), anyBoolean());
    }
}
