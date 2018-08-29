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

    @Test
    public void testOnRevertTask_logsCorrectly() throws Exception {
        setMoreDebug(true);

        mReporter.onRevertTask();

        assertLogcat(TAG, Log.INFO);
    }

    @Test
    public void testOnRemoteCallReturned_logsCorrectly() throws Exception {
        setMoreDebug(true);

        mReporter.onRemoteCallReturned(RemoteResult.of(3), "onFoo()");

        assertLogcat(TAG, Log.VERBOSE);
        ShadowLog.LogItem log = ShadowLog.getLogsForTag(TAG).get(0);
        assertThat(log.msg).contains("onFoo()");
        assertThat(log.msg).contains("3");
    }

    /**
     * HACK: We actually want {@link KeyValueBackupReporter#MORE_DEBUG} to be a constant to be able
     * to strip those lines at build time. So, we have to do this to test :(
     */
    private static void setMoreDebug(boolean value)
            throws NoSuchFieldException, IllegalAccessException {
        if (KeyValueBackupReporter.MORE_DEBUG == value) {
            return;
        }
        Field moreDebugField = KeyValueBackupReporter.class.getDeclaredField("MORE_DEBUG");
        moreDebugField.setAccessible(true);
        moreDebugField.set(null, value);
    }
}
