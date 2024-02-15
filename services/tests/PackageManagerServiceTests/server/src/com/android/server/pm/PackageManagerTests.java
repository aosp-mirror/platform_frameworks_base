/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import static android.system.OsConstants.S_IFDIR;
import static android.system.OsConstants.S_IFMT;
import static android.system.OsConstants.S_IRGRP;
import static android.system.OsConstants.S_IROTH;
import static android.system.OsConstants.S_IRWXU;
import static android.system.OsConstants.S_ISDIR;
import static android.system.OsConstants.S_IXGRP;
import static android.system.OsConstants.S_IXOTH;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.KeySet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.StatFs;
import android.os.SystemClock;
import android.platform.test.annotations.Postsubmit;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;

import com.android.compatibility.common.util.CddTest;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.parsing.ParsingUtils;
import com.android.server.pm.test.service.server.R;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

@Postsubmit
public class PackageManagerTests extends AndroidTestCase {
    private static final boolean localLOGV = true;

    public static final String TAG = "PackageManagerTests";

    public static final long MAX_WAIT_TIME = 25 * 1000;

    public static final long WAIT_TIME_INCR = 5 * 1000;

    private static final String SECURE_CONTAINERS_PREFIX = "/mnt/asec";

    private static final int APP_INSTALL_AUTO = InstallLocationUtils.APP_INSTALL_AUTO;

    private static final int APP_INSTALL_DEVICE = InstallLocationUtils.APP_INSTALL_INTERNAL;

    private static final int APP_INSTALL_SDCARD = InstallLocationUtils.APP_INSTALL_EXTERNAL;

    private static final int DEFAULT_INSTALL_FLAGS =
            PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;

    void failStr(String errMsg) {
        Log.w(TAG, "errMsg=" + errMsg);
        fail(errMsg);
    }

    void failStr(Exception e) {
        failStr(e.getMessage());
    }

    private abstract static class GenericReceiver extends BroadcastReceiver {
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

    private static class InstallReceiver extends GenericReceiver {
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

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            while (true) {
                try {
                    return mResult.take();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private PackageManager getPm() {
        return mContext.getPackageManager();
    }

    private PackageInstaller getPi() {
        return getPm().getPackageInstaller();
    }

    private void writeSplitToInstallSession(PackageInstaller.Session session, String inPath,
            String splitName) throws RemoteException {
        long sizeBytes = 0;
        final File file = new File(inPath);
        if (file.isFile()) {
            sizeBytes = file.length();
        } else {
            return;
        }

        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(inPath);
            out = session.openWrite(splitName, 0, sizeBytes);

            int total = 0;
            byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                total += c;
                out.write(buffer, 0, c);
            }
            session.fsync(out);
        } catch (IOException e) {
            fail("Error: failed to write; " + e.getMessage());
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(session);
        }
    }

    private void invokeInstallPackage(Uri packageUri, int flags, GenericReceiver receiver,
            boolean shouldSucceed) {
        mContext.registerReceiver(receiver, receiver.filter);
        synchronized (receiver) {
            final String inPath = packageUri.getPath();
            PackageInstaller.Session session = null;
            try {
                final SessionParams sessionParams =
                        new SessionParams(SessionParams.MODE_FULL_INSTALL);
                sessionParams.installFlags = flags;
                final int sessionId = getPi().createSession(sessionParams);
                session = getPi().openSession(sessionId);
                writeSplitToInstallSession(session, inPath, "base.apk");
                final LocalIntentReceiver localReceiver = new LocalIntentReceiver();
                session.commit(localReceiver.getIntentSender());
                final Intent result = localReceiver.getResult();
                final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (shouldSucceed) {
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        fail("Installation should have succeeded, but got code " + status);
                    }
                } else {
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        fail("Installation should have failed");
                    }
                    // We'll never get a broadcast since the package failed to install
                    return;
                }
                // Verify we received the broadcast
                long waitTime = 0;
                while ((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME)) {
                    try {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    } catch (InterruptedException e) {
                        Log.i(TAG, "Interrupted during sleep", e);
                    }
                }
                if (!receiver.isDone()) {
                    fail("Timed out waiting for PACKAGE_ADDED notification");
                }
            } catch (IllegalArgumentException | IOException | RemoteException e) {
                Log.w(TAG, "Failed to install package; path=" + inPath, e);
                fail("Failed to install package; path=" + inPath + ", e=" + e);
            } finally {
                IoUtils.closeQuietly(session);
                mContext.unregisterReceiver(receiver);
            }
        }
    }

