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

import static android.content.pm.Flags.FLAG_REMOVE_HIDDEN_MODULE_USAGE;
import static android.content.pm.Flags.FLAG_PROVIDE_INFO_OF_APK_IN_APEX;
import static android.os.UserHandle.MU_ENABLED;
import static android.os.UserHandle.USER_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.shadow.api.Shadow.extract;

import android.annotation.UserIdInt;
import android.app.Application;
import android.app.ApplicationPackageManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.text.TextUtils;
import android.util.IconDrawableFactory;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.applications.ApplicationsState.AppEntry;
import com.android.settingslib.applications.ApplicationsState.Callbacks;
import com.android.settingslib.applications.ApplicationsState.Session;
import com.android.settingslib.testutils.shadow.ShadowUserManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextImpl;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowUserManager.class,
        ApplicationsStateRoboTest.ShadowIconDrawableFactory.class,
        ApplicationsStateRoboTest.ShadowPackageManager.class})
public class ApplicationsStateRoboTest {

    private final static String HOME_PACKAGE_NAME = "com.android.home";
    private final static String LAUNCHABLE_PACKAGE_NAME = "com.android.launchable";

    private static final int PROFILE_USERID = 10;
    private static final int PROFILE_USERID2 = 11;

    private static final String PKG_1 = "PKG1";
    private static final int OWNER_UID_1 = 1001;
    private static final int PROFILE_UID_1 = UserHandle.getUid(PROFILE_USERID, OWNER_UID_1);

    private static final String PKG_2 = "PKG2";
    private static final int OWNER_UID_2 = 1002;
    private static final int PROFILE_UID_2 = UserHandle.getUid(PROFILE_USERID, OWNER_UID_2);

    private static final String PKG_3 = "PKG3";
    private static final int OWNER_UID_3 = 1003;
    private static final int PROFILE_UID_3 = UserHandle.getUid(PROFILE_USERID2, OWNER_UID_3);

    private static final String CLONE_USER = "clone_user";
    private static final String RANDOM_USER = "random_user";

    /** Class under test */
    private ApplicationsState mApplicationsState;
    private Session mSession;

    private Application mApplication;

    @Spy
    Context mContext = ApplicationProvider.getApplicationContext();
    @Mock
    private Callbacks mCallbacks;
    @Captor
    private ArgumentCaptor<ArrayList<AppEntry>> mAppEntriesCaptor;
    @Mock
    private StorageStatsManager mStorageStatsManager;
    @Mock
    private IPackageManager mPackageManagerService;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Implements(value = IconDrawableFactory.class)
    public static class ShadowIconDrawableFactory {

        @Implementation
        protected Drawable getBadgedIcon(ApplicationInfo appInfo) {
            return new ColorDrawable(0);
        }
    }

    @Implements(value = ApplicationPackageManager.class)
    public static class ShadowPackageManager extends
            org.robolectric.shadows.ShadowApplicationPackageManager {

        // test installed modules, 2 regular, 2 hidden
        private final String[] mModuleNames = {
            "test.module.1", "test.hidden.module.2", "test.hidden.module.3", "test.module.4"};
        private final List<ModuleInfo> mInstalledModules = new ArrayList<>();

        @Implementation
        protected ComponentName getHomeActivities(List<ResolveInfo> outActivities) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = new ActivityInfo();
            resolveInfo.activityInfo.packageName = HOME_PACKAGE_NAME;
            resolveInfo.activityInfo.enabled = true;
            outActivities.add(resolveInfo);
            return ComponentName.createRelative(resolveInfo.activityInfo.packageName, "foo");
        }

        @Implementation
        public List<ModuleInfo> getInstalledModules(int flags) {
            if (mInstalledModules.isEmpty()) {
                for (String moduleName : mModuleNames) {
                    mInstalledModules.add(
                            createModuleInfo(moduleName,
                                    TextUtils.concat(moduleName, ".apex").toString()));
                }
            }
            return mInstalledModules;
        }

