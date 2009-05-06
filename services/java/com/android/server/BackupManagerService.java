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

package com.android.server;

import android.backup.BackupService;
import android.backup.IBackupService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import android.backup.IBackupManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.String;
import java.util.HashSet;
import java.util.List;

class BackupManagerService extends IBackupManager.Stub {
    private static final String TAG = "BackupManagerService";
    private static final boolean DEBUG = true;
    
    private static final long COLLECTION_INTERVAL = 3 * 60 * 1000;

    private static final int MSG_RUN_BACKUP = 1;
    
    private Context mContext;
    private PackageManager mPackageManager;
    private final BackupHandler mBackupHandler = new BackupHandler();
    // map UIDs to the set of backup client services within that UID's app set
    private SparseArray<HashSet<ServiceInfo>> mBackupParticipants
        = new SparseArray<HashSet<ServiceInfo>>();
    // set of backup services that have pending changes
    private class BackupRequest {
        public ServiceInfo service;
        public boolean fullBackup;
        
        BackupRequest(ServiceInfo svc, boolean isFull) {
            service = svc;
            fullBackup = isFull;
        }
    }
    private HashSet<BackupRequest> mPendingBackups = new HashSet<BackupRequest>();
    private final Object mQueueLock = new Object();

    private File mStateDir;
    private File mDataDir;
    
    // ----- Handler that runs the actual backup process asynchronously -----

    private class BackupHandler extends Handler implements ServiceConnection {
        private volatile Object mBindSignaller = new Object();
        private volatile boolean mBinding = false;
        private IBackupService mTargetService = null;

