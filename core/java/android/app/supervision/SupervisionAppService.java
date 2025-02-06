/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.app.supervision;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Base class for a service that the {@code android.app.role.RoleManager.ROLE_SYSTEM_SUPERVISION}
 * role holder must implement.
 *
 * @hide
 */
public class SupervisionAppService extends Service {
    private final ISupervisionAppService mBinder = new ISupervisionAppService.Stub() {
        @Override
        public void onEnabled() {
            SupervisionAppService.this.onEnabled();
        }

        @Override
        public void onDisabled() {
            SupervisionAppService.this.onDisabled();
        }
    };

    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder.asBinder();
    }

    /**
     * Called when supervision is enabled.
     */
    public void onEnabled() {}

    /**
     * Called when supervision is disabled.
     */
    public void onDisabled() {}
}
