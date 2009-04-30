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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.backup.IBackupService;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * This is the central interface between an application and Android's
 * settings backup mechanism.
 * 
 * In order to use the backup service, your application must implement a
 * subclass of BackupService, and declare an intent filter
 * in the application manifest specifying that your BackupService subclass
 * handles the {link #SERVICE_ACTION} intent action.  For example:
 * 
 * <pre class="prettyprint">
 *      &lt;!-- Use the class "MyBackupService" to perform backups for my app --&gt;
 *      &lt;service android:name=".MyBackupService"&gt;
 *          &lt;intent-filter&gt;
 *              &lt;action android:name="android.service.action.BACKUP"&gt;
 *          &lt;/intent-filter&gt;
 *      &lt;/service&gt;</pre>
 * 
 * <p><em>Not hidden but API subject to change and should not be published</em>
 */

public abstract class BackupService extends Service {
    /**
     * Service Action: Participate in the backup infrastructure.  Applications
     * that wish to use the Android backup mechanism must provide an exported
     * subclass of BackupService and give it an {@link android.content.IntentFilter
     * IntentFilter} that accepts this action. 
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_ACTION = "android.service.action.BACKUP";

    /**
     * The application is being asked to write any data changed since the
     * last time it performed a backup operation.  The state data recorded
     * during the last backup pass is provided in the oldStateFd file descriptor.
     * If oldStateFd is negative, no old state is available and the application
     * should perform a full backup.  In both cases, a representation of the
     * final backup state after this pass should be written to the file pointed
     * to by the newStateFd file descriptor.
     * 
     * @param oldStateFd An open, read-only file descriptor pointing to the last
     *                   backup state provided by the application.  May be negative,
     *                   in which case no prior state is being provided and the
     *                   application should perform a full backup.
     * @param dataFd An open, read/write file descriptor pointing to the backup data
     *               destination.  Typically the application will use backup helper
     *               classes to write to this file.
     * @param newStateFd An open, read/write file descriptor pointing to an empty
     *                   file.  The application should record the final backup state
     *                   here after writing the requested data to dataFd.
     */
    public abstract void onBackup(int oldStateFd, int dataFd, int newStateFd);
    
    /**
     * The application is being restored from backup, and should replace any
     * existing data with the contents of the backup.  The backup data is
     * provided in the file pointed to by the dataFd file descriptor.  Once
     * the restore is finished, the application should write a representation
     * of the final state to the newStateFd file descriptor, 
     * 
     * @param dataFd An open, read-only file descriptor pointing to a full snapshot
     *               of the application's data.
     * @param newStateFd An open, read/write file descriptor pointing to an empty
     *                   file.  The application should record the final backup state
     *                   here after restoring its data from dataFd.
     */
    public abstract void onRestore(int dataFd, int newStateFd);


    // ----- Core implementation -----
    
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IBinder mBinder = new BackupServiceBinder().asBinder();

    // ----- IBackupService binder interface -----
    private class BackupServiceBinder extends IBackupService.Stub {
        public void doBackup(int oldStateFd, int dataFd, int newStateFd)
                throws RemoteException {
            // !!! TODO - real implementation; for now just invoke the callbacks directly
            Log.v("BackupServiceBinder", "doBackup() invoked");
            BackupService.this.onBackup(oldStateFd, dataFd, newStateFd);
        }

        public void doRestore(int dataFd, int newStateFd) throws RemoteException {
            // !!! TODO - real implementation; for now just invoke the callbacks directly
            Log.v("BackupServiceBinder", "doRestore() invoked");
            BackupService.this.onRestore(dataFd, newStateFd);
        }
    }
}
