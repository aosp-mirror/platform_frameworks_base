/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.unit_tests;

import android.net.Uri;
import android.os.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageStats;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.Environment;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;

public class PackageManagerTests extends AndroidTestCase {
    private static final boolean localLOGV = false;
    public static final String TAG="PackageManagerTests";
    public final long MAX_WAIT_TIME=60*1000;
    public final long WAIT_TIME_INCR=10*1000;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    private class PackageInstallObserver extends IPackageInstallObserver.Stub {
        public int returnCode;
        private boolean doneFlag = false;

        public void packageInstalled(String packageName, int returnCode) {
            synchronized(this) {
                this.returnCode = returnCode;
                doneFlag = true;
                notifyAll();
            }
        }

        public boolean isDone() {
            return doneFlag;
        }
    }

    abstract class GenericReceiver extends BroadcastReceiver {
        private boolean doneFlag = false;
        boolean received = false;
        Intent intent;
        IntentFilter filter;
        abstract boolean notifyNow(Intent intent);
        @Override
        public void onReceive(Context context, Intent intent) {
            if (notifyNow(intent)) {
                synchronized (this) {
                    received = true;
                    doneFlag = true;
                    this.intent = intent;
                    notifyAll();
                }
            }
        }

        public boolean isDone() {
            return doneFlag;
        }

        public void setFilter(IntentFilter filter) {
            this.filter = filter;
        }
    }

    class InstallReceiver extends GenericReceiver {
        String pkgName;

