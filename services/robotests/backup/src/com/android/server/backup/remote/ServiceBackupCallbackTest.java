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

package com.android.server.backup.remote;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.backup.IBackupManager;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ServiceBackupCallbackTest {
    @Test
    public void testOperationComplete_callsBackupManagerOpComplete() throws Exception {
        IBackupManager backupManager = mock(IBackupManager.class);
        ServiceBackupCallback callback = new ServiceBackupCallback(backupManager, 0x68e);

        callback.operationComplete(7);

        verify(backupManager).opComplete(0x68e, 7);
    }
}
