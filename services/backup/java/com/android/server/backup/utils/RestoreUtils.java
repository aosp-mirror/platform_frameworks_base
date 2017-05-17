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

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Process;
import android.util.Slog;

import com.android.server.backup.FileMetadata;
import com.android.server.backup.restore.RestoreDeleteObserver;
import com.android.server.backup.restore.RestoreInstallObserver;
import com.android.server.backup.restore.RestorePolicy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
     * @param packageManager - {@link PackageManager} instance.
     * @param installObserver - {@link RestoreInstallObserver} instance.
     * @param deleteObserver - {@link RestoreDeleteObserver} instance.
     * @param manifestSignatures - manifest signatures.
     * @param packagePolicies - package policies.
     * @param info - backup file info.
     * @param installerPackage - installer package.
     * @param bytesReadListener - listener to be called for counting bytes read.
     * @param dataDir - directory where to create apk file.
     * @return true if apk was successfully read and installed and false otherwise.
     */
    // TODO: Refactor to get rid of unneeded params.
    public static boolean installApk(InputStream instream, PackageManager packageManager,
            RestoreInstallObserver installObserver, RestoreDeleteObserver deleteObserver,
            HashMap<String, Signature[]> manifestSignatures,
            HashMap<String, RestorePolicy> packagePolicies,
            FileMetadata info,
            String installerPackage, BytesReadListener bytesReadListener,
            File dataDir) {
        boolean okay = true;

        if (DEBUG) {
            Slog.d(TAG, "Installing from backup: " + info.packageName);
        }

        // The file content is an .apk file.  Copy it out to a staging location and
        // attempt to install it.
        File apkFile = new File(dataDir, info.packageName);
        try {
            FileOutputStream apkStream = new FileOutputStream(apkFile);
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
            apkStream.close();

            // make sure the installer can read it
            apkFile.setReadable(true, false);

            // Now install it
            Uri packageUri = Uri.fromFile(apkFile);
            installObserver.reset();
            // TODO: PackageManager.installPackage() is deprecated, refactor.
            packageManager.installPackage(packageUri, installObserver,
                    PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_FROM_ADB,
                    installerPackage);
            installObserver.waitForCompletion();

            if (installObserver.getResult() != PackageManager.INSTALL_SUCCEEDED) {
                // The only time we continue to accept install of data even if the
                // apk install failed is if we had already determined that we could
                // accept the data regardless.
                if (packagePolicies.get(info.packageName) != RestorePolicy.ACCEPT) {
                    okay = false;
                }
            } else {
                // Okay, the install succeeded.  Make sure it was the right app.
                boolean uninstall = false;
                if (!installObserver.getPackageName().equals(info.packageName)) {
                    Slog.w(TAG, "Restore stream claimed to include apk for "
                            + info.packageName + " but apk was really "
                            + installObserver.getPackageName());
                    // delete the package we just put in place; it might be fraudulent
                    okay = false;
                    uninstall = true;
                } else {
                    try {
                        PackageInfo pkg = packageManager.getPackageInfo(
                                info.packageName,
                                PackageManager.GET_SIGNATURES);
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP)
                                == 0) {
                            Slog.w(TAG, "Restore stream contains apk of package "
                                    + info.packageName
                                    + " but it disallows backup/restore");
                            okay = false;
                        } else {
                            // So far so good -- do the signatures match the manifest?
                            Signature[] sigs = manifestSignatures.get(info.packageName);
                            if (AppBackupUtils.signaturesMatch(sigs, pkg)) {
                                // If this is a system-uid app without a declared backup agent,
                                // don't restore any of the file data.
                                if ((pkg.applicationInfo.uid < Process.FIRST_APPLICATION_UID)
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
                            installObserver.getPackageName(),
                            deleteObserver, 0);
                    deleteObserver.waitForCompletion();
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to transcribe restored apk for install");
            okay = false;
        } finally {
            apkFile.delete();
        }

        return okay;
    }
}
