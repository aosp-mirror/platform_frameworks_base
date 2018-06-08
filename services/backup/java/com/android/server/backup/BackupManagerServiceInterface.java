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

package com.android.server.backup;

import android.app.IBackupAgent;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Interface for BackupManagerService.
 *
 * Current and future implementations of BackupManagerService should use this interface, so that
 * Trampoline is able to switch between them.
 */
public interface BackupManagerServiceInterface {

  void unlockSystemUser();

  // Utility: build a new random integer token
  int generateRandomIntegerToken();

  boolean setBackupPassword(String currentPw, String newPw);

  boolean hasBackupPassword();

  // fire off a backup agent, blocking until it attaches or times out
  IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode);

  // Get the restore-set token for the best-available restore set for this package:
  // the active set if possible, else the ancestral one.  Returns zero if none available.
  long getAvailableRestoreToken(String packageName);

  int requestBackup(String[] packages, IBackupObserver observer, int flags);

  int requestBackup(String[] packages, IBackupObserver observer,
      IBackupManagerMonitor monitor, int flags);

  // Cancel all running backups.
  void cancelBackups();

  void prepareOperationTimeout(int token, long interval, BackupRestoreTask callback,
      int operationType);

  // synchronous waiter case
  boolean waitUntilOperationComplete(int token);

  void tearDownAgentAndKill(ApplicationInfo app);

  boolean beginFullBackup(FullBackupJob scheduledJob);

  // The job scheduler says our constraints don't hold any more,
  // so tear down any ongoing backup task right away.
  void endFullBackup();

  void dataChanged(String packageName);

  // Initialize the given transport
  void initializeTransports(String[] transportName, IBackupObserver observer);

  // Clear the given package's backup data from the current transport
  void clearBackupData(String transportName, String packageName);

  // Run a backup pass immediately for any applications that have declared
  // that they have pending updates.
  void backupNow();

  // Run a backup pass for the given packages, writing the resulting data stream
  // to the supplied file descriptor.  This method is synchronous and does not return
  // to the caller until the backup has been completed.
  //
  // This is the variant used by 'adb backup'; it requires on-screen confirmation
  // by the user because it can be used to offload data over untrusted USB.
  void adbBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs,
      boolean includeShared, boolean doWidgets, boolean doAllApps, boolean includeSystem,
      boolean compress, boolean doKeyValue, String[] pkgList);

  void fullTransportBackup(String[] pkgNames);

  void adbRestore(ParcelFileDescriptor fd);

  // Confirm that the previously-requested full backup/restore operation can proceed.  This
  // is used to require a user-facing disclosure about the operation.
  void acknowledgeAdbBackupOrRestore(int token, boolean allow,
      String curPassword, String encPpassword, IFullBackupRestoreObserver observer);

  // Enable/disable backups
  void setBackupEnabled(boolean enable);

  // Enable/disable automatic restore of app data at install time
  void setAutoRestore(boolean doAutoRestore);

  // Mark the backup service as having been provisioned
  void setBackupProvisioned(boolean available);

  // Report whether the backup mechanism is currently enabled
  boolean isBackupEnabled();

  // Update the transport attributes
  void updateTransportAttributes(
          ComponentName transportComponent,
          String name,
          Intent configurationIntent,
          String currentDestinationString,
          Intent dataManagementIntent,
          String dataManagementLabel);

  // Report the name of the currently active transport
  String getCurrentTransport();

  // Report all known, available backup transports
  String[] listAllTransports();

  ComponentName[] listAllTransportComponents();

  String[] getTransportWhitelist();

  // Select which transport to use for the next backup operation.
  String selectBackupTransport(String transport);

  void selectBackupTransportAsync(ComponentName transport,
      ISelectBackupTransportCallback listener);

  // Supply the configuration Intent for the given transport.  If the name is not one
  // of the available transports, or if the transport does not supply any configuration
  // UI, the method returns null.
  Intent getConfigurationIntent(String transportName);

  // Supply the configuration summary string for the given transport.  If the name is
  // not one of the available transports, or if the transport does not supply any
  // summary / destination string, the method can return null.
  //
  // This string is used VERBATIM as the summary text of the relevant Settings item!
  String getDestinationString(String transportName);

  // Supply the manage-data intent for the given transport.
  Intent getDataManagementIntent(String transportName);

  // Supply the menu label for affordances that fire the manage-data intent
  // for the given transport.
  String getDataManagementLabel(String transportName);

  // Callback: a requested backup agent has been instantiated.  This should only
  // be called from the Activity Manager.
  void agentConnected(String packageName, IBinder agentBinder);

  // Callback: a backup agent has failed to come up, or has unexpectedly quit.
  // If the agent failed to come up in the first place, the agentBinder argument
  // will be null.  This should only be called from the Activity Manager.
  void agentDisconnected(String packageName);

  // An application being installed will need a restore pass, then the Package Manager
  // will need to be told when the restore is finished.
  void restoreAtInstall(String packageName, int token);

  // Hand off a restore session
  IRestoreSession beginRestoreSession(String packageName, String transport);

  // Note that a currently-active backup agent has notified us that it has
  // completed the given outstanding asynchronous backup/restore operation.
  void opComplete(int token, long result);

  boolean isAppEligibleForBackup(String packageName);

  String[] filterAppsEligibleForBackup(String[] packages);

  void dump(FileDescriptor fd, PrintWriter pw, String[] args);

  IBackupManager getBackupManagerBinder();

  // Gets access to the backup/restore agent timeout parameters.
  BackupAgentTimeoutParameters getAgentTimeoutParameters();
}
