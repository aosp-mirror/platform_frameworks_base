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

package com.android.settingslib.applications;

import static android.content.pm.Flags.FLAG_PROVIDE_INFO_OF_APK_IN_APEX;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.settingslib.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class AppUtilsTest {

    private static final String APP_PACKAGE_NAME = "com.test.app";
    private static final int APP_UID = 9999;

    @Mock
    private Drawable mIcon;

    private Context mContext;
    private AppIconCacheManager mAppIconCacheManager;
    private ApplicationInfo mAppInfo;
    private ApplicationsState.AppEntry mAppEntry;
    private ArrayList<ApplicationsState.AppEntry> mAppEntries;
    private ShadowPackageManager mShadowPackageManager;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mAppIconCacheManager = AppIconCacheManager.getInstance();
        mAppInfo = createApplicationInfo(APP_PACKAGE_NAME, APP_UID);
        mAppEntry = createAppEntry(mAppInfo, /* id= */ 1);
        mAppEntries = new ArrayList<>(Arrays.asList(mAppEntry));
        doReturn(mIcon).when(mIcon).mutate();
        mShadowPackageManager = Shadow.extract(mContext.getPackageManager());
    }

    @After
    public void tearDown() {
        AppIconCacheManager.release();
    }

    @Test
    public void getIcon_nullAppEntry_shouldReturnNull() {
        assertThat(AppUtils.getIcon(mContext, /* appEntry= */ null)).isNull();
    }

    @Test
    @Config(shadows = ShadowUtils.class)
    public void getIcon_noCachedIcon_shouldNotReturnNull() {
        assertThat(AppUtils.getIcon(mContext, mAppEntry)).isNotNull();
    }

    @Test
    public void getIcon_existCachedIcon_shouldReturnCachedIcon() {
        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);

        assertThat(AppUtils.getIcon(mContext, mAppEntry)).isEqualTo(mIcon);
    }

    @Test
    public void getIconFromCache_nullAppEntry_shouldReturnNull() {
        assertThat(AppUtils.getIconFromCache(/* appEntry= */ null)).isNull();
    }

    @Test
    public void getIconFromCache_shouldReturnCachedIcon() {
        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);

        assertThat(AppUtils.getIconFromCache(mAppEntry)).isEqualTo(mIcon);
    }

    @Test
    public void preloadTopIcons_nullAppEntries_shouldNotCrash() {
        AppUtils.preloadTopIcons(mContext, /* appEntries= */ null, /* number= */ 1);
        // no crash
    }

    @Test
    public void preloadTopIcons_zeroPreloadIcons_shouldNotCacheIcons() {
        AppUtils.preloadTopIcons(mContext, mAppEntries, /* number= */ 0);

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNull();
    }

    @Test
    @Config(shadows = ShadowUtils.class)
    public void preloadTopIcons_shouldCheckIconFromCache() throws InterruptedException {
        AppUtils.preloadTopIcons(mContext, mAppEntries, /* number= */ 1);

        TimeUnit.SECONDS.sleep(1);
        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNotNull();
    }

    @Test
    public void isAppInstalled_noAppEntry_shouldReturnFalse() {
        assertThat(AppUtils.isAppInstalled(null)).isFalse();
    }

    @Test
    public void isAppInstalled_hasAppEntryWithInstalledFlag_shouldReturnTrue() {
        final ApplicationsState.AppEntry appEntry = mock(ApplicationsState.AppEntry.class);
        appEntry.info = new ApplicationInfo();
        appEntry.info.flags = ApplicationInfo.FLAG_INSTALLED;

        assertThat(AppUtils.isAppInstalled(appEntry)).isTrue();
    }

    @Test
    public void isAppInstalled_hasAppEntryWithoutInstalledFlag_shouldReturnFalse() {
        final ApplicationsState.AppEntry appEntry = mock(ApplicationsState.AppEntry.class);
        appEntry.info = new ApplicationInfo();

        assertThat(AppUtils.isAppInstalled(appEntry)).isFalse();
    }

    @Test
    public void isMainlineModule_hasApexPackageName_shouldCheckByPackageInfo() {
        mSetFlagsRule.enableFlags(FLAG_PROVIDE_INFO_OF_APK_IN_APEX);
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = APP_PACKAGE_NAME;
        packageInfo.setApexPackageName("com.test.apex.package");
        mShadowPackageManager.installPackage(packageInfo);

        assertThat(
                AppUtils.isMainlineModule(mContext.getPackageManager(), APP_PACKAGE_NAME)).isTrue();
    }

    @Test
    public void isMainlineModule_noApexPackageName_shouldCheckBySourceDirPath() {
        mSetFlagsRule.disableFlags(FLAG_PROVIDE_INFO_OF_APK_IN_APEX);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.sourceDir = Environment.getApexDirectory().getAbsolutePath();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = APP_PACKAGE_NAME;
        packageInfo.applicationInfo = applicationInfo;
        mShadowPackageManager.installPackage(packageInfo);

        assertThat(
                AppUtils.isMainlineModule(mContext.getPackageManager(), APP_PACKAGE_NAME)).isTrue();
    }

    private ApplicationsState.AppEntry createAppEntry(ApplicationInfo appInfo, int id) {
        ApplicationsState.AppEntry appEntry = new ApplicationsState.AppEntry(mContext, appInfo, id);
        appEntry.label = "label";
        appEntry.mounted = true;
        final File apkFile = mock(File.class);
        doReturn(true).when(apkFile).exists();
        try {
            Field field = ApplicationsState.AppEntry.class.getDeclaredField("apkFile");
            field.setAccessible(true);
            field.set(appEntry, apkFile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Not able to mock apkFile: " + e);
        }
        return appEntry;
    }

    private ApplicationInfo createApplicationInfo(String packageName, int uid) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.sourceDir = "appPath";
        appInfo.packageName = packageName;
        appInfo.uid = uid;
        return appInfo;
    }

    @Implements(Utils.class)
    private static class ShadowUtils {
        @Implementation
        public static Drawable getBadgedIcon(Context context, ApplicationInfo appInfo) {
            final Drawable icon = mock(Drawable.class);
            doReturn(10).when(icon).getIntrinsicHeight();
            doReturn(10).when(icon).getIntrinsicWidth();
            doReturn(icon).when(icon).mutate();
            return icon;
        }
    }
}
