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

package com.android.server.app;

import android.annotation.NonNull;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;

final class GameClassifierImpl implements GameClassifier {

    private final PackageManager mPackageManager;

    GameClassifierImpl(@NonNull PackageManager packageManager) {
        mPackageManager = packageManager;
    }

    @Override
    public boolean isGame(@NonNull String packageName, @NonNull UserHandle userHandle) {
        @ApplicationInfo.Category
        int applicationCategory = ApplicationInfo.CATEGORY_UNDEFINED;

        try {
            applicationCategory =
                    mPackageManager.getApplicationInfoAsUser(
                            packageName,
                            0,
                            userHandle.getIdentifier()).category;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }

        return applicationCategory == ApplicationInfo.CATEGORY_GAME;
    }
}
