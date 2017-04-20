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

package com.android.server.backup.fullbackup;

import android.app.IBackupAgent;
import android.content.pm.PackageInfo;

/**
 * Callout from the engine to an interested participant that might need to communicate with the
 * agent prior to asking it to move data.
 */
public interface FullBackupPreflight {

    /**
     * Perform the preflight operation necessary for the given package.
     *
     * @param pkg The name of the package being proposed for full-data backup
     * @param agent Live BackupAgent binding to the target app's agent
     * @return BackupTransport.TRANSPORT_OK to proceed with the backup operation, or one of the
     * other BackupTransport.* error codes as appropriate
     */
    int preflightFullBackup(PackageInfo pkg, IBackupAgent agent);

    long getExpectedSizeOrErrorCode();
}
