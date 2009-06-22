/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.backup;

import android.backup.IRestoreSession;

/**
 * Direct interface to the Backup Manager Service that applications invoke on.  The only
 * operation currently needed is a simple notification that the app has made changes to
 * data it wishes to back up, so the system should run a backup pass.
 *
 * Apps will use the {@link android.backup.BackupManager} class rather than going through
 * this Binder interface directly.
 * 
 * {@hide}
 */
interface IBackupManager {
    /**
     * Tell the system service that the caller has made changes to its
     * data, and therefore needs to undergo an incremental backup pass.
     */
    void dataChanged(String packageName);

    /**
     * Notifies the Backup Manager Service that an agent has become available.  This
     * method is only invoked by the Activity Manager.
     */
    void agentConnected(String packageName, IBinder agent);

    /**
     * Notify the Backup Manager Service that an agent has unexpectedly gone away.
     * This method is only invoked by the Activity Manager.
     */
    void agentDisconnected(String packageName);

    /**
     * Schedule an immediate backup attempt for all pending updates.  This is
     * primarily intended for transports to use when they detect a suitable
     * opportunity for doing a backup pass.  If there are no pending updates to
     * be sent, no action will be taken.  Even if some updates are pending, the
     * transport will still be asked to confirm via the usual requestBackupTime()
     * method.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    void backupNow();

    /**
     * Identify the currently selected transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     */
    int getCurrentTransport();

    /**
     * Specify a default backup transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     *
     * @param transportID The ID of the transport to select.  This should be one
     * of {@link BackupManager.TRANSPORT_GOOGLE} or {@link BackupManager.TRANSPORT_ADB}.
     * @return The ID of the previously selected transport.
     */
    int selectBackupTransport(int transportID);

    /**
     * Begin a restore session with the given transport (which may differ from the
     * currently-active backup transport).
     *
     * @return An interface to the restore session, or null on error.
     */
    IRestoreSession beginRestoreSession(int transportID);
}
