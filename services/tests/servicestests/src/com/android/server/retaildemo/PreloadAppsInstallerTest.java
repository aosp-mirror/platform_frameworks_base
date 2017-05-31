/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.retaildemo;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.FileUtils;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PreloadAppsInstallerTest {
    private static final int TEST_DEMO_USER = 111;

    private Context mContext;
    private @Mock IPackageManager mIpm;
    private MockContentResolver mContentResolver;
    private File mPreloadsAppsDirectory;
    private String[] mPreloadedApps =
            new String[] {"test1.apk.preload", "test2.apk.preload", "test3.apk.preload"};
    private ArrayList<String> mPreloadedAppPaths = new ArrayList<>();

    private PreloadAppsInstaller mInstaller;

    @BeforeClass
    @AfterClass
    public static void clearSettingsProvider() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        initializePreloadedApps();
        Settings.Secure.putStringForUser(mContentResolver,
                Settings.Secure.DEMO_USER_SETUP_COMPLETE, "0", TEST_DEMO_USER);

        mInstaller = new PreloadAppsInstaller(mContext, mIpm, mPreloadsAppsDirectory);
    }

    private void initializePreloadedApps() throws Exception {
        mPreloadsAppsDirectory = new File(InstrumentationRegistry.getContext().getFilesDir(),
                 "test_preload_apps_dir");
        mPreloadsAppsDirectory.mkdir();
        for (String name : mPreloadedApps) {
            final File f = new File(mPreloadsAppsDirectory, name);
            f.createNewFile();
            mPreloadedAppPaths.add(f.getPath());
        }
    }

    @After
    public void tearDown() {
        if (mPreloadsAppsDirectory != null) {
            FileUtils.deleteContentsAndDir(mPreloadsAppsDirectory);
        }
    }

    @Test
    public void testInstallApps() throws Exception {
        mInstaller.installApps(TEST_DEMO_USER);
        for (String path : mPreloadedAppPaths) {
            ArgumentCaptor<IPackageInstallObserver2> observer =
                    ArgumentCaptor.forClass(IPackageInstallObserver2.class);
            verify(mIpm).installPackageAsUser(eq(path), observer.capture(), anyInt(),
                    anyString(), eq(TEST_DEMO_USER));
            observer.getValue().onPackageInstalled(path, PackageManager.INSTALL_SUCCEEDED,
                    null, null);
            // Verify that we try to install the package in system user.
            verify(mIpm).installExistingPackageAsUser(path, UserHandle.USER_SYSTEM,
                    0 /*installFlags*/, PackageManager.INSTALL_REASON_UNKNOWN);
        }
        assertEquals("DEMO_USER_SETUP should be set to 1 after preloaded apps are installed",
                "1",
                Settings.Secure.getStringForUser(mContentResolver,
                        Settings.Secure.DEMO_USER_SETUP_COMPLETE, TEST_DEMO_USER));
    }

    @Test
    public void testInstallApps_noPreloads() throws Exception {
        // Delete all files in preloaded apps directory - no preloaded apps
        FileUtils.deleteContents(mPreloadsAppsDirectory);
        mInstaller.installApps(TEST_DEMO_USER);
        assertEquals("DEMO_USER_SETUP should be set to 1 after preloaded apps are installed",
                "1",
                Settings.Secure.getStringForUser(mContentResolver,
                        Settings.Secure.DEMO_USER_SETUP_COMPLETE, TEST_DEMO_USER));
    }

    @Test
    public void testInstallApps_installationFails() throws Exception {
        mInstaller.installApps(TEST_DEMO_USER);
        for (int i = 0; i < mPreloadedAppPaths.size(); ++i) {
            ArgumentCaptor<IPackageInstallObserver2> observer =
                    ArgumentCaptor.forClass(IPackageInstallObserver2.class);
            final String path = mPreloadedAppPaths.get(i);
            verify(mIpm).installPackageAsUser(eq(path), observer.capture(), anyInt(),
                    anyString(), eq(TEST_DEMO_USER));
            if (i == 0) {
                observer.getValue().onPackageInstalled(path, PackageManager.INSTALL_FAILED_DEXOPT,
                        null, null);
                continue;
            }
            observer.getValue().onPackageInstalled(path, PackageManager.INSTALL_SUCCEEDED,
                    null, null);
            // Verify that we try to install the package in system user.
            verify(mIpm).installExistingPackageAsUser(path, UserHandle.USER_SYSTEM,
                    0 /*installFlags*/, PackageManager.INSTALL_REASON_UNKNOWN);
        }
        assertEquals("DEMO_USER_SETUP should be set to 1 after preloaded apps are installed",
                "1",
                Settings.Secure.getStringForUser(mContentResolver,
                        Settings.Secure.DEMO_USER_SETUP_COMPLETE, TEST_DEMO_USER));
    }
}
