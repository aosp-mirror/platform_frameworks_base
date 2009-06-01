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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IBackupAgent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.Iterator;
import java.util.List;

class BackupManagerService extends IBackupManager.Stub {
    private static final String TAG = "BackupManagerService";
    private static final boolean DEBUG = true;
    
    private static final long COLLECTION_INTERVAL = 1000;
    //private static final long COLLECTION_INTERVAL = 3 * 60 * 1000;

    private static final int MSG_RUN_BACKUP = 1;
    
    private Context mContext;
    private PackageManager mPackageManager;
    private final IActivityManager mActivityManager;
    private final BackupHandler mBackupHandler = new BackupHandler();
    // map UIDs to the set of backup client services within that UID's app set
    private SparseArray<HashSet<ApplicationInfo>> mBackupParticipants
        = new SparseArray<HashSet<ApplicationInfo>>();
    // set of backup services that have pending changes
    private class BackupRequest {
        public ApplicationInfo appInfo;
        public boolean fullBackup;
        
        BackupRequest(ApplicationInfo app, boolean isFull) {
            appInfo = app;
            fullBackup = isFull;
        }

        public String toString() {
            return "BackupRequest{app=" + appInfo + " full=" + fullBackup + "}";
        }
    }
    // Backups that we haven't started yet.
    private HashMap<ApplicationInfo,BackupRequest> mPendingBackups
            = new HashMap<ApplicationInfo,BackupRequest>();
    // Backups that we have started.  These are separate to prevent starvation
    // if an app keeps re-enqueuing itself.
    private ArrayList<BackupRequest> mBackupQueue;
    private final Object mQueueLock = new Object();

    private File mStateDir;
    private File mDataDir;
    
    public BackupManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mActivityManager = ActivityManagerNative.getDefault();

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

    private class BackupHandler extends Handler {
        public void handleMessage(Message msg) {

            switch (msg.what) {
            case MSG_RUN_BACKUP:
                // snapshot the pending-backup set and work on that
                synchronized (mQueueLock) {
                    if (mBackupQueue == null) {
                        mBackupQueue = new ArrayList();
                        for (BackupRequest b: mPendingBackups.values()) {
                            mBackupQueue.add(b);
                        }
                        mPendingBackups = new HashMap<ApplicationInfo,BackupRequest>();
                    }
                    // !!! TODO: start a new backup-queue journal file too
                    // WARNING: If we crash after this line, anything in mPendingBackups will
                    // be lost.  FIX THIS.
                }
                startOneAgent();
                break;
            }
        }
    }

    void startOneAgent() {
        // Loop until we find someone to start or the queue empties out.
        while (true) {
            BackupRequest request;
            synchronized (mQueueLock) {
                int queueSize = mBackupQueue.size();
                Log.d(TAG, "mBackupQueue.size=" + queueSize);
                if (queueSize == 0) {
                    mBackupQueue = null;
                    // if there are pending backups, start those after a short delay
                    if (mPendingBackups.size() > 0) {
                        mBackupHandler.sendEmptyMessageDelayed(MSG_RUN_BACKUP, COLLECTION_INTERVAL);
                    }
                    return;
                }
                request = mBackupQueue.get(0);
                // Take it off the queue when we're done.
            }
            
            Log.d(TAG, "starting agent for " + request);
            // !!! TODO: need to handle the restore case?
            int mode = (request.fullBackup)
                    ? IApplicationThread.BACKUP_MODE_FULL
                    : IApplicationThread.BACKUP_MODE_INCREMENTAL;
            try {
                if (mActivityManager.bindBackupAgent(request.appInfo, mode)) {
                    Log.d(TAG, "awaiting agent for " + request);
                    // success
                    return;
                }
            } catch (RemoteException e) {
                // can't happen; activity manager is local
            } catch (SecurityException ex) {
                // Try for the next one.
                Log.d(TAG, "error in bind", ex);
            }
        }
    }

    void processOneBackup(String packageName, IBackupAgent bs) {
        Log.d(TAG, "processOneBackup doBackup() on " + packageName);

        BackupRequest request;
        synchronized (mQueueLock) {
            if (mBackupQueue == null) {
                Log.d(TAG, "mBackupQueue is null.  WHY?");
            }
            request = mBackupQueue.get(0);
        }

        try {
            // !!! TODO right now these naming schemes limit applications to
            // one backup service per package
            File savedStateName = new File(mStateDir, request.appInfo.packageName);
            File backupDataName = new File(mDataDir, request.appInfo.packageName + ".data");
            File newStateName = new File(mStateDir, request.appInfo.packageName + ".new");
            
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
            //backupDataName.delete();
            newStateName.renameTo(savedStateName);
            
        } catch (FileNotFoundException fnf) {
            Log.d(TAG, "File not found on backup: ");
            fnf.printStackTrace();
        } catch (RemoteException e) {
            Log.d(TAG, "Remote target " + packageName + " threw during backup:");
            e.printStackTrace();
        } catch (Exception e) {
            Log.w(TAG, "Final exception guard in backup: ");
            e.printStackTrace();
        }
        synchronized (mQueueLock) {
            mBackupQueue.remove(0);
        }

        if (request != null) {
            try {
                mActivityManager.unbindBackupAgent(request.appInfo);
            } catch (RemoteException e) {
                // can't happen
            }
        }

        // start the next one
        startOneAgent();
    }

    // Add the backup agents in the given package to our set of known backup participants.
    // If 'packageName' is null, adds all backup agents in the whole system.
    void addPackageParticipantsLocked(String packageName) {
        // Look for apps that define the android:backupAgent attribute
        List<ApplicationInfo> targetApps = allAgentApps();
        addPackageParticipantsLockedInner(packageName, targetApps);
    }

