/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.platform.test.ravenwood;

import static android.permission.PermissionManager.PERMISSION_GRANTED;

import android.content.AttributionSource;
import android.os.PermissionEnforcer;

public class RavenwoodPermissionEnforcer extends PermissionEnforcer {
    @Override
    protected int checkPermission(String permission, AttributionSource source) {
        // For the moment, since Ravenwood doesn't offer cross-process capabilities, assume all
        // permissions are granted during tests
        return PERMISSION_GRANTED;
    }

    @Override
    protected int checkPermission(String permission, int pid, int uid) {
        // For the moment, since Ravenwood doesn't offer cross-process capabilities, assume all
        // permissions are granted during tests
        return PERMISSION_GRANTED;
    }
}
