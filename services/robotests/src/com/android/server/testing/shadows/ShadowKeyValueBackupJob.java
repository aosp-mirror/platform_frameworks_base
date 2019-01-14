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

package com.android.server.testing.shadows;

import android.content.Context;
import android.os.Binder;

import com.android.server.backup.BackupManagerConstants;
import com.android.server.backup.KeyValueBackupJob;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(KeyValueBackupJob.class)
public class ShadowKeyValueBackupJob {
    private static int callingUid;

    public static int getCallingUid() {
        return callingUid;
    }

    @Implementation
    protected static void schedule(int userId, Context ctx, long delay,
            BackupManagerConstants constants) {
        callingUid = Binder.getCallingUid();
    }
}
