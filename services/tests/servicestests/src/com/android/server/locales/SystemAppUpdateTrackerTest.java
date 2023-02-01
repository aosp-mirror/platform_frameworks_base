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

import static android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.LocaleList;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link SystemAppUpdateTracker}.
 */
public class SystemAppUpdateTrackerTest {
    private static final String DEFAULT_PACKAGE_NAME_1 = "com.android.myapp1";
    private static final String DEFAULT_PACKAGE_NAME_2 = "com.android.myapp2";
    private static final String DEFAULT_LOCALE_TAGS = "en-XC,ar-XB";
    private static final LocaleList DEFAULT_LOCALES =
            LocaleList.forLanguageTags(DEFAULT_LOCALE_TAGS);
    private static final String PACKAGE_XML_TAG = "package";
    private static final String ATTR_NAME = "name";
    private static final String SYSTEM_APPS_XML_TAG = "system_apps";
    private static final int DEFAULT_USER_ID = 0;

    private AtomicFile mStoragefile;
    private static final String DEFAULT_INSTALLER_PACKAGE_NAME = "com.android.myapp.installer";
    private static final InstallSourceInfo DEFAULT_INSTALL_SOURCE_INFO = new InstallSourceInfo(
            /* initiatingPackageName = */ null, /* initiatingPackageSigningInfo = */ null,
            /* originatingPackageName = */ null,
            /* installingPackageName = */ DEFAULT_INSTALLER_PACKAGE_NAME,
            /* updateOwnerPackageName = */ null,
            /* packageSource = */ PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);

    @Mock
    private Context mMockContext;
    @Mock
    PackageManager mMockPackageManager;
    @Mock
    private ActivityTaskManagerInternal mMockActivityTaskManager;
    @Mock
    private ActivityManagerInternal mMockActivityManager;

    PackageMonitor mPackageMonitor;
    private LocaleManagerService mLocaleManagerService;

    // Object under test.
    private SystemAppUpdateTracker mSystemAppUpdateTracker;

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockActivityTaskManager = mock(ActivityTaskManagerInternal.class);
        mMockActivityManager = mock(ActivityManagerInternal.class);
        mMockPackageManager = mock(PackageManager.class);
        LocaleManagerBackupHelper mockLocaleManagerBackupHelper =
                mock(ShadowLocaleManagerBackupHelper.class);
        // PackageMonitor is not needed in LocaleManagerService for these tests hence it is
        // passed as null.
        mLocaleManagerService = new LocaleManagerService(mMockContext,
                mMockActivityTaskManager, mMockActivityManager,
                mMockPackageManager, mockLocaleManagerBackupHelper,
                /* mPackageMonitor= */ null);

        doReturn(DEFAULT_USER_ID).when(mMockActivityManager)
                .handleIncomingUser(anyInt(), anyInt(), eq(DEFAULT_USER_ID), anyBoolean(), anyInt(),
                        anyString(), anyString());

        doReturn(DEFAULT_INSTALL_SOURCE_INFO).when(mMockPackageManager)
                .getInstallSourceInfo(anyString());
        doReturn(mMockPackageManager).when(mMockContext).getPackageManager();
        doReturn(InstrumentationRegistry.getContext().getContentResolver())
                .when(mMockContext).getContentResolver();

        mStoragefile = new AtomicFile(new File(
                Environment.getExternalStorageDirectory(), "systemUpdateUnitTests.xml"));

        mSystemAppUpdateTracker = new SystemAppUpdateTracker(mMockContext,
            mLocaleManagerService, mStoragefile);