        public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
                @PackageManager.ResolveInfoFlagsBits int flags, @UserIdInt int userId) {
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

        private ModuleInfo createModuleInfo(String packageName, String apexPackageName) {
            final ModuleInfo info = new ModuleInfo();
            info.setName(packageName);
            info.setPackageName(packageName);
            info.setApkInApexPackageNames(Collections.singletonList(apexPackageName));
            // will treat any app with package name that contains "hidden" as hidden module
            // TODO(b/382016780): to be removed after flag cleanup.
            info.setHidden(!TextUtils.isEmpty(packageName) && packageName.contains("hidden"));
            return info;
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
        mApplication = spy(RuntimeEnvironment.application);
        StorageStats storageStats = new StorageStats();
        storageStats.codeBytes = 10;
        storageStats.cacheBytes = 30;
        // Data bytes are a superset of cache bytes.
        storageStats.dataBytes = storageStats.cacheBytes + 20;
        when(mStorageStatsManager.queryStatsForPackage(any(UUID.class),
            anyString(), any(UserHandle.class))).thenReturn(storageStats);

        // Set up 3 installed apps, in which 1 is hidden module
        final List<ApplicationInfo> infos = new ArrayList<>();
        infos.add(createApplicationInfo("test.package.1"));
        infos.add(createApplicationInfo("test.hidden.module.2"));
        infos.add(createApplicationInfo("test.package.3"));
        when(mPackageManagerService.getInstalledApplications(
            anyLong() /* flags */, anyInt() /* userId */)).thenReturn(new ParceledListSlice(infos));

        ApplicationsState.sInstance = null;
        mApplicationsState = ApplicationsState.getInstance(mApplication, mPackageManagerService);
        mApplicationsState.clearEntries();

        mSession = mApplicationsState.newSession(mCallbacks);
    }

    @After
    public void tearDown() {
        mSession.onDestroy();
    }

    private ApplicationInfo createApplicationInfo(String packageName) {
        return createApplicationInfo(packageName, 0);
    }

    private ApplicationInfo createApplicationInfo(String packageName, int uid) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.sourceDir = "foo";
        appInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        appInfo.storageUuid = UUID.randomUUID();
        appInfo.packageName = packageName;
        appInfo.uid = uid;
        return appInfo;
    }

    private AppEntry createAppEntry(ApplicationInfo appInfo, int id) {
        AppEntry appEntry = new AppEntry(RuntimeEnvironment.application, appInfo, id);
        appEntry.label = "label";
        appEntry.mounted = true;
        return appEntry;
    }

    private void addApp(String packageName, int id) {
        addApp(packageName, id, 0);
    }

    private void addApp(String packageName, int id, int userId) {
        ApplicationInfo appInfo = createApplicationInfo(packageName, id);
        AppEntry appEntry = createAppEntry(appInfo, id);
        mApplicationsState.mAppEntries.add(appEntry);
        mApplicationsState.mEntriesMap.get(userId).put(appInfo.packageName, appEntry);
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
    public void testDefaultSession_isResumed_LoadsAll() {
        mSession.onResume();

        addApp(HOME_PACKAGE_NAME, 1);
        addApp(LAUNCHABLE_PACKAGE_NAME, 2);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
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
    }

    @Test
    public void testDefaultSession_isPaused_NotLoadsAll() {
        mSession.onResume();

        addApp(HOME_PACKAGE_NAME, 1);
        addApp(LAUNCHABLE_PACKAGE_NAME, 2);
        mSession.mResumed = false;
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();

        verify(mCallbacks, never()).onRebuildComplete(mAppEntriesCaptor.capture());
    }

    @Test
    public void testCustomSessionLoadsIconsOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_ICONS);
        mSession.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNotNull();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
    }

    @Test
    public void testCustomSessionLoadsSizesOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_SIZES);
        mSession.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        assertThat(launchableEntry.size).isGreaterThan(0L);
    }

    @Test
    public void testCustomSessionLoadsHomeOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_HOME_APP);
        mSession.onResume();

        addApp(HOME_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.isHomeApp).isTrue();
    }

    @Test
    public void testCustomSessionLoadsLeanbackOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_LEANBACK_LAUNCHER);
        mSession.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.isHomeApp).isFalse();
        assertThat(launchableEntry.hasLauncherEntry).isTrue();
        assertThat(launchableEntry.launcherEntryEnabled).isTrue();
    }

    @Test
    @DisableFlags({FLAG_REMOVE_HIDDEN_MODULE_USAGE})
    public void onResume_shouldNotIncludeSystemHiddenModule() {
        mSession.onResume();

        final List<ApplicationInfo> mApplications = mApplicationsState.mApplications;
        assertThat(mApplications).hasSize(2);
        assertThat(mApplications.get(0).packageName).isEqualTo("test.package.1");
        assertThat(mApplications.get(1).packageName).isEqualTo("test.package.3");
    }

    @Test
    @EnableFlags({FLAG_REMOVE_HIDDEN_MODULE_USAGE})
    public void onResume_shouldIncludeSystemModule() {
        mSession.onResume();

        final List<ApplicationInfo> mApplications = mApplicationsState.mApplications;
        assertThat(mApplications).hasSize(3);
        assertThat(mApplications.get(0).packageName).isEqualTo("test.package.1");
        assertThat(mApplications.get(1).packageName).isEqualTo("test.hidden.module.2");
        assertThat(mApplications.get(2).packageName).isEqualTo("test.package.3");
    }

    @Test
    public void removeAndInstall_noWorkprofile_doResumeIfNeededLocked_shouldClearEntries()
            throws RemoteException {
        // scenario: only owner user
        // (PKG_1, PKG_2) -> (PKG_2, PKG_3)
        // PKG_1 is removed and PKG_3 is installed before app is resumed.
        ApplicationsState.sInstance = null;
        mApplicationsState = spy(
            ApplicationsState
                .getInstance(RuntimeEnvironment.application, mock(IPackageManager.class)));

        // Previous Applications:
        ApplicationInfo appInfo;
        final ArrayList<ApplicationInfo> prevAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        prevAppList.add(appInfo);
        mApplicationsState.mApplications = prevAppList;

        // Previous Entries:
        // (PKG_1, PKG_2)
        addApp(PKG_1, OWNER_UID_1, 0);
        addApp(PKG_2, OWNER_UID_2, 0);

        // latest Applications:
        // (PKG_2, PKG_3)
        final ArrayList<ApplicationInfo> appList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        appList.add(appInfo);
        appInfo = createApplicationInfo(PKG_3, OWNER_UID_3);
        appList.add(appInfo);
        setupDoResumeIfNeededLocked(appList, null);

        mApplicationsState.doResumeIfNeededLocked();

        verify(mApplicationsState).clearEntries();
    }

    @Test
    public void noAppRemoved_noWorkprofile_doResumeIfNeededLocked_shouldNotClearEntries()
            throws RemoteException {
        // scenario: only owner user
        // (PKG_1, PKG_2)
        ApplicationsState.sInstance = null;
        mApplicationsState = spy(
            ApplicationsState
                .getInstance(RuntimeEnvironment.application, mock(IPackageManager.class)));

        ApplicationInfo appInfo;
        // Previous Applications
        final ArrayList<ApplicationInfo> prevAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        prevAppList.add(appInfo);
        mApplicationsState.mApplications = prevAppList;

        // Previous Entries:
        // (pk1, PKG_2)
        addApp(PKG_1, OWNER_UID_1, 0);
        addApp(PKG_2, OWNER_UID_2, 0);

        // latest Applications:
        // (PKG_2, PKG_3)
        final ArrayList<ApplicationInfo> appList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        appList.add(appInfo);
        setupDoResumeIfNeededLocked(appList, null);

        mApplicationsState.doResumeIfNeededLocked();

        verify(mApplicationsState, never()).clearEntries();
    }

    @Ignore("b/328332487")
    @Test
    public void removeProfileApp_workprofileExists_doResumeIfNeededLocked_shouldClearEntries()
            throws RemoteException {
        if (!MU_ENABLED) {
            return;
        }
        // [Preconditions]
        // 2 apps (PKG_1, PKG_2) for owner, PKG_1 is not in installed state
        // 2 apps (PKG_1, PKG_2) for non-owner.
        //
        // [Actions]
        // profile user's PKG_2 is removed before resume
        //
        // Applications:
        // owner -  (PKG_1 - uninstalled, PKG_2) -> (PKG_1 - uninstalled, PKG_2)
        // profile - (PKG_1, PKG_2) -> (PKG_1)
        //
        // Previous Entries:
        // owner - (PKG_2)
        // profile - (PKG_1, PKG_2)

        ShadowUserManager shadowUserManager = Shadow
                .extract(RuntimeEnvironment.application.getSystemService(UserManager.class));
        shadowUserManager.addProfile(PROFILE_USERID, "profile");

        ApplicationsState.sInstance = null;
        mApplicationsState = spy(
            ApplicationsState
                .getInstance(RuntimeEnvironment.application, mock(IPackageManager.class)));

        ApplicationInfo appInfo;
        // Previous Applications
        // owner -  (PKG_1 - uninstalled, PKG_2)
        // profile - (PKG_1, PKG_2)
        final ArrayList<ApplicationInfo> prevAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        prevAppList.add(appInfo);

        appInfo = createApplicationInfo(PKG_1, PROFILE_UID_1);
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, PROFILE_UID_2);
        prevAppList.add(appInfo);

        mApplicationsState.mApplications = prevAppList;
        // Previous Entries:
        // owner (PKG_2), profile (pk1, PKG_2)
        // PKG_1 is not installed for owner, hence it's removed from entries
        addApp(PKG_2, OWNER_UID_2, 0);
        addApp(PKG_1, PROFILE_UID_1, PROFILE_USERID);
        addApp(PKG_2, PROFILE_UID_2, PROFILE_USERID);

        // latest Applications:
        // owner (PKG_1, PKG_2), profile (PKG_1)
        // owner's PKG_1 is still listed and is in non-installed state
        // profile user's PKG_2 is removed by a user before resume
        //owner
        final ArrayList<ApplicationInfo> ownerAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        ownerAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        ownerAppList.add(appInfo);
        //profile
        appInfo = createApplicationInfo(PKG_1, PROFILE_UID_1);
        setupDoResumeIfNeededLocked(ownerAppList, new ArrayList<>(Arrays.asList(appInfo)));

        mApplicationsState.doResumeIfNeededLocked();

        verify(mApplicationsState).clearEntries();
    }

    @Ignore("b/328332487")
    @Test
    public void removeOwnerApp_workprofileExists_doResumeIfNeededLocked_shouldClearEntries()
            throws RemoteException {
        if (!MU_ENABLED) {
            return;
        }
        // [Preconditions]
        // 2 apps (PKG_1, PKG_2) for owner, PKG_1 is not in installed state
        // 2 apps (PKG_1, PKG_2) for non-owner.
        //
        // [Actions]
        // Owner user's PKG_2 is removed before resume
        //
        // Applications:
        // owner -  (PKG_1 - uninstalled, PKG_2) -> (PKG_1 - uninstalled, PKG_2 - uninstalled)
        // profile - (PKG_1, PKG_2) -> (PKG_1, PKG_2)
        //
        // Previous Entries:
        // owner - (PKG_2)
        // profile - (PKG_1, PKG_2)

        ShadowUserManager shadowUserManager = Shadow
                .extract(RuntimeEnvironment.application.getSystemService(UserManager.class));
        shadowUserManager.addProfile(PROFILE_USERID, "profile");

        ApplicationsState.sInstance = null;
        mApplicationsState = spy(
            ApplicationsState
                .getInstance(RuntimeEnvironment.application, mock(IPackageManager.class)));

        ApplicationInfo appInfo;
        // Previous Applications:
        // owner -  (PKG_1 - uninstalled, PKG_2)
        // profile - (PKG_1, PKG_2)
        final ArrayList<ApplicationInfo> prevAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        prevAppList.add(appInfo);

        appInfo = createApplicationInfo(PKG_1, PROFILE_UID_1);
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, PROFILE_UID_2);
        prevAppList.add(appInfo);

        mApplicationsState.mApplications = prevAppList;

        // Previous Entries:
        // owner (PKG_2), profile (pk1, PKG_2)
        // PKG_1 is not installed for owner, hence it's removed from entries
        addApp(PKG_2, OWNER_UID_2, 0);
        addApp(PKG_1, PROFILE_UID_1, PROFILE_USERID);
        addApp(PKG_2, PROFILE_UID_2, PROFILE_USERID);

        // latest Applications:
        // owner (PKG_1 - uninstalled, PKG_2 - uninstalled), profile (PKG_1, PKG_2)
        // owner's PKG_1, PKG_2 is still listed and is in non-installed state
        // profile user's PKG_2 is removed before resume
        //owner
        final ArrayList<ApplicationInfo> ownerAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        ownerAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        ownerAppList.add(appInfo);

        //profile
        final ArrayList<ApplicationInfo> profileAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, PROFILE_UID_1);
        profileAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, PROFILE_UID_2);
        profileAppList.add(appInfo);
        setupDoResumeIfNeededLocked(ownerAppList, profileAppList);

        mApplicationsState.doResumeIfNeededLocked();

        verify(mApplicationsState).clearEntries();
    }

    @Ignore("b/328332487")
    @Test
    public void noAppRemoved_workprofileExists_doResumeIfNeededLocked_shouldNotClearEntries()
            throws RemoteException {
        if (!MU_ENABLED) {
            return;
        }
        // [Preconditions]
        // 2 apps (PKG_1, PKG_2) for owner, PKG_1 is not in installed state
        // 2 apps (PKG_1, PKG_2) for non-owner.
        //
        // Applications:
        // owner -  (PKG_1 - uninstalled, PKG_2)
        // profile - (PKG_1, PKG_2)
        //
        // Previous Entries:
        // owner - (PKG_2)
        // profile - (PKG_1, PKG_2)

        ShadowUserManager shadowUserManager = Shadow
                .extract(RuntimeEnvironment.application.getSystemService(UserManager.class));
        shadowUserManager.addProfile(PROFILE_USERID, "profile");

        ApplicationsState.sInstance = null;
        mApplicationsState = spy(
            ApplicationsState
                .getInstance(RuntimeEnvironment.application, mock(IPackageManager.class)));

        ApplicationInfo appInfo;
        // Previous Applications:
        // owner -  (PKG_1 - uninstalled, PKG_2)
        // profile - (PKG_1, PKG_2)
        final ArrayList<ApplicationInfo> prevAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        prevAppList.add(appInfo);

        appInfo = createApplicationInfo(PKG_1, PROFILE_UID_1);
        prevAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, PROFILE_UID_2);
        prevAppList.add(appInfo);

        mApplicationsState.mApplications = prevAppList;
        // Previous Entries:
        // owner (PKG_2), profile (pk1, PKG_2)
        // PKG_1 is not installed for owner, hence it's removed from entries
        addApp(PKG_2, OWNER_UID_2, 0);
        addApp(PKG_1, PROFILE_UID_1, PROFILE_USERID);
        addApp(PKG_2, PROFILE_UID_2, PROFILE_USERID);

        // latest Applications:
        // owner (PKG_1 - uninstalled, PKG_2), profile (PKG_1, PKG_2)
        // owner's PKG_1 is still listed and is in non-installed state

        // owner
        final ArrayList<ApplicationInfo> ownerAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, OWNER_UID_1);
        appInfo.flags ^= ApplicationInfo.FLAG_INSTALLED;
        ownerAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, OWNER_UID_2);
        ownerAppList.add(appInfo);

        // profile
        final ArrayList<ApplicationInfo> profileAppList = new ArrayList<>();
        appInfo = createApplicationInfo(PKG_1, PROFILE_UID_1);
        profileAppList.add(appInfo);
        appInfo = createApplicationInfo(PKG_2, PROFILE_UID_2);
        profileAppList.add(appInfo);
        setupDoResumeIfNeededLocked(ownerAppList, profileAppList);

        mApplicationsState.doResumeIfNeededLocked();

        verify(mApplicationsState, never()).clearEntries();
    }

    @Test
    public void testDefaultSession_enabledAppIconCache_shouldSkipPreloadIcon() {
        when(mApplication.getPackageName()).thenReturn("com.android.settings");
        mSession.onResume();

        addApp(HOME_PACKAGE_NAME, 1);
        addApp(LAUNCHABLE_PACKAGE_NAME, 2);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        for (AppEntry appEntry : appEntries) {
            assertThat(appEntry.icon).isNull();
        }
    }

    private void setupDoResumeIfNeededLocked(ArrayList<ApplicationInfo> ownerApps,
            ArrayList<ApplicationInfo> profileApps)
            throws RemoteException {

        if (ownerApps != null) {
            when(mApplicationsState.mIpm.getInstalledApplications(anyLong(), eq(0)))
                .thenReturn(new ParceledListSlice<>(ownerApps));
        }
        if (profileApps != null) {
            when(mApplicationsState.mIpm.getInstalledApplications(anyLong(), eq(PROFILE_USERID)))
                .thenReturn(new ParceledListSlice<>(profileApps));
        }
        final InterestingConfigChanges configChanges = mock(InterestingConfigChanges.class);
        when(configChanges.applyNewConfig(any(Resources.class))).thenReturn(false);
        mApplicationsState.setInterestingConfigChanges(configChanges);
    }

    @Test
    public void shouldShowInPersonalTab_forCurrentUser_returnsTrue() {
        UserManager um = RuntimeEnvironment.application.getSystemService(UserManager.class);
        ApplicationInfo appInfo = createApplicationInfo(PKG_1);
        AppEntry primaryUserApp = createAppEntry(appInfo, 1);

        assertThat(primaryUserApp.shouldShowInPersonalTab(um, appInfo.uid)).isTrue();
    }

    @Ignore("b/328332487")
    @Test
    public void shouldShowInPersonalTab_userProfilePreU_returnsFalse() {
        UserManager um = RuntimeEnvironment.application.getSystemService(UserManager.class);
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.TIRAMISU);
        // Create an app (and subsequent AppEntry) in a non-primary user profile.
        ApplicationInfo appInfo1 = createApplicationInfo(PKG_1, PROFILE_UID_1);
        AppEntry nonPrimaryUserApp = createAppEntry(appInfo1, 1);

        assertThat(nonPrimaryUserApp.shouldShowInPersonalTab(um, appInfo1.uid)).isFalse();
    }

    @Test
    public void shouldShowInPersonalTab_currentUserIsParent_returnsAsPerUserPropertyOfProfile1() {
        // Mark system user as parent for both profile users.
        UserManager um = RuntimeEnvironment.application.getSystemService(UserManager.class);
        ShadowUserManager shadowUserManager = Shadow.extract(um);
        shadowUserManager.addProfile(USER_SYSTEM, PROFILE_USERID,
                CLONE_USER, 0);
        shadowUserManager.addProfile(USER_SYSTEM, PROFILE_USERID2,
                RANDOM_USER, 0);
        shadowUserManager.setupUserProperty(PROFILE_USERID,
                /*showInSettings*/ UserProperties.SHOW_IN_SETTINGS_WITH_PARENT);
        shadowUserManager.setupUserProperty(PROFILE_USERID2,
                /*showInSettings*/ UserProperties.SHOW_IN_SETTINGS_NO);

        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);

        // Treat PROFILE_USERID as a clone user profile and create an app PKG_1 in it.
        ApplicationInfo appInfo1 = createApplicationInfo(PKG_1, PROFILE_UID_1);
        // Treat PROFILE_USERID2 as a random non-primary profile and create an app PKG_3 in it.
        ApplicationInfo appInfo2 = createApplicationInfo(PKG_3, PROFILE_UID_3);
        AppEntry nonPrimaryUserApp1 = createAppEntry(appInfo1, 1);
        AppEntry nonPrimaryUserApp2 = createAppEntry(appInfo2, 2);

        assertThat(nonPrimaryUserApp1.shouldShowInPersonalTab(um, appInfo1.uid)).isTrue();
        assertThat(nonPrimaryUserApp2.shouldShowInPersonalTab(um, appInfo2.uid)).isFalse();
    }

    @Test
    public void getEntry_hasCache_shouldReturnCacheEntry() {
        mApplicationsState.mEntriesMap.put(/* userId= */ 0, new HashMap<>());
        addApp(PKG_1, /* id= */ 1);

        assertThat(mApplicationsState.getEntry(PKG_1, /* userId= */ 0).info.packageName)
                .isEqualTo(PKG_1);
    }

    @Test
    public void getEntry_hasNoCache_shouldReturnEntry() {
        mApplicationsState.mEntriesMap.clear();
        ApplicationInfo appInfo = createApplicationInfo(PKG_1, /* uid= */ 0);
        mApplicationsState.mApplications.add(appInfo);
        // TODO(b/382016780): to be removed after flag cleanup.
        mApplicationsState.mSystemModules.put(PKG_1, /* value= */ false);

        assertThat(mApplicationsState.getEntry(PKG_1, /* userId= */ 0).info.packageName)
                .isEqualTo(PKG_1);
    }

    @Test
    @DisableFlags({FLAG_REMOVE_HIDDEN_MODULE_USAGE})
    public void isHiddenModule_hasApkInApexInfo_shouldSupportHiddenApexPackage() {
        mSetFlagsRule.enableFlags(FLAG_PROVIDE_INFO_OF_APK_IN_APEX);
        ApplicationsState.sInstance = null;
        mApplicationsState = ApplicationsState.getInstance(mApplication, mPackageManagerService);
        String normalModulePackage = "test.module.1";
        String hiddenModulePackage = "test.hidden.module.2";
        String hiddenApexPackage = "test.hidden.module.2.apex";

        assertThat(mApplicationsState.isHiddenModule(normalModulePackage)).isFalse();
        assertThat(mApplicationsState.isHiddenModule(hiddenModulePackage)).isTrue();
        assertThat(mApplicationsState.isHiddenModule(hiddenApexPackage)).isTrue();
    }

    @Test
    @DisableFlags({FLAG_REMOVE_HIDDEN_MODULE_USAGE})
    public void isHiddenModule_noApkInApexInfo_onlySupportHiddenModule() {
        mSetFlagsRule.disableFlags(FLAG_PROVIDE_INFO_OF_APK_IN_APEX);
        ApplicationsState.sInstance = null;
        mApplicationsState = ApplicationsState.getInstance(mApplication, mPackageManagerService);
        String normalModulePackage = "test.module.1";
        String hiddenModulePackage = "test.hidden.module.2";
        String hiddenApexPackage = "test.hidden.module.2.apex";

        assertThat(mApplicationsState.isHiddenModule(normalModulePackage)).isFalse();
        assertThat(mApplicationsState.isHiddenModule(hiddenModulePackage)).isTrue();
        assertThat(mApplicationsState.isHiddenModule(hiddenApexPackage)).isFalse();
    }
}
