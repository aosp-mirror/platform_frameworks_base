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

import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.utils.BackupManagerMonitorEventSender;
import com.android.server.testing.shadows.ShadowEventLog;
import com.android.server.testing.shadows.ShadowSlog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowEventLog.class, ShadowSlog.class})
@Presubmit
public class KeyValueBackupReporterTest {
    @Mock private UserBackupManagerService mBackupManagerService;
    @Mock private IBackupObserver mObserver;
    @Mock private IBackupManagerMonitor mMonitor;

    private KeyValueBackupReporter mReporter;
    private BackupManagerMonitorEventSender mBackupManagerMonitorEventSender;

    @Before
    public void setUp() {
        mBackupManagerMonitorEventSender = new BackupManagerMonitorEventSender(mMonitor);
        mReporter = new KeyValueBackupReporter(
                mBackupManagerService, mObserver, mBackupManagerMonitorEventSender);
    }

    @Test
    public void testMoreDebug_isFalse() {
        assertThat(KeyValueBackupReporter.MORE_DEBUG).isFalse();
    }

    @Test
    public void testOnNewThread_logsCorrectly() {
        KeyValueBackupReporter.onNewThread("foo");

        assertLogcat(TAG, Log.DEBUG);
    }

    @Test
    public void testGetMonitor_returnsMonitor() {
        IBackupManagerMonitor monitor = mReporter.getMonitor();

        assertThat(monitor).isEqualTo(mMonitor);
    }

    @Test
    public void testGetObserver_returnsObserver() {
        IBackupObserver observer = mReporter.getObserver();

        assertThat(observer).isEqualTo(mObserver);
    }

    /**
     * Ensure that EventLog is called when logging the transport uninitialised issue.
     */
    @Test
    public void testOnTransportNotInitialized_callsEventLog() {
        ShadowEventLog.setUp();

        mReporter.onTransportNotInitialized("transport");

        assertThat(ShadowEventLog.getEntries().size()).isEqualTo(1);
    }
}
