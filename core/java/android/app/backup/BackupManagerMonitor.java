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
   * boolean: when we have an event with id LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL we record if
   * the call was to cancel backup of all packages
   */
  public static final String EXTRA_LOG_CANCEL_ALL = "android.app.backup.extra.LOG_CANCEL_ALL";

  /**
   * string: when we have an event with id LOG_EVENT_ID_ILLEGAL_KEY we send the key that was used
   * by the app
   */
  public static final String EXTRA_LOG_ILLEGAL_KEY = "android.app.backup.extra.LOG_ILLEGAL_KEY";

  /**
   * long: when we have an event with id LOG_EVENT_ID_ERROR_PREFLIGHT we send the error code that
   * was returned by the transport during preflight
   */
  public static final String EXTRA_LOG_PREFLIGHT_ERROR =
          "android.app.backup.extra.LOG_PREFLIGHT_ERROR";

  /**
   * string: when we have an event with id LOG_EVENT_ID_EXCEPTION_FULL_BACKUP we send the
   * exception's stacktrace
   */
  public static final String EXTRA_LOG_EXCEPTION_FULL_BACKUP =
          "android.app.backup.extra.LOG_EXCEPTION_FULL_BACKUP";

  /**
   * int: when we have an event with id LOG_EVENT_ID_RESTORE_VERSION_HIGHER we send the
   * restore package version
   */
  public static final String EXTRA_LOG_RESTORE_VERSION =
          "android.app.backup.extra.LOG_RESTORE_VERSION";

  /**
   * boolean: when we have an event with id LOG_EVENT_ID_RESTORE_VERSION_HIGHER we record if
   * ApplicationInfo.FLAG_RESTORE_ANY_VERSION flag is set
   */
  public static final String EXTRA_LOG_RESTORE_ANYWAY =
          "android.app.backup.extra.LOG_RESTORE_ANYWAY";


  /**
   * boolean: when we have an event with id LOG_EVENT_ID_APK_NOT_INSTALLED we record if
   * the policy allows to install apks provided with the dataset
   */
  public static final String EXTRA_LOG_POLICY_ALLOW_APKS =
          "android.app.backup.extra.LOG_POLICY_ALLOW_APKS";


  /**
   * string: when we have an event with id LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE we record the
   * package name provided in the restore manifest
   */
  public static final String EXTRA_LOG_MANIFEST_PACKAGE_NAME =
          "android.app.backup.extra.LOG_MANIFEST_PACKAGE_NAME";

  /**
   * string: when we have an event with id LOG_EVENT_ID_WIDGET_METADATA_MISMATCH we record the
   * package name provided in the widget metadata
   */
  public static final String EXTRA_LOG_WIDGET_PACKAGE_NAME =
          "android.app.backup.extra.LOG_WIDGET_PACKAGE_NAME";

  /**
   * int: when we have event of id LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER we send the version
   * of the backup.
   */
  public static final String EXTRA_LOG_OLD_VERSION = "android.app.backup.extra.LOG_OLD_VERSION";

  // TODO complete this list with all log messages. And document properly.
  public static final int LOG_EVENT_ID_FULL_BACKUP_CANCEL = 4;
  public static final int LOG_EVENT_ID_ILLEGAL_KEY = 5;
  public static final int LOG_EVENT_ID_NO_DATA_TO_SEND = 7;
  public static final int LOG_EVENT_ID_PACKAGE_INELIGIBLE = 9;
  public static final int LOG_EVENT_ID_PACKAGE_KEY_VALUE_PARTICIPANT = 10;
  public static final int LOG_EVENT_ID_PACKAGE_STOPPED = 11;
  public static final int LOG_EVENT_ID_PACKAGE_NOT_FOUND = 12;
  public static final int LOG_EVENT_ID_BACKUP_DISABLED = 13;
  public static final int LOG_EVENT_ID_DEVICE_NOT_PROVISIONED = 14;
  public static final int LOG_EVENT_ID_PACKAGE_TRANSPORT_NOT_PRESENT = 15;
  public static final int LOG_EVENT_ID_ERROR_PREFLIGHT = 16;
  public static final int LOG_EVENT_ID_QUOTA_HIT_PREFLIGHT = 18;
  public static final int LOG_EVENT_ID_EXCEPTION_FULL_BACKUP = 19;
  public static final int LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL = 21;
  public static final int LOG_EVENT_ID_NO_RESTORE_METADATA_AVAILABLE = 22;
  public static final int LOG_EVENT_ID_NO_PM_METADATA_RECEIVED = 23;
  public static final int LOG_EVENT_ID_PM_AGENT_HAS_NO_METADATA = 24;
  public static final int LOG_EVENT_ID_LOST_TRANSPORT = 25;
  public static final int LOG_EVENT_ID_PACKAGE_NOT_PRESENT = 26;
  public static final int LOG_EVENT_ID_RESTORE_VERSION_HIGHER = 27;
  public static final int LOG_EVENT_ID_APP_HAS_NO_AGENT = 28;
  public static final int LOG_EVENT_ID_SIGNATURE_MISMATCH = 29;
  public static final int LOG_EVENT_ID_CANT_FIND_AGENT = 30;
  public static final int LOG_EVENT_ID_KEY_VALUE_RESTORE_TIMEOUT = 31;
  public static final int LOG_EVENT_ID_RESTORE_ANY_VERSION = 34;
  public static final int LOG_EVENT_ID_VERSIONS_MATCH = 35;
  public static final int LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER = 36;
  public static final int LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH = 37;
  public static final int LOG_EVENT_ID_SYSTEM_APP_NO_AGENT = 38;
  public static final int LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE = 39;
  public static final int LOG_EVENT_ID_APK_NOT_INSTALLED = 40;
  public static final int LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK = 41;
  public static final int LOG_EVENT_ID_MISSING_SIGNATURE = 42;
  public static final int LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE = 43;
  public static final int LOG_EVENT_ID_UNKNOWN_VERSION = 44;
  public static final int LOG_EVENT_ID_FULL_RESTORE_TIMEOUT = 45;
  public static final int LOG_EVENT_ID_CORRUPT_MANIFEST = 46;
  public static final int LOG_EVENT_ID_WIDGET_METADATA_MISMATCH = 47;
  public static final int LOG_EVENT_ID_WIDGET_UNKNOWN_VERSION = 48;
  public static final int LOG_EVENT_ID_NO_PACKAGES = 49;
  public static final int LOG_EVENT_ID_TRANSPORT_IS_NULL = 50;





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
