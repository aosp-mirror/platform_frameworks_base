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

package com.android.server.backup.utils;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;

import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.Session;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.restore.RestoreDeleteObserver;
import com.android.server.backup.restore.RestorePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

/**
 * Utility methods used by {@link com.android.server.backup.restore.PerformAdbRestoreTask} and
 * {@link com.android.server.backup.restore.FullRestoreEngine}.
 */
public class RestoreUtils {
    /**
     * Reads apk contents from input stream and installs the apk.
     *
     * @param instream - input stream to read apk data from.
     * @param context - installing context
     * @param deleteObserver - {@link RestoreDeleteObserver} instance.
     * @param manifestSignatures - manifest signatures.
     * @param packagePolicies - package policies.
     * @param info - backup file info.
     * @param installerPackageName - package name of installer.
     * @param bytesReadListener - listener to be called for counting bytes read.
     * @return true if apk was successfully read and installed and false otherwise.
     */
    // TODO: Refactor to get rid of unneeded params.
    public static boolean installApk(InputStream instream, Context context,
            RestoreDeleteObserver deleteObserver,
            HashMap<String, Signature[]> manifestSignatures,
            HashMap<String, RestorePolicy> packagePolicies,
            FileMetadata info,
            String installerPackageName,
            BytesReadListener bytesReadListener,
            int userId) {
        boolean okay = true;

        if (DEBUG) {
            Slog.d(TAG, "Installing from backup: " + info.packageName);
        }

        try {
            LocalIntentReceiver receiver = new LocalIntentReceiver();
            PackageManager packageManager = context.getPackageManager();
            PackageInstaller installer = packageManager.getPackageInstaller();

            SessionParams params = new SessionParams(SessionParams.MODE_FULL_INSTALL);
            params.setInstallerPackageName(installerPackageName);
            int sessionId = installer.createSession(params);
            try {
                try (Session session = installer.openSession(sessionId)) {
                    try (OutputStream apkStream = session.openWrite(info.packageName, 0,
                            info.size)) {
                        byte[] buffer = new byte[32 * 1024];
                        long size = info.size;
                        while (size > 0) {
                            long toRead = (buffer.length < size) ? buffer.length : size;
                            int didRead = instream.read(buffer, 0, (int) toRead);
                            if (didRead >= 0) {
                                bytesReadListener.onBytesRead(didRead);
                            }
                            apkStream.write(buffer, 0, didRead);
                            size -= didRead;
                        }
                    }

                    // Installation is current disabled
                    session.abandon();
                    // session.commit(receiver.getIntentSender());
                }
            } catch (Exception t) {
                installer.abandonSession(sessionId);

                throw t;
            }

            // Installation is current disabled
            Intent result = null;
            // Intent result = receiver.getResult();

            // Installation is current disabled
            int status = PackageInstaller.STATUS_FAILURE;
            // int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
            //        PackageInstaller.STATUS_FAILURE);

            if (status != PackageInstaller.STATUS_SUCCESS) {
                // The only time we continue to accept install of data even if the
                // apk install failed is if we had already determined that we could
                // accept the data regardless.
                if (packagePolicies.get(info.packageName) != RestorePolicy.ACCEPT) {
                    okay = false;
                }
            } else {
                // Okay, the install succeeded.  Make sure it was the right app.
                boolean uninstall = false;
                final String installedPackageName = result.getStringExtra(
                        PackageInstaller.EXTRA_PACKAGE_NAME);
                if (!installedPackageName.equals(info.packageName)) {
                    Slog.w(TAG, "Restore stream claimed to include apk for "
                            + info.packageName + " but apk was really "
                            + installedPackageName);
                    // delete the package we just put in place; it might be fraudulent
                    okay = false;
                    uninstall = true;
                } else {
                    try {
                        PackageInfo pkg = packageManager.getPackageInfoAsUser(info.packageName,
                                PackageManager.GET_SIGNING_CERTIFICATES, userId);
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP)
                                == 0) {
                            Slog.w(TAG, "Restore stream contains apk of package "
                                    + info.packageName
                                    + " but it disallows backup/restore");
                            okay = false;
                        } else {
                            // So far so good -- do the signatures match the manifest?
                            Signature[] sigs = manifestSignatures.get(info.packageName);
                            PackageManagerInternal pmi = LocalServices.getService(
                                    PackageManagerInternal.class);
                            if (AppBackupUtils.signaturesMatch(sigs, pkg, pmi)) {
                                // If this is a system-uid app without a declared backup agent,
                                // don't restore any of the file data.
                                if (UserHandle.isCore(pkg.applicationInfo.uid)
                                        && (pkg.applicationInfo.backupAgentName == null)) {
                                    Slog.w(TAG, "Installed app " + info.packageName
                                            + " has restricted uid and no agent");
                                    okay = false;
                                }
                            } else {
                                Slog.w(TAG, "Installed app " + info.packageName
                                        + " signatures do not match restore manifest");
                                okay = false;
                                uninstall = true;
                            }
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(TAG, "Install of package " + info.packageName
                                + " succeeded but now not found");
                        okay = false;
                    }
                }

                // If we're not okay at this point, we need to delete the package
                // that we just installed.
                if (uninstall) {
                    deleteObserver.reset();
                    packageManager.deletePackage(
                            installedPackageName,
                            deleteObserver, 0);
                    deleteObserver.waitForCompletion();
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to transcribe restored apk for install");
            okay = false;
        }

        return okay;
    }

    private static class LocalIntentReceiver {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private Intent mResult = null;

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                synchronized (mLock) {
                    mResult = intent;
                    mLock.notifyAll();
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            synchronized (mLock) {
                while (mResult == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        // ignored
                    }
                }

                return mResult;
            }
        }
    }
}
