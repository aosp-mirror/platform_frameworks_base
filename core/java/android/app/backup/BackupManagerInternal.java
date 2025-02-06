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

package android.app.backup;

import android.annotation.UserIdInt;
import android.os.IBinder;

/**
 * Local system service interface for {@link com.android.server.backup.BackupManagerService}.
 *
 * @hide Only for use within the system server.
 */
public interface BackupManagerInternal {

    /**
     * Notifies the Backup Manager Service that an agent has become available. This
     * method is only invoked by the Activity Manager.
     */
    void agentConnectedForUser(String packageName, @UserIdInt int userId, IBinder agent);

    /**
     * Notify the Backup Manager Service that an agent has unexpectedly gone away.
     * This method is only invoked by the Activity Manager.
     */
    void agentDisconnectedForUser(String packageName, @UserIdInt int userId);
}
