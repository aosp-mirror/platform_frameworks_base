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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import android.backup.IBackupManager;
import android.backup.BackupManager;

import com.android.internal.backup.AdbTransport;
import com.android.internal.backup.GoogleTransport;
import com.android.internal.backup.IBackupTransport;

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
    private static final int MSG_RUN_FULL_BACKUP = 2;
    
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

    // The thread performing the sequence of queued backups binds to each app's agent
    // in succession.  Bind notifications are asynchronously delivered through the
    // Activity Manager; use this lock object to signal when a requested binding has
    // completed.
    private final Object mAgentConnectLock = new Object();
    private IBackupAgent mConnectedAgent;
    private volatile boolean mConnecting;

    private int mTransportId;

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
        mTransportId = BackupManager.TRANSPORT_GOOGLE;
        
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
                        mBackupQueue = new ArrayList<BackupRequest>();
                        for (BackupRequest b: mPendingBackups.values()) {
                            mBackupQueue.add(b);
                        }
                        mPendingBackups = new HashMap<ApplicationInfo,BackupRequest>();
                    }
                    // !!! TODO: start a new backup-queue journal file too
                    // WARNING: If we crash after this line, anything in mPendingBackups will
                    // be lost.  FIX THIS.
                }
                (new PerformBackupThread(mTransportId, mBackupQueue)).run();
                break;

            case MSG_RUN_FULL_BACKUP:
                break;
            }
        }
    }

    void processOneBackup(BackupRequest request, IBackupAgent agent, IBackupTransport transport) {
        final String packageName = request.appInfo.packageName;
        Log.d(TAG, "processOneBackup doBackup() on " + packageName);

        try {
            // Look up the package info & signatures.  This is first so that if it
            // throws an exception, there's no file setup yet that would need to
            // be unraveled.
            PackageInfo packInfo = mPackageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);

            // !!! TODO: get the state file dir from the transport
            File savedStateName = new File(mStateDir, packageName);
            File backupDataName = new File(mDataDir, packageName + ".data");
            File newStateName = new File(mStateDir, packageName + ".new");
            
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
            boolean success = false;
            try {
                agent.doBackup(savedState, backupData, newState);
                success = true;
            } finally {
                if (savedState != null) {
                    savedState.close();
                }
                backupData.close();
                newState.close();
            }

            // Now propagate the newly-backed-up data to the transport
            if (success) {
                if (DEBUG) Log.v(TAG, "doBackup() success; calling transport");
                backupData =
                    ParcelFileDescriptor.open(backupDataName, ParcelFileDescriptor.MODE_READ_ONLY);
                int error = transport.performBackup(packInfo, backupData);

                // !!! TODO: After successful transport, delete the now-stale data
                // and juggle the files so that next time the new state is passed
                //backupDataName.delete();
                newStateName.renameTo(savedStateName);
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Package not found on backup: " + packageName);
        } catch (FileNotFoundException fnf) {
            Log.w(TAG, "File not found on backup: ");
            fnf.printStackTrace();
        } catch (RemoteException e) {
            Log.d(TAG, "Remote target " + request.appInfo.packageName + " threw during backup:");
            e.printStackTrace();
        } catch (Exception e) {
            Log.w(TAG, "Final exception guard in backup: ");
            e.printStackTrace();
        }
    }

    // Add the backup agents in the given package to our set of known backup participants.
    // If 'packageName' is null, adds all backup agents in the whole system.
    void addPackageParticipantsLocked(String packageName) {
        // Look for apps that define the android:backupAgent attribute
        if (DEBUG) Log.v(TAG, "addPackageParticipantsLocked: " + packageName);
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
        if (DEBUG) Log.v(TAG, "removePackageParticipantsLocked: " + packageName);
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
        if (DEBUG) {
            Log.v(TAG, "removePackageParticipantsLockedInner (" + packageName
                    + ") removing " + agents.size() + " entries");
            for (ApplicationInfo a : agents) {
                Log.v(TAG, "    - " + a);
            }
        }
        for (ApplicationInfo app : agents) {
            if (packageName == null || app.packageName.equals(packageName)) {
                int uid = app.uid;
                HashSet<ApplicationInfo> set = mBackupParticipants.get(uid);
                if (set != null) {
                    // Find the existing entry with the same package name, and remove it.
                    // We can't just remove(app) because the instances are different.
                    for (ApplicationInfo entry: set) {
                        if (entry.packageName.equals(app.packageName)) {
                            set.remove(entry);
                            break;
                        }
                    }
                    if (set.size() == 0) {
                        mBackupParticipants.delete(uid);                    }
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
        if (DEBUG) Log.v(TAG, "updatePackageParticipantsLocked: " + packageName);

        // brute force but small code size
        List<ApplicationInfo> allApps = allAgentApps();
        removePackageParticipantsLockedInner(packageName, allApps);
        addPackageParticipantsLockedInner(packageName, allApps);
    }

    // ----- Back up a set of applications via a worker thread -----

    class PerformBackupThread extends Thread {
        private static final String TAG = "PerformBackupThread";
        int mTransport;
        ArrayList<BackupRequest> mQueue;

        public PerformBackupThread(int transportId, ArrayList<BackupRequest> queue) {
            mTransport = transportId;
            mQueue = queue;
        }

        @Override
        public void run() {
            /*
             * 1. start up the current transport
             * 2. for each item in the queue:
             *      2a. bind the agent [wait for async attach]
             *      2b. set up the files and call doBackup()
             *      2c. unbind the agent
             * 3. tear down the transport
             * 4. done!
             */
            if (DEBUG) Log.v(TAG, "Beginning backup of " + mQueue.size() + " targets");

            // stand up the current transport
            IBackupTransport transport = null;
            switch (mTransport) {
            case BackupManager.TRANSPORT_ADB:
                if (DEBUG) Log.v(TAG, "Initializing adb transport");
                transport = new AdbTransport();
                break;

            case BackupManager.TRANSPORT_GOOGLE:
                if (DEBUG) Log.v(TAG, "Initializing Google transport");
                //!!! TODO: stand up the google backup transport here
                transport = new GoogleTransport();
                break;

            default:
                Log.e(TAG, "Perform backup with unknown transport " + mTransport);
                // !!! TODO: re-enqueue the backup queue for later?
                return;
            }

            try {
                transport.startSession();
            } catch (Exception e) {
                Log.e(TAG, "Error starting backup session");
                e.printStackTrace();
                // !!! TODO: re-enqueue the backup queue for later?
                return;
            }

            // The transport is up and running; now run all the backups in our queue
            doQueuedBackups(transport);

            // Finally, tear down the transport
            try {
                transport.endSession();
            } catch (Exception e) {
                Log.e(TAG, "Error ending transport");
                e.printStackTrace();
            }
        }

        private void doQueuedBackups(IBackupTransport transport) {
            for (BackupRequest request : mQueue) {
                Log.d(TAG, "starting agent for " + request);
                // !!! TODO: need to handle the restore case?

                IBackupAgent agent = null;
                int mode = (request.fullBackup)
                        ? IApplicationThread.BACKUP_MODE_FULL
                        : IApplicationThread.BACKUP_MODE_INCREMENTAL;
                try {
                    synchronized(mAgentConnectLock) {
                        mConnecting = true;
                        mConnectedAgent = null;
                        if (mActivityManager.bindBackupAgent(request.appInfo, mode)) {
                            Log.d(TAG, "awaiting agent for " + request);

                            // success; wait for the agent to arrive
                            while (mConnecting && mConnectedAgent == null) {
                                try {
                                    mAgentConnectLock.wait(10000);
                                } catch (InterruptedException e) {
                                    // just retry
                                    continue;
                                }
                            }

                            // if we timed out with no connect, abort and move on
                            if (mConnecting == true) {
                                Log.w(TAG, "Timeout waiting for agent " + request);
                                continue;
                            }
                            agent = mConnectedAgent;
                        }
                    }
                } catch (RemoteException e) {
                    // can't happen; activity manager is local
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Log.d(TAG, "error in bind", ex);
                }

                // successful bind? run the backup for this agent
                if (agent != null) {
                    processOneBackup(request, agent, transport);
                }

                // send the unbind even on timeout, just in case
                try {
                    mActivityManager.unbindBackupAgent(request.appInfo);
                } catch (RemoteException e) {
                    // can't happen
                }
            }
        }
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
                mBackupHandler.removeMessages(MSG_RUN_BACKUP);
                mBackupHandler.sendEmptyMessageDelayed(MSG_RUN_BACKUP, COLLECTION_INTERVAL);
            }
        }
    }

    // Schedule a backup pass for a given package.  This method will schedule a
    // full backup even for apps that do not declare an android:backupAgent, so
    // use with care.
    public void scheduleFullBackup(String packageName) throws RemoteException {
        mContext.enforceCallingPermission("android.permission.BACKUP", "scheduleFullBackup");

        if (DEBUG) Log.v(TAG, "Scheduling immediate full backup for " + packageName);
        synchronized (mQueueLock) {
            try {
                ApplicationInfo app = mPackageManager.getApplicationInfo(packageName, 0);
                mPendingBackups.put(app, new BackupRequest(app, true));
                mBackupHandler.sendEmptyMessage(MSG_RUN_FULL_BACKUP);
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Could not find app for " + packageName + " to schedule full backup");
            }
        }
    }

    // Select which transport to use for the next backup operation
    public int selectBackupTransport(int transportId) {
        mContext.enforceCallingPermission("android.permission.BACKUP", "selectBackupTransport");

        int prevTransport = mTransportId;
        mTransportId = transportId;
        return prevTransport;
    }

    // Callback: a requested backup agent has been instantiated.  This should only
    // be called from the Activity Manager.
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized(mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                Log.d(TAG, "agentConnected pkg=" + packageName + " agent=" + agentBinder);
                IBackupAgent agent = IBackupAgent.Stub.asInterface(agentBinder);
                mConnectedAgent = agent;
                mConnecting = false;
            } else {
                Log.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                        + " claiming agent connected");
            }
            mAgentConnectLock.notifyAll();
        }
    }

    // Callback: a backup agent has failed to come up, or has unexpectedly quit.
    // If the agent failed to come up in the first place, the agentBinder argument
    // will be null.  This should only be called from the Activity Manager.
    public void agentDisconnected(String packageName) {
        // TODO: handle backup being interrupted
        synchronized(mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                mConnectedAgent = null;
                mConnecting = false;
            } else {
                Log.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                        + " claiming agent disconnected");
            }
            mAgentConnectLock.notifyAll();
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
