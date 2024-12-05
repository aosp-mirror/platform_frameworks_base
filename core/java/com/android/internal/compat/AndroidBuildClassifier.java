/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;

import android.os.Build;

/**
 * Platform private class for determining the type of Android build installed.
 *
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class AndroidBuildClassifier {

    public boolean isDebuggableBuild() {
        return Build.IS_DEBUGGABLE;
    }

    public boolean isFinalBuild() {
        return "REL".equals(Build.VERSION.CODENAME);
    }

    /**
     * The current platform SDK version.
     */
    public int platformTargetSdk() {
        if (isFinalBuild()) {
            return Build.VERSION.SDK_INT;
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }
}
