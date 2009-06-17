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
import android.content.pm.IPackageDataObserver;
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
import android.backup.IRestoreSession;
import android.backup.BackupManager;
import android.backup.RestoreSet;

import com.android.internal.backup.LocalTransport;
import com.android.internal.backup.GoogleTransport;
import com.android.internal.backup.IBackupTransport;

import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
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
    private static final int MSG_RUN_RESTORE = 3;

    // Timeout interval for deciding that a bind or clear-data has taken too long
    static final long TIMEOUT_INTERVAL = 10 * 1000;

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

    // A similar synchronicity mechanism around clearing apps' data for restore
    private final Object mClearDataLock = new Object();
    private volatile boolean mClearingData;

    private int mTransportId;
    private RestoreSession mActiveRestoreSession;

    private File mStateDir;
    private File mDataDir;
    private File mJournalDir;
    private File mJournal;
    private RandomAccessFile mJournalStream;
    
    public BackupManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mActivityManager = ActivityManagerNative.getDefault();

        // Set up our bookkeeping
        mStateDir = new File(Environment.getDataDirectory(), "backup");
        mStateDir.mkdirs();
        mDataDir = Environment.getDownloadCacheDirectory();

        // Set up the backup-request journaling
        mJournalDir = new File(mStateDir, "pending");
        mJournalDir.mkdirs();
        makeJournalLocked();    // okay because no other threads are running yet

        //!!! TODO: default to cloud transport, not local
        mTransportId = BackupManager.TRANSPORT_LOCAL;
        
        // Build our mapping of uid to backup client services
        synchronized (mBackupParticipants) {
            addPackageParticipantsLocked(null);
        }

        // Now that we know about valid backup participants, parse any
        // leftover journal files and schedule a new backup pass
        parseLeftoverJournals();

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    private void makeJournalLocked() {
        try {
            mJournal = File.createTempFile("journal", null, mJournalDir);
            mJournalStream = new RandomAccessFile(mJournal, "rwd");
        } catch (IOException e) {
            Log.e(TAG, "Unable to write backup journals");
            mJournal = null;
            mJournalStream = null;
        }
    }

    private void parseLeftoverJournals() {
        if (mJournal != null) {
            File[] allJournals = mJournalDir.listFiles();
            for (File f : allJournals) {
                if (f.compareTo(mJournal) != 0) {
                    // This isn't the current journal, so it must be a leftover.  Read
                    // out the package names mentioned there and schedule them for
                    // backup.
                    try {
                        Log.i(TAG, "Found stale backup journal, scheduling:");
                        RandomAccessFile in = new RandomAccessFile(f, "r");
                        while (true) {
                            String packageName = in.readUTF();
                            Log.i(TAG, "    + " + packageName);
                            dataChanged(packageName);
                        }
                    } catch (EOFException e) {
                        // no more data; we're done
                    } catch (Exception e) {
                        // can't read it or other error; just skip it
                    } finally {
                        // close/delete the file
                        f.delete();
                    }
                }
            }
        }
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
                File oldJournal = mJournal;
                synchronized (mQueueLock) {
                    if (mPendingBackups.size() == 0) {
                        Log.v(TAG, "Backup requested but nothing pending");
                        break;
                    }

                    if (mBackupQueue == null) {
                        mBackupQueue = new ArrayList<BackupRequest>();
                        for (BackupRequest b: mPendingBackups.values()) {
                            mBackupQueue.add(b);
                        }
                        mPendingBackups = new HashMap<ApplicationInfo,BackupRequest>();
                    }

                    // Start a new backup-queue journal file too
                    if (mJournalStream != null) {
                        try {
                            mJournalStream.close();
                        } catch (IOException e) {
                            // don't need to do anything
                        }
                        makeJournalLocked();
                    }

                    // At this point, we have started a new journal file, and the old
                    // file identity is being passed to the backup processing thread.
                    // When it completes successfully, that old journal file will be
                    // deleted.  If we crash prior to that, the old journal is parsed
                    // at next boot and the journaled requests fulfilled.
                }
                (new PerformBackupThread(mTransportId, mBackupQueue, oldJournal)).run();
                break;

            case MSG_RUN_FULL_BACKUP:
                break;

            case MSG_RUN_RESTORE:
            {
                int token = msg.arg1;
                IBackupTransport transport = (IBackupTransport)msg.obj;
                (new PerformRestoreThread(transport, token)).run();
                break;
            }
            }
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
                if (((app.flags&ApplicationInfo.FLAG_ALLOW_BACKUP) == 0)
                        || app.backupAgentName == null) {
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

    // Instantiate the given transport
    private IBackupTransport createTransport(int transportID) {
        IBackupTransport transport = null;
        switch (transportID) {
        case BackupManager.TRANSPORT_LOCAL:
            if (DEBUG) Log.v(TAG, "Initializing local transport");
            transport = new LocalTransport(mContext);
            break;

        case BackupManager.TRANSPORT_GOOGLE:
            if (DEBUG) Log.v(TAG, "Initializing Google transport");
            //!!! TODO: stand up the google backup transport for real here
            transport = new GoogleTransport();
            break;

        default:
            Log.e(TAG, "Asked for unknown transport " + transportID);
        }
        return transport;
    }

    // fire off a backup agent, blocking until it attaches or times out
    IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode) {
        IBackupAgent agent = null;
        synchronized(mAgentConnectLock) {
            mConnecting = true;
            mConnectedAgent = null;
            try {
                if (mActivityManager.bindBackupAgent(app, mode)) {
                    Log.d(TAG, "awaiting agent for " + app);

                    // success; wait for the agent to arrive
                    // only wait 10 seconds for the clear data to happen
                    long timeoutMark = System.currentTimeMillis() + TIMEOUT_INTERVAL;
                    while (mConnecting && mConnectedAgent == null
                            && (System.currentTimeMillis() < timeoutMark)) {
                        try {
                            mAgentConnectLock.wait(5000);
                        } catch (InterruptedException e) {
                            // just bail
                            return null;
                        }
                    }

                    // if we timed out with no connect, abort and move on
                    if (mConnecting == true) {
                        Log.w(TAG, "Timeout waiting for agent " + app);
                        return null;
                    }
                    agent = mConnectedAgent;
                }
            } catch (RemoteException e) {
                // can't happen
            }
        }
        return agent;
    }

    // clear an application's data, blocking until the operation completes or times out
    void clearApplicationDataSynchronous(String packageName) {
        ClearDataObserver observer = new ClearDataObserver();

        synchronized(mClearDataLock) {
            mClearingData = true;
            mPackageManager.clearApplicationUserData(packageName, observer);

            // only wait 10 seconds for the clear data to happen
            long timeoutMark = System.currentTimeMillis() + TIMEOUT_INTERVAL;
            while (mClearingData && (System.currentTimeMillis() < timeoutMark)) {
                try {
                    mClearDataLock.wait(5000);
                } catch (InterruptedException e) {
                    // won't happen, but still.
                    mClearingData = false;
                }
            }
        }
    }

    class ClearDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(String packageName, boolean succeeded)
                throws android.os.RemoteException {
            synchronized(mClearDataLock) {
                mClearingData = false;
                mClearDataLock.notifyAll();
            }
        }
    }

    // ----- Back up a set of applications via a worker thread -----

    class PerformBackupThread extends Thread {
        private static final String TAG = "PerformBackupThread";
        int mTransport;
        ArrayList<BackupRequest> mQueue;
        File mJournal;

        public PerformBackupThread(int transportId, ArrayList<BackupRequest> queue,
                File journal) {
            mTransport = transportId;
            mQueue = queue;
            mJournal = journal;
        }

        @Override
        public void run() {
            if (DEBUG) Log.v(TAG, "Beginning backup of " + mQueue.size() + " targets");

            // stand up the current transport
            IBackupTransport transport = createTransport(mTransport);
            if (transport == null) {
                return;
            }

            // start up the transport
            try {
                transport.startSession();
            } catch (Exception e) {
                Log.e(TAG, "Error session transport");
                e.printStackTrace();
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

            if (!mJournal.delete()) {
                Log.e(TAG, "Unable to remove backup journal file " + mJournal.getAbsolutePath());
            }
        }

        private void doQueuedBackups(IBackupTransport transport) {
            for (BackupRequest request : mQueue) {
                Log.d(TAG, "starting agent for backup of " + request);

                IBackupAgent agent = null;
                int mode = (request.fullBackup)
                        ? IApplicationThread.BACKUP_MODE_FULL
                        : IApplicationThread.BACKUP_MODE_INCREMENTAL;
                try {
                    agent = bindToAgentSynchronous(request.appInfo, mode);
                    if (agent != null) {
                        processOneBackup(request, agent, transport);
                    }

                    // unbind even on timeout, just in case
                    mActivityManager.unbindBackupAgent(request.appInfo);
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Log.d(TAG, "error in bind/backup", ex);
                } catch (RemoteException e) {
                    Log.v(TAG, "bind/backup threw");
                    e.printStackTrace();
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
                PackageInfo packInfo = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_SIGNATURES);

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
    }


    // ----- Restore handling -----

    // Is the given package restorable on this device?  Returns the on-device app's
    // ApplicationInfo struct if it is; null if not.
    //
    // !!! TODO: also consider signatures
    PackageInfo isRestorable(PackageInfo packageInfo) {
        if (packageInfo.packageName != null) {
            try {
                PackageInfo app = mPackageManager.getPackageInfo(packageInfo.packageName,
                        PackageManager.GET_SIGNATURES);
                if ((app.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
                    return app;
                }
            } catch (Exception e) {
                // doesn't exist on this device, or other error -- just ignore it.
            }
        }
        return null;
    }

    class PerformRestoreThread extends Thread {
        private IBackupTransport mTransport;
        private int mToken;
        private RestoreSet mImage;

        PerformRestoreThread(IBackupTransport transport, int restoreSetToken) {
            mTransport = transport;
            mToken = restoreSetToken;
        }

        @Override
        public void run() {
            /**
             * Restore sequence:
             *
             * 1. start up the transport session
             * 2. get the restore set description for our identity
             * 3. for each app in the restore set:
             *    3.a. if it's restorable on this device, add it to the restore queue
             * 4. for each app in the restore queue:
             *    4.a. clear the app data
             *    4.b. get the restore data for the app from the transport
             *    4.c. launch the backup agent for the app
             *    4.d. agent.doRestore() with the data from the server
             *    4.e. unbind the agent [and kill the app?]
             * 5. shut down the transport
             */

            int err = -1;
            try {
                err = mTransport.startSession();
            } catch (Exception e) {
                Log.e(TAG, "Error starting transport for restore");
                e.printStackTrace();
            }

            if (err == 0) {
                // build the set of apps to restore
                try {
                    RestoreSet[] images = mTransport.getAvailableRestoreSets();
                    if (images.length > 0) {
                        // !!! TODO: pick out the set for this token
                        mImage = images[0];

                        // build the set of apps we will attempt to restore
                        PackageInfo[] packages = mTransport.getAppSet(mImage.token);
                        HashSet<PackageInfo> appsToRestore = new HashSet<PackageInfo>();
                        for (PackageInfo pkg: packages) {
                            // get the real PackageManager idea of the package
                            PackageInfo app = isRestorable(pkg);
                            if (app != null) {
                                appsToRestore.add(app);
                            }
                        }

                        // now run the restore queue
                        doQueuedRestores(appsToRestore);
                    }
                } catch (RemoteException e) {
                    // can't happen; transports run locally
                }

                // done; shut down the transport
                try {
                    mTransport.endSession();
                } catch (Exception e) {
                    Log.e(TAG, "Error ending transport for restore");
                    e.printStackTrace();
                }
            }

            // even if the initial session startup failed, report that we're done here
        }

        // restore each app in the queue
        void doQueuedRestores(HashSet<PackageInfo> appsToRestore) {
            for (PackageInfo app : appsToRestore) {
                Log.d(TAG, "starting agent for restore of " + app);

                try {
                    // Remove the app's data first
                    clearApplicationDataSynchronous(app.packageName);

                    // Now perform the restore into the clean app
                    IBackupAgent agent = bindToAgentSynchronous(app.applicationInfo,
                            IApplicationThread.BACKUP_MODE_RESTORE);
                    if (agent != null) {
                        processOneRestore(app, agent);
                    }

                    // unbind even on timeout, just in case
                    mActivityManager.unbindBackupAgent(app.applicationInfo);
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Log.d(TAG, "error in bind", ex);
                } catch (RemoteException e) {
                    // can't happen
                }

            }
        }

        // Do the guts of a restore of one application, derived from the 'mImage'
        // restore set via the 'mTransport' transport.
        void processOneRestore(PackageInfo app, IBackupAgent agent) {
            // !!! TODO: actually run the restore through mTransport
            final String packageName = app.packageName;

            // !!! TODO: get the dirs from the transport
            File backupDataName = new File(mDataDir, packageName + ".restore");
            backupDataName.delete();
            try {
                ParcelFileDescriptor backupData =
                    ParcelFileDescriptor.open(backupDataName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE);

                // Run the transport's restore pass
                // Run the target's backup pass
                int err = -1;
                try {
                    err = mTransport.getRestoreData(mImage.token, app, backupData);
                } catch (RemoteException e) {
                    // can't happen
                } finally {
                    backupData.close();
                }

                // Okay, we have the data.  Now have the agent do the restore.
                File newStateName = new File(mStateDir, packageName + ".new");
                ParcelFileDescriptor newState =
                    ParcelFileDescriptor.open(newStateName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE);

                backupData = ParcelFileDescriptor.open(backupDataName,
                            ParcelFileDescriptor.MODE_READ_ONLY);

                boolean success = false;
                try {
                    agent.doRestore(backupData, newState);
                    success = true;
                } catch (Exception e) {
                    Log.e(TAG, "Restore failed for " + packageName);
                    e.printStackTrace();
                } finally {
                    newState.close();
                    backupData.close();
                }

                // if everything went okay, remember the recorded state now
                if (success) {
                    File savedStateName = new File(mStateDir, packageName);
                    newStateName.renameTo(savedStateName);
                }
            } catch (FileNotFoundException fnfe) {
                Log.v(TAG, "Couldn't open file for restore: " + fnfe);
            } catch (IOException ioe) {
                Log.e(TAG, "Unable to process restore file: " + ioe);
            } catch (Exception e) {
                Log.e(TAG, "Final exception guard in restore:");
                e.printStackTrace();
            }
        }
    }


    // ----- IBackupManager binder interface -----

    public void dataChanged(String packageName) throws RemoteException {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.

        Log.d(TAG, "dataChanged packageName=" + packageName);

        // If the caller does not hold the BACKUP permission, it can only request a
        // backup of its own data.
        HashSet<ApplicationInfo> targets;
        if ((mContext.checkPermission("android.permission.BACKUP", Binder.getCallingPid(),
                Binder.getCallingUid())) == PackageManager.PERMISSION_DENIED) {
            targets = mBackupParticipants.get(Binder.getCallingUid());
        } else {
            // a caller with full permission can ask to back up any participating app
            // !!! TODO: allow backup of ANY app?
            if (DEBUG) Log.v(TAG, "Privileged caller, allowing backup of other apps");
            targets = new HashSet<ApplicationInfo>();
            int N = mBackupParticipants.size();
            for (int i = 0; i < N; i++) {
                HashSet<ApplicationInfo> s = mBackupParticipants.valueAt(i);
                if (s != null) {
                    targets.addAll(s);
                }
            }
        }
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

                        // Journal this request in case of crash
                        writeToJournalLocked(packageName);
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
        } else {
            Log.w(TAG, "dataChanged but no participant pkg " + packageName);
        }
    }

    private void writeToJournalLocked(String str) {
        if (mJournalStream != null) {
            try {
                mJournalStream.writeUTF(str);
            } catch (IOException e) {
                Log.e(TAG, "Error writing to backup journal");
                mJournalStream = null;
                mJournal = null;
            }
        }
    }

    // Run a backup pass immediately for any applications that have declared
    // that they have pending updates.
    public void backupNow() throws RemoteException {
        mContext.enforceCallingPermission("android.permission.BACKUP", "tryBackupNow");

        if (DEBUG) Log.v(TAG, "Scheduling immediate backup pass");
        synchronized (mQueueLock) {
            mBackupHandler.removeMessages(MSG_RUN_BACKUP);
            mBackupHandler.sendEmptyMessage(MSG_RUN_BACKUP);
        }
    }

    // Report the currently active transport
    public int getCurrentTransport() {
        mContext.enforceCallingPermission("android.permission.BACKUP", "selectBackupTransport");
        return mTransportId;
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

    // Hand off a restore session
    public IRestoreSession beginRestoreSession(int transportID) {
        mContext.enforceCallingPermission("android.permission.BACKUP", "beginRestoreSession");

        synchronized(this) {
            if (mActiveRestoreSession != null) {
                Log.d(TAG, "Restore session requested but one already active");
                return null;
            }
            mActiveRestoreSession = new RestoreSession(transportID);
        }
        return mActiveRestoreSession;
    }

    // ----- Restore session -----

    class RestoreSession extends IRestoreSession.Stub {
        private static final String TAG = "RestoreSession";

        private IBackupTransport mRestoreTransport = null;
        RestoreSet[] mRestoreSets = null;

        RestoreSession(int transportID) {
            mRestoreTransport = createTransport(transportID);
        }

        // --- Binder interface ---
        public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
            mContext.enforceCallingPermission("android.permission.BACKUP",
                    "getAvailableRestoreSets");

            try {
            synchronized(this) {
                if (mRestoreSets == null) {
                    mRestoreSets = mRestoreTransport.getAvailableRestoreSets();
                }
                return mRestoreSets;
            }
            } catch (RuntimeException e) {
                Log.d(TAG, "getAvailableRestoreSets exception");
                e.printStackTrace();
                throw e;
            }
        }

        public int performRestore(int token) throws android.os.RemoteException {
            mContext.enforceCallingPermission("android.permission.BACKUP", "performRestore");

            if (mRestoreSets != null) {
                for (int i = 0; i < mRestoreSets.length; i++) {
                    if (token == mRestoreSets[i].token) {
                        Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE,
                                mRestoreTransport);
                        msg.arg1 = token;
                        mBackupHandler.sendMessage(msg);
                        return 0;
                    }
                }
            } else {
                if (DEBUG) Log.v(TAG, "No current restore set, not doing restore");
            }
            return -1;
        }

        public void endRestoreSession() throws android.os.RemoteException {
            mContext.enforceCallingPermission("android.permission.BACKUP",
                    "endRestoreSession");

            mRestoreTransport.endSession();
            mRestoreTransport = null;
            synchronized(BackupManagerService.this) {
                if (BackupManagerService.this.mActiveRestoreSession == this) {
                    BackupManagerService.this.mActiveRestoreSession = null;
                } else {
                    Log.e(TAG, "ending non-current restore session");
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
