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
 * limitations under the License.
 */

package com.android.settingslib.applications;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.shadow.api.Shadow.extract;

import android.annotation.UserIdInt;
import android.app.ApplicationPackageManager;
import android.app.usage.IStorageStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.util.IconDrawableFactory;

import com.android.settingslib.applications.ApplicationsState.AppEntry;
import com.android.settingslib.applications.ApplicationsState.Callbacks;
import com.android.settingslib.applications.ApplicationsState.Session;
import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.testutils.shadow.ShadowUserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextImpl;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(shadows = {ShadowUserManager.class,
        ApplicationsStateRoboTest.ShadowIconDrawableFactory.class,
        ApplicationsStateRoboTest.ShadowPackageManager.class})
public class ApplicationsStateRoboTest {

    private final static String HOME_PACKAGE_NAME = "com.android.home";
    private final static String LAUNCHABLE_PACKAGE_NAME = "com.android.launchable";

    /** Class under test */
    private ApplicationsState mApplicationsState;

    @Mock
    private Callbacks mCallbacks;
    @Captor
    private ArgumentCaptor<ArrayList<AppEntry>> mAppEntriesCaptor;
    @Mock
    private StorageStatsManager mStorageStatsManager;

    @Implements(value = IconDrawableFactory.class, inheritImplementationMethods = true)
    public static class ShadowIconDrawableFactory {

        @Implementation
        public Drawable getBadgedIcon(ApplicationInfo appInfo) {
            return new ColorDrawable(0);
        }
    }

    @Implements(value = ApplicationPackageManager.class, inheritImplementationMethods = true)
    public static class ShadowPackageManager extends
            org.robolectric.shadows.ShadowApplicationPackageManager {

        @Implementation
        public ComponentName getHomeActivities(List<ResolveInfo> outActivities) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = new ActivityInfo();
            resolveInfo.activityInfo.packageName = HOME_PACKAGE_NAME;
            resolveInfo.activityInfo.enabled = true;
            outActivities.add(resolveInfo);
            return ComponentName.createRelative(resolveInfo.activityInfo.packageName, "foo");
        }

        public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
                @PackageManager.ResolveInfoFlags int flags, @UserIdInt int userId) {
            List<ResolveInfo> resolveInfos = new ArrayList<>();
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = new ActivityInfo();
            resolveInfo.activityInfo.packageName = LAUNCHABLE_PACKAGE_NAME;
            resolveInfo.activityInfo.enabled = true;
            resolveInfo.filter = new IntentFilter();
            resolveInfo.filter.addCategory(Intent.CATEGORY_LAUNCHER);
            resolveInfos.add(resolveInfo);
            return resolveInfos;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Robolectric does not know about the StorageStatsManager as a system service.
        // Registering a mock of this service as a replacement.
        ShadowContextImpl shadowContext = Shadow.extract(
                RuntimeEnvironment.application.getBaseContext());
        shadowContext.setSystemService(Context.STORAGE_STATS_SERVICE, mStorageStatsManager);
        StorageStats storageStats = new StorageStats();
        storageStats.codeBytes = 10;
        storageStats.dataBytes = 20;
        storageStats.cacheBytes = 30;
        when(mStorageStatsManager.queryStatsForPackage(ArgumentMatchers.any(UUID.class),
                anyString(), ArgumentMatchers.any(UserHandle.class))).thenReturn(storageStats);

        mApplicationsState = ApplicationsState.getInstance(RuntimeEnvironment.application);
        mApplicationsState.clearEntries();
    }

    private ApplicationInfo createApplicationInfo(String packageName) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.sourceDir = "foo";
        appInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        appInfo.storageUuid = UUID.randomUUID();
        appInfo.packageName = packageName;
        return appInfo;
    }

    private AppEntry createAppEntry(ApplicationInfo appInfo, int id) {
        AppEntry appEntry = new AppEntry(RuntimeEnvironment.application, appInfo, id);
        appEntry.label = "label";
        appEntry.mounted = true;
        return appEntry;
    }

    private void addApp(String packageName, int id) {
        ApplicationInfo appInfo = createApplicationInfo(packageName);
        AppEntry appEntry = createAppEntry(appInfo, id);
        mApplicationsState.mAppEntries.add(appEntry);
        mApplicationsState.mEntriesMap.get(0).put(appInfo.packageName, appEntry);
    }

    private void processAllMessages() {
        Handler mainHandler = mApplicationsState.mMainHandler;
        Handler bkgHandler = mApplicationsState.mBackgroundHandler;
        ShadowLooper shadowBkgLooper = extract(bkgHandler.getLooper());
        ShadowLooper shadowMainLooper = extract(mainHandler.getLooper());
        shadowBkgLooper.idle();
        shadowMainLooper.idle();
    }

    private AppEntry findAppEntry(List<AppEntry> appEntries, long id) {
        for (AppEntry appEntry : appEntries) {
            if (appEntry.id == id) {
                return appEntry;
            }
        }
        return null;
    }

    @Test
    public void testDefaultSessionLoadsAll() {
        Session session = mApplicationsState.newSession(mCallbacks);
        session.onResume();

        addApp(HOME_PACKAGE_NAME,1);
        addApp(LAUNCHABLE_PACKAGE_NAME,2);
        session.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(2);

        for (AppEntry appEntry : appEntries) {
            assertThat(appEntry.size).isGreaterThan(0L);
            assertThat(appEntry.icon).isNotNull();
        }

        AppEntry homeEntry = findAppEntry(appEntries, 1);
        assertThat(homeEntry.isHomeApp).isTrue();
        assertThat(homeEntry.hasLauncherEntry).isFalse();

        AppEntry launchableEntry = findAppEntry(appEntries, 2);
        assertThat(launchableEntry.hasLauncherEntry).isTrue();
        assertThat(launchableEntry.launcherEntryEnabled).isTrue();
        session.onDestroy();
    }

    @Test
    public void testCustomSessionLoadsIconsOnly() {
        Session session = mApplicationsState.newSession(mCallbacks);
        session.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_ICONS);
        session.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME,1);
        session.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNotNull();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        session.onDestroy();
    }

    @Test
    public void testCustomSessionLoadsSizesOnly() {
        Session session = mApplicationsState.newSession(mCallbacks);
        session.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_SIZES);
        session.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME,1);
        session.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNull();
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        assertThat(launchableEntry.size).isGreaterThan(0L);
        session.onDestroy();
    }

    @Test
    public void testCustomSessionLoadsHomeOnly() {
        Session session = mApplicationsState.newSession(mCallbacks);
        session.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_HOME_APP);
        session.onResume();

        addApp(HOME_PACKAGE_NAME,1);
        session.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNull();
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.isHomeApp).isTrue();
        session.onDestroy();
    }

    @Test
    public void testCustomSessionLoadsLeanbackOnly() {
        Session session = mApplicationsState.newSession(mCallbacks);
        session.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_LEANBACK_LAUNCHER);
        session.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME,1);
        session.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNull();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.isHomeApp).isFalse();
        assertThat(launchableEntry.hasLauncherEntry).isTrue();
        assertThat(launchableEntry.launcherEntryEnabled).isTrue();
        session.onDestroy();
    }
}
