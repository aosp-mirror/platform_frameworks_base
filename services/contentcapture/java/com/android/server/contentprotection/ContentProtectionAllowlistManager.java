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

package com.android.server.contentprotection;

import android.annotation.NonNull;
import android.util.Slog;

/**
 * Manages whether the content protection is enabled for an app using a allowlist.
 *
 * @hide
 */
public class ContentProtectionAllowlistManager {

    private static final String TAG = "ContentProtectionAllowlistManager";

    public ContentProtectionAllowlistManager() {}

    /** Returns true if the package is allowed. */
    public boolean isAllowed(@NonNull String packageName) {
        Slog.v(TAG, packageName);
        return false;
    }
}
