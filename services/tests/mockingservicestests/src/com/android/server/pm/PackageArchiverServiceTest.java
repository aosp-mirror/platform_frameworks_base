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

import static android.content.pm.PackageManager.DELETE_KEEP_DATA;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.IntentSender;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelableException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageStateInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackageArchiverServiceTest {

    private static final String PACKAGE = "com.example";
    private static final String CALLER_PACKAGE = "com.vending";

    @Rule
    public final MockSystemRule mMockSystem = new MockSystemRule();

    @Mock
    private IntentSender mIntentSender;
    @Mock
    private Computer mComputer;
    @Mock
    private Context mContext;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private PackageInstallerService mInstallerService;
    @Mock
    private PackageStateInternal mPackageState;

    private final InstallSource mInstallSource =
            InstallSource.create(
                    CALLER_PACKAGE,
                    CALLER_PACKAGE,
                    CALLER_PACKAGE,
                    Binder.getCallingUid(),
                    CALLER_PACKAGE,
                    /* installerAttributionTag= */ null,
                    /* packageSource= */ 0);

    private final List<LauncherActivityInfo> mLauncherActivityInfos = createLauncherActivities();

    private final int mUserId = UserHandle.CURRENT.getIdentifier();

    private PackageSetting mPackageSetting;

    private PackageArchiverService mArchiveService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mMockSystem.system().stageNominalSystemState();
        when(mMockSystem.mocks().getInjector().getPackageInstallerService()).thenReturn(
                mInstallerService);
        PackageManagerService pm = spy(new PackageManagerService(mMockSystem.mocks().getInjector(),
                /* factoryTest= */false,
                MockSystem.Companion.getDEFAULT_VERSION_INFO().fingerprint,
                /* isEngBuild= */ false,
                /* isUserDebugBuild= */false,
                Build.VERSION_CODES.CUR_DEVELOPMENT,
                Build.VERSION.INCREMENTAL));

        when(mComputer.getPackageStateFiltered(eq(PACKAGE), anyInt(), anyInt())).thenReturn(
                mPackageState);
        when(mPackageState.getPackageName()).thenReturn(PACKAGE);
        when(mPackageState.getInstallSource()).thenReturn(mInstallSource);
        mPackageSetting = createBasicPackageSetting();
        when(mMockSystem.mocks().getSettings().getPackageLPr(eq(PACKAGE))).thenReturn(
                mPackageSetting);
        when(mContext.getSystemService(LauncherApps.class)).thenReturn(mLauncherApps);
        when(mLauncherApps.getActivityList(eq(PACKAGE), eq(UserHandle.CURRENT))).thenReturn(
                mLauncherActivityInfos);
        doReturn(mComputer).when(pm).snapshotComputer();
        when(mComputer.getPackageUid(eq(CALLER_PACKAGE), eq(0L), eq(mUserId))).thenReturn(
                Binder.getCallingUid());
        mArchiveService = new PackageArchiverService(mContext, pm);
    }

    @Test
    public void archiveApp_callerPackageNameIncorrect() {
        Exception e = assertThrows(
                SecurityException.class,
                () -> mArchiveService.requestArchive(PACKAGE, "different", mIntentSender,
                        UserHandle.CURRENT
                )
        );
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
                () -> mArchiveService.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT
                )
        );
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("Package %s not found.", PACKAGE));
    }

    @Test
    public void archiveApp_packageNotInstalledForUser() {
        mPackageSetting.modifyUserState(UserHandle.CURRENT.getIdentifier()).setInstalled(false);

        Exception e = assertThrows(
                ParcelableException.class,
                () -> mArchiveService.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT
                )
        );
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
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
                () -> mArchiveService.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender,
                        UserHandle.CURRENT
                )
        );
        assertThat(e.getCause()).isInstanceOf(PackageManager.NameNotFoundException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo(
                String.format("No installer found to archive app %s.", PACKAGE));
    }

    @Test
    public void archiveApp_success() {
        List<ArchiveState.ArchiveActivityInfo> activityInfos = new ArrayList<>();
        for (LauncherActivityInfo mainActivity : createLauncherActivities()) {
            // TODO(b/278553670) Extract and store launcher icons
            ArchiveState.ArchiveActivityInfo activityInfo = new ArchiveState.ArchiveActivityInfo(
                    mainActivity.getLabel().toString(),
                    Path.of("/TODO"), null);
            activityInfos.add(activityInfo);
        }

        mArchiveService.requestArchive(PACKAGE, CALLER_PACKAGE, mIntentSender, UserHandle.CURRENT);

        verify(mInstallerService).uninstall(
                eq(new VersionedPackage(PACKAGE, PackageManager.VERSION_CODE_HIGHEST)),
                eq(CALLER_PACKAGE), eq(DELETE_KEEP_DATA), eq(mIntentSender),
                eq(UserHandle.CURRENT.getIdentifier()));
        assertThat(mPackageSetting.readUserState(
                UserHandle.CURRENT.getIdentifier()).getArchiveState()).isEqualTo(
                new ArchiveState(activityInfos, CALLER_PACKAGE));
    }

    private static List<LauncherActivityInfo> createLauncherActivities() {
        LauncherActivityInfo activity1 = mock(LauncherActivityInfo.class);
        when(activity1.getLabel()).thenReturn("activity1");
        LauncherActivityInfo activity2 = mock(LauncherActivityInfo.class);
        when(activity2.getLabel()).thenReturn("activity2");
        return List.of(activity1, activity2);
    }

    private PackageSetting createBasicPackageSetting() {
        return new PackageSettingBuilder()
                .setName(PACKAGE).setCodePath("/data/app/" + PACKAGE + "-randompath")
                .setInstallState(UserHandle.CURRENT.getIdentifier(), /* installed= */ true)
                .build();
    }
}
