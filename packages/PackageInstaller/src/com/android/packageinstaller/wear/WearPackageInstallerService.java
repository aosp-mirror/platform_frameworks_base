/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.PackageUtil;
import com.android.packageinstaller.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service that will install/uninstall packages. It will check for permissions and features as well.
 *
 * -----------
 *
 * Debugging information:
 *
 *  Install Action example:
 *  adb shell am startservice -a com.android.packageinstaller.wear.INSTALL_PACKAGE \
 *     -d package://com.google.android.gms \
 *     --eu com.google.android.clockwork.EXTRA_ASSET_URI content://com.google.android.clockwork.home.provider/host/com.google.android.wearable.app/wearable/com.google.android.gms/apk \
 *     --es android.intent.extra.INSTALLER_PACKAGE_NAME com.google.android.gms \
 *     --ez com.google.android.clockwork.EXTRA_CHECK_PERMS false \
 *     --eu com.google.android.clockwork.EXTRA_PERM_URI content://com.google.android.clockwork.home.provider/host/com.google.android.wearable.app/permissions \
 *     com.android.packageinstaller/com.android.packageinstaller.wear.WearPackageInstallerService
 *
 *  Uninstall Action example:
 *  adb shell am startservice -a com.android.packageinstaller.wear.UNINSTALL_PACKAGE \
 *     -d package://com.google.android.gms \
 *     com.android.packageinstaller/com.android.packageinstaller.wear.WearPackageInstallerService
 *
 *  Retry GMS:
 *  adb shell am startservice -a com.android.packageinstaller.wear.RETRY_GMS \
 *     com.android.packageinstaller/com.android.packageinstaller.wear.WearPackageInstallerService
 */
public class WearPackageInstallerService extends Service {
    private static final String TAG = "WearPkgInstallerService";

    private static final String WEAR_APPS_CHANNEL = "wear_app_install_uninstall";

    private final int START_INSTALL = 1;
    private final int START_UNINSTALL = 2;

