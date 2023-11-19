/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model.installstagedata;

import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import com.android.packageinstaller.v2.model.PackageUtil.AppSnippet;

public class InstallInstalling extends InstallStage {

    private final int mStage = InstallStage.STAGE_INSTALLING;
    @NonNull
    private final AppSnippet mAppSnippet;

    public InstallInstalling(@NonNull AppSnippet appSnippet) {
        mAppSnippet = appSnippet;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }

    @NonNull
    public Drawable getAppIcon() {
        return mAppSnippet.getIcon();
    }

    @NonNull
    public String getAppLabel() {
        return (String) mAppSnippet.getLabel();
    }
}
