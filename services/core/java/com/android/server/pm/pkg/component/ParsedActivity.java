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

package com.android.server.pm.pkg.component;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;

import java.util.Set;

/** @hide **/
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedActivity extends ParsedMainComponent {

    /**
     * Generate activity object that forwards user to App Details page automatically.
     * This activity should be invisible to user and user should not know or see it.
     * @hide
     */
    @NonNull
    static ParsedActivity makeAppDetailsActivity(String packageName, String processName,
            int uiOptions, String taskAffinity, boolean hardwareAccelerated) {
        // Proxy method since ParsedActivityImpl is supposed to be package visibility
        return ParsedActivityImpl.makeAppDetailsActivity(packageName, processName, uiOptions,
                taskAffinity, hardwareAccelerated);
    }

    int getColorMode();

    int getConfigChanges();

    int getDocumentLaunchMode();

    int getLaunchMode();

    int getLockTaskLaunchMode();

    int getMaxRecents();

    float getMaxAspectRatio();

    float getMinAspectRatio();

    @Nullable
    String getParentActivityName();

    @Nullable
    String getPermission();

    /**
     * Gets the trusted host certificates of apps that are allowed to embed this activity.
     */
    @NonNull
    Set<String> getKnownActivityEmbeddingCerts();

    int getPersistableMode();

    int getPrivateFlags();

    @Nullable
    String getRequestedVrComponent();

    int getRotationAnimation();

    int getResizeMode();

    int getScreenOrientation();

    int getSoftInputMode();

    @Nullable
    String getTargetActivity();

    @Nullable
    String getTaskAffinity();

    int getTheme();

    int getUiOptions();

    @Nullable
    ActivityInfo.WindowLayout getWindowLayout();

    boolean isSupportsSizeChanges();
}
