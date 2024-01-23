/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.Intent.FLAG_RECEIVER_FOREGROUND;
import static android.content.pm.PackageManager.DELETE_ARCHIVE;
import static android.content.pm.PackageManager.DELETE_KEEP_DATA;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelableException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.text.TextUtils;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateImpl;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackageArchiverTest {

    private static final String PACKAGE = "com.example";
    private static final String CALLER_PACKAGE = "com.caller";
    private static final String INSTALLER_PACKAGE = "com.installer";
    private static final String INSTALLER_LABEL = "Installer";
    private static final Path ICON_PATH = Path.of("icon.png");

    @Rule
    public final MockSystemRule rule = new MockSystemRule();

    @Mock
    private IntentSender mIntentSender;
    @Mock
    private Computer mComputer;
    @Mock
    private Context mContext;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageInstallerService mInstallerService;
    @Mock
    private PackageStateInternal mPackageState;
    @Mock
    private PackageStateInternal mCallerPackageState;
    @Mock
    private Bitmap mIcon;

    private final InstallSource mInstallSource =
            InstallSource.create(
                    INSTALLER_PACKAGE,
                    INSTALLER_PACKAGE,
                    INSTALLER_PACKAGE,
                    Binder.getCallingUid(),
                    INSTALLER_PACKAGE,
                    /* installerAttributionTag= */ null,
                    /* packageSource= */ 0);

    private final List<LauncherActivityInfo> mLauncherActivityInfos = createLauncherActivities();
    private final int mUserId = UserHandle.CURRENT.getIdentifier();

    private PackageUserStateImpl mUserState;

    private PackageSetting mPackageSetting;

    private PackageManagerService mPackageManagerService;

    private PackageArchiver mArchiveManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        rule.system().stageNominalSystemState();
        when(rule.mocks().getInjector().getPackageInstallerService()).thenReturn(
                mInstallerService);
        mPackageManagerService = spy(new PackageManagerService(rule.mocks().getInjector(),
                /* factoryTest= */false,
                MockSystem.Companion.getDEFAULT_VERSION_INFO().fingerprint,
                /* isEngBuild= */ false,
                /* isUserDebugBuild= */false,
                Build.VERSION_CODES.CUR_DEVELOPMENT,
                Build.VERSION.INCREMENTAL));

        when(mComputer.getPackageStateFiltered(eq(PACKAGE), anyInt(), anyInt())).thenReturn(
                mPackageState);
        when(mComputer.getPackageStateFiltered(eq(INSTALLER_PACKAGE), anyInt(),
                anyInt())).thenReturn(mock(PackageStateInternal.class));
        when(mComputer.getPackageStateFiltered(eq(CALLER_PACKAGE), anyInt(), anyInt())).thenReturn(
                mCallerPackageState);
        AndroidPackage androidPackage = mock(AndroidPackage.class);
        when(mCallerPackageState.getAndroidPackage()).thenReturn(androidPackage);
        when(androidPackage.getRequestedPermissions()).thenReturn(Set.of());
        when(mPackageState.getPackageName()).thenReturn(PACKAGE);
        when(mPackageState.getInstallSource()).thenReturn(mInstallSource);
        mPackageSetting = createBasicPackageSetting();
        when(rule.mocks().getSettings().getPackageLPr(eq(PACKAGE))).thenReturn(
                mPackageSetting);
        mUserState = new PackageUserStateImpl().setInstalled(true);
        mPackageSetting.setUserState(mUserId, mUserState);
        when(mPackageState.getUserStateOrDefault(eq(mUserId))).thenReturn(mUserState);
        when(mPackageState.getUserStates()).thenReturn(new SparseArray<>());

        when(mContext.getSystemService(LauncherApps.class)).thenReturn(mLauncherApps);
        when(mContext.getSystemService(AppOpsManager.class)).thenReturn(
                mAppOpsManager);
        when(mLauncherApps.getActivityList(eq(PACKAGE), eq(UserHandle.CURRENT))).thenReturn(
                mLauncherActivityInfos);

        when(mContext.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);
        when(mActivityManager.getLauncherLargeIconDensity()).thenReturn(100);
        when(mContext.checkCallingOrSelfPermission(
                eq(Manifest.permission.REQUEST_INSTALL_PACKAGES))).thenReturn(
                PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(
                eq(Manifest.permission.REQUEST_DELETE_PACKAGES))).thenReturn(
                PackageManager.PERMISSION_DENIED);

        when(mAppOpsManager.checkOp(
                eq(AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED),
                anyInt(), eq(PACKAGE))).thenReturn(MODE_ALLOWED);
        doReturn(mComputer).when(mPackageManagerService).snapshotComputer();
        when(mComputer.getPackageUid(eq(CALLER_PACKAGE), eq(0L), eq(mUserId))).thenReturn(
                Binder.getCallingUid());

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getResourcesForApplication(eq(PACKAGE))).thenReturn(
                mock(Resources.class));
        ApplicationInfo installerAi = mock(ApplicationInfo.class);
        when(mComputer.getApplicationInfo(eq(INSTALLER_PACKAGE), anyLong(), anyInt())).thenReturn(
                installerAi);
        when(installerAi.loadLabel(any())).thenReturn(INSTALLER_LABEL);
        when(mInstallerService.createSessionInternal(any(), any(), any(), anyInt(),
                anyInt())).thenReturn(1);
        when(mInstallerService.getExistingDraftSessionId(anyInt(), any(), anyInt())).thenReturn(
                PackageInstaller.SessionInfo.INVALID_ID);
        PackageInstallerSession session = mock(PackageInstallerSession.class);
        when(mInstallerService.getSession(anyInt())).thenReturn(session);
        when(session.getUnarchivalStatus()).thenReturn(PackageInstaller.UNARCHIVAL_STATUS_UNSET);
        doReturn(new ParceledListSlice<>(List.of(mock(ResolveInfo.class))))
                .when(mPackageManagerService).queryIntentReceivers(any(), any(), any(), anyLong(),
                        eq(mUserId));

        mArchiveManager = spy(new PackageArchiver(mContext, mPackageManagerService));
        doReturn(ICON_PATH).when(mArchiveManager).storeIcon(eq(PACKAGE),
                any(LauncherActivityInfo.class), eq(mUserId), anyInt(), anyInt());
        doReturn(mIcon).when(mArchiveManager).decodeIcon(
                any(ArchiveState.ArchiveActivityInfo.class));
        Resources mockResources = mock(Resources.class);
        doReturn(mockResources)
                .when(mContext)
                .getResources();
    }

    @Test
    public void archiveApp_callerPackageNameIncorrect() {
        Exception e = assertThrows(
                SecurityException.class,
                () -> mArchiveManager.requestArchive(PACKAGE, "different", mIntentSender,
                        UserHandle.CURRENT));
        assertThat(e).hasMessageThat().isEqualTo(
                String.format(
                        "The UID %s of callerPackageName set by the caller doesn't match the "
                                + "caller's actual UID %s.",
                        0,
                        Binder.getCallingUid()));
    }

    @Test
    public void archiveApp_packageNotInstalled() {
        when(mComputer.getPackageStateFiltered(eq(PACKAGE), anyInt(), anyInt())).thenReturn(
                null);

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("Package %s not found.", PACKAGE));
    }

    @Test
    public void archiveApp_packageNotInstalledForUser() throws IntentSender.SendIntentException {
        mPackageSetting.modifyUserState(UserHandle.CURRENT.getIdentifier()).setInstalled(false);

        mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender, UserHandle.CURRENT);
        rule.mocks().getHandler().flush();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mIntentSender).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any(),
                any(), any());
        Intent value = intentCaptor.getValue();
        assertThat(value.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)).isEqualTo(PACKAGE);
        assertThat(value.getIntExtra(PackageInstaller.EXTRA_STATUS, 0)).isEqualTo(
                PackageInstaller.STATUS_FAILURE);
        assertThat(value.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)).isEqualTo(
                String.format("Package %s not found.", PACKAGE));
    }

    @Test
    public void archiveApp_noInstallerFound() {
        InstallSource otherInstallSource =
                InstallSource.create(
                        CALLER_PACKAGE,
                        CALLER_PACKAGE,
                        /* installerPackageName= */ null,
                        Binder.getCallingUid(),
                        /* updateOwnerPackageName= */ null,
                        /* installerAttributionTag= */ null,
                        /* packageSource= */ 0);
        when(mPackageState.getInstallSource()).thenReturn(otherInstallSource);

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo("No installer found");
    }

    @Test
    public void archiveApp_installerDoesntSupportUnarchival() {
        doReturn(new ParceledListSlice<>(List.of()))
                .when(mPackageManagerService).queryIntentReceivers(any(), any(), any(), anyLong(),
                        eq(mUserId));

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                "Installer does not support unarchival");
    }

    @Test
    public void archiveApp_noMainActivities() {
        when(mLauncherApps.getActivityList(eq(PACKAGE), eq(UserHandle.CURRENT))).thenReturn(
                List.of());

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                TextUtils.formatSimple("The app %s does not have a main activity.", PACKAGE));
    }

    @Test
    public void archiveApp_storeIconFails() throws IntentSender.SendIntentException, IOException {
        IOException e = new IOException("IO");
        doThrow(e).when(mArchiveManager).storeIcon(eq(PACKAGE),
                any(LauncherActivityInfo.class), eq(mUserId), anyInt(), anyInt());

        mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender, UserHandle.CURRENT);
        rule.mocks().getHandler().flush();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mIntentSender).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any(),
                any(), any());
        Intent value = intentCaptor.getValue();
        assertThat(value.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)).isEqualTo(PACKAGE);
        assertThat(value.getIntExtra(PackageInstaller.EXTRA_STATUS, 0)).isEqualTo(
                PackageInstaller.STATUS_FAILURE);
        assertThat(value.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)).isEqualTo(
                e.toString());
    }

    @Test
    public void archiveApp_appOptedOutOfArchiving() {
        when(mAppOpsManager.checkOpNoThrow(
                eq(AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED),
                anyInt(), eq(PACKAGE))).thenReturn(MODE_IGNORED);

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                TextUtils.formatSimple("The app %s is opted out of archiving.", PACKAGE));
    }

    @Test
    public void archiveApp_withNoAdditionalFlags_success() {
        mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender, UserHandle.CURRENT);
        rule.mocks().getHandler().flush();

        verify(mInstallerService).uninstall(
                eq(new VersionedPackage(PACKAGE, PackageManager.VERSION_CODE_HIGHEST)),
                eq(CALLER_PACKAGE), eq(DELETE_ARCHIVE | DELETE_KEEP_DATA), eq(mIntentSender),
                eq(UserHandle.CURRENT.getIdentifier()), anyInt(), anyInt());

        ArchiveState expectedArchiveState = createArchiveState();
        ArchiveState actualArchiveState = mPackageSetting.readUserState(
                UserHandle.CURRENT.getIdentifier()).getArchiveState();
        assertThat(actualArchiveState.getActivityInfos())
                .isEqualTo(expectedArchiveState.getActivityInfos());
        assertThat(actualArchiveState.getInstallerTitle())
                .isEqualTo(expectedArchiveState.getInstallerTitle());
        // The timestamps are expected to be different
        assertThat(actualArchiveState.getArchiveTimeMillis())
                .isNotEqualTo(expectedArchiveState.getArchiveTimeMillis());
    }

    @Test
    public void archiveApp_withAdditionalFlags_success() {
        mArchiveManager.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender, UserHandle.CURRENT);
        rule.mocks().getHandler().flush();

        verify(mInstallerService).uninstall(
                eq(new VersionedPackage(PACKAGE, PackageManager.VERSION_CODE_HIGHEST)),
                eq(CALLER_PACKAGE),
                eq(DELETE_ARCHIVE | DELETE_KEEP_DATA),
                eq(mIntentSender),
                eq(UserHandle.CURRENT.getIdentifier()), anyInt(), anyInt());

        ArchiveState expectedArchiveState = createArchiveState();
        ArchiveState actualArchiveState = mPackageSetting.readUserState(
                UserHandle.CURRENT.getIdentifier()).getArchiveState();
        assertThat(actualArchiveState.getActivityInfos())
                .isEqualTo(expectedArchiveState.getActivityInfos());
        assertThat(actualArchiveState.getInstallerTitle())
                .isEqualTo(expectedArchiveState.getInstallerTitle());
        // The timestamps are expected to be different
        assertThat(actualArchiveState.getArchiveTimeMillis())
                .isNotEqualTo(expectedArchiveState.getArchiveTimeMillis());
    }

    @Test
    public void isAppArchivable_success() throws PackageManager.NameNotFoundException {
        assertThat(mArchiveManager.isAppArchivable(PACKAGE, UserHandle.CURRENT)).isTrue();
    }

    @Test
    public void isAppArchivable_installerDoesntSupportUnarchival()
            throws PackageManager.NameNotFoundException {
        doReturn(new ParceledListSlice<>(List.of()))
                .when(mPackageManagerService).queryIntentReceivers(any(), any(), any(), anyLong(),
                        eq(mUserId));

        assertThat(mArchiveManager.isAppArchivable(PACKAGE, UserHandle.CURRENT)).isFalse();
    }

    @Test
    public void isAppArchivable_noMainActivities() throws PackageManager.NameNotFoundException {
        when(mLauncherApps.getActivityList(eq(PACKAGE), eq(UserHandle.CURRENT))).thenReturn(
                List.of());

        assertThat(mArchiveManager.isAppArchivable(PACKAGE, UserHandle.CURRENT)).isFalse();
    }

    @Test
    public void isAppArchivable_appOptedOutOfArchiving()
            throws PackageManager.NameNotFoundException {
        when(mAppOpsManager.checkOpNoThrow(
                eq(AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED),
                anyInt(), eq(PACKAGE))).thenReturn(MODE_IGNORED);

        assertThat(mArchiveManager.isAppArchivable(PACKAGE, UserHandle.CURRENT)).isFalse();
    }

    @Test
    public void unarchiveApp_callerPackageNameIncorrect() {
        mUserState.setArchiveState(createArchiveState()).setInstalled(false);

        Exception e = assertThrows(
                SecurityException.class,
                () -> mArchiveManager.requestUnarchive(PACKAGE, "different",
                        mIntentSender, UserHandle.CURRENT));
        assertThat(e).hasMessageThat().isEqualTo(
                String.format(
                        "The UID %s of callerPackageName set by the caller doesn't match the "
                                + "caller's actual UID %s.",
                        0,
                        Binder.getCallingUid()));
    }

    @Test
    public void unarchiveApp_packageNotInstalled() {
        mUserState.setArchiveState(createArchiveState()).setInstalled(false);
        when(mComputer.getPackageStateFiltered(eq(PACKAGE), anyInt(), anyInt())).thenReturn(
                null);

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestUnarchive(PACKAGE, CALLER_PACKAGE,
                        mIntentSender, UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("Package %s not found.", PACKAGE));
    }

    @Test
    public void unarchiveApp_notArchived_missingArchiveState() {
        mUserState.setInstalled(false);
        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestUnarchive(PACKAGE, CALLER_PACKAGE,
                        mIntentSender, UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("Package %s is not currently archived.", PACKAGE));
    }

    @Test
    public void unarchiveApp_notArchived_stillInstalled() {
        mUserState.setArchiveState(createArchiveState());
        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestUnarchive(PACKAGE, CALLER_PACKAGE,
                        mIntentSender, UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("Package %s is not currently archived.", PACKAGE));
    }


    @Test
    public void unarchiveApp_noInstallerFound() {
        mUserState.setArchiveState(createArchiveState()).setInstalled(false);
        InstallSource otherInstallSource =
                InstallSource.create(
                        CALLER_PACKAGE,
                        CALLER_PACKAGE,
                        /* installerPackageName= */ null,
                        Binder.getCallingUid(),
                        /* updateOwnerPackageName= */ null,
                        /* installerAttributionTag= */ null,
                        /* packageSource= */ 0);
        when(mPackageState.getInstallSource()).thenReturn(otherInstallSource);

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveManager.requestUnarchive(PACKAGE, CALLER_PACKAGE,
                        mIntentSender, UserHandle.CURRENT));
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("No installer found to unarchive app %s.", PACKAGE));
    }

    @Test
    public void unarchiveApp_success() {
        mUserState.setArchiveState(createArchiveState()).setInstalled(false);

        mArchiveManager.requestUnarchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                UserHandle.CURRENT);
        rule.mocks().getHandler().flush();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendOrderedBroadcastAsUser(
                intentCaptor.capture(),
                eq(UserHandle.CURRENT),
                /* receiverPermission = */ isNull(),
                eq(AppOpsManager.OP_NONE),
                any(Bundle.class),
                /* resultReceiver= */ isNull(),
                /* scheduler= */ isNull(),
                /* initialCode= */ eq(0),
                /* initialData= */ isNull(),
                /* initialExtras= */ isNull());
        Intent intent = intentCaptor.getValue();
        assertThat(intent.getFlags() & FLAG_RECEIVER_FOREGROUND).isNotEqualTo(0);
        assertThat(intent.getIntExtra(PackageInstaller.EXTRA_UNARCHIVE_ID, -1)).isEqualTo(1);
        assertThat(intent.getStringExtra(PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME)).isEqualTo(
                PACKAGE);
        assertThat(
                intent.getBooleanExtra(PackageInstaller.EXTRA_UNARCHIVE_ALL_USERS, true)).isFalse();
        assertThat(intent.getPackage()).isEqualTo(INSTALLER_PACKAGE);
    }

    @Test
    public void getArchivedAppIcon_packageNotInstalled() {
        when(mComputer.getPackageStateFiltered(eq(PACKAGE), anyInt(), anyInt())).thenReturn(
                null);

        assertThat(mArchiveManager.getArchivedAppIcon(PACKAGE, UserHandle.CURRENT)).isNull();
    }

    @Test
    public void getArchivedAppIcon_notArchived() {
        assertThat(mArchiveManager.getArchivedAppIcon(PACKAGE, UserHandle.CURRENT)).isNull();
    }

    @Test
    public void getArchivedAppIcon_success() {
        mUserState.setArchiveState(createArchiveState()).setInstalled(false);

        assertThat(mArchiveManager.getArchivedAppIcon(PACKAGE, UserHandle.CURRENT)).isEqualTo(
                mIcon);
    }


    private static ArchiveState createArchiveState() {
        List<ArchiveState.ArchiveActivityInfo> activityInfos = new ArrayList<>();
        for (LauncherActivityInfo mainActivity : createLauncherActivities()) {
            ArchiveState.ArchiveActivityInfo activityInfo = new ArchiveState.ArchiveActivityInfo(
                    mainActivity.getLabel().toString(),
                    mainActivity.getComponentName(),
                    ICON_PATH, null);
            activityInfos.add(activityInfo);
        }
        return new ArchiveState(activityInfos, INSTALLER_LABEL);
    }

    private static List<LauncherActivityInfo> createLauncherActivities() {
        ActivityInfo activityInfo = mock(ActivityInfo.class);
        LauncherActivityInfo activity1 = mock(LauncherActivityInfo.class);
        when(activity1.getLabel()).thenReturn("activity1");
        when(activity1.getComponentName()).thenReturn(new ComponentName("pkg1", "class1"));
        when(activity1.getActivityInfo()).thenReturn(activityInfo);
        LauncherActivityInfo activity2 = mock(LauncherActivityInfo.class);
        when(activity2.getLabel()).thenReturn("activity2");
        when(activity2.getComponentName()).thenReturn(new ComponentName("pkg2", "class2"));
        when(activity2.getActivityInfo()).thenReturn(activityInfo);
        return List.of(activity1, activity2);
    }

    private PackageSetting createBasicPackageSetting() {
        return new PackageSettingBuilder()
                .setName(PACKAGE).setCodePath("/data/app/" + PACKAGE + "-randompath")
                .setInstallState(UserHandle.CURRENT.getIdentifier(), /* installed= */ true)
                .build();
    }
}
