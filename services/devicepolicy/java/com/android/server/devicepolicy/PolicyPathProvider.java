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

package com.android.server.devicepolicy;

import android.os.Environment;

import java.io.File;

/**
 * Interface providing directories for various DPMS files.
 */
public interface PolicyPathProvider {
    /**
     * Returns policy data directory for system user, typically /data/system
     * Used for SYSTEM_USER policies, device owner file and policy version file.
     */
    default File getDataSystemDirectory() {
        return Environment.getDataSystemDirectory();
    }

    /**
     * Returns policy data directory for a given user, typically /data/system/users/$userId
     * Used for non-system user policies and profile owner files.
     */
    default File getUserSystemDirectory(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }
}