        AppUpdateTracker appUpdateTracker = mock(AppUpdateTracker.class);
        mPackageMonitor = new LocaleManagerServicePackageMonitor(mockLocaleManagerBackupHelper,
                mSystemAppUpdateTracker, appUpdateTracker, mLocaleManagerService);
    }

    @After
    public void tearDown() {
        mStoragefile.delete();
    }

    @Test
    public void testInit_loadsCorrectly() throws Exception {
        doReturn(createApplicationInfoForApp(DEFAULT_PACKAGE_NAME_1,
            /* isUpdatedSystemApp = */ true))
            .when(mMockPackageManager).getApplicationInfo(eq(DEFAULT_PACKAGE_NAME_1), any());

        // Updates the app once so that it writes to the file.
        mPackageMonitor.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME_1,
                Binder.getCallingUid());
        // Clear the in-memory data of updated apps
        mSystemAppUpdateTracker.getUpdatedApps().clear();
        // Invoke init to verify if it correctly populates in-memory set.
        mSystemAppUpdateTracker.init();

        assertEquals(Set.of(DEFAULT_PACKAGE_NAME_1), mSystemAppUpdateTracker.getUpdatedApps());
    }

    @Test
    public void testOnPackageUpdatedFinished_systemAppFirstUpdate_writesToFile() throws Exception {
        doReturn(createApplicationInfoForApp(DEFAULT_PACKAGE_NAME_1,
            /* isUpdatedSystemApp = */ true))
            .when(mMockPackageManager).getApplicationInfo(eq(DEFAULT_PACKAGE_NAME_1), any());
        doReturn(new ActivityTaskManagerInternal.PackageConfig(/* nightMode = */ 0,
                        DEFAULT_LOCALES, GRAMMATICAL_GENDER_NOT_SPECIFIED))
                .when(mMockActivityTaskManager)
                .getApplicationConfig(anyString(), anyInt());

        mPackageMonitor.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME_1,
                Binder.getCallingUid());

        assertBroadcastSentToInstaller(DEFAULT_PACKAGE_NAME_1, DEFAULT_LOCALES);
        Set<String> expectedAppList = Set.of(DEFAULT_PACKAGE_NAME_1);
        assertEquals(expectedAppList, mSystemAppUpdateTracker.getUpdatedApps());
        verifyStorageFileContents(expectedAppList);
    }

    @Test
    public void testOnPackageUpdatedFinished_systemAppSecondUpdate_doesNothing() throws Exception {
        doReturn(createApplicationInfoForApp(DEFAULT_PACKAGE_NAME_1,
            /* isUpdatedSystemApp = */ true))
            .when(mMockPackageManager).getApplicationInfo(eq(DEFAULT_PACKAGE_NAME_1), any());
        doReturn(new ActivityTaskManagerInternal.PackageConfig(/* nightMode = */ 0,
                        DEFAULT_LOCALES, GRAMMATICAL_GENDER_NOT_SPECIFIED))
                .when(mMockActivityTaskManager)
                .getApplicationConfig(anyString(), anyInt());

        // first update
        mPackageMonitor.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME_1,
                Binder.getCallingUid());

        assertBroadcastSentToInstaller(DEFAULT_PACKAGE_NAME_1, DEFAULT_LOCALES);
        Set<String> expectedAppList = Set.of(DEFAULT_PACKAGE_NAME_1);
        assertEquals(expectedAppList, mSystemAppUpdateTracker.getUpdatedApps());
        verifyStorageFileContents(expectedAppList);

        // second update
        mPackageMonitor.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME_1,
                Binder.getCallingUid());
        // getApplicationLocales should be invoked only once on the first update.
        verify(mMockActivityTaskManager, times(1))
                .getApplicationConfig(anyString(), anyInt());
        // Broadcast should be sent only once on first update.
        verify(mMockContext, times(1)).sendBroadcastAsUser(any(), any());
        // Verify that the content remains the same.
        verifyStorageFileContents(expectedAppList);
    }

    @Test
    public void testOnPackageUpdatedFinished_notSystemApp_doesNothing() throws Exception {
        doReturn(createApplicationInfoForApp(DEFAULT_PACKAGE_NAME_2,
            /* isUpdatedSystemApp = */false))
            .when(mMockPackageManager).getApplicationInfo(eq(DEFAULT_PACKAGE_NAME_2), any());

        mPackageMonitor.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME_2,
                Binder.getCallingUid());

        assertTrue(!mSystemAppUpdateTracker.getUpdatedApps().contains(DEFAULT_PACKAGE_NAME_2));
        // getApplicationLocales should be never be invoked if not a system app.
        verifyZeroInteractions(mMockActivityTaskManager);
        // Broadcast should be never sent if not a system app.
        verify(mMockContext, never()).sendBroadcastAsUser(any(), any());
        // It shouldn't write to the file if not a system app.
        assertTrue(!mStoragefile.getBaseFile().isFile());
    }

    @Test
    public void testOnPackageUpdatedFinished_noInstaller_doesNothing() throws Exception {
        doReturn(createApplicationInfoForApp(DEFAULT_PACKAGE_NAME_1,
            /* isUpdatedSystemApp = */ true))
            .when(mMockPackageManager).getApplicationInfo(eq(DEFAULT_PACKAGE_NAME_1), any());
        doReturn(null).when(mMockPackageManager).getInstallSourceInfo(anyString());

        mPackageMonitor.onPackageUpdateFinished(DEFAULT_PACKAGE_NAME_1,
                Binder.getCallingUid());

        // getApplicationLocales should be never be invoked if not installer is not present.
        verifyZeroInteractions(mMockActivityTaskManager);
        // Broadcast should be never sent if installer is not present.
        verify(mMockContext, never()).sendBroadcastAsUser(any(), any());
        // It shouldn't write to file if no installer present.
        assertTrue(!mStoragefile.getBaseFile().isFile());
    }

    private void verifyStorageFileContents(Set<String> expectedAppList)
            throws IOException, XmlPullParserException {
        assertTrue(mStoragefile.getBaseFile().isFile());
        try (InputStream storageInputStream = mStoragefile.openRead()) {
            assertEquals(expectedAppList, readFromXml(storageInputStream));
        } catch (IOException | XmlPullParserException e) {
            throw e;
        }
    }

    private Set<String> readFromXml(InputStream storageInputStream)
            throws XmlPullParserException, IOException {
        Set<String> outputList = new HashSet<>();
        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(storageInputStream, StandardCharsets.UTF_8.name());
        XmlUtils.beginDocument(parser, SYSTEM_APPS_XML_TAG);
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (parser.getName().equals(PACKAGE_XML_TAG)) {
                String packageName = parser.getAttributeValue(/* namespace= */ null,
                        ATTR_NAME);
                if (!TextUtils.isEmpty(packageName)) {
                    outputList.add(packageName);
                }
            }
        }
        return outputList;
    }

    /**
     * Verifies the broadcast sent to the installer of the updated app.
     */
    private void assertBroadcastSentToInstaller(String packageName, LocaleList locales) {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext).sendBroadcastAsUser(captor.capture(), any(UserHandle.class));
        for (Intent intent : captor.getAllValues()) {
            assertTrue(Intent.ACTION_APPLICATION_LOCALE_CHANGED.equals(intent.getAction()));
            assertTrue(DEFAULT_INSTALLER_PACKAGE_NAME.equals(intent.getPackage()));
            assertTrue(packageName.equals(intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)));
            assertTrue(locales.equals(intent.getParcelableExtra(Intent.EXTRA_LOCALE_LIST)));
        }
    }

    private ApplicationInfo createApplicationInfoForApp(String packageName,
            boolean isUpdatedSystemApp) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        if (isUpdatedSystemApp) {
            applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        }
        return applicationInfo;
    }
}
