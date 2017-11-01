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

import android.app.backup.IFullBackupRestoreObserver;
import android.os.ParcelFileDescriptor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parameters used by adbBackup() and adbRestore().
 */
public class AdbParams {

    public ParcelFileDescriptor fd;
    public final AtomicBoolean latch;
    public IFullBackupRestoreObserver observer;
    public String curPassword;     // filled in by the confirmation step
    public String encryptPassword;

    AdbParams() {
        latch = new AtomicBoolean(false);
    }
}
