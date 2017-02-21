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

import android.annotation.SystemApi;
import android.os.Bundle;

/**
 * Callback class for receiving important events during backup/restore operations.
 * Events consist mostly of errors and exceptions, giving detailed reason on why a restore/backup
 * failed or any time BackupManager makes an important decision.
 * On the other hand {@link BackupObserver} will give a failure/success view without
 * getting into details why. This callback runs on the thread it was called on because it can get
 * a bit spammy.
 * These callbacks will run on the binder thread.
 *
 * @hide
 */
@SystemApi
public class BackupManagerMonitor {

  // Logging constants for BackupManagerMonitor
  public static final int LOG_EVENT_CATEGORY_TRANSPORT = 1;
  public static final int LOG_EVENT_CATEGORY_AGENT = 2;
  public static final int LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY = 3;
  /** string : the package name */
  public static final String EXTRA_LOG_EVENT_PACKAGE_NAME =
          "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME";
  /** int : the versionCode of the package named by EXTRA_LOG_EVENT_PACKAGE_NAME */
  public static final String EXTRA_LOG_EVENT_PACKAGE_VERSION =
          "android.app.backup.extra.LOG_EVENT_PACKAGE_VERSION";
  /** int : the id of the log message, will be a unique identifier */
  public static final String EXTRA_LOG_EVENT_ID = "android.app.backup.extra.LOG_EVENT_ID";
  /**
   *  int : category will be one of
   *  { LOG_EVENT_CATEGORY_TRANSPORT,
   *    LOG_EVENT_CATEGORY_AGENT,
   *    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY}.
   */
  public static final String EXTRA_LOG_EVENT_CATEGORY =
          "android.app.backup.extra.LOG_EVENT_CATEGORY";

  /**
   * string: when we have event of id LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER we send the version
   * of the backup.
   */
  public static final String EXTRA_LOG_OLD_VERSION =
          "android.app.backup.extra.LOG_OLD_VERSION";

  // TODO complete this list with all log messages. And document properly.
  public static final int LOG_EVENT_ID_FULL_BACKUP_TIMEOUT = 4;
  public static final int LOG_EVENT_ID_PACKAGE_NOT_FOUND = 12;
  public static final int LOG_EVENT_ID_PACKAGE_TRANSPORT_NOT_PRESENT = 15;
  public static final int LOG_EVENT_ID_KEY_VALUE_BACKUP_TIMEOUT = 21;
  public static final int LOG_EVENT_ID_NO_RESTORE_METADATA_AVAILABLE = 22;
  public static final int LOG_EVENT_ID_PACKAGE_NOT_PRESENT = 26;
  public static final int LOG_EVENT_ID_APP_HAS_NO_AGENT = 28;
  public static final int LOG_EVENT_ID_CANT_FIND_AGENT = 30;
  public static final int LOG_EVENT_ID_KEY_VALUE_RESTORE_TIMEOUT = 31;
  public static final int LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER = 36;
  public static final int LOG_EVENT_ID_FULL_RESTORE_TIMEOUT = 45;
  public static final int LOG_EVENT_ID_NO_PACKAGES = 49;





  /**
   * This method will be called each time something important happens on BackupManager.
   *
   * @param event bundle will contain data about event:
   *    - event id, not optional, a unique identifier for each event.
   *    - package name, optional, the current package we're backing up/restoring if applicable.
   *    - package version, optional, the current package version  we're backing up/restoring
   *          if applicable.
   *    - category of event, not optional, one of
   *          { LOG_EVENT_CATEGORY_TRANSPORT,
   *            LOG_EVENT_CATEGORY_AGENT,
   *            LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY}
   *
   */
  public void onEvent(Bundle event) {
  }
}
