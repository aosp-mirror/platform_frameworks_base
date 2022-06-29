/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.packageinstaller.wear;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of package manager installation using modern PackageInstaller api.
 *
 * Heavily copied from Wearsky/Finsky implementation
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class PackageInstallerImpl {
    private static final String TAG = "PackageInstallerImpl";

    /** Intent actions used for broadcasts from PackageInstaller back to the local receiver */
    private static final String ACTION_INSTALL_COMMIT =
            "com.android.vending.INTENT_PACKAGE_INSTALL_COMMIT";

    private final Context mContext;
    private final PackageInstaller mPackageInstaller;
    private final Map<String, PackageInstaller.SessionInfo> mSessionInfoMap;
    private final Map<String, PackageInstaller.Session> mOpenSessionMap;

    public PackageInstallerImpl(Context context) {
        mContext = context.getApplicationContext();
        mPackageInstaller = mContext.getPackageManager().getPackageInstaller();

        // Capture a map of known sessions
        // This list will be pruned a bit later (stale sessions will be canceled)
        mSessionInfoMap = new HashMap<String, PackageInstaller.SessionInfo>();
        List<PackageInstaller.SessionInfo> mySessions = mPackageInstaller.getMySessions();
        for (int i = 0; i < mySessions.size(); i++) {
            PackageInstaller.SessionInfo sessionInfo = mySessions.get(i);
            String packageName = sessionInfo.getAppPackageName();
            PackageInstaller.SessionInfo oldInfo = mSessionInfoMap.put(packageName, sessionInfo);

            // Checking for old info is strictly for logging purposes
            if (oldInfo != null) {
                Log.w(TAG, "Multiple sessions for " + packageName + " found. Removing " + oldInfo
                        .getSessionId() + " & keeping " + mySessions.get(i).getSessionId());
            }
        }
        mOpenSessionMap = new HashMap<String, PackageInstaller.Session>();
    }

    /**
     * This callback will be made after an installation attempt succeeds or fails.
     */
    public interface InstallListener {
        /**
         * This callback signals that preflight checks have succeeded and installation
         * is beginning.
         */
        void installBeginning();

        /**
         * This callback signals that installation has completed.
         */
        void installSucceeded();

        /**
         * This callback signals that installation has failed.
         */
        void installFailed(int errorCode, String errorDesc);
    }

    /**
     * This is a placeholder implementation that bundles an entire "session" into a single
     * call. This will be replaced by more granular versions that allow longer session lifetimes,
     * download progress tracking, etc.
     *
     * This must not be called on main thread.
     */
    public void install(final String packageName, ParcelFileDescriptor parcelFileDescriptor,
            final InstallListener callback) {
        // 0. Generic try/catch block because I am not really sure what exceptions (other than
        // IOException) might be thrown by PackageInstaller and I want to handle them
        // at least slightly gracefully.
        try {
            // 1. Create or recover a session, and open it
            // Try recovery first
            PackageInstaller.Session session = null;
            PackageInstaller.SessionInfo sessionInfo = mSessionInfoMap.get(packageName);
            if (sessionInfo != null) {
                // See if it's openable, or already held open
                session = getSession(packageName);
            }
            // If open failed, or there was no session, create a new one and open it.
            // If we cannot create or open here, the failure is terminal.
            if (session == null) {
                try {
                    innerCreateSession(packageName);
                } catch (IOException ioe) {
                    Log.e(TAG, "Can't create session for " + packageName + ": " + ioe.getMessage());
                    callback.installFailed(InstallerConstants.ERROR_INSTALL_CREATE_SESSION,
                            "Could not create session");
                    mSessionInfoMap.remove(packageName);
                    return;
                }
                sessionInfo = mSessionInfoMap.get(packageName);
                try {
                    session = mPackageInstaller.openSession(sessionInfo.getSessionId());
                    mOpenSessionMap.put(packageName, session);
                } catch (SecurityException se) {
                    Log.e(TAG, "Can't open session for " + packageName + ": " + se.getMessage());
                    callback.installFailed(InstallerConstants.ERROR_INSTALL_OPEN_SESSION,
                            "Can't open session");
                    mSessionInfoMap.remove(packageName);
                    return;
                }
            }

            // 2. Launch task to handle file operations.
            InstallTask task = new InstallTask( mContext, packageName, parcelFileDescriptor,
                    callback, session,
                    getCommitCallback(packageName, sessionInfo.getSessionId(), callback));
            task.execute();
            if (task.isError()) {
                cancelSession(sessionInfo.getSessionId(), packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception while installing: " + packageName + ": "
                    + e.getMessage());
            callback.installFailed(InstallerConstants.ERROR_INSTALL_SESSION_EXCEPTION,
                    "Unexpected exception while installing " + packageName);
        }
    }

    /**
     * Retrieve an existing session. Will open if needed, but does not attempt to create.
     */
    private PackageInstaller.Session getSession(String packageName) {
        // Check for already-open session
        PackageInstaller.Session session = mOpenSessionMap.get(packageName);
        if (session != null) {
            try {
                // Probe the session to ensure that it's still open. This may or may not
                // throw (if non-open), but it may serve as a canary for stale sessions.
                session.getNames();
                return session;
            } catch (IOException ioe) {
                Log.e(TAG, "Stale open session for " + packageName + ": " + ioe.getMessage());
                mOpenSessionMap.remove(packageName);
            } catch (SecurityException se) {
                Log.e(TAG, "Stale open session for " + packageName + ": " + se.getMessage());
                mOpenSessionMap.remove(packageName);
            }
        }
        // Check to see if this is a known session
        PackageInstaller.SessionInfo sessionInfo = mSessionInfoMap.get(packageName);
        if (sessionInfo == null) {
            return null;
        }
        // Try to open it. If we fail here, assume that the SessionInfo was stale.
        try {
            session = mPackageInstaller.openSession(sessionInfo.getSessionId());
        } catch (SecurityException se) {
            Log.w(TAG, "SessionInfo was stale for " + packageName + " - deleting info");
            mSessionInfoMap.remove(packageName);
            return null;
        } catch (IOException ioe) {
            Log.w(TAG, "IOException opening old session for " + ioe.getMessage()
                    + " - deleting info");
            mSessionInfoMap.remove(packageName);
            return null;
        }
        mOpenSessionMap.put(packageName, session);
        return session;
    }

    /** This version throws an IOException when the session cannot be created */
    private void innerCreateSession(String packageName) throws IOException {
        if (mSessionInfoMap.containsKey(packageName)) {
            Log.w(TAG, "Creating session for " + packageName + " when one already exists");
            return;
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);

        // IOException may be thrown at this point
        int sessionId = mPackageInstaller.createSession(params);
        PackageInstaller.SessionInfo sessionInfo = mPackageInstaller.getSessionInfo(sessionId);
        mSessionInfoMap.put(packageName, sessionInfo);
    }

    /**
     * Cancel a session based on its sessionId. Package name is for logging only.
     */
    private void cancelSession(int sessionId, String packageName) {
        // Close if currently held open
        closeSession(packageName);
        // Remove local record
        mSessionInfoMap.remove(packageName);
        try {
            mPackageInstaller.abandonSession(sessionId);
        } catch (SecurityException se) {
            // The session no longer exists, so we can exit quietly.
            return;
        }
    }

    /**
     * Close a session if it happens to be held open.
     */
    private void closeSession(String packageName) {
        PackageInstaller.Session session = mOpenSessionMap.remove(packageName);
        if (session != null) {
            // Unfortunately close() is not idempotent. Try our best to make this safe.
            try {
                session.close();
            } catch (Exception e) {
                Log.w(TAG, "Unexpected error closing session for " + packageName + ": "
                        + e.getMessage());
            }
        }
    }

    /**
     * Creates a commit callback for the package install that's underway. This will be called
     * some time after calling session.commit() (above).
     */
    private IntentSender getCommitCallback(final String packageName, final int sessionId,
            final InstallListener callback) {
        // Create a single-use broadcast receiver
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mContext.unregisterReceiver(this);
                handleCommitCallback(intent, packageName, sessionId, callback);
            }
        };
        // Create a matching intent-filter and register the receiver
        String action = ACTION_INSTALL_COMMIT + "." + packageName;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(action);
        mContext.registerReceiver(broadcastReceiver, intentFilter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        // Create a matching PendingIntent and use it to generate the IntentSender
        Intent broadcastIntent = new Intent(action);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, packageName.hashCode(),
                broadcastIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_MUTABLE);
        return pendingIntent.getIntentSender();
    }

    /**
     * Examine the extras to determine information about the package update/install, decode
     * the result, and call the appropriate callback.
     *
     * @param intent The intent, which the PackageInstaller will have added Extras to
     * @param packageName The package name we created the receiver for
     * @param sessionId The session Id we created the receiver for
     * @param callback The callback to report success/failure to
     */
    private void handleCommitCallback(Intent intent, String packageName, int sessionId,
            InstallListener callback) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Installation of " + packageName + " finished with extras "
                    + intent.getExtras());
        }
        String statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            cancelSession(sessionId, packageName);
            callback.installSucceeded();
        } else if (status == -1 /*PackageInstaller.STATUS_USER_ACTION_REQUIRED*/) {
            // TODO - use the constant when the correct/final name is in the SDK
            // TODO This is unexpected, so we are treating as failure for now
            cancelSession(sessionId, packageName);
            callback.installFailed(InstallerConstants.ERROR_INSTALL_USER_ACTION_REQUIRED,
                    "Unexpected: user action required");
        } else {
            cancelSession(sessionId, packageName);
            int errorCode = getPackageManagerErrorCode(status);
            Log.e(TAG, "Error " + errorCode + " while installing " + packageName + ": "
                    + statusMessage);
            callback.installFailed(errorCode, null);
        }
    }

    private int getPackageManagerErrorCode(int status) {
        // This is a hack: because PackageInstaller now reports error codes
        // with small positive values, we need to remap them into a space
        // that is more compatible with the existing package manager error codes.
        // See https://sites.google.com/a/google.com/universal-store/documentation
        //       /android-client/download-error-codes
        int errorCode;
        if (status == Integer.MIN_VALUE) {
            errorCode = InstallerConstants.ERROR_INSTALL_MALFORMED_BROADCAST;
        } else {
            errorCode = InstallerConstants.ERROR_PACKAGEINSTALLER_BASE - status;
        }
        return errorCode;
    }
}