        InstallReceiver(String pkgName) {
            this.pkgName = pkgName;
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                return false;
            }
            Uri data = intent.getData();
            String installedPkg = data.getEncodedSchemeSpecificPart();
            if (pkgName.equals(installedPkg)) {
                return true;
            }
            return false;
        }
    }

    PackageManager getPm() {
        return mContext.getPackageManager();
    }

    public boolean invokeInstallPackage(Uri packageURI, int flags,
            final String pkgName, GenericReceiver receiver) throws Exception {
        PackageInstallObserver observer = new PackageInstallObserver();
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        try {
            // Wait on observer
            synchronized(observer) {
                    getPm().installPackage(packageURI, observer, flags, null);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!observer.isDone()) {
                        throw new Exception("Timed out waiting for packageInstalled callback");
                    }
                    if (observer.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                        return false;
                    }
                    synchronized (receiver) {
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!receiver.isDone()) {
                        throw new Exception("Timed out waiting for PACKAGE_ADDED notification");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    Uri getInstallablePackage(int fileResId, File outFile) {
        Resources res = mContext.getResources();
        InputStream is = null;
        try {
            is = res.openRawResource(fileResId);
        } catch (NotFoundException e) {
            failStr("Failed to load resource with id: " + fileResId);
        }
        FileUtils.setPermissions(outFile.getPath(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IRWXO,
                -1, -1);
        assertTrue(FileUtils.copyToFile(is, outFile));
        FileUtils.setPermissions(outFile.getPath(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IRWXO,
                -1, -1);
        return Uri.fromFile(outFile);
    }

    private PackageParser.Package parsePackage(Uri packageURI) {
        final String archiveFilePath = packageURI.getPath();
        PackageParser packageParser = new PackageParser(archiveFilePath);
        File sourceFile = new File(archiveFilePath);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        return packageParser.parsePackage(sourceFile, archiveFilePath, metrics, 0);
    }

    private void assertInstall(String pkgName, int flags) {
        try {
        ApplicationInfo info = getPm().getApplicationInfo(pkgName, 0);
        assertNotNull(info);
        assertEquals(pkgName, info.packageName);
        File dataDir = Environment.getDataDirectory();
        String appInstallPath = new File(dataDir, "app").getPath();
        String drmInstallPath = new File(dataDir, "app-private").getPath();
        File srcDir = new File(info.sourceDir);
        String srcPath = srcDir.getParent();
        File publicSrcDir = new File(info.publicSourceDir);
        String publicSrcPath = publicSrcDir.getParent();

        if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) {
            assertTrue((info.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
            assertEquals(srcPath, drmInstallPath);
            assertEquals(publicSrcPath, appInstallPath);
        } else {
            assertFalse((info.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
            if ((flags & PackageManager.INSTALL_ON_SDCARD) != 0) {
                assertTrue((info.flags & ApplicationInfo.FLAG_ON_SDCARD) != 0);
                // Hardcoded for now
                assertTrue(srcPath.startsWith("/asec"));
                assertTrue(publicSrcPath.startsWith("/asec"));
            } else {
                assertEquals(srcPath, appInstallPath);
                assertEquals(publicSrcPath, appInstallPath);
                assertFalse((info.flags & ApplicationInfo.FLAG_ON_SDCARD) != 0);
            }
        }
        } catch (NameNotFoundException e) {
            failStr("failed with exception : " + e);
        }
    }

    class InstallParams {
        String outFileName;
        Uri packageURI;
        PackageParser.Package pkg;
        InstallParams(PackageParser.Package pkg, String outFileName, Uri packageURI) {
            this.outFileName = outFileName;
            this.packageURI = packageURI;
            this.pkg = pkg;
        }
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install it.
     */
    public InstallParams installFromRawResource(int flags, boolean cleanUp) {
        String outFileName = "install.apk";
        File filesDir = mContext.getFilesDir();
        File outFile = new File(filesDir, outFileName);
        Uri packageURI = getInstallablePackage(R.raw.install, outFile);
        PackageParser.Package pkg = parsePackage(packageURI);
        assertNotNull(pkg);
        InstallReceiver receiver = new InstallReceiver(pkg.packageName);
        try {
            try {
                assertTrue(invokeInstallPackage(packageURI, flags,
                        pkg.packageName, receiver));
            } catch (Exception e) {
                failStr("Failed with exception : " + e);
            }
            // Verify installed information
            assertInstall(pkg.packageName, flags);
            return new InstallParams(pkg, outFileName, packageURI);
        } finally {
            if (cleanUp) {
                getPm().deletePackage(pkg.packageName, null, 0);
                if (outFile != null && outFile.exists()) {
                    outFile.delete();
                }
            }
        }
    }

    @MediumTest
    public void testInstallNormalInternal() {
        installFromRawResource(0, true);
    }

    @MediumTest
    public void testInstallFwdLockedInternal() {
        installFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, true);
    }

    @MediumTest
    public void testInstallSdcard() {
        installFromRawResource(PackageManager.INSTALL_ON_SDCARD, true);
    }

    /* ------------------------- Test replacing packages --------------*/
    class ReplaceReceiver extends GenericReceiver {
        String pkgName;
        boolean removed = false;

        ReplaceReceiver(String pkgName) {
            this.pkgName = pkgName;
            filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                Uri data = intent.getData();
                String installedPkg = data.getEncodedSchemeSpecificPart();
                if (pkgName.equals(installedPkg)) {
                    if (removed) {
                        return true;
                    } else {
                        removed = true;
                    }
                }
            }
            return false;
        }
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install first and then replace it
     * again.
     */
    public void replaceFromRawResource(int flags) {
        InstallParams ip = installFromRawResource(flags, false);
        boolean result = ((flags & PackageManager.INSTALL_REPLACE_EXISTING) != 0);
        GenericReceiver receiver;
        if (result) {
            receiver = new ReplaceReceiver(ip.pkg.packageName);
        } else {
            receiver = new InstallReceiver(ip.pkg.packageName);
        }
        try {
            try {
                assertEquals(invokeInstallPackage(ip.packageURI, flags,
                        ip.pkg.packageName, receiver), result);
                if (result) {
                    assertInstall(ip.pkg.packageName, flags);
                }
            } catch (Exception e) {
                failStr("Failed with exception : " + e);
            }
        } finally {
            getPm().deletePackage(ip.pkg.packageName, null, 0);
            File outFile = new File(ip.outFileName);
            if (outFile != null && outFile.exists()) {
                outFile.delete();
            }
        }
    }

    @MediumTest
    public void testReplaceFailNormalInternal() {
        replaceFromRawResource(0);
    }

    @MediumTest
    public void testReplaceFailFwdLockedInternal() {
        replaceFromRawResource(PackageManager.INSTALL_FORWARD_LOCK);
    }

    @MediumTest
    public void testReplaceFailSdcard() {
        replaceFromRawResource(PackageManager.INSTALL_ON_SDCARD);
    }

    void failStr(String errMsg) {
        Log.w(TAG, "errMsg="+errMsg);
        fail(errMsg);
    }
    void failStr(Exception e) {
        Log.w(TAG, "e.getMessage="+e.getMessage());
        Log.w(TAG, "e="+e);
    }
}