        public void handleMessage(Message msg) {

            switch (msg.what) {
            case MSG_RUN_BACKUP:
            {
                // snapshot the pending-backup set and work on that
                HashSet<BackupRequest> queue;
                synchronized (mQueueLock) {
                    queue = mPendingBackups;
                    mPendingBackups = new HashSet<BackupRequest>();
                    // !!! TODO: start a new backup-queue journal file too
                }
                
                // Walk the set of pending backups, setting up the relevant files and
                // invoking the backup service in each participant
                Intent backupIntent = new Intent(BackupService.SERVICE_ACTION);
                for (BackupRequest request : queue) {
                    mBinding = true;
                    mTargetService = null;

                    backupIntent.setClassName(request.service.packageName, request.service.name);
                    Log.d(TAG, "binding to " + backupIntent);
                    if (mContext.bindService(backupIntent, this, 0)) {
                        synchronized (mBindSignaller) {
                            while (mTargetService == null && mBinding == true) {
                                try {
                                    mBindSignaller.wait();
                                } catch (InterruptedException e) {
                                }
                            }
                        }
                        if (mTargetService != null) {
                            try {
                                Log.d(TAG, "invoking doBackup() on " + backupIntent);

                                // !!! TODO right now these naming schemes limit applications to
                                // one backup service per package
                                File savedStateName = new File(mStateDir,
                                        request.service.packageName);
                                File dummyName = new File(mStateDir, "#####");
                                File backupDataName = new File(mDataDir,
                                        request.service.packageName + ".data");
                                File newStateName = new File(mStateDir,
                                        request.service.packageName + ".new");
                                
                                // In a full backup, we pass a file that is never writeable, hence
                                // is always zero-sized, as a sentinel to the callee that they must
                                // write all of their data.
                                ParcelFileDescriptor savedState = 
                                    ParcelFileDescriptor.open(
                                            (request.fullBackup) ? savedStateName : dummyName,
                                            ParcelFileDescriptor.MODE_READ_ONLY |
                                            ParcelFileDescriptor.MODE_CREATE);

                                backupDataName.delete();
                                ParcelFileDescriptor backupData =
                                        ParcelFileDescriptor.open(backupDataName,
                                                ParcelFileDescriptor.MODE_READ_WRITE |
                                                ParcelFileDescriptor.MODE_CREATE);

                                newStateName.delete();
                                ParcelFileDescriptor newState =
                                        ParcelFileDescriptor.open(newStateName,
                                                ParcelFileDescriptor.MODE_READ_WRITE |
                                                ParcelFileDescriptor.MODE_CREATE);

                                // Run the target's backup pass
                                try {
                                    mTargetService.doBackup(savedState, backupData, newState);
                                } finally {
                                    savedState.close();
                                    backupData.close();
                                    newState.close();
                                }

                                // !!! TODO: Now propagate the newly-backed-up data to the transport
                                
                                // !!! TODO: After successful transport, delete the now-stale data
                                // and juggle the files so that next time the new state is passed
                                backupDataName.delete();
                                newStateName.renameTo(savedStateName);
                                
                            } catch (FileNotFoundException fnf) {
                                Log.d(TAG, "File not found on backup: ");
                                fnf.printStackTrace();
                            } catch (RemoteException e) {
                                Log.d(TAG, "Remote target " + backupIntent
                                        + " threw during backup:");
                                e.printStackTrace();
                            } catch (Exception e) {
                                Log.w(TAG, "Final exception guard in backup: ");
                                e.printStackTrace();
                            }
                            mContext.unbindService(this);
                        }
                    } else {
                        Log.d(TAG, "Unable to bind to " + backupIntent);
                    }
                }
            }
            break;
            }
        }
        
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mBindSignaller) {
                mTargetService = IBackupService.Stub.asInterface(service);
                mBinding = false;
                mBindSignaller.notifyAll();
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            synchronized (mBindSignaller) {
                mTargetService = null;
                mBinding = false;
                mBindSignaller.notifyAll();
            }
        }
    }
    
    public BackupManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();

        // Set up our bookkeeping
        mStateDir = new File(Environment.getDataDirectory(), "backup");
        mStateDir.mkdirs();
        mDataDir = Environment.getDownloadCacheDirectory();
        
        // Identify the backup participants
        // !!! TODO: also watch package-install to keep this up to date
        List<ResolveInfo> services = mPackageManager.queryIntentServices(
                new Intent(BackupService.SERVICE_ACTION), 0);
        if (DEBUG) {
            Log.v(TAG, "Backup participants: " + services.size());
            for (ResolveInfo ri : services) {
                Log.v(TAG, "    " + ri + " : " + ri.filter);
            }
        }

        // Build our mapping of uid to backup client services
        for (ResolveInfo ri : services) {
            int uid = ri.serviceInfo.applicationInfo.uid;
            HashSet<ServiceInfo> set = mBackupParticipants.get(uid);
            if (set == null) {
                set = new HashSet<ServiceInfo>();
                mBackupParticipants.put(uid, set);
            }
            set.add(ri.serviceInfo);
        }
    }

    
    // ----- IBackupManager binder interface -----
    
    public void dataChanged(String packageName) throws RemoteException {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.
        
        HashSet<ServiceInfo> targets = mBackupParticipants.get(Binder.getCallingUid());
        if (targets != null) {
            synchronized (mQueueLock) {
                // Note that this client has made data changes that need to be backed up
                for (ServiceInfo service : targets) {
                    // validate the caller-supplied package name against the known set of
                    // packages associated with this uid
                    if (service.packageName.equals(packageName)) {
                        // add the caller to the set of pending backups
                        if (mPendingBackups.add(new BackupRequest(service, false))) {
                            // !!! TODO: write to the pending-backup journal file in case of crash
                        }
                    }
                }

                // Schedule a backup pass in a few minutes.  As backup-eligible data
                // keeps changing, continue to defer the backup pass until things
                // settle down, to avoid extra overhead.
                mBackupHandler.sendEmptyMessageDelayed(MSG_RUN_BACKUP, COLLECTION_INTERVAL);
            }
        }
    }

    // Schedule a backup pass for a given package, even if the caller is not part of
    // that uid or package itself.
    public void scheduleFullBackup(String packageName) throws RemoteException {
        // !!! TODO: protect with a signature-or-system permission?
        HashSet<ServiceInfo> targets = new HashSet<ServiceInfo>();
        synchronized (mQueueLock) {
            int numKeys = mBackupParticipants.size();
            for (int index = 0; index < numKeys; index++) {
                int uid = mBackupParticipants.keyAt(index);
                HashSet<ServiceInfo> servicesAtUid = mBackupParticipants.get(uid);
                for (ServiceInfo service: servicesAtUid) {
                    if (service.packageName.equals(packageName)) {
                        mPendingBackups.add(new BackupRequest(service, true));
                    }
                }
            }
        }
    }
}