    private void addPackageParticipantsLockedInner(String packageName,
            List<ApplicationInfo> targetApps) {
        if (DEBUG) {
            Log.v(TAG, "Adding " + targetApps.size() + " backup participants:");
            for (ApplicationInfo a : targetApps) {
                Log.v(TAG, "    " + a + " agent=" + a.backupAgentName);
            }
        }

        for (ApplicationInfo app : targetApps) {
            if (packageName == null || app.packageName.equals(packageName)) {
                int uid = app.uid;
                HashSet<ApplicationInfo> set = mBackupParticipants.get(uid);
                if (set == null) {
                    set = new HashSet<ApplicationInfo>();
                    mBackupParticipants.put(uid, set);
                }
                set.add(app);
            }
        }
    }

    // Remove the given package's backup services from our known active set.  If
    // 'packageName' is null, *all* backup services will be removed.
    void removePackageParticipantsLocked(String packageName) {
        List<ApplicationInfo> allApps = null;
        if (packageName != null) {
            allApps = new ArrayList<ApplicationInfo>();
            try {
                ApplicationInfo app = mPackageManager.getApplicationInfo(packageName, 0);
                allApps.add(app);
            } catch (Exception e) {
                // just skip it
            }
        } else {
            // all apps with agents
            allApps = allAgentApps();
        }
        removePackageParticipantsLockedInner(packageName, allApps);
    }

    private void removePackageParticipantsLockedInner(String packageName,
            List<ApplicationInfo> agents) {
        for (ApplicationInfo app : agents) {
            if (packageName == null || app.packageName.equals(packageName)) {
                int uid = app.uid;
                HashSet<ApplicationInfo> set = mBackupParticipants.get(uid);
                if (set != null) {
                    set.remove(app);
                    if (set.size() == 0) {
                        mBackupParticipants.put(uid, null);
                    }
                }
            }
        }
    }

    // Returns the set of all applications that define an android:backupAgent attribute
    private List<ApplicationInfo> allAgentApps() {
        List<ApplicationInfo> allApps = mPackageManager.getInstalledApplications(0);
        int N = allApps.size();
        if (N > 0) {
            for (int a = N-1; a >= 0; a--) {
                ApplicationInfo app = allApps.get(a);
                if (app.backupAgentName == null) {
                    allApps.remove(a);
                }
            }
        }
        return allApps;
    }
    
    // Reset the given package's known backup participants.  Unlike add/remove, the update
    // action cannot be passed a null package name.
    void updatePackageParticipantsLocked(String packageName) {
        if (packageName == null) {
            Log.e(TAG, "updatePackageParticipants called with null package name");
            return;
        }

        // brute force but small code size
        List<ApplicationInfo> allApps = allAgentApps();
        removePackageParticipantsLockedInner(packageName, allApps);
        addPackageParticipantsLockedInner(packageName, allApps);
    }

    // ----- IBackupManager binder interface -----
    
    public void dataChanged(String packageName) throws RemoteException {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.

        Log.d(TAG, "dataChanged packageName=" + packageName);
        
        HashSet<ApplicationInfo> targets = mBackupParticipants.get(Binder.getCallingUid());
        if (targets != null) {
            synchronized (mQueueLock) {
                // Note that this client has made data changes that need to be backed up
                for (ApplicationInfo app : targets) {
                    // validate the caller-supplied package name against the known set of
                    // packages associated with this uid
                    if (app.packageName.equals(packageName)) {
                        // Add the caller to the set of pending backups.  If there is
                        // one already there, then overwrite it, but no harm done.
                        BackupRequest req = new BackupRequest(app, false);
                        mPendingBackups.put(app, req);
                        // !!! TODO: write to the pending-backup journal file in case of crash
                    }
                }

                if (DEBUG) {
                    int numKeys = mPendingBackups.size();
                    Log.d(TAG, "Scheduling backup for " + numKeys + " participants:");
                    for (BackupRequest b : mPendingBackups.values()) {
                        Log.d(TAG, "    + " + b + " agent=" + b.appInfo.backupAgentName);
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
        synchronized (mQueueLock) {
            int numKeys = mBackupParticipants.size();
            for (int index = 0; index < numKeys; index++) {
                int uid = mBackupParticipants.keyAt(index);
                HashSet<ApplicationInfo> servicesAtUid = mBackupParticipants.get(uid);
                for (ApplicationInfo app: servicesAtUid) {
                    if (app.packageName.equals(packageName)) {
                        mPendingBackups.put(app, new BackupRequest(app, true));
                    }
                }
            }
        }
    }

    // Callback: a requested backup agent has been instantiated
    public void agentConnected(String packageName, IBinder agentBinder) {
        Log.d(TAG, "agentConnected pkg=" + packageName + " agent=" + agentBinder);
        IBackupAgent bs = IBackupAgent.Stub.asInterface(agentBinder);
        processOneBackup(packageName, bs);
    }

    // Callback: a backup agent has failed to come up, or has unexpectedly quit.
    // If the agent failed to come up in the first place, the agentBinder argument
    // will be null.
    public void agentDisconnected(String packageName) {
        // TODO: handle backup being interrupted
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
                HashSet<ApplicationInfo> participants = mBackupParticipants.valueAt(i);
                for (ApplicationInfo app: participants) {
                    pw.print("    ");
                    pw.println(app.toString());
                }
            }
            pw.println("Pending:");
            Iterator<BackupRequest> br = mPendingBackups.values().iterator();
            while (br.hasNext()) {
                pw.print("    ");
                pw.println(br);
            }
        }
    }
}
