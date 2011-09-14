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

package android.content.pm;

import com.android.frameworks.coretests.R;
import com.android.internal.content.PackageHelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.storage.IMountService;
import android.os.storage.StorageListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageResultCode;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class PackageManagerTests extends AndroidTestCase {
    private static final boolean localLOGV = true;
    public static final String TAG="PackageManagerTests";
    public final long MAX_WAIT_TIME = 25*1000;
    public final long WAIT_TIME_INCR = 5*1000;
    private static final String SECURE_CONTAINERS_PREFIX = "/mnt/asec";
    private static final int APP_INSTALL_AUTO = PackageHelper.APP_INSTALL_AUTO;
    private static final int APP_INSTALL_DEVICE = PackageHelper.APP_INSTALL_INTERNAL;
    private static final int APP_INSTALL_SDCARD = PackageHelper.APP_INSTALL_EXTERNAL;
    private boolean mOrigState;

    void failStr(String errMsg) {
        Log.w(TAG, "errMsg="+errMsg);
        fail(errMsg);
    }
    void failStr(Exception e) {
        failStr(e.getMessage());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mOrigState = checkMediaState(Environment.MEDIA_MOUNTED);
        if (!mountMedia()) {
            Log.i(TAG, "sdcard not mounted? Some of these tests might fail");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        // Restore media state.
        boolean newState = checkMediaState(Environment.MEDIA_MOUNTED);
        if (newState != mOrigState) {
            if (mOrigState) {
                mountMedia();
            } else {
                unmountMedia();
            }
        }
        super.tearDown();
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

    private PackageManager getPm() {
        return mContext.getPackageManager();
    }

    private IPackageManager getIPm() {
        IPackageManager ipm  = IPackageManager.Stub.asInterface(
                ServiceManager.getService("package"));
        return ipm;
    }

    public boolean invokeInstallPackage(Uri packageURI, int flags, GenericReceiver receiver) {
        PackageInstallObserver observer = new PackageInstallObserver();
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        final boolean DEBUG = true;
        try {
            // Wait on observer
            synchronized(observer) {
                synchronized (receiver) {
                    getPm().installPackage(packageURI, observer, flags, null);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        try {
                            observer.wait(WAIT_TIME_INCR);
                            waitTime += WAIT_TIME_INCR;
                        } catch (InterruptedException e) {
                            Log.i(TAG, "Interrupted during sleep", e);
                        }
                    }
                    if(!observer.isDone()) {
                        fail("Timed out waiting for packageInstalled callback");
                    }
                    if (observer.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                        Log.i(TAG, "Failed to install with error code = " + observer.returnCode);
                        return false;
                    }
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        try {
                            receiver.wait(WAIT_TIME_INCR);
                            waitTime += WAIT_TIME_INCR;
                        } catch (InterruptedException e) {
                            Log.i(TAG, "Interrupted during sleep", e);
                        }
                    }
                    if(!receiver.isDone()) {
                        fail("Timed out waiting for PACKAGE_ADDED notification");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    public void invokeInstallPackageFail(Uri packageURI, int flags, int expectedResult) {
        PackageInstallObserver observer = new PackageInstallObserver();
        try {
            // Wait on observer
            synchronized(observer) {
                getPm().installPackage(packageURI, observer, flags, null);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    try {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    } catch (InterruptedException e) {
                        Log.i(TAG, "Interrupted during sleep", e);
                    }
                }
                if(!observer.isDone()) {
                    fail("Timed out waiting for packageInstalled callback");
                }
                assertEquals(expectedResult, observer.returnCode);
            }
        } finally {
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
        PackageParser.Package pkg = packageParser.parsePackage(sourceFile, archiveFilePath, metrics, 0);
        packageParser = null;
        return pkg;
    }
    private boolean checkSd(long pkgLen) {
        String status = Environment.getExternalStorageState();
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            return false;
        }
        long sdSize = -1;
        StatFs sdStats = new StatFs(
                Environment.getExternalStorageDirectory().getPath());
        sdSize = (long)sdStats.getAvailableBlocks() *
                (long)sdStats.getBlockSize();
        // TODO check for thresholds here
        return pkgLen <= sdSize;

    }
    private boolean checkInt(long pkgLen) {
        StatFs intStats = new StatFs(Environment.getDataDirectory().getPath());
        long intSize = (long)intStats.getBlockCount() *
                (long)intStats.getBlockSize();
        long iSize = (long)intStats.getAvailableBlocks() *
                (long)intStats.getBlockSize();
        // TODO check for thresholds here?
        return pkgLen <= iSize;
    }
    private static final int INSTALL_LOC_INT = 1;
    private static final int INSTALL_LOC_SD = 2;
    private static final int INSTALL_LOC_ERR = -1;
    private int getInstallLoc(int flags, int expInstallLocation, long pkgLen) {
        // Flags explicitly over ride everything else.
        if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0 ) {
            return INSTALL_LOC_INT;
        } else if ((flags & PackageManager.INSTALL_EXTERNAL) != 0 ) {
            return INSTALL_LOC_SD;
        } else if ((flags & PackageManager.INSTALL_INTERNAL) != 0) {
            return INSTALL_LOC_INT;
        }
        // Manifest option takes precedence next
        if (expInstallLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
            if (checkSd(pkgLen)) {
               return INSTALL_LOC_SD;
            }
            if (checkInt(pkgLen)) {
                return INSTALL_LOC_INT;
            }
            return INSTALL_LOC_ERR;
        }
        if (expInstallLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
            if (checkInt(pkgLen)) {
                return INSTALL_LOC_INT;
            }
            return INSTALL_LOC_ERR;
        }
        if (expInstallLocation == PackageInfo.INSTALL_LOCATION_AUTO) {
            // Check for free memory internally
            if (checkInt(pkgLen)) {
                return INSTALL_LOC_INT;
            }
            // Check for free memory externally
            if (checkSd(pkgLen)) {
                return INSTALL_LOC_SD;
            }
            return INSTALL_LOC_ERR;
        }
        // Check for settings preference.
        boolean checkSd = false;
        int userPref = getDefaultInstallLoc();
        if (userPref == APP_INSTALL_DEVICE) {
            if (checkInt(pkgLen)) {
                return INSTALL_LOC_INT;
            }
            return INSTALL_LOC_ERR;
        } else if (userPref == APP_INSTALL_SDCARD) {
            if (checkSd(pkgLen)) {
                return INSTALL_LOC_SD;
            }
            return INSTALL_LOC_ERR;
        }
        // Default system policy for apps with no manifest option specified.
        // Check for free memory internally
        if (checkInt(pkgLen)) {
            return INSTALL_LOC_INT;
        }
        return INSTALL_LOC_ERR;
    }

    private void assertInstall(PackageParser.Package pkg, int flags, int expInstallLocation) {
        try {
            String pkgName = pkg.packageName;
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
            long pkgLen = new File(info.sourceDir).length();

            if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) {
                assertTrue((info.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
                assertEquals(srcPath, drmInstallPath);
                assertEquals(publicSrcPath, appInstallPath);
                assertTrue(info.nativeLibraryDir.startsWith(dataDir.getPath()));
            } else {
                assertFalse((info.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
                int rLoc = getInstallLoc(flags, expInstallLocation, pkgLen);
                if (rLoc == INSTALL_LOC_INT) {
                    assertEquals(srcPath, appInstallPath);
                    assertEquals(publicSrcPath, appInstallPath);
                    assertFalse((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                    assertTrue(info.nativeLibraryDir.startsWith(dataDir.getPath()));

                    // Make sure the native library dir is not a symlink
                    final File nativeLibDir = new File(info.nativeLibraryDir);
                    assertTrue("Native library dir should exist at " + info.nativeLibraryDir,
                            nativeLibDir.exists());
                    try {
                        assertEquals("Native library dir should not be a symlink",
                                info.nativeLibraryDir,
                                nativeLibDir.getCanonicalPath());
                    } catch (IOException e) {
                        fail("Can't read " + nativeLibDir.getPath());
                    }
                } else if (rLoc == INSTALL_LOC_SD){
                    assertTrue("Application flags (" + info.flags
                            + ") should contain FLAG_EXTERNAL_STORAGE",
                            (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                    assertTrue("The APK path (" + srcPath + ") should start with "
                            + SECURE_CONTAINERS_PREFIX, srcPath
                            .startsWith(SECURE_CONTAINERS_PREFIX));
                    assertTrue("The public APK path (" + publicSrcPath + ") should start with "
                            + SECURE_CONTAINERS_PREFIX, publicSrcPath
                            .startsWith(SECURE_CONTAINERS_PREFIX));
                    assertTrue("The native library path (" + info.nativeLibraryDir
                            + ") should start with " + SECURE_CONTAINERS_PREFIX,
                            info.nativeLibraryDir.startsWith(SECURE_CONTAINERS_PREFIX));

                    // Make sure the native library in /data/data/<app>/lib is a
                    // symlink to the ASEC
                    final File nativeLibSymLink = new File(info.dataDir, "lib");
                    assertTrue("Native library symlink should exist at " + nativeLibSymLink.getPath(),
                            nativeLibSymLink.exists());
                    try {
                        assertEquals(nativeLibSymLink.getPath() + " should be a symlink to "
                                + info.nativeLibraryDir, info.nativeLibraryDir, nativeLibSymLink
                                .getCanonicalPath());
                    } catch (IOException e) {
                        fail("Can't read " + nativeLibSymLink.getPath());
                    }
                } else {
                    // TODO handle error. Install should have failed.
                    fail("Install should have failed");
                }
            }
        } catch (NameNotFoundException e) {
            failStr("failed with exception : " + e);
        }
    }

    private void assertNotInstalled(String pkgName) {
        try {
            ApplicationInfo info = getPm().getApplicationInfo(pkgName, 0);
            fail(pkgName + " shouldnt be installed");
        } catch (NameNotFoundException e) {
        }
    }

    class InstallParams {
        Uri packageURI;
        PackageParser.Package pkg;
        InstallParams(String outFileName, int rawResId) {
            this.pkg = getParsedPackage(outFileName, rawResId);
            this.packageURI = Uri.fromFile(new File(pkg.mScanPath));
        }
        InstallParams(PackageParser.Package pkg) {
            this.packageURI = Uri.fromFile(new File(pkg.mScanPath));
            this.pkg = pkg;
        }
        long getApkSize() {
            File file = new File(pkg.mScanPath);
            return file.length();
        }
    }

    private InstallParams sampleInstallFromRawResource(int flags, boolean cleanUp) {
        return installFromRawResource("install.apk", R.raw.install, flags, cleanUp,
                false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    static final String PERM_PACKAGE = "package";
    static final String PERM_DEFINED = "defined";
    static final String PERM_UNDEFINED = "undefined";
    static final String PERM_USED = "used";
    static final String PERM_NOTUSED = "notused";

    private void assertPermissions(String[] cmds) {
        final PackageManager pm = getPm();
        String pkg = null;
        PackageInfo pkgInfo = null;
        String mode = PERM_DEFINED;
        int i = 0;
        while (i < cmds.length) {
            String cmd = cmds[i++];
            if (cmd == PERM_PACKAGE) {
                pkg = cmds[i++];
                try {
                    pkgInfo = pm.getPackageInfo(pkg,
                            PackageManager.GET_PERMISSIONS
                            | PackageManager.GET_UNINSTALLED_PACKAGES);
                } catch (NameNotFoundException e) {
                    pkgInfo = null;
                }
            } else if (cmd == PERM_DEFINED || cmd == PERM_UNDEFINED
                    || cmd == PERM_USED || cmd == PERM_NOTUSED) {
                mode = cmds[i++];
            } else {
                if (mode == PERM_DEFINED) {
                    try {
                        PermissionInfo pi = pm.getPermissionInfo(cmd, 0);
                        assertNotNull(pi);
                        assertEquals(pi.packageName, pkg);
                        assertEquals(pi.name, cmd);
                        assertNotNull(pkgInfo);
                        boolean found = false;
                        for (int j=0; j<pkgInfo.permissions.length && !found; j++) {
                            if (pkgInfo.permissions[j].name.equals(cmd)) {
                                found = true;
                            }
                        }
                        if (!found) {
                            fail("Permission not found: " + cmd);
                        }
                    } catch (NameNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                } else if (mode == PERM_UNDEFINED) {
                    try {
                        pm.getPermissionInfo(cmd, 0);
                        throw new RuntimeException("Permission exists: " + cmd);
                    } catch (NameNotFoundException e) {
                    }
                    if (pkgInfo != null) {
                        boolean found = false;
                        for (int j=0; j<pkgInfo.permissions.length && !found; j++) {
                            if (pkgInfo.permissions[j].name.equals(cmd)) {
                                found = true;
                            }
                        }
                        if (found) {
                            fail("Permission still exists: " + cmd);
                        }
                    }
                } else if (mode == PERM_USED || mode == PERM_NOTUSED) {
                    boolean found = false;
                    for (int j=0; j<pkgInfo.requestedPermissions.length && !found; j++) {
                        if (pkgInfo.requestedPermissions[j].equals(cmd)) {
                            found = true;
                        }
                    }
                    if (!found) {
                        fail("Permission not requested: " + cmd);
                    }
                    if (mode == PERM_USED) {
                        if (pm.checkPermission(cmd, pkg)
                                != PackageManager.PERMISSION_GRANTED) {
                            fail("Permission not granted: " + cmd);
                        }
                    } else {
                        if (pm.checkPermission(cmd, pkg)
                                != PackageManager.PERMISSION_DENIED) {
                            fail("Permission granted: " + cmd);
                        }
                    }
                }
            }
        }
    }

    private PackageParser.Package getParsedPackage(String outFileName, int rawResId) {
        PackageManager pm = mContext.getPackageManager();
        File filesDir = mContext.getFilesDir();
        File outFile = new File(filesDir, outFileName);
        Uri packageURI = getInstallablePackage(rawResId, outFile);
        PackageParser.Package pkg = parsePackage(packageURI);
        return pkg;
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install it.
     */
    private void installFromRawResource(InstallParams ip,
            int flags, boolean cleanUp, boolean fail, int result,
            int expInstallLocation) {
        PackageManager pm = mContext.getPackageManager();
        PackageParser.Package pkg = ip.pkg;
        Uri packageURI = ip.packageURI;
        if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) == 0) {
            // Make sure the package doesn't exist
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg.packageName,
                        PackageManager.GET_UNINSTALLED_PACKAGES);
                GenericReceiver receiver = new DeleteReceiver(pkg.packageName);
                invokeDeletePackage(pkg.packageName, 0, receiver);
            } catch (NameNotFoundException e1) {
            } catch (Exception e) {
                failStr(e);
            }
        }
        try {
            if (fail) {
                invokeInstallPackageFail(packageURI, flags, result);
                if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) == 0) {
                    assertNotInstalled(pkg.packageName);
                }
            } else {
                InstallReceiver receiver = new InstallReceiver(pkg.packageName);
                assertTrue(invokeInstallPackage(packageURI, flags, receiver));
                // Verify installed information
                assertInstall(pkg, flags, expInstallLocation);
            }
        } finally {
            if (cleanUp) {
                cleanUpInstall(ip);
            }
        }
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install it.
     */
    private InstallParams installFromRawResource(String outFileName,
            int rawResId, int flags, boolean cleanUp, boolean fail, int result,
            int expInstallLocation) {
        InstallParams ip = new InstallParams(outFileName, rawResId);
        installFromRawResource(ip, flags, cleanUp, fail, result, expInstallLocation);
        return ip;
    }

    @LargeTest
    public void testInstallNormalInternal() {
        sampleInstallFromRawResource(0, true);
    }

    @LargeTest
    public void testInstallFwdLockedInternal() {
        sampleInstallFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, true);
    }

    @LargeTest
    public void testInstallSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        mountMedia();
        sampleInstallFromRawResource(PackageManager.INSTALL_EXTERNAL, true);
    }

    /* ------------------------- Test replacing packages --------------*/
    class ReplaceReceiver extends GenericReceiver {
        String pkgName;
        final static int INVALID = -1;
        final static int REMOVED = 1;
        final static int ADDED = 2;
        final static int REPLACED = 3;
        int removed = INVALID;
        // for updated system apps only
        boolean update = false;

        ReplaceReceiver(String pkgName) {
            this.pkgName = pkgName;
            filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            if (update) {
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            }
            filter.addDataScheme("package");
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            Uri data = intent.getData();
            String installedPkg = data.getEncodedSchemeSpecificPart();
            if (pkgName == null || !pkgName.equals(installedPkg)) {
                return false;
            }
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                removed = REMOVED;
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                if (removed != REMOVED) {
                    return false;
                }
                boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (!replacing) {
                    return false;
                }
                removed = ADDED;
                if (!update) {
                    return true;
                }
            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                if (removed != ADDED) {
                    return false;
                }
                removed = REPLACED;
                return true;
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
    private void sampleReplaceFromRawResource(int flags) {
        InstallParams ip = sampleInstallFromRawResource(flags, false);
        boolean replace = ((flags & PackageManager.INSTALL_REPLACE_EXISTING) != 0);
        Log.i(TAG, "replace=" + replace);
        GenericReceiver receiver;
        if (replace) {
            receiver = new ReplaceReceiver(ip.pkg.packageName);
            Log.i(TAG, "Creating replaceReceiver");
        } else {
            receiver = new InstallReceiver(ip.pkg.packageName);
        }
        try {
            try {
                assertEquals(invokeInstallPackage(ip.packageURI, flags, receiver), replace);
                if (replace) {
                    assertInstall(ip.pkg, flags, ip.pkg.installLocation);
                }
            } catch (Exception e) {
                failStr("Failed with exception : " + e);
            }
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testReplaceFailNormalInternal() {
        sampleReplaceFromRawResource(0);
    }

    @LargeTest
    public void testReplaceFailFwdLockedInternal() {
        sampleReplaceFromRawResource(PackageManager.INSTALL_FORWARD_LOCK);
    }

    @LargeTest
    public void testReplaceFailSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        sampleReplaceFromRawResource(PackageManager.INSTALL_EXTERNAL);
    }

    @LargeTest
    public void testReplaceNormalInternal() {
        sampleReplaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING);
    }

    @LargeTest
    public void testReplaceFwdLockedInternal() {
        sampleReplaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING |
                PackageManager.INSTALL_FORWARD_LOCK);
    }

    @LargeTest
    public void testReplaceSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        sampleReplaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING |
                PackageManager.INSTALL_EXTERNAL);
    }

    /* -------------- Delete tests ---*/
    class DeleteObserver extends IPackageDeleteObserver.Stub {

        public boolean succeeded;
        private boolean doneFlag = false;

        public boolean isDone() {
            return doneFlag;
        }

        public void packageDeleted(String packageName, int returnCode) throws RemoteException {
            synchronized(this) {
                this.succeeded = returnCode == PackageManager.DELETE_SUCCEEDED;
                doneFlag = true;
                notifyAll();
            }
        }
    }

    class DeleteReceiver extends GenericReceiver {
        String pkgName;

        DeleteReceiver(String pkgName) {
            this.pkgName = pkgName;
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
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

    public boolean invokeDeletePackage(final String pkgName, int flags,
            GenericReceiver receiver) throws Exception {
        DeleteObserver observer = new DeleteObserver();
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        try {
            // Wait on observer
            synchronized(observer) {
                synchronized (receiver) {
                    getPm().deletePackage(pkgName, observer, flags);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!observer.isDone()) {
                        throw new Exception("Timed out waiting for packageInstalled callback");
                    }
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!receiver.isDone()) {
                        throw new Exception("Timed out waiting for PACKAGE_REMOVED notification");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    public void deleteFromRawResource(int iFlags, int dFlags) {
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        boolean retainData = ((dFlags & PackageManager.DONT_DELETE_DATA) != 0);
        GenericReceiver receiver = new DeleteReceiver(ip.pkg.packageName);
        DeleteObserver observer = new DeleteObserver();
        try {
            assertTrue(invokeDeletePackage(ip.pkg.packageName, dFlags, receiver));
            ApplicationInfo info = null;
            Log.i(TAG, "okay4");
            try {
            info = getPm().getApplicationInfo(ip.pkg.packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES);
            } catch (NameNotFoundException e) {
                info = null;
            }
            if (retainData) {
                assertNotNull(info);
                assertEquals(info.packageName, ip.pkg.packageName);
                File file = new File(info.dataDir);
                assertTrue(file.exists());
            } else {
                assertNull(info);
            }
        } catch (Exception e) {
            failStr(e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testDeleteNormalInternal() {
        deleteFromRawResource(0, 0);
    }

    @LargeTest
    public void testDeleteFwdLockedInternal() {
        deleteFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, 0);
    }

    @LargeTest
    public void testDeleteSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        deleteFromRawResource(PackageManager.INSTALL_EXTERNAL, 0);
    }

    @LargeTest
    public void testDeleteNormalInternalRetainData() {
        deleteFromRawResource(0, PackageManager.DONT_DELETE_DATA);
    }

    @LargeTest
    public void testDeleteFwdLockedInternalRetainData() {
        deleteFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, PackageManager.DONT_DELETE_DATA);
    }

    @LargeTest
    public void testDeleteSdcardRetainData() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        deleteFromRawResource(PackageManager.INSTALL_EXTERNAL, PackageManager.DONT_DELETE_DATA);
    }

    /* sdcard mount/unmount tests ******/

    class SdMountReceiver extends GenericReceiver {
        String pkgNames[];
        boolean status = true;

        SdMountReceiver(String[] pkgNames) {
            this.pkgNames = pkgNames;
            IntentFilter filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            Log.i(TAG, "okay 1");
            String action = intent.getAction();
            if (!Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                return false;
            }
            String rpkgList[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            for (String pkg : pkgNames) {
                boolean found = false;
                for (String rpkg : rpkgList) {
                    if (rpkg.equals(pkg)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    status = false;
                    return true;
                }
            }
            return true;
        }
    }

    class SdUnMountReceiver extends GenericReceiver {
        String pkgNames[];
        boolean status = true;

        SdUnMountReceiver(String[] pkgNames) {
            this.pkgNames = pkgNames;
            IntentFilter filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                return false;
            }
            String rpkgList[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            for (String pkg : pkgNames) {
                boolean found = false;
                for (String rpkg : rpkgList) {
                    if (rpkg.equals(pkg)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    status = false;
                    return true;
                }
            }
            return true;
        }
    }

    IMountService getMs() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get mount service");
        }
        return null;
    }

    boolean checkMediaState(String desired) {
        try {
            String mPath = Environment.getExternalStorageDirectory().getPath();
            String actual = getMs().getVolumeState(mPath);
            if (desired.equals(actual)) {
                return true;
            } else {
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while checking media state", e);
            return false;
        }
    }

    boolean mountMedia() {
        // We can't mount emulated storage.
        if (Environment.isExternalStorageEmulated()) {
            return true;
        }

        if (checkMediaState(Environment.MEDIA_MOUNTED)) {
            return true;
        }

        final String path = Environment.getExternalStorageDirectory().toString();
        StorageListener observer = new StorageListener(Environment.MEDIA_MOUNTED);
        StorageManager sm = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        sm.registerListener(observer);
        try {
            // Wait on observer
            synchronized (observer) {
                int ret = getMs().mountVolume(path);
                if (ret != StorageResultCode.OperationSucceeded) {
                    throw new Exception("Could not mount the media");
                }
                long waitTime = 0;
                while ((!observer.isDone()) && (waitTime < MAX_WAIT_TIME)) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if (!observer.isDone()) {
                    throw new Exception("Timed out waiting for unmount media notification");
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception : " + e);
            return false;
        } finally {
            sm.unregisterListener(observer);
        }
    }

    private boolean unmountMedia() {
        // We can't unmount emulated storage.
        if (Environment.isExternalStorageEmulated()) {
            return true;
        }

        if (checkMediaState(Environment.MEDIA_UNMOUNTED)) {
            return true;
        }

        final String path = Environment.getExternalStorageDirectory().getPath();
        StorageListener observer = new StorageListener(Environment.MEDIA_UNMOUNTED);
        StorageManager sm = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        sm.registerListener(observer);
        try {
            // Wait on observer
            synchronized(observer) {
                getMs().unmountVolume(path, true, false);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    throw new Exception("Timed out waiting for unmount media notification");
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception : " + e);
            return false;
        } finally {
            sm.unregisterListener(observer);
        }
    }

    private boolean mountFromRawResource() {
        // Install pkg on sdcard
        InstallParams ip = sampleInstallFromRawResource(PackageManager.INSTALL_EXTERNAL, false);
        if (localLOGV) Log.i(TAG, "Installed pkg on sdcard");
        boolean origState = checkMediaState(Environment.MEDIA_MOUNTED);
        boolean registeredReceiver = false;
        SdMountReceiver receiver = new SdMountReceiver(new String[]{ip.pkg.packageName});
        try {
            if (localLOGV) Log.i(TAG, "Unmounting media");
            // Unmount media
            assertTrue(unmountMedia());
            if (localLOGV) Log.i(TAG, "Unmounted media");
            // Register receiver here
            PackageManager pm = getPm();
            mContext.registerReceiver(receiver, receiver.filter);
            registeredReceiver = true;

            // Wait on receiver
            synchronized (receiver) {
                if (localLOGV) Log.i(TAG, "Mounting media");
                // Mount media again
                assertTrue(mountMedia());
                if (localLOGV) Log.i(TAG, "Mounted media");
                if (localLOGV) Log.i(TAG, "Waiting for notification");
                long waitTime = 0;
                // Verify we received the broadcast
                waitTime = 0;
                while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    receiver.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!receiver.isDone()) {
                    failStr("Timed out waiting for EXTERNAL_APPLICATIONS notification");
                }
                return receiver.received;
            }
        } catch (InterruptedException e) {
            failStr(e);
            return false;
        } finally {
            if (registeredReceiver) mContext.unregisterReceiver(receiver);
            // Restore original media state
            if (origState) {
                mountMedia();
            } else {
                unmountMedia();
            }
            if (localLOGV) Log.i(TAG, "Cleaning up install");
            cleanUpInstall(ip);
        }
    }

    /*
     * Install package on sdcard. Unmount and then mount the media.
     * (Use PackageManagerService private api for now)
     * Make sure the installed package is available.
     */
    @LargeTest
    public void testMountSdNormalInternal() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertTrue(mountFromRawResource());
    }

    void cleanUpInstall(InstallParams ip) {
        if (ip == null) {
            return;
        }
        Runtime.getRuntime().gc();
        Log.i(TAG, "Deleting package : " + ip.pkg.packageName);
        getPm().deletePackage(ip.pkg.packageName, null, 0);
        File outFile = new File(ip.pkg.mScanPath);
        if (outFile != null && outFile.exists()) {
            outFile.delete();
        }
    }
    void cleanUpInstall(String pkgName) {
        if (pkgName == null) {
            return;
        }
        Log.i(TAG, "Deleting package : " + pkgName);
        try {
            ApplicationInfo info = getPm().getApplicationInfo(pkgName,
                    PackageManager.GET_UNINSTALLED_PACKAGES);
            if (info != null) {
                getPm().deletePackage(pkgName, null, 0);
            }
        } catch (NameNotFoundException e) {}
    }

    @LargeTest
    public void testManifestInstallLocationInternal() {
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testManifestInstallLocationSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testManifestInstallLocationAuto() {
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_AUTO);
    }

    @LargeTest
    public void testManifestInstallLocationUnspecified() {
        installFromRawResource("install.apk", R.raw.install_loc_unspecified,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testManifestInstallLocationFwdLockedFlagSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_unspecified,
                PackageManager.INSTALL_FORWARD_LOCK |
                PackageManager.INSTALL_EXTERNAL, true, true,
                PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testManifestInstallLocationFwdLockedSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                PackageManager.INSTALL_FORWARD_LOCK, true, false,
                -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /*
     * Install a package on internal flash via PackageManager install flag. Replace
     * the package via flag to install on sdcard. Make sure the new flag overrides
     * the old install location.
     */
    @LargeTest
    public void testReplaceFlagInternalSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = 0;
        int rFlags = PackageManager.INSTALL_EXTERNAL;
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.packageName);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            assertEquals(invokeInstallPackage(ip.packageURI, replaceFlags, receiver), true);
            assertInstall(ip.pkg, rFlags, ip.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    /*
     * Install a package on sdcard via PackageManager install flag. Replace
     * the package with no flags or manifest option and make sure the old
     * install location is retained.
     */
    @LargeTest
    public void testReplaceFlagSdcardInternal() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = 0;
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.packageName);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            assertEquals(invokeInstallPackage(ip.packageURI, replaceFlags, receiver), true);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testManifestInstallLocationReplaceInternalSdcard() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = 0;
        int iApk = R.raw.install_loc_internal;
        int rFlags = 0;
        int rApk = R.raw.install_loc_sdcard;
        InstallParams ip = installFromRawResource("install.apk", iApk,
                iFlags, false,
                false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.packageName);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            InstallParams rp = installFromRawResource("install.apk", rApk,
                    replaceFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
            assertInstall(rp.pkg, replaceFlags, rp.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testManifestInstallLocationReplaceSdcardInternal() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = 0;
        int iApk = R.raw.install_loc_sdcard;
        int rFlags = 0;
        int rApk = R.raw.install_loc_unspecified;
        InstallParams ip = installFromRawResource("install.apk", iApk,
                iFlags, false,
                false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            InstallParams rp = installFromRawResource("install.apk", rApk,
                    replaceFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
            assertInstall(rp.pkg, replaceFlags, ip.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    class MoveReceiver extends GenericReceiver {
        String pkgName;
        final static int INVALID = -1;
        final static int REMOVED = 1;
        final static int ADDED = 2;
        int removed = INVALID;

        MoveReceiver(String pkgName) {
            this.pkgName = pkgName;
            filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "MoveReceiver::" + action);
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                String[] list = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (list != null) {
                    for (String pkg : list) {
                        if (pkg.equals(pkgName)) {
                            removed = REMOVED;
                            break;
                        }
                    }
                }
                removed = REMOVED;
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                if (removed != REMOVED) {
                    return false;
                }
                String[] list = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (list != null) {
                    for (String pkg : list) {
                        if (pkg.equals(pkgName)) {
                            removed = ADDED;
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private class PackageMoveObserver extends IPackageMoveObserver.Stub {
        public int returnCode;
        private boolean doneFlag = false;
        public String packageName;
        public PackageMoveObserver(String pkgName) {
            packageName = pkgName;
        }
        public void packageMoved(String packageName, int returnCode) {
            Log.i("DEBUG_MOVE::", "pkg = " + packageName + ", " + "ret = " + returnCode);
            if (!packageName.equals(this.packageName)) {
                return;
            }
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

    public boolean invokeMovePackage(String pkgName, int flags,
            GenericReceiver receiver) throws Exception {
        PackageMoveObserver observer = new PackageMoveObserver(pkgName);
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        try {
            // Wait on observer
            synchronized(observer) {
                synchronized (receiver) {
                    getPm().movePackage(pkgName, observer, flags);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!observer.isDone()) {
                        throw new Exception("Timed out waiting for pkgmove callback");
                    }
                    if (observer.returnCode != PackageManager.MOVE_SUCCEEDED) {
                        return false;
                    }
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!receiver.isDone()) {
                        throw new Exception("Timed out waiting for MOVE notifications");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }
    private boolean invokeMovePackageFail(String pkgName, int flags, int errCode) throws Exception {
        PackageMoveObserver observer = new PackageMoveObserver(pkgName);
        try {
            // Wait on observer
            synchronized(observer) {
                getPm().movePackage(pkgName, observer, flags);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    throw new Exception("Timed out waiting for pkgmove callback");
                }
                assertEquals(errCode, observer.returnCode);
            }
        } finally {
        }
        return true;
    }

    private int getDefaultInstallLoc() {
        int origDefaultLoc = PackageInfo.INSTALL_LOCATION_AUTO;
        try {
            origDefaultLoc = Settings.System.getInt(mContext.getContentResolver(), Settings.Secure.DEFAULT_INSTALL_LOCATION);
        } catch (SettingNotFoundException e1) {
        }
        return origDefaultLoc;
    }

    private void setInstallLoc(int loc) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.Secure.DEFAULT_INSTALL_LOCATION, loc);
    }
    /*
     * Tests for moving apps between internal and external storage
     */
    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install first and then replace it
     * again.
     */

    private void moveFromRawResource(String outFileName,
            int rawResId, int installFlags, int moveFlags, boolean cleanUp,
            boolean fail, int result) {
        int origDefaultLoc = getDefaultInstallLoc();
        InstallParams ip = null;
        try {
            setInstallLoc(PackageHelper.APP_INSTALL_AUTO);
            // Install first
            ip = installFromRawResource("install.apk", rawResId, installFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            ApplicationInfo oldAppInfo = getPm().getApplicationInfo(ip.pkg.packageName, 0);
            if (fail) {
                assertTrue(invokeMovePackageFail(ip.pkg.packageName, moveFlags, result));
                ApplicationInfo info = getPm().getApplicationInfo(ip.pkg.packageName, 0);
                assertNotNull(info);
                assertEquals(oldAppInfo.flags, info.flags);
            } else {
                // Create receiver based on expRetCode
                MoveReceiver receiver = new MoveReceiver(ip.pkg.packageName);
                boolean retCode = invokeMovePackage(ip.pkg.packageName, moveFlags,
                        receiver);
                assertTrue(retCode);
                ApplicationInfo info = getPm().getApplicationInfo(ip.pkg.packageName, 0);
                assertNotNull("ApplicationInfo for recently installed application should exist",
                        info);
                if ((moveFlags & PackageManager.MOVE_INTERNAL) != 0) {
                    assertTrue("ApplicationInfo.FLAG_EXTERNAL_STORAGE flag should NOT be set",
                            (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0);
                    assertTrue("ApplicationInfo.nativeLibraryDir should start with " + info.dataDir,
                            info.nativeLibraryDir.startsWith(info.dataDir));
                } else if ((moveFlags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0){
                    assertTrue("ApplicationInfo.FLAG_EXTERNAL_STORAGE flag should be set",
                            (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                    assertTrue("ApplicationInfo.nativeLibraryDir should start with " + SECURE_CONTAINERS_PREFIX,
                            info.nativeLibraryDir.startsWith(SECURE_CONTAINERS_PREFIX));
                    final File nativeLibSymLink = new File(info.dataDir, "lib");
                    assertTrue("The data directory should have a 'lib' symlink that points to the ASEC container",
                            nativeLibSymLink.getCanonicalPath().startsWith(SECURE_CONTAINERS_PREFIX));
                }
            }
        } catch (NameNotFoundException e) {
            failStr("Pkg hasnt been installed correctly");
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            if (ip != null) {
                cleanUpInstall(ip);
            }
            // Restore default install location
            setInstallLoc(origDefaultLoc);
        }
    }
    private void sampleMoveFromRawResource(int installFlags, int moveFlags, boolean fail,
            int result) {
        moveFromRawResource("install.apk",
                R.raw.install, installFlags, moveFlags, true,
                fail, result);
    }

    @LargeTest
    public void testMoveAppInternalToExternal() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int installFlags = PackageManager.INSTALL_INTERNAL;
        int moveFlags = PackageManager.MOVE_EXTERNAL_MEDIA;
        boolean fail = false;
        int result = PackageManager.MOVE_SUCCEEDED;
        sampleMoveFromRawResource(installFlags, moveFlags, fail, result);
    }

    @LargeTest
    public void testMoveAppInternalToInternal() {
        int installFlags = PackageManager.INSTALL_INTERNAL;
        int moveFlags = PackageManager.MOVE_INTERNAL;
        boolean fail = true;
        int result = PackageManager.MOVE_FAILED_INVALID_LOCATION;
        sampleMoveFromRawResource(installFlags, moveFlags, fail, result);
    }

    @LargeTest
    public void testMoveAppExternalToExternal() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int installFlags = PackageManager.INSTALL_EXTERNAL;
        int moveFlags = PackageManager.MOVE_EXTERNAL_MEDIA;
        boolean fail = true;
        int result = PackageManager.MOVE_FAILED_INVALID_LOCATION;
        sampleMoveFromRawResource(installFlags, moveFlags, fail, result);
    }

    @LargeTest
    public void testMoveAppExternalToInternal() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int installFlags = PackageManager.INSTALL_EXTERNAL;
        int moveFlags = PackageManager.MOVE_INTERNAL;
        boolean fail = false;
        int result = PackageManager.MOVE_SUCCEEDED;
        sampleMoveFromRawResource(installFlags, moveFlags, fail, result);
    }

    @LargeTest
    public void testMoveAppForwardLocked() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int installFlags = PackageManager.INSTALL_FORWARD_LOCK;
        int moveFlags = PackageManager.MOVE_EXTERNAL_MEDIA;
        boolean fail = true;
        int result = PackageManager.MOVE_FAILED_FORWARD_LOCKED;
        sampleMoveFromRawResource(installFlags, moveFlags, fail, result);
    }

    @LargeTest
    public void testMoveAppFailInternalToExternalDelete() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int installFlags = 0;
        int moveFlags = PackageManager.MOVE_EXTERNAL_MEDIA;
        boolean fail = true;
        final int result = PackageManager.MOVE_FAILED_DOESNT_EXIST;

        int rawResId = R.raw.install;
        int origDefaultLoc = getDefaultInstallLoc();
        InstallParams ip = null;
        try {
            PackageManager pm = getPm();
            setInstallLoc(PackageHelper.APP_INSTALL_AUTO);
            // Install first
            ip = installFromRawResource("install.apk", R.raw.install, installFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            // Delete the package now retaining data.
            GenericReceiver receiver = new DeleteReceiver(ip.pkg.packageName);
            invokeDeletePackage(ip.pkg.packageName, PackageManager.DONT_DELETE_DATA, receiver);
            assertTrue(invokeMovePackageFail(ip.pkg.packageName, moveFlags, result));
        } catch (Exception e) {
            failStr(e);
        } finally {
            if (ip != null) {
                cleanUpInstall(ip);
            }
            // Restore default install location
            setInstallLoc(origDefaultLoc);
        }
    }

    /*
     * Test that an install error code is returned when media is unmounted
     * and package installed on sdcard via package manager flag.
     */
    @LargeTest
    public void testInstallSdcardUnmount() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean origState = checkMediaState(Environment.MEDIA_MOUNTED);
        try {
            // Unmount sdcard
            assertTrue(unmountMedia());
            // Try to install and make sure an error code is returned.
            installFromRawResource("install.apk", R.raw.install,
                    PackageManager.INSTALL_EXTERNAL, false,
                    true, PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    PackageInfo.INSTALL_LOCATION_AUTO);
        } finally {
            // Restore original media state
            if (origState) {
                mountMedia();
            } else {
                unmountMedia();
            }
        }
    }

    /*
     * Unmount sdcard. Try installing an app with manifest option to install
     * on sdcard. Make sure it gets installed on internal flash.
     */
    @LargeTest
    public void testInstallManifestSdcardUnmount() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean origState = checkMediaState(Environment.MEDIA_MOUNTED);
        try {
            // Unmount sdcard
            assertTrue(unmountMedia());
            InstallParams ip = new InstallParams("install.apk", R.raw.install_loc_sdcard);
            installFromRawResource(ip, 0, true, false, -1,
                    PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        } finally {
            // Restore original media state
            if (origState) {
                mountMedia();
            } else {
                unmountMedia();
            }
        }
    }

   /*---------- Recommended install location tests ----*/
   /* Precedence: FlagManifestExistingUser
    * PrecedenceSuffixes:
    * Flag : FlagI, FlagE, FlagF
    * I - internal, E - external, F - forward locked, Flag suffix absent if not using any option.
    * Manifest: ManifestI, ManifestE, ManifestA, Manifest suffix absent if not using any option.
    * Existing: Existing suffix absent if not existing.
    * User: UserI, UserE, UserA, User suffix absent if not existing.
    *
    */

    /*
     * Install an app on internal flash
     */
    @LargeTest
    public void testFlagI() {
        sampleInstallFromRawResource(PackageManager.INSTALL_INTERNAL, true);
    }

    /*
     * Install an app on sdcard.
     */
    @LargeTest
    public void testFlagE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        sampleInstallFromRawResource(PackageManager.INSTALL_EXTERNAL, true);
    }

    /*
     * Install an app forward-locked.
     */
    @LargeTest
    public void testFlagF() {
        sampleInstallFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, true);
    }

    /*
     * Install an app with both internal and external flags set. should fail
     */
    @LargeTest
    public void testFlagIE() {
        installFromRawResource("install.apk", R.raw.install,
                PackageManager.INSTALL_EXTERNAL | PackageManager.INSTALL_INTERNAL,
                false,
                true, PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                PackageInfo.INSTALL_LOCATION_AUTO);
    }

    /*
     * Install an app with both internal and forward-lock flags set.
     */
    @LargeTest
    public void testFlagIF() {
        sampleInstallFromRawResource(PackageManager.INSTALL_FORWARD_LOCK
                | PackageManager.INSTALL_INTERNAL, true);
    }

    /*
     * Install an app with both external and forward-lock flags set. should fail
     */
    @LargeTest
    public void testFlagEF() {
        installFromRawResource("install.apk", R.raw.install,
                PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_EXTERNAL,
                false,
                true, PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                PackageInfo.INSTALL_LOCATION_AUTO);
    }

    /*
     * Install an app with both internal and external flags set with forward
     * lock. Should fail.
     */
    @LargeTest
    public void testFlagIEF() {
        installFromRawResource("install.apk", R.raw.install,
                PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_INTERNAL |
                PackageManager.INSTALL_EXTERNAL,
                false,
                true, PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                PackageInfo.INSTALL_LOCATION_AUTO);
    }

   /*
    * Install an app with both internal and manifest option set.
    * should install on internal.
    */
   @LargeTest
   public void testFlagIManifestI() {
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               PackageManager.INSTALL_INTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * Install an app with both internal and manifest preference for
    * preferExternal. Should install on internal.
    */
   @LargeTest
   public void testFlagIManifestE() {
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               PackageManager.INSTALL_INTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * Install an app with both internal and manifest preference for
    * auto. should install internal.
    */
   @LargeTest
   public void testFlagIManifestA() {
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               PackageManager.INSTALL_INTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * Install an app with both external and manifest option set.
    * should install externally.
    */
   @LargeTest
   public void testFlagEManifestI() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

       installFromRawResource("install.apk", R.raw.install_loc_internal,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }

   /*
    * Install an app with both external and manifest preference for
    * preferExternal. Should install externally.
    */
   @LargeTest
   public void testFlagEManifestE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                PackageManager.INSTALL_EXTERNAL,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /*
     * Install an app with both external and manifest preference for
     * auto. should install on external media.
     */
    @LargeTest
    public void testFlagEManifestA() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_auto,
                PackageManager.INSTALL_EXTERNAL,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /*
     * Install an app with fwd locked flag set and install location set to
     * internal. should install internally.
     */
    @LargeTest
    public void testFlagFManifestI() {
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                PackageManager.INSTALL_FORWARD_LOCK,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /*
     * Install an app with fwd locked flag set and install location set to
     * preferExternal. should install internally.
     */
    @LargeTest
    public void testFlagFManifestE() {
        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                PackageManager.INSTALL_FORWARD_LOCK,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /*
     * Install an app with fwd locked flag set and install location set to
     * auto. should install internally.
     */
    @LargeTest
    public void testFlagFManifestA() {
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                PackageManager.INSTALL_FORWARD_LOCK,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /* The following test functions verify install location for existing apps.
     * ie existing app can be installed internally or externally. If install
     * flag is explicitly set it should override current location. If manifest location
     * is set, that should over ride current location too. if not the existing install
     * location should be honoured.
     * testFlagI/E/F/ExistingI/E -
     */
    @LargeTest
    public void testFlagIExistingI() {
        int iFlags = PackageManager.INSTALL_INTERNAL;
        int rFlags = PackageManager.INSTALL_INTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install,
                rFlags,
                true,
                false, -1,
                -1);
    }

    @LargeTest
    public void testFlagIExistingE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = PackageManager.INSTALL_INTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install,
                rFlags,
                true,
                false, -1,
                -1);
    }

    @LargeTest
    public void testFlagEExistingI() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_INTERNAL;
        int rFlags = PackageManager.INSTALL_EXTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install,
                rFlags,
                true,
                false, -1,
                -1);
    }

    @LargeTest
    public void testFlagEExistingE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = PackageManager.INSTALL_EXTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install,
                rFlags,
                true,
                false, -1,
                -1);
    }

    @LargeTest
    public void testFlagFExistingI() {
        int iFlags = PackageManager.INSTALL_INTERNAL;
        int rFlags = PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install,
                rFlags,
                true,
                false, -1,
                -1);
    }

    @LargeTest
    public void testFlagFExistingE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install,
                rFlags,
                true,
                false, -1,
                -1);
    }

    /*
     * The following set of tests verify the installation of apps with
     * install location attribute set to internalOnly, preferExternal and auto.
     * The manifest option should dictate the install location.
     * public void testManifestI/E/A
     * TODO out of memory fall back behaviour.
     */
    @LargeTest
    public void testManifestI() {
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                0,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testManifestE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                0,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testManifestA() {
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                0,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    /*
     * The following set of tests verify the installation of apps
     * with install location attribute set to internalOnly, preferExternal and auto
     * for already existing apps. The manifest option should take precedence.
     * TODO add out of memory fall back behaviour.
     * testManifestI/E/AExistingI/E
     */
    @LargeTest
    public void testManifestIExistingI() {
        int iFlags = PackageManager.INSTALL_INTERNAL;
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                rFlags,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testManifestIExistingE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                rFlags,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testManifestEExistingI() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_INTERNAL;
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                rFlags,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testManifestEExistingE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                rFlags,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testManifestAExistingI() {
        int iFlags = PackageManager.INSTALL_INTERNAL;
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                rFlags,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_AUTO);
    }

    @LargeTest
    public void testManifestAExistingE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First install.
        installFromRawResource("install.apk", R.raw.install,
                iFlags,
                false,
                false, -1,
                -1);
        // Replace now
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                rFlags,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

   /*
    * The following set of tests check install location for existing
    * application based on user setting.
    */
   private int getExpectedInstallLocation(int userSetting) {
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       boolean enable = getUserSettingSetInstallLocation();
       if (enable) {
           if (userSetting == PackageHelper.APP_INSTALL_AUTO) {
               iloc = PackageInfo.INSTALL_LOCATION_AUTO;
           } else if (userSetting == PackageHelper.APP_INSTALL_EXTERNAL) {
               iloc = PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL;
           } else if (userSetting == PackageHelper.APP_INSTALL_INTERNAL) {
               iloc = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
           }
       }
       return iloc;
   }
   private void setExistingXUserX(int userSetting, int iFlags, int iloc) {
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
       int origSetting = getDefaultInstallLoc();
       try {
           // Set user setting
           setInstallLoc(userSetting);
           // Replace now
           installFromRawResource("install.apk", R.raw.install,
                   rFlags,
                   true,
                   false, -1,
                   iloc);
       } finally {
           setInstallLoc(origSetting);
       }
   }
   @LargeTest
   public void testExistingIUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iFlags = PackageManager.INSTALL_INTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }

    @LargeTest
    public void testExistingIUserE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
        int iFlags = PackageManager.INSTALL_INTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

   @LargeTest
   public void testExistingIUserA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iFlags = PackageManager.INSTALL_INTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }

    @LargeTest
    public void testExistingEUserI() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
        int iFlags = PackageManager.INSTALL_EXTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testExistingEUserE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
        int iFlags = PackageManager.INSTALL_EXTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testExistingEUserA() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = PackageHelper.APP_INSTALL_AUTO;
        int iFlags = PackageManager.INSTALL_EXTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

   /*
    * The following set of tests verify that the user setting defines
    * the install location.
    *
    */
   private boolean getUserSettingSetInstallLocation() {
       try {
           return Settings.System.getInt(mContext.getContentResolver(), Settings.Secure.SET_INSTALL_LOCATION) != 0;

       } catch (SettingNotFoundException e1) {
       }
       return false;
   }

   private void setUserSettingSetInstallLocation(boolean value) {
       Settings.System.putInt(mContext.getContentResolver(),
               Settings.Secure.SET_INSTALL_LOCATION, value ? 1 : 0);
   }
   private void setUserX(boolean enable, int userSetting, int iloc) {
       boolean origUserSetting = getUserSettingSetInstallLocation();
       int origSetting = getDefaultInstallLoc();
       try {
           setUserSettingSetInstallLocation(enable);
           // Set user setting
           setInstallLoc(userSetting);
           // Replace now
           installFromRawResource("install.apk", R.raw.install,
                   0,
                   true,
                   false, -1,
                   iloc);
       } finally {
           // Restore original setting
           setUserSettingSetInstallLocation(origUserSetting);
           setInstallLoc(origSetting);
       }
   }
   @LargeTest
   public void testUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iloc = getExpectedInstallLocation(userSetting);
       setUserX(true, userSetting, iloc);
   }

    @LargeTest
    public void testUserE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
        int iloc = getExpectedInstallLocation(userSetting);
        setUserX(true, userSetting, iloc);
    }

   @LargeTest
   public void testUserA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iloc = getExpectedInstallLocation(userSetting);
       setUserX(true, userSetting, iloc);
   }
   /*
    * The following set of tests turn on/off the basic
    * user setting for turning on install location.
    */
   @LargeTest
   public void testUserPrefOffUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       setUserX(false, userSetting, iloc);
   }

    @LargeTest
    public void testUserPrefOffUserE() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
        int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
        setUserX(false, userSetting, iloc);
    }

   @LargeTest
   public void testUserPrefOffA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       setUserX(false, userSetting, iloc);
   }

    static final String BASE_PERMISSIONS_DEFINED[] = new String[] {
        PERM_PACKAGE, "com.android.unit_tests.install_decl_perm",
        PERM_DEFINED,
        "com.android.frameworks.coretests.NORMAL",
        "com.android.frameworks.coretests.DANGEROUS",
        "com.android.frameworks.coretests.SIGNATURE",
    };

    static final String BASE_PERMISSIONS_UNDEFINED[] = new String[] {
        PERM_PACKAGE, "com.android.frameworks.coretests.install_decl_perm",
        PERM_UNDEFINED,
        "com.android.frameworks.coretests.NORMAL",
        "com.android.frameworks.coretests.DANGEROUS",
        "com.android.frameworks.coretests.SIGNATURE",
    };

    static final String BASE_PERMISSIONS_USED[] = new String[] {
        PERM_PACKAGE, "com.android.frameworks.coretests.install_use_perm_good",
        PERM_USED,
        "com.android.frameworks.coretests.NORMAL",
        "com.android.frameworks.coretests.DANGEROUS",
        "com.android.frameworks.coretests.SIGNATURE",
    };

    static final String BASE_PERMISSIONS_NOTUSED[] = new String[] {
        PERM_PACKAGE, "com.android.frameworks.coretests.install_use_perm_good",
        PERM_NOTUSED,
        "com.android.frameworks.coretests.NORMAL",
        "com.android.frameworks.coretests.DANGEROUS",
        "com.android.frameworks.coretests.SIGNATURE",
    };

    static final String BASE_PERMISSIONS_SIGUSED[] = new String[] {
        PERM_PACKAGE, "com.android.frameworks.coretests.install_use_perm_good",
        PERM_USED,
        "com.android.frameworks.coretests.SIGNATURE",
        PERM_NOTUSED,
        "com.android.frameworks.coretests.NORMAL",
        "com.android.frameworks.coretests.DANGEROUS",
    };

    /*
     * Ensure that permissions are properly declared.
     */
    @LargeTest
    public void testInstallDeclaresPermissions() {
        InstallParams ip = null;
        InstallParams ip2 = null;
        try {
            // **: Upon installing a package, are its declared permissions published?

            int iFlags = PackageManager.INSTALL_INTERNAL;
            int iApk = R.raw.install_decl_perm;
            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);

            // **: Upon installing package, are its permissions granted?

            int i2Flags = PackageManager.INSTALL_INTERNAL;
            int i2Apk = R.raw.install_use_perm_good;
            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_USED);

            // **: Upon removing but not deleting, are permissions retained?

            GenericReceiver receiver = new DeleteReceiver(ip.pkg.packageName);

            try {
                invokeDeletePackage(ip.pkg.packageName, PackageManager.DONT_DELETE_DATA, receiver);
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_USED);

            // **: Upon re-installing, are permissions retained?

            ip = installFromRawResource("install.apk", iApk,
                    iFlags | PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_USED);

            // **: Upon deleting package, are all permissions removed?

            try {
                invokeDeletePackage(ip.pkg.packageName, 0, receiver);
                ip = null;
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_UNDEFINED);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Delete package using permissions; nothing to check here.

            GenericReceiver receiver2 = new DeleteReceiver(ip2.pkg.packageName);
            try {
                invokeDeletePackage(ip2.pkg.packageName, 0, receiver);
                ip2 = null;
            } catch (Exception e) {
                failStr(e);
            }

            // **: Re-install package using permissions; no permissions can be granted.

            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Upon installing declaring package, are sig permissions granted
            // to other apps (but not other perms)?

            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_SIGUSED);

            // **: Re-install package using permissions; are all permissions granted?

            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags | PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Upon deleting package, are all permissions removed?

            try {
                invokeDeletePackage(ip.pkg.packageName, 0, receiver);
                ip = null;
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_UNDEFINED);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Delete package using permissions; nothing to check here.

            try {
                invokeDeletePackage(ip2.pkg.packageName, 0, receiver);
                ip2 = null;
            } catch (Exception e) {
                failStr(e);
            }

        } finally {
            if (ip2 != null) {
                cleanUpInstall(ip2);
            }
            if (ip != null) {
                cleanUpInstall(ip);
            }
        }
    }

    /*
     * Ensure that permissions are properly declared.
     */
    @LargeTest
    public void testInstallOnSdPermissionsUnmount() {
        InstallParams ip = null;
        boolean origMediaState = checkMediaState(Environment.MEDIA_MOUNTED);
        try {
            // **: Upon installing a package, are its declared permissions published?
            int iFlags = PackageManager.INSTALL_INTERNAL;
            int iApk = R.raw.install_decl_perm;
            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            // Unmount media here
            assertTrue(unmountMedia());
            // Mount media again
            mountMedia();
            //Check permissions now
            assertPermissions(BASE_PERMISSIONS_DEFINED);
        } finally {
            if (ip != null) {
                cleanUpInstall(ip);
            }
        }
    }

    /* This test creates a stale container via MountService and then installs
     * a package and verifies that the stale container is cleaned up and install
     * is successful.
     * Please note that this test is very closely tied to the framework's
     * naming convention for secure containers.
     */
    @LargeTest
    public void testInstallSdcardStaleContainer() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean origMediaState = checkMediaState(Environment.MEDIA_MOUNTED);
        try {
            // Mount media first
            mountMedia();
            String outFileName = "install.apk";
            int rawResId = R.raw.install;
            PackageManager pm = mContext.getPackageManager();
            File filesDir = mContext.getFilesDir();
            File outFile = new File(filesDir, outFileName);
            Uri packageURI = getInstallablePackage(rawResId, outFile);
            PackageParser.Package pkg = parsePackage(packageURI);
            assertNotNull(pkg);
            // Install an app on sdcard.
            installFromRawResource(outFileName, rawResId,
                    PackageManager.INSTALL_EXTERNAL, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            // Unmount sdcard
            unmountMedia();
            // Delete the app on sdcard to leave a stale container on sdcard.
            GenericReceiver receiver = new DeleteReceiver(pkg.packageName);
            assertTrue(invokeDeletePackage(pkg.packageName, 0, receiver));
            mountMedia();
            // Reinstall the app and make sure it gets installed.
            installFromRawResource(outFileName, rawResId,
                    PackageManager.INSTALL_EXTERNAL, true,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        } catch (Exception e) {
            failStr(e.getMessage());
        } finally {
            if (origMediaState) {
                mountMedia();
            } else {
                unmountMedia();
            }

        }
    }

    /* This test installs an application on sdcard and unmounts media.
     * The app is then re-installed on internal storage. The sdcard is mounted
     * and verified that the re-installation on internal storage takes precedence.
     */
    @LargeTest
    public void testInstallSdcardStaleContainerReinstall() {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean origMediaState = checkMediaState(Environment.MEDIA_MOUNTED);
        try {
            // Mount media first
            mountMedia();
            String outFileName = "install.apk";
            int rawResId = R.raw.install;
            PackageManager pm = mContext.getPackageManager();
            File filesDir = mContext.getFilesDir();
            File outFile = new File(filesDir, outFileName);
            Uri packageURI = getInstallablePackage(rawResId, outFile);
            PackageParser.Package pkg = parsePackage(packageURI);
            assertNotNull(pkg);
            // Install an app on sdcard.
            installFromRawResource(outFileName, rawResId,
                    PackageManager.INSTALL_EXTERNAL, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            // Unmount sdcard
            unmountMedia();
            // Reinstall the app and make sure it gets installed on internal storage.
            installFromRawResource(outFileName, rawResId,
                    PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            mountMedia();
            // Verify that the app installed is on internal storage.
            assertInstall(pkg, 0, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);            
        } catch (Exception e) {
            failStr(e.getMessage());
        } finally {
            if (origMediaState) {
                mountMedia();
            } else {
                unmountMedia();
            }
        }
    }

    /*
     * The following series of tests are related to upgrading apps with
     * different certificates.
     */
    private int APP1_UNSIGNED = R.raw.install_app1_unsigned;
    private int APP1_CERT1 = R.raw.install_app1_cert1;
    private int APP1_CERT2 = R.raw.install_app1_cert2;
    private int APP1_CERT1_CERT2 = R.raw.install_app1_cert1_cert2;
    private int APP1_CERT3_CERT4 = R.raw.install_app1_cert3_cert4;
    private int APP1_CERT3 = R.raw.install_app1_cert3;
    private int APP2_UNSIGNED = R.raw.install_app2_unsigned;
    private int APP2_CERT1 = R.raw.install_app2_cert1;
    private int APP2_CERT2 = R.raw.install_app2_cert2;
    private int APP2_CERT1_CERT2 = R.raw.install_app2_cert1_cert2;
    private int APP2_CERT3 = R.raw.install_app2_cert3;

    private InstallParams replaceCerts(int apk1, int apk2, boolean cleanUp, boolean fail, int retCode) {
        int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        PackageParser.Package pkg1 = getParsedPackage(apk1Name, apk1);
        try {
            InstallParams ip = installFromRawResource(apk1Name, apk1, 0, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            installFromRawResource(apk2Name, apk2, rFlags, false,
                    fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            return ip;
        } catch (Exception e) {
            failStr(e.getMessage());
        } finally {
            if (cleanUp) {
                cleanUpInstall(pkg1.packageName);
            }
        }
        return null;
    }
    /*
     * Test that an app signed with two certificates can be upgraded by the
     * same app signed with two certificates.
     */
    @LargeTest
    public void testReplaceMatchAllCerts() {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT1_CERT2, true, false, -1);
    }

    /*
     * Test that an app signed with two certificates cannot be upgraded
     * by an app signed with a different certificate.
     */
    @LargeTest
    public void testReplaceMatchNoCerts1() {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT3, true, true,
                PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES);
    }
    /*
     * Test that an app signed with two certificates cannot be upgraded
     * by an app signed with a different certificate.
     */
    @LargeTest
    public void testReplaceMatchNoCerts2() {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT3_CERT4, true, true,
                PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES);
    }
    /*
     * Test that an app signed with two certificates cannot be upgraded by
     * an app signed with a subset of initial certificates.
     */
    @LargeTest
    public void testReplaceMatchSomeCerts1() {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT1, true, true,
                PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES);
    }
    /*
     * Test that an app signed with two certificates cannot be upgraded by
     * an app signed with the last certificate.
     */
    @LargeTest
    public void testReplaceMatchSomeCerts2() {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT2, true, true,
                PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES);
    }
    /*
     * Test that an app signed with a certificate can be upgraded by app
     * signed with a superset of certificates.
     */
    @LargeTest
    public void testReplaceMatchMoreCerts() {
        replaceCerts(APP1_CERT1, APP1_CERT1_CERT2, true, true,
                PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES);
    }
    /*
     * Test that an app signed with a certificate can be upgraded by app
     * signed with a superset of certificates. Then verify that the an app
     * signed with the original set of certs cannot upgrade the new one.
     */
    @LargeTest
    public void testReplaceMatchMoreCertsReplaceSomeCerts() {
        InstallParams ip = replaceCerts(APP1_CERT1, APP1_CERT1_CERT2, false, true,
                PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES);
        try {
            int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
            installFromRawResource("install.apk", APP1_CERT1, rFlags, false,
                    false, -1,
                    PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        } catch (Exception e) {
            failStr(e.getMessage());
        } finally {
            if (ip != null) {
                cleanUpInstall(ip);
            }
        }
    }
    /*
     * The following tests are related to testing the checkSignatures
     * api.
     */
    private void checkSignatures(int apk1, int apk2, int expMatchResult) {
        checkSharedSignatures(apk1, apk2, true, false, -1, expMatchResult);
    }
    @LargeTest
    public void testCheckSignaturesAllMatch() {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT1_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
    }
    @LargeTest
    public void testCheckSignaturesNoMatch() {
        int apk1 = APP1_CERT1;
        int apk2 = APP2_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }
    @LargeTest
    public void testCheckSignaturesSomeMatch1() {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT1;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }
    @LargeTest
    public void testCheckSignaturesSomeMatch2() {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }
    @LargeTest
    public void testCheckSignaturesMoreMatch() {
        int apk1 = APP1_CERT1;
        int apk2 = APP2_CERT1_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }
    @LargeTest
    public void testCheckSignaturesUnknown() {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT1_CERT2;
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        InstallParams ip1 = null;

        try {
            ip1 = installFromRawResource(apk1Name, apk1, 0, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            PackageManager pm = mContext.getPackageManager();
            // Delete app2
            File filesDir = mContext.getFilesDir();
            File outFile = new File(filesDir, apk2Name);
            int rawResId = apk2;
            Uri packageURI = getInstallablePackage(rawResId, outFile);
            PackageParser.Package pkg = parsePackage(packageURI);
            getPm().deletePackage(pkg.packageName, null, 0);
            // Check signatures now
            int match = mContext.getPackageManager().checkSignatures(
                    ip1.pkg.packageName, pkg.packageName);
            assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, match);
        } finally {
            if (ip1 != null) {
                cleanUpInstall(ip1);
            }
        }
    }
    @LargeTest
    public void testInstallNoCertificates() {
        int apk1 = APP1_UNSIGNED;
        String apk1Name = "install1.apk";
        InstallParams ip1 = null;

        try {
            installFromRawResource(apk1Name, apk1, 0, false,
                    true, PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        } finally {
        }
    }
    /* The following tests are related to apps using shared uids signed
     * with different certs.
     */
    private int SHARED1_UNSIGNED = R.raw.install_shared1_unsigned;
    private int SHARED1_CERT1 = R.raw.install_shared1_cert1;
    private int SHARED1_CERT2 = R.raw.install_shared1_cert2;
    private int SHARED1_CERT1_CERT2 = R.raw.install_shared1_cert1_cert2;
    private int SHARED2_UNSIGNED = R.raw.install_shared2_unsigned;
    private int SHARED2_CERT1 = R.raw.install_shared2_cert1;
    private int SHARED2_CERT2 = R.raw.install_shared2_cert2;
    private int SHARED2_CERT1_CERT2 = R.raw.install_shared2_cert1_cert2;
    private void checkSharedSignatures(int apk1, int apk2, boolean cleanUp, boolean fail, int retCode, int expMatchResult) {
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        PackageParser.Package pkg1 = getParsedPackage(apk1Name, apk1);
        PackageParser.Package pkg2 = getParsedPackage(apk2Name, apk2);

        try {
            // Clean up before testing first.
            cleanUpInstall(pkg1.packageName);
            cleanUpInstall(pkg2.packageName);
            installFromRawResource(apk1Name, apk1, 0, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            if (fail) {
                installFromRawResource(apk2Name, apk2, 0, false,
                        true, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            } else {
                installFromRawResource(apk2Name, apk2, 0, false,
                        false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
                int match = mContext.getPackageManager().checkSignatures(
                        pkg1.packageName, pkg2.packageName);
                assertEquals(expMatchResult, match);
            }
        } finally {
            if (cleanUp) {
                cleanUpInstall(pkg1.packageName);
                cleanUpInstall(pkg2.packageName);
            }
        }
    }
    @LargeTest
    public void testCheckSignaturesSharedAllMatch() {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        boolean fail = false;
        int retCode = -1;
        int expMatchResult = PackageManager.SIGNATURE_MATCH;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }
    @LargeTest
    public void testCheckSignaturesSharedNoMatch() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT2;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
        int expMatchResult = -1;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }
    /*
     * Test that an app signed with cert1 and cert2 cannot be replaced when signed with cert1 alone.
     */
    @LargeTest
    public void testCheckSignaturesSharedSomeMatch1() {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
        int expMatchResult = -1;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }
    /*
     * Test that an app signed with cert1 and cert2 cannot be replaced when signed with cert2 alone.
     */
    @LargeTest
    public void testCheckSignaturesSharedSomeMatch2() {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT2;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
        int expMatchResult = -1;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }
    @LargeTest
    public void testCheckSignaturesSharedUnknown() {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        InstallParams ip1 = null;

        try {
            ip1 = installFromRawResource(apk1Name, apk1, 0, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            PackageManager pm = mContext.getPackageManager();
            // Delete app2
            PackageParser.Package pkg = getParsedPackage(apk2Name, apk2);
            getPm().deletePackage(pkg.packageName, null, 0);
            // Check signatures now
            int match = mContext.getPackageManager().checkSignatures(
                    ip1.pkg.packageName, pkg.packageName);
            assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, match);
        } finally {
            if (ip1 != null) {
                cleanUpInstall(ip1);
            }
        }
    }

    @LargeTest
    public void testReplaceFirstSharedMatchAllCerts() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk1 = SHARED1_CERT1;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
        replaceCerts(apk1, rapk1, true, false, -1);
    }
    @LargeTest
    public void testReplaceSecondSharedMatchAllCerts() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk2 = SHARED2_CERT1;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
        replaceCerts(apk2, rapk2, true, false, -1);
    }
    @LargeTest
    public void testReplaceFirstSharedMatchSomeCerts() {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        int rapk1 = SHARED1_CERT1;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk1, PackageManager.INSTALL_REPLACE_EXISTING, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }
    @LargeTest
    public void testReplaceSecondSharedMatchSomeCerts() {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        int rapk2 = SHARED2_CERT1;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk2, PackageManager.INSTALL_REPLACE_EXISTING, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }
    @LargeTest
    public void testReplaceFirstSharedMatchNoCerts() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk1 = SHARED1_CERT2;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk1, PackageManager.INSTALL_REPLACE_EXISTING, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }
    @LargeTest
    public void testReplaceSecondSharedMatchNoCerts() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk2 = SHARED2_CERT2;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk2, PackageManager.INSTALL_REPLACE_EXISTING, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }
    @LargeTest
    public void testReplaceFirstSharedMatchMoreCerts() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk1 = SHARED1_CERT1_CERT2;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk1, PackageManager.INSTALL_REPLACE_EXISTING, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }
    @LargeTest
    public void testReplaceSecondSharedMatchMoreCerts() {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk2 = SHARED2_CERT1_CERT2;
        boolean fail = true;
        int retCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk2, PackageManager.INSTALL_REPLACE_EXISTING, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    /**
     * Unknown features should be allowed to install. This prevents older phones
     * from rejecting new packages that specify features that didn't exist when
     * an older phone existed. All older phones are assumed to have those
     * features.
     * <p>
     * Right now we allow all packages to be installed regardless of their
     * features.
     */
    @LargeTest
    public void testUsesFeatureUnknownFeature() {
        int retCode = PackageManager.INSTALL_SUCCEEDED;
        installFromRawResource("install.apk", R.raw.install_uses_feature, 0, true, false, retCode,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testInstallNonexistentFile() {
        int retCode = PackageManager.INSTALL_FAILED_INVALID_URI;
        File invalidFile = new File("/nonexistent-file.apk");
        invokeInstallPackageFail(Uri.fromFile(invalidFile), 0, retCode);
    }

    @SmallTest
    public void testGetVerifierDeviceIdentity() {
        PackageManager pm = getPm();
        VerifierDeviceIdentity id = pm.getVerifierDeviceIdentity();

        assertNotNull("Verifier device identity should not be null", id);
    }

    /*---------- Recommended install location tests ----*/
    /*
     * TODO's
     * check version numbers for upgrades
     * check permissions of installed packages
     * how to do tests on updated system apps?
     * verify updates to system apps cannot be installed on the sdcard.
     */
}
