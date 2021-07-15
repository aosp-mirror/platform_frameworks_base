/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server;

import java.util.List;

/**
 * Shim to allow core/backup to communicate with the app widget service
 * about various important events without needing to be able to see the
 * implementation of the service.
 *
 * @hide
 */
public interface WidgetBackupProvider {
    public List<String> getWidgetParticipants(int userId);
    public byte[] getWidgetState(String packageName, int userId);
    public void systemRestoreStarting(int userId);
    public void restoreWidgetState(String packageName, byte[] restoredState, int userId);
    public void systemRestoreFinished(int userId);
}
