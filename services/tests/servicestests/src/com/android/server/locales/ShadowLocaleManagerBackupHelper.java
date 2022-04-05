/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locales;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.util.SparseArray;

import java.time.Clock;

/**
 * Shadow for {@link LocaleManagerBackupHelper} to enable mocking it for tests.
 *
 * <p>{@link LocaleManagerBackupHelper} is a package private class and hence not mockable directly.
 */
public class ShadowLocaleManagerBackupHelper extends LocaleManagerBackupHelper {
    ShadowLocaleManagerBackupHelper(Context context,
            LocaleManagerService localeManagerService,
            PackageManager packageManager, Clock clock,
            SparseArray<LocaleManagerBackupHelper.StagedData> stagedData,
            HandlerThread broadcastHandlerThread) {
        super(context, localeManagerService, packageManager, clock, stagedData,
                broadcastHandlerThread);
    }
}