    private int mInstallNotificationId = 1;
    private final Map<String, Integer> mNotifIdMap = new ArrayMap<>();

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case START_INSTALL:
                    installPackage(msg.getData());
                    break;
                case START_UNINSTALL:
                    uninstallPackage(msg.getData());
                    break;
            }
        }
    }
    private ServiceHandler mServiceHandler;
    private NotificationChannel mNotificationChannel;
    private static volatile PowerManager.WakeLock lockStatic = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("PackageInstallerThread",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        mServiceHandler = new ServiceHandler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!DeviceUtils.isWear(this)) {
            Log.w(TAG, "Not running on wearable.");
            finishServiceEarly(startId);
            return START_NOT_STICKY;
        }

        if (intent == null) {
            Log.w(TAG, "Got null intent.");
            finishServiceEarly(startId);
            return START_NOT_STICKY;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Got install/uninstall request " + intent);
        }

        Uri packageUri = intent.getData();
        if (packageUri == null) {
            Log.e(TAG, "No package URI in intent");
            finishServiceEarly(startId);
            return START_NOT_STICKY;
        }

        final String packageName = WearPackageUtil.getSanitizedPackageName(packageUri);
        if (packageName == null) {
            Log.e(TAG, "Invalid package name in URI (expected package:<pkgName>): " + packageUri);
            finishServiceEarly(startId);
            return START_NOT_STICKY;
        }

        PowerManager.WakeLock lock = getLock(this.getApplicationContext());
        if (!lock.isHeld()) {
            lock.acquire();
        }

        Bundle intentBundle = intent.getExtras();
        if (intentBundle == null) {
            intentBundle = new Bundle();
        }
        WearPackageArgs.setStartId(intentBundle, startId);
        WearPackageArgs.setPackageName(intentBundle, packageName);
        Message msg;
        String notifTitle;
        if (Intent.ACTION_INSTALL_PACKAGE.equals(intent.getAction())) {
            msg = mServiceHandler.obtainMessage(START_INSTALL);
            notifTitle = getString(R.string.installing);
        } else if (Intent.ACTION_UNINSTALL_PACKAGE.equals(intent.getAction())) {
            msg = mServiceHandler.obtainMessage(START_UNINSTALL);
            notifTitle = getString(R.string.uninstalling);
        } else {
            Log.e(TAG, "Unknown action : " + intent.getAction());
            finishServiceEarly(startId);
            return START_NOT_STICKY;
        }
        Pair<Integer, Notification> notifPair = buildNotification(packageName, notifTitle);
        startForeground(notifPair.first, notifPair.second);
        msg.setData(intentBundle);
        mServiceHandler.sendMessage(msg);
        return START_NOT_STICKY;
    }

    private void installPackage(Bundle argsBundle) {
        int startId = WearPackageArgs.getStartId(argsBundle);
        final String packageName = WearPackageArgs.getPackageName(argsBundle);
        final Uri assetUri = WearPackageArgs.getAssetUri(argsBundle);
        final Uri permUri = WearPackageArgs.getPermUri(argsBundle);
        boolean checkPerms = WearPackageArgs.checkPerms(argsBundle);
        boolean skipIfSameVersion = WearPackageArgs.skipIfSameVersion(argsBundle);
        int companionSdkVersion = WearPackageArgs.getCompanionSdkVersion(argsBundle);
        int companionDeviceVersion = WearPackageArgs.getCompanionDeviceVersion(argsBundle);
        String compressionAlg = WearPackageArgs.getCompressionAlg(argsBundle);
        boolean skipIfLowerVersion = WearPackageArgs.skipIfLowerVersion(argsBundle);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Installing package: " + packageName + ", assetUri: " + assetUri +
                    ",permUri: " + permUri + ", startId: " + startId + ", checkPerms: " +
                    checkPerms + ", skipIfSameVersion: " + skipIfSameVersion +
                    ", compressionAlg: " + compressionAlg + ", companionSdkVersion: " +
                    companionSdkVersion + ", companionDeviceVersion: " + companionDeviceVersion +
                    ", skipIfLowerVersion: " + skipIfLowerVersion);
        }
        final PackageManager pm = getPackageManager();
        File tempFile = null;
        int installFlags = 0;
        PowerManager.WakeLock lock = getLock(this.getApplicationContext());
        boolean messageSent = false;
        try {
            PackageInfo existingPkgInfo = null;
            try {
                existingPkgInfo = pm.getPackageInfo(packageName,
                        PackageManager.MATCH_ANY_USER | PackageManager.GET_PERMISSIONS);
                if (existingPkgInfo != null) {
                    installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore this exception. We could not find the package, will treat as a new
                // installation.
            }
            if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Replacing package:" + packageName);
                }
            }
            // TODO(28021618): This was left as a temp file due to the fact that this code is being
            //       deprecated and that we need the bare minimum to continue working moving forward
            //       If this code is used as reference, this permission logic might want to be
            //       reworked to use a stream instead of a file so that we don't need to write a
            //       file at all.  Note that there might be some trickiness with opening a stream
            //       for multiple users.
            ParcelFileDescriptor parcelFd = getContentResolver()
                    .openFileDescriptor(assetUri, "r");
            tempFile = WearPackageUtil.getFileFromFd(WearPackageInstallerService.this,
                    parcelFd, packageName, compressionAlg);
            if (tempFile == null) {
                Log.e(TAG, "Could not create a temp file from FD for " + packageName);
                return;
            }
            PackageParser.Package pkg = PackageUtil.getPackageInfo(this, tempFile);
            if (pkg == null) {
                Log.e(TAG, "Could not parse apk information for " + packageName);
                return;
            }

            if (!pkg.packageName.equals(packageName)) {
                Log.e(TAG, "Wearable Package Name has to match what is provided for " +
                        packageName);
                return;
            }

            pkg.applicationInfo.sourceDir = tempFile.getPath();
            pkg.applicationInfo.publicSourceDir = tempFile.getPath();
            getLabelAndUpdateNotification(packageName,
                    getString(R.string.installing_app, pkg.applicationInfo.loadLabel(pm)));

            List<String> wearablePerms = pkg.requestedPermissions;

            // Log if the installed pkg has a higher version number.
            if (existingPkgInfo != null) {
                if (existingPkgInfo.getLongVersionCode() == pkg.getLongVersionCode()) {
                    if (skipIfSameVersion) {
                        Log.w(TAG, "Version number (" + pkg.getLongVersionCode() +
                                ") of new app is equal to existing app for " + packageName +
                                "; not installing due to versionCheck");
                        return;
                    } else {
                        Log.w(TAG, "Version number of new app (" + pkg.getLongVersionCode() +
                                ") is equal to existing app for " + packageName);
                    }
                } else if (existingPkgInfo.getLongVersionCode() > pkg.getLongVersionCode()) {
                    if (skipIfLowerVersion) {
                        // Starting in Feldspar, we are not going to allow downgrades of any app.
                        Log.w(TAG, "Version number of new app (" + pkg.getLongVersionCode() +
                                ") is lower than existing app ( "
                                + existingPkgInfo.getLongVersionCode() +
                                ") for " + packageName + "; not installing due to versionCheck");
                        return;
                    } else {
                        Log.w(TAG, "Version number of new app (" + pkg.getLongVersionCode() +
                                ") is lower than existing app ( "
                                + existingPkgInfo.getLongVersionCode() + ") for " + packageName);
                    }
                }

                // Following the Android Phone model, we should only check for permissions for any
                // newly defined perms.
                if (existingPkgInfo.requestedPermissions != null) {
                    for (int i = 0; i < existingPkgInfo.requestedPermissions.length; ++i) {
                        // If the permission is granted, then we will not ask to request it again.
                        if ((existingPkgInfo.requestedPermissionsFlags[i] &
                                PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, existingPkgInfo.requestedPermissions[i] +
                                        " is already granted for " + packageName);
                            }
                            wearablePerms.remove(existingPkgInfo.requestedPermissions[i]);
                        }
                    }
                }
            }

            // Check that the wearable has all the features.
            boolean hasAllFeatures = true;
            if (pkg.reqFeatures != null) {
                for (FeatureInfo feature : pkg.reqFeatures) {
                    if (feature.name != null && !pm.hasSystemFeature(feature.name) &&
                            (feature.flags & FeatureInfo.FLAG_REQUIRED) != 0) {
                        Log.e(TAG, "Wearable does not have required feature: " + feature +
                                " for " + packageName);
                        hasAllFeatures = false;
                    }
                }
            }

            if (!hasAllFeatures) {
                return;
            }

            // Check permissions on both the new wearable package and also on the already installed
            // wearable package.
            // If the app is targeting API level 23, we will also start a service in ClockworkHome
            // which will ultimately prompt the user to accept/reject permissions.
            if (checkPerms && !checkPermissions(pkg, companionSdkVersion, companionDeviceVersion,
                    permUri, wearablePerms, tempFile)) {
                Log.w(TAG, "Wearable does not have enough permissions.");
                return;
            }

            // Finally install the package.
            ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(assetUri, "r");
            PackageInstallerFactory.getPackageInstaller(this).install(packageName, fd,
                    new PackageInstallListener(this, lock, startId, packageName));

            messageSent = true;
            Log.i(TAG, "Sent installation request for " + packageName);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Could not find the file with URI " + assetUri, e);
        } finally {
            if (!messageSent) {
                // Some error happened. If the message has been sent, we can wait for the observer
                // which will finish the service.
                if (tempFile != null) {
                    tempFile.delete();
                }
                finishService(lock, startId);
            }
        }
    }

    // TODO: This was left using the old PackageManager API due to the fact that this code is being
    //       deprecated and that we need the bare minimum to continue working moving forward
    //       If this code is used as reference, this logic should be reworked to use the new
    //       PackageInstaller APIs similar to how installPackage was reworked
    private void uninstallPackage(Bundle argsBundle) {
        int startId = WearPackageArgs.getStartId(argsBundle);
        final String packageName = WearPackageArgs.getPackageName(argsBundle);

        PowerManager.WakeLock lock = getLock(this.getApplicationContext());
        final PackageManager pm = getPackageManager();
        try {
            PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
            getLabelAndUpdateNotification(packageName,
                    getString(R.string.uninstalling_app, pkgInfo.applicationInfo.loadLabel(pm)));

            // Found package, send uninstall request.
            pm.deletePackage(packageName, new PackageDeleteObserver(lock, startId),
                    PackageManager.DELETE_ALL_USERS);

            Log.i(TAG, "Sent delete request for " + packageName);
        } catch (IllegalArgumentException | PackageManager.NameNotFoundException e) {
            // Couldn't find the package, no need to call uninstall.
            Log.w(TAG, "Could not find package, not deleting " + packageName, e);
            finishService(lock, startId);
        }
    }

    private boolean checkPermissions(PackageParser.Package pkg, int companionSdkVersion,
            int companionDeviceVersion, Uri permUri, List<String> wearablePermissions,
            File apkFile) {
        // Assumption: We are running on Android O.
        // If the Phone App is targeting M, all permissions may not have been granted to the phone
        // app. If the Wear App is then not targeting M, there may be permissions that are not
        // granted on the Phone app (by the user) right now and we cannot just grant it for the Wear
        // app.
        if (pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.M) {
            // Install the app if Wear App is ready for the new perms model.
            return true;
        }

        if (!doesWearHaveUngrantedPerms(pkg.packageName, permUri, wearablePermissions)) {
            // All permissions requested by the watch are already granted on the phone, no need
            // to do anything.
            return true;
        }

        // Log an error if Wear is targeting < 23 and phone is targeting >= 23.
        if (companionSdkVersion == 0 || companionSdkVersion >= Build.VERSION_CODES.M) {
            Log.e(TAG, "MNC: Wear app's targetSdkVersion should be at least 23, if "
                    + "phone app is targeting at least 23, will continue.");
        }

        return false;
    }

    /**
     * Given a {@string packageName} corresponding to a phone app, query the provider for all the
     * perms that are granted.
     *
     * @return true if the Wear App has any perms that have not been granted yet on the phone side.
     * @return true if there is any error cases.
     */
    private boolean doesWearHaveUngrantedPerms(String packageName, Uri permUri,
            List<String> wearablePermissions) {
        if (permUri == null) {
            Log.e(TAG, "Permission URI is null");
            // Pretend there is an ungranted permission to avoid installing for error cases.
            return true;
        }
        Cursor permCursor = getContentResolver().query(permUri, null, null, null, null);
        if (permCursor == null) {
            Log.e(TAG, "Could not get the cursor for the permissions");
            // Pretend there is an ungranted permission to avoid installing for error cases.
            return true;
        }

        Set<String> grantedPerms = new HashSet<>();
        Set<String> ungrantedPerms = new HashSet<>();
        while(permCursor.moveToNext()) {
            // Make sure that the MatrixCursor returned by the ContentProvider has 2 columns and
            // verify their types.
            if (permCursor.getColumnCount() == 2
                    && Cursor.FIELD_TYPE_STRING == permCursor.getType(0)
                    && Cursor.FIELD_TYPE_INTEGER == permCursor.getType(1)) {
                String perm = permCursor.getString(0);
                Integer granted = permCursor.getInt(1);
                if (granted == 1) {
                    grantedPerms.add(perm);
                } else {
                    ungrantedPerms.add(perm);
                }
            }
        }
        permCursor.close();

        boolean hasUngrantedPerm = false;
        for (String wearablePerm : wearablePermissions) {
            if (!grantedPerms.contains(wearablePerm)) {
                hasUngrantedPerm = true;
                if (!ungrantedPerms.contains(wearablePerm)) {
                    // This is an error condition. This means that the wearable has permissions that
                    // are not even declared in its host app. This is a developer error.
                    Log.e(TAG, "Wearable " + packageName + " has a permission \"" + wearablePerm
                            + "\" that is not defined in the host application's manifest.");
                } else {
                    Log.w(TAG, "Wearable " + packageName + " has a permission \"" + wearablePerm +
                            "\" that is not granted in the host application.");
                }
            }
        }
        return hasUngrantedPerm;
    }

    /** Finishes the service after fulfilling obligation to call startForeground. */
    private void finishServiceEarly(int startId) {
        Pair<Integer, Notification> notifPair = buildNotification(
                getApplicationContext().getPackageName(), "");
        startForeground(notifPair.first, notifPair.second);
        finishService(null, startId);
    }

    private void finishService(PowerManager.WakeLock lock, int startId) {
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
        stopSelf(startId);
    }

    private synchronized PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            lockStatic = mgr.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, context.getClass().getSimpleName());
            lockStatic.setReferenceCounted(true);
        }
        return lockStatic;
    }

    private class PackageInstallListener implements PackageInstallerImpl.InstallListener {
        private Context mContext;
        private PowerManager.WakeLock mWakeLock;
        private int mStartId;
        private String mApplicationPackageName;
        private PackageInstallListener(Context context, PowerManager.WakeLock wakeLock,
                int startId, String applicationPackageName) {
            mContext = context;
            mWakeLock = wakeLock;
            mStartId = startId;
            mApplicationPackageName = applicationPackageName;
        }

        @Override
        public void installBeginning() {
            Log.i(TAG, "Package " + mApplicationPackageName + " is being installed.");
        }

        @Override
        public void installSucceeded() {
            try {
                Log.i(TAG, "Package " + mApplicationPackageName + " was installed.");

                // Delete tempFile from the file system.
                File tempFile = WearPackageUtil.getTemporaryFile(mContext, mApplicationPackageName);
                if (tempFile != null) {
                    tempFile.delete();
                }
            } finally {
                finishService(mWakeLock, mStartId);
            }
        }

        @Override
        public void installFailed(int errorCode, String errorDesc) {
            Log.e(TAG, "Package install failed " + mApplicationPackageName
                    + ", errorCode " + errorCode);
            finishService(mWakeLock, mStartId);
        }
    }

    private class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        private PowerManager.WakeLock mWakeLock;
        private int mStartId;

        private PackageDeleteObserver(PowerManager.WakeLock wakeLock, int startId) {
            mWakeLock = wakeLock;
            mStartId = startId;
        }

        public void packageDeleted(String packageName, int returnCode) {
            try {
                if (returnCode >= 0) {
                    Log.i(TAG, "Package " + packageName + " was uninstalled.");
                } else {
                    Log.e(TAG, "Package uninstall failed " + packageName + ", returnCode " +
                            returnCode);
                }
            } finally {
                finishService(mWakeLock, mStartId);
            }
        }
    }

    private synchronized Pair<Integer, Notification> buildNotification(final String packageName,
            final String title) {
        int notifId;
        if (mNotifIdMap.containsKey(packageName)) {
            notifId = mNotifIdMap.get(packageName);
        } else {
            notifId = mInstallNotificationId++;
            mNotifIdMap.put(packageName, notifId);
        }

        if (mNotificationChannel == null) {
            mNotificationChannel = new NotificationChannel(WEAR_APPS_CHANNEL,
                    getString(R.string.wear_app_channel), NotificationManager.IMPORTANCE_MIN);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(mNotificationChannel);
        }
        return new Pair<>(notifId, new Notification.Builder(this, WEAR_APPS_CHANNEL)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(title)
            .build());
    }

    private void getLabelAndUpdateNotification(String packageName, String title) {
        // Update notification since we have a label now.
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        Pair<Integer, Notification> notifPair = buildNotification(packageName, title);
        notificationManager.notify(notifPair.first, notifPair.second);
    }
}
