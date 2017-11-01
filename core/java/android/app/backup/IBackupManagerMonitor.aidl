/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.app.backup;

import android.os.Bundle;

/**
 * Callback class for receiving important events during backup/restore operations.
 * These callbacks will run on the binder thread.
 *
 * @hide
 */
oneway interface IBackupManagerMonitor {

  /**
   * This method will be called each time something important happens on BackupManager.
   *
   * @param event bundle will contain data about event, like package name, package version etc.
   */
  void onEvent(in Bundle event);

}