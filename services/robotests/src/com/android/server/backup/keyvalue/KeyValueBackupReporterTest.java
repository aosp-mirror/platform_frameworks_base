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
 * limitations under the License
 */

package com.android.server.backup.keyvalue;

import static com.android.server.backup.keyvalue.KeyValueBackupReporter.TAG;
import static com.android.server.backup.testing.TestUtils.assertLogcat;

import static com.google.common.truth.Truth.assertThat;

import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import com.android.server.backup.BackupManagerService;
import com.android.server.backup.remote.RemoteResult;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderPackages;
import com.android.server.testing.shadows.ShadowEventLog;
import com.android.server.testing.shadows.ShadowSlog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 26,
        shadows = {ShadowEventLog.class, ShadowSlog.class})
@SystemLoaderPackages({"com.android.server.backup"})
@Presubmit
public class KeyValueBackupReporterTest {
    @Mock private BackupManagerService mBackupManagerService;
    @Mock private IBackupObserver mObserver;
    @Mock private IBackupManagerMonitor mMonitor;

    private KeyValueBackupReporter mReporter;

    @Before
    public void setUp() throws Exception {
        mReporter = new KeyValueBackupReporter(mBackupManagerService, mObserver, mMonitor);
    }

    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = KeyValueBackupReporter.MORE_DEBUG;

        assertThat(moreDebug).isFalse();
    }

    @Test
    public void testOnNewThread_logsCorrectly() throws Exception {
        KeyValueBackupReporter.onNewThread("foo");

        assertLogcat(TAG, Log.DEBUG);
    }

    @Test
    public void testGetMonitor_returnsMonitor() throws Exception {
        IBackupManagerMonitor monitor = mReporter.getMonitor();

        assertThat(monitor).isEqualTo(mMonitor);
    }

    @Test
    public void testGetObserver_returnsObserver() throws Exception {
        IBackupObserver observer = mReporter.getObserver();

        assertThat(observer).isEqualTo(mObserver);
    }
}
