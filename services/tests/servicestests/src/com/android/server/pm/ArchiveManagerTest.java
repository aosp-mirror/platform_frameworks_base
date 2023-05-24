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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.pkg.PackageStateInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ArchiveManagerTest {

    private static final String PACKAGE = "com.example";
    private static final String CALLER_PACKAGE = "com.vending";
    private static final int USER_ID = 1;

    @Mock private IntentSender mIntentSender;
    @Mock private PackageManagerService mPm;
    @Mock private Computer mComputer;
    @Mock private PackageStateInternal mPackageState;
    private InstallSource mInstallSource =
            InstallSource.create(
                    CALLER_PACKAGE,
                    CALLER_PACKAGE,
                    CALLER_PACKAGE,
                    Binder.getCallingUid(),
                    CALLER_PACKAGE,
                    /* installerAttributionTag= */ null,
                    /* packageSource= */ 0);

    private ArchiveManager mArchiveManager;
    private int mCallingUid;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mArchiveManager = new ArchiveManager(mPm);
        mCallingUid = Binder.getCallingUid();
        when(mPm.snapshotComputer()).thenReturn(mComputer);
        when(mComputer.getNameForUid(eq(mCallingUid))).thenReturn(CALLER_PACKAGE);
        when(mComputer.getPackageStateInternal(eq(PACKAGE))).thenReturn(mPackageState);
        when(mPackageState.getInstallSource()).thenReturn(mInstallSource);
    }

    @Test
    public void archiveApp_callerPackageNameIncorrect() {
        Exception e = assertThrows(
                SecurityException.class,
                () -> mArchiveManager.archiveApp(PACKAGE, "different", USER_ID, mIntentSender)
        );
        assertThat(e).hasMessageThat().isEqualTo(
                String.format(
                        "The callerPackageName %s set by the caller doesn't match the "
                                + "caller's own package name %s.",
                        "different",
                        CALLER_PACKAGE));
    }

    @Test
    public void archiveApp_packageNotInstalled() {
        when(mComputer.getPackageStateInternal(eq(PACKAGE))).thenReturn(null);

        Exception e = assertThrows(
                PackageManager.NameNotFoundException.class,
                () -> mArchiveManager.archiveApp(PACKAGE, CALLER_PACKAGE, USER_ID, mIntentSender)
        );
        assertThat(e).hasMessageThat().isEqualTo(String.format("Package %s not found.", PACKAGE));
    }

    @Test
    public void archiveApp_callerNotInstallerOfRecord() {
        InstallSource otherInstallSource =
                InstallSource.create(
                        CALLER_PACKAGE,
                        CALLER_PACKAGE,
                        /* installerPackageName= */ "different",
                        Binder.getCallingUid(),
                        CALLER_PACKAGE,
                        /* installerAttributionTag= */ null,
                        /* packageSource= */ 0);
        when(mPackageState.getInstallSource()).thenReturn(otherInstallSource);

        Exception e = assertThrows(
                SecurityException.class,
                () -> mArchiveManager.archiveApp(PACKAGE, CALLER_PACKAGE, USER_ID, mIntentSender)
        );
        assertThat(e).hasMessageThat().isEqualTo(
                String.format("Caller is not the installer of record for %s.", PACKAGE));
    }

    @Test
    public void archiveApp_callerNotUpdateOwner() {
        InstallSource otherInstallSource =
                InstallSource.create(
                        CALLER_PACKAGE,
                        CALLER_PACKAGE,
                        CALLER_PACKAGE,
                        Binder.getCallingUid(),
                        /* updateOwnerPackageName= */ "different",
                        /* installerAttributionTag= */ null,
                        /* packageSource= */ 0);
        when(mPackageState.getInstallSource()).thenReturn(otherInstallSource);

        Exception e = assertThrows(
                SecurityException.class,
                () -> mArchiveManager.archiveApp(PACKAGE, CALLER_PACKAGE, USER_ID, mIntentSender)
        );
        assertThat(e).hasMessageThat().isEqualTo(
                String.format("Caller is not the update owner for %s.", PACKAGE));
    }
}
