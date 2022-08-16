/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.backup.fullbackup;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PerformFullTransportBackupTaskTest {
    @Mock
    UserBackupManagerService mBackupManagerService;
    @Mock
    TransportManager mTransportManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mBackupManagerService.getTransportManager()).thenReturn(mTransportManager);
    }

    @Test
    public void testNewWithCurrentTransport_noTransportConnection_throws() {
        when(mTransportManager.getCurrentTransportClient(any())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> {
                    PerformFullTransportBackupTask task = PerformFullTransportBackupTask
                            .newWithCurrentTransport(
                                    mBackupManagerService,
                                    /* operationStorage */  null,
                                    /* observer */  null,
                                    /* whichPackages */  null,
                                    /* updateSchedule */  false,
                                    /* runningJob */  null,
                                    /* latch */  null,
                                    /* backupObserver */  null,
                                    /* monitor */  null,
                                    /* userInitiated */  false,
                                    /* caller */  null,
                                    /* backupEligibilityRules */  null);
                });
    }
}