    private void invokeInstallPackageFail(Uri packageUri, int flags, int expectedResult) {
        final String inPath = packageUri.getPath();
        PackageInstaller.Session session = null;
        try {
            final SessionParams sessionParams =
                    new SessionParams(SessionParams.MODE_FULL_INSTALL);
            sessionParams.installFlags = flags;
            final int sessionId = getPi().createSession(sessionParams);
            session = getPi().openSession(sessionId);
            writeSplitToInstallSession(session, inPath, "base.apk");
            final LocalIntentReceiver localReceiver = new LocalIntentReceiver();
            session.commit(localReceiver.getIntentSender());
            final Intent result = localReceiver.getResult();
            final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_SUCCESS);
            String statusMessage = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            assertEquals(statusMessage, expectedResult, status);
        } catch (IllegalArgumentException | IOException | RemoteException e) {
            Log.w(TAG, "Failed to install package; path=" + inPath, e);
            fail("Failed to install package; path=" + inPath + ", e=" + e);
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private Uri getInstallablePackage(int fileResId, File outFile) {
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

    private ParsedPackage parsePackage(Uri packageURI) {
        final String archiveFilePath = packageURI.getPath();
        ParseResult<ParsedPackage> result = ParsingUtils.parseDefaultOneTime(
                new File(archiveFilePath), 0 /*flags*/, Collections.emptyList(),
                false /*collectCertificates*/);
        if (result.isError()) {
            throw new IllegalStateException(result.getErrorMessage(), result.getException());
        }
        return result.getResult();
    }

    private boolean checkSd(long pkgLen) {
        String status = Environment.getExternalStorageState();
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            return false;
        }
        long sdSize = -1;
        StatFs sdStats = new StatFs(Environment.getExternalStorageDirectory().getPath());
        sdSize = (long) sdStats.getAvailableBlocks() * (long) sdStats.getBlockSize();
        // TODO check for thresholds here
        return pkgLen <= sdSize;

    }

    private boolean checkInt(long pkgLen) {
        StatFs intStats = new StatFs(Environment.getDataDirectory().getPath());
        long intSize = (long) intStats.getBlockCount() * (long) intStats.getBlockSize();
        long iSize = (long) intStats.getAvailableBlocks() * (long) intStats.getBlockSize();
        // TODO check for thresholds here?
        return pkgLen <= iSize;
    }

    private static final int INSTALL_LOC_INT = 1;

    private static final int INSTALL_LOC_SD = 2;

    private static final int INSTALL_LOC_ERR = -1;

    private int getInstallLoc(int flags, int expInstallLocation, long pkgLen) {
        // Flags explicitly over ride everything else.
        if ((flags & PackageManager.INSTALL_INTERNAL) != 0) {
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

    private void assertInstall(ParsedPackage pkg, int flags, int expInstallLocation) {
        try {
            String pkgName = pkg.getPackageName();
            ApplicationInfo info = getPm().getApplicationInfo(pkgName, 0);
            assertNotNull(info);
            assertEquals(pkgName, info.packageName);
            File dataDir = Environment.getDataDirectory();
            String appInstallParent = new File(dataDir, "app").getPath();
            File srcDir = new File(info.sourceDir);
            String srcPathParent = srcDir.getParentFile().getParentFile().getParent();
            File publicSrcDir = new File(info.publicSourceDir);
            String publicSrcPath = publicSrcDir.getParentFile().getParentFile().getParent();
            long pkgLen = new File(info.sourceDir).length();
            String expectedLibPath = new File(new File(info.sourceDir).getParentFile(), "lib")
                    .getPath();

            int rLoc = getInstallLoc(flags, expInstallLocation, pkgLen);
            if (rLoc == INSTALL_LOC_INT) {
                assertEquals(appInstallParent, srcPathParent);
                assertEquals(appInstallParent, publicSrcPath);
                assertStartsWith("Native library should point to shared lib directory",
                        expectedLibPath, info.nativeLibraryDir);
                assertDirOwnerGroupPermsIfExists(
                        "Native library directory should be owned by system:system and 0755",
                        Process.SYSTEM_UID, Process.SYSTEM_UID,
                        S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH,
                        info.nativeLibraryDir);
                assertFalse((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);

                // Make sure the native library dir is not a symlink
                final File nativeLibDir = new File(info.nativeLibraryDir);
                if (nativeLibDir.exists()) {
                    try {
                        assertEquals("Native library dir should not be a symlink",
                                info.nativeLibraryDir, nativeLibDir.getCanonicalPath());
                    } catch (IOException e) {
                        fail("Can't read " + nativeLibDir.getPath());
                    }
                }
            } else if (rLoc == INSTALL_LOC_SD) {
                assertTrue("Application flags (" + info.flags
                        + ") should contain FLAG_EXTERNAL_STORAGE",
                        (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                // Might need to check:
                // ((info.privateFlags & ApplicationInfo.PRIVATE_FLAG_FORWARD_LOCK) != 0)
                assertStartsWith("The APK path should point to the ASEC",
                        SECURE_CONTAINERS_PREFIX, srcPathParent);
                assertStartsWith("The public APK path should point to the ASEC",
                        SECURE_CONTAINERS_PREFIX, publicSrcPath);
                assertStartsWith("The native library path should point to the ASEC",
                        SECURE_CONTAINERS_PREFIX, info.nativeLibraryDir);

                // Make sure the native library in /data/data/<app>/lib is a
                // symlink to the ASEC
                final File nativeLibSymLink = new File(info.dataDir, "lib");
                assertTrue("Native library symlink should exist at " + nativeLibSymLink.getPath(),
                        nativeLibSymLink.exists());
                try {
                    assertEquals(nativeLibSymLink.getPath() + " should be a symlink to "
                            + info.nativeLibraryDir, info.nativeLibraryDir,
                            nativeLibSymLink.getCanonicalPath());
                } catch (IOException e) {
                    fail("Can't read " + nativeLibSymLink.getPath());
                }
            } else {
                // TODO handle error. Install should have failed.
                fail("Install should have failed");
            }
        } catch (NameNotFoundException e) {
            failStr("failed with exception : " + e);
        }
    }

    private void assertDirOwnerGroupPermsIfExists(String reason, int uid, int gid, int perms,
            String path) {
        if (!new File(path).exists()) {
            return;
        }

        final StructStat stat;
        try {
            stat = Os.lstat(path);
        } catch (ErrnoException e) {
            throw new AssertionError(reason + "\n" + "Got: " + path + " does not exist");
        }

        StringBuilder sb = new StringBuilder();

        if (!S_ISDIR(stat.st_mode)) {
            sb.append("\nExpected type: ");
            sb.append(S_IFDIR);
            sb.append("\ngot type: ");
            sb.append((stat.st_mode & S_IFMT));
        }

        if (stat.st_uid != uid) {
            sb.append("\nExpected owner: ");
            sb.append(uid);
            sb.append("\nGot owner: ");
            sb.append(stat.st_uid);
        }

        if (stat.st_gid != gid) {
            sb.append("\nExpected group: ");
            sb.append(gid);
            sb.append("\nGot group: ");
            sb.append(stat.st_gid);
        }

        if ((stat.st_mode & ~S_IFMT) != perms) {
            sb.append("\nExpected permissions: ");
            sb.append(Integer.toOctalString(perms));
            sb.append("\nGot permissions: ");
            sb.append(Integer.toOctalString(stat.st_mode & ~S_IFMT));
        }

        if (sb.length() > 0) {
            throw new AssertionError(reason + sb.toString());
        }
    }

    private static void assertStartsWith(String prefix, String actual) {
        assertStartsWith("", prefix, actual);
    }

    private static void assertStartsWith(String description, String prefix, String actual) {
        if (!actual.startsWith(prefix)) {
            StringBuilder sb = new StringBuilder(description);
            sb.append("\nExpected prefix: ");
            sb.append(prefix);
            sb.append("\n     got: ");
            sb.append(actual);
            sb.append('\n');
            throw new AssertionError(sb.toString());
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

        ParsedPackage pkg;

        InstallParams(String outFileName, int rawResId) {
            this.pkg = getParsedPackage(outFileName, rawResId);
            this.packageURI = Uri.fromFile(new File(pkg.getPath()));
        }

        InstallParams(ParsedPackage pkg) {
            this.packageURI = Uri.fromFile(new File(pkg.getPath()));
            this.pkg = pkg;
        }

        long getApkSize() {
            File file = new File(pkg.getPath());
            return file.length();
        }
    }

    private InstallParams sampleInstallFromRawResource(int flags, boolean cleanUp)
            throws Exception {
        return installFromRawResource("install.apk", R.raw.install, flags, cleanUp, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
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
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
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
                        for (int j = 0; j < pkgInfo.permissions.length && !found; j++) {
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
                        for (int j = 0; j < pkgInfo.permissions.length && !found; j++) {
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
                    for (int j = 0; j < pkgInfo.requestedPermissions.length && !found; j++) {
                        if (pkgInfo.requestedPermissions[j].equals(cmd)) {
                            found = true;
                        }
                    }
                    if (!found) {
                        fail("Permission not requested: " + cmd);
                    }
                    if (mode == PERM_USED) {
                        if (pm.checkPermission(cmd, pkg) != PackageManager.PERMISSION_GRANTED) {
                            fail("Permission not granted: " + cmd);
                        }
                    } else {
                        if (pm.checkPermission(cmd, pkg) != PackageManager.PERMISSION_DENIED) {
                            fail("Permission granted: " + cmd);
                        }
                    }
                }
            }
        }
    }

    private ParsedPackage getParsedPackage(String outFileName, int rawResId) {
        PackageManager pm = mContext.getPackageManager();
        File filesDir = mContext.getFilesDir();
        File outFile = new File(filesDir, outFileName);
        Uri packageURI = getInstallablePackage(rawResId, outFile);
        return parsePackage(packageURI);
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install it.
     */
    private void installFromRawResource(InstallParams ip, int flags, boolean cleanUp, boolean fail,
            int result, int expInstallLocation) throws Exception {
        PackageManager pm = mContext.getPackageManager();
        ParsedPackage pkg = ip.pkg;
        Uri packageURI = ip.packageURI;
        if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) == 0) {
            // Make sure the package doesn't exist
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg.getPackageName(),
                        PackageManager.MATCH_UNINSTALLED_PACKAGES);
                GenericReceiver receiver = new DeleteReceiver(pkg.getPackageName());
                invokeDeletePackage(pkg.getPackageName(), 0, receiver);
            } catch (IllegalArgumentException | NameNotFoundException e) {
            }
        }
        try {
            if (fail) {
                invokeInstallPackageFail(packageURI, flags, result);
                if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) == 0) {
                    assertNotInstalled(pkg.getPackageName());
                }
            } else {
                InstallReceiver receiver = new InstallReceiver(pkg.getPackageName());
                invokeInstallPackage(packageURI, flags, receiver, true);
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
    private InstallParams installFromRawResource(String outFileName, int rawResId, int flags,
            boolean cleanUp, boolean fail, int result, int expInstallLocation) throws Exception {
        InstallParams ip = new InstallParams(outFileName, rawResId);
        installFromRawResource(ip, flags, cleanUp, fail, result, expInstallLocation);
        return ip;
    }

    @LargeTest
    public void testInstallNormalInternal() throws Exception {
        sampleInstallFromRawResource(0, true);
    }

    /* ------------------------- Test replacing packages -------------- */
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
    private void sampleReplaceFromRawResource(int flags) throws Exception {
        InstallParams ip = sampleInstallFromRawResource(flags, false);
        boolean replace = ((flags & PackageManager.INSTALL_REPLACE_EXISTING) != 0);
        Log.i(TAG, "replace=" + replace);
        GenericReceiver receiver;
        if (replace) {
            receiver = new ReplaceReceiver(ip.pkg.getPackageName());
            Log.i(TAG, "Creating replaceReceiver");
        } else {
            receiver = new InstallReceiver(ip.pkg.getPackageName());
        }
        try {
            invokeInstallPackage(ip.packageURI, flags, receiver, true);
            if (replace) {
                assertInstall(ip.pkg, flags, ip.pkg.getInstallLocation());
            }
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testReplaceFlagDoesNotNeedToBeSet() throws Exception {
        sampleReplaceFromRawResource(0);
    }

    @LargeTest
    public void testReplaceNormalInternal() throws Exception {
        sampleReplaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING);
    }

    /* -------------- Delete tests --- */
    private static class DeleteObserver extends IPackageDeleteObserver.Stub {
        private CountDownLatch mLatch = new CountDownLatch(1);

        private int mReturnCode;

        private final String mPackageName;

        private String mObservedPackage;

        public DeleteObserver(String packageName) {
            mPackageName = packageName;
        }

        public boolean isSuccessful() {
            return mReturnCode == PackageManager.DELETE_SUCCEEDED;
        }

        public void packageDeleted(String packageName, int returnCode) throws RemoteException {
            mObservedPackage = packageName;

            mReturnCode = returnCode;

            mLatch.countDown();
        }

        public void waitForCompletion(long timeoutMillis) {
            final long deadline = SystemClock.uptimeMillis() + timeoutMillis;

            long waitTime = timeoutMillis;
            while (waitTime > 0) {
                try {
                    boolean done = mLatch.await(waitTime, TimeUnit.MILLISECONDS);
                    if (done) {
                        assertEquals(mPackageName, mObservedPackage);
                        return;
                    }
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                waitTime = deadline - SystemClock.uptimeMillis();
            }

            throw new AssertionError("Timeout waiting for package deletion");
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

    public boolean invokeDeletePackage(final String pkgName, int flags, GenericReceiver receiver)
            throws Exception {
        ApplicationInfo info = getPm().getApplicationInfo(pkgName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES);

        mContext.registerReceiver(receiver, receiver.filter);
        try {
            final LocalIntentReceiver localReceiver = new LocalIntentReceiver();
            getPi().uninstall(pkgName,
                    flags | PackageManager.DELETE_ALL_USERS,
                    localReceiver.getIntentSender());
            localReceiver.getResult();

            assertUninstalled(info);

            // Verify we received the broadcast
            // TODO replace this with a CountDownLatch
            synchronized (receiver) {
                long waitTime = 0;
                while ((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME)) {
                    receiver.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if (!receiver.isDone()) {
                    throw new Exception("Timed out waiting for PACKAGE_REMOVED notification");
                }
            }
            return receiver.received;
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    private static void assertUninstalled(ApplicationInfo info) throws Exception {
        if (info.nativeLibraryDir != null) {
            File nativeLibraryFile = new File(info.nativeLibraryDir);
            assertFalse("Native library directory " + info.nativeLibraryDir
                    + " should be erased", nativeLibraryFile.exists());
        }
    }

    public void deleteFromRawResource(int iFlags, int dFlags) throws Exception {
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        boolean retainData = ((dFlags & PackageManager.DELETE_KEEP_DATA) != 0);
        GenericReceiver receiver = new DeleteReceiver(ip.pkg.getPackageName());
        try {
            assertTrue(invokeDeletePackage(ip.pkg.getPackageName(), dFlags, receiver));
            ApplicationInfo info = null;
            Log.i(TAG, "okay4");
            try {
                info = getPm().getApplicationInfo(ip.pkg.getPackageName(),
                        PackageManager.MATCH_UNINSTALLED_PACKAGES);
            } catch (NameNotFoundException e) {
                info = null;
            }
            if (retainData) {
                assertNotNull(info);
                assertEquals(info.packageName, ip.pkg.getPackageName());
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
    public void testDeleteNormalInternal() throws Exception {
        deleteFromRawResource(0, 0);
    }


    @LargeTest
    public void testDeleteNormalInternalRetainData() throws Exception {
        deleteFromRawResource(0, PackageManager.DELETE_KEEP_DATA);
    }

    void cleanUpInstall(InstallParams ip) throws Exception {
        if (ip == null) {
            return;
        }
        Runtime.getRuntime().gc();
        try {
            cleanUpInstall(ip.pkg.getPackageName());
        } finally {
            File outFile = new File(ip.pkg.getPath());
            if (outFile != null && outFile.exists()) {
                outFile.delete();
            }
        }
    }

    private void cleanUpInstall(String pkgName) throws Exception {
        if (pkgName == null) {
            return;
        }
        Log.i(TAG, "Deleting package : " + pkgName);
        try {
            final ApplicationInfo info = getPm().getApplicationInfo(pkgName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if (info != null) {
                final LocalIntentReceiver localReceiver = new LocalIntentReceiver();
                getPi().uninstall(pkgName,
                        PackageManager.DELETE_ALL_USERS,
                        localReceiver.getIntentSender());
                localReceiver.getResult();
                assertUninstalled(info);
            }
        } catch (IllegalArgumentException | NameNotFoundException e) {
        }
    }

    @LargeTest
    public void testManifestInstallLocationInternal() throws Exception {
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testManifestInstallLocationSdcard() throws Exception {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    @LargeTest
    public void testManifestInstallLocationAuto() throws Exception {
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_AUTO);
    }

    @LargeTest
    public void testManifestInstallLocationUnspecified() throws Exception {
        installFromRawResource("install.apk", R.raw.install_loc_unspecified,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testManifestInstallLocationReplaceInternalSdcard() throws Exception {
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
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.getPackageName());
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            InstallParams rp = installFromRawResource("install.apk", rApk,
                    replaceFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
            assertInstall(rp.pkg, replaceFlags, rp.pkg.getInstallLocation());
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testManifestInstallLocationReplaceSdcardInternal() throws Exception {
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
            assertInstall(rp.pkg, replaceFlags, ip.pkg.getInstallLocation());
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

    public boolean invokeMovePackage(String pkgName, int flags, GenericReceiver receiver)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    private boolean invokeMovePackageFail(String pkgName, int flags, int errCode) throws Exception {
        throw new UnsupportedOperationException();
    }

    private int getDefaultInstallLoc() {
        int origDefaultLoc = PackageInfo.INSTALL_LOCATION_AUTO;
        try {
            origDefaultLoc = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEFAULT_INSTALL_LOCATION);
        } catch (SettingNotFoundException e1) {
        }
        return origDefaultLoc;
    }

    private void setInstallLoc(int loc) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.DEFAULT_INSTALL_LOCATION, loc);
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

    private void moveFromRawResource(String outFileName, int rawResId, int installFlags,
            int moveFlags, boolean cleanUp, boolean fail, int result) throws Exception {
        int origDefaultLoc = getDefaultInstallLoc();
        InstallParams ip = null;
        try {
            setInstallLoc(InstallLocationUtils.APP_INSTALL_AUTO);
            // Install first
            ip = installFromRawResource("install.apk", rawResId, installFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            ApplicationInfo oldAppInfo = getPm().getApplicationInfo(ip.pkg.getPackageName(), 0);
            if (fail) {
                assertTrue(invokeMovePackageFail(ip.pkg.getPackageName(), moveFlags, result));
                ApplicationInfo info = getPm().getApplicationInfo(ip.pkg.getPackageName(), 0);
                assertNotNull(info);
                assertEquals(oldAppInfo.flags, info.flags);
            } else {
                // Create receiver based on expRetCode
                MoveReceiver receiver = new MoveReceiver(ip.pkg.getPackageName());
                boolean retCode = invokeMovePackage(ip.pkg.getPackageName(), moveFlags, receiver);
                assertTrue(retCode);
                ApplicationInfo info = getPm().getApplicationInfo(ip.pkg.getPackageName(), 0);
                assertNotNull("ApplicationInfo for recently installed application should exist",
                        info);
                if ((moveFlags & PackageManager.MOVE_INTERNAL) != 0) {
                    assertTrue("ApplicationInfo.FLAG_EXTERNAL_STORAGE flag should NOT be set",
                            (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0);
                    assertStartsWith("Native library dir should be in dataDir",
                            info.dataDir, info.nativeLibraryDir);
                } else if ((moveFlags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0) {
                    assertTrue("ApplicationInfo.FLAG_EXTERNAL_STORAGE flag should be set",
                            (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                    assertStartsWith("Native library dir should point to ASEC",
                            SECURE_CONTAINERS_PREFIX, info.nativeLibraryDir);
                }
            }
        } catch (NameNotFoundException e) {
            failStr("Pkg hasnt been installed correctly");
        } finally {
            if (ip != null) {
                cleanUpInstall(ip);
            }
            // Restore default install location
            setInstallLoc(origDefaultLoc);
        }
    }

    private void sampleMoveFromRawResource(int installFlags, int moveFlags, boolean fail,
            int result) throws Exception {
        moveFromRawResource("install.apk",
                R.raw.install, installFlags, moveFlags, true,
                fail, result);
    }

    @LargeTest
    public void testMoveAppInternalToExternal() throws Exception {
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

    @Suppress
    @LargeTest
    public void testMoveAppInternalToInternal() throws Exception {
        int installFlags = PackageManager.INSTALL_INTERNAL;
        int moveFlags = PackageManager.MOVE_INTERNAL;
        boolean fail = true;
        int result = PackageManager.MOVE_FAILED_INVALID_LOCATION;
        sampleMoveFromRawResource(installFlags, moveFlags, fail, result);
    }

    @LargeTest
    public void testMoveAppFailInternalToExternalDelete() throws Exception {
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
            setInstallLoc(InstallLocationUtils.APP_INSTALL_AUTO);
            // Install first
            ip = installFromRawResource("install.apk", R.raw.install, installFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            // Delete the package now retaining data.
            GenericReceiver receiver = new DeleteReceiver(ip.pkg.getPackageName());
            invokeDeletePackage(ip.pkg.getPackageName(), PackageManager.DELETE_KEEP_DATA, receiver);
            assertTrue(invokeMovePackageFail(ip.pkg.getPackageName(), moveFlags, result));
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

    /*---------- Recommended install location tests ----*/
    /*
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
    public void testFlagI() throws Exception {
        sampleInstallFromRawResource(PackageManager.INSTALL_INTERNAL, true);
    }

    /*
     * Install an app with both internal and manifest option set.
     * should install on internal.
     */
    @LargeTest
    public void testFlagIManifestI() throws Exception {
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
    public void testFlagIManifestE() throws Exception {
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
    public void testFlagIManifestA() throws Exception {
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                PackageManager.INSTALL_INTERNAL,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    /*
     * The following test functions verify install location for existing apps.
     * ie existing app can be installed internally or externally. If install
     * flag is explicitly set it should override current location. If manifest location
     * is set, that should over ride current location too. if not the existing install
     * location should be honoured.
     * testFlagI/E/F/ExistingI/E -
     */
    @LargeTest
    public void testFlagIExistingI() throws Exception {
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

    /*
     * The following set of tests verify the installation of apps with
     * install location attribute set to internalOnly, preferExternal and auto.
     * The manifest option should dictate the install location.
     * public void testManifestI/E/A
     * TODO out of memory fall back behaviour.
     */
    @LargeTest
    public void testManifestI() throws Exception {
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                0,
                true,
                false, -1,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testManifestE() throws Exception {
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
    public void testManifestA() throws Exception {
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
    public void testManifestIExistingI() throws Exception {
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
    public void testManifestEExistingI() throws Exception {
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
    public void testManifestAExistingI() throws Exception {
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

    /*
     * The following set of tests check install location for existing
     * application based on user setting.
     */
    private int getExpectedInstallLocation(int userSetting) {
        int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
        boolean enable = getUserSettingSetInstallLocation();
        if (enable) {
            if (userSetting == InstallLocationUtils.APP_INSTALL_AUTO) {
                iloc = PackageInfo.INSTALL_LOCATION_AUTO;
            } else if (userSetting == InstallLocationUtils.APP_INSTALL_EXTERNAL) {
                iloc = PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL;
            } else if (userSetting == InstallLocationUtils.APP_INSTALL_INTERNAL) {
                iloc = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
            }
        }
        return iloc;
    }

    private void setExistingXUserX(int userSetting, int iFlags, int iloc) throws Exception {
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
    public void testExistingIUserI() throws Exception {
        int userSetting = InstallLocationUtils.APP_INSTALL_INTERNAL;
        int iFlags = PackageManager.INSTALL_INTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testExistingIUserE() throws Exception {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = InstallLocationUtils.APP_INSTALL_EXTERNAL;
        int iFlags = PackageManager.INSTALL_INTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    @LargeTest
    public void testExistingIUserA() throws Exception {
        int userSetting = InstallLocationUtils.APP_INSTALL_AUTO;
        int iFlags = PackageManager.INSTALL_INTERNAL;
        setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    /*
     * The following set of tests verify that the user setting defines
     * the install location.
     *
     */
    private boolean getUserSettingSetInstallLocation() {
        try {
            return Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.SET_INSTALL_LOCATION) != 0;
        } catch (SettingNotFoundException e1) {
        }
        return false;
    }

    private void setUserSettingSetInstallLocation(boolean value) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.SET_INSTALL_LOCATION, value ? 1 : 0);
    }

    private void setUserX(boolean enable, int userSetting, int iloc) throws Exception {
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
    public void testUserI() throws Exception {
        int userSetting = InstallLocationUtils.APP_INSTALL_INTERNAL;
        int iloc = getExpectedInstallLocation(userSetting);
        setUserX(true, userSetting, iloc);
    }

    @LargeTest
    public void testUserE() throws Exception {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = InstallLocationUtils.APP_INSTALL_EXTERNAL;
        int iloc = getExpectedInstallLocation(userSetting);
        setUserX(true, userSetting, iloc);
    }

    @LargeTest
    public void testUserA() throws Exception {
        int userSetting = InstallLocationUtils.APP_INSTALL_AUTO;
        int iloc = getExpectedInstallLocation(userSetting);
        setUserX(true, userSetting, iloc);
    }

    /*
     * The following set of tests turn on/off the basic
     * user setting for turning on install location.
     */
    @LargeTest
    public void testUserPrefOffUserI() throws Exception {
        int userSetting = InstallLocationUtils.APP_INSTALL_INTERNAL;
        int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
        setUserX(false, userSetting, iloc);
    }

    @LargeTest
    public void testUserPrefOffUserE() throws Exception {
        // Do not run on devices with emulated external storage.
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        int userSetting = InstallLocationUtils.APP_INSTALL_EXTERNAL;
        int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
        setUserX(false, userSetting, iloc);
    }

    @LargeTest
    public void testUserPrefOffA() throws Exception {
        int userSetting = InstallLocationUtils.APP_INSTALL_AUTO;
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
    public void testInstallDeclaresPermissions() throws Exception {
        InstallParams ip = null;
        InstallParams ip2 = null;
        try {
            // **: Upon installing a package, are its declared permissions published?

            int iFlags = PackageManager.INSTALL_INTERNAL;
            int iApk = R.raw.install_decl_perm;
            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.getInstallLocation());
            assertPermissions(BASE_PERMISSIONS_DEFINED);

            // **: Upon installing package, are its permissions granted?

            int i2Flags = PackageManager.INSTALL_INTERNAL;
            int i2Apk = R.raw.install_use_perm_good;
            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.getInstallLocation());
            assertPermissions(BASE_PERMISSIONS_USED);

            // **: Upon removing but not deleting, are permissions retained?

            GenericReceiver receiver = new DeleteReceiver(ip.pkg.getPackageName());

            try {
                invokeDeletePackage(ip.pkg.getPackageName(), PackageManager.DELETE_KEEP_DATA, receiver);
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_USED);

            // **: Upon re-installing, are permissions retained?

            ip = installFromRawResource("install.apk", iApk,
                    iFlags | PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.getInstallLocation());
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_USED);

            // **: Upon deleting package, are all permissions removed?

            try {
                invokeDeletePackage(ip.pkg.getPackageName(), 0, receiver);
                ip = null;
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_UNDEFINED);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Delete package using permissions; nothing to check here.

            GenericReceiver receiver2 = new DeleteReceiver(ip2.pkg.getPackageName());
            try {
                invokeDeletePackage(ip2.pkg.getPackageName(), 0, receiver);
                ip2 = null;
            } catch (Exception e) {
                failStr(e);
            }

            // **: Re-install package using permissions; no permissions can be granted.

            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.getInstallLocation());
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Upon installing declaring package, are sig permissions granted
            // to other apps (but not other perms)?

            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.getInstallLocation());
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_SIGUSED);

            // **: Re-install package using permissions; are all permissions granted?

            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags | PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.getInstallLocation());
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Upon deleting package, are all permissions removed?

            try {
                invokeDeletePackage(ip.pkg.getPackageName(), 0, receiver);
                ip = null;
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_UNDEFINED);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);

            // **: Delete package using permissions; nothing to check here.

            try {
                invokeDeletePackage(ip2.pkg.getPackageName(), 0, receiver);
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
     * The following series of tests are related to upgrading apps with
     * different certificates.
     */
    private static final int APP1_UNSIGNED = R.raw.install_app1_unsigned;

    private static final int APP1_CERT1 = R.raw.install_app1_cert1;

    private static final int APP1_CERT2 = R.raw.install_app1_cert2;

    private static final int APP1_CERT1_CERT2 = R.raw.install_app1_cert1_cert2;

    private static final int APP1_CERT3_CERT4 = R.raw.install_app1_cert3_cert4;

    private static final int APP1_CERT3 = R.raw.install_app1_cert3;

    private static final int APP1_CERT5 = R.raw.install_app1_cert5;

    private static final int APP1_CERT5_ROTATED_CERT6 = R.raw.install_app1_cert5_rotated_cert6;

    private static final int APP1_CERT6 = R.raw.install_app1_cert6;

    private static final int APP2_UNSIGNED = R.raw.install_app2_unsigned;

    private static final int APP2_CERT1 = R.raw.install_app2_cert1;

    private static final int APP2_CERT2 = R.raw.install_app2_cert2;

    private static final int APP2_CERT1_CERT2 = R.raw.install_app2_cert1_cert2;

    private static final int APP2_CERT3 = R.raw.install_app2_cert3;

    private static final int APP2_CERT5_ROTATED_CERT6 = R.raw.install_app2_cert5_rotated_cert6;

    private InstallParams replaceCerts(int apk1, int apk2, boolean cleanUp, boolean fail,
            int retCode) throws Exception {
        int rFlags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        var pkg1 = getParsedPackage(apk1Name, apk1);
        try {
            InstallParams ip = installFromRawResource(apk1Name, apk1,
                    DEFAULT_INSTALL_FLAGS, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            installFromRawResource(apk2Name, apk2, rFlags, false,
                    fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            return ip;
        } catch (Exception e) {
            failStr(e.getMessage());
        } finally {
            if (cleanUp) {
                cleanUpInstall(pkg1.getPackageName());
            }
        }
        return null;
    }

    /*
     * Test that an app signed with two certificates can be upgraded by the
     * same app signed with two certificates.
     */
    @LargeTest
    public void testReplaceMatchAllCerts() throws Exception {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT1_CERT2, true, false, -1);
    }

    /*
     * Test that an app signed with two certificates cannot be upgraded
     * by an app signed with a different certificate.
     */
    @LargeTest
    public void testReplaceMatchNoCerts1() throws Exception {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT3, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Test that an app signed with two certificates cannot be upgraded
     * by an app signed with a different certificate.
     */
    @LargeTest
    public void testReplaceMatchNoCerts2() throws Exception {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT3_CERT4, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Test that an app signed with two certificates cannot be upgraded by
     * an app signed with a subset of initial certificates.
     */
    @LargeTest
    public void testReplaceMatchSomeCerts1() throws Exception {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT1, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Test that an app signed with two certificates cannot be upgraded by
     * an app signed with the last certificate.
     */
    @LargeTest
    public void testReplaceMatchSomeCerts2() throws Exception {
        replaceCerts(APP1_CERT1_CERT2, APP1_CERT2, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Test that an app signed with a certificate can be upgraded by app
     * signed with a superset of certificates.
     */
    @LargeTest
    public void testReplaceMatchMoreCerts() throws Exception {
        replaceCerts(APP1_CERT1, APP1_CERT1_CERT2, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Test that an app signed with a certificate can be upgraded by app
     * signed with a superset of certificates. Then verify that the an app
     * signed with the original set of certs cannot upgrade the new one.
     */
    @LargeTest
    public void testReplaceMatchMoreCertsReplaceSomeCerts() throws Exception {
        InstallParams ip = replaceCerts(APP1_CERT1, APP1_CERT1_CERT2, false, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
        try {
            int rFlags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
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

    /**
     * The following tests are related to testing KeySets-based key rotation
     */
    /*
     * Check if an apk which does not specify an upgrade-keyset may be upgraded
     * by an apk which does
     */
    public void testNoKSToUpgradeKS() throws Exception {
        replaceCerts(R.raw.keyset_sa_unone, R.raw.keyset_sa_ua, true, false, -1);
    }

    /*
     * Check if an apk which does specify an upgrade-keyset may be downgraded to
     * an apk which does not
     */
    public void testUpgradeKSToNoKS() throws Exception {
        replaceCerts(R.raw.keyset_sa_ua, R.raw.keyset_sa_unone, true, false, -1);
    }

    /*
     * Check if an apk signed by a key other than the upgrade keyset can update
     * an app
     */
    public void testUpgradeKSWithWrongKey() throws Exception {
        replaceCerts(R.raw.keyset_sa_ua, R.raw.keyset_sb_ua, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Check if an apk signed by its signing key, which is not an upgrade key,
     * can upgrade an app.
     */
    public void testUpgradeKSWithWrongSigningKey() throws Exception {
        replaceCerts(R.raw.keyset_sa_ub, R.raw.keyset_sa_ub, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Check if an apk signed by its upgrade key, which is not its signing key,
     * can upgrade an app.
     */
    public void testUpgradeKSWithUpgradeKey() throws Exception {
        replaceCerts(R.raw.keyset_sa_ub, R.raw.keyset_sb_ub, true, false, -1);
    }
    /*
     * Check if an apk signed by its upgrade key, which is its signing key, can
     * upgrade an app.
     */
    public void testUpgradeKSWithSigningUpgradeKey() throws Exception {
        replaceCerts(R.raw.keyset_sa_ua, R.raw.keyset_sa_ua, true, false, -1);
    }

    /*
     * Check if an apk signed by multiple keys, one of which is its upgrade key,
     * can upgrade an app.
     */
    public void testMultipleUpgradeKSWithUpgradeKey() throws Exception {
        replaceCerts(R.raw.keyset_sa_ua, R.raw.keyset_sab_ua, true, false, -1);
    }

    /*
     * Check if an apk signed by multiple keys, one of which is its signing key,
     * but none of which is an upgrade key, can upgrade an app.
     */
    public void testMultipleUpgradeKSWithSigningKey() throws Exception {
        replaceCerts(R.raw.keyset_sau_ub, R.raw.keyset_sa_ua, true, true,
                PackageInstaller.STATUS_FAILURE_CONFLICT);
    }

    /*
     * Check if an apk which defines multiple (two) upgrade keysets is
     * upgrade-able by either.
     */
    public void testUpgradeKSWithMultipleUpgradeKeySets() throws Exception {
        replaceCerts(R.raw.keyset_sa_ua_ub, R.raw.keyset_sa_ua, true, false, -1);
        replaceCerts(R.raw.keyset_sa_ua_ub, R.raw.keyset_sb_ub, true, false, -1);
    }

    /*
     * Check if an apk's sigs are changed after upgrading with a non-signing
     * key.
     *
     * TODO: consider checking against hard-coded Signatures in the Sig-tests
     */
    public void testSigChangeAfterUpgrade() throws Exception {
        // install original apk and grab sigs
        installFromRawResource("tmp.apk", R.raw.keyset_sa_ub,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        PackageManager pm = getPm();
        String pkgName = "com.android.frameworks.coretests.keysets";
        PackageInfo pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should only have one signature, sig A",
                pi.signatures.length == 1);
        String sigBefore = pi.signatures[0].toCharsString();
        // install apk signed by different upgrade KeySet
        installFromRawResource("tmp2.apk", R.raw.keyset_sb_ub,
                PackageManager.INSTALL_REPLACE_EXISTING, false, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should only have one signature, sig B",
                pi.signatures.length == 1);
        String sigAfter = pi.signatures[0].toCharsString();
        assertFalse("Package signatures did not change after upgrade!",
                sigBefore.equals(sigAfter));
        cleanUpInstall(pkgName);
    }

    /*
     * Check if an apk's sig is the same  after upgrading with a signing
     * key.
     */
    public void testSigSameAfterUpgrade() throws Exception {
        // install original apk and grab sigs
        installFromRawResource("tmp.apk", R.raw.keyset_sa_ua,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        PackageManager pm = getPm();
        String pkgName = "com.android.frameworks.coretests.keysets";
        PackageInfo pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should only have one signature, sig A",
                pi.signatures.length == 1);
        String sigBefore = pi.signatures[0].toCharsString();
        // install apk signed by same upgrade KeySet
        installFromRawResource("tmp2.apk", R.raw.keyset_sa_ua,
                PackageManager.INSTALL_REPLACE_EXISTING, false, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should only have one signature, sig A",
                pi.signatures.length == 1);
        String sigAfter = pi.signatures[0].toCharsString();
        assertTrue("Package signatures changed after upgrade!",
                sigBefore.equals(sigAfter));
        cleanUpInstall(pkgName);
    }

    /*
     * Check if an apk's sigs are the same after upgrading with an app with
     * a subset of the original signing keys.
     */
    public void testSigRemovedAfterUpgrade() throws Exception {
        // install original apk and grab sigs
        installFromRawResource("tmp.apk", R.raw.keyset_sab_ua,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        PackageManager pm = getPm();
        String pkgName = "com.android.frameworks.coretests.keysets";
        PackageInfo pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should have two signatures, sig A and sig B",
                pi.signatures.length == 2);
        Set<String> sigsBefore = new HashSet<String>();
        for (int i = 0; i < pi.signatures.length; i++) {
            sigsBefore.add(pi.signatures[i].toCharsString());
        }
        // install apk signed subset upgrade KeySet
        installFromRawResource("tmp2.apk", R.raw.keyset_sa_ua,
                PackageManager.INSTALL_REPLACE_EXISTING, false, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should only have one signature, sig A",
                pi.signatures.length == 1);
        String sigAfter = pi.signatures[0].toCharsString();
        assertTrue("Original package signatures did not contain new sig",
                sigsBefore.contains(sigAfter));
        cleanUpInstall(pkgName);
    }

    /*
     * Check if an apk's sigs are added to after upgrading with an app with
     * a superset of the original signing keys.
     */
    public void testSigAddedAfterUpgrade() throws Exception {
        // install original apk and grab sigs
        installFromRawResource("tmp.apk", R.raw.keyset_sa_ua,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        PackageManager pm = getPm();
        String pkgName = "com.android.frameworks.coretests.keysets";
        PackageInfo pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should only have one signature, sig A",
                pi.signatures.length == 1);
        String sigBefore = pi.signatures[0].toCharsString();
        // install apk signed subset upgrade KeySet
        installFromRawResource("tmp2.apk", R.raw.keyset_sab_ua,
                PackageManager.INSTALL_REPLACE_EXISTING, false, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        pi = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES);
        assertTrue("Package should have two signatures, sig A and sig B",
                pi.signatures.length == 2);
        Set<String> sigsAfter = new HashSet<String>();
        for (int i = 0; i < pi.signatures.length; i++) {
            sigsAfter.add(pi.signatures[i].toCharsString());
        }
        assertTrue("Package signatures did not change after upgrade!",
                sigsAfter.contains(sigBefore));
        cleanUpInstall(pkgName);
    }

    /*
     * Check if an apk gains signature-level permission after changing to the a
     * new signature, for which a permission should be granted.
     */
    public void testUpgradeSigPermGained() throws Exception {
        // install apk which defines permission
        installFromRawResource("permDef.apk", R.raw.keyset_permdef_sa_unone,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        // install apk which uses permission but does not have sig
        installFromRawResource("permUse.apk", R.raw.keyset_permuse_sb_ua_ub,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        // verify that package does not have perm before
        PackageManager pm = getPm();
        String permPkgName = "com.android.frameworks.coretests.keysets_permdef";
        String pkgName = "com.android.frameworks.coretests.keysets";
        String permName = "com.android.frameworks.coretests.keysets_permdef.keyset_perm";
        assertFalse("keyset permission granted to app without same signature!",
                    pm.checkPermission(permName, pkgName)
                    == PackageManager.PERMISSION_GRANTED);
        // upgrade to apk with perm signature
        installFromRawResource("permUse2.apk", R.raw.keyset_permuse_sa_ua_ub,
                PackageManager.INSTALL_REPLACE_EXISTING, false, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        assertTrue("keyset permission not granted to app after upgrade to same sig",
                    pm.checkPermission(permName, pkgName)
                    == PackageManager.PERMISSION_GRANTED);
        cleanUpInstall(permPkgName);
        cleanUpInstall(pkgName);
    }

    /*
     * Check if an apk loses signature-level permission after changing to the a
     * new signature, from one which a permission should be granted.
     */
    public void testUpgradeSigPermLost() throws Exception {
        // install apk which defines permission
        installFromRawResource("permDef.apk", R.raw.keyset_permdef_sa_unone,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        // install apk which uses permission, signed by same sig
        installFromRawResource("permUse.apk", R.raw.keyset_permuse_sa_ua_ub,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        // verify that package does not have perm before
        PackageManager pm = getPm();
        String permPkgName = "com.android.frameworks.coretests.keysets_permdef";
        String pkgName = "com.android.frameworks.coretests.keysets";
        String permName = "com.android.frameworks.coretests.keysets_permdef.keyset_perm";
        assertTrue("keyset permission not granted to app with same sig",
                    pm.checkPermission(permName, pkgName)
                    == PackageManager.PERMISSION_GRANTED);
        // upgrade to apk without perm signature
        installFromRawResource("permUse2.apk", R.raw.keyset_permuse_sb_ua_ub,
                PackageManager.INSTALL_REPLACE_EXISTING, false, false, -1,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);

        assertFalse("keyset permission not revoked from app which upgraded to a "
                    + "different signature",
                    pm.checkPermission(permName, pkgName)
                    == PackageManager.PERMISSION_GRANTED);
        cleanUpInstall(permPkgName);
        cleanUpInstall(pkgName);
    }

    /**
     * The following tests are related to testing KeySets-based API
     */

    /*
     * testGetSigningKeySetNull - ensure getSigningKeySet() returns null on null
     * input and when calling a package other than that which made the call.
     */
    public void testGetSigningKeySet() throws Exception {
        PackageManager pm = getPm();
        String mPkgName = mContext.getPackageName();
        String otherPkgName = "com.android.frameworks.coretests.keysets_api";
        KeySet ks;
        try {
            ks = pm.getSigningKeySet(null);
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            ks = pm.getSigningKeySet("keysets.test.bogus.package");
            assertTrue(false); // should have thrown
        } catch (IllegalArgumentException e) {
        }
        final InstallParams ip = installFromRawResource("keysetApi.apk", R.raw.keyset_splat_api,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            ks = pm.getSigningKeySet(otherPkgName);
            assertTrue(false); // should have thrown
        } catch (SecurityException e) {
        } finally {
            cleanUpInstall(ip);
        }
        ks = pm.getSigningKeySet(mContext.getPackageName());
        assertNotNull(ks);
    }

    /*
     * testGetKeySetByAlias - same as getSigningKeySet, but for keysets defined
     * by this package.
     */
    public void testGetKeySetByAlias() throws Exception {
        PackageManager pm = getPm();
        String mPkgName = mContext.getPackageName();
        String otherPkgName = "com.android.frameworks.coretests.keysets_api";
        KeySet ks;
        try {
            ks = pm.getKeySetByAlias(null, null);
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            ks = pm.getKeySetByAlias(null, "keysetBogus");
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            ks = pm.getKeySetByAlias("keysets.test.bogus.package", null);
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            ks = pm.getKeySetByAlias("keysets.test.bogus.package", "A");
            assertTrue(false); // should have thrown
        } catch(IllegalArgumentException e) {
        }
        try {
            ks = pm.getKeySetByAlias(mPkgName, "keysetBogus");
            assertTrue(false); // should have thrown
        } catch(IllegalArgumentException e) {
        }

        // make sure we can get a KeySet from our pkg
        ks = pm.getKeySetByAlias(mPkgName, "A");
        assertNotNull(ks);

        // and another
        final InstallParams ip = installFromRawResource("keysetApi.apk", R.raw.keyset_splat_api,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            ks = pm.getKeySetByAlias(otherPkgName, "A");
            assertNotNull(ks);
        } finally {
            cleanUpInstall(ip);
        }
    }

    public void testIsSignedBy() throws Exception {
        PackageManager pm = getPm();
        String mPkgName = mContext.getPackageName();
        String otherPkgName = "com.android.frameworks.coretests.keysets_api";
        KeySet mSigningKS = pm.getSigningKeySet(mPkgName);
        KeySet mDefinedKS = pm.getKeySetByAlias(mPkgName, "A");

        try {
            assertFalse(pm.isSignedBy(null, null));
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            assertFalse(pm.isSignedBy(null, mSigningKS));
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            assertFalse(pm.isSignedBy(mPkgName, null));
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            assertFalse(pm.isSignedBy("keysets.test.bogus.package", mDefinedKS));
        } catch(IllegalArgumentException e) {
        }
        assertFalse(pm.isSignedBy(mPkgName, mDefinedKS));
        assertFalse(pm.isSignedBy(mPkgName, new KeySet(new Binder())));
        assertTrue(pm.isSignedBy(mPkgName, mSigningKS));

        final InstallParams ip1 = installFromRawResource("keysetApi.apk", R.raw.keyset_splat_api,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            assertFalse(pm.isSignedBy(otherPkgName, mDefinedKS));
            assertTrue(pm.isSignedBy(otherPkgName, mSigningKS));
        } finally {
            cleanUpInstall(ip1);
        }

        final InstallParams ip2 = installFromRawResource("keysetApi.apk", R.raw.keyset_splata_api,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            assertTrue(pm.isSignedBy(otherPkgName, mDefinedKS));
            assertTrue(pm.isSignedBy(otherPkgName, mSigningKS));
        } finally {
            cleanUpInstall(ip2);
        }
    }

    public void testIsSignedByExactly() throws Exception {
        PackageManager pm = getPm();
        String mPkgName = mContext.getPackageName();
        String otherPkgName = "com.android.frameworks.coretests.keysets_api";
        KeySet mSigningKS = pm.getSigningKeySet(mPkgName);
        KeySet mDefinedKS = pm.getKeySetByAlias(mPkgName, "A");
        try {
            assertFalse(pm.isSignedBy(null, null));
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            assertFalse(pm.isSignedBy(null, mSigningKS));
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            assertFalse(pm.isSignedBy(mPkgName, null));
            assertTrue(false); // should have thrown
        } catch (NullPointerException e) {
        }
        try {
            assertFalse(pm.isSignedByExactly("keysets.test.bogus.package", mDefinedKS));
        } catch(IllegalArgumentException e) {
        }
        assertFalse(pm.isSignedByExactly(mPkgName, mDefinedKS));
        assertFalse(pm.isSignedByExactly(mPkgName, new KeySet(new Binder())));
        assertTrue(pm.isSignedByExactly(mPkgName, mSigningKS));

        final InstallParams ip1 = installFromRawResource("keysetApi.apk", R.raw.keyset_splat_api,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            assertFalse(pm.isSignedByExactly(otherPkgName, mDefinedKS));
            assertTrue(pm.isSignedByExactly(otherPkgName, mSigningKS));
        } finally {
            cleanUpInstall(ip1);
        }

        final InstallParams ip2 = installFromRawResource("keysetApi.apk", R.raw.keyset_splata_api,
                0, false, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            assertFalse(pm.isSignedByExactly(otherPkgName, mDefinedKS));
            assertFalse(pm.isSignedByExactly(otherPkgName, mSigningKS));
        } finally {
            cleanUpInstall(ip2);
        }
    }



    /**
     * The following tests are related to testing the checkSignatures api.
     */
    private void checkSignatures(int apk1, int apk2, int expMatchResult) throws Exception {
        checkSharedSignatures(apk1, apk2, true, false, -1, expMatchResult);
    }

    @LargeTest
    public void testCheckSignaturesAllMatch() throws Exception {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT1_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
    }

    @LargeTest
    public void testCheckSignaturesNoMatch() throws Exception {
        int apk1 = APP1_CERT1;
        int apk2 = APP2_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }

    @LargeTest
    public void testCheckSignaturesSomeMatch1() throws Exception {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT1;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }

    @LargeTest
    public void testCheckSignaturesSomeMatch2() throws Exception {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }

    @LargeTest
    public void testCheckSignaturesMoreMatch() throws Exception {
        int apk1 = APP1_CERT1;
        int apk2 = APP2_CERT1_CERT2;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_NO_MATCH);
    }

    @LargeTest
    public void testCheckSignaturesUnknown() throws Exception {
        int apk1 = APP1_CERT1_CERT2;
        int apk2 = APP2_CERT1_CERT2;
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";

        final InstallParams ip = installFromRawResource(apk1Name, apk1,
                DEFAULT_INSTALL_FLAGS, false,
                false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        try {
            PackageManager pm = mContext.getPackageManager();
            // Delete app2
            File filesDir = mContext.getFilesDir();
            File outFile = new File(filesDir, apk2Name);
            int rawResId = apk2;
            Uri packageURI = getInstallablePackage(rawResId, outFile);
            var pkg = parsePackage(packageURI);
            try {
                getPi().uninstall(pkg.getPackageName(),
                        PackageManager.DELETE_ALL_USERS,
                        null /*statusReceiver*/);
            } catch (IllegalArgumentException ignore) {
            }
            // Check signatures now
            int match = mContext.getPackageManager().checkSignatures(
                    ip.pkg.getPackageName(), pkg.getPackageName());
            assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, match);
        } finally {
            cleanUpInstall(ip);
        }
    }

    @LargeTest
    public void testCheckSignaturesRotatedAgainstOriginal() throws Exception {
        // checkSignatures should be backwards compatible with pre-rotation behavior; this test
        // verifies that an app signed with a rotated key results in a signature match with an app
        // signed with the original key in the lineage.
        int apk1 = APP1_CERT5;
        int apk2 = APP2_CERT5_ROTATED_CERT6;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
    }

    @LargeTest
    public void testCheckSignaturesRotatedAgainstRotated() throws Exception {
        // checkSignatures should be successful when both apps have been signed with the same
        // rotated key since the initial signature comparison between the two apps should
        // return a match.
        int apk1 = APP1_CERT5_ROTATED_CERT6;
        int apk2 = APP2_CERT5_ROTATED_CERT6;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
    }

    @LargeTest
    public void testInstallNoCertificates() throws Exception {
        int apk1 = APP1_UNSIGNED;
        String apk1Name = "install1.apk";

        installFromRawResource(apk1Name, apk1, 0, false,
                true, PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    /*
     * The following tests are related to apps using shared uids signed with
     * different certs.
     */
    private int SHARED1_UNSIGNED = R.raw.install_shared1_unsigned;

    private int SHARED1_CERT1 = R.raw.install_shared1_cert1;

    private int SHARED1_CERT2 = R.raw.install_shared1_cert2;

    private int SHARED1_CERT1_CERT2 = R.raw.install_shared1_cert1_cert2;

    private int SHARED2_UNSIGNED = R.raw.install_shared2_unsigned;

    private int SHARED2_CERT1 = R.raw.install_shared2_cert1;

    private int SHARED2_CERT2 = R.raw.install_shared2_cert2;

    private int SHARED2_CERT1_CERT2 = R.raw.install_shared2_cert1_cert2;

    private void checkSharedSignatures(int apk1, int apk2, boolean cleanUp, boolean fail,
            int retCode, int expMatchResult) throws Exception {
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        var pkg1 = getParsedPackage(apk1Name, apk1);
        var pkg2 = getParsedPackage(apk2Name, apk2);

        try {
            // Clean up before testing first.
            cleanUpInstall(pkg1.getPackageName());
            cleanUpInstall(pkg2.getPackageName());
            installFromRawResource(apk1Name, apk1, DEFAULT_INSTALL_FLAGS, false, false, -1,
                    PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            if (fail) {
                installFromRawResource(apk2Name, apk2, DEFAULT_INSTALL_FLAGS, false, true, retCode,
                        PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            } else {
                installFromRawResource(apk2Name, apk2, DEFAULT_INSTALL_FLAGS, false, false, -1,
                        PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
                // TODO: All checkSignatures tests should return the same result regardless of
                // querying by package name or uid; however if there are any edge cases where
                // individual packages within a shareduid are compared with signatures that do not
                // match the full lineage of the shareduid this method should be overloaded to
                // accept the expected response for the uid query.
                PackageManager pm = getPm();
                int matchByName = pm.checkSignatures(pkg1.getPackageName(), pkg2.getPackageName());
                int pkg1Uid = pm.getApplicationInfo(pkg1.getPackageName(), 0).uid;
                int pkg2Uid = pm.getApplicationInfo(pkg2.getPackageName(), 0).uid;
                int matchByUid = pm.checkSignatures(pkg1Uid, pkg2Uid);
                assertEquals(expMatchResult, matchByName);
                assertEquals(expMatchResult, matchByUid);
            }
        } finally {
            if (cleanUp) {
                cleanUpInstall(pkg1.getPackageName());
                cleanUpInstall(pkg2.getPackageName());
            }
        }
    }

    @LargeTest
    public void testCheckSignaturesSharedAllMatch() throws Exception {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        boolean fail = false;
        int retCode = -1;
        int expMatchResult = PackageManager.SIGNATURE_MATCH;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }

    @LargeTest
    public void testCheckSignaturesSharedNoMatch() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT2;
        boolean fail = true;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        int expMatchResult = -1;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }

    /*
     * Test that an app signed with cert1 and cert2 cannot be replaced when
     * signed with cert1 alone.
     */
    @LargeTest
    public void testCheckSignaturesSharedSomeMatch1() throws Exception {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1;
        boolean fail = true;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        int expMatchResult = -1;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }

    /*
     * Test that an app signed with cert1 and cert2 cannot be replaced when
     * signed with cert2 alone.
     */
    @LargeTest
    public void testCheckSignaturesSharedSomeMatch2() throws Exception {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT2;
        boolean fail = true;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        int expMatchResult = -1;
        checkSharedSignatures(apk1, apk2, true, fail, retCode, expMatchResult);
    }

    @LargeTest
    public void testCheckSignaturesSharedUnknown() throws Exception {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        String apk1Name = "install1.apk";
        String apk2Name = "install2.apk";
        InstallParams ip1 = null;

        try {
            ip1 = installFromRawResource(apk1Name, apk1, DEFAULT_INSTALL_FLAGS, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            PackageManager pm = mContext.getPackageManager();
            // Delete app2
            var pkg = getParsedPackage(apk2Name, apk2);
            try {
                getPi().uninstall(pkg.getPackageName(), PackageManager.DELETE_ALL_USERS,
                        null /*statusReceiver*/);
            } catch (IllegalArgumentException ignore) {
            }
            // Check signatures now
            int match = mContext.getPackageManager().checkSignatures(
                    ip1.pkg.getPackageName(), pkg.getPackageName());
            assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, match);
        } finally {
            if (ip1 != null) {
                cleanUpInstall(ip1);
            }
        }
    }

    @LargeTest
    public void testReplaceFirstSharedMatchAllCerts() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk1 = SHARED1_CERT1;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
        replaceCerts(apk1, rapk1, true, false, -1);
    }

    @LargeTest
    public void testReplaceSecondSharedMatchAllCerts() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk2 = SHARED2_CERT1;
        checkSignatures(apk1, apk2, PackageManager.SIGNATURE_MATCH);
        replaceCerts(apk2, rapk2, true, false, -1);
    }

    @LargeTest
    public void testReplaceFirstSharedMatchSomeCerts() throws Exception {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        int rapk1 = SHARED1_CERT1;
        boolean fail = true;
        int flags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk1, flags, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testReplaceSecondSharedMatchSomeCerts() throws Exception {
        int apk1 = SHARED1_CERT1_CERT2;
        int apk2 = SHARED2_CERT1_CERT2;
        int rapk2 = SHARED2_CERT1;
        boolean fail = true;
        int flags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk2, flags, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testReplaceFirstSharedMatchNoCerts() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk1 = SHARED1_CERT2;
        boolean fail = true;
        int flags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk1, flags, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testReplaceSecondSharedMatchNoCerts() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk2 = SHARED2_CERT2;
        boolean fail = true;
        int flags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk2, flags, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testReplaceFirstSharedMatchMoreCerts() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk1 = SHARED1_CERT1_CERT2;
        boolean fail = true;
        int flags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk1, flags, true,
                fail, retCode, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testReplaceSecondSharedMatchMoreCerts() throws Exception {
        int apk1 = SHARED1_CERT1;
        int apk2 = SHARED2_CERT1;
        int rapk2 = SHARED2_CERT1_CERT2;
        boolean fail = true;
        int flags = DEFAULT_INSTALL_FLAGS | PackageManager.INSTALL_REPLACE_EXISTING;
        int retCode = PackageInstaller.STATUS_FAILURE_CONFLICT;
        checkSharedSignatures(apk1, apk2, false, false, -1, PackageManager.SIGNATURE_MATCH);
        installFromRawResource("install.apk", rapk2, flags, true,
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
    public void testUsesFeatureUnknownFeature() throws Exception {
        int retCode = PackageManager.INSTALL_SUCCEEDED;
        installFromRawResource("install.apk", R.raw.install_uses_feature, 0, true, false, retCode,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testInstallNonexistentFile() throws Exception {
        int retCode = PackageInstaller.STATUS_FAILURE_INVALID;
        File invalidFile = new File("/nonexistent-file.apk");
        invokeInstallPackageFail(Uri.fromFile(invalidFile), 0, retCode);
    }

    @SmallTest
    public void testGetVerifierDeviceIdentity() throws Exception {
        PackageManager pm = getPm();
        VerifierDeviceIdentity id = pm.getVerifierDeviceIdentity();

        assertNotNull("Verifier device identity should not be null", id);
    }

    public void testGetInstalledPackages() throws Exception {
        List<PackageInfo> packages = getPm().getInstalledPackages(0);
        assertNotNull("installed packages cannot be null", packages);
        assertTrue("installed packages cannot be empty", packages.size() > 0);
    }

    public void testGetUnInstalledPackages() throws Exception {
        List<PackageInfo> packages = getPm().getInstalledPackages(
                PackageManager.MATCH_UNINSTALLED_PACKAGES);
        assertNotNull("installed packages cannot be null", packages);
        assertTrue("installed packages cannot be empty", packages.size() > 0);
    }

    /**
     * Test that getInstalledPackages returns all the data specified in flags.
     */
    public void testGetInstalledPackagesAll() throws Exception {
        final int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_GIDS
                | PackageManager.GET_CONFIGURATIONS | PackageManager.GET_INSTRUMENTATION
                | PackageManager.GET_PERMISSIONS | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
                | PackageManager.GET_SIGNATURES | PackageManager.MATCH_UNINSTALLED_PACKAGES;

        final InstallParams ip =
                installFromRawResource("install.apk", R.raw.install_complete_package_info,
                        0 /*flags*/, false /*cleanUp*/, false /*fail*/, -1 /*result*/,
                        PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        try {
            final List<PackageInfo> packages = getPm().getInstalledPackages(flags);
            assertNotNull("installed packages cannot be null", packages);
            assertTrue("installed packages cannot be empty", packages.size() > 0);

            PackageInfo packageInfo = null;

            // Find the package with all components specified in the AndroidManifest
            // to ensure no null values
            for (PackageInfo pi : packages) {
                if ("com.android.frameworks.coretests.install_complete_package_info"
                        .equals(pi.packageName)) {
                    packageInfo = pi;
                    break;
                }
            }
            assertNotNull("activities should not be null", packageInfo.activities);
            assertNotNull("configPreferences should not be null", packageInfo.configPreferences);
            assertNotNull("instrumentation should not be null", packageInfo.instrumentation);
            assertNotNull("permissions should not be null", packageInfo.permissions);
            assertNotNull("providers should not be null", packageInfo.providers);
            assertNotNull("receivers should not be null", packageInfo.receivers);
            assertNotNull("services should not be null", packageInfo.services);
            assertNotNull("signatures should not be null", packageInfo.signatures);
        } finally {
            cleanUpInstall(ip);
        }
    }

    /**
     * Test that getInstalledPackages returns all the data specified in
     * flags when the GET_UNINSTALLED_PACKAGES flag is set.
     */
    public void testGetUnInstalledPackagesAll() throws Exception {
        final int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.GET_ACTIVITIES | PackageManager.GET_GIDS
                | PackageManager.GET_CONFIGURATIONS | PackageManager.GET_INSTRUMENTATION
                | PackageManager.GET_PERMISSIONS | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
                | PackageManager.GET_SIGNATURES;

        // first, install the package
        final InstallParams ip =
                installFromRawResource("install.apk", R.raw.install_complete_package_info,
                        0 /*flags*/, false /*cleanUp*/, false /*fail*/, -1 /*result*/,
                        PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        try {
            // then, remove it, keeping it's data around
            final GenericReceiver receiver = new DeleteReceiver(ip.pkg.getPackageName());
            invokeDeletePackage(ip.pkg.getPackageName(), PackageManager.DELETE_KEEP_DATA, receiver);

            final List<PackageInfo> packages = getPm().getInstalledPackages(flags);
            assertNotNull("installed packages cannot be null", packages);
            assertTrue("installed packages cannot be empty", packages.size() > 0);

            PackageInfo packageInfo = null;

            // Find the package with all components specified in the AndroidManifest
            // to ensure no null values
            for (PackageInfo pi : packages) {
                if ("com.android.frameworks.coretests.install_complete_package_info"
                        .equals(pi.packageName)) {
                    packageInfo = pi;
                    break;
                }
            }
            assertNotNull("applicationInfo should not be null", packageInfo.applicationInfo);
            assertNull("activities should be null", packageInfo.activities);
            assertNull("configPreferences should be null", packageInfo.configPreferences);
            assertNull("instrumentation should be null", packageInfo.instrumentation);
            assertNull("permissions should be null", packageInfo.permissions);
            assertNull("providers should be null", packageInfo.providers);
            assertNull("receivers should be null", packageInfo.receivers);
            assertNull("services should be null", packageInfo.services);
            assertNotNull("signingInfo should not be null", packageInfo.signingInfo);
        } finally {
            cleanUpInstall(ip);
        }
    }

    @Suppress
    public void testInstall_BadDex_CleanUp() throws Exception {
        int retCode = PackageInstaller.STATUS_FAILURE_INVALID;
        installFromRawResource("install.apk", R.raw.install_bad_dex, 0, true, true, retCode,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testMinInstallableTargetSdkPass() throws Exception {
        // Test installing a package that meets the minimum installable sdk requirement
        setMinInstallableTargetSdkFeatureFlags();
        int flags = PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
        installFromRawResource("install.apk", R.raw.install_target_sdk_23, flags,
                true, false /* fail */, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    @CddTest(requirements = {"3.1/C-0-8"})
    public void testMinInstallableTargetSdkFail() throws Exception {
        // Test installing a package that doesn't meet the minimum installable sdk requirement
        setMinInstallableTargetSdkFeatureFlags();
        int flags = 0;
        // Expect install to fail
        installFromRawResource("install.apk", R.raw.install_target_sdk_22, flags,
                true, true /* fail */, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    @LargeTest
    public void testMinInstallableTargetSdkBypass() throws Exception {
        // Test installing a package that doesn't meet the minimum installable sdk requirement
        setMinInstallableTargetSdkFeatureFlags();
        int flags = PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
        installFromRawResource("install.apk", R.raw.install_target_sdk_22, flags,
                true, false /* fail */, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    private void setMinInstallableTargetSdkFeatureFlags() {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                "MinInstallableTargetSdk__install_block_enabled",
                "true",
                false);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                "MinInstallableTargetSdk__min_installable_target_sdk",
                "23",
                false);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                "MinInstallableTargetSdk__install_block_strict_mode_enabled",
                "true",
                false);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                "MinInstallableTargetSdk__strict_mode_target_sdk",
                "23",
                false);
    }

    // Copied from com.android.server.pm.InstructionSets because we don't have access to it here.
    private static String[] getAppDexInstructionSets(ApplicationInfo info) {
        if (info.primaryCpuAbi != null) {
            if (info.secondaryCpuAbi != null) {
                return new String[] {
                        VMRuntime.getInstructionSet(info.primaryCpuAbi),
                        VMRuntime.getInstructionSet(info.secondaryCpuAbi) };
            } else {
                return new String[] {
                        VMRuntime.getInstructionSet(info.primaryCpuAbi) };
            }
        }

        return new String[] { VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]) };
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
