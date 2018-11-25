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

package com.android.commands.bmgr;

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

/** Unit tests for {@link com.android.commands.bmgr.Bmgr}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BmgrTest {
    @Mock private IBackupManager mBackupManager;
    private Bmgr mBmgr;

    /** Common setup run before each test method. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mBmgr = new Bmgr(mBackupManager);
    }

    /**
     * Test that bmgr uses the default user {@link UserHandle.USER_SYSTEM} if no user is specified.
     */
    @Test
    public void testRun_whenUserNotSpecified_callsBackupManagerAsSystemUser() throws Exception {
        mBmgr.run(new String[] {"run"});

        verify(mBackupManager).isBackupServiceActive(UserHandle.USER_SYSTEM);
    }

    /** Test that bmgr uses the specified user if an user is specified. */
    @Test
    public void testRun_whenUserSpecified_callsBackupManagerAsSpecifiedUser() throws Exception {
        mBmgr.run(new String[] {"--user", "10"});

        verify(mBackupManager).isBackupServiceActive(10);
    }
}
