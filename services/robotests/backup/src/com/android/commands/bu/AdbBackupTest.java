/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.commands.bu;

import static org.mockito.Mockito.verify;

import android.app.backup.IBackupManager;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowParcelFileDescriptor;

/** Unit tests for {@link com.android.commands.bu.Backup}. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowParcelFileDescriptor.class})
@Presubmit
public class AdbBackupTest {
    @Mock private IBackupManager mBackupManager;
    private Backup mBackup;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mBackup = new Backup(mBackupManager);
    }

    @Test
    public void testRun_whenUserNotSpecified_callsAdbBackupAsSystemUser() throws Exception {
        mBackup.run(new String[] {"backup", "-all"});

        verify(mBackupManager).isBackupServiceActive(UserHandle.USER_SYSTEM);
    }

    @Test
    public void testRun_whenUserSpecified_callsBackupManagerAsSpecifiedUser() throws Exception {
        mBackup.run(new String[] {"backup", "-user", "10", "-all"});

        verify(mBackupManager).isBackupServiceActive(10);
    }
}
