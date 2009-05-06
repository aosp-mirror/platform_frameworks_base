/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.example.android.apis.app;

import android.backup.BackupService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class BackupTestService extends BackupService
{
    static final String TAG = "BackupTestService";

    @Override
    public void onBackup(ParcelFileDescriptor oldState,
            ParcelFileDescriptor data,
            ParcelFileDescriptor newState) {
        Log.d(TAG, "onBackup");
    }

    @Override
    public void onRestore(ParcelFileDescriptor data, ParcelFileDescriptor newState) {
        Log.d(TAG, "onRestore");
    }
}

