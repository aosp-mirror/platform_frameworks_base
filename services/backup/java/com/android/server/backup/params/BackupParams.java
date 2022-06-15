/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.backup.params;

import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;

import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.BackupEligibilityRules;

import java.util.ArrayList;

public class BackupParams {

    public TransportClient transportClient;
    public String dirName;
    public ArrayList<String> kvPackages;
    public ArrayList<String> fullPackages;
    public IBackupObserver observer;
    public IBackupManagerMonitor monitor;
    public OnTaskFinishedListener listener;
    public boolean userInitiated;
    public boolean nonIncrementalBackup;
    public BackupEligibilityRules mBackupEligibilityRules;

    public BackupParams(TransportClient transportClient, String dirName,
            ArrayList<String> kvPackages, ArrayList<String> fullPackages, IBackupObserver observer,
            IBackupManagerMonitor monitor, OnTaskFinishedListener listener, boolean userInitiated,
            boolean nonIncrementalBackup, BackupEligibilityRules backupEligibilityRules) {
        this.transportClient = transportClient;
        this.dirName = dirName;
        this.kvPackages = kvPackages;
        this.fullPackages = fullPackages;
        this.observer = observer;
        this.monitor = monitor;
        this.listener = listener;
        this.userInitiated = userInitiated;
        this.nonIncrementalBackup = nonIncrementalBackup;
        this.mBackupEligibilityRules = backupEligibilityRules;
    }
}
