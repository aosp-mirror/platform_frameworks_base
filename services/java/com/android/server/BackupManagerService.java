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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
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
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

class BackupManagerService extends IBackupManager.Stub {
    private static final String TAG = "BackupManagerService";
    private static final boolean DEBUG = true;
    
    private static final long COLLECTION_INTERVAL = 1000;
    //private static final long COLLECTION_INTERVAL = 3 * 60 * 1000;

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
    // Backups that we haven't started yet.
    private HashMap<ComponentName,BackupRequest> mPendingBackups = new HashMap();
    // Backups that we have started.  These are separate to prevent starvation
    // if an app keeps re-enqueuing itself.
    private ArrayList<BackupRequest> mBackupQueue;
    private final Object mQueueLock = new Object();

    private File mStateDir;
    private File mDataDir;
    
    public BackupManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();

        // Set up our bookkeeping
        mStateDir = new File(Environment.getDataDirectory(), "backup");
        mStateDir.mkdirs();
        mDataDir = Environment.getDownloadCacheDirectory();
        
        // Build our mapping of uid to backup client services
        synchronized (mBackupParticipants) {
            addPackageParticipantsLocked(null);
        }

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    // ----- Track installation/removal of packages -----
    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "Received broadcast " + intent);

            Uri uri = intent.getData();
            if (uri == null) {
                return;
            }
            String pkgName = uri.getSchemeSpecificPart();
            if (pkgName == null) {
                return;
            }

            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                synchronized (mBackupParticipants) {
                    Bundle extras = intent.getExtras();
                    if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                        // The package was just upgraded
                        updatePackageParticipantsLocked(pkgName);
                    } else {
                        // The package was just added
                        addPackageParticipantsLocked(pkgName);
                    }
                }
            }
            else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                Bundle extras = intent.getExtras();
                if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                    // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                } else {
                    synchronized (mBackupParticipants) {
                        removePackageParticipantsLocked(pkgName);
                    }
                }
            }
        }
    };

    // ----- Run the actual backup process asynchronously -----

    private class BackupHandler extends Handler implements ServiceConnection {
        public void handleMessage(Message msg) {

            switch (msg.what) {
            case MSG_RUN_BACKUP:
                // snapshot the pending-backup set and work on that
                synchronized (mQueueLock) {
                    mBackupQueue = new ArrayList();
                    for (BackupRequest b: mPendingBackups.values()) {
                        mBackupQueue.add(b);
                    }
                    mPendingBackups = new HashMap<ComponentName,BackupRequest>();
                    // !!! TODO: start a new backup-queue journal file too
                    // WARNING: If we crash after this line, anything in mPendingBackups will
                    // be lost.  FIX THIS.
                }
                startOneService();
                break;
            }
        }
        
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected name=" + name + " service=" + service);
            IBackupService bs = IBackupService.Stub.asInterface(service);
            processOneBackup(name, bs);
        }

        public void onServiceDisconnected(ComponentName name) {
            // TODO: handle backup being interrupted
        }
    }

    void startOneService() {
        // Loop until we find someone to start or the queue empties out.
        Intent intent = new Intent(BackupService.SERVICE_ACTION);
        while (true) {
            BackupRequest request;
            synchronized (mQueueLock) {
                int queueSize = mBackupQueue.size();
                if (queueSize == 0) {
                    mBackupQueue = null;
                    // TODO: Anything else to do here?
                    return;
                }
                request = mBackupQueue.get(0);
                // Take it off the queue when we're done.
            }
            
            intent.setClassName(request.service.packageName, request.service.name);
            Log.d(TAG, "binding to " + intent);
            try {
                if (mContext.bindService(intent, mBackupHandler, Context.BIND_AUTO_CREATE)) {
                    Log.d(TAG, "awaiting service object for " + intent);
                    // success
                    return;
                }
            } catch (SecurityException ex) {
                // Try for the next one.
                Log.d(TAG, "error in bind", ex);
            }
        }
    }

    void processOneBackup(ComponentName name, IBackupService bs) {
        try {
            Log.d(TAG, "processOneBackup doBackup() on " + name);

            BackupRequest request;
            synchronized (mQueueLock) {
                if (mBackupQueue == null) {
                    Log.d(TAG, "mBackupQueue is null.  WHY?");
                }
                request = mBackupQueue.get(0);
            }

            // !!! TODO right now these naming schemes limit applications to
            // one backup service per package
            File savedStateName = new File(mStateDir, request.service.packageName);
            File backupDataName = new File(mDataDir, request.service.packageName + ".data");
            File newStateName = new File(mStateDir, request.service.packageName + ".new");
            
            // In a full backup, we pass a null ParcelFileDescriptor as
            // the saved-state "file"
            ParcelFileDescriptor savedState = (request.fullBackup) ? null
                    : ParcelFileDescriptor.open(savedStateName,
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
                // TODO: Make this oneway
                bs.doBackup(savedState, backupData, newState);
            } finally {
                if (savedState != null) {
                    savedState.close();
                }
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
            Log.d(TAG, "Remote target " + name + " threw during backup:");
            e.printStackTrace();
        } catch (Exception e) {
            Log.w(TAG, "Final exception guard in backup: ");
            e.printStackTrace();
        }
        synchronized (mQueueLock) {
            mBackupQueue.remove(0);
        }
        mContext.unbindService(mBackupHandler);
    }

    // Add the backup services in the given package to our set of known backup participants.
    // If 'packageName' is null, adds all backup services in the system.
    void addPackageParticipantsLocked(String packageName) {
        List<ResolveInfo> services = mPackageManager.queryIntentServices(
                new Intent(BackupService.SERVICE_ACTION), 0);
        addPackageParticipantsLockedInner(packageName, services);
    }

    private void addPackageParticipantsLockedInner(String packageName, List<ResolveInfo> services) {
        for (ResolveInfo ri : services) {
            if (packageName == null || ri.serviceInfo.packageName.equals(packageName)) {
                int uid = ri.serviceInfo.applicationInfo.uid;
                HashSet<ServiceInfo> set = mBackupParticipants.get(uid);
                if (set == null) {
                    set = new HashSet<ServiceInfo>();
                    mBackupParticipants.put(uid, set);
                }
                if (DEBUG) {
                    Log.v(TAG, "Adding " + services.size() + " backup participants:");
                    for (ResolveInfo svc : services) {
                        Log.v(TAG, "    " + svc + " : " + svc.filter);
                    }
                }

                set.add(ri.serviceInfo);
            }
        }
    }

    // Remove the given package's backup services from our known active set.  If
    // 'packageName' is null, *all* backup services will be removed.
    void removePackageParticipantsLocked(String packageName) {
        List<ResolveInfo> services = mPackageManager.queryIntentServices(
                new Intent(BackupService.SERVICE_ACTION), 0);
        removePackageParticipantsLockedInner(packageName, services);
    }

    private void removePackageParticipantsLockedInner(String packageName,
            List<ResolveInfo> services) {
        for (ResolveInfo ri : services) {
            if (packageName == null || ri.serviceInfo.packageName.equals(packageName)) {
                int uid = ri.serviceInfo.applicationInfo.uid;
                HashSet<ServiceInfo> set = mBackupParticipants.get(uid);
                if (set != null) {
                    set.remove(ri.serviceInfo);
                    if (set.size() == 0) {
                        mBackupParticipants.put(uid, null);
                    }
                }
            }
        }
    }

    // Reset the given package's known backup participants.  Unlike add/remove, the update
    // action cannot be passed a null package name.
    void updatePackageParticipantsLocked(String packageName) {
        if (packageName == null) {
            Log.e(TAG, "updatePackageParticipants called with null package name");
            return;
        }

        // brute force but small code size
        List<ResolveInfo> services = mPackageManager.queryIntentServices(
                new Intent(BackupService.SERVICE_ACTION), 0);
        removePackageParticipantsLockedInner(packageName, services);
        addPackageParticipantsLockedInner(packageName, services);
    }

    // ----- IBackupManager binder interface -----
    
    public void dataChanged(String packageName) throws RemoteException {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.

        Log.d(TAG, "dataChanged packageName=" + packageName);
        
        HashSet<ServiceInfo> targets = mBackupParticipants.get(Binder.getCallingUid());
        Log.d(TAG, "targets=" + targets);
        if (targets != null) {
            synchronized (mQueueLock) {
                // Note that this client has made data changes that need to be backed up
                for (ServiceInfo service : targets) {
                    // validate the caller-supplied package name against the known set of
                    // packages associated with this uid
                    if (service.packageName.equals(packageName)) {
                        // Add the caller to the set of pending backups.  If there is
                        // one already there, then overwrite it, but no harm done.
                        mPendingBackups.put(new ComponentName(service.packageName, service.name),
                                new BackupRequest(service, true));
                        // !!! TODO: write to the pending-backup journal file in case of crash
                    }
                }

                Log.d(TAG, "Scheduling backup for " + mPendingBackups.size() + " participants");
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
                        mPendingBackups.put(new ComponentName(service.packageName, service.name),
                                new BackupRequest(service, true));
                    }
                }
            }
        }
    }

    
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            int N = mBackupParticipants.size();
            pw.println("Participants:");
            for (int i=0; i<N; i++) {
                int uid = mBackupParticipants.keyAt(i);
                pw.print("  uid: ");
                pw.println(uid);
                HashSet<ServiceInfo> services = mBackupParticipants.valueAt(i);
                for (ServiceInfo s: services) {
                    pw.print("    ");
                    pw.println(s.toString());
                }
            }
        }
    }
}
